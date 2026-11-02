package kyubey.notepad;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.HitInfo;
import javafx.scene.text.Text;
import kyubey.ui.Ime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 메모를 전체화면으로 폈을 때의 적는 칸 — 신문처럼 세로 단으로 나눠 왼쪽 → 가운데 → 오른쪽으로 흘려 쓴다.
 *
 * 화면엔 단이 VISIBLE 개 들어가고, 글이 넘치면 같은 크기 단을 오른쪽에 덧붙여 옆으로 스크롤한다.
 * 위아래 스크롤은 없다 (휠도 옆으로 돌린다).
 *
 * 단마다 TextArea 를 하나씩 두고 글은 모두 원래 적는 칸(source)에 묶는다 (bindBidirectional).
 * 각 TextArea 는 글 전체 높이로 세워 두고 단 창(window)이 제 몫 줄만 잘라 보여준다 — TextArea 안은 스크롤하지 않는다.
 * 줄 가운데가 잘리지 않게 줄 단위로 끊어서, 단마다 실제로 차는 높이는 조금씩 다를 수 있다.
 * 캐럿이 지금 단 밖 줄로 나가면 (↓, 줄바꿈, 넘친 글) 그 줄이 있는 단으로 포커스를 넘긴다.
 */
public final class ColumnEditor extends ScrollPane {

  /** 한 화면에 보이는 단 수 */
  private static final int VISIBLE = 3;

  /** 단 사이 */
  private static final double GAP = 10;

  /** 단 판 테두리 + 안쪽 여백. CSS (.notepad-page) 의 border-width + padding 과 맞춘다 */
  private static final double INSET = 11;

  private final TextArea source;
  /** 단 TextArea 를 새로 만들 때마다 — 키 · 오른쪽 클릭 메뉴를 NotepadPane 이 단다 */
  private final Consumer<TextArea> setup;
  private final HBox row = new HBox(GAP);
  private final List<Column> columns = new ArrayList<>();
  /** 줄 위치를 재는 그림자 글 — 단 TextArea 와 같은 글꼴 · 폭으로 둔다 */
  private final Text measure = new Text();

  /** 단마다 첫 줄 y (글 맨 위 = 0). 아직 화면 크기를 모르면 비어 있다 */
  private final List<Double> starts = new ArrayList<>();
  private double pageW;
  private double innerW;
  private double innerH;

  /** 지금 캐럿이 있는 단 */
  private Column focused;
  /** 한글 조합 중 — 조합 중에 포커스를 옮기면 글자가 깨져서, 끝날 때까지 단을 안 넘긴다 */
  private boolean composing;
  /** 화면에 붙기 전에 부른 focusAt — 첫 배치 뒤에 한다 */
  private Runnable pending;

  public ColumnEditor(TextArea source, Consumer<TextArea> setup) {
    this.source = source;
    this.setup = setup;
    getStyleClass().add("notepad-columns");
    setContent(row);
    setFitToHeight(true);
    setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
    setVbarPolicy(ScrollBarPolicy.NEVER);
    setFocusTraversable(false);
    measure.setTextOrigin(VPos.TOP);

    viewportBoundsProperty().addListener((obs, was, is) -> relayout());
    // 접혀 있는 동안엔 글이 바뀌어도 안 잰다 — 다시 펼 때 (화면에 붙을 때) 한 번에
    sceneProperty().addListener((obs, was, is) -> relayout());
    source.textProperty().addListener((obs, was, is) -> relayout());

    // 위아래 휠도 옆으로 — TextArea 까지 내려가기 전에 가로챈다
    addEventFilter(ScrollEvent.SCROLL, e -> {
      double extra = contentWidth() - getViewportBounds().getWidth();
      if (extra > 0) {
        double delta = e.getDeltaX() != 0 ? e.getDeltaX() : e.getDeltaY();
        setHvalue(clamp(getHvalue() - delta / extra, 0, 1));
      }
      e.consume();
    });
  }

  /** 지금 쓰는 단의 TextArea — 아직 단이 없으면 null */
  public TextArea current() {
    return focused != null ? focused.area : columns.isEmpty() ? null : columns.get(0).area;
  }

