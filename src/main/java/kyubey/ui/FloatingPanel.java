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
 * 오른쪽 아래 꼭지점 (resizer) 을 끌면 크기가 바뀐다 — 왼쪽 위 자리는 그대로 두고. 한 번 바꾸면 얹힌 판 높이를 더는 안 따른다.
 */
public class FloatingPanel extends VBox {

  /** 얹힌 판 높이 중 이만큼을 차지한다 */
  private static final double HEIGHT_RATIO = 0.4;

  private final HBox grip = new HBox();
  private double pressX;
  private double pressY;
  private double startX;
  private double startY;
  private boolean dragging;

  /** 오른쪽 아래 꼭지점 크기 손잡이 — 자리 잡기는 layoutChildren 이 따로 (VBox 줄에 안 낀다) */
  private final Region resizer = new Region();
  private boolean resizing;
  private double startW;
  private double startH;
  /** 누른 때 판 왼쪽 위 자리 — 끄는 동안 layoutX 가 한 박자 늦게 따라와서 처음 값을 쓴다 */
  private double startLeft;
  private double startTop;

  /** 이보다 작게는 못 줄인다 */
  private static final double MIN_W = 160;
  private static final double MIN_H = 120;
  /** 꼭지점 손잡이 한 변 */
  private static final double RESIZER = 14;

  public FloatingPanel(double width, Insets margin) {
    this(width, margin, false);
  }

  /** fitContent 면 얹힌 판 높이를 따르지 않고 안에 담은 만큼만 선다 (수다 상대 목록처럼 짧은 것) */
  public FloatingPanel(double width, Insets margin, boolean fitContent) {
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

    resizer.getStyleClass().add("float-resizer");
    resizer.setManaged(false);
    resizer.setCursor(Cursor.SE_RESIZE);
    resizer.setOnMousePressed(e -> {
      resizing = true;
      pressX = e.getSceneX();
      pressY = e.getSceneY();
      startW = getWidth();
      startH = getHeight();
      startX = getTranslateX();
      startLeft = getLayoutX() + getTranslateX();
      startTop = getLayoutY() + getTranslateY();
      e.consume();
    });
    resizer.setOnMouseDragged(e -> {
      if (!resizing || !(getParent() instanceof Region box))
        return;
      // 오른쪽 · 아래 가장자리가 얹힌 판 밖으로 나가지 않게
      double w = clamp(startW + e.getSceneX() - pressX, MIN_W, box.getWidth() - startLeft);
      double h = clamp(startH + e.getSceneY() - pressY, MIN_H, box.getHeight() - startTop);
      maxHeightProperty().unbind();
      prefHeightProperty().unbind();
      setPrefWidth(w);
      setMaxWidth(w);
      setPrefHeight(h);
      setMaxHeight(h);
      // 판은 오른쪽 위에 붙어 서서, 넓어지면 왼쪽으로 자란다 — 그만큼 밀어 왼쪽 가장자리를 붙잡아 둔다
      setTranslateX(startX + (w - startW));
      e.consume();
    });
    resizer.setOnMouseReleased(e -> {
      resizing = false;
      e.consume();
    });

    if (fitContent)
      setMaxHeight(Region.USE_PREF_SIZE);
    parentProperty().addListener((obs, was, is) -> {
      if (fitContent || resized())
        return;
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
    getChildren().setAll(grip, content, resizer);
  }

  /** 꼭지점으로 크기를 바꿨는지 — 그 뒤엔 높이가 묶여 있지 않다 */
  private boolean resized() {
    return !prefHeightProperty().isBound() && getPrefHeight() != Region.USE_COMPUTED_SIZE;
  }

  @Override
  protected void layoutChildren() {
    super.layoutChildren();
    // setContent 가 맨 끝에 넣어 둬서 내용 위에 얹힌다
    resizer.resizeRelocate(getWidth() - RESIZER, getHeight() - RESIZER, RESIZER, RESIZER);
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
