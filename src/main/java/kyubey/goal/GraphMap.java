package kyubey.goal;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.css.PseudoClass;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.text.TextAlignment;
import kyubey.ui.Theme;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 관계도 (GoalStore.GRAPH) 를 그린다. GoalMap 이 트리를 알아서 늘어놓는 것과 달리 칸 자리는 손으로 잡는다 —
 * 칸마다 x · y (가운데 자리, 중심 칸이 0, 0) 를 들고 있고, 끌면 옮겨져서 남는다.
 *
 * 판의 한가운데가 늘 0, 0 이다. 판 크기는 0, 0 에서 제일 먼 칸까지를 양쪽으로 똑같이 잡아서,
 * 칸을 끌어 판이 커져도 나머지 칸이 화면에서 튀지 않는다.
 *
 * 마우스: 클릭 고르기, 고른 칸을 또 누르면 메모 (GoalPane), 끌기 고르고 옮기기, Ctrl+끌기 다른 칸에 놓으면 잇기 ·
 * 빈 데 놓으면 그 자리에 이은 새 칸, 빈 데 누르기 고르기 풀기, 연결 선 클릭 그 연결 고르기, 오른쪽 클릭 그 연결 메뉴.
 * 칸과 연결은 한 번에 하나만 고른다.
 * 키: ←↑→↓ 그쪽에서 가장 가까운 칸으로 (연결로는 안 간다). 나머지는 GoalPane.graphKey 가 받는다.
 */
final class GraphMap extends ScrollPane {

  /** 판 가장자리 여백 */
  private static final double PAD = 30;
  /** 딱지 하나가 이보다 넓어지면 줄을 바꾼다 */
  private static final double CHIP_MAX = 110;
  /** 새 칸을 붙일 때 이만큼 떨어진 데부터 빈자리를 찾는다 */
  private static final double STEP = 90;
  /** 화살촉 크기 */
  private static final double ARROW = 8;
  /** 서로 반대로 가는 연결 두 줄은 이만큼 벌려 그린다 */
  private static final double TWIN_GAP = 4;

  private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
  /** Ctrl+끌기 하는 동안 마우스 밑에 있어서, 놓으면 이어질 칸 */
  private static final PseudoClass LINK_TARGET = PseudoClass.getPseudoClass("link-target");
  /** 고른 칸과 선으로 이어진 칸 */
  private static final PseudoClass LINKED = PseudoClass.getPseudoClass("linked");

  private final Board board = new Board();
  private final Function<GoalStore.Node, ContextMenu> menus;
  private final Function<GoalStore.Link, ContextMenu> linkMenus;
  /** 칸을 옮겼을 때 — GoalPane 이 저장한다 */
  private final Runnable onMoved;
  /** 고른 칸을 또 눌렀을 때 — 메모 창 */
  private final Consumer<GoalStore.Node> onOpen;
  /** Ctrl+끌기로 from 에서 to 로 이었을 때 */
  private final BiConsumer<GoalStore.Node, GoalStore.Node> onConnect;
  /** Ctrl+끌기로 from 을 빈 데 (0, 0 기준 자리) 에 놓았을 때 — 거기 이은 새 칸 */
  private final BiConsumer<GoalStore.Node, Point2D> onSprout;

  private GoalStore.Goal goal;
  private GoalStore.Node selected;
  /** 고른 연결. 칸을 고르면 풀린다 */
  private GoalStore.Link selectedLink;
  private ContextMenu shownMenu;
  private final Map<GoalStore.Node, Label> chips = new IdentityHashMap<>();
  private final List<Edge> edges = new ArrayList<>();
  /** Ctrl+끌기 하는 동안 마우스까지 그어 두는 선 */
  private final Line rubber = new Line();
  /** 판 위 마지막 마우스 자리 (0, 0 기준). 아무것도 안 고르고 N 이면 여기 따로 선 칸을 둔다. 아직 안 왔으면 null */
  private Point2D hover;

  /** 연결 하나 = 보이는 선 + 누르기 쉬운 굵은 투명 선 + (방향 있으면) 화살촉 */
  private record Edge(GoalStore.Link link, GoalStore.Node from, GoalStore.Node to, Line line, Line hit, Polygon arrow) {
  }

