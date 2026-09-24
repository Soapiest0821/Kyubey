package widget.ui;

import widget.llm.GeminiClient;
import widget.llm.KeyHealth;
import widget.llm.LlmClient;

import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import java.util.List;

/**
 * "keys" 로 뜨는 창 — .env 의 WIZ_ 키를 넣고 빼고 고친다.
 *
 * 줄마다 그 키로 마지막에 던져본 결과가 불로 붙는다 (KeyHealth):
 * 🟢 성공 · 🟡 서버 오류 · 🔴 한도 초과 · ⚫ 못 쓰는 키 · ⚪ 아직 안 써봄.
 * 불은 키 값에 붙어 있어서, 칸을 고치면 그 자리에서 새 키의 불로 바뀐다.
 * 키 값은 칸을 눌러 들어가기 전까진 안 보이게 둔다 — 화면 공유나 어깨 너머로 새지 않게.
 *
 * 저장하면 WIZ_1 부터 번호를 새로 매겨 .env 에 적고 (빈칸·겹치는 키는 빠진다),
 * 클라이언트가 곧장 새로 읽는다. 딴 설정 줄은 그대로 둔다.
 * Ctrl+Enter 저장 · Esc 취소 — PromptDialog 와 같은 손버릇.
 */
public final class KeysDialog {

  private static final String KEY_PAGE = "https://aistudio.google.com/api-keys?hl=ko";
  /** 가림막이 덮여 있는 칸 — 밑에 깔린 진짜 글자는 투명하게 */
  private static final PseudoClass MASKED = PseudoClass.getPseudoClass("masked");

  private KeysDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. 저장하고 닫았으면 true */
  public static boolean show(Stage owner, LlmClient llm) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("🔑 Gemini 키");
    heading.getStyleClass().add("notice-title");

    Label sub = new Label("위에서부터 WIZ_1, WIZ_2 … 로 .env 에 적혀. 🟢 성공 · 🟡 서버 오류 · 🔴 한도 초과 · ⚫ 못 씀 · ⚪ 안 써봄"
        + "\nCtrl+Enter 저장 · Esc 취소");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    VBox rows = new VBox(6);
    List<String> current = GeminiClient.readKeys();
    for (String key : current)
      addRow(rows, key);
    if (current.isEmpty())
      addRow(rows, "");

    ScrollPane scroll = new ScrollPane(rows);
    scroll.getStyleClass().add("keys-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setPrefViewportHeight(Math.min(8, Math.max(3, current.size())) * 44);

    Button add = new Button("+ 키 추가");
    add.getStyleClass().add("dialog-button");
    add.setOnAction(e -> {
      TextField field = addRow(rows, "");
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
      List<String> keys = new ArrayList<>();
      for (var node : rows.getChildren())
        keys.add(clean(fieldOf(node).getText()));
      try {
        GeminiClient.writeKeys(keys);
        llm.reload();
        saved[0] = true;
        dialog.close();
      } catch (IOException ex) {
        error.setText(".env 를 못 썼어: " + ex.getMessage());
        error.setManaged(true);
        error.setVisible(true);
      }
    };

    // 왼쪽 아래 — 키 발급/상태 보는 곳
    Label link = new Label("🔗 aistudio.google.com/api-keys");
    link.getStyleClass().add("keys-link");
    link.setCursor(Cursor.HAND);
    link.setOnMouseClicked(e -> MarkdownView.openInBrowser(KEY_PAGE));

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

    VBox root = new VBox(12, heading, sub, scroll, add, error, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(520);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(KeysDialog.class.getResource("/style/style.css").toExternalForm());
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
      for (var node : rows.getChildren()) {
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
  private static TextField addRow(VBox rows, String key) {
    Label light = new Label();
    light.getStyleClass().add("keys-light");

    Label name = new Label();
    name.getStyleClass().add("dialog-label");
    name.setMinWidth(58);

    TextField field = new TextField(key);
    field.getStyleClass().add("dialog-textfield");
    field.setPromptText("AIza… 붙여넣기");

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
        addRow(rows, "");
      renumber(rows);
    });

    rows.getChildren().add(row);
    renumber(rows);
    return field;
  }

  /** 지우고 나면 번호가 당겨진다 — 저장했을 때 .env 에 적힐 이름 그대로 보여준다 */
  private static void renumber(VBox rows) {
    int n = 1;
    for (var node : rows.getChildren()) {
      Label name = (Label) ((HBox) node).getChildren().get(1);
      name.setText("WIZ_" + n++);
    }
  }

  private static void paint(String text, Label light, Label state, Tooltip tip) {
    String key = clean(text);
    KeyHealth.Status status = key.isEmpty() ? null : KeyHealth.of(key);
    if (status == null) {
      light.setText("⚪");
      state.setText(key.isEmpty() ? "" : "안 써봄");
      tip.setText("이 키로는 아직 안 물어봤어");
      return;
    }
    String ago = ago(status.at());
    light.setText(status.state().light());
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
