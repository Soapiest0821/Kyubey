package kyubey.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 이모지 고르기 판 — 수다 입력칸 왼쪽 단추와 말풍선 반응이 같이 쓴다.
 * 그림은 Emoji 가 Noto PNG 로 그린다. 분류는 자주 쓸 만한 것만 추려뒀다.
 */
public final class EmojiPicker {

  /** 분류 이름(탭에 보일 이모지) → 그 안의 이모지들 */
  private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();
  static {
    CATEGORIES.put("😀", List.of(("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 "
        + "🤗 🤭 🫢 🤫 🤔 🫡 🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 "
        + "😎 🤓 🧐 😕 🫤 😟 🙁 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 "
        + "😈 👿 💀 👻 👽 🤖 💩 😺 😸 😹 😻 😼 😽 🙀 😿 😾").split(" ")));
    CATEGORIES.put("👋", List.of(("👋 🤚 🖐 ✋ 🖖 🫱 🫲 👌 🤌 🤏 ✌ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝ 🫵 👍 👎 ✊ 👊 "
        + "🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍ 💅 💪 👀 👁 👄 🫦 🙇 🙋 🙆 🙅 🤷 🤦 💁 🧏").split(" ")));
    CATEGORIES.put("🩷", List.of(("❤ 🩷 🧡 💛 💚 💙 🩵 💜 🖤 🩶 🤍 🤎 💔 ❣ 💕 💞 💓 💗 💖 💘 💝 💟 💌 💋 "
        + "✨ ⭐ 🌟 💫 🔥 💯 💢 💥 💦 💨 💤 💬 💭 🎉 🎊 🎈 🎁 🎀 ✅ ❌ ❓ ❗ ⚠ 🆗 🆕").split(" ")));
    CATEGORIES.put("🐱", List.of(("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦉 "
        + "🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐬 🐳 🐟 🦈 🌸 💮 🌹 🌷 🌻 🌼 🌱 🌿 🍀 🍁 🍂 🌈 ☀ 🌙 ☁ ☔ ❄ ⛄").split(" ")));
    CATEGORIES.put("🍰", List.of(("🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍑 🍒 🥝 🍅 🥑 🍞 🥐 🧀 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🍜 "
        + "🍝 🍣 🍱 🍙 🍚 🍛 🍤 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍮 🍯 🍦 ☕ 🍵 🧋 🥤 🍺 🍻 🍷 🥂").split(" ")));
    CATEGORIES.put("⚽", List.of(("⚽ 🏀 ⚾ 🎾 🏐 🎮 🕹 🎲 🎯 🎨 🎵 🎶 🎧 🎤 🎹 🎸 📚 📖 ✏ 📝 💻 📱 ⏰ 📅 "
        + "📌 📎 ✂ 🔑 💡 💰 💸 👑 💎 🔔 📢 🏠 🏫 🚗 🚌 ✈ 🚀 ⌛ 💊 🛁 🛏 🎓 🏆 🥇").split(" ")));
  }

  /** 최근에 고른 것 — 켜져 있는 동안만 기억한다 */
  private static final List<String> recent = new ArrayList<>();
  private static final int RECENT_MAX = 24;

  /** 지금까지 쓴 이모지, 많이 쓴 차례로 — 수다 화면이 대화 기록에서 세어 넘겨준다 (ChatPane) */
  private static Supplier<List<String>> used = List::of;

  /** 말풍선 반응에 바로 보이는 것들 */
  public static final List<String> QUICK = List.of("❤", "👍", "😂", "😮", "😢", "🙏");

  private static final double CELL = 22;

  private EmojiPicker() {
  }

  /** 판의 "많이 쓴" 분류를 채울 곳을 건다 */
  public static void setUsed(Supplier<List<String>> source) {
    used = source;
  }

