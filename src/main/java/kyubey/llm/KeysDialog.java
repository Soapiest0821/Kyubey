package kyubey.llm;


import kyubey.core.SettingsDialog;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kyubey.llm.GeminiClient.Provider;
import kyubey.ui.Emoji;
import kyubey.ui.MarkdownView;
import kyubey.ui.WindowDrag;

/**
 * "keys" 로 뜨는 창 — .env 의 Google(WIZ_) · BAZE(BAZE_) 키를 넣고 빼고 고치고, 어느 쪽을 쓸지 고른다.
 *
 * 위의 단추로 고른 쪽 키가 보이고, 저장할 때 고른 쪽이 그대로 쓸 쪽이 된다. 두 쪽 키는 다 남는다.
 *
 * 줄마다 그 키로 마지막에 던져본 결과가 불로 붙는다 (KeyHealth):
 * 🟢 성공 · 🟡 서버 오류 · 🔴 한도 초과 · ⚫ 못 쓰는 키 · ⚪ 아직 안 써봄.
 * 불은 키 값에 붙어 있어서, 칸을 고치면 그 자리에서 새 키의 불로 바뀐다.
 * 키 값은 칸을 눌러 들어가기 전까진 안 보이게 둔다 — 화면 공유나 어깨 너머로 새지 않게.
 *
 * 저장하면 쪽마다 1번부터 번호를 새로 매겨 .env 에 적고 (빈칸·겹치는 키는 빠진다),
 * 클라이언트가 곧장 새로 읽는다. 딴 설정 줄은 그대로 둔다.
 * Ctrl+Enter 저장 · Esc 취소 — PromptDialog 와 같은 손버릇.
 * 위젯이 전체화면이면 창을 넓히고 키 줄도 더 많이 보이게 띄운다.
 */
public final class KeysDialog {

  /** 가림막이 덮여 있는 칸 — 밑에 깔린 진짜 글자는 투명하게 */
  private static final PseudoClass MASKED = PseudoClass.getPseudoClass("masked");
  /** 제목 · 키 줄 불 · 범례 불과 링크 그림 크기 */
  private static final double HEADING_SIZE = 18;
  private static final double LIGHT_SIZE = 16;
  private static final double LEGEND_SIZE = 13;
  private static final double FULL_WIDTH = 760;
  /** 스크롤 없이 보일 키 줄 수 — 평소 · 전체화면 */
  private static final int VISIBLE_ROWS = 8;
  private static final int FULL_VISIBLE_ROWS = 14;

  private KeysDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. 저장하고 닫았으면 true */
  public static boolean show(Stage owner, LlmClient llm, boolean full) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("Gemini 키");
    heading.setGraphic(Emoji.view("🔑", HEADING_SIZE));
    heading.getStyleClass().add("notice-title");

    // 불 범례도 그림으로 (Emoji) — 글자로 두면 윈도우에선 흑백 윤곽만 나온다
    FlowPane legend = new FlowPane(6, 2);
    Label order = hint("");
    legend.getChildren().add(order);
    String[][] lights = { { "🟢", "성공" }, { "🟡", "서버 오류" }, { "🔴", "한도 초과" }, { "⚫", "못 씀" }, { "⚪", "안 써봄" } };
    for (String[] l : lights) {
      Label item = hint(l[1]);
      item.setGraphic(Emoji.view(l[0], LEGEND_SIZE));
      legend.getChildren().add(item);
    }
    VBox sub = new VBox(2, legend, hint("Ctrl+Enter 저장 · Esc 취소"));

    Map<Provider, VBox> rowsOf = new EnumMap<>(Provider.class);
    int mostKeys = 0;
    for (Provider provider : Provider.values()) {
      VBox rows = new VBox(6);
      List<String> current = GeminiClient.readKeys(provider);
      for (String key : current)
        addRow(rows, provider, key);
      if (current.isEmpty())
        addRow(rows, provider, "");
      rowsOf.put(provider, rows);
      mostKeys = Math.max(mostKeys, current.size());
    }

