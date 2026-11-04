package kyubey.todo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import kyubey.ui.WindowDrag;

/**
 * 오로라 (Main 의 빛 속 입력창) 에 띄우는 오늘 할 일. 할 일마다 기본 위젯의 카테고리 이름표 같은 풍선에
 * 카테고리 색 테두리를 두르고, 그 안에 네잎 체크칸과 이름을 넣는다.
 *
 *  - 안 한 할 일은 입력창 밑 빛 (아래 칸) 에서 벽에 튕기며 돌아다닌다. 끝내면 로켓처럼 쏘아 올린다 —
 *    풍선이 잠깐 멈추고 둘레에 카테고리 색 타원 그라데이션 (오로라 빛) 이 피어오른 뒤, 가속하다 같은 빠르기로
 *    입력창 위 빛 (위 칸) 까지 비스듬히 올라가고, 끝에선 감속해 멈추며 그라데이션이 옅어진다.
 *    가는 쪽은 "풍선 → 오로라 가운데" 와 "연직 위" 의 사이각을 이등분하는 쪽이다.
 *    다 올라가면 거기서 돌아다닌다. 체크를 풀면 입력창 밑 아무 데로나 순간이동한다
 *  - 늘 느리게 다니고 (BASE_SPEED 가 가장 빠른 것), 마우스가 가까울수록 더 느려진다.
 *    마우스가 입력창보다 위에 있는 동안엔 마우스 때문에 빠르기가 바뀌지 않는다 — 넘어가던 때 빠르기를 지킨다
 *  - 밀린 할 일은 밀린 날수만큼 조금씩 더 빨리 다닌다 (마우스에 따라 느려지는 건 같다)
 *  - 풍선을 잡아 끌어 제 칸 안 아무 데나 놓는다. 체크칸을 누르면 기본 위젯에서 누른 것과 같다 (TodoPane.toggle)
 *
 * 할 일은 TodoPane 이 들고 있는 것을 그대로 쓴다 — 그 판이 새로 그리거나 저장할 때마다 따라 맞춘다.
 * 칸 높이는 입력창 자리로 가른다 (setInput 에 준 노드를 이 판 좌표로 잰다).
 */
public final class AuroraTodos extends Pane {

  /** 가장 빠를 때 (마우스가 멀 때) 속도 — 이것도 느리다. px/초 */
  private static final double BASE_SPEED = 30;
  /** 마우스가 이만큼 안으로 들어오면 그때부터 느려진다 */
  private static final double SLOW_RADIUS = 260;
  /** 마우스 바로 밑에서도 이만큼은 움직인다 (BASE_SPEED 에 곱하는 값) */
  private static final double MIN_FACTOR = 0.08;
  /** 밀린 하루마다 더 빨라지는 몫, 그리고 몇 날까지 쳐줄지 */
  private static final double OVERDUE_STEP = 0.4;
  private static final int OVERDUE_CAP = 5;
  /** 쏘아 올리기 — 멈춰서 빛이 피는 시간(초), 가속도 · 끝에서 멈추는 감속도(px/초²), 다 붙은 빠르기(px/초) */
  private static final double LAUNCH_PAUSE = 0.55;
  private static final double LAUNCH_ACCEL = 110;
  private static final double LAUNCH_DECEL = 90;
  private static final double LAUNCH_SPEED = 140;
  /** 멈추기 직전에도 이만큼은 간다 — 감속이 끝없이 늘어지지 않게 (px/초) */
  private static final double LAUNCH_CRAWL = 4;
  /** 풍선에 다는 타원 빛 (풍선이 늘 한가운데) — 풍선 크기에 곱하는 가로 · 세로(가는 쪽) 몫, 빠르기마다 길어지는 몫 */
  private static final double TRAIL_WIDE = 1.5;
  private static final double TRAIL_LONG = 2.6;
  private static final double TRAIL_PER_SPEED = 0.22;
  /** 입력창에서 띄우는 거리, 판 가장자리에서 띄우는 거리 (빛이 흐려지는 데로 안 가게) */
  private static final double INPUT_GAP = 14;
  private static final double EDGE_X = 70;
  private static final double EDGE_Y = 36;
  /** 풍선 가운데가 머무는 빛 타원의 반지름 (판 크기에 곱하는 값) — 빛이 아직 진한 데까지 */
  private static final double GLOW_RADIUS = 0.42;
  /** 풍선 안 이름의 가장 긴 너비 */
  private static final double TEXT_MAX = 200;

