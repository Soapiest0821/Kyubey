package widget.share;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 같은 와이파이 안에서 폰이랑 파일을 주고받는 임시 웹서버.
 *
 * 보내기: 고른 파일을 붙들고 있다가 폰이 주소로 들어오면 내려준다.
 * 받기: 폰에 올리기 화면을 띄워주고, 올라온 걸 다운로드 폴더에 떨군다.
 *
 * 주소는 창에 QR 로 뜬다 — 폰 카메라로 찍으면 바로 열린다. 주소 안에는 매번 새로 뽑은
 * 토큰이 들어가서, 같은 와이파이에 물린 다른 기기가 포트를 찔러봐도 토큰이 안 맞으면 404 다.
 * 창을 닫으면 서버도 같이 내려간다 (close). 떠 있는 동안만 열려 있는 문이다.
 *
 * 콜백(Listener)은 HTTP 스레드에서 불린다 — 화면을 만지려면 Platform.runLater 로 넘겨야 한다.
 */
public final class LanShare implements AutoCloseable {

  /** 주고받는 사이에 화면으로 올려 보내는 소식들 */
  public interface Listener {
    /** 오가는 중. total 이 0 이하면 전체 크기를 모른다는 뜻 */
    void onProgress(String name, long done, long total);

    /** 한 파일이 끝났다 — 보낸 쪽은 "받아갔다", 받는 쪽은 "저장했다" */
    void onDone(String name, Path saved);

    /** 도중에 엎어졌다 (폰에서 취소했거나 와이파이가 끊겼거나) */
    void onFailed(String name, String message);
  }

  /** 진행 상황을 이 간격보다 자주는 안 알린다 — 한 번에 수천 번 불러봐야 화면만 버벅인다 */
  private static final long PROGRESS_INTERVAL_MS = 150;

  private static final int COPY_BUFFER = 64 * 1024;

  /** 저장할 파일 이름 길이 한도. 경로 전체 한도(260자)에 폴더 몫을 남겨둔 값 */
  private static final int NAME_LIMIT = 120;

