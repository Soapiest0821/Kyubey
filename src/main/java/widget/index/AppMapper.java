package widget.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 이미 설치돼 있는 프로그램들을 훑어서 installed.json 으로 뽑아내는 애.
 * FolderMapper 가 폴더를 훑는 것처럼, 얘는 앱을 훑는다.
 *
 * 두 종류를 같이 모은다:
 * - 바로가기(.lnk): 시작 메뉴 / 바탕화면 → 값은 .lnk 의 전체 경로
 * - 스토어 앱(UWP): 마인크래프트처럼 .lnk 가 아예 없는 애들
 * → 값은 "shell:AppsFolder\<AppUserModelID>"
 */
public class AppMapper {

  public static final String UWP_PREFIX = "shell:AppsFolder\\";

  private static final String INSTALLED_JSON_PATH = "src/main/resources/json/installed.json";

  /** 앱이 아니라 곁다리(제거 프로그램, 설명서 같은 것)인 바로가기 걸러내기 */
  private static final Pattern NOISE = Pattern.compile(
      "(?i).*(uninstall|설치 제거|프로그램 삭제|release notes|readme|documentation|dokumentation"
          + "|user manual|매뉴얼|사용 설명서|license|라이선스|homepage|\\bfaq\\b).*");

  /** 시작 프로그램 폴더는 앱 목록이라기보단 자동실행 설정이라 통째로 건너뜀 */
  private static final Set<String> SKIP_DIRS = Set.of("startup", "시작프로그램");

  public static void Run() {
    Map<String, String> installed = scan();
    try {
      File file = new File(INSTALLED_JSON_PATH);
      File parent = file.getParentFile();
      if (parent != null)
        parent.mkdirs();

      ObjectMapper mapper = new ObjectMapper();
      mapper.enable(SerializationFeature.INDENT_OUTPUT);
      mapper.writeValue(file, new TreeMap<>(installed));

      System.out.println("설치된 앱 " + installed.size() + "개 저장 완료: " + file.getAbsolutePath());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 앱 이름 → 실행 대상(.lnk 경로 또는 shell:AppsFolder\AUMID) */
  public static Map<String, String> scan() {
    Map<String, String> result = new LinkedHashMap<>();
    collectShortcuts(result);
    collectStoreApps(result);
    return result;
  }

  // ── .lnk 바로가기 훑기 ──
  private static void collectShortcuts(Map<String, String> result) {
    for (Path root : shortcutRoots()) {
      if (!Files.isDirectory(root))
        continue;

      try (Stream<Path> paths = Files.walk(root)) {
        paths.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".lnk"))
            .filter(p -> !isUnderSkippedDir(root, p))
            .forEach(p -> {
              String fileName = p.getFileName().toString();
              String name = fileName.substring(0, fileName.length() - ".lnk".length());
              if (NOISE.matcher(name).matches())
                return;
              // 같은 이름이 여러 군데 있으면 먼저 찾은 쪽을 쓴다
              result.putIfAbsent(name, p.toAbsolutePath().toString());
            });
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private static List<Path> shortcutRoots() {
    List<Path> roots = new ArrayList<>();
    addIfPresent(roots, System.getenv("ProgramData"), "Microsoft", "Windows", "Start Menu", "Programs");
    addIfPresent(roots, System.getenv("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs");
    addIfPresent(roots, System.getProperty("user.home"), "Desktop");
    addIfPresent(roots, System.getenv("PUBLIC"), "Desktop");
    return roots;
  }

  private static void addIfPresent(List<Path> roots, String base, String... more) {
    if (base == null || base.isBlank())
      return;
    roots.add(Paths.get(base, more));
  }

  private static boolean isUnderSkippedDir(Path root, Path file) {
    Path relative = root.relativize(file);
    for (int i = 0; i < relative.getNameCount() - 1; i++) {
      if (SKIP_DIRS.contains(relative.getName(i).toString().toLowerCase()))
        return true;
    }
    return false;
  }

  // ── 스토어(UWP) 앱 훑기 ──
  // 마인크래프트처럼 .lnk 가 없는 앱은 Get-StartApps 로만 잡힌다.
  private static void collectStoreApps(Map<String, String> result) {
    String script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
        + "Get-StartApps | Where-Object { $_.AppID -like '*!*' } | "
        + "ForEach-Object { $_.Name + \"`t\" + $_.AppID }";

    // 따옴표 이스케이프 지옥을 피하려고 -EncodedCommand (UTF-16LE + Base64) 로 넘김
    String encoded = Base64.getEncoder()
        .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

    try {
      ProcessBuilder pb = new ProcessBuilder(
          "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
      pb.redirectErrorStream(false);
      Process process = pb.start();

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          int tab = line.indexOf('\t');
          if (tab <= 0)
            continue;
          String name = line.substring(0, tab).trim();
          String appId = line.substring(tab + 1).trim();
          if (name.isEmpty() || appId.isEmpty() || NOISE.matcher(name).matches())
            continue;
          result.putIfAbsent(name, UWP_PREFIX + appId);
        }
      }
      process.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