  private enum State {
    WANDER, LAUNCH
  }

  private final class Bubble {
    TodoStore.Todo todo;
    final HBox node;
    final TodoPane.Clover clover;
    final Label text = new Label();
    double x, y;
    /** 다니는 쪽 (길이 1) */
    double dx, dy;
    boolean placed;
    boolean dragging;
    double grabX, grabY;
    boolean done;
    /**
     * 지금 쓰는 마우스 몫 — 마우스가 입력창 밑이면 mouseFactor 를 그대로 따르고, 위면 넘어가던 때 몫을 그대로 지킨다
     * (넘어가는 순간 확 빨라지지 않게). 다 올라가 멈추면 1 (가장 빠르게) 에서 다시 시작한다
     */
    double factor = 1;
    State state = State.WANDER;
    /** 쏘아 올리는 중 — 시작한 뒤 지난 시간, 지금 빠르기, 떠난 자리, 가는 쪽 (길이 1), 갈 길이 · 간 길이, 둘레의 타원 빛 */
    double launchTime, launchSpeed, fromX, fromY, launchDx, launchDy, launchLength, launched;
    Region flame;

    Bubble(TodoStore.Todo todo) {
      this.todo = todo;
      done = done(todo);
      clover = new TodoPane.Clover(Color.web(pane.colorOf(todo)), done);
      text.getStyleClass().add("todo-text");
      text.setMaxWidth(TEXT_MAX);
      node = new HBox(8, clover, text);
      node.setAlignment(Pos.CENTER_LEFT);
      node.getStyleClass().add("aurora-todo");
      // 풍선 위에선 창(빛) 을 끌지 않는다 — 풍선을 끈다 (WindowDrag.NO_DRAG)
      node.getProperties().put(WindowDrag.NO_DRAG, true);
      node.setCursor(Cursor.OPEN_HAND);
      aim();
      clover.setOnMouseClicked(e -> {
        if (e.getButton() != MouseButton.PRIMARY)
          return;
        e.consume();
        pane.toggle(this.todo);
      });
      node.setOnMousePressed(e -> {
        if (e.getButton() != MouseButton.PRIMARY || within(e.getTarget(), clover))
          return;
        e.consume();
        Point2D at = sceneToLocal(e.getSceneX(), e.getSceneY());
        dragging = true;
        grabX = at.getX() - x;
        grabY = at.getY() - y;
        node.setCursor(Cursor.CLOSED_HAND);
        node.toFront();
      });
      node.setOnMouseDragged(e -> {
        if (!dragging)
          return;
        e.consume();
        Point2D at = sceneToLocal(e.getSceneX(), e.getSceneY());
        double[] band = band(done);
        x = clamp(at.getX() - grabX, band[0], band[1] - width());
        y = clamp(at.getY() - grabY, band[2], band[3] - height());
        keepInGlow(this, false);
        // 쏘아 올리던 걸 잡으면 거기서 그친다
        land();
        place();
      });
      node.setOnMouseReleased(e -> {
        if (!dragging)
          return;
        e.consume();
        dragging = false;
        node.setCursor(Cursor.OPEN_HAND);
        aim();
      });
      update(todo);
    }

    /** 새로 읽은 할 일로 글자 · 색 · 끝냄을 맞춘다. 끝냈으면 쏘아 올리고, 체크를 풀었으면 입력창 밑 아무 데로나 순간이동 */
    void update(TodoStore.Todo todo) {
      this.todo = todo;
      text.setText(todo.content);
      boolean now = done(todo);
      text.getStyleClass().remove("todo-text-overdue");
      if (todo.overdue() && !now)
        text.getStyleClass().add("todo-text-overdue");
      node.setStyle("-fx-border-color: " + pane.colorOf(todo) + ";");
      clover.set(now);
      boolean changed = now != done;
      done = now;
      if (!changed || !placed)
        return;
      dragging = false;
      node.setCursor(Cursor.OPEN_HAND);
      if (now) {
        launch();
      } else {
        land();
        scatter(this, band(false));
        aim();
        place();
      }
    }

