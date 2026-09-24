package widget.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 프로젝트 옆에 놓인 .env 를 읽어주는 자리.
 *
 * 처음엔 GeminiClient 혼자 읽었는데, 받아쓰기(Whisper)도 자기 설정을 여기서 찾게 되면서
 * 같은 파일을 두 군데서 파싱하게 됐다. 읽는 규칙(따옴표 벗기기, export 떼기, # 주석)이
 * 두 벌로 갈라지면 한쪽만 고쳐질 게 뻔해서 이리로 모았다.
 *
 * 한 번 읽어두고 계속 들고 있는다. 위젯 안에서 고치는 길(keys 창 → rewrite)만은
 * 쓰고 나서 들고 있던 걸 버려서, 다음에 부를 때 새로 읽게 한다.
 *
 * 파일을 찾는 곳은 세 군데다: 지금 실행 위치(user.dir), jar 가 놓인 폴더, 그 윗 폴더.
 * mvn javafx:run 으로 띄우면 첫 번째에서 잡히고, target\ 에 구운 jar 로 띄우면 뒤엣것이 잡힌다.
 * 모델 파일(models\) 처럼 .env 말고 다른 것도 같은 자리에서 찾기 때문에 homes() 를 열어뒀다.
 */
public final class Env {

  private static final String FILE = ".env";

  /** 한 번 읽은 .env. 두 번째부터는 이걸 그대로 준다 */
  private static Map<String, String> cached;

  private Env() {
  }

  /** .env 전체를 KEY=VALUE 맵으로. 파일이 없으면 빈 맵 */
  public static synchronized Map<String, String> all() {
    if (cached == null)
      cached = read();
    return cached;
  }

  /** 들고 있던 걸 버린다 — 다음 all() 이 파일을 새로 읽는다 */
  public static synchronized void reload() {
    cached = null;
  }

  /**
   * owns 가 맞다고 하는 이름의 줄을 전부 걷어내고, 그 자리에 entries 를 차례로 적는다.
   * 나머지 줄(주석, GEMINI_MODEL, WHISPER_… 같은 딴 설정)은 순서까지 그대로 둔다.
   * 걷어낸 줄이 하나도 없었으면 맨 위에 적는다. .env 가 아직 없으면 실행 위치에 새로 만든다.
   */
  public static synchronized void rewrite(Predicate<String> owns, Map<String, String> entries) throws IOException {
    Path file = find(FILE);
    if (file == null)
      file = homes().get(0).resolve(FILE);

    List<String> lines = Files.isRegularFile(file)
        ? Files.readAllLines(file, StandardCharsets.UTF_8)
        : new ArrayList<>();

    List<String> fresh = new ArrayList<>();
    entries.forEach((key, value) -> fresh.add(key + "=" + value));

    List<String> out = new ArrayList<>();
    int at = -1;
    for (String line : lines) {
      String key = keyOf(line);
      if (key != null && owns.test(key)) {
        if (at < 0)
          at = out.size();
        continue;
      }
      out.add(line);
    }
    if (at < 0) {
      at = 0;
      // 맨 위에 새로 끼울 땐 딴 설정이랑 한 줄 띄운다
      if (!fresh.isEmpty() && !out.isEmpty() && !out.get(0).isBlank())
        out.add(0, "");
    }
    out.addAll(at, fresh);

    Files.write(file, out, StandardCharsets.UTF_8);
    reload();
  }

  /** 한 줄에서 이름만 떼어 본다. 빈 줄·주석·= 없는 줄이면 null */
  private static String keyOf(String line) {
    String trimmed = line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("#"))
      return null;
    if (trimmed.startsWith("export "))
      trimmed = trimmed.substring("export ".length()).trim();
    int eq = trimmed.indexOf('=');
    return eq <= 0 ? null : trimmed.substring(0, eq).trim();
  }

  /** 한 줄만 꺼내 본다. 없거나 빈칸뿐이면 null */
  public static String get(String key) {
    String value = all().get(key);
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * 딸린 파일을 찾아볼 폴더들 — 실행 위치, jar 가 놓인 곳, 그 윗 폴더 순.
   * 앞엣것부터 보므로 "지금 작업하는 프로젝트" 가 늘 먼저다.
   */
  public static List<Path> homes() {
    List<Path> homes = new ArrayList<>();
    homes.add(Paths.get(System.getProperty("user.dir")));

    Path jarDir = jarDirectory();
    if (jarDir != null) {
      homes.add(jarDir);
      if (jarDir.getParent() != null)
        homes.add(jarDir.getParent());
    }
    return homes;
  }

  /** homes() 를 차례로 뒤져서 이 이름(models\ggml-small.bin 같은 상대경로)을 찾는다. 없으면 null */
  public static Path find(String relative) {
    for (Path home : homes()) {
      Path candidate = home.resolve(relative);
      if (Files.exists(candidate))
        return candidate;
    }
    return null;
  }

  private static Map<String, String> read() {
    Map<String, String> values = new HashMap<>();
    Path file = find(FILE);
    if (file == null || !Files.isRegularFile(file))
      return values;

    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String key = keyOf(line);
        if (key == null)
          continue;

        String value = line.substring(line.indexOf('=') + 1).trim();
        // 따옴표로 감싸놨으면 벗겨준다
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
          value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
      }
    } catch (IOException e) {
      // 깨진 .env 는 없는 것과 똑같이 친다 — 읽는 쪽이 저마다 "설정이 없다" 고 안내한다
      return values;
    }
    return values;
  }

  private static Path jarDirectory() {
    try {
      URI location = Env.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      Path path = Paths.get(location);
      return Files.isDirectory(path) ? path : path.getParent();
    } catch (Exception e) {
      return null;
    }
  }
}
