package kyubey.goal;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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
import kyubey.core.SettingsDialog;
import kyubey.ui.Ime;
import kyubey.ui.WindowDrag;

import java.util.function.Consumer;

/**
 * 관계도 설정 창 — 빈 데 오른쪽 클릭 "설정…" (GoalPane.graphBoardMenu).
 *  - 무향 · 유향: 누르는 대로 바로 onDirected 로 먹인다
 *  - 빨간 테두리 "관계도 지우기": 누르면 밑에 확인 칸이 펴진다. 루트(중심 칸) 이름을 똑같이 쳐야 지우기 단추가 켜진다.
 *    관계도를 통째로 지우는 길은 여기 하나 — 루트 칸 오른쪽 클릭 메뉴엔 지우기가 없다
 * Esc · 닫기면 그냥 닫는다.
 */
final class GraphSettingsDialog {

  private GraphSettingsDialog() {
  }

  /**
   * 창을 띄우고 닫힐 때까지 기다린다. 지우기로 확인했으면 true.
   * askDelete 면 처음부터 지우기 확인 칸을 펴 둔다 (루트 칸에서 Delete 를 눌렀을 때)
   */
  static boolean show(Window owner, String rootTitle, boolean directed, Consumer<Boolean> onDirected,
      boolean askDelete) {
    Stage dialog = new Stage();
    if (owner != null)
      dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label("⚙ 「" + rootTitle + "」 관계도 설정");
    title.getStyleClass().add("notice-title");
    title.setWrapText(true);

    // 연결 방향 — 무향 · 유향 둘 중 하나 (다시 눌러도 안 풀리게)
    Label dirLabel = new Label("연결 방향");
    dirLabel.getStyleClass().add("dialog-label");
    ToggleGroup group = new ToggleGroup();
    ToggleButton undirected = new ToggleButton("무향 (방향 없음)");
    ToggleButton directedButton = new ToggleButton("유향 (화살표)");
    for (ToggleButton b : new ToggleButton[] { undirected, directedButton }) {
      b.getStyleClass().add("graph-mode-button");
      b.setFocusTraversable(false);
      b.setToggleGroup(group);
    }
    (directed ? directedButton : undirected).setSelected(true);
    group.selectedToggleProperty().addListener((obs, was, is) -> {
      if (is == null) {
        group.selectToggle(was);
        return;
      }
      onDirected.accept(is == directedButton);
    });
    HBox dirBar = new HBox(undirected, directedButton);
    dirBar.getStyleClass().add("graph-mode");
    dirBar.setMaxWidth(Region.USE_PREF_SIZE);

    // 지우기 — 루트 이름을 똑같이 쳐야 켜진다
    boolean[] deleted = { false };
    Button delete = new Button("🗑 관계도 지우기");
    delete.getStyleClass().add("dialog-button-danger");
    delete.setFocusTraversable(false);

    Label confirmHint = new Label("칸이랑 연결이 다 사라져. 지우려면 루트 이름 「" + rootTitle + "」 을 똑같이 쳐줘");
    confirmHint.getStyleClass().add("dialog-label");
    confirmHint.setWrapText(true);
    TextField confirmField = new TextField();
    confirmField.getStyleClass().add("dialog-textfield");
    confirmField.setPromptText(rootTitle);
    Button confirm = new Button("지우기");
    confirm.getStyleClass().add("dialog-button-danger");
    confirm.setFocusTraversable(false);
    confirm.disableProperty().bind(confirmField.textProperty().map(t -> !t.strip().equals(rootTitle.strip())));
    confirm.setOnAction(e -> {
      deleted[0] = true;
      dialog.close();
    });
    HBox confirmRow = new HBox(8, confirmField, confirm);
    confirmRow.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(confirmField, Priority.ALWAYS);
    VBox confirmBox = new VBox(8, confirmHint, confirmRow);
    confirmBox.setVisible(false);
    confirmBox.setManaged(false);

    Runnable openConfirm = () -> {
      confirmBox.setVisible(true);
      confirmBox.setManaged(true);
      delete.setDisable(true);
      dialog.sizeToScene();
      Platform.runLater(() -> Ime.focus(confirmField));
    };
    delete.setOnAction(e -> openConfirm.run());

    Button close = new Button("닫기");
    close.getStyleClass().add("dialog-button");
    close.setFocusTraversable(false);
    close.setOnAction(e -> dialog.close());
    Region gap = new Region();
    HBox.setHgrow(gap, Priority.ALWAYS);
    HBox buttons = new HBox(8, delete, gap, close);
    buttons.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(12, title, dirLabel, dirBar, new Separator(), buttons, confirmBox);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(340);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        dialog.close();
      } else if (e.getCode() == KeyCode.ENTER && confirmBox.isVisible()) {
        e.consume();
        if (!confirm.isDisabled())
          confirm.fire();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      if (askDelete)
        openConfirm.run();
      if (owner == null || owner.getWidth() <= 0) {
        dialog.centerOnScreen();
      } else {
        dialog.setX(owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2);
        dialog.setY(owner.getY() + (owner.getHeight() - dialog.getHeight()) / 2);
      }
    });
    dialog.showAndWait();
    return deleted[0];
  }
}
