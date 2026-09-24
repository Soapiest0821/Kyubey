package widget.core;

import widget.index.AppMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class MacroManager {

  public static class SiteEntry {
    public String home;
    public String search;
  }

  public static class AliasEntry {
    public String target;
    public String type;
  }

  /** 콘솔에서 돌릴 명령어 한 줄 + 어느 폴더에서 돌릴지 (dir 은 비워둘 수 있음) */
  public static class CmdEntry {
    public String dir;
    public String cmd;
  }

  private static final String ALIASES_JSON_PATH = "src/main/resources/json/aliases.json";
  private static final String TOP_JSON_PATH = "src/main/resources/json/top.json";
  private static final String APPS_JSON_PATH = "src/main/resources/json/apps.json";
  private static final String EDITORS_JSON_PATH = "src/main/resources/json/editors.json";
  private static final String SITES_JSON_PATH = "src/main/resources/json/sites.json";
  // 검색 주소 없이 열기만 하는 사이트 — 내 것이라 저장소엔 안 올린다
  private static final String BOOKMARKS_JSON_PATH = "src/main/resources/json/bookmarks.json";
  private static final String CMDS_JSON_PATH = "src/main/resources/json/cmds.json";
  private static final String INSTALLED_JSON_PATH = "src/main/resources/json/installed.json";

  private Map<String, AliasEntry> aliases;
  private Map<String, List<String>> commands;
  private Map<String, Map<String, Integer>> freqData;
  private Map<String, String> apps;
  private Map<String, String> editors;
  private Map<String, String> installed;
  private Map<String, SiteEntry> sites;
  private Map<String, CmdEntry> cmds;
  private final ObjectMapper mapper = new ObjectMapper();

  private Resolver resolver;

  public MacroManager(String commandsJsonPath) {
    aliases = loadMap(ALIASES_JSON_PATH, new TypeReference<Map<String, AliasEntry>>() {
    }, new HashMap<>());
    commands = loadMap(commandsJsonPath, new TypeReference<Map<String, List<String>>>() {
    }, new HashMap<>());
    apps = loadMap(APPS_JSON_PATH, new TypeReference<Map<String, String>>() {
    }, new HashMap<>());
    editors = loadMap(EDITORS_JSON_PATH, new TypeReference<Map<String, String>>() {
    }, new HashMap<>());
    sites = loadSites();
    cmds = loadMap(CMDS_JSON_PATH, new TypeReference<Map<String, CmdEntry>>() {
    }, new HashMap<>());
    installed = loadInstalled();
    loadFrequency();

    resolver = new Resolver(aliases, commands, apps, editors, sites, cmds, installed, freqData);
  }

  /**
   * 키워드는 대소문자를 안 가린다 — "YT" 로 쳐도 "yt" 가 걸리게.
   * 파일에 적힌 이름은 그대로 두고, 메모리에 올릴 때만 대소문자 무시 맵에 담는다.
   */
  private <T> Map<String, T> loadMap(String path, TypeReference<Map<String, T>> typeRef, Map<String, T> fallback) {
    Map<String, T> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    try {
      File file = new File(path);
      map.putAll(file.exists() ? mapper.readValue(file, typeRef) : fallback);
    } catch (IOException e) {
      e.printStackTrace();
      map.putAll(fallback);
    }
    return map;
  }

  /** sites.json (검색 되는 곳) + bookmarks.json (열기만 하는 곳) 을 한 맵으로 */
  private Map<String, SiteEntry> loadSites() {
    Map<String, SiteEntry> map = loadMap(SITES_JSON_PATH, new TypeReference<Map<String, SiteEntry>>() {
    }, new HashMap<>());
    map.putAll(loadMap(BOOKMARKS_JSON_PATH, new TypeReference<Map<String, SiteEntry>>() {
    }, new HashMap<>()));
    return map;
  }

  /** installed.json 이 아직 없으면 그 자리에서 한 번 훑어서 만든다 */
  private Map<String, String> loadInstalled() {
    if (!new File(INSTALLED_JSON_PATH).exists()) {
      AppMapper.Run();
    }
    return loadMap(INSTALLED_JSON_PATH, new TypeReference<Map<String, String>>() {
    }, new HashMap<>());
  }

  /** 앱을 새로 깔았을 때 다시 훑기 (위젯에 "scan" 치면 호출됨) */
  public int rescanInstalledApps() {
    AppMapper.Run();
    Map<String, String> fresh = loadMap(INSTALLED_JSON_PATH, new TypeReference<Map<String, String>>() {
    }, new HashMap<>());
    installed.clear();
    installed.putAll(fresh);
    return installed.size();
  }

  /** 폴더를 새로 훑은 뒤 dirs.json 을 다시 읽는다 (처음 설정 / "setup" 에서 저장하면 호출됨) */
  public int reloadDirs(String commandsJsonPath) {
    Map<String, List<String>> fresh = loadMap(commandsJsonPath, new TypeReference<Map<String, List<String>>>() {
    }, new HashMap<>());
    commands.clear();
    commands.putAll(fresh);
    return commands.size();
  }

  /** 키워드별 횟수도 대소문자를 안 가린다 — 예전에 "Dev" / "dev" 로 따로 쌓인 건 합쳐준다 */
  private void loadFrequency() {
    freqData = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    File file = new File(TOP_JSON_PATH);
    if (file.exists()) {
      try {
        Map<String, Map<String, Integer>> raw = mapper.readValue(file,
            new TypeReference<Map<String, Map<String, Integer>>>() {
            });
        raw.forEach((key, counts) -> counts.forEach((path, n) -> freqData
            .computeIfAbsent(key, k -> new HashMap<>())
            .merge(path, n, Integer::sum)));
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      saveFrequency();
    }
  }

  private void saveFrequency() {
    try {
      File file = new File(TOP_JSON_PATH);
      file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, freqData);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public List<SearchResult> resolve(String input) {
    return resolver.resolve(input);
  }

  public void incrementFrequency(String key, String path) {
    freqData.computeIfAbsent(key.trim(), k -> new HashMap<>())
        .merge(path, 1, Integer::sum);
    saveFrequency();
  }

  public void execute(SearchResult result) {
    switch (result.getType()) {
      case FOLDER -> openFolder(result.getPrimaryValue());
      case APP -> runCommand(result.getPrimaryValue());
      case EDITOR_FOLDER -> runEditorWithFolder(result.getPrimaryValue(), result.getSecondaryValue());
      case GOOGLE -> searchGoogle(result.getPrimaryValue());
      case SITE_SEARCH -> runCommand(result.getPrimaryValue());
      case SITE_HOME -> runCommand(result.getPrimaryValue());
      case CMD -> runShellCommand(result.getPrimaryValue(), result.getSecondaryValue());
    }
  }

  private void openFolder(String path) {
    try {
      Runtime.getRuntime().exec(new String[] { "explorer.exe", path });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void runCommand(String command) {
    try {
      // 폴더를 등록해둔 거면 탐색기로 바로 연다
      if (command.startsWith(AppMapper.UWP_PREFIX) || new File(command).isDirectory()) {
        // 스토어 앱(마인크래프트 등)은 start 로는 안 뜨고 explorer 로 넘겨야 열린다
        Runtime.getRuntime().exec(new String[] { "explorer.exe", command });
        return;
      }
      Runtime.getRuntime().exec(new String[] { "cmd", "/c", "start", "", command });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * 등록해둔 명령어를 새 콘솔 창에서 돌린다.
   * 명령어를 cmd 인자로 바로 넘기면 따옴표(git commit -m "..." 같은 것)가 깨져서,
   * 임시 .bat 에 그대로 적어두고 그 파일을 띄운다. cd /d 로 작업 폴더도 같이 챙긴다.
   */
  private void runShellCommand(String command, String dir) {
    try {
      File bat = File.createTempFile("widget-cmd-", ".bat");
      bat.deleteOnExit();

      StringBuilder script = new StringBuilder("@echo off\r\n");
      if (dir != null && !dir.isBlank()) {
        script.append("cd /d \"").append(dir).append("\"\r\n");
      }
      script.append(command).append("\r\n");
      script.append("echo.\r\n");
      script.append("pause\r\n");

      // JDK 18 부터 기본 인코딩이 UTF-8 이라 그대로 쓰면 콘솔(cp949)에서 한글 경로가 깨진다.
      // sun.jnu.encoding 이 OS 쪽 인코딩이라 이걸 따라간다.
      java.nio.file.Files.write(bat.toPath(), script.toString().getBytes(nativeCharset()));

      Runtime.getRuntime().exec(new String[] { "cmd", "/c", "start", "", bat.getAbsolutePath() });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static java.nio.charset.Charset nativeCharset() {
    try {
      return java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", "MS949"));
    } catch (Exception e) {
      return java.nio.charset.Charset.defaultCharset();
    }
  }

  private void runEditorWithFolder(String editorCmd, String folderPath) {
    try {
      Runtime.getRuntime().exec(new String[] { editorCmd, folderPath });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void searchGoogle(String query) {
    try {
      String url = "https://www.google.com/search?q=" +
          java.net.URLEncoder.encode(query, "UTF-8");
      Runtime.getRuntime().exec(new String[] { "cmd", "/c", "start", "", url });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // ✨ 등록 json 들을 다시 읽어서 메모리에 반영 (재시작 없이 즉시 적용!)
  // Resolver 가 이 Map 객체들을 그대로 들고 있어서, 새로 만들지 말고 내용만 갈아끼운다.
  public void reloadRegistry() {
    Map<String, String> newApps = loadMap(APPS_JSON_PATH,
        new TypeReference<Map<String, String>>() {
        }, new HashMap<>());
    apps.clear();
    apps.putAll(newApps);

    Map<String, AliasEntry> newAliases = loadMap(ALIASES_JSON_PATH,
        new TypeReference<Map<String, AliasEntry>>() {
        }, new HashMap<>());
    aliases.clear();
    aliases.putAll(newAliases);

    Map<String, SiteEntry> newSites = loadSites();
    sites.clear();
    sites.putAll(newSites);

    Map<String, CmdEntry> newCmds = loadMap(CMDS_JSON_PATH,
        new TypeReference<Map<String, CmdEntry>>() {
        }, new HashMap<>());
    cmds.clear();
    cmds.putAll(newCmds);
  }

  /** 키워드별로 뭘 몇 번 골랐는지 (지난 명령어 목록의 밑천으로도 쓴다) */
  public Map<String, Map<String, Integer>> getFrequencyData() {
    return freqData;
  }

  // ✨ 다이얼로그에서 "등록된 앱" 목록 보여줄 때 쓰는 getter
  public Map<String, String> getApps() {
    return apps;
  }
}
