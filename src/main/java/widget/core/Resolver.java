package widget.core;

import java.util.*;
import java.util.stream.*;

public class Resolver {
  private static final java.util.regex.Pattern DOMAIN_PATTERN = java.util.regex.Pattern
      .compile("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(/\\S*)?$");

  private final Map<String, MacroManager.AliasEntry> aliases;
  private final Map<String, List<String>> commands;
  private final Map<String, String> apps;
  private final Map<String, String> editors;
  private final Map<String, MacroManager.SiteEntry> sites;
  private final Map<String, MacroManager.CmdEntry> cmds;
  private final Map<String, String> installed;
  private final Map<String, Map<String, Integer>> freqData;

  /** 설치된 앱 후보를 한 번에 몇 개까지 보여줄지 */
  private static final int INSTALLED_LIMIT = 6;

  public Resolver(
      Map<String, MacroManager.AliasEntry> aliases,
      Map<String, List<String>> commands,
      Map<String, String> apps,
      Map<String, String> editors,
      Map<String, MacroManager.SiteEntry> sites,
      Map<String, MacroManager.CmdEntry> cmds,
      Map<String, String> installed,
      Map<String, Map<String, Integer>> freqData) {
    this.aliases = aliases;
    this.commands = commands;
    this.apps = apps;
    this.editors = editors;
    this.sites = sites;
    this.cmds = cmds;
    this.installed = installed;
    this.freqData = freqData;
  }

  private String resolveAlias(String word, String expectedType) {
    MacroManager.AliasEntry entry = aliases.get(word);
    if (entry != null && entry.type.equals(expectedType)) {
      return entry.target;
    }
    return word;
  }

  public List<SearchResult> resolve(String input) {
    String trimmed = input.trim();
    if (trimmed.isEmpty())
      return Collections.emptyList();

    List<SearchResult> results = new ArrayList<>();
    String[] parts = trimmed.split("\\s+", 2);

    // 위젯 자체 명령어 (new / restart / scan / 채팅 삭제 …) 도 목록에 같이 올린다.
    // 첫 마디만 보는 게 아니라 친 것 전부로 찾는다 — 이름에 띄어쓰기가 들어간 항목("채팅 삭제")도 있어서.
    // 띄어 친 입력은 어느 이름과도 안 맞아서("edit yt"), 한 마디짜리만 칠 때와 달라지는 건 없다.
    results.addAll(matchBuiltins(trimmed));

    if (parts.length == 1) {
      String rawWord = parts[0];
      String appWord = resolveAlias(rawWord, "app");
      String siteWord = resolveAlias(rawWord, "site");
      String cmdWord = resolveAlias(rawWord, "cmd");

      if (apps.containsKey(appWord)) {
        results.add(new SearchResult(
            "🚀 " + rawWord + " 실행하기",
            SearchResult.Type.APP,
            apps.get(appWord),
            null));
      }

      if (sites.containsKey(siteWord)) {
        MacroManager.SiteEntry site = sites.get(siteWord);
        results.add(new SearchResult(
            "🌐 " + rawWord + " 열기",
            SearchResult.Type.SITE_HOME,
            site.home,
            null));
      }

      if (cmds.containsKey(cmdWord)) {
        MacroManager.CmdEntry entry = cmds.get(cmdWord);
        results.add(new SearchResult(
            "🖥 " + entry.cmd + " 실행하기",
            SearchResult.Type.CMD,
            entry.cmd,
            entry.dir));
      }

      if (commands.containsKey(appWord)) {
        for (String path : getAllResultsSorted(appWord)) {
          results.add(new SearchResult("📁 " + path, SearchResult.Type.FOLDER, path, null));
        }
      }

    } else {
      String firstKey = parts[0];
      String secondArg = parts[1];
      String siteWord = resolveAlias(firstKey, "site");
      String cmdWord = resolveAlias(firstKey, "cmd");

      // "edit yt" — 뒤는 통째로 키워드로 넘긴다 (띄어쓰기 들어간 옛날 항목도 있어서)
      if (Builtin.EDIT.keyword().equalsIgnoreCase(firstKey))
        results.add(editResult(secondArg));

      // 에디터 조합 (zed dev)
      if (editors.containsKey(firstKey) && commands.containsKey(secondArg)) {
        for (String path : getAllResultsSorted(secondArg)) {
          results.add(new SearchResult(
              "🛠 " + firstKey + "(으)로 " + secondArg + " 열기 [" + path + "]",
              SearchResult.Type.EDITOR_FOLDER,
              editors.get(firstKey),
              path));
        }
      }

      // 사이트 검색 조합 (yt 고양이)
      if (sites.containsKey(siteWord)) {
        MacroManager.SiteEntry site = sites.get(siteWord);
        if (site.search != null) {
          String query = java.net.URLEncoder.encode(secondArg, java.nio.charset.StandardCharsets.UTF_8);
          // %s 가 있으면 그 자리에 끼운다 (나무위키처럼 경로에 들어가는 곳은 공백이 + 가 아니라 %20 이어야 함)
          String url = site.search.contains("%s")
              ? site.search.replace("%s", query.replace("+", "%20"))
              : site.search + query;
          results.add(new SearchResult(
              "🔎 " + firstKey + "에서 " + secondArg + " 검색",
              SearchResult.Type.SITE_SEARCH,
              url,
              null));
        }
      }

      // 등록해둔 명령어 뒤에 인자 붙이기 (build --release 처럼)
      if (cmds.containsKey(cmdWord)) {
        MacroManager.CmdEntry entry = cmds.get(cmdWord);
        String full = entry.cmd + " " + secondArg;
        results.add(new SearchResult(
            "🖥 " + full + " 실행하기",
            SearchResult.Type.CMD,
            full,
            entry.dir));
      }
    }

    // 등록 안 해둔, 그냥 설치돼 있는 앱들 (마인크래프트 / 한글 2024 같은 것)
    results.addAll(matchInstalled(trimmed, results));

    if (DOMAIN_PATTERN.matcher(trimmed).matches()) {
      String url = trimmed;
      if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "https://" + url;
      }
      results.add(new SearchResult("🌐 " + trimmed + " 열기", SearchResult.Type.SITE_HOME, url, null));
    }