    /** 쏘아 올리기 시작 — 갈 쪽 · 닿을 데를 잡고, 둘레에 타원 빛을 단다 (풍선 뒤로) */
    void launch() {
      land();
      aimLaunch(this);
      launchTime = 0;
      launchSpeed = 0;
      launched = 0;
      state = State.LAUNCH;
      Color color = Color.web(pane.colorOf(todo));
      flame = new Region();
      flame.setMouseTransparent(true);
      // 크기는 flameAt 이 직접 — 판이 제 크기(0)로 되돌리지 않게
      flame.setManaged(false);
      // 오로라 빛 판 (.full-glow) 과 같은 결의 타원 그라데이션 — 가운데 진하고 가장자리로 투명해진다
      flame.setBackground(new Background(new BackgroundFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true,
          CycleMethod.NO_CYCLE, new Stop(0, color.deriveColor(0, 1, 1.1, 0.9)),
          new Stop(0.45, color.deriveColor(0, 1, 1.2, 0.6)), new Stop(0.75, color.deriveColor(0, 1, 1.2, 0.25)),
          new Stop(1, Color.TRANSPARENT)), CornerRadii.EMPTY, null)));
      flame.setOpacity(0);
      getChildren().add(getChildren().indexOf(node), flame);
      flameAt();
    }

    /** 타원 빛을 풍선에 붙인다 — 풍선이 한가운데, 긴 축이 가는 쪽을 따르고, 빠를수록 길다 */
    void flameAt() {
      if (flame == null)
        return;
      double w = width() * TRAIL_WIDE;
      double h = height() * TRAIL_LONG + launchSpeed * TRAIL_PER_SPEED;
      double cx = x + width() / 2;
      double cy = y + height() / 2;
      flame.resizeRelocate(cx - w / 2, cy - h / 2, w, h);
      // 세로가 긴 타원을 가는 쪽으로 돌린다 (가운데를 축으로)
      flame.setRotate(Math.toDegrees(Math.atan2(launchDx, -launchDy)));
    }

    /** 쏘아 올리기를 그치고 (다 올라갔거나 붙잡혔거나) 그 자리에서 돌아다닌다 */
    void land() {
      if (flame != null)
        getChildren().remove(flame);
      flame = null;
      state = State.WANDER;
    }

    void aim() {
      double angle = random.nextDouble() * Math.PI * 2;
      dx = Math.cos(angle);
      dy = Math.sin(angle);
    }

    double width() {
      return node.getWidth() > 0 ? node.getWidth() : node.prefWidth(-1);
    }

    double height() {
      return node.getHeight() > 0 ? node.getHeight() : node.prefHeight(-1);
    }

    void place() {
      node.setLayoutX(x);
      node.setLayoutY(y);
    }

    /** 밀린 날수만큼 빨라지는 몫 — 끝낸 건 1 */
    double overdueFactor() {
      if (done || !todo.overdue())
        return 1;
      try {
        long days = ChronoUnit.DAYS.between(LocalDate.parse(todo.overdueSince), TodoStore.today());
        return 1 + OVERDUE_STEP * Math.max(1, Math.min(OVERDUE_CAP, days));
      } catch (RuntimeException e) {
        return 1 + OVERDUE_STEP;
      }
    }
  }

  private final TodoPane pane;
  private final Random random = new Random();
  private final Map<String, Bubble> bubbles = new LinkedHashMap<>();
  private Node input;
  /**
   * 이번 걸음의 마우스 자리 (이 판 좌표). 못 읽었으면 null.
   * 장면에 오는 MOUSE_MOVED 로 재면 입력창 위쪽에선 이벤트가 안 와서 마지막 자리에 멈춰 있었다 —
   * 그래서 걸음마다 OS 커서 자리를 직접 읽는다 (Robot)
   */
  private Point2D mouse;
  private Robot robot;
  private long last;

  private final AnimationTimer timer = new AnimationTimer() {
    @Override
    public void handle(long now) {
      double dt = last == 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
      last = now;
      if (getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing())
        return;
      mouse = pointer();
      step(dt, now / 1e9);
    }
  };

  public AuroraTodos(TodoPane pane) {
    this.pane = pane;
    // 빈 데는 뒤 (빛) 로 흘려보낸다 — 풍선만 잡힌다
    setPickOnBounds(false);
    pane.addOnRender(this::sync);
    sceneProperty().addListener((obs, was, is) -> running());
    visibleProperty().addListener((obs, was, is) -> running());
    sync();
  }

  /** 칸을 가를 입력창 */
  public void setInput(Node input) {
    this.input = input;
  }

  /** 지금 OS 커서 자리를 이 판 좌표로 — 풍선 위가 아니어도, 창 어디서든 가까이 오면 느려지게 */
  private Point2D pointer() {
    try {
      if (robot == null)
        robot = new Robot();
      Point2D screen = robot.getMousePosition();
      return screen == null ? null : screenToLocal(screen);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void running() {
    last = 0;
    if (isVisible() && getScene() != null)
      timer.start();
    else
      timer.stop();
  }

  /** TodoPane 의 오늘 할 일에 맞춘다 — 있던 풍선은 자리를 지키고, 새 건 제 칸 아무 데나, 없어진 건 뺀다 */
  private void sync() {
    Set<String> seen = new HashSet<>();
    for (TodoStore.Todo todo : pane.todos()) {
      if (todo.id == null || !seen.add(todo.id))
        continue;
      Bubble bubble = bubbles.get(todo.id);
      if (bubble == null) {
        bubble = new Bubble(todo);
        bubbles.put(todo.id, bubble);
        getChildren().add(bubble.node);
      } else {
        bubble.update(todo);
      }
    }
    List<String> gone = new ArrayList<>();
    for (String id : bubbles.keySet()) {
      if (!seen.contains(id))
        gone.add(id);
    }
    for (String id : gone) {
      Bubble bubble = bubbles.remove(id);
      bubble.land();
      getChildren().remove(bubble.node);
    }
  }

  private static boolean done(TodoStore.Todo todo) {
    return todo.done || todo.doneOn != null;
  }

  /** 제 칸 [왼, 오른, 위, 아래] — 끝낸 건 입력창 위, 안 한 건 입력창 밑 */
  private double[] band(boolean done) {
    double w = getWidth();
    double h = getHeight();
    double top = h / 2;
    double bottom = h / 2;
    if (input != null && input.getScene() == getScene()) {
      Bounds at = sceneToLocal(input.localToScene(input.getBoundsInLocal()));
      top = at.getMinY();
      bottom = at.getMaxY();
    }
    double left = EDGE_X;
    double right = Math.max(left + 1, w - EDGE_X);
    return done
        ? new double[] { left, right, EDGE_Y, Math.max(EDGE_Y + 1, top - INPUT_GAP) }
        : new double[] { left, right, bottom + INPUT_GAP, Math.max(bottom + INPUT_GAP + 1, h - EDGE_Y) };
  }

  private void step(double dt, double time) {
    if (getWidth() <= 0 || getHeight() <= 0)
      return;
    double[] high = band(true);
    double[] low = band(false);
    for (Bubble b : bubbles.values()) {
      double bw = b.width();
      double bh = b.height();
      double[] band = b.done ? high : low;
      if (!b.placed) {
        if (bw <= 0)
          continue;
        scatter(b, band);
        b.placed = true;
        b.place();
        continue;
      }
      if (b.dragging)
        continue;
      switch (b.state) {
        case LAUNCH -> {
          b.launchTime += dt;
          if (b.launchTime < LAUNCH_PAUSE) {
            // 멈춰서 불꽃이 피어오른다
            b.flame.setOpacity(b.launchTime / LAUNCH_PAUSE);
          } else {
            // 가속하다 LAUNCH_SPEED 에서 같은 빠르기로, 남은 길이 감속해 멈출 만큼이 되면 LAUNCH_DECEL 로 줄여 멈춘다.
            // 빛은 간 만큼 옅어진다
            double left = b.launchLength - b.launched;
            double brake = Math.sqrt(2 * LAUNCH_DECEL * Math.max(0, left));
            b.launchSpeed = Math.max(LAUNCH_CRAWL,
                Math.min(Math.min(LAUNCH_SPEED, b.launchSpeed + LAUNCH_ACCEL * dt), brake));
            b.launched = Math.min(b.launchLength, b.launched + b.launchSpeed * dt);
            b.x = b.fromX + b.launchDx * b.launched;
            b.y = b.fromY + b.launchDy * b.launched;
            double gone = b.launchLength <= 0 ? 1 : b.launched / b.launchLength;
            b.flame.setOpacity(clamp(1 - gone, 0, 1));
            if (b.launched >= b.launchLength) {
              b.land();
              b.factor = 1;
              b.aim();
            }
          }
          b.flameAt();
        }
        case WANDER -> {
          if (mouse == null || mouse.getY() >= high[3] + INPUT_GAP)
            b.factor = mouseFactor(b.x + bw / 2, b.y + bh / 2);
          double speed = BASE_SPEED * b.overdueFactor() * b.factor;
          b.x += b.dx * speed * dt;
          b.y += b.dy * speed * dt;
          bounce(b, band, bw, bh);
          keepInGlow(b, true);
          // 타원으로 당기다 입력창 쪽으로 넘어가면 칸 안으로 도로
          bounce(b, band, bw, bh);
        }
      }
      b.place();
    }
    collide(high, true);
    collide(low, false);
  }

  /**
   * 쏘아 올릴 쪽과 길이를 잡는다 — "풍선 가운데 → 오로라 가운데" 와 "연직 위" 의 사이각을 이등분하는 쪽.
   * 그 줄을 따라가다 위 칸 아무 높이에서 멈추는데, 멈출 데가 칸 · 빛 타원 밖이면 다른 높이를 뽑고,
   * 끝내 없으면 줄 위에서 칸 안에 드는 가장 가까운 데로
   */
  private void aimLaunch(Bubble b) {
    double bw = b.width();
    double bh = b.height();
    double cx = b.x + bw / 2;
    double cy = b.y + bh / 2;
    double ux = getWidth() / 2 - cx;
    double uy = getHeight() / 2 - cy;
    double len = Math.hypot(ux, uy);
    double dx = 0;
    double dy = -1;
    if (len > 0) {
      // 두 단위 벡터의 합이 사이각의 이등분선 — 서로 반대 (바로 밑을 볼 때) 면 그냥 위로
      double sx = ux / len;
      double sy = uy / len - 1;
      double sl = Math.hypot(sx, sy);
      if (sl > 1e-6) {
        dx = sx / sl;
        dy = sy / sl;
      }
    }
    b.fromX = b.x;
    b.fromY = b.y;
    b.launchDx = dx;
    b.launchDy = dy;
    double[] high = band(true);
    double top = high[2];
    double bottom = Math.max(top, high[3] - bh);
    double fallback = -1;
    for (int tries = 0; tries < 40; tries++) {
      double ty = tries < 20 ? top + random.nextDouble() * (bottom - top) : bottom - (bottom - top) * (tries - 20) / 20.0;
      double t = (b.y - ty) / -dy;
      if (t <= 0)
        continue;
      double tx = b.x + dx * t;
      if (tx < high[0] || tx > high[1] - bw)
        continue;
      if (glowSpot(tx + bw / 2, ty + bh / 2) <= 1) {
        b.launchLength = t;
        return;
      }
      if (fallback < 0)
        fallback = t;
    }
    // 칸 안에 드는 데가 하나도 없으면 위 칸 밑변까지만 (거기서 돌아다니며 칸 · 빛 안으로 튕겨 든다)
    b.launchLength = fallback >= 0 ? fallback : Math.max(0, (b.y - bottom) / -dy);
  }

  /** 칸 안 아무 데나 — 빛 (타원) 안에 들 때까지 몇 번 다시 뽑는다 */
  private void scatter(Bubble b, double[] band) {
    double bw = b.width();
    double bh = b.height();
    for (int tries = 0; tries < 20; tries++) {
      b.x = band[0] + random.nextDouble() * Math.max(0, band[1] - band[0] - bw);
      b.y = band[2] + random.nextDouble() * Math.max(0, band[3] - band[2] - bh);
      if (glowSpot(b.x + bw / 2, b.y + bh / 2) <= 1)
        break;
    }
    keepInGlow(b, false);
  }

  /** 빛 타원 안에서 어디쯤인지 — 1 이하면 안 (가운데 0) */
  private double glowSpot(double cx, double cy) {
    double u = (cx - getWidth() / 2) / (getWidth() * GLOW_RADIUS);
    double v = (cy - getHeight() / 2) / (getHeight() * GLOW_RADIUS);
    return u * u + v * v;
  }

  /**
   * 빛은 타원이라 네모 칸의 귀퉁이는 비어 있다 — 풍선 가운데가 빛 타원 밖으로 나가면 테두리로 당겨 넣고,
   * reflect 면 타원 벽에 튕긴다
   */
  private void keepInGlow(Bubble b, boolean reflect) {
    double a = getWidth() * GLOW_RADIUS;
    double r = getHeight() * GLOW_RADIUS;
    if (a <= 0 || r <= 0)
      return;
    double bw = b.width();
    double bh = b.height();
    double u = (b.x + bw / 2 - getWidth() / 2) / a;
    double v = (b.y + bh / 2 - getHeight() / 2) / r;
    double k = u * u + v * v;
    if (k <= 1)
      return;
    double scale = 1 / Math.sqrt(k);
    b.x = getWidth() / 2 + u * scale * a - bw / 2;
    b.y = getHeight() / 2 + v * scale * r - bh / 2;
    if (!reflect)
      return;
    double nx = u / a;
    double ny = v / r;
    double len = Math.hypot(nx, ny);
    nx /= len;
    ny /= len;
    double dot = b.dx * nx + b.dy * ny;
    if (dot > 0) {
      b.dx -= 2 * dot * nx;
      b.dy -= 2 * dot * ny;
    }
  }

  /** 마우스가 가까울수록 느려진다 — 멀면 1, 바로 위면 MIN_FACTOR */
  private double mouseFactor(double cx, double cy) {
    if (mouse == null)
      return 1;
    double d = Math.hypot(mouse.getX() - cx, mouse.getY() - cy);
    return clamp(d / SLOW_RADIUS, MIN_FACTOR, 1);
  }

  /** 칸 벽에 닿으면 튕긴다. 칸이 풍선보다 작으면 위 · 왼쪽에 붙인다 */
  private static void bounce(Bubble b, double[] band, double bw, double bh) {
    double maxX = Math.max(band[0], band[1] - bw);
    double maxY = Math.max(band[2], band[3] - bh);
    if (b.x < band[0]) {
      b.x = band[0];
      b.dx = Math.abs(b.dx);
    } else if (b.x > maxX) {
      b.x = maxX;
      b.dx = -Math.abs(b.dx);
    }
    if (b.y < band[2]) {
      b.y = band[2];
      b.dy = Math.abs(b.dy);
    } else if (b.y > maxY) {
      b.y = maxY;
      b.dy = -Math.abs(b.dy);
    }
  }

  /** 같은 칸에서 돌아다니는 풍선끼리 겹치면 덜 겹친 쪽으로 밀어내고 서로 반대로 튕긴다 */
  private void collide(double[] band, boolean done) {
    List<Bubble> list = new ArrayList<>();
    for (Bubble b : bubbles.values()) {
      if (b.placed && b.state == State.WANDER && b.done == done)
        list.add(b);
    }
    for (int i = 0; i < list.size(); i++) {
      for (int j = i + 1; j < list.size(); j++) {
        Bubble a = list.get(i);
        Bubble b = list.get(j);
        double ox = Math.min(a.x + a.width(), b.x + b.width()) - Math.max(a.x, b.x);
        double oy = Math.min(a.y + a.height(), b.y + b.height()) - Math.max(a.y, b.y);
        if (ox <= 0 || oy <= 0)
          continue;
        boolean aFirst;
        if (ox < oy) {
          aFirst = a.x + a.width() / 2 < b.x + b.width() / 2;
          push(a, b, aFirst, ox, true);
        } else {
          aFirst = a.y + a.height() / 2 < b.y + b.height() / 2;
          push(a, b, aFirst, oy, false);
        }
        for (Bubble c : new Bubble[] { a, b }) {
          bounce(c, band, c.width(), c.height());
          c.place();
        }
      }
    }
  }

  /** first 쪽이 왼쪽 (위) — 반씩 비켜 서고 그 축으로는 서로 멀어지는 쪽으로 간다. 잡고 있는 건 안 민다 */
  private static void push(Bubble a, Bubble b, boolean aFirst, double overlap, boolean horizontal) {
    Bubble first = aFirst ? a : b;
    Bubble second = aFirst ? b : a;
    double share = first.dragging || second.dragging ? overlap : overlap / 2;
    if (!first.dragging) {
      if (horizontal) {
        first.x -= share;
        first.dx = -Math.abs(first.dx);
      } else {
        first.y -= share;
        first.dy = -Math.abs(first.dy);
      }
    }
    if (!second.dragging) {
      if (horizontal) {
        second.x += share;
        second.dx = Math.abs(second.dx);
      } else {
        second.y += share;
        second.dy = Math.abs(second.dy);
      }
    }
  }

  private static boolean within(Object target, Node box) {
    if (!(target instanceof Node node))
      return false;
    for (Node n = node; n != null; n = n.getParent()) {
      if (n == box)
        return true;
    }
    return false;
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }
}
