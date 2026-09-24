package widget.llm;

import widget.core.Env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini API 무료 티어용 클라이언트.
 *
 * 키는 프로젝트 루트의 .env 에서 읽는다: WIZ_1, WIZ_2 … 를 번호순으로 모아서 돌려 쓴다.
 * WIZ_ 로 시작하는 이름만 본다 — 다른 이름으로 적어두면 없는 것과 똑같다.
 * 키 발급: https://aistudio.google.com/apikey
 *
 * 모델은 .env 의 GEMINI_MODEL 로 덮어쓸 수 있다 (모델 이름 바뀌어도 재빌드 안 해도 되게).
 * 빠른 답변 모드는 GEMINI_FAST_MODEL 을 적어두면 그 모델로, 안 적어두면 같은 모델에
 * thinkingLevel=low 를 붙여서 부른다.
 */
public class GeminiClient implements LlmClient {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

  /** 위젯에서 쓰는 거라 답이 너무 길면 곤란해서 짧게 달라고 미리 깔아둠 */
  private static final String SYSTEM_HINT = """
      너는 데스크톱 런처 위젯 안에 들어있는 검색 도우미야.
      답은 짧고 바로 쓸 수 있게, 군더더기 인사말 없이 해줘.
      코드를 물어보면 ``` 코드 블록으로 감싸서 줘.
      답은 마크다운으로 써도 돼 — 제목, 목록, 굵게, 코드 블록 다 그려진다.
      어디서 본 문장을 그대로 옮기지 말고 항상 네 말로 새로 써.
      """;

  /** 빠른 모드일 때 시스템 프롬프트에 덧붙이는 문구 */
  private static final String FAST_HINT = """

      지금은 빠른 답변 모드야. 길게 따져보지 말고 결론부터 짧게 줘.""";

  /** RECITATION 으로 한 번 막혔을 때 프롬프트 뒤에 덧붙여서 다시 찔러보는 문구 */
  private static final String REPHRASE_HINT = """


      (방금 답이 원문 복사로 판정돼서 막혔어. 인용 없이, 표현을 완전히 바꿔서 네 말로 다시 설명해줘.)""";

  private static final String RECITATION_HELP = """
      답이 막혔어 (finishReason=RECITATION)

      구글이 "이 답은 학습 데이터를 그대로 읊는 것 같다"고 판단하면
      내용을 통째로 지우고 빈 응답만 보낸다. 우리 코드 문제가 아니라 서버쪽 필터라
      표현을 바꿔서 한 번 더 물어봤는데도 똑같이 막혔어.

      이렇게 하면 대개 풀려:
        - 유명한 코드/문서/가사를 "그대로" 달라는 요청은 피하기
        - "요약해서", "네 말로 다시 써서" 처럼 바꿔 쓰라고 하기
        - 내 상황(파일명, 에러 메시지 등)을 섞어서 더 구체적으로 묻기""";

  /**
   * 키가 전부 막혔을 때 메시지 끝에 붙이는 안내.
   * 이 메시지는 MarkdownView 로 그려져서 [글자](주소) 가 클릭되는 링크로 나온다.
   */
  private static final String KEY_PAGE_HELP = """


      키 상태 확인하거나 새로 발급: [aistudio.google.com/api-keys](https://aistudio.google.com/api-keys?hl=ko)""";

  /**
   * 화면에서 자른 그림의 글자를 읽어달라고 할 때 쓰는 말.
   * 설명을 곁들이지 말라고 못을 박는다 — 읽어낸 글자가 그대로 클립보드에 들어가서,
   * "이미지에 적힌 내용은 다음과 같습니다" 같은 인사말이 섞이면 붙여넣을 때 지워야 한다.
   */
  private static final String OCR_HINT = """
      이 그림에 보이는 글자를 그대로 옮겨 적어.
      - 설명·인사말·따옴표·코드블록 없이 글자만.
      - 줄바꿈과 읽는 순서는 그림에 있는 대로.
      - 글자가 하나도 없으면 %s 한 단어만 답해.""";

