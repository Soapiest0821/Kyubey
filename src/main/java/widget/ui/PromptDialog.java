package widget.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * "채팅 프롬프트" / "대화 프롬프트" 로 뜨는 창 — 마도카한테 입혀주는 성격문을 고쳐 쓴다.
 *
 * 지금 쓰고 있는 성격문을 채워서 연다. 여러 줄이라 Enter 는 줄바꿈이고,
 * 저장은 Ctrl+Enter 나 단추로, 취소는 Esc. "기본값" 은 칸만 기본 성격문으로 되돌려놓고
 * 저장은 안 한다 — 눌러보고 마음이 바뀌면 그냥 Esc 로 나가면 되게.
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

    Label heading = new Label("💬 마도카 프롬프트");
    heading.getStyleClass().add("notice-title");

    Label sub = new Label("저장하면 다음 답부터 바로 먹어. Ctrl+Enter 저장 · Esc 취소");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    TextArea area = new TextArea(chat.prompt());
    area.getStyleClass().addAll("dialog-textfield", "prompt-area");
    area.setWrapText(true);
    area.setPrefColumnCount(40);
    area.setPrefRowCount(16);

    boolean[] saved = { false };
    Runnable save = () -> {
      chat.setPrompt(area.getText());
      saved[0] = true;
      dialog.close();
    };

    Button reset = new Button("기본값");
    reset.getStyleClass().add("dialog-button");
    reset.setOnAction(e -> {
      area.setText(ChatPane.PERSONA);
      area.requestFocus();
    });

    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setOnAction(e -> dialog.close());

    Button ok = new Button("저장");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setOnAction(e -> save.run());

    Region spacer = new Region();
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
    HBox buttons = new HBox(8, reset, spacer, cancel, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, heading, sub, area, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(520);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(PromptDialog.class.getResource("/style/style.css").toExternalForm());
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
