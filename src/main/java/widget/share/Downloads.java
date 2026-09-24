package widget.share;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 폰에서 받은 파일을 떨구는 곳 — 윈도우 다운로드 폴더를 찾는다.
 *
 * 보통은 %USERPROFILE%\Downloads 지만, 폴더를 다른 드라이브로 옮겨 둔 사람도 있어서
 * 레지스트리에 먼저 물어본다. 옮겨 두고도 원래 자리에 빈 폴더가 남아 있는 경우가 흔해서,
 * "있으면 그게 맞겠지" 하고 집으면 엉뚱한 데다 쌓아두게 된다.
 */
public final class Downloads {

  /** 다운로드 폴더의 known folder GUID. 레지스트리엔 이름 대신 이걸로 들어 있다 */
  private static final String GUID = "{374DE290-123F-4565-9164-39C4925E467B}";

  private static final String REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders";

  private Downloads() {
  }

  /**
   * 파일을 떨굴 폴더. 어디에도 없으면 %USERPROFILE%\Downloads 를 만들어서라도 돌려준다
   * (받아 놓고 둘 데가 없는 것보단 낫다).
   */
  public static Path dir() throws IOException {
    Path fromRegistry = fromRegistry();
    if (fromRegistry != null && Files.isDirectory(fromRegistry))
      return fromRegistry;

    Path home = Paths.get(System.getProperty("user.home"), "Downloads");
    if (!Files.isDirectory(home))
      Files.createDirectories(home);
    return home;
  }

  /**
   * reg.exe 에 다운로드 폴더 위치를 물어본다. 못 물어봤으면 null — 부르는 쪽이 기본 자리로 내려간다.
   * (윈도우 전용이라 다른 OS 에선 reg 를 못 찾고 조용히 null 이 된다)
   */
  private static Path fromRegistry() {
    try {
      Process proc = new ProcessBuilder("reg", "query", REG_KEY, "/v", GUID)
          .redirectErrorStream(true)
          .start();

      String value = null;
      // reg.exe 는 콘솔 코드페이지(한국어 윈도우면 949)로 뱉는다. 경로에 한글이 들어갈 수 있어서
      // UTF-8 로 읽으면 깨진다 — 기본 인코딩(sun.jnu.encoding 을 따라가는 default charset)으로 읽는다.
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(proc.getInputStream(), Charset.defaultCharset()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          // "    {374DE290-...}    REG_EXPAND_SZ    C:\Users\USER\Downloads"
          int at = line.indexOf("REG_");
          if (at < 0 || !line.contains(GUID))
            continue;
          int tab = line.indexOf("    ", at);
          if (tab < 0)
            continue;
          value = line.substring(tab).trim();
        }
      }
      proc.waitFor();

      if (value == null || value.isEmpty())
        return null;
      // REG_EXPAND_SZ 라 %USERPROFILE% 같은 게 안 풀린 채로 들어 있다
      return Paths.get(expand(value));
    } catch (IOException | InvalidPathException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** 값에 박혀 있는 %변수% 를 환경변수로 바꿔준다 */
  private static String expand(String raw) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < raw.length()) {
      int open = raw.indexOf('%', i);
      int close = open < 0 ? -1 : raw.indexOf('%', open + 1);
      if (open < 0 || close < 0) {
        out.append(raw, i, raw.length());
        break;
      }
      out.append(raw, i, open);
      String name = raw.substring(open + 1, close);
      String value = System.getenv(name);
      out.append(value != null ? value : raw.substring(open, close + 1));
      i = close + 1;
    }
    return out.toString();
  }
}
