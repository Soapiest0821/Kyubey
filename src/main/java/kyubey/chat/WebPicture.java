package kyubey.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마도카가 웹에서 사진 한 장을 가져와 보내는 길.
 *
 * 토큰을 아끼려고 LLM 을 따로 부르지 않는다. 내 말이 정규식(ASK)에 걸린 턴에만 성격문에 안내(GUIDE)를
 * 붙이고, 마도카는 그 턴의 답 끝에 [[사진:영어 검색어|한글 설명]] 을 단다. 안 걸린 턴엔 한 글자도 더 안 간다.
 * 정규식이 잘못 걸려도 마도카가 표시를 안 달면 그만이라, 손해는 안내 몇 줄 값뿐이다.
 *
 * 설명은 사진 줄의 caption 으로 남겨서 ChatImage 의 "한 번 읽기" 를 건너뛴다 — 보낼 때도 토큰이 안 든다.
 * 대신 마도카가 아는 건 찾으려던 사진이지 실제로 온 사진이 아니다.
 *
 * 사진은 위키미디어 공용에서 찾는다. 키 없이 쓸 수 있는 공식 API 중에 영어 검색어로 찾으면 제일 잘 맞는다.
 * 빙 · 덕덕고 이미지를 세션 없이 긁으면 치즈케이크에 색칠공부가 나오는 식으로 엉뚱한 결과가 와서 안 쓴다.
 * 한글 검색어는 거의 안 나와서 검색어는 영어로 받는다. 세이프서치가 없으니 AVOID 로 거른다.
 *
 * 마도카 본인이나 애니 캐릭터는 위키미디어에 없어서 [[그림:태그|한글 설명]] 으로 받아 세이프부루에서 찾는다.
 * 다들 중학생 캐릭터라 전 연령(general) 말고는 절대 안 보낸다. safe 까지 넓히지 말 것 — 범위가 넓고 사이트에
 * questionable 도 섞여 있다. 그래서 등급은 안내문에 안 맡기고 검색과 응답 양쪽에서 코드로 막는다 (safeTags, searchArt).
 */
final class WebPicture {

  /**
   * 찾아서 저장까지 끝낸 사진.
   *
   * @param search 찾을 때 쓴 조건 — 줄에 남겨서 "사진 다시 가져오기" 가 같은 조건으로 찾는다 (refetch)
   */
  record Found(String path, String caption, String search) {
  }

  /**
   * 표시를 뗀 답과 그에 딸린 사진.
   *
   * @param missed 찾으려다 못 찾았는지 — 마도카가 보낸다고 말했는데 사진이 없는 자리라 화면에 알려야 한다
   */
  record Reply(String text, Found picture, boolean missed) {
  }

  /** 사진을 찾으려다 못 찾았을 때 말풍선에 남기는 글. 다음 턴 마도카도 이걸 읽고 안 간 걸 안다 */
  static final String MISSED = "(사진을 못 찾았어)";

