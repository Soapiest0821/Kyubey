package kyubey.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위젯 색 테마 — "설정" 에서 고른다.
 *
 * style.css 는 핑크로 짜여 있어서, 테마는 그 핑크 색들을 다른 색으로 바꿔 끼운 사본이다
 * (JavaFX CSS 엔 알파만 바꾸는 계산식이 없어서 변수 하나로는 rgba 들을 못 돌린다).
 * 그라데이션 테마는 거기에 창 바탕 규칙을 덧붙여 덮어쓴다.
 */
public enum Theme {

  PINK("pink", "핑크", Map.of(), null),

  GRAY("gray", "회색", remap(
      "255, 181, 217", "206, 209, 216",
      "255, 105, 160", "112, 119, 134",
      "154, 96, 125", "104, 110, 122",
      "122, 85, 102", "64, 70, 82",
      "#ff69a0", "#707786",
      "#ff85b3", "#8a909d",
      "#4a2c3a", "#2d3038",
      "#7a5566", "#5c616c",
      "#3a1f2c", "#22252b",
      "#b32b5e", "#454c5b",
      "#9b7383", "#868b95",
      "#c2557f", "#626978",
      "#d6417f", "#525867",
      "#c86bd6", "#9da3b0",
      "#ffe3ef", "#eaecf0",
      "#fff0f6", "#f4f5f7",
      "#ffd2e6", "#e2e4e8",
      "#ffe4f0", "#eceef1",
      "#e0136f", "#2f3440"), null),

  SKY("sky", "하늘", remap(
      "255, 181, 217", "176, 214, 245",
      "255, 105, 160", "74, 144, 217",
      "154, 96, 125", "90, 125, 160",
      "122, 85, 102", "60, 85, 110",
      "#ff69a0", "#4a90d9",
      "#ff85b3", "#6aa6e3",
      "#4a2c3a", "#23384d",
      "#7a5566", "#50657a",
      "#3a1f2c", "#182a3c",
      "#b32b5e", "#2a6aa8",
      "#9b7383", "#7d8fa3",
      "#c2557f", "#3d7fc2",
      "#d6417f", "#3478c0",
      "#c86bd6", "#7b8fe0",
      "#ffe3ef", "#e3f0fc",
      "#fff0f6", "#f0f7fe",
      "#ffd2e6", "#d2e8ff",
      "#ffe4f0", "#e4f1ff",
      "#e0136f", "#1559a8"), null),

  YELLOW("yellow", "노랑", remap(
      "255, 181, 217", "255, 224, 140",
      "255, 105, 160", "240, 176, 20",
      "154, 96, 125", "160, 125, 50",
      "122, 85, 102", "120, 95, 40",
      "#ff69a0", "#f5b82e",
      "#ff85b3", "#f8c95a",
      "#4a2c3a", "#4a3a1a",
      "#7a5566", "#7a6640",
      "#3a1f2c", "#33270d",
      "#b32b5e", "#a8700a",
      "#9b7383", "#9b8a63",
      "#c2557f", "#c98a12",
      "#d6417f", "#c47f08",
      "#c86bd6", "#ffa04a",
      "#ffe3ef", "#fff3cc",
      "#fff0f6", "#fff9e6",
      "#ffd2e6", "#ffe9a8",
      "#ffe4f0", "#fff4d1",
      "#e0136f", "#d9480f"), """
      /* 노란 바탕에 흰 글씨는 안 읽혀서, 노랑을 칠한 자리의 글씨만 진한 갈색으로 */
      .chat-theirs, .dialog-button-primary, .llm-mode:selected, .goal-chip:selected,
      .goal-chip.goal-chip-done, .goal-chip.goal-chip-done:selected,
      .unread-badge, .list-cell:filled:selected, .chat-note {
          -fx-text-fill: #4a3410;
      }
      .chat-theirs .chat-text {
          -fx-fill: #4a3410;
      }
      """),

