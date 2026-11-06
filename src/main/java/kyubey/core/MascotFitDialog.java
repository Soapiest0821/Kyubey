package kyubey.core;

import kyubey.ui.WindowDrag;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * 기본 마도카보다 옆으로 넓은 그림을 골랐을 때, 어떻게 세울지 묻는 창.
 *
 * - 가로에 맞추기 — 기본 마도카 너비 안에 들어가게 줄인다. 대신 키가 작아진다
 * - 왼쪽으로 넓게 — 기본 마도카 키 그대로 세우고, 넘치는 만큼 왼쪽으로 삐져나간다
 *
 * 두 경우를 위젯 오른쪽 아래 모서리처럼 작게 그려서 나란히 보여주고, 누르는 쪽으로 정한다.
 * 점선 네모가 기본 마도카 자리.
 */
public final class MascotFitDialog {

  /** 미리보기는 실제 크기의 이만큼으로 줄여 그린다 */
  private static final double SCALE = 0.6;

  /** 미리보기 칸 — 위젯 오른쪽 아래 귀퉁이만 잘라 보여주는 크기 */
  private static final double STAGE_WIDTH = 240;
  private static final double STAGE_HEIGHT = 130;

  /** 실제 위젯에서 그림을 모서리에서 띄우는 거리 (Main.MASCOT_MARGIN) 를 줄인 것 */
  private static final double STAGE_MARGIN = 12 * SCALE;

  private MascotFitDialog() {
  }

  /**
   * 물어보고 고른 걸 돌려준다. 가로에 맞추면 true, 왼쪽으로 넓게면 false,
   * Esc 로 그냥 닫으면 null. current 는 지금 걸려 있는 쪽 (그 카드에 표시가 붙는다).
   */
  public static Boolean ask(Window owner, Image image, String name, boolean current) {
    Stage popup = new Stage();
    if (owner != null)
      popup.initOwner(owner);
    popup.initModality(Modality.APPLICATION_MODAL);
    popup.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label("🖼 " + name + " 그림 크기");
    title.getStyleClass().add("notice-title");

    Label body = new Label("기본 그림보다 옆으로 넓은 그림이야. 어떻게 세울까?\n점선이 기본 그림 자리야.");
    body.getStyleClass().add("notice-message");
    body.setWrapText(true);

    Boolean[] picked = { null };
    Button width = card(image, true, "가로에 맞추기",
        "기본 너비 안에 쏙. 대신 키가 작아져", current);
    Button height = card(image, false, "왼쪽으로 넓게",
        "기본 키 그대로. 넘치는 만큼 왼쪽을 가려", !current);
    width.setOnAction(e -> {
      picked[0] = true;
      popup.close();
    });
    height.setOnAction(e -> {
      picked[0] = false;
      popup.close();
    });
    HBox cards = new HBox(12, width, height);

    VBox root = new VBox(14, title, body, cards);
    root.getStyleClass().addAll("dialog-root", "notice-root");
    root.setPadding(new Insets(22));
    root.setAlignment(Pos.CENTER_LEFT);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE)
        popup.close();
    });

    popup.setScene(scene);
    WindowDrag.makeDraggable(popup, root);
    popup.setOnShown(e -> {
      centerOn(popup, owner);
      (current ? width : height).requestFocus();
    });
    popup.showAndWait();
    return picked[0];
  }

  /** 고를 거리 하나 — 미리보기 + 이름 + 한 줄 설명. 지금 걸린 쪽은 테두리가 진하다 */
  private static Button card(Image image, boolean fitWidth, String label, String hint, boolean selected) {
    ImageView view = new ImageView(image);
    view.setPreserveRatio(true);
    view.setFitHeight(SettingsDialog.MASCOT_HEIGHT * SCALE);
    view.setFitWidth(fitWidth ? SettingsDialog.MASCOT_WIDTH * SCALE : 0);

    // 기본 마도카 자리 — 점선 네모
    Region slot = new Region();
    slot.getStyleClass().add("fit-slot");
    double slotWidth = SettingsDialog.MASCOT_WIDTH * SCALE;
    double slotHeight = SettingsDialog.MASCOT_HEIGHT * SCALE;
    slot.setMinSize(slotWidth, slotHeight);
    slot.setMaxSize(slotWidth, slotHeight);

    StackPane stage = new StackPane(view, slot);
    stage.getStyleClass().add("fit-stage");
    stage.setMinSize(STAGE_WIDTH, STAGE_HEIGHT);
    stage.setMaxSize(STAGE_WIDTH, STAGE_HEIGHT);
    for (var node : stage.getChildren()) {
      StackPane.setAlignment(node, Pos.BOTTOM_RIGHT);
      StackPane.setMargin(node, new Insets(0, STAGE_MARGIN, STAGE_MARGIN, 0));
    }
    // 왼쪽으로 칸 밖까지 삐져나가는 그림은 칸 끝에서 자른다 — 위젯 창 끝에서 잘리듯
    Rectangle clip = new Rectangle(STAGE_WIDTH, STAGE_HEIGHT);
    clip.setArcWidth(16);
    clip.setArcHeight(16);
    stage.setClip(clip);

    Label name = new Label(label);
    name.getStyleClass().add("dialog-label");
    Label sub = new Label(hint);
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);
    sub.setMaxWidth(STAGE_WIDTH);

    VBox content = new VBox(8, stage, name, sub);
    content.setAlignment(Pos.TOP_LEFT);

    Button card = new Button();
    card.setGraphic(content);
    card.getStyleClass().add("fit-card");
    if (selected)
      card.getStyleClass().add("fit-card-current");
    return card;
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
