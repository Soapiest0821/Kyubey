package kyubey.goal;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.core.SettingsDialog;
import kyubey.ui.Ime;
import kyubey.ui.WindowDrag;

import java.util.concurrent.Callable;

/**
 * 맵 칸을 누르면 뜨는 칸 메모 창. 그 칸을 쪼갤 때 마도카가 한 말이 적혀 있고 (GoalPane.apply),
 * 글 칸을 한 번 눌러야 고쳐 쓸 수 있다 — 칸을 훑어보다 실수로 고치지 않게.
 *
 * 저장 단추는 없다. 창이 닫히면 (Esc, Ctrl+Enter) 그때 적힌 대로 저장된다.
 * 메모가 비어 있으면 아래 줄 오른쪽에 자동 생성 단추가 떠서 마도카한테 메모를 써 달라고 할 수 있다.
 */
final class GoalNoteDialog {

  private GoalNoteDialog() {
  }

  /**
   * 창을 띄우고 닫힐 때까지 기다린다. 닫힐 때 적혀 있던 글을 돌려준다.
   * generate 는 자동 생성 단추가 부를 일 — 뒤에서 돌리니 오래 걸려도 된다. null 이면 단추를 안 띄운다 (파트너 없이 쓰는 목표).
   */
  static String show(Window owner, String title, String note, Callable<String> generate) {
    Stage dialog = new Stage();
    if (owner != null)
      dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("📝 " + title);
    heading.getStyleClass().add("notice-title");
    heading.setWrapText(true);

    TextArea area = new TextArea(note == null ? "" : note);
    area.getStyleClass().addAll("dialog-textfield", "prompt-area");
    area.setWrapText(true);
    area.setPrefColumnCount(30);
    area.setPrefRowCount(6);
    area.setPromptText(generate == null ? "아직 메모가 없어. 눌러서 적어줘" : "아직 메모가 없어. 쪼개면 마케몬이 한 말이 여기 적혀");
    area.setEditable(false);
    // 눌러야 고칠 수 있다. 포커스는 Ime 로 줘야 한글 조합이 보인다
    area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      if (area.isEditable())
        return;
      area.setEditable(true);
      Ime.focus(area);
    });

    Label sub = new Label("눌러서 고치기 · 비우면 지워져 · Esc 닫기 (닫으면 저장돼)");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);
    sub.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(sub, Priority.ALWAYS);

    Button auto = new Button("자동 생성");
    auto.getStyleClass().add("dialog-button");
    auto.setFocusTraversable(false);
    auto.visibleProperty().bind(area.textProperty().isEmpty().and(new javafx.beans.property.SimpleBooleanProperty(generate != null)));
    auto.managedProperty().bind(auto.visibleProperty());
    auto.setOnAction(e -> {
      auto.setDisable(true);
      auto.setText("생각 중…");
      Task<String> task = new Task<>() {
        @Override
        protected String call() throws Exception {
          return generate.call();
        }
      };
      task.setOnSucceeded(done -> {
        auto.setDisable(false);
        auto.setText("자동 생성");
        String text = task.getValue();
        if (text != null && !text.isBlank())
          area.setText(text.strip());
      });
      task.setOnFailed(failed -> {
        auto.setDisable(false);
        auto.setText("자동 생성");
        Throwable ex = task.getException();
        sub.setText("못 만들었어 ㅠㅠ " + (ex == null ? "" : ex.getMessage()));
      });
      Thread worker = new Thread(task, "goal-note");
      worker.setDaemon(true);
      worker.start();
    });

    HBox bottom = new HBox(8, sub, auto);
    bottom.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(12, heading, area, bottom);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(420);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    // TextArea 가 Enter 를 먹기 전에 받아야 해서 필터로 단다
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE || (e.getCode() == KeyCode.ENTER && e.isShortcutDown())) {
        e.consume();
        dialog.close();
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
      // 글 칸이 처음부터 포커스를 쥐고 있으면 눌러도 포커스가 안 바뀌어 한글 조합이 깨진다 (Ime 주석 참고)
      root.requestFocus();
    });
    dialog.showAndWait();
    return area.getText();
  }
}
