package widget.voice;

import widget.core.Env;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 녹음한 wav 를 글자로 바꿔주는 자리 — whisper.cpp 를 그냥 불러 쓴다.
 *
 * 인터넷으로 안 보낸다. 마이크에 대고 한 말이 남의 서버로 나가는 게 찜찜한 것도 있고,
 * 이 위젯에서 받아 적을 말은 "유튜브 열어줘" 같은 한 마디라 왕복하는 시간이 더 아깝다.
 * 이 컴퓨터엔 이미 whisper.cpp 가 깔려 있고 Vulkan 으로 그래픽카드까지 쓴다.
 *
 * 부르는 길은 둘인데 쓰는 쪽은 신경 안 써도 된다:
 * - whisper-server.exe 가 있으면 그걸 한 대 띄워놓고 물어본다 (Server 참고). 한 마디에 1초쯤.
 * - 없으면 whisper-cli.exe 를 한 마디마다 새로 띄운다. 3초쯤 — 느리지만 되긴 된다.
 *
 * 찾는 곳:
 * - 실행기: .env 의 WHISPER_CLI / WHISPER_SERVER → 흔히 깔리는 자리(msys2) → PATH
 * - 모델: .env 의 WHISPER_MODEL → 프로젝트의 models\ 안에 있는 ggml-*.bin 중 제일 큰 것
 * (큰 모델일수록 잘 알아들어서, 여러 개 받아놨으면 좋은 쪽을 쓰는 게 맞다)
 * - 말 골라내기(VAD) 모델: models\ggml-silero-*.bin — 있으면 쓰고 없으면 그냥 넘어간다 (vadModel 참고)
 * - 언어: .env 의 WHISPER_LANG, 안 적어놨으면 한국어(ko)
 *
 * 실행기도 모델도 없으면 isReady() 가 false 고, 왜 못 쓰는지는 unavailableReason() 이
 * 한 줄로 말해준다 (GeminiClient 가 키 없을 때 하는 것과 같은 수법).
 *
 * 부르는 건 딴 스레드에서. 몇 초짜리 말이라도 모델이 도는 동안은 돌아오지 않는다.
 */
public final class Whisper {

  /** whisper.cpp 를 msys2 로 깔면 여기 놓인다. .env 에 안 적어놨을 때 뒤져보는 자리 */
  private static final String[] BIN_DIRS = {
      "C:/msys64/mingw64/bin",
      "C:/msys64/ucrt64/bin",
  };

  /** 모델을 찾아볼 폴더 (프로젝트 루트 기준) */
  private static final String MODEL_DIR = "models";

  /** 안 적어놨을 때 알아들을 말 — 이 위젯은 한국어로 쓰니까 */
  private static final String DEFAULT_LANG = "ko";

  /**
   * 한 판에 줄 시간. 몇 초짜리 말이 이만큼 걸릴 일은 없고, 넘겼으면 뭔가 잘못된 것이라
   * 영영 기다리지 말고 끊는다 (기다리는 동안 다음 받아쓰기가 막힌다).
   */
  private static final long TIMEOUT_SECONDS = 90;

  /** 실패했다고 알릴 때 whisper 가 한 말을 몇 줄까지 붙일지 */
  private static final int ERROR_LINES = 6;

  /**
   * 소리 1초가 인코더 눈금 몇 칸인지. whisper 는 30초(1500칸)를 한 번에 보는 물건이라,
   * 두 마디 말하고 30초어치를 통째로 인코딩하면 그 시간이 제일 아깝다. 들려줄 만큼만
   * 보라고 일러주면(-ac) 짧은 말일수록 눈에 띄게 빨라진다 — 여기서 재보니 "유튜브 열어줘"
   * 한 마디가 1.7초에서 1.0초로 줄었다.
   */
  private static final int FRAMES_PER_SECOND = 50;

  /**
   * 들려준 길이의 몇 배까지 보게 할지. 말한 만큼만 딱 맞춰 잡으면 알아듣는 게 눈에 띄게
   * 나빠진다 — 9초짜리를 578 칸으로 들려줬더니 "자바에서 스레드" 가 "java에서 thread" 로,
   * 우리말을 죄다 영어로 받아 적었다. 800 칸(두 배쯤)부터 제대로 돌아왔다.
   */
  private static final int AUDIO_CTX_HEADROOM = 2;

  /**
   * 눈금을 줄이더라도 이보다 밑으로는 안 내려간다.
   *
   * 여기가 생각보다 날카롭다. 같은 "유튜브 열어줘" 를 320 으로 들려주면 "유튜브야라쥬어"
   * 가 되고 384 부터 멀쩡해졌다 — 짧은 말이라고 눈금까지 짧게 잡으면 글자가 무너진다.
   * 그 경계에 붙여 놓으면 마이크로 실제로 말할 때(앞뒤 숨소리, 잡음) 넘어갈 게 뻔해서
   * 한 칸 더 여유를 두고 512 로 잡았다. 그래도 한 마디에 1초 언저리다.
   */
  private static final int MIN_AUDIO_CTX = 512;

