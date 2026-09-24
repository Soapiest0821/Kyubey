package widget.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * 위젯이 뭔가 안 됐다고 말할 때 뜨는 작은 창.
 *
 * 예전엔 JavaFX 기본 Alert 을 썼는데, 혼자만 하얀 시스템 창이라
 * 분홍 위젯 위에 뜨면 남의 프로그램 같아 보여서 등록 창이랑 같은 옷을 입혔다.
 * 닫는 건 Enter / Esc / 버튼 아무거나.
 */
public final class Notice {

  private Notice() {
  }

  /** 경고 한 줄 띄우고, 닫을 때까지 기다린다 */
  public static void warn(Window owner, String message) {
    Stage popup = new Stage();
    if (owner != null)
      popup.initOwner(owner);
    popup.initModality(Modality.APPLICATION_MODAL);
    popup.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label("🚨 잠깐!");
    title.getStyleClass().add("notice-title");

    Label body = new Label(message);
    body.getStyleClass().add("notice-message");
    body.setWrapText(true);
    body.setMaxWidth(300);

    Button okBtn = new Button("알겠어!");
    okBtn.getStyleClass().add("dialog-button-primary");
    okBtn.setDefaultButton(true); // Enter 로도 닫히게
    okBtn.setOnAction(e -> popup.close());

    VBox buttonBox = new VBox(okBtn);
    buttonBox.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(14, title, body, buttonBox);
    root.getStyleClass().addAll("dialog-root", "notice-root");
    root.setPadding(new Insets(22));
    root.setAlignment(Pos.CENTER_LEFT);
    root.setMaxWidth(340);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(Notice.class.getResource("/style/style.css").toExternalForm());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE)
        popup.close();
    });

    popup.setScene(scene);
    // 타이틀바가 없으니 이 창도 아무 데나 잡고 끌 수 있게
    WindowDrag.makeDraggable(popup, root);
    popup.setOnShown(e -> {
      centerOn(popup, owner);
      okBtn.requestFocus();
    });
    popup.showAndWait();
  }

  /** 띄운 쪽 창 한가운데로. 주인 창이 없거나 아직 크기를 모르면 화면 가운데로 둔다 */
  private static void centerOn(Stage popup, Window owner) {
    if (owner == null || owner.getWidth() <= 0 || Double.isNaN(owner.getX())) {
      popup.centerOnScreen();
      return;
    }
    popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
    popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
  }
}