  /** "강아지 사진 귀여운 걸로 하나 보내줘" 처럼 사진 말과 달라는 말 사이에 낄 수 있는 글자 수. 말줄임표(..)는 넘어간다 */
  private static final String GAP = "[^!?\\n]{0,12}?";
  private static final String NOUN = "(?:사진|이미지|그림|움짤|짤방?|셀카|셀피|프사|실물|얼굴|생김새|[가-힣]{0,3}샷|pics?|photo)";
  /** 달라는 어미. 보내줄게 · 보내줬어 처럼 내가 준다는 꼴은 일부러 뺐다 */
  private static final String PLEASE = "(?:줘|줭|주라|주셈|주삼|주쇼|주세요|줄래|주면|주실|주지|달라|봐|와|조|죠)";
  /** 어떤 말이 끼어 있어도 달라는 뜻인 말 */
  private static final String GIVE = "(?:(?:보내|보여|올려|찾아|구해|가져|갖다|띄워|던져|풀어|찍어)\\s*(?:다\\s*)?" + PLEASE
      + "|달라|내놔|내놓|보자|볼래|볼까|보고\\s*싶|보구\\s*싶|구경)";
  /**
   * 달라는 꼴은 아니어도 사진 말 뒤에 오면 달라는 뜻인 말 — "오늘도 셀카 찍어왓어?", "사진 가져왔어?", "셀카 어딨어?".
   * "사진 찍으러 갔어" 도 걸리지만 그땐 표시를 안 달면 그만이다
   */
  private static final String TOOK = "(?:찍|(?:가져|갖고|구해|챙겨)\\s*[왔왓온]|보[냈냇]|어[딨딧]|어디\\s*[있잇])";
  /** "사진 줘" 처럼 사진 말 바로 뒤라야 달라는 뜻인 말 — 거리를 두면 "사진 설명해줘" 까지 걸린다 */
  private static final String NEAR = "(?:\\s|을|를|은|는|이나|도|만|좀|더|하나|한\\s*장|몇\\s*장)*"
      + "(?:줘|줭|주라|주셈|주삼|주쇼|주세요|줄래|ㄱ+|없어|없엉|없냐|없음|업냐|있어\\?|있냐|있음|잇어|잇엉|잇냐|잇음)";

  /**
   * 사진을 달라는 말. 대충 흘려 말하는 걸 받으려고 일부러 넓게 잡았다 — 오타 섞인 말, 사진 말이 없는
   * "얼마나 잘생겼는지좀 보자", 맨몸의 "보여줘" 까지 건다. 잘못 걸려도 마도카가 표시를 안 달면 그만이고
   * 손해는 안내 몇 줄뿐인데, 놓치면 사진을 달라는 말을 그냥 흘려버리니까 놓치는 쪽이 더 비싸다.
   */
  private static final Pattern ASK = Pattern.compile(
      NOUN + GAP + GIVE
          + "|" + NOUN + GAP + TOOK
          + "|" + NOUN + "\\s*(?:만|이나|이라도|라도)\\s*보고"
          + "|" + NOUN + NEAR
          + "|" + NOUN + "\\s*(?:은|는)?\\s*(?:좀|\\?+)\\s*$"
          + "|" + GIVE + "[^!?\\n]{0,10}?" + NOUN
          + "|(?:보여|보내)\\s*" + PLEASE
          + "|(?:어떻게|어케|어캐|얼마나)[^!?\\n]{0,6}?생[겼겻기긴]"
          + "|생[겼겻][^!?\\n]{0,8}?(?:보자|볼래|보여|봐)",
      Pattern.CASE_INSENSITIVE);

  /** 최근 대화가 사진 얘기였는지 가르는 말. 사진 줄의 글(ChatImage.TEXT)도 여기 걸린다 */
  private static final Pattern PICTURE_TALK = Pattern.compile(
      "사진|사쥔|이미지|그림|움짤|짤방?|셀카|셀피|프사|얼빡샷|인증샷|pics?|photo", Pattern.CASE_INSENSITIVE);

  /**
   * 사진 말 없이 이어서 조르는 말 — "더 줘봐..", "줄래?", "안 줄거야?", "부탁이야..".
   * 이 말만으론 뭘 달라는 건지 몰라서 최근 대화가 사진 얘기였을 때만 친다 (asked).
   * 줘 앞에 다른 글자가 붙은 "설명해줘" 는 안 받고, 줄게 · 줬어 처럼 내가 준다는 꼴도 안 받는다.
   */
  private static final Pattern BEGGING = Pattern.compile(
      "(?:올려|찾아|구해|가져|갖다|띄워|던져)\\s*(?:다\\s*)?" + PLEASE
          + "|내놔|내놓|달라|부탁|제발|플리즈|plz|pls"
          + "|(?:(?<![가-힣])|(?<=[더좀]))(?:줘|줭|주라|주셈|주삼|주쇼|주세요|줄래|줄\\s*수)"
          + "|안\\s*줄|주면\\s*안|(?:하나|한\\s*장)\\s*더|더\\s*(?:있|잇|없)",
      Pattern.CASE_INSENSITIVE);

