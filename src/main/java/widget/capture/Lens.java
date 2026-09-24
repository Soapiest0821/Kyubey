package widget.capture;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 잘라낸 그림을 구글 렌즈로 넘긴다.
 *
 * 우리가 직접 올리지 않고, 브라우저가 올리게 한다. 왜냐면 렌즈는 이제 결과를
 * AI 모드(udm=26, Gemini)로 띄우는데, 그 주소에 붙는 gsessionid/lsessionid 가
 * "올린 쪽의 세션" 에 묶여 있어서다. 예전처럼 자바가 제 HttpClient 로(쿠키 한 톨 없는
 * 남남인 세션으로) 올려놓고 결과 주소만 브라우저에 던지면, 브라우저는 제 구글 쿠키를
 * 들고 그 주소를 여는데 그 세션엔 그림이 안 붙어 있다 — 그림칸이 텅 빈 채로 열리고
 * Gemini 는 볼 게 없어서 아무 말도 안 해준다. 그림은 멀쩡히 올라갔는데도.
 *
 * 그래서 그림을 품은 HTML 한 장을 임시로 써두고 기본 브라우저로 연다. 그 페이지가
 * 렌즈로 폼을 제출하면 올린 것도 브라우저, 결과를 받는 것도 브라우저라 세션이 한 몸이 된다
 * — 사람이 렌즈에 손으로 올린 것과 똑같은 길이 된다.
 *
 * 공식 API 가 아니라 언젠가 모양이 바뀔 수 있는 길인 건 여전하다. 그래서 실패하면 던지고,
 * 부르는 쪽(Capture)이 "클립보드엔 있으니 렌즈에서 Ctrl+V 해" 로 안내하며
 * 업로드 페이지를 열어준다 — 어느 쪽이든 복사는 이미 끝나 있으니 손해는 없다.
 */
final class Lens {

  /** 웹 렌즈가 그림을 받는 창구. 브라우저가 이리로 폼을 제출한다 */
  private static final String UPLOAD = "https://lens.google.com/v3/upload?hl=ko";

  /** 업로드가 막혔을 때 사람이 직접 붙여넣을 수 있게 열어주는 페이지 */
  static final String UPLOAD_PAGE = "https://lens.google.com/upload?hl=ko";

  /** 임시 페이지를 이만큼 지난 것부터 치운다 — 브라우저가 읽고 나면 쓸모가 없다 */
  private static final Duration KEEP = Duration.ofMinutes(5);

  private static final String PREFIX = "widget-lens-";

  private Lens() {
  }

  /**
   * 그림을 품은 임시 페이지를 써두고 그 주소를 돌려준다. 이걸 브라우저로 열면
   * 페이지가 알아서 렌즈에 올리고 결과로 넘어간다.
   *
   * 큰 그림이면 base64 로 굽는 데 잠깐 걸리니 JavaFX 스레드에서 부르지 말 것.
   *
   * @throws IOException 임시 파일을 못 썼을 때
   */
  static String handoff(byte[] png) throws IOException {
    sweep();
    Path page = Files.createTempFile(PREFIX, ".html");
    Files.writeString(page, html(png), StandardCharsets.UTF_8);
    page.toFile().deleteOnExit();
    return page.toUri().toString();
  }

