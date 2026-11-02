package kyubey.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 목표 맵 · 메모를 크게 폈을 때 판 안 오른쪽 위에 뜨는 둥근 판 (목표 말 칸, 메모 목록).
 *
 * 바탕은 위젯 창과 같은 반투명 색 (.widget-root) 이라 테마 · 투명도 설정을 그대로 따라간다.
 * 키는 얹힌 판 높이의 HEIGHT_RATIO 만큼. 위쪽 손잡이나 가장자리를 잡고 끌면 판 안에서 옮겨진다 —
 * 안쪽 내용(말 칸 · 목록)을 잡으면 평소대로 스크롤 · 고르기가 되게 거기선 안 끈다.
 */
public final class FloatingPanel extends VBox {

  /** 얹힌 판 높이 중 이만큼을 차지한다 */
  private static final double HEIGHT_RATIO = 0.4;

  private final HBox grip = new HBox();
  private double pressX;
  private double pressY;
  private double startX;
  private double startY;
  private boolean dragging;

  public FloatingPanel(double width, Insets margin) {
    getStyleClass().addAll("widget-root", "float-panel");
    setSpacing(4);
    setPrefWidth(width);
    setMaxWidth(width);
    StackPane.setAlignment(this, Pos.TOP_RIGHT);
    StackPane.setMargin(this, margin);
    // 창 끌기 (WindowDrag) 가 이 판 끌기를 가로채지 않게
    getProperties().put(WindowDrag.NO_DRAG, true);

    Region bar = new Region();
    bar.getStyleClass().add("float-grip-bar");
    grip.getStyleClass().add("float-grip");
    grip.setAlignment(Pos.CENTER);
    grip.getChildren().add(bar);
    grip.setCursor(Cursor.MOVE);

    parentProperty().addListener((obs, was, is) -> {
      maxHeightProperty().unbind();
      prefHeightProperty().unbind();
      if (is instanceof Region box) {
        maxHeightProperty().bind(box.heightProperty().multiply(HEIGHT_RATIO));
        prefHeightProperty().bind(box.heightProperty().multiply(HEIGHT_RATIO));
      }
    });

    setOnMousePressed(e -> {
      dragging = onHandle(e.getTarget());
      if (!dragging)
        return;
      pressX = e.getSceneX();
      pressY = e.getSceneY();
      startX = getTranslateX();
      startY = getTranslateY();
      e.consume();
    });
    setOnMouseDragged(e -> {
      if (!dragging || !(getParent() instanceof Region box))
        return;
      // 얹힌 판 밖으로 나가지 않게
      double x = startX + e.getSceneX() - pressX;
      double y = startY + e.getSceneY() - pressY;
      setTranslateX(clamp(x, -getLayoutX(), box.getWidth() - getWidth() - getLayoutX()));
      setTranslateY(clamp(y, -getLayoutY(), box.getHeight() - getHeight() - getLayoutY()));
      e.consume();
    });
    setOnMouseReleased(e -> dragging = false);
  }

  /** 안에 담을 것 — 손잡이 밑을 꽉 채운다 */
  public void setContent(Node content) {
    VBox.setVgrow(content, Priority.ALWAYS);
    getChildren().setAll(grip, content);
  }

  /** 옮겨둔 자리를 처음 자리 (오른쪽 위) 로 */
  public void resetPosition() {
    setTranslateX(0);
    setTranslateY(0);
  }

  /** 누른 데가 손잡이거나 판 가장자리(안쪽 내용 밖)인지 */
  private boolean onHandle(Object target) {
    for (Node at = target instanceof Node node ? node : null; at != null && at != this; at = at.getParent()) {
      if (at == grip)
        return true;
    }
    return target == this;
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(Math.max(min, max), v));
  }
}
