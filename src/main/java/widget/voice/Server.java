package widget.voice;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 모델을 올려둔 채 기다리는 whisper-server 한 대.
 *
 * 왜 서버냐면, 한 마디 받아 적을 때마다 whisper-cli 를 새로 띄우면 말을 알아듣는 시간보다
 * 모델을 올리고 그래픽카드를 깨우는 시간이 더 든다. 여기서 재보니 "유튜브 열어줘" 한 마디에
 * 한 판씩 띄우면 3초, 띄워둔 서버한테 물어보면 1초 언저리였다. 런처에서 3초는 못 쓸 물건이다.
 *
 * 대신 떠 있는 동안 700MB 쯤을 붙들고 있어서, 말을 안 시키면 스스로 접는다 (IDLE_MINUTES).
 * 다시 필요해지면 그때 또 뜬다.
 *
 * 뜨는 데 드는 2초 남짓은 사람이 말하는 동안 숨긴다 — 스페이스를 누르는 순간(녹음 시작)
 * warmUp() 이 불려서, 말을 마치고 손을 뗄 때쯤이면 서버는 이미 서 있다.
 *
 * 위젯이 비정상으로 죽으면 이 서버만 남을 수 있다. 그래서 띄울 때 프로세스 번호를 임시
 * 폴더에 적어두고, 다음번에 띄우기 전에 그 번호가 아직 살아 있으면 먼저 거둬간다 (reap).
 */
final class Server {

  /** 서버가 설 때까지 기다려주는 시간. 모델을 처음 읽는 날은 디스크에서 긁어오느라 오래 걸린다 */
  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

  /** 이만큼 아무 말도 안 시키면 서버를 접는다 */
  private static final long IDLE_MINUTES = 5;

  /** 살아났나 물어보는 간격 */
  private static final long POLL_MILLIS = 150;

  /** 서버가 뜨면서 늘어놓는 얘기를 받아두는 곳 — 안 뜰 때 들여다볼 자리 */
  private static final String LOG_NAME = "widget-whisper-server.log";

  /** 띄운 서버의 프로세스 번호를 적어두는 곳 (reap 참고) */
  private static final String PID_NAME = "widget-whisper-server.pid";

  private final Path exe;
  private final Path model;
  private final String lang;
  /** 말 골라내기 모델. 없으면 null 이고 그땐 VAD 없이 뜬다 (Whisper.vadArgs 참고) */
  private final Path vadModel;

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private Process process;
  /** 서버가 설 때까지의 약속. 다 서면 포트 번호가 들어온다 */
  private CompletableFuture<Integer> standing;
  /** 마지막으로 말을 시킨 때 — 한참 지나면 접는다 */
  private volatile long lastUsed;
  /** 지금 받아쓰는 중인지. 쓰는 중에 접어버리면 안 되니까 */
  private volatile boolean busy;

  private ScheduledExecutorService janitor;

