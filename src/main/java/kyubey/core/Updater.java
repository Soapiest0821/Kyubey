package kyubey.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.stage.Window;
import kyubey.ui.Notice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

/**
 * 켤 때 GitHub Release 에서 새 버전을 찾아, 사용자가 받으면 MSI 로 업그레이드한다.
 *
 * Release 는 makeit-v1.2.3 태그에 Makeit-1.2.3.msi 와 .msi.sha256 을 함께 올린 것만 본다
 * (.github/workflows/makeit-release.yml). 같은 저장소에 Kyubey Release 가 생겨도 섞이지 않는다.
 *
 * 실행 중에는 설치 파일을 바꿀 수 없어서, 이 프로세스가 끝나길 기다렸다가 설치하고 다시 켜는
 * 일을 PowerShell 에 맡기고 종료한다. 개발 실행(mvn javafx:run)에선 아무것도 하지 않는다.
 */
public final class Updater {

  private static final String REPO = "Soapiest0821/Kyubey";
  private static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
  private static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
  private static final String TAG_PREFIX = "makeit-v";
  private static final String AGENT = "Makeit-updater";

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 찾아낸 새 버전 하나 */
  record Release(String version, URI msi, URI sha256) {
  }

  private Updater() {
  }

  /** jpackage 로 설치된 앱이면 실행 파일 자리, 아니면 null */
  private static String appPath() {
    return System.getProperty("jpackage.app-path");
  }

  public static boolean packaged() {
    return appPath() != null;
  }

  /** 지금 앱 버전. pom.xml 의 version 이 빌드 때 version.properties 로 들어온다 */
  public static String currentVersion() {
    Properties properties = new Properties();
    try (InputStream in = Updater.class.getResourceAsStream("/version.properties")) {
      if (in != null)
        properties.load(in);
    } catch (IOException e) {
      System.err.println("[update] 버전을 못 읽었어: " + e.getMessage());
    }
    return properties.getProperty("version", "");
  }

  /** 뒤에서 새 버전을 찾아 보고, 있으면 물어본다. 실패해도 앱 사용에는 지장이 없게 넘어간다 */
  public static void check(Window owner) {
    if (!packaged())
      return;
    String current = currentVersion();
    if (!current.matches("\\d+\\.\\d+\\.\\d+")) {
      System.err.println("[update] 버전이 이상해서 건너뛰어: " + current);
      return;
    }
    Thread worker = new Thread(() -> {
      try {
        Release release = fetchLatest(current);
        if (release != null)
          Platform.runLater(() -> offer(owner, current, release));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException | RuntimeException e) {
        System.err.println("[update] 새 버전 확인 실패: " + e);
      }
    }, "update-check");
    worker.setDaemon(true);
    worker.start();
  }

  /** /update — 지금 버전을 알리고 새 버전을 바로 찾는다. 찾지 못한 이유도 화면에 보여준다 */
  public static void checkNow(Window owner) {
    String current = currentVersion();
    if (!packaged()) {
      Notice.info(owner, "지금 버전은 " + current + " 이야.\n업데이트는 설치한 앱에서만 돼.");
      return;
    }
    Thread worker = new Thread(() -> {
      try {
        Release release = fetchLatest(current);
        Platform.runLater(() -> {
          if (release == null)
            Notice.info(shown(owner), "지금 " + current + " 이 최신 버전이야.");
          else
            offer(owner, current, release);
        });
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException | RuntimeException e) {
        System.err.println("[update] 새 버전 확인 실패: " + e);
        Platform.runLater(() -> Notice.warn(shown(owner),
            "새 버전을 확인하지 못했어. 지금 버전은 " + current + " 이야.\n" + e.getMessage()));
      }
    }, "update-check-now");
    worker.setDaemon(true);
    worker.start();
  }

  private static Release fetchLatest(String current) throws IOException, InterruptedException {
    return latest(fetchText(URI.create(RELEASES)), current);
  }

  /** 숨겨진 창에 알림을 붙이면 안 보일 수 있어서, 떠 있는 창일 때만 주인으로 쓴다 */
  private static Window shown(Window owner) {
    return owner != null && owner.isShowing() ? owner : null;
  }

  private static void offer(Window owner, String current, Release release) {
    Window parent = owner != null && owner.isShowing() ? owner : null;
    String message = "새 버전이 나왔어.\n" + current + " → " + release.version();
    if (Notice.ask(parent, message, "업데이트", "나중에") != 0)
      return;

    Thread worker = new Thread(() -> {
      try {
        Path msi = download(release);
        Platform.runLater(() -> install(owner, release, msi));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException | RuntimeException e) {
        System.err.println("[update] 받기 실패: " + e);
        Platform.runLater(() -> Notice.warn(parent, "업데이트를 받지 못했어.\n" + e.getMessage()));
      }
    }, "update-download");
    worker.setDaemon(true);
    worker.start();
  }