  /**
   * 라벤더 → 하늘 → 민트로 흐르는 바탕. 핑크는 하나도 안 남기고 파스텔 보라로 바꿔 끼우고,
   * 수다 말풍선 · 목표 칸처럼 하나씩 떨어지는 것들은 CYCLE 색을 차례로 돌려 칠한다 (cycleClass).
   * 돌리는 색은 할 일 색(SettingsDialog.TODO_THEMES)과 겹치지 않게 따로 고른 연한 색들이다.
   */
  PASTEL("pastel", "파스텔 그라데이션", remap(
      "255, 181, 217", "214, 204, 245",
      "255, 105, 160", "160, 140, 220",
      "154, 96, 125", "130, 120, 170",
      "122, 85, 102", "100, 95, 130",
      "#ff69a0", "#a594e0",
      "#ff85b3", "#b8aaea",
      "#4a2c3a", "#3d3a55",
      "#7a5566", "#6a6788",
      "#3a1f2c", "#2c2a42",
      "#b32b5e", "#6f5fb8",
      "#9b7383", "#8c89a8",
      "#c2557f", "#8a78d0",
      "#d6417f", "#7d6ad0",
      "#c86bd6", "#8fc7ee",
      "#ffe3ef", "#ece8fb",
      "#fff0f6", "#f5f3fd",
      "#ffd2e6", "#e2dcf7",
      "#ffe4f0", "#ede9fb",
      "#e0136f", "#c75a4a"), """
      .widget-root, .dialog-root {
          -fx-background-color: linear-gradient(to bottom right,
              rgba(218, 206, 255, 0.55), rgba(190, 226, 255, 0.55) 50%, rgba(196, 240, 222, 0.55));
      }
      .dialog-root-drop {
          -fx-background-color: linear-gradient(to bottom right,
              rgba(218, 206, 255, 0.78), rgba(190, 226, 255, 0.78) 50%, rgba(196, 240, 222, 0.78));
      }
      .notice-root {
          -fx-background-color: linear-gradient(to bottom right,
              rgba(226, 216, 255, 0.93), rgba(204, 232, 255, 0.93) 50%, rgba(208, 244, 228, 0.93));
      }
      .list-view {
          -fx-background-color: rgba(255, 255, 255, 0.25);
          -fx-border-color: rgba(255, 255, 255, 0.55);
      }
      """ + cycleCss(
      "#c9f0ee", "#e8f4c2", "#f3d6f0", "#f3e7d3", "#d3daf8", "#fcdfd8")
      + chatCycleCss(
      "#fcdfd8", "#f8e3d5", "#f3e7d3", "#f3f1cb", "#e8f4c2", "#c6f2ca",
      "#c9f0ee", "#cee6f4", "#d3daf8", "#e4d4f6", "#f3d6f0", "#f8d7e2")),

  /** 회색에 은은한 빛 — 회색 테마 색 위에 바탕만 흐르게 */
  GRAY_GRADIENT("gray-gradient", "회색 그라데이션", GRAY.colors, """
      .widget-root, .dialog-root {
          -fx-background-color: linear-gradient(to bottom right,
              rgba(232, 234, 239, 0.6), rgba(196, 200, 210, 0.55) 55%, rgba(160, 166, 180, 0.55));
      }
      .dialog-root-drop {
          -fx-background-color: linear-gradient(to bottom right,
              rgba(232, 234, 239, 0.8), rgba(196, 200, 210, 0.78) 55%, rgba(160, 166, 180, 0.78));
      }
      .notice-root {
          -fx-background-color: linear-gradient(to bottom right,
              rgba(238, 240, 244, 0.94), rgba(206, 210, 220, 0.93) 55%, rgba(176, 182, 196, 0.93));
      }
      .list-view {
          -fx-background-color: rgba(255, 255, 255, 0.25);
          -fx-border-color: rgba(255, 255, 255, 0.55);
      }
      """);

  public static final Theme DEFAULT = PINK;

  /** 차례로 돌려 칠하는 색 칸 수 — cycle-0 … cycle-(CYCLE-1). 그 색을 정해둔 테마(파스텔)에서만 먹는다 */
  public static final int CYCLE = 6;

  /**
   * 하나씩 떨어지는 것(말풍선, 목표 가지) 의 index 번째에 붙일 클래스. 늘 붙여두고,
   * 칠할 색은 테마 CSS 가 정한다 — 색을 안 정한 테마에선 아무 일도 없다.
   */
  public static String cycleClass(int index) {
    return "cycle-" + Math.floorMod(index, CYCLE);
  }

  /** 수다 말풍선에 돌리는 색 칸 수 — 6색 순환을 무지개 차례로 늘어놓고 사이색을 끼운 12색 */
  public static final int CHAT_CYCLE = 12;

  /** 수다 화면 상대 말풍선의 index 번째에 붙일 클래스. 색은 cycleClass 처럼 테마 CSS 가 정한다 */
  public static String chatCycleClass(int index) {
    return "chat-cycle-" + Math.floorMod(index, CHAT_CYCLE);
  }

  /** settings.json 에 남는 이름 */
  public final String id;
  /** 설정 창에 보이는 이름 */
  public final String label;
  /** style.css 의 핑크 → 이 테마의 색 */
  private final Map<String, String> colors;
  /** 바꿔 끼운 뒤 맨 끝에 덧붙여 덮어쓸 규칙 (없으면 null) */
  private final String extra;
  private final Pattern pattern;