  /**
   * 답에 다는 표시 — [[사진:cute kitten sleeping|이불에서 자는 아기 고양이 사진]], [[그림:kaname_madoka smile|웃는 내 얼굴]].
   * 호감도 표시처럼 어디 있든 찾는다
   */
  private static final Pattern MARK = Pattern.compile(
      "\\[\\[\\s*(사진|그림)\\s*[:：]\\s*([^\\]|｜]*?)\\s*(?:[|｜]\\s*([^\\]]*?)\\s*)?\\]\\]");

  /** 걸린 턴마다 값을 치르는 글이다. 코드로 막을 수 있는 건 여기 적지 말 것 (등급, 작품, 머리색, 혼자 나오기) */
  private static final String GUIDE = """

      [사진 보내기]
      사진을 달라는 말이면("셀카 찍었어?"도) 답 끝에 표시 하나를 달아. 없으면 사진이 안 가. 장난이나 사진 얘기뿐이면 달지 마.
      - 실물: [[사진:영어 2~5단어|한글 설명]] 맨 앞은 대상 명사. 예: capybara swimming water
      - 너(셀카 포함), 마케몬 캐릭터: [[그림:태그|한글 설명]] 맨 앞은 캐릭터 태그. 예: {나}
        태그: kohaku_(makemon!) devilun_(makemon!)
      - "더 줘"는 앞 사진과 비슷한 걸로. 다른 작품이나 야한 그림은 표시 없이 네 말투로 넘겨. 앱, 검색, 만화 얘기는 꺼내지 마.
      """;

  /**
   * 안내 없이 사진 얘기를 받으면 모델이 표시 대신 "[사진 파일: 호무라 사진]" 같은 글을 지어낸다.
   * 대화 기록의 "[내가 사진을 보냄: ...]" 을 흉내 내는 것 — 글자로 보이면 곤란하니 표시처럼 받아서 자기 그림을 찾는다.
   */
  private static final Pattern FAKE = Pattern.compile(
      "(?<!\\[)\\[\\s*(?:내가\\s+)?사진[^\\[\\]\\n:：]*(?:[:：]\\s*([^\\[\\]\\n]*?))?\\s*\\](?!\\])");

  private static final String API = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
      + "&generator=search&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=1024&gsrsearch=";

  /** 그림 파일 중 사진이 많은 JPEG 만, 그리고 뜬금없이 튀어나오면 곤란한 것들을 뺀다 */
  private static final String AVOID = " filemime:image/jpeg"
      + " -nude -naked -nudity -erotic -sex -genitalia -penis -vagina"
      + " -corpse -autopsy -surgery -wound -skeleton -taxidermy -specimen -dissection -anatomy"
      + " -diagram -map -logo -chart -scan";

  private static final String ART_API = "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&limit=40&tags=";

  /**
   * 그림에서 뺄 태그. 윗줄들은 전 연령 등급에 섞여도 안 보낼 것들, 아랫줄들은 그림이 아니라 엉뚱한 게 오는 것들 —
   * 안 빼면 마도카를 찾았는데 굿즈숍 합성 사진이나 스프라이트 모음이 온다.
   */
  private static final Set<String> ART_BLOCK = Set.of(
      "swimsuit", "bikini", "underwear", "panties", "bra", "lingerie", "nude", "naked", "topless", "bottomless",
      "cleavage", "breasts", "large_breasts", "ass", "pantyshot", "upskirt", "skirt_lift", "bathing", "bath", "onsen",
      "towel", "bandaid", "blood", "guro", "injury", "death",
      "photo_(medium)", "photo_background", "comic", "4koma", "sprite", "sprite_sheet", "pixel_art", "chart",
      "character_sheet", "reference_sheet", "collage", "crossover", "multiple_crossover", "meme", "text_focus",
      "speech_bubble", "english_text", "translated", "shop", "figure", "toy_packaging", "parody", "cosplay",
      "no_humans", "food_focus", "object_focus");