  GraphMap(Function<GoalStore.Node, ContextMenu> menus, Function<GoalStore.Link, ContextMenu> linkMenus,
      Runnable onMoved, Consumer<GoalStore.Node> onOpen, BiConsumer<GoalStore.Node, GoalStore.Node> onConnect,
      BiConsumer<GoalStore.Node, Point2D> onSprout) {
    this.menus = menus;
    this.linkMenus = linkMenus;
    this.onMoved = onMoved;
    this.onOpen = onOpen;
    this.onConnect = onConnect;
    this.onSprout = onSprout;

    getStyleClass().add("goal-map");
    setContent(board);
    setFitToWidth(true);
    setFitToHeight(true);
    board.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    board.getStyleClass().add("goal-board");
    // 빈 데를 누르면 고른 칸을 푼다 — 그래야 N 이 따로 선 칸을 만든다
    board.setOnMousePressed(e -> {
      requestFocus();
      if (e.getButton() == MouseButton.PRIMARY)
        select(null);
    });
    // 판을 나가도 지우지 않는다 — 오른쪽 클릭 메뉴로 마우스가 빠져도 누른 자리에 칸이 서게
    board.setOnMouseMoved(e -> hover = toWorld(e.getX(), e.getY()));
    rubber.getStyleClass().add("graph-rubber");
    rubber.setMouseTransparent(true);
    rubber.setVisible(false);

    addEventFilter(KeyEvent.KEY_PRESSED, this::arrowKey);
  }

  /** 관계도를 새로 그린다. selectId 칸을 고른 채로 (없으면 아무것도 안 고른다). goal 이 null 이면 빈 판 */
  void show(GoalStore.Goal goal, String selectId) {
    // 다른 관계도의 마우스 자리는 뜻이 없다
    if (goal != this.goal)
      hover = null;
    this.goal = goal;
    chips.clear();
    edges.clear();
    board.getChildren().clear();
    selected = null;
    selectedLink = null;
    if (goal == null)
      return;
    int color = 0;
    for (GoalStore.Node node : goal.graphNodes()) {
      Label chip = chip(node);
      chip.getStyleClass().add(Theme.cycleClass(color++));
      chips.put(node, chip);
    }
    for (GoalStore.Link link : goal.links) {
      GoalStore.Node from = goal.find(link.from);
      GoalStore.Node to = goal.find(link.to);
      if (from == null || to == null || !chips.containsKey(from) || !chips.containsKey(to))
        continue;
      edges.add(edge(link, from, to));
    }
    // 선 → 딱지 차례로 쌓는다
    for (Edge edge : edges) {
      board.getChildren().add(edge.line);
      if (edge.arrow != null)
        board.getChildren().add(edge.arrow);
      board.getChildren().add(edge.hit);
    }
    board.getChildren().addAll(chips.values());
    board.getChildren().add(rubber);
    select(goal.find(selectId));
  }

  void refresh() {
    GoalStore.Link link = selectedLink;
    show(goal, selected == null ? null : selected.id);
    if (link != null)
      selectLink(link);
  }

  GoalStore.Node selected() {
    return selected;
  }

  GoalStore.Link selectedLink() {
    return selectedLink;
  }

  /** 연결을 고른다 — 고른 칸은 푼다. 판에 없는 연결이면 그대로 */
  void selectLink(GoalStore.Link link) {
    Edge edge = edgeOf(link);
    if (edge == null)
      return;
    select(null, false);
    selectedLink = link;
    markLink(edge, true);
  }

  private Edge edgeOf(GoalStore.Link link) {
    for (Edge edge : edges) {
      if (edge.link == link)
        return edge;
    }
    return null;
  }

  private void markLink(Edge edge, boolean on) {
    edge.line.pseudoClassStateChanged(SELECTED, on);
    if (edge.arrow != null)
      edge.arrow.pseudoClassStateChanged(SELECTED, on);
  }

  /** node 를 고른다. null 이면 고른 걸 푼다 */
  void select(GoalStore.Node node) {
    select(node, true);
  }