  /** 캐럿(과 선택)을 그 줄이 있는 단에 두고 포커스를 준다 */
  public void focusAt(int anchor, int caret) {
    if (starts.isEmpty()) {
      pending = () -> focusAt(anchor, caret);
      return;
    }
    Column target = columns.get(columnOf(lineTop(caret)));
    target.area.selectRange(anchor, caret);
    Ime.focus(target.area);
    reveal(target);
  }

  // ── 배치 ──

  /** 화면 크기나 글이 바뀔 때 — 단 폭 · 높이를 다시 잡고 글을 줄 단위로 단에 나눈다 */
  private void relayout() {
    Bounds view = getViewportBounds();
    if (getScene() == null || view.getWidth() <= 0 || view.getHeight() <= 0)
      return;
    pageW = (view.getWidth() - GAP * (VISIBLE - 1)) / VISIBLE;
    innerW = Math.max(1, pageW - 2 * INSET);
    innerH = Math.max(1, view.getHeight() - 2 * INSET);

    if (columns.isEmpty())
      addColumn();
    TextArea first = columns.get(0).area;
    first.applyCss();
    first.layout();
    copyLayout(first);
    measure.setWrappingWidth(innerW);
    measure.setText(source.getText());
    double total = measure.getLayoutBounds().getHeight();

    starts.clear();
    starts.add(0.0);
    double y = 0;
    while (y + innerH < total) {
      y = cutAt(y);
      starts.add(y);
    }

    int count = Math.max(VISIBLE, starts.size());
    while (columns.size() < count)
      addColumn();
    while (columns.size() > count)
      removeColumn();

    // 넘쳐도 TextArea 안이 스크롤하지 않게 글 높이보다 한 단만큼 더 세운다
    double areaH = total + innerH;
    for (int i = 0; i < columns.size(); i++) {
      Column c = columns.get(i);
      double top = i < starts.size() ? starts.get(i) : total;
      double end = i + 1 < starts.size() ? starts.get(i + 1) : top + innerH;
      c.page.setMinSize(pageW, view.getHeight());
      c.page.setPrefSize(pageW, view.getHeight());
      c.page.setMaxSize(pageW, view.getHeight());
      c.window.setMinSize(innerW, innerH);
      c.window.setPrefSize(innerW, innerH);
      c.window.setMaxSize(innerW, innerH);
      c.area.setMinSize(innerW, areaH);
      c.area.setPrefSize(innerW, areaH);
      c.area.setMaxSize(innerW, areaH);
      c.area.setLayoutY(-top);
      c.clip.setWidth(innerW);
      c.clip.setHeight(end - top);
    }

    if (pending != null) {
      Runnable run = pending;
      pending = null;
      Platform.runLater(run);
    }
  }

  /**
   * 그림자 글을 TextArea 안 실제 글(스킨의 Text)과 똑같이 맞춘다. 줄 높이 잡는 법(boundsType)이
   * 기본값과 달라서 (LOGICAL_VERTICAL_CENTER) 글꼴만 맞추면 줄이 조금씩 어긋나 단 경계에서 글자가 잘린다.
   */
  private void copyLayout(TextArea area) {
    measure.setFont(area.getFont());
    for (Node node : area.lookupAll(".text")) {
      if (node instanceof Text text && !(node.getParent() instanceof TextArea)) {
        measure.setFont(text.getFont());
        measure.setBoundsType(text.getBoundsType());
        measure.setLineSpacing(text.getLineSpacing());
        return;
      }
    }
  }

  /** from 에서 시작한 단을 끊을 y — 단 밑변에 걸친 줄은 통째로 다음 단으로 넘긴다 */
  private double cutAt(double from) {
    double limit = from + innerH;
    HitInfo hit = measure.hitTest(new Point2D(0, limit));
    double top = lineTop(hit.getCharIndex());
    // 한 줄이 단보다 크면 어쩔 수 없이 단 높이에서 자른다
    return top > from ? Math.min(top, limit) : limit;
  }

