package kyubey.goal;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.core.SettingsDialog;
import kyubey.ui.Ime;
import kyubey.ui.WindowDrag;

/**
 * 칸 이름 한 줄 받는 작은 창 — 손으로 자식 칸을 붙이거나 칸 이름을 고칠 때 (GoalPane.addChild · rename).
 * Enter 면 적힌 대로, Esc · 취소면 null.
 */
final class GoalTitleDialog {

  private GoalTitleDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. 확인이면 다듬은 이름, 취소거나 비었으면 null */
  static String show(Window owner, String heading, String initial) {
    Stage dialog = new Stage();
    if (owner != null)
      dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label(heading);
    title.getStyleClass().add("notice-title");
    title.setWrapText(true);

    TextField field = new TextField(initial == null ? "" : initial);
    field.getStyleClass().add("dialog-textfield");
    field.setPromptText("짧게 — 한 단어, 길어야 10자 남짓");

    String[] result = { null };
    Button ok = new Button("확인");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setFocusTraversable(false);
    ok.disableProperty().bind(field.textProperty().map(String::isBlank));
    ok.setOnAction(e -> {
      result[0] = field.getText().strip();
      dialog.close();
    });
    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setFocusTraversable(false);
    cancel.setOnAction(e -> dialog.close());

    HBox buttons = new HBox(8, cancel, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, title, field, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(320);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        dialog.close();
      } else if (e.getCode() == KeyCode.ENTER) {
        e.consume();
        if (!ok.isDisabled())
          ok.fire();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      if (owner == null || owner.getWidth() <= 0) {
        dialog.centerOnScreen();
      } else {
        dialog.setX(owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2);
        dialog.setY(owner.getY() + (owner.getHeight() - dialog.getHeight()) / 2);
      }
      // 막 뜬 창의 입력칸이라 한 박자 미뤄 Ime 로 줘야 한글 조합이 보인다 (Ime 주석 참고)
      Platform.runLater(() -> {
        Ime.focus(field);
        field.selectAll();
      });
    });
    dialog.showAndWait();
    return result[0];
  }
}