  /** 30초를 통째로 보는 값. 길게 말했으면 결국 여기까지 온다 */
  private static final int FULL_AUDIO_CTX = 1500;

  /** 말 끝을 자르지 않게 눈금을 이만큼 더 얹어준다 (앞뒤로 남는 숨소리 몫) */
  private static final int AUDIO_CTX_MARGIN = 256;

  /**
   * 말이 진짜로 있었는지 먼저 가려내는 모델(silero). 이게 있으면 --vad 로 같이 돌린다.
   *
   * 없어도 되지만 있는 편이 훨씬 낫다. 조용한 방에서 스페이스만 눌렀다 떼면 Whisper 는
   * 빈손으로 돌아오는 게 아니라 "MBC 뉴스 김지경입니다" 같은 걸 지어낸다 — 아무 말 없는
   * 구간을 받아도 뭐라도 적어내는 물건이라서. 소리 크기로 걸러보려 했는데 조용한 방도
   * 팬 소리에 따라 rms 가 0.5 에서 300 까지 오르내려서 선을 그을 자리가 없었고,
   * VAD 를 켜니 같은 녹음이 깔끔하게 빈 줄로 돌아왔다.
   */
  private static final String VAD_PREFIX = "ggml-silero";

  /**
   * 말로 치는 기준. 기본값 0.5 는 이 방의 팬 소리를 말로 쳐서 그대로 헛소리가 나왔고,
   * 0.6 부터 조용한 녹음이 빈 줄로 돌아왔다. 사람이 말한 건 0.9 에서도 멀쩡히 잡힌다.
   */
  private static final String VAD_THRESHOLD = "0.6";

  /** 말이라고 본 구간 앞뒤로 더 붙여 보낼 길이 (vadArgs 참고) */
  private static final String VAD_PAD_MS = "400";

  /** 이만큼은 조용해야 말이 끊긴 것으로 친다 — 짧게 잡으면 한 마디가 토막토막 잘린다 */
  private static final String VAD_MIN_SILENCE_MS = "400";

  /**
   * 말이 없는 구간에서 Whisper 가 버릇처럼 뱉는 것들. 조용한데 스페이스만 눌렀다 떼면
   * "감사합니다" 나 "[BLANK_AUDIO]" 같은 게 튀어나와서, 그대로 입력칸에 꽂히면 황당하다.
   * 통째로 이것뿐일 때만 지운다 — 진짜로 "감사합니다" 라고 말했으면 그건 그대로 적어야 하니
   * 앞뒤에 다른 말이 붙어 있으면 손대지 않는다.
   */
  private static final List<String> NOISE = List.of(
      "[BLANK_AUDIO]", "[_BEG_]", "[SOUND]", "[MUSIC]", "(음악)", "(끝)",
      "감사합니다", "시청해주셔서 감사합니다", "구독과 좋아요 부탁드립니다", "고맙습니다");

  /** 한 번 찾아둔 실행기·모델. 매번 디스크를 뒤질 이유가 없다 */
  private static Path cli;
  private static Path serverExe;
  private static Path model;
  private static Path vadModel;
  private static boolean looked;

  /** 띄워둔 서버. 서버 실행기가 없으면 끝까지 null 이고, 그땐 한 판씩 돈다 */
  private static Server server;

  private Whisper() {
  }

  /** 받아쓸 채비가 됐나 — 실행기와 모델이 둘 다 제자리에 있나 */
  public static synchronized boolean isReady() {
    look();
    return (serverExe != null || cli != null) && model != null;
  }

  /** 왜 못 쓰는지 사람 말로. isReady() 가 false 일 때만 뜻이 있다 */
  public static synchronized String unavailableReason() {
    look();
    if (serverExe == null && cli == null)
      return "whisper-cli.exe 를 못 찾았어 — .env 에 WHISPER_CLI=경로 를 적어줘";
    if (model == null)
      return "받아쓰기 모델이 없어 — models\\ggml-small.bin 을 놓거나 .env 에 WHISPER_MODEL=경로 를 적어줘";
    return "";
  }

  /** 지금 쓰는 모델 이름 (ggml-small.bin). 없으면 null */
  public static synchronized String modelName() {
    look();
    return model == null ? null : model.getFileName().toString();
  }