  /**
   * 그림을 렌즈로 밀어 넣는 한 장짜리 페이지.
   *
   * 파일 고르는 칸은 자바스크립트로 값을 못 넣게 막혀 있지만, DataTransfer 로 만든
   * 파일 목록을 통째로 얹는 건 된다 — 사람이 파일을 끌어다 놓은 것과 같은 길이라서.
   * 그렇게 채워서 폼을 제출하면 브라우저가 직접 올리고, 구글이 돌려주는 결과 페이지로
   * 알아서 넘어간다.
   */
  private static String html(byte[] png) {
    String image = Base64.getEncoder().encodeToString(png);
    String action = UPLOAD + "&st=" + System.currentTimeMillis();
    return """
        <!doctype html>
        <html lang="ko">
        <head>
        <meta charset="utf-8">
        <title>구글 렌즈로 보내는 중…</title>
        <style>
          body { margin: 0; height: 100vh; display: flex; flex-direction: column;
                 align-items: center; justify-content: center; gap: 18px;
                 background: #14131a; color: #f2eef7; font-family: system-ui, sans-serif; }
          .msg { font-size: 17px; margin: 0; }
          .sub { font-size: 13px; color: #b6adc4; margin: 0; text-align: center; line-height: 1.7; }
          a, button { color: #ff69a0; }
          button { background: none; border: 1px solid #ff69a0; border-radius: 6px;
                   padding: 4px 10px; font: inherit; font-size: 13px; cursor: pointer; }
        </style>
        </head>
        <body>
        <p class="msg">구글 렌즈로 보내는 중…</p>
        <form id="form" method="post" enctype="multipart/form-data" action="ACTION_URL">
          <input type="file" name="encoded_image" id="pick" hidden>
        </form>
        <p class="sub">안 넘어가면 <button id="again" type="button">다시 보내기</button><br>
        그래도 안 되면 <a href="UPLOAD_PAGE">렌즈 업로드 페이지</a>에 Ctrl+V 로 붙여넣어 (그림은 클립보드에 있어)</p>
        <script>
        const PNG = "IMAGE_BASE64";
        function send() {
          const raw = atob(PNG);
          const bytes = new Uint8Array(raw.length);
          for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
          const box = new DataTransfer();
          box.items.add(new File([bytes], "capture.png", { type: "image/png" }));
          document.getElementById("pick").files = box.files;
          document.getElementById("form").submit();
        }
        document.getElementById("again").addEventListener("click", send);
        send();
        </script>
        </body>
        </html>
        """
        .replace("ACTION_URL", action)
        .replace("UPLOAD_PAGE", UPLOAD_PAGE)
        .replace("IMAGE_BASE64", image);
  }

  /** 지난번에 써둔 임시 페이지 치우기 — 앱이 안 꺼지고 며칠 떠 있어도 쌓이지 않게 */
  private static void sweep() {
    Path dir = Path.of(System.getProperty("java.io.tmpdir"));
    Instant cutoff = Instant.now().minus(KEEP);
    try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, PREFIX + "*.html")) {
      for (Path page : old) {
        try {
          if (Files.getLastModifiedTime(page).toInstant().isBefore(cutoff))
            Files.deleteIfExists(page);
        } catch (IOException ignored) {
          // 남의 손에 잡혀 있는 파일 — 다음 번에 다시 치우면 된다
        }
      }
    } catch (IOException ignored) {
      // 임시 폴더를 못 뒤졌으면 치우는 건 넘어간다. 본 일(업로드)과는 상관없다
    }
  }

  /** 글자로 읽어낸 걸 검색할 땐 그냥 구글 검색 */
  static String googleSearch(String query) {
    return "https://www.google.com/search?q="
        + URLEncoder.encode(query, StandardCharsets.UTF_8);
  }

  /**
   * 기본 브라우저로 주소를 연다.
   *
   * 다른 데서 쓰는 cmd /c start 를 안 쓴다 — 렌즈 결과 주소엔 & 가 잔뜩 붙어 있는데
   * cmd 는 그걸 명령어 구분자로 읽어서 주소가 중간에 잘린다.
   * rundll32 쪽은 cmd 를 안 거치니까 주소를 그대로 넘긴다.
   */
  static void open(String url) throws IOException {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI.create(url));
        return;
      }
    } catch (Exception e) {
      // 데스크톱 연동이 막힌 환경 — 아래 뒷길로 간다
    }
    Runtime.getRuntime().exec(new String[] { "rundll32", "url.dll,FileProtocolHandler", url });
  }
}
