package kyubey.chat;

import kyubey.core.SettingsDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.ui.Ime;
import kyubey.ui.WindowDrag;

/**
 * "채팅 프롬프트" / "대화 프롬프트" 로 뜨는 창 — 지금 수다 상대의 이름과 성격문,
 * 뜸할 때 먼저 말을 걸라고 끼워 넣는 안내문, 전날 못 끝낸 할 일을 재촉하는 안내문,
 * 할 일을 끝냈을 때 칭찬하는 안내문을 고쳐 쓴다.
 *
 * 둘 다 지금 쓰고 있는 것을 채워서 연다. 여러 줄이라 Enter 는 줄바꿈이고,
 * 저장은 Ctrl+Enter 나 단추로, 취소는 Esc. 칸마다 붙은 "기본값" 은 그 칸만 기본 문구로
 * 되돌려놓고 저장은 안 한다 — 눌러보고 마음이 바뀌면 그냥 Esc 로 나가면 되게.
 *
 * 접어둔 기억(memo)은 여기 안 보인다. 그건 성격문 뒤에 알아서 따라붙는다 (ChatPane.persona).
 */
public final class PromptDialog {

  private PromptDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. 저장하고 닫았으면 true */
  public static boolean show(Stage owner, ChatPane chat) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("💬 " + chat.name() + " 프롬프트");
    heading.getStyleClass().add("notice-title");