  /**
   * 캐릭터 태그와 그 캐릭터의 머리색. 캐릭터 태그만 믿으면 마도카를 찾았는데 검은 머리 변형 그림이 온다 —
   * 마도카 상위 100장 중 11장이 분홍 머리가 아니었다. 한 명만 찾을 땐 머리색까지 맞아야 한다 (artSearch).
   * 쿄코는 머리색 태그까지 걸면 한두 장만 남아서 뺐다.
   */
  private static final Map<String, String> HAIR = Map.of();

  /** 머리색 없이 찾는 등장인물 */
  private static final Set<String> OTHERS = Set.of("kohaku_(makemon!)", "devilun_(makemon!)");

  /**
   * 작품 태그. 모든 그림 검색에 넣고 응답에서도 본다. 캐릭터 태그는 98% 넘게 이 태그가 함께 달려 있어 놓치는 게 거의 없다.
   * 모델이 캐릭터 태그를 빼먹거나 한글로 적으면 남은 태그만으로 찾아서 딴 작품 그림이 왔다 — 호무라 사진에 원신이 온 적이 있다.
   */
  private static final String WORK = "makemon!";

  /** 캐릭터 태그 하나만 믿지 않게 태그는 이만큼만 받는다 */
  private static final int MAX_TAGS = 3;
  /** 점수 높은 그림 중 이만큼에서 무작위로 고른다. 늘 1등만 보내면 같은 그림이 되풀이된다 */
  private static final int ART_POOL = 15;
  private static final int MIN_ART_WIDTH = 500;
  /** 가로세로가 이보다 길쭉한 그림은 말풍선에서 띠처럼 보인다 */
  private static final double MAX_RATIO = 2.5;

  /** 위키미디어는 클라이언트 이름을 밝히라고 한다 */
  private static final String AGENT = "Makeit/1.0 (desktop chat widget)";

  /** 이보다 좁은 그림은 말풍선에서 뭉개진다 */
  private static final int MIN_WIDTH = 640;
  private static final int MAX_BYTES = 8 * 1024 * 1024;
  /** 검색어가 빡빡해서 결과가 없을 때 끝 단어를 몇 번까지 덜어볼지. 맨 앞 낱말은 끝까지 남는다 */
  private static final int LOOSEN = 4;
  /** 받기나 그림 읽기에 실패했을 때 다음 후보를 몇 장까지 볼지. 보내는 건 언제나 한 장이다 */
  private static final int TRIES = 3;
  private static final int MAX_CAPTION = 80;

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();

  private WebPicture() {
  }

  /**
   * 사진을 달라는 말인지. 네트워크도 LLM 도 안 타니 말할 때마다 불러도 된다.
   *
   * @param text 아직 답을 못 받은 내 말
   * @param recent 최근 대화 — 사진 얘기 중이면 "보내줘.." 처럼 사진 말이 없는 말도 달라는 말로 친다
   */
  static boolean asked(String text, String recent) {
    return ASK.matcher(text).find()
        || BEGGING.matcher(text).find() && PICTURE_TALK.matcher(recent).find();
  }

  /** 사진을 달라고 한 턴이면 성격문에 안내를 붙인다 */
  static String withGuide(String persona, boolean asked, ChatProfile who) {
    return asked ? persona.stripTrailing() + "\n" + GUIDE.replace("{나}", characterTag(who)) : persona;
  }

  /** 이 상대의 그림 태그 */
  private static String characterTag(ChatProfile who) {
    return switch (who) {
      case MAKEMON -> "kohaku_(makemon!)";
    };
  }