  Theme(String id, String label, Map<String, String> colors, String extra) {
    this.id = id;
    this.label = label;
    this.colors = colors;
    this.extra = extra;
    // 한 번에 훑어서 바꾼다 — 차례로 바꾸면 바꾼 색이 또 다른 색으로 바뀔 수 있어서
    this.pattern = colors.isEmpty() ? null
        : Pattern.compile(String.join("|", colors.keySet().stream().map(Pattern::quote).toList()),
            Pattern.CASE_INSENSITIVE);
  }

  /** 모르는 이름이나 null 이면 기본(핑크) */
  public static Theme of(String id) {
    for (Theme t : values())
      if (t.id.equals(id))
        return t;
    return DEFAULT;
  }

  /** style.css 원본에 이 테마 색을 입힌 사본 */
  public String apply(String css) {
    if (css == null)
      return null;
    String out = swap(css);
    return extra == null ? out : out + "\n" + extra;
  }

  /**
   * style.css 밖에서 쓰는 핑크(코드에 박힌 색, 브라우저 페이지 같은 것)를 이 테마 색으로.
   * "#ff69a0" 이나 "255, 105, 160" 처럼 style.css 와 같은 모양으로 적어야 바뀐다. 덧붙이는 규칙은 없다
   */
  public String swap(String text) {
    if (text == null || pattern == null)
      return text;
    Matcher m = pattern.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find())
      m.appendReplacement(sb, Matcher.quoteReplacement(colors.get(m.group().toLowerCase())));
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * 색마다 cycle-N 규칙 — 상대 말풍선과 목표 칸 · 가지. 칸은 그 색으로 채우고 테두리 · 선만
   * 조금 진하게 해서 연한 바탕 위에서도 보이게. 글씨는 파스텔 위에서 읽히게 진하게,
   * 진하게 채운 끝낸 칸만 흰 글씨
   */
  private static String cycleCss(String... colors) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < colors.length; i++) {
      String c = colors[i];
      sb.append("""
          .chat-theirs.cycle-%1$d { -fx-background-color: %2$s; -fx-text-fill: #3d3a55; }
          .chat-theirs.cycle-%1$d .chat-text { -fx-fill: #3d3a55; }
          .goal-chip.cycle-%1$d { -fx-background-color: %2$s; -fx-border-color: derive(%2$s, -18%%); }
          .goal-chip.cycle-%1$d:hover { -fx-background-color: derive(%2$s, 45%%); }
          .goal-chip.cycle-%1$d:selected { -fx-background-color: derive(%2$s, 40%%); -fx-border-color: derive(%2$s, -40%%); -fx-border-width: 2; -fx-text-fill: #3d3a55; }
          .goal-chip.cycle-%1$d.goal-chip-done { -fx-background-color: derive(%2$s, -30%%); -fx-border-color: derive(%2$s, -45%%); -fx-text-fill: white; }
          .goal-chip.cycle-%1$d.goal-chip-done:hover { -fx-background-color: derive(%2$s, -18%%); }
          .goal-chip.cycle-%1$d.goal-chip-done:selected { -fx-background-color: derive(%2$s, 40%%); -fx-border-color: derive(%2$s, -40%%); -fx-border-width: 2; -fx-text-fill: #3d3a55; }
          .goal-edge.cycle-%1$d { -fx-stroke: derive(%2$s, -15%%); -fx-stroke-width: 2; }
          """.formatted(i, c));
    }
    // 고른 칸은 (끝냈어도) 제 색보다 연하게, 끝낸 칸은 진하게 채운다. 말단 칸 흰 테두리는 늘 이긴다
    sb.append("""
        .goal-chip.goal-chip-leaf, .goal-chip.goal-chip-leaf:selected { -fx-border-color: white; -fx-border-width: 2; }
        """);
    return sb.toString();
  }

  /** 수다 말풍선 chat-cycle-N 규칙 — 무지개 차례 */
  private static String chatCycleCss(String... colors) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < colors.length; i++)
      sb.append("""
          .chat-theirs.chat-cycle-%1$d { -fx-background-color: %2$s; -fx-text-fill: #3d3a55; }
          .chat-theirs.chat-cycle-%1$d .chat-text { -fx-fill: #3d3a55; }
          """.formatted(i, colors[i]));
    return sb.toString();
  }

  private static Map<String, String> remap(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2)
      map.put(pairs[i], pairs[i + 1]);
    return map;
  }
}
