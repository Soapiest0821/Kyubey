package widget.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Pretendard 를 JavaFX 에 직접 밀어넣는 로더.
 *
 * 먼저 같이 들고 다니는 /style/fonts/Pretendard-*.otf 를 올리고,
 * 거기 없으면 이 컴퓨터에 설치된 걸 찾는다.
 *
 * 윈도우에서 폰트를 "현재 사용자만" 으로 설치하면 파일이
 * %LOCALAPPDATA%\Microsoft\Windows\Fonts 로 들어가는데,
 * JavaFX 21 은 시스템 폰트 폴더(%WINDIR%\Fonts)만 훑기 때문에 이걸 못 본다.
 * 그래서 여기서 Font.loadFont() 로 읽어줘야 CSS 의 'Pretendard' 가 먹는다.
 *
 * 못 찾아도 예외는 안 던진다 — style.css 의 폴백(맑은 고딕)으로 자연스럽게 내려간다.
 */
public final class Fonts {

  public static final String FAMILY = "Pretendard";

  private static final String[] WEIGHTS = {
      "Thin", "ExtraLight", "Light", "Regular", "Medium",
      "SemiBold", "Bold", "ExtraBold", "Black" };

  private Fonts() {
  }

  /** Scene 만들기 전에 한 번만 불러주면 된다. */
  public static void loadPretendard() {
    if (loadBundled())
      return;

    for (Path dir : fontDirectories()) {
      if (!Files.isDirectory(dir))
        continue;

      try (Stream<Path> files = Files.list(dir)) {
        files.filter(Fonts::looksLikePretendard).forEach(Fonts::load);
      } catch (IOException ignored) {
        // 폴더를 못 읽으면 다음 후보로
      }
    }
  }

  /** jar 안에선 폴더를 훑을 수 없어서 굵기 이름을 박아두고 하나씩 연다. 하나라도 올라가면 true */
  private static boolean loadBundled() {
    boolean any = false;
    for (String weight : WEIGHTS) {
      try (InputStream in = Fonts.class.getResourceAsStream("/style/fonts/Pretendard-" + weight + ".otf")) {
        if (in != null && Font.loadFont(in, 12) != null)
          any = true;
      } catch (IOException ignored) {
        // 한 파일 깨졌다고 나머지까지 포기할 필요는 없음
      }
    }
    return any;
  }

  private static boolean looksLikePretendard(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.startsWith("pretendard") && (name.endsWith(".otf") || name.endsWith(".ttf"));
  }

  private static void load(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      Font.loadFont(in, 12);
    } catch (IOException ignored) {
      // 한 파일 깨졌다고 나머지까지 포기할 필요는 없음
    }
  }

  private static List<Path> fontDirectories() {
    List<Path> dirs = new ArrayList<>();

    String localAppData = System.getenv("LOCALAPPDATA");
    if (localAppData != null)
      dirs.add(Paths.get(localAppData, "Microsoft", "Windows", "Fonts")); // 사용자 설치

    String windir = System.getenv("WINDIR");
    dirs.add(Paths.get(windir == null ? "C:\\Windows" : windir, "Fonts")); // 전체 설치

    return dirs;
  }

  /** 실제로 올라왔는지 (디버깅용) */
  public static boolean isPretendardAvailable() {
    return Font.getFamilies().contains(FAMILY);
  }
}
