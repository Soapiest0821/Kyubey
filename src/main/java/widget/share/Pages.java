package widget.share;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.io.File;

/**
 * 폰 브라우저에 뜨는 화면들. 틀은 resources/web/*.html 에 있고 여기선 빈칸만 채운다.
 *
 * HTML 을 자바 문자열로 들고 있으면 손대기가 괴로워서 파일로 뺐다.
 * 빈칸은 {{이름}} 꼴 — 템플릿 엔진을 끼울 만큼 복잡하지 않아서 그냥 갈아끼운다.
 */
final class Pages {

  private Pages() {
  }

  /** 파일 올리는 화면 (파일 수신) */
  static String upload(String token) {
    return read("/web/upload.html").replace("{{TOKEN}}", token);
  }

  /** 여러 개를 내걸었을 때 먼저 뜨는 목록 화면 (파일 송신) */
  static String fileList(String token, List<File> files) {
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < files.size(); i++) {
      File file = files.get(i);
      rows.append("<li><a href=\"/").append(token).append('/').append(i).append("\">")
          .append("<span class=\"name\">").append(escape(file.getName())).append("</span>")
          .append("<span class=\"size\">").append(LanShare.human(file.length())).append("</span>")
          .append("</a></li>\n");
    }
    return read("/web/files.html")
        .replace("{{COUNT}}", String.valueOf(files.size()))
        .replace("{{ROWS}}", rows.toString());
  }

  /**
   * 파일 이름이 태그로 읽히지 않게. 폰에서 올린 이름은 여기까지 안 오지만
   * (목록은 내 컴퓨터에서 고른 파일들이다) 이름에 &나 <가 든 건 흔해서 막아둔다.
   */
  private static String escape(String raw) {
    return raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /** 빌드에 같이 들어가는 파일이라 없으면 그건 빌드가 깨진 거다 — 조용히 넘기지 않는다 */
  private static String read(String resource) {
    try (InputStream in = Pages.class.getResourceAsStream(resource)) {
      if (in == null)
        throw new IOException(resource + " 를 못 찾았어");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