  /**
   * 곧 말을 시킬 거라고 미리 알려둔다 — 말하는 동안 서버가 뜨게.
   * 스페이스를 누르는 순간(녹음 시작) 불린다. 서버를 안 쓰는 길이면 할 일이 없다.
   */
  public static synchronized void warmUp() {
    if (!isReady())
      return;
    if (serverExe == null)
      return;
    if (server == null)
      server = new Server(serverExe, model, lang(), vadModel);
    server.warmUp();
  }

  /**
   * wav 한 개를 글자로 바꾼다. 다 쓴 wav 는 여기서 지운다 (성공이든 실패든).
   * 알아들은 게 없으면 빈 문자열.
   *
   * @param seconds 녹음한 길이. 들려줄 만큼만 인코딩하라고 일러주는 데 쓴다 (audioCtx 참고)
   */
  public static String transcribe(Path wav, double seconds) throws IOException, InterruptedException {
    if (!isReady())
      throw new IOException(unavailableReason());

    try {
      Server standing = standingServer();
      String heard = standing != null
          ? standing.transcribe(wav, audioCtx(seconds))
          : runOnce(wav, audioCtx(seconds));
      return clean(heard);
    } finally {
      try {
        Files.deleteIfExists(wav);
      } catch (IOException ignored) {
        // 임시 폴더에 몇 KB 남는 것뿐이라, 못 지웠다고 받아쓴 말까지 버릴 일은 아니다
      }
    }
  }

  /** 서버를 쓰는 길이면 그 서버, 아니면 null (그땐 한 판씩 띄운다) */
  private static synchronized Server standingServer() {
    warmUp();
    return server;
  }

  /**
   * 들려줄 소리 길이에 맞춘 인코더 눈금 (-ac).
   * 말한 만큼에 여유분을 얹어 잡되, 너무 좁지도(알아듣는 게 나빠진다) 30초를 넘지도 않게.
   */
  private static int audioCtx(double seconds) {
    int needed = (int) Math.ceil(Math.max(0, seconds) * FRAMES_PER_SECOND * AUDIO_CTX_HEADROOM)
        + AUDIO_CTX_MARGIN;
    return Math.min(FULL_AUDIO_CTX, Math.max(MIN_AUDIO_CTX, needed));
  }

  /** 서버가 없을 때 — whisper-cli 를 한 판 띄워서 받아 적는다 */
  private static String runOnce(Path wav, int audioCtx) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(
        cli.toString(),
        "-m", model.toString(),
        "-f", wav.toAbsolutePath().toString(),
        "-l", lang(),
        "-nt", // 시간 표시 빼고 글자만
        "-np", // 결과 말고는 아무것도 찍지 말라고
        "-ac", String.valueOf(audioCtx),
        "-bo", "1", // 후보를 여러 개 굴려봐야 한 마디짜리엔 값만 든다
        "-bs", "1",
        "-t", String.valueOf(threads()));
    builder.command().addAll(vadArgs(vadModel));
    // 모델 올리는 얘기(Vulkan 장치 목록 같은 것)는 죄다 stderr 로 나온다. 섞으면
    // 그게 받아쓴 말인 줄 알고 입력칸에 꽂히니까 따로 흘려보낸다.
    Process process = builder.start();

    // 그 stderr 도 누가 퍼내주긴 해야 한다. 파이프가 꽉 차면 whisper-cli 가 거기서
    // 멈춰 서서 영영 안 끝나기 때문 — 퍼낸 건 실패했을 때 무슨 소리를 했는지 붙여준다.
    StringBuilder complaint = new StringBuilder();
    Thread stderr = new Thread(() -> {
      try (InputStream err = process.getErrorStream()) {
        String said = new String(err.readAllBytes(), StandardCharsets.UTF_8);
        synchronized (complaint) {
          complaint.append(said);
        }
      } catch (IOException ignored) {
        // 못 읽었어도 받아쓴 말 자체는 stdout 으로 온다
      }
    }, "voice-whisper-err");
    stderr.setDaemon(true);
    stderr.start();