  /** 윈도우가 장치 이름으로 잡아먹는 것들 — 이 이름으로 저장하면 파일이 안 생기고 사라진다 */
  private static final Set<String> RESERVED = Set.of(
      "CON", "PRN", "AUX", "NUL",
      "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
      "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

  private final HttpServer server;
  private final ExecutorService pool;
  private final Listener listener;
  private final String url;
  private final AtomicBoolean closed = new AtomicBoolean();

  private LanShare(HttpServer server, ExecutorService pool, Listener listener, String url) {
    this.server = server;
    this.pool = pool;
    this.listener = listener;
    this.url = url;
  }

  /** 폰 카메라로 찍을 주소. QR 에 담기는 것도 이 값이다 */
  public String url() {
    return url;
  }

  // ── 열기 ──

  /**
   * 고른 파일을 폰이 받아가게 내건다.
   *
   * 한 개면 주소가 곧 그 파일이라 QR 을 찍는 순간 바로 내려받기가 시작되고,
   * 여러 개면 목록 화면이 먼저 뜬다 (뭘 받을지 폰에서 고르게).
   */
  public static LanShare send(List<File> files, Listener listener) throws IOException {
    List<File> picked = List.copyOf(files);
    if (picked.isEmpty())
      throw new IOException("보낼 파일이 없어");

    String token = token();
    HttpServer server = server();
    LanShare share = new LanShare(server, pool(), listener,
        base(server) + "/" + token + (picked.size() == 1 ? "" : "/"));

    server.createContext("/", exchange -> share.guard(exchange, token, rest -> {
      String method = exchange.getRequestMethod();
      if (!method.equals("GET") && !method.equals("HEAD")) {
        text(exchange, 405, "GET 만 받아");
        return;
      }
      if (rest.isEmpty() || rest.equals("/")) {
        if (picked.size() == 1)
          share.serve(exchange, picked.get(0), method.equals("HEAD"));
        else
          html(exchange, Pages.fileList(token, picked));
        return;
      }
      int index = index(rest);
      if (index < 0 || index >= picked.size()) {
        text(exchange, 404, "없는 파일이야");
        return;
      }
      share.serve(exchange, picked.get(index), method.equals("HEAD"));
    }));

    share.start();
    return share;
  }

  /**
   * 폰에서 올린 파일을 받아 dir 에 떨군다 (보통 다운로드 폴더).
   * 폰에는 파일 고르는 화면이 뜬다 — 여러 개를 한 번에 올려도 된다.
   */
  public static LanShare receive(Path dir, Listener listener) throws IOException {
    Files.createDirectories(dir);

    String token = token();
    HttpServer server = server();
    LanShare share = new LanShare(server, pool(), listener, base(server) + "/" + token);

    server.createContext("/", exchange -> share.guard(exchange, token, rest -> {
      if (rest.isEmpty() || rest.equals("/")) {
        if (!exchange.getRequestMethod().equals("GET")) {
          text(exchange, 405, "GET 만 받아");
          return;
        }
        html(exchange, Pages.upload(token));
        return;
      }
      if (rest.equals("/up")) {
        if (!exchange.getRequestMethod().equals("PUT")) {
          text(exchange, 405, "PUT 으로 올려줘");
          return;
        }
        share.store(exchange, dir);
        return;
      }
      text(exchange, 404, "없는 주소야");
    }));

    share.start();
    return share;
  }

  private void start() {
    server.setExecutor(pool);
    server.start();
  }

  /** 서버를 내린다. 창을 닫을 때 반드시 불러야 한다 — 안 그러면 포트가 계속 열려 있다 */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true))
      return;
    server.stop(0);
    pool.shutdownNow();
  }

  // ── 주고받기 ──

  /** 파일 하나를 응답으로 흘려보낸다 */
  private void serve(HttpExchange exchange, File file, boolean headOnly) throws IOException {
    if (!file.isFile()) {
      text(exchange, 404, "파일이 사라졌어");
      return;
    }

    String name = file.getName();
    long total = file.length();
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/octet-stream");
    headers.set("Content-Disposition", disposition(name));
    headers.set("Cache-Control", "no-store");

    if (headOnly) {
      headers.set("Content-Length", String.valueOf(total));
      exchange.sendResponseHeaders(200, -1);
      return;
    }

    exchange.sendResponseHeaders(200, total);
    long sent = 0;
    try (InputStream in = Files.newInputStream(file.toPath());
        OutputStream out = exchange.getResponseBody()) {
      sent = copy(in, out, name, total);
    } catch (IOException e) {
      // 폰에서 취소하거나 와이파이가 끊기면 여기로 온다. 서버가 죽을 일은 아니고, 화면에만 알린다.
      listener.onFailed(name, "보내다 끊겼어 (" + human(sent) + " / " + human(total) + ")");
      return;
    }
    listener.onDone(name, file.toPath());
  }

  /** 올라온 몸통을 파일로 받아 적는다 */
  private void store(HttpExchange exchange, Path dir) throws IOException {
    String name = safeName(query(exchange, "n"));
    long total = length(exchange);

    // 받는 도중엔 .part 로 있다가 다 받고서야 제 이름을 단다.
    // 안 그러면 절반만 받은 파일이 다운로드 폴더에 멀쩡한 얼굴로 앉아 있게 된다.
    Path part = Files.createTempFile(dir, ".widget-", ".part");
    long got = 0;
    try (InputStream in = exchange.getRequestBody();
        OutputStream out = Files.newOutputStream(part)) {
      got = copy(in, out, name, total);
    } catch (IOException e) {
      Files.deleteIfExists(part);
      listener.onFailed(name, "받다 끊겼어 (" + human(got) + ")");
      text(exchange, 500, "끊겼어");
      return;
    }

    Path saved = unique(dir, name);
    try {
      Files.move(part, saved);
    } catch (IOException e) {
      Files.deleteIfExists(part);
      listener.onFailed(name, "저장에 실패했어: " + e.getMessage());
      text(exchange, 500, "저장 실패");
      return;
    }

    listener.onDone(name, saved);
    text(exchange, 200, saved.getFileName().toString());
  }

  /** 흘려보내면서 진행 상황을 알린다. 돌려주는 값은 실제로 옮긴 바이트 수 */
  private long copy(InputStream in, OutputStream out, String name, long total) throws IOException {
    byte[] buffer = new byte[COPY_BUFFER];
    long done = 0;
    long lastReport = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
      done += read;
      long now = System.currentTimeMillis();
      if (now - lastReport >= PROGRESS_INTERVAL_MS) {
        lastReport = now;
        listener.onProgress(name, done, total);
      }
    }
    out.flush();
    listener.onProgress(name, done, total <= 0 ? done : total);
    return done;
  }

  // ── 문지기 ──

  /** 토큰이 맞을 때만 안쪽 처리로 넘긴다. 예외가 새 나가도 서버가 멎지 않게 여기서 막는다 */
  private void guard(HttpExchange exchange, String token, Route route) {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      String prefix = "/" + token;
      // 토큰 뒤엔 아무것도 없거나 / 로 이어져야 한다 ("/tokenXYZ" 같은 건 남의 주소다)
      if (!path.startsWith(prefix)
          || (path.length() > prefix.length() && path.charAt(prefix.length()) != '/')) {
        text(exchange, 404, "여긴 아무것도 없어");
        return;
      }
      route.handle(path.substring(prefix.length()));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private interface Route {
    void handle(String rest) throws IOException;
  }

  // ── 자잘한 것들 ──

  private static HttpServer server() throws IOException {
    // 포트 0 = 비어 있는 걸 알아서 잡아준다. 주소는 안 가린다 — 폰이 들어와야 해서.
    return HttpServer.create(new InetSocketAddress(0), 0);
  }

  private static ExecutorService pool() {
    return Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "lan-share");
      // 위젯이 꺼질 때 붙잡고 늘어지지 않게
      thread.setDaemon(true);
      return thread;
    });
  }

  private static String base(HttpServer server) throws IOException {
    return "http://" + lanIp() + ":" + server.getAddress().getPort();
  }

  /**
   * 폰이 찾아올 수 있는 내 주소. 공유기 쪽으로 나가는 길을 물어보는 게 제일 정확하다 —
   * VMware·WSL·VPN 이 만든 가짜 랜카드가 여럿 꽂혀 있어도 진짜로 쓰는 걸 집어준다.
   * (UDP 소켓의 connect 는 패킷을 안 보낸다. 커널한테 경로만 물어보는 셈)
   */
  static String lanIp() throws IOException {
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.connect(InetAddress.getByName("8.8.8.8"), 53);
      InetAddress local = probe.getLocalAddress();
      if (local instanceof Inet4Address && !local.isAnyLocalAddress() && !local.isLoopbackAddress())
        return local.getHostAddress();
    } catch (Exception ignored) {
      // 인터넷이 끊겨 있으면 길을 못 찾는다. 랜카드를 하나씩 뒤지는 쪽으로 내려간다.
    }

    for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!nic.isUp() || nic.isLoopback() || nic.isVirtual())
        continue;
      for (InetAddress address : Collections.list(nic.getInetAddresses())) {
        if (address instanceof Inet4Address && address.isSiteLocalAddress())
          return address.getHostAddress();
      }
    }
    throw new IOException("와이파이(랜) 주소를 못 찾았어. 네트워크에 연결돼 있는지 봐줘");
  }

  private static String token() {
    byte[] raw = new byte[9];
    new SecureRandom().nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  /** "/3" 또는 "/3/사진.jpg" 에서 3 을 꺼낸다. 숫자가 아니면 -1 */
  private static int index(String rest) {
    String first = rest.substring(1);
    int slash = first.indexOf('/');
    if (slash >= 0)
      first = first.substring(0, slash);
    try {
      return Integer.parseInt(first);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * 내려받을 때 폰에 붙을 파일 이름.
   * 한글 이름은 filename* 쪽(RFC 5987)으로 가고, 그걸 모르는 옛날 브라우저를 위해
   * 아스키만 남긴 이름도 같이 적어준다.
   */
  private static String disposition(String name) {
    String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\\", "_").replace("\"", "_");
    if (ascii.isBlank())
      ascii = "file";
    return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
        + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String query(HttpExchange exchange, String key) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null)
      return null;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(key))
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
    }
    return null;
  }

  private static long length(HttpExchange exchange) {
    String raw = exchange.getRequestHeaders().getFirst("Content-Length");
    try {
      return raw == null ? -1 : Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * 폰이 보내온 이름을 그대로 믿지 않는다 — 경로가 섞여 있으면 다운로드 폴더 밖에다
   * 쓰게 될 수도 있고, 윈도우가 싫어하는 글자가 들어 있으면 저장 자체가 엎어진다.
   */
  static String safeName(String raw) {
    String name = raw == null ? "" : raw.trim();

    // 폴더 부분은 통째로 버린다 ("../../.ssh/config" 같은 것도 여기서 이름만 남는다)
    int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (cut >= 0)
      name = name.substring(cut + 1);

    StringBuilder cleaned = new StringBuilder();
    for (char c : name.toCharArray())
      cleaned.append(c < 0x20 || "<>:\"|?*".indexOf(c) >= 0 ? '_' : c);
    name = cleaned.toString();

    // 윈도우는 이름 끝의 점·공백을 말없이 떼어낸다. 미리 떼어야 저장한 이름과 실제가 안 어긋난다.
    while (name.endsWith(".") || name.endsWith(" "))
      name = name.substring(0, name.length() - 1);

    if (name.isEmpty() || name.equals(".."))
      name = "받은 파일";

    String stem = stem(name);
    String ext = name.substring(stem.length());
    if (RESERVED.contains(stem.toUpperCase(Locale.ROOT)))
      name = "_" + name;
    if (name.length() > NAME_LIMIT)
      name = stem.substring(0, Math.max(1, NAME_LIMIT - ext.length())) + ext;
    return name;
  }

  /** 같은 이름이 이미 있으면 "사진 (1).jpg" 처럼 번호를 붙인다 */
  static Path unique(Path dir, String name) {
    Path target = dir.resolve(name);
    if (!Files.exists(target))
      return target;

    String stem = stem(name);
    String ext = name.substring(stem.length());
    for (int i = 1; i < 1000; i++) {
      Path candidate = dir.resolve(stem + " (" + i + ")" + ext);
      if (!Files.exists(candidate))
        return candidate;
    }
    return dir.resolve(stem + " (" + System.currentTimeMillis() + ")" + ext);
  }

  /** 확장자를 뗀 앞부분. 맨 앞의 점은 확장자가 아니라 숨김 파일 표시라 안 센다 (".gitignore") */
  private static String stem(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  /** 1.4 MB 처럼 읽기 좋게 */
  public static String human(long bytes) {
    if (bytes < 1024)
      return bytes + " B";
    String[] units = { "KB", "MB", "GB", "TB" };
    double value = bytes;
    int unit = -1;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
  }

  // ── 응답 ──

  private static void html(HttpExchange exchange, String body) throws IOException {
    respond(exchange, 200, "text/html; charset=UTF-8", body);
  }

  private static void text(HttpExchange exchange, int status, String body) throws IOException {
    respond(exchange, status, "text/plain; charset=UTF-8", body);
  }

  private static void respond(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