  /** 그림에 글자가 없을 때 모델이 돌려줄 약속된 말 (빈 답은 에러로 읽히니까 신호를 따로 둔다) */
  private static final String OCR_NOTHING = "NO_TEXT_FOUND";

  /** .env 에서 키를 긁어올 때 쓰는 이름 패턴 (WIZ_1, WIZ_2 …) */
  private static final Pattern WIZ_KEY = Pattern.compile("WIZ_(\\d{1,5})");

  /** keys 창에서 저장하면 reload() 가 통째로 갈아 끼운다 */
  private volatile List<ApiKey> apiKeys;
  /** 요청마다 한 칸씩 밀어서 한 키에 몰리지 않게 한다 */
  private final AtomicInteger cursor = new AtomicInteger();
  private final String normalModel;
  private final String fastModel;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public GeminiClient() {
    Map<String, String> env = Env.all();
    this.apiKeys = loadKeys(env);
    this.normalModel = firstNonBlank(env.get("GEMINI_MODEL"), DEFAULT_MODEL);
    // 빠른 모드용 모델을 따로 안 적어놨으면 같은 모델로 두고 생각만 줄인다
    this.fastModel = firstNonBlank(env.get("GEMINI_FAST_MODEL"), this.normalModel);
  }

  /** .env 를 새로 읽어서 키를 갈아 끼운다 — keys 창에서 저장한 게 다음 질문부터 먹게 */
  @Override
  public void reload() {
    apiKeys = loadKeys(Env.all());
  }

  /** 지금 .env 에 적힌 키 값들, 번호순. keys 창이 여기서 채운다 */
  public static List<String> readKeys() {
    List<String> values = new ArrayList<>();
    for (ApiKey key : loadKeys(Env.all()))
      values.add(key.value());
    return values;
  }