    ScrollPane scroll = new ScrollPane();
    scroll.getStyleClass().add("keys-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setPrefViewportHeight(Math.min(full ? FULL_VISIBLE_ROWS : VISIBLE_ROWS, Math.max(3, mostKeys)) * 44);

    // 왼쪽 아래 — 키 발급/상태 보는 곳. 고른 쪽 따라 바뀐다
    Label link = new Label();
    link.setGraphic(Emoji.view("🔗", LEGEND_SIZE));
    link.getStyleClass().add("keys-link");
    link.setCursor(Cursor.HAND);

    Provider[] picked = { GeminiClient.currentProvider() };
    Runnable showPicked = () -> {
      Provider provider = picked[0];
      scroll.setContent(rowsOf.get(provider));
      scroll.setVvalue(0);
      order.setText("위에서부터 " + provider.prefix() + "_1, " + provider.prefix() + "_2 … 로 .env 에 적혀.");
      link.setText(provider.keyPageLabel());
      link.setOnMouseClicked(e -> MarkdownView.openInBrowser(provider.keyPage()));
    };
    showPicked.run();

    // 고른 걸 다시 눌러도 꺼지지 않는 알약 단추 — 설정 창의 고르기 단추와 같은 생김새
    ToggleGroup group = new ToggleGroup();
    HBox picker = new HBox(6);
    for (Provider provider : Provider.values()) {
      ToggleButton button = new ToggleButton(provider.label());
      button.getStyleClass().add("llm-mode");
      button.setToggleGroup(group);
      button.setSelected(provider == picked[0]);
      button.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
        if (button.isSelected())
          e.consume();
      });
      button.setOnAction(e -> {
        picked[0] = provider;
        showPicked.run();
      });
      picker.getChildren().add(button);
    }

    Button add = new Button("+ 키 추가");
    add.getStyleClass().add("dialog-button");
    add.setOnAction(e -> {
      TextField field = addRow(rowsOf.get(picked[0]), picked[0], "");
      scroll.layout();
      scroll.setVvalue(1);
      field.requestFocus();
    });

    Label error = new Label();
    error.getStyleClass().add("dialog-path-label");
    error.setWrapText(true);
    error.setManaged(false);
    error.setVisible(false);

    boolean[] saved = { false };
    Runnable save = () -> {
      try {
        for (Provider provider : Provider.values()) {
          List<String> keys = new ArrayList<>();
          for (var node : rowsOf.get(provider).getChildren())
            keys.add(clean(fieldOf(node).getText()));
          GeminiClient.writeKeys(provider, keys);
        }
        GeminiClient.writeProvider(picked[0]);
        llm.reload();
        saved[0] = true;
        dialog.close();
      } catch (IOException ex) {
        error.setText(".env 를 못 썼어: " + ex.getMessage());
        error.setManaged(true);
        error.setVisible(true);
      }
    };

    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setOnAction(e -> dialog.close());

    Button ok = new Button("저장");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setOnAction(e -> save.run());

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, link, spacer, cancel, ok);
    buttons.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(12, heading, picker, sub, scroll, add, error, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(full ? FULL_WIDTH : 520);
    if (full)
      root.setStyle("-fx-font-size: 16px;");

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        dialog.close();
      } else if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
        e.consume();
        save.run();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      // 비어 있는 칸이 있으면 거기로. 다 차 있으면 아무 칸에도 안 들어간다 — 들어가면 키가 드러나니까
      for (var node : rowsOf.get(picked[0]).getChildren()) {
        if (fieldOf(node).getText().isBlank()) {
          fieldOf(node).requestFocus();
          return;
        }
      }
      root.requestFocus();
    });
    dialog.showAndWait();
    return saved[0];
  }

  /** 키 한 줄: 불 · WIZ_n · 입력칸 · 상태 · 지우기 */
  private static TextField addRow(VBox rows, Provider provider, String key) {
    Label light = new Label();
    light.getStyleClass().add("keys-light");

    Label name = new Label();
    name.getStyleClass().add("dialog-label");
    name.setMinWidth(58);

    TextField field = new TextField(key);
    field.getStyleClass().add("dialog-textfield");
    field.setPromptText(provider.keyHint());

    HBox.setHgrow(field, Priority.ALWAYS);
    // 칸을 누르기 전엔 글자를 투명하게 — 누르면 들어가면서 드러난다
    Runnable cover = () -> field.pseudoClassStateChanged(MASKED, !field.isFocused());
    field.focusedProperty().addListener((obs, was, now) -> cover.run());

    Label state = new Label();
    state.getStyleClass().add("dialog-path-label");
    state.setMinWidth(104);
    state.setPrefWidth(104);

    Button remove = new Button("✕");
    remove.getStyleClass().add("dialog-button");

    HBox row = new HBox(8, light, name, field, state, remove);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setUserData(field);

    Tooltip tip = new Tooltip();
    Tooltip.install(light, tip);
    Runnable refresh = () -> paint(field.getText(), light, state, tip);
    field.textProperty().addListener((obs, was, now) -> refresh.run());
    refresh.run();
    cover.run();

    remove.setOnAction(e -> {
      rows.getChildren().remove(row);
      // 다 지워도 빈칸 하나는 남겨둔다 — 새로 붙여넣을 자리
      if (rows.getChildren().isEmpty())
        addRow(rows, provider, "");
      renumber(rows, provider);
    });

    rows.getChildren().add(row);
    renumber(rows, provider);
    return field;
  }

  /** 지우고 나면 번호가 당겨진다 — 저장했을 때 .env 에 적힐 이름 그대로 보여준다 */
  private static void renumber(VBox rows, Provider provider) {
    int n = 1;
    for (var node : rows.getChildren()) {
      Label name = (Label) ((HBox) node).getChildren().get(1);
      name.setText(provider.prefix() + "_" + n++);
    }
  }

  /** 회색 작은 안내 글 */
  private static Label hint(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("dialog-path-label");
    return label;
  }

  /** 불을 그림으로 켠다 — 채팅 말풍선처럼. 그림이 없으면 글자 그대로 */
  private static void light(Label light, String emoji) {
    ImageView view = Emoji.view(emoji, LIGHT_SIZE);
    light.setGraphic(view);
    light.setText(view == null ? emoji : "");
  }

  private static void paint(String text, Label light, Label state, Tooltip tip) {
    String key = clean(text);
    KeyHealth.Status status = key.isEmpty() ? null : KeyHealth.of(key);
    if (status == null) {
      light(light, "⚪");
      state.setText(key.isEmpty() ? "" : "안 써봄");
      tip.setText("이 키로는 아직 안 물어봤어");
      return;
    }
    String ago = ago(status.at());
    light(light, status.state().light());
    state.setText(status.reason() + " · " + ago);
    tip.setText(status.reason() + " (" + ago + ")");
  }

  private static TextField fieldOf(javafx.scene.Node row) {
    return (TextField) row.getUserData();
  }

  /** 붙여넣다 딸려 온 공백·따옴표를 걷는다 */
  private static String clean(String text) {
    String v = text == null ? "" : text.trim();
    if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")))
      v = v.substring(1, v.length() - 1).trim();
    return v;
  }

  private static String ago(long at) {
    long minutes = (System.currentTimeMillis() - at) / 60_000;
    if (minutes < 1)
      return "방금";
    if (minutes < 60)
      return minutes + "분 전";
    if (minutes < 60 * 24)
      return minutes / 60 + "시간 전";
    return minutes / (60 * 24) + "일 전";
  }

  /** 띄운 쪽 창 한가운데로 (Notice 와 같은 이치) */
  private static void centerOn(Stage popup, Window owner) {
    if (owner == null || owner.getWidth() <= 0 || Double.isNaN(owner.getX())) {
      popup.centerOnScreen();
      return;
    }
    popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
    popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
  }
}