  /**
   * anchor 위에 판을 띄운다. 고를 때마다 onPick 에 넘긴다 (혼자 쓰면 글자로 나오는 건 FE0F 를 붙여서).
   * closeOnPick 이면 하나 고르고 닫히고, 아니면 바깥을 누를 때까지 계속 고른다.
   */
  public static Popup show(Node anchor, boolean closeOnPick, Consumer<String> onPick) {
    Popup popup = new Popup();
    popup.setAutoHide(true);

    FlowPane grid = new FlowPane(2, 2);
    grid.getStyleClass().add("emoji-grid");
    ScrollPane scroll = new ScrollPane(grid);
    scroll.getStyleClass().add("emoji-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setPrefViewportHeight(180);

    Consumer<String> pick = emoji -> {
      String shown = Emoji.presented(emoji);
      remember(shown);
      if (closeOnPick)
        popup.hide();
      onPick.accept(shown);
    };

    ToggleGroup tabs = new ToggleGroup();
    HBox tabRow = new HBox(2);
    tabRow.setAlignment(Pos.CENTER_LEFT);
    Map<String, List<String>> shelves = new LinkedHashMap<>();
    List<String> often = used.get();
    if (!often.isEmpty())
      shelves.put("⭐", often);
    if (!recent.isEmpty())
      shelves.put("🕘", List.copyOf(recent));
    shelves.putAll(CATEGORIES);
    for (Map.Entry<String, List<String>> shelf : shelves.entrySet()) {
      ToggleButton tab = new ToggleButton();
      tab.setGraphic(graphic(shelf.getKey(), 16));
      tab.getStyleClass().add("emoji-tab");
      tab.setFocusTraversable(false);
      tab.setToggleGroup(tabs);
      tab.setUserData(shelf.getValue());
      tabRow.getChildren().add(tab);
    }
    tabs.selectedToggleProperty().addListener((obs, was, is) -> {
      // 고른 탭을 다시 눌러 꺼지면 도로 켠다 — 늘 한 분류는 펼쳐져 있게
      if (is == null) {
        was.setSelected(true);
        return;
      }
      @SuppressWarnings("unchecked")
      List<String> emojis = (List<String>) is.getUserData();
      grid.getChildren().clear();
      for (String emoji : emojis)
        grid.getChildren().add(cell(emoji, CELL, pick));
      scroll.setVvalue(0);
    });
    tabs.getToggles().get(0).setSelected(true);

    VBox root = new VBox(6, tabRow, scroll);
    root.getStyleClass().add("emoji-picker");
    root.setPrefWidth(8 * (CELL + 12) + 24);
    popup.getContent().add(root);
    showAbove(popup, anchor);
    return popup;
  }

  /**
   * 반응 고르기 — 자주 쓰는 몇 개만 한 줄로, 끝의 ⋯ 를 누르면 판 전체가 뜬다.
   * 판은 anchor 에 붙고 (늘 보이는 것이어야 같이 안 닫힌다), 자리는 beside 바로 오른쪽 — 높이 가운데를 맞춘다
   */
  public static Popup quick(Node anchor, Node beside, String current, Consumer<String> onPick) {
    Popup popup = new Popup();
    popup.setAutoHide(true);
    HBox row = row(QUICK, current, picked -> {
      popup.hide();
      onPick.accept(picked);
    }, () -> {
      popup.hide();
      show(anchor, true, onPick);
    });
    row.getStyleClass().add("emoji-picker");
    popup.getContent().add(row);
    showBeside(popup, anchor, beside);
    return popup;
  }

  /**
   * 반응 고르기 줄 — emojis 를 컬러 그림으로 늘어놓고 끝에 ⋯ (onMore). 지금 단 반응(current)은 칠해 둔다.
   * 반응 고르기 띄움판과 말풍선 오른쪽 클릭 메뉴가 같이 쓴다
   */
  public static HBox row(List<String> emojis, String current, Consumer<String> onPick, Runnable onMore) {
    return row(emojis, current, 20, onPick, onMore);
  }

  /** size 는 그림 한 변 — 오른쪽 클릭 메뉴에선 다른 메뉴 줄 크기에 맞춰 작게 */
  public static HBox row(List<String> emojis, String current, double size, Consumer<String> onPick, Runnable onMore) {
    HBox row = new HBox(2);
    row.setAlignment(Pos.CENTER_LEFT);
    for (String emoji : emojis) {
      Button cell = cell(emoji, size, picked -> onPick.accept(Emoji.presented(picked)));
      if (current != null && Emoji.presented(emoji).equals(current))
        cell.getStyleClass().add("emoji-cell-picked");
      row.getChildren().add(cell);
    }
    Button more = new Button("⋯");
    more.getStyleClass().addAll("emoji-cell", "emoji-more");
    more.setFocusTraversable(false);
    more.setOnAction(e -> onMore.run());
    row.getChildren().add(more);
    return row;
  }

  private static Button cell(String emoji, double size, Consumer<String> pick) {
    Button cell = new Button();
    cell.setGraphic(graphic(emoji, size));
    cell.getStyleClass().add("emoji-cell");
    cell.setFocusTraversable(false);
    cell.setOnAction(e -> pick.accept(emoji));
    return cell;
  }

  /** 그림이 있으면 그림, 없으면 글자 그대로 */
  private static Node graphic(String emoji, double size) {
    ImageView view = Emoji.view(Emoji.presented(emoji), size);
    if (view != null)
      return view;
    Label text = new Label(emoji);
    text.setStyle("-fx-font-size: " + size * 0.8 + "px;");
    return text;
  }

  private static void remember(String emoji) {
    recent.remove(emoji);
    recent.add(0, emoji);
    if (recent.size() > RECENT_MAX)
      recent.remove(recent.size() - 1);
  }

  /** anchor 바로 위에 띄운다. 판은 anchor 가 있는 창의 style.css 를 그대로 입는다 */
  private static void showAbove(Popup popup, Node anchor) {
    popup.getScene().getStylesheets().setAll(anchor.getScene().getStylesheets());
    popup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_LEFT);
    Bounds at = anchor.localToScreen(anchor.getBoundsInLocal());
    popup.show(anchor, at.getMinX(), at.getMinY() - 4);
  }

  /** beside 바로 오른쪽에 높이 가운데를 맞춰 띄운다. 판의 주인은 anchor */
  private static void showBeside(Popup popup, Node anchor, Node beside) {
    popup.getScene().getStylesheets().setAll(anchor.getScene().getStylesheets());
    popup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
    Bounds at = beside.localToScreen(beside.getBoundsInLocal());
    popup.show(anchor, at.getMaxX() + 4, at.getMinY());
    // 띄운 뒤에야 판 높이를 알 수 있다
    popup.setAnchorY(at.getCenterY() - popup.getHeight() / 2);
  }
}