  /**
   * 키 목록을 .env 에 WIZ_1 부터 번호를 새로 매겨 적는다. 원래 있던 WIZ_ 줄은 다 걷어낸다.
   * 빈칸·겹치는 키는 뺀다. 다른 설정 줄은 안 건드린다 (Env.rewrite).
   */
  public static void writeKeys(List<String> values) throws IOException {
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String value : values) {
      String v = firstNonBlank(value);
      if (v != null)
        unique.add(v);
    }
    Map<String, String> entries = new LinkedHashMap<>();
    int n = 1;
    for (String value : unique)
      entries.put("WIZ_" + n++, value);
    Env.rewrite(name -> WIZ_KEY.matcher(name).matches(), entries);
  }

  /**
   * .env 의 WIZ_1, WIZ_2 … 를 번호순으로 모은다. 읽는 건 이 이름뿐이다.
   * 번호가 비어 있어도(WIZ_1, WIZ_3 만 있어도) 있는 것만 순서대로 쓴다.
   */
  private static List<ApiKey> loadKeys(Map<String, String> env) {
    List<Map.Entry<Integer, String>> numbered = new ArrayList<>();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      Matcher m = WIZ_KEY.matcher(entry.getKey());
      String value = firstNonBlank(entry.getValue());
      if (m.matches() && value != null)
        numbered.add(Map.entry(Integer.parseInt(m.group(1)), value));
    }
    numbered.sort(Map.Entry.comparingByKey());

    List<ApiKey> keys = new ArrayList<>();
    for (Map.Entry<Integer, String> entry : numbered)
      keys.add(new ApiKey("WIZ_" + entry.getKey(), entry.getValue()));

    return List.copyOf(keys);
  }

  /** 키 하나 — 실패했을 때 어느 키였는지 말해주려고 .env 이름을 같이 들고 다닌다 */
  private record ApiKey(String label, String value) {
  }

  /** 키 하나에 던져본 결과 한 줄 (WIZ_3 → 한도 초과) */
  private record Attempt(String label, String reason) {
  }

  /** 키를 다 돌려본 결과 — 마지막 응답과 실패한 키들의 기록 */
  private record Sent(HttpResponse<String> response, List<Attempt> attempts) {
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank())
        return v.trim();
    }
    return null;
  }

  @Override
  public boolean isReady() {
    return !apiKeys.isEmpty();
  }

  @Override
  public String unavailableReason() {
    return """
        .env 에 쓸 수 있는 키가 하나도 없어!

        1. https://aistudio.google.com/apikey 에서 무료 키 발급
        2. 프로젝트 루트(.env 파일)에 번호 붙여서 적기 (여러 개면 돌려 쓴다):
             WIZ_1=발급받은키
             WIZ_2=두번째키
        3. 또는 위젯에서 keys 를 쳐서 바로 넣기

        (WIZ_ 이름만 읽는다 — GEMINI_API_KEY 같은 건 안 본다)""";
  }

  @Override
  public String modelName(Mode mode) {
    return mode == Mode.FAST ? fastModel : normalModel;
  }

  @Override
  public String ask(String prompt, Mode mode) throws IOException, InterruptedException {
    return converse(SYSTEM_HINT, List.of(new Message(true, prompt)), mode);
  }

  @Override
  public String chat(String persona, List<Message> turns, Mode mode) throws IOException, InterruptedException {
    return converse(firstNonBlank(persona, SYSTEM_HINT), turns, mode);
  }

  /**
   * 그림 속 글자 읽기. 화면 캡쳐의 텍스트 모드가 부른다.
   *
   * 키 돌려쓰기·실패 안내는 ask() 와 똑같은 길(post·failed)을 탄다. 다른 건 보내는
   * 본문뿐이라 — 글자 대신 PNG 를 inlineData 로 실어 보낸다 — 여기서만 따로 짓는다.
   * 모델은 빠른 쪽을 쓴다. 읽어 옮기는 일엔 오래 생각할 게 없어서.
   */
  @Override
  public String ocr(byte[] png) throws IOException, InterruptedException {
    if (!isReady())
      throw new IllegalStateException(unavailableReason());
    if (png == null || png.length == 0)
      throw new IOException("읽을 그림이 비어 있어");

    // 생각은 최소로 — 그림에 적힌 걸 옮기는 일이라 오래 따져봐야 느려지기만 한다
    // (끄기 전엔 한 장 읽는 데 1분씩 걸렸다). 모르는 모델이면 400 이 오니 빼고 한 번 더.
    Sent sent = post(buildOcrBody(png, true), Mode.FAST);
    if (sent.response() != null && sent.response().statusCode() == 400
        && sent.response().body().contains("thinking"))
      sent = post(buildOcrBody(png, false), Mode.FAST);

    if (sent.response() == null || sent.response().statusCode() != 200)
      throw failed(sent, Mode.FAST);

    String text = extractText(sent.response().body());
    // 약속대로 "글자 없음" 이 오면 빈손으로 돌려준다 (부르는 쪽이 "안 보였어" 라고 말한다)
    return text.contains(OCR_NOTHING) ? "" : text;
  }

  private String buildOcrBody(byte[] png, boolean lowThinking) throws IOException {
    ObjectNode root = mapper.createObjectNode();

    ObjectNode turn = root.putArray("contents").addObject();
    turn.put("role", "user");
    ArrayNode parts = turn.putArray("parts");
    parts.addObject().put("text", String.format(OCR_HINT, OCR_NOTHING));
    ObjectNode inline = parts.addObject().putObject("inlineData");
    inline.put("mimeType", "image/png");
    inline.put("data", Base64.getEncoder().encodeToString(png));

    // 읽어 옮기는 일이라 상상할 자리를 줄인다
    ObjectNode config = root.putObject("generationConfig");
    config.put("temperature", 0);
    if (lowThinking)
      config.putObject("thinkingConfig").put("thinkingLevel", "low");
    return mapper.writeValueAsString(root);
  }

  /** ask() 도 chat() 도 결국 여기로 모인다 — 성격 한 덩어리 + 오간 말 목록 */
  private String converse(String system, List<Message> turns, Mode mode) throws IOException, InterruptedException {
    if (!isReady())
      throw new IllegalStateException(unavailableReason());

    try {
      return send(system, turns, false, mode);
    } catch (RecitationException first) {
      // 같은 프롬프트를 그대로 다시 넣으면 거의 똑같이 막히니까,
      // "네 말로 다시 써줘" 를 붙이고 온도도 올려서 한 번만 더 시도한다.
      try {
        return send(system, turns, true, mode);
      } catch (RecitationException second) {
        throw new IOException(RECITATION_HELP);
      }
    }
  }

  private String send(String system, List<Message> turns, boolean rephrase, Mode mode)
      throws IOException, InterruptedException {
    boolean lowThinking = mode == Mode.FAST;
    Sent sent = post(buildBody(system, turns, rephrase, mode, lowThinking), mode);

    // 키를 다 돌았는데 응답을 한 번도 못 받은 경우 (전부 연결 실패/타임아웃)
    if (sent.response() == null)
      throw failed(sent, mode);

    // thinkingConfig 를 모르는 모델이면 400 이 온다. 그럼 그것만 빼고 한 번 더.
    if (lowThinking && sent.response().statusCode() == 400 && sent.response().body().contains("thinking")) {
      sent = post(buildBody(system, turns, rephrase, mode, false), mode);
      if (sent.response() == null)
        throw failed(sent, mode);
    }

    if (sent.response().statusCode() != 200)
      throw failed(sent, mode);

    return extractText(sent.response().body());
  }

  /**
   * 키를 하나씩 돌려가며 던진다.
   * 요청마다 시작 키를 한 칸 밀고, 그 키가 실패하면 기다리지 않고 곧장 다음 키로 넘어간다
   * (한도/무효뿐 아니라 서버 오류나 연결 실패도 똑같이 다음 키로 — 키 하나 때문에 멈추지 않게).
   * 전부 막히면 마지막 응답과 키별 실패 사유를 같이 돌려줘서 위에서 메시지로 만들게 한다.
   * 응답을 한 번도 못 받았으면 response 는 null 로 온다.
   */
  private Sent post(String body, Mode mode) throws InterruptedException {
    // 도는 도중에 keys 창에서 갈아 끼워도 이번 한 바퀴는 처음 본 목록으로 끝낸다
    List<ApiKey> apiKeys = this.apiKeys;
    List<Attempt> attempts = new ArrayList<>();
    if (apiKeys.isEmpty())
      return new Sent(null, attempts);
    int start = Math.floorMod(cursor.getAndIncrement(), apiKeys.size());
    HttpResponse<String> last = null;

    for (int i = 0; i < apiKeys.size(); i++) {
      ApiKey key = apiKeys.get((start + i) % apiKeys.size());
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(String.format(ENDPOINT, modelName(mode))))
          .header("Content-Type", "application/json")
          .header("x-goog-api-key", key.value())
          .timeout(Duration.ofSeconds(60))
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();

      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        // 타임아웃/연결 끊김. 남은 키가 있으면 여기서 죽지 말고 바로 다음 키로 던져본다.
        String reason = transportReason(e);
        attempts.add(new Attempt(key.label(), reason));
        KeyHealth.failed(key.value(), reason);
        continue;
      }

      last = response;
      if (response.statusCode() == 200) {
        KeyHealth.ok(key.value());
        return new Sent(response, attempts);
      }

      String reason = reason(response);
      attempts.add(new Attempt(key.label(), reason));
      KeyHealth.failed(key.value(), reason);
      if (!shouldTryNextKey(response))
        break;
    }
    return new Sent(last, attempts);
  }

  /** 이 응답이 "키 문제" 라서 다음 키로 넘어가면 될 것인지 */
  private static boolean shouldTryNextKey(HttpResponse<String> response) {
    int code = response.statusCode();
    if (code == 429 || code == 401) // 한도 초과 / 키가 아예 안 먹힘
      return true;
    if (code >= 500) // 구글 쪽 일시적 오류 — 다른 키로 바로 한 번 더 찔러본다
      return true;
    if (code != 400 && code != 403)
      return false;
    // 키가 죽었거나 권한이 막힌 경우. 모델/요청 문제인 400 은 그대로 에러로 올린다.
    String body = response.body();
    return body.contains("API_KEY_INVALID")
        || body.contains("UNAUTHENTICATED")
        || body.contains("PERMISSION_DENIED")
        || body.contains("RESOURCE_EXHAUSTED");
  }

  /**
   * 실패한 응답을 한글 키워드로 바꾼다.
   * 구글이 주는 상태 문자열이 HTTP 코드보다 정확해서 본문을 먼저 본다.
   */
  private static String reason(HttpResponse<String> response) {
    String body = response.body() == null ? "" : response.body();
    int code = response.statusCode();

    if (body.contains("API_KEY_INVALID"))
      return "키 무효";
    if (code == 401 || body.contains("UNAUTHENTICATED"))
      return "인증 실패";
    if (code == 429 || body.contains("RESOURCE_EXHAUSTED"))
      return "한도 초과";
    if (code == 403 || body.contains("PERMISSION_DENIED"))
      return "권한 없음";
    if (code == 404 || body.contains("NOT_FOUND"))
      return "모델 없음";
    if (code >= 500)
      return "서버 오류";
    if (code == 400)
      return "요청 거부";
    return "HTTP " + code;
  }

  /** 응답도 못 받고 끊긴 경우 — 타임아웃인지 연결 자체가 안 된 건지만 구분한다 */
  private static String transportReason(IOException e) {
    return e instanceof HttpTimeoutException ? "응답 없음" : "연결 실패";
  }

  /** 키워드마다 붙여줄 한 줄 설명 — 모르는 키워드면 null */
  private static String help(String reason) {
    return switch (reason) {
      case "인증 실패" -> "키를 안 보고 OAuth 토큰/로그인 쿠키를 달라고 하는 상태야. 키 값에 따옴표·공백이 섞였는지, 만료된 키인지 확인해봐.";
      case "키 무효" -> "키 문자열 자체가 틀렸어. AI Studio 에서 다시 복사해서 .env 에 넣어.";
      case "한도 초과" -> "무료 티어 분당/일일 한도야. 조금 기다리거나 .env 에 키를 더 추가해.";
      case "권한 없음" -> "키는 살아있는데 이 모델/API 를 쓸 권한이 없어. 키가 붙은 구글 프로젝트를 확인해봐.";
      case "모델 없음" -> "모델 이름이 틀렸거나 이 키로는 못 보는 모델이야. .env 의 GEMINI_MODEL 을 고쳐봐.";
      case "서버 오류" -> "구글 쪽 문제야. 잠깐 뒤에 다시 물어봐.";
      case "요청 거부" -> "요청 형식을 구글이 거절했어. 모델 이름이나 프롬프트 길이를 의심해봐.";
      case "응답 없음" -> "60초 안에 답이 안 와서 접었어. 질문이 길면 짧게 잘라서 다시 물어봐.";
      case "연결 실패" -> "구글까지 연결이 안 됐어. 인터넷/프록시/방화벽을 확인해봐.";
      default -> null;
    };
  }

  /** 키를 다 돌려봐도 안 됐을 때 화면에 그대로 띄울 메시지 (raw 응답 대신 키 번호 + 한글 키워드) */
  private IOException failed(Sent sent, Mode mode) {
    List<Attempt> attempts = sent.attempts();
    StringBuilder sb = new StringBuilder();
    sb.append("키 ").append(attempts.size()).append("개 시도했는데 다 막혔어 (모델 ")
        .append(modelName(mode)).append(")\n");

    for (Attempt attempt : attempts)
      sb.append("\n  ").append(attempt.label()).append(" → ").append(attempt.reason());

    List<String> seen = new ArrayList<>();
    for (Attempt attempt : attempts) {
      if (seen.contains(attempt.reason()))
        continue;
      seen.add(attempt.reason());
      String help = help(attempt.reason());
      if (help != null)
        sb.append("\n\n").append(attempt.reason()).append(": ").append(help);
    }
    sb.append(KEY_PAGE_HELP);
    return new IOException(sb.toString());
  }

  /**
   * 빈 말을 걷어내고, 같은 쪽이 연달아 말한 건 한 덩어리로 붙인다.
   * 답을 못 받아서 내 말만 두 줄 쌓인 경우에도 user/model 이 번갈아 가게 하려고.
   */
  private static List<Message> merge(List<Message> turns) {
    List<Message> out = new ArrayList<>();
    for (Message message : turns) {
      if (message.text() == null || message.text().isBlank())
        continue;
      int last = out.size() - 1;
      if (last >= 0 && out.get(last).mine() == message.mine())
        out.set(last, new Message(message.mine(), out.get(last).text() + "\n" + message.text()));
      else
        out.add(message);
    }
    return out;
  }

  private String buildBody(String system, List<Message> turns, boolean rephrase, Mode mode, boolean lowThinking)
      throws IOException {
    ObjectNode root = mapper.createObjectNode();

    List<Message> merged = merge(turns);
    if (merged.isEmpty())
      throw new IOException("건넬 말이 하나도 없어");

    ArrayNode contents = root.putArray("contents");
    for (int i = 0; i < merged.size(); i++) {
      Message message = merged.get(i);
      String text = message.text();
      // "네 말로 다시 써줘" 는 맨 끝, 내가 한 말 뒤에만 붙인다
      if (rephrase && message.mine() && i == merged.size() - 1)
        text = text + REPHRASE_HINT;

      ObjectNode turn = contents.addObject();
      turn.put("role", message.mine() ? "user" : "model");
      turn.putArray("parts").addObject().put("text", text);
    }

    root.putObject("systemInstruction")
        .putArray("parts").addObject()
        .put("text", mode == Mode.FAST ? system + FAST_HINT : system);

    ObjectNode config = mapper.createObjectNode();
    // 재시도는 좀 더 흔들어야 같은 문장으로 안 돌아온다
    if (rephrase)
      config.put("temperature", 1.3);
    // 빠른 모드: 생각(thinking)을 최소로 — 이게 체감 속도를 제일 많이 줄인다
    if (lowThinking)
      config.putObject("thinkingConfig").put("thinkingLevel", "low");
    if (!config.isEmpty())
      root.set("generationConfig", config);

    return mapper.writeValueAsString(root);
  }

  private String extractText(String body) throws IOException {
    JsonNode root = mapper.readTree(body);

    JsonNode candidates = root.path("candidates");
    if (!candidates.isArray() || candidates.isEmpty()) {
      // 안전필터에 걸리면 candidates 없이 promptFeedback 만 온다
      String blockReason = root.path("promptFeedback").path("blockReason").asText("");
      throw new IOException(blockReason.isEmpty()
          ? "답이 통째로 비어서 왔어 (candidates 없음)"
          : "요청이 차단됐어 (" + blockReason + ")");
    }

    JsonNode candidate = candidates.get(0);
    StringBuilder sb = new StringBuilder();
    for (JsonNode part : candidate.path("content").path("parts")) {
      String text = part.path("text").asText("");
      if (!text.isEmpty())
        sb.append(text);
    }

    if (sb.isEmpty()) {
      String finishReason = candidate.path("finishReason").asText("");
      throw emptyAnswer(finishReason);
    }
    return sb.toString().trim();
  }

  /**
   * 내용 없이 finishReason 만 온 경우. RECITATION 은 재시도 대상이라 따로 구분한다.
   */
  private static IOException emptyAnswer(String finishReason) {
    switch (finishReason) {
      case "RECITATION":
        return new RecitationException();
      case "SAFETY":
        return new IOException("""
            답이 안전필터에 걸려서 지워졌어 (finishReason=SAFETY)
            질문 표현을 좀 순하게 바꿔서 다시 해봐.""");
      case "MAX_TOKENS":
        return new IOException("""
            생각하다가 길이 제한에 먼저 걸려서 본문이 안 왔어 (finishReason=MAX_TOKENS)
            질문을 잘게 쪼개서 물어보면 나온다.""");
      default:
        return new IOException("답이 비어서 왔어"
            + (finishReason.isEmpty() ? "" : " (finishReason=" + finishReason + ")"));
    }
  }

  /** RECITATION 전용 — ask() 에서 이것만 잡아서 한 번 재시도한다 */
  private static class RecitationException extends IOException {
    RecitationException() {
      super(RECITATION_HELP);
    }
  }
}