  private static void install(Window owner, Release release, Path msi) {
    Window parent = owner != null && owner.isShowing() ? owner : null;
    if (Notice.ask(parent, release.version() + " 을 받았어.\n지금 앱을 껐다 켜면서 설치할까?", "설치", "나중에") != 0)
      return;
    try {
      Path log = msi.resolveSibling("install.log");
      String args = "/i \"" + msi + "\" /passive /norestart /l*v \"" + log + "\"";
      afterExit("Start-Process msiexec.exe -Wait -ArgumentList " + quote(args) + "\n");
      System.exit(0);
    } catch (IOException e) {
      System.err.println("[update] 설치 시작 실패: " + e);
      Notice.warn(parent, "설치를 시작하지 못했어.\n" + e.getMessage());
    }
  }

  /** /restart — 이 프로세스가 끝나면 앱을 다시 켠다. 부른 쪽이 곧바로 종료해야 한다 */
  public static void relaunch() throws IOException {
    afterExit("");
  }

  /** 이 프로세스가 끝나길 기다렸다가 script 를 돌리고, 앱을 다시 켜는 PowerShell 을 띄운다 */
  private static void afterExit(String script) throws IOException {
    String body = "Wait-Process -Id " + ProcessHandle.current().pid() + " -Timeout 60 -ErrorAction SilentlyContinue\n"
        + script
        + "Start-Process " + quote(appPath()) + "\n";
    // 경로에 한글이나 따옴표가 있어도 깨지지 않게 PowerShell 이 받는 UTF-16 인코딩으로 넘긴다
    String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_16LE));
    new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-EncodedCommand", encoded)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  /** PowerShell 작은따옴표 문자열 */
  static String quote(String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  /** 지금보다 새 버전 중 가장 높은 것. 없으면 null. 설치 파일이 갖춰진 정식 Release 만 친다 */
  static Release latest(String json, String current) throws IOException {
    Release best = null;
    for (JsonNode node : MAPPER.readTree(json)) {
      if (node.path("draft").asBoolean() || node.path("prerelease").asBoolean())
        continue;
      String tag = node.path("tag_name").asText("");
      if (!tag.startsWith(TAG_PREFIX))
        continue;
      String version = tag.substring(TAG_PREFIX.length());
      if (!version.matches("\\d+\\.\\d+\\.\\d+") || compare(version, current) <= 0)
        continue;
      if (best != null && compare(version, best.version()) <= 0)
        continue;

      URI msi = asset(node, "Makeit-" + version + ".msi");
      URI sha256 = asset(node, "Makeit-" + version + ".msi.sha256");
      if (msi != null && sha256 != null)
        best = new Release(version, msi, sha256);
    }
    return best;
  }

  private static URI asset(JsonNode release, String name) {
    for (JsonNode asset : release.path("assets")) {
      String url = asset.path("browser_download_url").asText("");
      if (name.equals(asset.path("name").asText()) && url.startsWith(DOWNLOADS))
        return URI.create(url);
    }
    return null;
  }

  /** 1.2.10 이 1.2.9 보다 크게, 점으로 나눈 숫자끼리 견준다 */
  static int compare(String a, String b) {
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    for (int i = 0; i < 3; i++) {
      int order = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
      if (order != 0)
        return order;
    }
    return 0;
  }

  /** sha256sum 꼴("해시  파일이름")이든 해시만 있든 해시를 꺼낸다 */
  static String parseHash(String text) throws IOException {
    String hash = text.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
    if (!hash.matches("[0-9a-f]{64}"))
      throw new IOException("sha256 파일이 이상해");
    return hash;
  }

  /** 설치 파일을 받아 해시를 맞춰 본다. 같은 파일을 이미 받아 뒀으면 그대로 쓴다 */
  private static Path download(Release release) throws IOException, InterruptedException {
    String expected = parseHash(fetchText(release.sha256()));
    Path dir = Path.of(AppPaths.ROOT, "update");
    Files.createDirectories(dir);
    Path msi = dir.resolve("Makeit-" + release.version() + ".msi");
    if (Files.isRegularFile(msi) && expected.equals(sha256(msi)))
      return msi;

    Path part = dir.resolve(msi.getFileName() + ".part");
    HttpResponse<Path> response = HTTP.send(request(release.msi()), HttpResponse.BodyHandlers.ofFile(part));
    if (response.statusCode() != 200) {
      Files.deleteIfExists(part);
      throw new IOException("다운로드 실패 (HTTP " + response.statusCode() + ")");
    }
    if (!expected.equals(sha256(part))) {
      Files.deleteIfExists(part);
      throw new IOException("받은 파일의 해시가 맞지 않아");
    }
    return Files.move(part, msi, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String sha256(Path file) throws IOException {
    try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file),
        MessageDigest.getInstance("SHA-256"))) {
      in.transferTo(OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(in.getMessageDigest().digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String fetchText(URI uri) throws IOException, InterruptedException {
    HttpResponse<String> response = HTTP.send(request(uri), HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();
    if (status == 403 || status == 429)
      throw new IOException("GitHub 호출 제한에 걸렸어. 조금 뒤에 다시 해줘.");
    if (status != 200)
      throw new IOException("서버가 " + status + " 으로 답했어.");
    return response.body();
  }

  private static HttpRequest request(URI uri) {
    return HttpRequest.newBuilder(uri)
        .header("User-Agent", AGENT)
        .header("Accept", "application/vnd.github+json, application/octet-stream")
        .timeout(Duration.ofMinutes(10))
        .build();
  }
}