    results.add(new SearchResult("🔍 " + trimmed + " 검색하기!", SearchResult.Type.GOOGLE, trimmed, null));

    // 맨 밑 한 줄은 위가 어떤지에 따라 갈린다.
    // 검색하기밖에 안 떴다 = 아무것도 못 찾았다는 뜻이라 새로 등록하는 길(new) 을,
    // 뭐라도 찾았으면 그걸 고치러 가는 길(edit) 을 열어둔다.
    String keyword = editKeyword(trimmed, parts[0]);
    if (results.size() == 1)
      results.add(newResult(trimmed));
    else if (!Builtin.EDIT.keyword().equalsIgnoreCase(keyword))
      results.add(editResult(keyword));

    return results;
  }

  /**
   * 위젯 자체 명령어 찾기.
   * 정확히 치면 언제나, 앞글자만 쳤을 땐 두 글자부터 — 한 글자에 끼어들면 앱 검색을 밀어내서.
   * 친 것 전부를 받는다 — "채팅 삭제" 처럼 이름 자체에 띄어쓰기가 들어간 게 있어서.
   *
   * "채팅 삭제" 만 앞글자 매칭에서 뺀다. 다른 명령어야 잘못 골라도 창 한 번 뜨고 마는데
   * 이건 되돌릴 수가 없어서, "채팅" 까지만 치고 엔터를 눌렀다가 대화가 날아가면 곤란하다.
   * 이름을 통째로 쳐야 목록에 오른다.
   *
   * 이름은 하나가 아닐 수 있다 ("채팅 삭제" = "대화 삭제"). 어느 이름으로 찾았든
   * 목록엔 대표 이름으로 한 줄만 오른다 — Builtin.keywords 참고.
   */
  private static List<SearchResult> matchBuiltins(String typed) {
    String q = typed.toLowerCase();
    List<SearchResult> out = new ArrayList<>();
    for (Builtin builtin : Builtin.values()) {
      boolean byPrefix = q.length() >= 2 && builtin != Builtin.CLEAR_CHAT
          && builtin.startsWith(q);
      if (builtin.answersTo(q) || byPrefix)
        out.add(builtin.asResult());
    }
    return out;
  }

  /**
   * 맨 밑 "수정하기" 줄이 데려갈 키워드.
   * 보통은 첫 마디지만, 친 것 전부가 아는 이름이면 그걸 통째로 넘긴다 —
   * "Android Studio" 처럼 이름에 띄어쓰기가 있는 앱을 첫 마디("Android")로 잘라 보내면
   * 그런 이름은 없다며 되돌아오기 때문.
   */
  private String editKeyword(String trimmed, String firstWord) {
    return knownName(trimmed) ? trimmed : firstWord;
  }

  /** 등록해뒀거나(앱·사이트·명령어·별칭) 그냥 깔려 있는(installed) 이름인가 */
  private boolean knownName(String word) {
    if (apps.containsKey(word) || sites.containsKey(word)
        || cmds.containsKey(word) || aliases.containsKey(word))
      return true;
    for (String name : installed.keySet()) {
      if (name.equalsIgnoreCase(word))
        return true;
    }
    return false;
  }

  /**
   * 등록 창을 새로 등록 모드로 여는 항목.
   * 아무것도 못 찾았을 때만 뜨는 줄이라, 찾다 만 그 글자가 곧 등록할 키워드다 — 같이 들려 보낸다.
   */
  private static SearchResult newResult(String keyword) {
    String shown = keyword.trim();
    return new SearchResult(
        "📦 " + (shown.isEmpty() ? "" : shown + " ") + Builtin.NEW.label(),
        SearchResult.Type.BUILTIN,
        Builtin.NEW.keyword(),
        shown);
  }

  /** 등록 창을 수정 모드로 여는 항목 */
  private static SearchResult editResult(String keyword) {
    String shown = keyword.trim();
    return new SearchResult(
        "📦 " + (shown.isEmpty() ? "" : shown + " ") + "수정하기",
        SearchResult.Type.BUILTIN,
        Builtin.EDIT.keyword(),
        shown,
        null);
  }

  /**
   * installed.json 을 이름으로 느슨하게 찾는다.
   * 정확히 일치 > 앞부분 일치 > 중간 포함 순서, 같은 등급이면 이름 짧은 게 위로.
   * 한 글자만 쳤을 땐 "포함"까지 가면 너무 쏟아져서 앞부분 일치까지만 본다.
   */
  private List<SearchResult> matchInstalled(String query, List<SearchResult> existing) {
    if (installed.isEmpty())
      return Collections.emptyList();

    String q = query.toLowerCase();
    Set<String> already = existing.stream()
        .filter(r -> r.getType() == SearchResult.Type.APP)
        .map(SearchResult::getPrimaryValue)
        .collect(Collectors.toSet());

    List<Map.Entry<String, String>> exact = new ArrayList<>();
    List<Map.Entry<String, String>> prefix = new ArrayList<>();
    List<Map.Entry<String, String>> partial = new ArrayList<>();

    for (Map.Entry<String, String> entry : installed.entrySet()) {
      if (already.contains(entry.getValue()))
        continue;

      String name = entry.getKey().toLowerCase();
      if (name.equals(q))
        exact.add(entry);
      else if (name.startsWith(q))
        prefix.add(entry);
      else if (q.length() >= 2 && name.contains(q))
        partial.add(entry);
    }

    // 같은 걸 자주 실행했으면 위로 올려준다 (폴더 랭킹이랑 같은 방식)
    Map<String, Integer> freqMap = freqData.getOrDefault(query.trim(), Collections.emptyMap());
    Comparator<Map.Entry<String, String>> ranking = Comparator
        .comparingInt((Map.Entry<String, String> e) -> -freqMap.getOrDefault(e.getValue(), 0))
        .thenComparingInt(e -> e.getKey().length())
        .thenComparing(Map.Entry::getKey);
    exact.sort(ranking);
    prefix.sort(ranking);
    partial.sort(ranking);

    return Stream.of(exact, prefix, partial)
        .flatMap(List::stream)
        .limit(INSTALLED_LIMIT)
        .map(e -> new SearchResult(
            "🚀 " + e.getKey() + " 실행하기",
            SearchResult.Type.APP,
            e.getValue(),
            null,
            e.getKey()))
        .collect(Collectors.toList());
  }

  private List<String> getAllResultsSorted(String key) {
    Map<String, Integer> freqMap = freqData.getOrDefault(key, new HashMap<>());
    List<String> sortedByFreq = freqMap.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    List<String> rawList = commands.getOrDefault(key, Collections.emptyList());
    List<String> remaining = rawList.stream()
        .distinct()
        .filter(path -> !freqMap.containsKey(path))
        .collect(Collectors.toList());

    List<String> result = new ArrayList<>(sortedByFreq);
    result.addAll(remaining);
    return result;
  }
}
