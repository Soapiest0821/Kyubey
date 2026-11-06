package kyubey.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Makeit 이 사용자 데이터를 두는 자리 — %APPDATA%\Makeit.
 *
 * 설치 폴더는 업데이트나 삭제 때 통째로 바뀌므로 대화, 키, 설정을 둘 수 없다.
 * 시험할 땐 -Dmakeit.data=폴더 로 다른 곳을 쓴다.
 */
public final class AppPaths {

  /** 끝에 / 가 붙은 데이터 폴더 */
  public static final String ROOT = root();
  /** 끝에 / 가 붙은 json 폴더. 예전 src/main/resources/json/ 자리 */
  public static final String JSON = ROOT + "json/";

  /** 앱과 함께 나가서 처음 켤 때 깔아 주는 기본 파일 */
  private static final String[] SEEDS = { "sites.json", "editors.json" };

  static {
    seed();
  }

  private AppPaths() {
  }

  private static String root() {
    String override = System.getProperty("makeit.data");
    if (override != null && !override.isBlank())
      return withSlash(override);
    String appData = System.getenv("APPDATA");
    String base = appData != null ? appData : System.getProperty("user.home");
    return withSlash(Path.of(base, "Makeit").toString());
  }

  private static String withSlash(String path) {
    return path.replace('\\', '/').replaceAll("/+$", "") + "/";
  }

  private static void seed() {
    try {
      Files.createDirectories(Path.of(JSON));
      for (String name : SEEDS) {
        Path target = Path.of(JSON, name);
        if (Files.exists(target))
          continue;
        try (InputStream in = AppPaths.class.getResourceAsStream("/json/" + name)) {
          if (in != null)
            Files.copy(in, target);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("데이터 폴더를 못 만들었어: " + JSON, e);
    }
  }
}