  /** 사진을 보낸 척 글자만 적은 답인지 (FAKE). 달라는 말을 못 알아본 턴에도 사진을 보내려고 본다 */
  static boolean faked(String raw) {
    return raw != null && FAKE.matcher(raw).find();
  }

  /**
   * 답에서 사진 표시를 떼고, 달라고 한 턴이면 사진을 찾아 저장한다. 표시는 화면에도 기록에도 안 남는다.
   * 네트워크를 타니 JavaFX 스레드에서 부르지 말 것. 못 찾아도 예외를 안 던진다 — 말까지 버릴 일은 아니라서.
   *
   * @param sent 이번 실행에서 이미 보낸 사진. "하나 더 줘" 에 같은 사진이 안 가게 거른다
   */
  static Reply resolve(String raw, boolean asked, String dir, Set<String> sent, ChatProfile who) {
    if (raw == null)
      return new Reply(null, null, false);
    Matcher m = MARK.matcher(raw);
    Matcher fake = FAKE.matcher(raw);
    boolean marked = m.find();
    boolean faked = fake.find();
    if (!marked && !faked)
      return new Reply(raw, null, false);

    boolean art = !marked || m.group(1).equals("그림");
    String query = !marked ? characterTag(who) : art ? safeTags(m.group(2)) : m.group(2).strip();
    String said = marked ? m.group(3) : fake.group(1);
    String caption = said == null ? "" : said.strip();
    String text = FAKE.matcher(MARK.matcher(raw).replaceAll("")).replaceAll("");
    if (art && query.isEmpty())
      query = characterTag(who);
    if (!asked || query.isEmpty())
      return new Reply(text, null, false);

    if (caption.isEmpty())
      caption = query;
    if (caption.length() > MAX_CAPTION)
      caption = caption.substring(0, MAX_CAPTION);
    try {
      String search = (art ? ART_MARK : PHOTO_MARK) + query;
      System.err.println("[chat] 사진 찾기: " + search);
      String path = find(query, art, dir, sent, who);
      return path == null ? new Reply(text, null, true) : new Reply(text, new Found(path, caption, search), false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException e) {
      System.err.println("[chat] 사진 찾기 실패: " + e);
    }
    return new Reply(text, null, true);
  }

  /**
   * 모델이 적은 태그에서 평범한 태그만 남긴다. rating:explicit 이나 -rating:general 처럼 검색 조건을
   * 바꾸는 말이 끼면 등급 고정이 뚫리니, 콜론이 든 말과 - 로 시작하는 말은 태그로 치지 않는다.
   */
  static String safeTags(String raw) {
    List<String> tags = new ArrayList<>();
    for (String tag : raw.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
      if (tag.matches("[a-z0-9][a-z0-9_()'.!-]*") && !ART_BLOCK.contains(tag) && tags.size() < MAX_TAGS)
        tags.add(tag);
    }
    return String.join(" ", tags);
  }

  /** 줄에 남기는 검색 조건 앞머리 */
  private static final String ART_MARK = "그림:";
  private static final String PHOTO_MARK = "사진:";

  /**
   * 같은 조건으로 다른 사진을 찾는다. LLM 을 안 부르니 토큰이 안 든다.
   * 조건을 안 남기던 때 받은 사진(search 가 null)은 이 상대의 그림으로 찾는다. 없으면 null.
   * 네트워크를 타니 JavaFX 스레드에서 부르지 말 것.
   */
  static String refetch(String search, ChatProfile who, String dir, Set<String> sent)
      throws IOException, InterruptedException {
    if (search == null || search.isBlank())
      return find(characterTag(who), true, dir, sent, who);
    boolean art = search.startsWith(ART_MARK);
    return find(search.substring(ART_MARK.length()), art, dir, sent, who);
  }

  /**
   * 프로필 사진으로 쓸 이 상대의 얼굴 그림을 한 장 받는다 (ChatAvatar). 없으면 null.
   * portrait 라 artSearch 가 혼자 나온 그림, 맞는 머리색, 눈을 맞춘 그림으로 좁혀준다.
   * 네트워크를 타니 JavaFX 스레드에서 부르지 말 것.
   */
  static String avatar(ChatProfile who, String dir, Set<String> sent) throws IOException, InterruptedException {
    return find(characterTag(who) + " solo", true, dir, sent, who);
  }

  /** 맞는 사진을 한 장 받아 저장하고 파일 자리를 돌려준다. 없으면 null */
  private static String find(String query, boolean art, String dir, Set<String> sent, ChatProfile who)
      throws IOException, InterruptedException {
    String path = findOnce(query, art, dir, sent, who);
    // 코하쿠 그림은 몇 장 안 돼서 금방 다 보낸다. 다 보냈으면 처음부터 다시 돌려 쓴다
    if (path == null && art && !sent.isEmpty()) {
      sent.clear();
      path = findOnce(query, art, dir, sent, who);
    }
    return path;
  }

  private static String findOnce(String query, boolean art, String dir, Set<String> sent, ChatProfile who)
      throws IOException, InterruptedException {
    String words = query;
    for (int round = 0; round <= LOOSEN; round++) {
      List<Candidate> found = art ? searchArt(words, sent, who) : search(words, sent);
      for (Candidate candidate : found.subList(0, Math.min(TRIES, found.size()))) {
        String path = save(candidate, dir);
        if (path != null) {
          sent.add(candidate.url);
          return path;
        }
      }
      int space = words.lastIndexOf(' ');
      if (space < 0)
        break;
      words = words.substring(0, space).strip();
    }
    return null;
  }

  private record Candidate(String url, int score, int order) {
  }

  /** 검색어 낱말이 파일 이름에 많이 든 것부터. 같으면 위키미디어가 준 순서대로다 */
  private static List<Candidate> search(String words, Set<String> sent) throws IOException, InterruptedException {
    // 맨 앞 낱말은 파일 이름에 꼭 있어야 한다. 설명까지 뒤지게 두면 카피바라를 찾았는데 아나콘다가 온다
    String url = API + URLEncoder.encode("intitle:" + words + AVOID, StandardCharsets.UTF_8);
    HttpResponse<String> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200)
      throw new IOException("위키미디어 응답 " + response.statusCode());

    List<Candidate> out = new ArrayList<>();
    for (JsonNode page : JSON.readTree(response.body()).path("query").path("pages")) {
      JsonNode info = page.path("imageinfo").path(0);
      String image = info.path("thumburl").asText("");
      if (image.isEmpty() || info.path("width").asInt() < MIN_WIDTH || sent.contains(image))
        continue;
      out.add(new Candidate(image, score(page.path("title").asText(""), words), page.path("index").asInt()));
    }
    out.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(Candidate::order));
    return out;
  }

  /**
   * 세이프부루에서 전 연령 그림만 찾는다. 점수 높은 것부터 ART_POOL 장 안에서 순서를 섞는다.
   * 등급과 뺄 태그는 검색 조건에 넣고 응답에서도 또 본다 — 검색 조건만 믿으면 사이트 쪽 태그가 늦게 달린 그림이 샌다.
   */
  private static List<Candidate> searchArt(String tags, Set<String> sent, ChatProfile who)
      throws IOException, InterruptedException {
    StringBuilder search = new StringBuilder(artSearch(tags, who)).append(" rating:general sort:score:desc");
    for (String tag : ART_BLOCK)
      search.append(" -").append(tag);
    String url = ART_API + URLEncoder.encode(search.toString(), StandardCharsets.UTF_8);
    HttpResponse<String> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200)
      throw new IOException("세이프부루 응답 " + response.statusCode());
    // 결과가 없으면 빈 몸으로 온다
    if (response.body().isBlank())
      return new ArrayList<>();

    List<Candidate> out = new ArrayList<>();
    for (JsonNode post : JSON.readTree(response.body())) {
      String image = post.path(post.path("sample").asBoolean() ? "sample_url" : "file_url").asText("");
      if (image.isEmpty() || sent.contains(image) || !usable(post))
        continue;
      out.add(new Candidate(image, 0, out.size()));
    }
    Collections.shuffle(out.subList(0, Math.min(ART_POOL, out.size())));
    return out;
  }

  /**
   * 모델이 적은 태그를 실제 검색 조건으로 넓힌다. 캐릭터가 한 명이면 그 캐릭터 혼자 나온 그림만, 머리색까지 맞게 —
   * 안 그러면 잠깐 끼어 있을 뿐인 단체 그림이나 변형 그림이 온다. 둘 이상이면 같이 나오는 그림이라 혼자로 안 좁힌다.
   * 등장인물이 하나도 없으면 말하는 상대(who)로 찾고, 어떤 경우든 작품 태그가 붙는다.
   */
  static String artSearch(String tags, ChatProfile who) {
    List<String> words = new ArrayList<>(List.of(tags.split(" ")));
    int cast = (int) words.stream().filter(w -> HAIR.containsKey(w) || OTHERS.contains(w)).count();
    if (cast == 0) {
      words.add(0, characterTag(who));
      cast = 1;
    }
    words.add(1, WORK);
    if (cast > 1)
      return String.join(" ", words);
    words.stream().map(HAIR::get).filter(Objects::nonNull).findFirst().ifPresent(words::add);
    // 얼굴을 달라는 말인데 portrait 만으론 눈을 가린 그림이나 추상화도 와서, 눈을 맞춘 그림으로 좁힌다
    if (words.contains("portrait"))
      words.add("looking_at_viewer");
    return String.join(" ", words);
  }

  /** 등급, 태그, 파일 꼴, 크기를 응답에서 다시 본다 */
  private static boolean usable(JsonNode post) {
    if (!post.path("rating").asText("").equalsIgnoreCase("general"))
      return false;
    Set<String> tags = Set.copyOf(new HashSet<>(List.of(post.path("tags").asText("").strip().split("\\s+"))));
    if (!tags.contains(WORK))
      return false;
    for (String tag : tags) {
      if (ART_BLOCK.contains(tag) || tag.endsWith("_(meme)"))
        return false;
    }
    if (!post.path("file_url").asText("").toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)(\\?.*)?"))
      return false;
    double width = post.path("width").asDouble();
    double height = post.path("height").asDouble();
    return width >= MIN_ART_WIDTH && height > 0 && Math.max(width / height, height / width) <= MAX_RATIO;
  }

  /** 검색어 낱말이 제목에 몇 개 들었는지. on · of 같은 짧은 말은 안 센다 */
  private static int score(String title, String words) {
    String lower = title.toLowerCase(Locale.ROOT);
    int hits = 0;
    for (String word : words.toLowerCase(Locale.ROOT).split("\\s+")) {
      if (word.length() >= 3 && lower.contains(word))
        hits++;
    }
    return hits;
  }

  /** 받아서 줄여 저장한다. 이 후보를 못 쓰면 null — 부르는 쪽이 다음 후보로 간다 */
  private static String save(Candidate candidate, String dir) throws InterruptedException {
    try {
      HttpResponse<InputStream> response = HTTP.send(request(candidate.url), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200)
          return null;
        byte[] bytes = body.readNBytes(MAX_BYTES + 1);
        return bytes.length > MAX_BYTES ? null : ChatImage.save(ChatImage.load(bytes), dir);
      }
    } catch (IOException e) {
      System.err.println("[chat] 사진 받기 실패: " + e);
      return null;
    }
  }

  private static HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", AGENT)
        .GET()
        .build();
  }
}