    String heard;
    try (InputStream out = process.getInputStream()) {
      heard = new String(out.readAllBytes(), StandardCharsets.UTF_8);
    }

    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("받아쓰기가 " + TIMEOUT_SECONDS + "초가 지나도 안 끝나서 그만뒀어");
    }
    if (process.exitValue() != 0)
      throw new IOException("whisper-cli 가 " + process.exitValue() + " 로 끝났어\n\n" + tail(complaint));

    return heard;
  }

  /** whisper 가 늘어놓은 얘기 중 마지막 몇 줄 — 실패했을 때 붙여줄 만큼만 */
  private static String tail(StringBuilder complaint) {
    String said;
    synchronized (complaint) {
      said = complaint.toString().trim();
    }
    String[] lines = said.split("\\R");
    int from = Math.max(0, lines.length - ERROR_LINES);
    return String.join("\n", List.of(lines).subList(from, lines.length));
  }

  /**
   * 받아온 걸 한 줄로 다듬는다.
   * 줄을 나눠 오면 붙이고(한 마디를 받아 적는 자리라 줄바꿈이 의미가 없다),
   * 말 없는 구간에서 나오는 버릇 문구는 통째로 그것뿐일 때 지운다.
   */
  private static String clean(String raw) {
    List<String> kept = new ArrayList<>();
    for (String line : raw.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty())
        continue;
      kept.add(trimmed);
    }

    String joined = String.join(" ", kept).replaceAll("\\s+", " ").trim();
    for (String noise : NOISE) {
      if (joined.equalsIgnoreCase(noise))
        return "";
    }
    return joined;
  }

  private static String lang() {
    String configured = Env.get("WHISPER_LANG");
    return configured == null ? DEFAULT_LANG : configured;
  }

  /** 절반만 쓴다 — 받아쓰는 동안에도 위젯은 굴러가야 하고, 그래픽카드가 거들어서 더 필요도 없다 */
  static int threads() {
    return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
  }

  private static void look() {
    if (looked)
      return;
    looked = true;
    cli = findExe("whisper-cli.exe", "WHISPER_CLI");
    serverExe = findExe("whisper-server.exe", "WHISPER_SERVER");
    model = findModel();
    vadModel = findVadModel();
  }

  private static Path findExe(String name, String envKey) {
    String configured = Env.get(envKey);
    if (configured != null) {
      Path path = Paths.get(configured);
      if (Files.isRegularFile(path))
        return path;
      System.err.println("[voice] .env 의 " + envKey + " 자리에 파일이 없어: " + path);
    }

    for (String dir : BIN_DIRS) {
      Path path = Paths.get(dir).resolve(name);
      if (Files.isRegularFile(path))
        return path;
    }
    return onPath(name);
  }

  /** PATH 에 걸어놨으면 거기서도 찾아본다 (직접 빌드해서 쓰는 경우) */
  private static Path onPath(String name) {
    String path = System.getenv("PATH");
    if (path == null)
      return null;
    for (String dir : path.split(";")) {
      if (dir.isBlank())
        continue;
      try {
        Path candidate = Paths.get(dir.trim()).resolve(name);
        if (Files.isRegularFile(candidate))
          return candidate;
      } catch (Exception ignored) {
        // PATH 에 이상한 글자가 섞인 칸은 건너뛴다
      }
    }
    return null;
  }

  private static Path findModel() {
    String configured = Env.get("WHISPER_MODEL");
    if (configured != null) {
      Path path = Paths.get(configured);
      if (Files.isRegularFile(path))
        return path;
      System.err.println("[voice] .env 의 WHISPER_MODEL 자리에 파일이 없어: " + path);
    }

    Path dir = Env.find(MODEL_DIR);
    if (dir == null || !Files.isDirectory(dir))
      return null;

    // 여러 개 받아놨으면 제일 큰 것 — 클수록 잘 알아듣는다
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> {
            String name = p.getFileName().toString().toLowerCase();
            // 말 골라내기 모델은 받아쓰는 모델이 아니라서 여기 끼면 안 된다
            return name.startsWith("ggml-") && name.endsWith(".bin")
                && !name.startsWith(VAD_PREFIX);
          })
          .max(Comparator.comparingLong(Whisper::size))
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * 말 골라내기 모델을 찾는다 (.env 의 WHISPER_VAD_MODEL → models\ggml-silero-*.bin).
   * 없으면 null — 그땐 VAD 없이 돌고, 헛소리는 NOISE 목록으로만 걸러진다.
   */
  private static Path findVadModel() {
    String configured = Env.get("WHISPER_VAD_MODEL");
    if (configured != null) {
      Path path = Paths.get(configured);
      if (Files.isRegularFile(path))
        return path;
      System.err.println("[voice] .env 의 WHISPER_VAD_MODEL 자리에 파일이 없어: " + path);
    }

    Path dir = Env.find(MODEL_DIR);
    if (dir == null || !Files.isDirectory(dir))
      return null;

    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase().startsWith(VAD_PREFIX))
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** whisper 한테 VAD 를 켜라고 넘기는 인자. 모델이 없으면 빈손 (서버·CLI 가 같이 쓴다) */
  static List<String> vadArgs(Path vad) {
    if (vad == null)
      return List.of();
    // 말 앞뒤를 넉넉히 붙여서 넘긴다. 말한 자리만 칼같이 오려 보내면 앞뒤 맥락이 사라져서
    // "다운로드 폴더" 가 "Download folder" 로 오는 식으로 받아 적는 게 나빠진다.
    return List.of("--vad", "-vm", vad.toString(), "-vt", VAD_THRESHOLD,
        "-vp", VAD_PAD_MS, "-vsd", VAD_MIN_SILENCE_MS);
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }
}