  /** index 글자가 놓인 줄의 위 y — measure 에 지금 글이 들어 있어야 한다 */
  private double lineTop(int index) {
    measure.setCaretBias(true);
    measure.setCaretPosition(Math.min(index, measure.getText().length()));
    double y = Double.MAX_VALUE;
    for (PathElement el : measure.getCaretShape()) {
      if (el instanceof MoveTo move)
        y = Math.min(y, move.getY());
      else if (el instanceof LineTo line)
        y = Math.min(y, line.getY());
    }
    return y == Double.MAX_VALUE ? 0 : y - measure.getLayoutBounds().getMinY();
  }

  /** y 줄이 들어간 단 번호 */
  private int columnOf(double y) {
    int at = 0;
    for (int i = 1; i < starts.size(); i++) {
      if (starts.get(i) <= y + 0.5)
        at = i;
    }
    return at;
  }

  private double contentWidth() {
    int n = columns.size();
    return n * pageW + Math.max(0, n - 1) * GAP;
  }

  /** 그 단이 화면 밖이면 옆으로 스크롤해 들인다 */
  private void reveal(Column c) {
    double viewW = getViewportBounds().getWidth();
    double extra = contentWidth() - viewW;
    if (extra <= 0)
      return;
    double x = columns.indexOf(c) * (pageW + GAP);
    double offset = getHvalue() * extra;
    if (x < offset)
      offset = x;
    else if (x + pageW > offset + viewW)
      offset = x + pageW - viewW;
    setHvalue(clamp(offset / extra, 0, 1));
  }

  // ── 단 ──

  private void addColumn() {
    Column c = new Column();
    columns.add(c);
    row.getChildren().add(c.page);
  }

  private void removeColumn() {
    Column c = columns.remove(columns.size() - 1);
    row.getChildren().remove(c.page);
    c.area.textProperty().unbindBidirectional(source.textProperty());
    if (focused == c)
      focused = null;
  }

  /** 캐럿이 지금 단 밖으로 나갔으면 그 줄이 있는 단으로 따라간다 */
  private void follow() {
    if (focused == null || composing || focused.area.isPressed() || starts.isEmpty())
      return;
    TextArea area = focused.area;
    Column target = columns.get(columnOf(lineTop(area.getCaretPosition())));
    if (target != focused) {
      target.area.selectRange(area.getAnchor(), area.getCaretPosition());
      Ime.focus(target.area);
    }
    reveal(target);
  }

  private void caretMoved(Column c) {
    if (focused != c)
      return;
    // 접을 때 원래 적는 칸이 같은 자리에서 이어 쓰게
    source.selectRange(c.area.getAnchor(), c.area.getCaretPosition());
    Platform.runLater(this::follow);
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  /** 단 하나 — 둥근 판(page) 안 창(window)이 글 전체 높이 TextArea 를 제 몫만큼 잘라 보인다 */
  private final class Column {
    final TextArea area = new TextArea();
    final Rectangle clip = new Rectangle();
    final Pane window = new Pane(area);
    final StackPane page = new StackPane(window);

    Column() {
      area.getStyleClass().add("notepad-column");
      area.setWrapText(true);
      area.textProperty().bindBidirectional(source.textProperty());
      // 글 높이를 넉넉히 잡아 두지만, 잠깐이라도 안에서 스크롤되면 도로 맨 위로
      area.scrollTopProperty().addListener((obs, was, is) -> {
        if (is.doubleValue() != 0)
          area.setScrollTop(0);
      });
      area.focusedProperty().addListener((obs, was, is) -> {
        if (is) {
          focused = this;
          caretMoved(this);
        }
      });
      area.caretPositionProperty().addListener(obs -> caretMoved(this));
      area.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, e -> composing = !e.getComposed().isEmpty());
      // 끌어 고르는 동안엔 안 넘기고, 손을 뗀 뒤에 따라간다
      area.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> caretMoved(this));
      window.setClip(clip);
      page.getStyleClass().add("notepad-page");
      StackPane.setAlignment(window, Pos.TOP_LEFT);
      setup.accept(area);
    }
  }
}