    // 말하는 방식(BasePrompt)은 상대와 상관없이 하나라 평소엔 숨겨둔다 — 두 번 눌러야 열린다
    Button baseButton = new Button("기본 프롬프트");
    baseButton.getStyleClass().add("dialog-button");
    baseButton.setFocusTraversable(false);
    baseButton.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
        showBase(dialog, chat);
    });
    Region headSpacer = new Region();
    HBox.setHgrow(headSpacer, Priority.ALWAYS);
    HBox headRow = new HBox(8, heading, headSpacer, baseButton);
    headRow.setAlignment(Pos.CENTER_LEFT);

    Label sub = new Label("이 상대한테만 먹어. 저장하면 다음 답부터 바로. {이름} 자리는 상대 이름으로 채워져 · Ctrl+Enter 저장 · Esc 취소");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    TextField name = new TextField(chat.name());
    name.getStyleClass().add("dialog-textfield");
    name.setPromptText(chat.profile().defaultName);

    TextArea greeting = area(chat.greeting(), 2);
    TextArea area = area(chat.prompt(), 9);
    TextArea nudge = area(chat.nudgeGuide(), 7);
    TextArea todo = area(chat.todoGuide(), 4);
    TextArea praise = area(chat.praiseGuide(), 4);

    boolean[] saved = { false };
    Runnable save = () -> {
      chat.setName(name.getText());
      chat.setGreeting(greeting.getText());
      chat.setPrompt(area.getText());
      chat.setNudgeGuide(nudge.getText());
      chat.setTodoGuide(todo.getText());
      chat.setPraiseGuide(praise.getText());
      saved[0] = true;
      dialog.close();
    };

    Label nudgeHint = new Label("[이름] 줄이 칸을 가른다 · [답 못 한 채 끊김] 은 [공통] 없이 홀로 가 · {상황} {지난시간} {읽은시간} 자리는 알아서 채워져");
    nudgeHint.getStyleClass().add("dialog-path-label");
    nudgeHint.setWrapText(true);

    Label nameTitle = new Label("이름");
    nameTitle.getStyleClass().add("dialog-path-label");
    Button nameReset = new Button("기본값");
    nameReset.getStyleClass().add("dialog-button");
    nameReset.setOnAction(e -> name.setText(chat.profile().defaultName));
    Region nameSpacer = new Region();
    HBox.setHgrow(nameSpacer, Priority.ALWAYS);
    HBox nameHead = new HBox(8, nameTitle, nameSpacer, nameReset);
    nameHead.setAlignment(Pos.CENTER_LEFT);
    VBox nameBox = new VBox(6, nameHead, name);

    Label greetingHint = new Label("대화가 빈 상대를 처음 열 때 하는 첫마디야 · {이름} 자리는 상대 이름으로 채워져");
    greetingHint.getStyleClass().add("dialog-path-label");
    greetingHint.setWrapText(true);
    VBox greetingBox = section("시작 멘트", greeting, chat.defaultGreeting(), greetingHint);
    VBox personaBox = section("성격문", area, chat.defaultPersona(), null);
    VBox nudgeBox = section("먼저 말 걸 때 안내문", nudge, ChatPane.NUDGE, nudgeHint);

    Label todoHint = new Label("아침 6시가 지나 전날 못 끝낸 할 일이 넘어오면 하루 한 번 · {할일} 자리에 그 목록이 채워져");
    todoHint.getStyleClass().add("dialog-path-label");
    todoHint.setWrapText(true);
    VBox todoBox = section("못 끝낸 할 일 재촉 안내문", todo, ChatPane.TODO_NAG, todoHint);

    Label praiseHint = new Label("할 일을 절반 · 다 끝냈을 때, 다 끝낸 뒤 더 적은 걸 끝냈을 때 · {할일} 자리에 끝낸 할 일이 채워져");
    praiseHint.getStyleClass().add("dialog-path-label");
    praiseHint.setWrapText(true);
    VBox praiseBox = section("할 일 끝냈을 때 칭찬 안내문", praise, ChatPane.TODO_PRAISE, praiseHint);

    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setOnAction(e -> dialog.close());

    Button ok = new Button("저장");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setOnAction(e -> save.run());

    HBox buttons = new HBox(8, cancel, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    // 칸이 넷이라 작은 화면에선 창이 아래로 넘친다 — 칸들만 굴리고 제목·단추는 붙박아둔다
    VBox sections = new VBox(12, nameBox, greetingBox, personaBox, nudgeBox, todoBox, praiseBox);
    sections.setPadding(new Insets(0, 6, 0, 0));
    ScrollPane scroll = new ScrollPane(sections);
    scroll.getStyleClass().add("keys-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setPrefViewportHeight(440);

    VBox root = new VBox(12, headRow, sub, scroll, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(520);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    // TextArea 가 Enter 를 먹기 전에 받아야 해서 필터로 단다
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
      // 방금 뜬 창이라 그냥 포커스를 주면 한글 조합이 안 보인다 (Ime 주석 참고)
      Ime.focus(area);
      area.positionCaret(area.getLength());
    });
    dialog.showAndWait();
    return saved[0];
  }

  /**
   * 숨겨둔 기본 프롬프트 — 말하는 방식(톡처럼 짧게, 줄바꿈 하나가 말풍선 하나 …). 상대와 상관없이 하나다.
   * 프롬프트 창의 "기본 프롬프트" 를 두 번 누르면 뜬다. 저장은 Ctrl+Enter 나 단추, 취소는 Esc.
   */
  private static void showBase(Stage owner, ChatPane chat) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("🔧 기본 프롬프트");
    heading.getStyleClass().add("notice-title");
    Label sub = new Label("상대가 누구든 성격문 뒤에 붙는 말하는 방식이야. 말풍선 나누기가 여기에 걸려 있으니 조심해서 · Ctrl+Enter 저장 · Esc 취소");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    TextArea area = area(chat.basePrompt(), 10);
    VBox box = section("말하는 방식", area, BasePrompt.DEFAULT, null);

    Runnable save = () -> {
      chat.setBasePrompt(area.getText());
      dialog.close();
    };

    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setOnAction(e -> dialog.close());
    Button ok = new Button("저장");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setOnAction(e -> save.run());
    HBox buttons = new HBox(8, cancel, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, heading, sub, box, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(500);

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
      Ime.focus(area);
      area.positionCaret(area.getLength());
    });
    dialog.showAndWait();
  }

  private static TextArea area(String text, int rows) {
    TextArea area = new TextArea(text);
    area.getStyleClass().addAll("dialog-textfield", "prompt-area");
    area.setWrapText(true);
    area.setPrefColumnCount(40);
    area.setPrefRowCount(rows);
    return area;
  }

  /** 칸 이름 + 그 칸만 되돌리는 "기본값" 단추 한 줄, 그 아래 (있으면) 안내, 그 아래 입력칸 */
  private static VBox section(String name, TextArea area, String fallback, Label hint) {
    Label title = new Label(name);
    title.getStyleClass().add("dialog-path-label");

    Button reset = new Button("기본값");
    reset.getStyleClass().add("dialog-button");
    reset.setOnAction(e -> {
      area.setText(fallback);
      area.requestFocus();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
    HBox head = new HBox(8, title, spacer, reset);
    head.setAlignment(Pos.CENTER_LEFT);

    VBox box = new VBox(6, head);
    if (hint != null)
      box.getChildren().add(hint);
    box.getChildren().add(area);
    return box;
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