  /** reveal 이면 고른 칸이 화면 밖일 때 스크롤을 끌어온다 */
  private void select(GoalStore.Node node, boolean reveal) {
    if (node != null && !chips.containsKey(node))
      return;
    Edge picked = edgeOf(selectedLink);
    if (picked != null)
      markLink(picked, false);
    selectedLink = null;
    if (selected != null && chips.containsKey(selected))
      chips.get(selected).pseudoClassStateChanged(SELECTED, false);
    selected = node;
    for (Edge edge : edges) {
      chips.get(edge.from).pseudoClassStateChanged(LINKED, false);
      chips.get(edge.to).pseudoClassStateChanged(LINKED, false);
    }
    for (Edge edge : edges) {
      boolean touches = node != null && (edge.from == node || edge.to == node);
      edge.line.pseudoClassStateChanged(LINKED, touches);
      if (edge.from == node)
        chips.get(edge.to).pseudoClassStateChanged(LINKED, true);
      else if (edge.to == node)
        chips.get(edge.from).pseudoClassStateChanged(LINKED, true);
    }
    if (node != null) {
      chips.get(node).pseudoClassStateChanged(SELECTED, true);
      if (reveal)
        reveal(node);
    }
  }

  /** 입력칸 ↑↓ 가 옮겨 다니는 차례 — 중심 칸, 그다음 만든 차례 */
  List<GoalStore.Node> order() {
    return goal == null ? List.of() : goal.graphNodes();
  }

  /**
   * 새 칸을 둘 빈자리. near 가 있으면 그 칸 둘레 (중심 칸 반대쪽부터), 없으면 마우스 자리나 화면 가운데 둘레.
   */
  Point2D freeSpot(GoalStore.Node near) {
    double cx;
    double cy;
    double start;
    if (near != null) {
      cx = near.x == null ? 0 : near.x;
      cy = near.y == null ? 0 : near.y;
      // 중심 칸에서 멀어지는 쪽부터 — 가지가 바깥으로 뻗게
      start = cx == 0 && cy == 0 ? -Math.PI / 2 : Math.atan2(cy, cx);
    } else {
      return freeSpotAround(hover != null ? hover : viewCenter());
    }
    return spiral(cx, cy, start);
  }

  /** at (0, 0 기준) 이 비었으면 거기, 아니면 그 둘레에서 가장 가까운 빈자리 */
  Point2D freeSpotAround(Point2D at) {
    if (isFree(at.getX(), at.getY()))
      return at;
    return spiral(at.getX(), at.getY(), 0);
  }

  /** (cx, cy) 둘레를 start 방향부터 고리처럼 넓혀 가며 빈자리를 찾는다 */
  private Point2D spiral(double cx, double cy, double start) {
    for (int ring = 1; ring < 12; ring++) {
      int slots = 8 * ring;
      for (int k = 0; k < slots; k++) {
        // 시작 방향에서 양쪽으로 번갈아 벌려 나간다
        int side = (k + 1) / 2 * (k % 2 == 0 ? 1 : -1);
        double angle = start + side * (2 * Math.PI / slots);
        double x = cx + Math.cos(angle) * STEP * ring;
        double y = cy + Math.sin(angle) * STEP * ring * 0.7;
        if (isFree(x, y))
          return new Point2D(x, y);
      }
    }
    return new Point2D(cx + STEP, cy);
  }

  /** 딱지가 겹치지 않을 만큼 다른 칸들과 떨어져 있는지 */
  private boolean isFree(double x, double y) {
    if (goal == null)
      return true;
    for (GoalStore.Node node : goal.graphNodes()) {
      double dx = (x - (node.x == null ? 0 : node.x)) / (CHIP_MAX * 0.9);
      double dy = (y - (node.y == null ? 0 : node.y)) / 50;
      if (dx * dx + dy * dy < 1)
        return false;
    }
    return true;
  }

  /** 지금 보이는 화면 가운데 (0, 0 기준) */
  private Point2D viewCenter() {
    Bounds view = getViewportBounds();
    double extraW = Math.max(0, board.getWidth() - view.getWidth());
    double extraH = Math.max(0, board.getHeight() - view.getHeight());
    double left = getHvalue() * extraW;
    double top = getVvalue() * extraH;
    return toWorld(left + Math.min(view.getWidth(), board.getWidth()) / 2,
        top + Math.min(view.getHeight(), board.getHeight()) / 2);
  }

  private Point2D toWorld(double boardX, double boardY) {
    return new Point2D(boardX - board.getWidth() / 2, boardY - board.getHeight() / 2);
  }

  // ── 짓기 ──