  Server(Path exe, Path model, String lang, Path vadModel) {
    this.exe = exe;
    this.model = model;
    this.lang = lang;
    this.vadModel = vadModel;
    // 위젯이 제대로 꺼질 땐(exit·restart 둘 다 System.exit 로 나간다) 서버도 같이 접는다
    Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "voice-server-close"));
  }

  /**
   * 서버를 깨워둔다. 이미 서 있거나 서는 중이면 아무 일도 안 한다.
   * 바로 돌아온다 — 부르는 쪽이 JavaFX 스레드라 여기서 기다리면 화면이 굳는다.
   */
  synchronized void warmUp() {
    rising();
  }

  /**
   * 서버가 설 때까지의 약속을 돌려준다. 아직 없으면 지금 세우기 시작한다.
   * 부르는 쪽이 이 약속을 손에 쥐고 기다리기 때문에, 그 사이에 실패해서 standing 이
   * 지워지더라도 "누구를 기다리고 있었는지" 를 놓치지 않는다.
   */
  private synchronized CompletableFuture<Integer> rising() {
    lastUsed = System.currentTimeMillis();

    if (process != null && !process.isAlive())
      clear(); // 어쩌다 죽었으면 흔적을 지우고 새로 세운다
    if (standing != null)
      return standing;

    CompletableFuture<Integer> promise = new CompletableFuture<>();
    standing = promise;

    Thread starter = new Thread(() -> {
      try {
        promise.complete(raise());
      } catch (Exception ex) {
        promise.completeExceptionally(ex);
        synchronized (this) {
          // 못 선 약속을 들고 있으면 다음에도 그 실패를 또 돌려주게 된다
          if (standing == promise)
            clear();
        }
      }
    }, "voice-server-start");
    starter.setDaemon(true);
    starter.start();
    return promise;
  }

  /**
   * wav 한 개를 서버한테 맡겨 글자로 받는다.
   * 서버가 아직 서는 중이면 다 설 때까지 기다린다 (딴 스레드에서 부를 것).
   *
   * @param audioCtx 들려줄 소리 길이만큼만 인코더를 돌리라고 일러주는 값 (Whisper.audioCtx 참고)
   */
  String transcribe(Path wav, int audioCtx) throws IOException, InterruptedException {
    CompletableFuture<Integer> standingUp = rising();

    int port;
    try {
      port = standingUp.get(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new IOException("받아쓰기 서버가 안 떴어 — " + Paths.get(tmp(), LOG_NAME) + " 를 봐줘", ex);
    }

    busy = true;
    try {
      String heard = ask(port, wav, audioCtx);
      lastUsed = System.currentTimeMillis();
      return heard;
    } catch (IOException ex) {
      // 서버가 도중에 넘어졌을 수 있다. 다음 판은 새 서버로 시작하게 접어둔다
      stop();
      throw ex;
    } finally {
      busy = false;
    }
  }

  /** 서버를 띄우고 대답할 때까지 기다린다. 선 포트를 돌려준다 */
  private int raise() throws IOException, InterruptedException {
    reap();

    int port = freePort();
    Path log = Paths.get(tmp(), LOG_NAME);

    ProcessBuilder builder = new ProcessBuilder(
        exe.toString(),
        "-m", model.toString(),
        "-l", lang,
        "-nt", // 시간 표시 빼고 글자만
        "-bo", "1", // 후보를 여러 개 굴려봐야 한 마디짜리엔 값만 든다
        "-bs", "1",
        "-t", String.valueOf(Whisper.threads()),
        "--host", "127.0.0.1", // 이 컴퓨터 밖에서는 못 두드리게
        "--port", String.valueOf(port));
    builder.command().addAll(Whisper.vadArgs(vadModel));
    // 서버가 늘어놓는 얘기(Vulkan 장치 목록, 모델 정보)는 로그로 보낸다.
    // 안 퍼내면 파이프가 차서 서버가 그 자리에 멈춰 선다.
    builder.redirectOutput(log.toFile());
    builder.redirectErrorStream(true);

    Process started = builder.start();
    synchronized (this) {
      process = started;
    }
    mark(started.pid());
    sweepWhenIdle();

    long deadline = System.currentTimeMillis() + START_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (!started.isAlive())
        throw new IOException("받아쓰기 서버가 뜨자마자 꺼졌어 (" + started.exitValue() + ") — " + log + " 를 봐줘");
      if (answers(port))
        return port;
      Thread.sleep(POLL_MILLIS);
    }
    throw new IOException("받아쓰기 서버가 " + START_TIMEOUT.toSeconds() + "초가 지나도 대답이 없어");
  }

  /** 아직 살아 있나 한 번 두드려본다 */
  private boolean answers(int port) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
          .timeout(Duration.ofSeconds(2))
          .GET()
          .build();
      http.send(request, HttpResponse.BodyHandlers.discarding());
      return true;
    } catch (Exception e) {
      return false; // 아직 안 섰다는 뜻일 뿐이라 조용히 한 번 더 기다린다
    }
  }

  /** /inference 에 wav 를 얹어 보내고 받아 적은 글자를 돌려받는다 */
  private String ask(int port, Path wav, int audioCtx) throws IOException, InterruptedException {
    String boundary = "----widget" + Long.toHexString(System.nanoTime());
    byte[] body = multipart(boundary, Files.readAllBytes(wav), wav.getFileName().toString(), audioCtx);

    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/inference"))
        .timeout(START_TIMEOUT)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();

    HttpResponse<String> response = http.send(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200)
      throw new IOException("받아쓰기 서버가 " + response.statusCode() + " 로 답했어");

    return response.body();
  }

  /**
   * multipart/form-data 한 덩어리를 손으로 쌓는다.
   * 보내는 칸이 셋뿐이라(소리 파일·답 모양·인코더 길이) 라이브러리를 더 들이는 것보다 짧다.
   */
  private static byte[] multipart(String boundary, byte[] wav, String filename, int audioCtx)
      throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    OutputStream out = body;

    for (List<String> field : List.of(
        List.of("response_format", "text"),
        List.of("audio_ctx", String.valueOf(audioCtx)))) {
      write(out, "--" + boundary + "\r\n"
          + "Content-Disposition: form-data; name=\"" + field.get(0) + "\"\r\n\r\n"
          + field.get(1) + "\r\n");
    }

    write(out, "--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
        + "Content-Type: audio/wav\r\n\r\n");
    out.write(wav);
    write(out, "\r\n--" + boundary + "--\r\n");

    return body.toByteArray();
  }

  private static void write(OutputStream out, String text) throws IOException {
    out.write(text.getBytes(StandardCharsets.UTF_8));
  }

  /** 한참 조용하면 접는다. 서버가 붙들고 있는 게 적지 않아서 (클래스 주석 참고) */
  private synchronized void sweepWhenIdle() {
    if (janitor != null)
      return;
    janitor = Executors.newSingleThreadScheduledExecutor(work -> {
      Thread thread = new Thread(work, "voice-server-idle");
      thread.setDaemon(true);
      return thread;
    });
    janitor.scheduleWithFixedDelay(() -> {
      if (busy || process == null)
        return;
      if (System.currentTimeMillis() - lastUsed > TimeUnit.MINUTES.toMillis(IDLE_MINUTES))
        stop();
    }, 1, 1, TimeUnit.MINUTES);
  }

  /** 서버를 접는다. 다시 말을 시키면 그때 새로 뜬다 */
  synchronized void stop() {
    if (process != null) {
      process.destroy();
      try {
        if (!process.waitFor(2, TimeUnit.SECONDS))
          process.destroyForcibly();
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      }
    }
    clear();
    unmark();
  }

  private synchronized void clear() {
    process = null;
    standing = null;
  }

  /**
   * 지난번에 띄워놓고 못 거둔 서버가 있으면 거둔다.
   * 위젯이 비정상으로 죽으면(작업 관리자로 끄거나 뻗으면) 서버만 남아서 계속 메모리를 쥔다.
   * 적어둔 번호가 아직 살아 있고 그게 정말 whisper 서버일 때만 내린다 —
   * 번호는 돌고 도는 것이라, 남의 프로세스를 잘못 내리면 안 된다.
   */
  private static void reap() {
    Path file = Paths.get(tmp(), PID_NAME);
    if (!Files.isRegularFile(file))
      return;
    try {
      long pid = Long.parseLong(Files.readString(file).trim());
      ProcessHandle.of(pid).ifPresent(handle -> {
        String command = handle.info().command().orElse("");
        if (command.toLowerCase().contains("whisper-server"))
          handle.destroyForcibly();
      });
    } catch (Exception ignored) {
      // 못 읽었으면 남은 게 없는 셈 친다
    }
    unmark();
  }

  private static void mark(long pid) {
    try {
      Files.writeString(Paths.get(tmp(), PID_NAME), String.valueOf(pid));
    } catch (IOException ignored) {
      // 적어두지 못해도 이번 판은 멀쩡히 돈다 (다음번 거두기만 못 한다)
    }
  }

  private static void unmark() {
    try {
      Files.deleteIfExists(Paths.get(tmp(), PID_NAME));
    } catch (IOException ignored) {
      // 남아 있어도 다음 reap 이 "그런 프로세스 없음" 으로 지나간다
    }
  }

  /** 아무도 안 쓰는 포트 하나. 열어보고 바로 닫아서 번호만 얻어온다 */
  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String tmp() {
    return System.getProperty("java.io.tmpdir");
  }
}