  private Label chip(GoalStore.Node node) {
    Label chip = new Label(node.title);
    chip.getStyleClass().add("goal-chip");
    if (node == goal.root)
      chip.getStyleClass().add("goal-chip-root");
    if (node.done)
      chip.getStyleClass().add("goal-chip-done");
    chip.setMaxWidth(CHIP_MAX);
    chip.setWrapText(true);
    chip.setTextAlignment(TextAlignment.CENTER);
    chip.setAlignment(Pos.CENTER);
    boolean noted = node.note != null && !node.note.isBlank();
    chip.setTooltip(new Tooltip(noted ? node.title + "\n\n" + node.note : node.title));
    chip.setOnContextMenuRequested(e -> {
      e.consume();
      showMenu(menus.apply(node), chip, e.getScreenX(), e.getScreenY());
    });

    // 끌기: 그냥이면 옮기기, Ctrl 이면 다른 칸에 놓아 잇기 · 빈 데 놓아 이은 새 칸. 어느 쪽이든 누르자마자 고른다
    double[] press = new double[4];
    boolean[] linking = { false };
    // 누르기 전에 벌써 고른 칸이었는지 — 그래야 또 누른 것만 메모를 연다
    boolean[] wasSelected = { false };
    // Ctrl+끌기 하는 동안 마우스 밑의 칸 — 놓기 전에 어디로 이어질지 보이게 칠해 둔다
    GoalStore.Node[] aimed = { null };
    chip.setOnMousePressed(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      e.consume();
      requestFocus();
      press[0] = e.getSceneX();
      press[1] = e.getSceneY();
      press[2] = node.x == null ? 0 : node.x;
      press[3] = node.y == null ? 0 : node.y;
      linking[0] = e.isShortcutDown();
      wasSelected[0] = selected == node;
      // 마우스 밑의 칸이라 이미 보인다 — 스크롤을 끌어오면 끄는 칸이 마우스에서 미끄러진다
      select(node, false);
      if (linking[0]) {
        double cx = chip.getLayoutX() + chip.getWidth() / 2;
        double cy = chip.getLayoutY() + chip.getHeight() / 2;
        rubber.setStartX(cx);
        rubber.setStartY(cy);
        rubber.setEndX(cx);
        rubber.setEndY(cy);
        rubber.setVisible(true);
        rubber.toFront();
      }
    });
    chip.setOnMouseDragged(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      e.consume();
      if (linking[0]) {
        Point2D at = board.sceneToLocal(e.getSceneX(), e.getSceneY());
        rubber.setEndX(at.getX());
        rubber.setEndY(at.getY());
        GoalStore.Node over = chipAt(at);
        aim(aimed, over == node ? null : over);
        return;
      }
      node.x = press[2] + (e.getSceneX() - press[0]);
      node.y = press[3] + (e.getSceneY() - press[1]);
      board.requestLayout();
    });
    chip.setOnMouseReleased(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      e.consume();
      if (linking[0]) {
        linking[0] = false;
        rubber.setVisible(false);
        aim(aimed, null);
        Point2D at = board.sceneToLocal(e.getSceneX(), e.getSceneY());
        GoalStore.Node target = chipAt(at);
        if (target != null && target != node) {
          onConnect.accept(node, target);
        } else if (target == null && !e.isStillSincePress()) {
          // 이름 받는 창이 뜨니까 이 마우스 일이 다 끝난 뒤에
          Point2D spot = toWorld(at.getX(), at.getY());
          Platform.runLater(() -> onSprout.accept(node, spot));
        }
        return;
      }
      if (!e.isStillSincePress())
        onMoved.run();
    });
    chip.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress() || e.isShortcutDown())
        return;
      e.consume();
      // 안 고른 칸은 누를 때 고르기만 했고, 고른 칸을 또 누르면 메모
      if (wasSelected[0] && selected == node)
        onOpen.accept(node);
    });
    return chip;
  }

  private Edge edge(GoalStore.Link link, GoalStore.Node from, GoalStore.Node to) {
    Line line = new Line();
    line.getStyleClass().add("goal-edge");
    line.setMouseTransparent(true);
    Line hit = new Line();
    hit.getStyleClass().add("graph-edge-hit");
    hit.setOnContextMenuRequested(e -> {
      e.consume();
      selectLink(link);
      showMenu(linkMenus.apply(link), hit, e.getScreenX(), e.getScreenY());
    });
    hit.setOnMousePressed(e -> {
      e.consume();
      requestFocus();
      if (e.getButton() == MouseButton.PRIMARY)
        selectLink(link);
    });
    Polygon arrow = null;
    if (goal.directed) {
      arrow = new Polygon();
      arrow.getStyleClass().add("graph-arrow");
      arrow.setMouseTransparent(true);
    }
    return new Edge(link, from, to, line, hit, arrow);
  }

  private void showMenu(ContextMenu menu, javafx.scene.Node anchor, double x, double y) {
    if (shownMenu != null)
      shownMenu.hide();
    shownMenu = menu;
    if (menu != null)
      menu.show(anchor, x, y);
  }

  /** Ctrl+끌기로 겨눈 칸을 aimed[0] 에서 to 로 바꿔 칠한다. null 이면 칠한 걸 지운다 */
  private void aim(GoalStore.Node[] aimed, GoalStore.Node to) {
    if (aimed[0] == to)
      return;
    if (aimed[0] != null && chips.containsKey(aimed[0]))
      chips.get(aimed[0]).pseudoClassStateChanged(LINK_TARGET, false);
    aimed[0] = to;
    if (to != null)
      chips.get(to).pseudoClassStateChanged(LINK_TARGET, true);
  }

  /** 판 위 이 자리에 있는 칸. 없으면 null */
  private GoalStore.Node chipAt(Point2D at) {
    for (Map.Entry<GoalStore.Node, Label> entry : chips.entrySet()) {
      if (entry.getValue().getBoundsInParent().contains(at))
        return entry.getKey();
    }
    return null;
  }

  // ── 키 ──

  /** 화살표 쪽으로 가장 가까운 칸을 고른다. 아무것도 안 골랐으면 중심 칸부터 */
  private void arrowKey(KeyEvent e) {
    if (goal == null || e.isAltDown())
      return;
    KeyCode code = e.getCode();
    double dirX = code == KeyCode.LEFT ? -1 : code == KeyCode.RIGHT ? 1 : 0;
    double dirY = code == KeyCode.UP ? -1 : code == KeyCode.DOWN ? 1 : 0;
    if (dirX == 0 && dirY == 0)
      return;
    e.consume();
    if (selected == null) {
      select(goal.root);
      return;
    }
    double sx = selected.x == null ? 0 : selected.x;
    double sy = selected.y == null ? 0 : selected.y;
    GoalStore.Node best = null;
    double bestScore = Double.MAX_VALUE;
    for (GoalStore.Node node : goal.graphNodes()) {
      if (node == selected)
        continue;
      double dx = (node.x == null ? 0 : node.x) - sx;
      double dy = (node.y == null ? 0 : node.y) - sy;
      double along = dx * dirX + dy * dirY;
      if (along <= 1)
        continue;
      // 그쪽으로 곧게 놓인 칸일수록 먼저
      double across = Math.abs(dx * dirY) + Math.abs(dy * dirX);
      double score = along + across * 2;
      if (score < bestScore) {
        bestScore = score;
        best = node;
      }
    }
    if (best != null)
      select(best);
  }

  // ── 자리 ──

  /** 고른 칸이 화면 밖이면 스크롤을 끌어온다 */
  private void reveal(GoalStore.Node node) {
    if (getScene() == null)
      return;
    applyCss();
    layout();
    Label chip = chips.get(node);
    Bounds view = getViewportBounds();
    double extraW = board.getWidth() - view.getWidth();
    double extraH = board.getHeight() - view.getHeight();
    double margin = 10;
    if (extraW > 0) {
      double left = getHvalue() * extraW;
      if (chip.getLayoutX() - margin < left)
        left = chip.getLayoutX() - margin;
      else if (chip.getLayoutX() + chip.getWidth() + margin > left + view.getWidth())
        left = chip.getLayoutX() + chip.getWidth() + margin - view.getWidth();
      setHvalue(Math.max(0, Math.min(1, left / extraW)));
    }
    if (extraH > 0) {
      double top = getVvalue() * extraH;
      if (chip.getLayoutY() - margin < top)
        top = chip.getLayoutY() - margin;
      else if (chip.getLayoutY() + chip.getHeight() + margin > top + view.getHeight())
        top = chip.getLayoutY() + chip.getHeight() + margin - view.getHeight();
      setVvalue(Math.max(0, Math.min(1, top / extraH)));
    }
  }

  /** 딱지와 선을 얹는 판. 한가운데가 0, 0 */
  private final class Board extends Pane {

    private double chipW(Label chip) {
      return Math.min(CHIP_MAX, chip.prefWidth(-1));
    }

    private double chipH(Label chip) {
      return chip.prefHeight(chipW(chip));
    }

    /** 0, 0 에서 가장 먼 딱지 끝까지 — xy[0] 가로, xy[1] 세로 */
    private double[] reach() {
      double rx = 0;
      double ry = 0;
      for (Map.Entry<GoalStore.Node, Label> entry : chips.entrySet()) {
        GoalStore.Node node = entry.getKey();
        Label chip = entry.getValue();
        rx = Math.max(rx, Math.abs(node.x == null ? 0 : node.x) + chipW(chip) / 2);
        ry = Math.max(ry, Math.abs(node.y == null ? 0 : node.y) + chipH(chip) / 2);
      }
      return new double[] { rx, ry };
    }

    @Override
    protected double computePrefWidth(double height) {
      return reach()[0] * 2 + PAD * 2;
    }

    @Override
    protected double computePrefHeight(double width) {
      return reach()[1] * 2 + PAD * 2;
    }

    @Override
    protected void layoutChildren() {
      if (goal == null)
        return;
      double ox = getWidth() / 2;
      double oy = getHeight() / 2;
      for (Map.Entry<GoalStore.Node, Label> entry : chips.entrySet()) {
        GoalStore.Node node = entry.getKey();
        Label chip = entry.getValue();
        double w = chipW(chip);
        double h = chipH(chip);
        double x = ox + (node.x == null ? 0 : node.x) - w / 2;
        double y = oy + (node.y == null ? 0 : node.y) - h / 2;
        chip.resizeRelocate(snapPositionX(x), snapPositionY(y), snapSizeX(w), snapSizeY(h));
      }
      for (Edge edge : edges)
        placeEdge(edge);
    }

    /** 칸 테두리에서 칸 테두리까지. 반대로 가는 연결이 같이 있으면 옆으로 조금 벌린다 */
    private void placeEdge(Edge edge) {
      Label from = chips.get(edge.from);
      Label to = chips.get(edge.to);
      double fx = from.getLayoutX() + from.getWidth() / 2;
      double fy = from.getLayoutY() + from.getHeight() / 2;
      double tx = to.getLayoutX() + to.getWidth() / 2;
      double ty = to.getLayoutY() + to.getHeight() / 2;
      double len = Math.hypot(tx - fx, ty - fy);
      if (len < 1)
        len = 1;
      double ux = (tx - fx) / len;
      double uy = (ty - fy) / len;
      if (goal.directed && goal.link(edge.to, edge.from) != null) {
        fx += -uy * TWIN_GAP;
        fy += ux * TWIN_GAP;
        tx += -uy * TWIN_GAP;
        ty += ux * TWIN_GAP;
      }
      double[] start = border(fx, fy, ux, uy, from);
      double[] end = border(tx, ty, -ux, -uy, to);
      for (Line line : List.of(edge.line, edge.hit)) {
        line.setStartX(start[0]);
        line.setStartY(start[1]);
        line.setEndX(end[0]);
        line.setEndY(end[1]);
      }
      if (edge.arrow != null) {
        double bx = end[0] - ux * ARROW;
        double by = end[1] - uy * ARROW;
        edge.arrow.getPoints().setAll(end[0], end[1],
            bx - uy * ARROW * 0.55, by + ux * ARROW * 0.55,
            bx + uy * ARROW * 0.55, by - ux * ARROW * 0.55);
      }
    }

    /** 가운데 (cx, cy) 에서 (ux, uy) 쪽으로 나가다 딱지 테두리에 닿는 자리 */
    private double[] border(double cx, double cy, double ux, double uy, Label chip) {
      double hw = chip.getWidth() / 2 + 2;
      double hh = chip.getHeight() / 2 + 2;
      double t = Math.min(Math.abs(ux) < 1e-6 ? Double.MAX_VALUE : hw / Math.abs(ux),
          Math.abs(uy) < 1e-6 ? Double.MAX_VALUE : hh / Math.abs(uy));
      return new double[] { cx + ux * t, cy + uy * t };
    }
  }
}
