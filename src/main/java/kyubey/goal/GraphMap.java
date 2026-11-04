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
 *
 * 루트부터 보기 (Goal.layered): 손으로 잡은 자리는 그대로 두고, 보이는 자리만 따로 잡는다 — 중심 칸을 맨 왼쪽 가운데,
 * 중심 칸에서 연결 몇 번 만에 닿는지 같은 칸끼리 한 세로줄로 오른쪽으로 늘어놓는다 — 방향 있는 관계도면 화살표를 따라서만
 * (방향 안 정한 연결은 양쪽으로), 없으면 어느 쪽으로든 건너간다.
 * 줄 안 차례는 이웃 줄 칸들 평균 자리로 몇 번 오가며 맞춰 선이 덜 엇갈리게 (layers). 안 닿는 칸은 맨 오른쪽 따로 한 줄.
 * 줄 안 칸들은 좌우 갈래 (lanes) 로 위아래 번갈아 엇갈려 세워 딱지와 선이 덜 포개지게 — 같은 줄 칸끼리 이어져 있으면
 * 그 둘은 꼭 다른 갈래라 선이 세로로 곧게 서지 않는다. 갈래만큼 줄이 넓어지고 줄 사이 틈은 그대로라 깊이는 여전히 갈린다.
 * 이때는 끌어도 안 옮겨진다 (Ctrl+끌기 잇기는 된다).
 */
final class GraphMap extends ScrollPane {

  /** 판 가장자리 여백 */
  private static final double PAD = 30;
  /** 끝 칸 바깥에 더 두는 여백 — 전체화면에서 끝까지 스크롤해도 칸이 가장자리에 붙지 않게 (setRim) */
  private double rim;
  /** 딱지 하나가 이보다 넓어지면 줄을 바꾼다 */
  private static final double CHIP_MAX = 110;
  /** 새 칸을 붙일 때 이만큼 떨어진 데부터 빈자리를 찾는다 */
  private static final double STEP = 90;
  /** 화살촉 크기 */
  private static final double ARROW = 8;
  /** 루트부터 볼 때 세로줄 사이 · 줄 안 칸 사이 */
  private static final double COL_GAP = 70;
  private static final double ROW_GAP = 16;
  /** 루트부터 볼 때 엇갈림 줄이기를 오가는 횟수 */
  private static final int SWEEPS = 12;
  /** 같은 줄 안 갈래 사이 가로 거리 — 그 줄 가장 넓은 딱지의 이만큼, 적어도 LANE_MIN */
  private static final double LANE_RATIO = 0.6;
  private static final double LANE_MIN = 40;

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
  /** 루트부터 볼 때 왼쪽부터 세로줄들 (줄 안은 위에서부터). 자유 그래프면 null */
  private List<List<GoalStore.Node>> columns;
  /** columns 가 있을 때 맨 오른쪽 줄이 중심 칸에서 안 닿는 칸들인지 — 한 칸 더 띄운다 */
  private boolean looseColumn;
  /** 줄 안에서 칸이 서는 갈래 (0 부터, 왼쪽부터) */
  private final Map<GoalStore.Node, Integer> lanes = new IdentityHashMap<>();
  /** 줄마다 갈래 수 (columns 와 같은 차례) */
  private final List<Integer> laneCounts = new ArrayList<>();
  /** 루트부터 볼 때 보이는 자리 (0, 0 기준 가운데) — Board 가 딱지 크기를 알고 잡는다 */
  private final Map<GoalStore.Node, Point2D> placed = new IdentityHashMap<>();

  /** 연결 하나 = 보이는 선 + 누르기 쉬운 굵은 투명 선 + 화살촉 (to 쪽 · 양방향이면 from 쪽도. 없으면 null) */
  private record Edge(GoalStore.Link link, GoalStore.Node from, GoalStore.Node to, Line line, Line hit, Polygon arrow,
      Polygon back) {
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
    // 루트부터 볼 땐 보이는 자리가 손으로 잡은 자리와 따로 논다 — 안 잡는다
    board.setOnMouseMoved(e -> hover = columns == null ? toWorld(e.getX(), e.getY()) : null);
    rubber.getStyleClass().add("graph-rubber");
    rubber.setMouseTransparent(true);
    rubber.setVisible(false);

    addEventFilter(KeyEvent.KEY_PRESSED, this::arrowKey);
  }

  /** 끝 칸 바깥 여백을 바꾼다 — 판이 가운데를 기준으로 그려져 상하좌우에 같이 붙는다 */
  void setRim(double rim) {
    if (this.rim == rim)
      return;
    this.rim = rim;
    board.requestLayout();
  }

  /** 관계도를 새로 그린다. selectId 칸을 고른 채로 (없으면 아무것도 안 고른다). goal 이 null 이면 빈 판 */
  void show(GoalStore.Goal goal, String selectId) {
    // 다른 관계도의 마우스 자리는 뜻이 없다
    if (goal != this.goal)
      hover = null;
    this.goal = goal;
    chips.clear();
    edges.clear();
    placed.clear();
    columns = null;
    lanes.clear();
    laneCounts.clear();
    board.getChildren().clear();
    selected = null;
    selectedLink = null;
    if (goal == null)
      return;
    if (goal.layered) {
      columns = layers();
      hover = null;
    }
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
      if (edge.back != null)
        board.getChildren().add(edge.back);
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
    if (edge.back != null)
      edge.back.pseudoClassStateChanged(SELECTED, on);
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
    } else if (columns != null) {
      // 루트부터 볼 땐 마우스 · 화면 자리가 손으로 잡은 자리와 따로 논다 — 중심 칸 둘레에
      return freeSpotAround(Point2D.ZERO);
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

  /** 보이는 자리 (0, 0 기준 가운데) — 루트부터 볼 땐 늘어놓은 자리, 아니면 손으로 잡은 자리 */
  private double px(GoalStore.Node node) {
    Point2D at = placed.get(node);
    return at != null ? at.getX() : node.x == null ? 0 : node.x;
  }

  private double py(GoalStore.Node node) {
    Point2D at = placed.get(node);
    return at != null ? at.getY() : node.y == null ? 0 : node.y;
  }

  // ── 루트부터 ──

  /**
   * 중심 칸에서 연결 몇 번 만에 닿는지로 세로줄을 나누고 (넓이 우선, 방향 있는 관계도면 화살표를 따라서만), 줄 안 차례를 맞춘다 —
   * 왼쪽→오른쪽, 오른쪽→왼쪽으로 번갈아 각 칸을 이웃 줄에 이어진 칸들의 평균 자리 순으로 늘어놓고,
   * 그중 선이 가장 덜 엇갈린 차례를 남긴다. 안 닿는 칸들은 맨 오른쪽 따로 한 줄 (looseColumn).
   */
  private List<List<GoalStore.Node>> layers() {
    List<GoalStore.Node> all = goal.graphNodes();
    // near: 방향 없이 이웃 (차례 맞추기용), ahead: 건너갈 수 있는 쪽만 (거리용)
    Map<GoalStore.Node, List<GoalStore.Node>> near = new IdentityHashMap<>();
    Map<GoalStore.Node, List<GoalStore.Node>> ahead = new IdentityHashMap<>();
    for (GoalStore.Node node : all) {
      near.put(node, new ArrayList<>());
      ahead.put(node, new ArrayList<>());
    }
    for (GoalStore.Link link : goal.links) {
      GoalStore.Node from = goal.find(link.from);
      GoalStore.Node to = goal.find(link.to);
      if (from == null || to == null || from == to || !near.containsKey(from) || !near.containsKey(to))
        continue;
      if (!near.get(from).contains(to)) {
        near.get(from).add(to);
        near.get(to).add(from);
      }
      if (!goal.directed || link.goes(from.id, to.id))
        ahead.get(from).add(to);
      if (!goal.directed || link.goes(to.id, from.id))
        ahead.get(to).add(from);
    }

    Map<GoalStore.Node, Integer> depth = new IdentityHashMap<>();
    List<List<GoalStore.Node>> cols = new ArrayList<>();
    List<GoalStore.Node> queue = new ArrayList<>(List.of(goal.root));
    depth.put(goal.root, 0);
    for (int i = 0; i < queue.size(); i++) {
      GoalStore.Node node = queue.get(i);
      int d = depth.get(node);
      if (cols.size() <= d)
        cols.add(new ArrayList<>());
      cols.get(d).add(node);
      for (GoalStore.Node next : ahead.get(node)) {
        if (!depth.containsKey(next)) {
          depth.put(next, d + 1);
          queue.add(next);
        }
      }
    }

    List<List<GoalStore.Node>> best = copy(cols);
    int bestCross = crossings(cols, near);
    for (int sweep = 0; sweep < SWEEPS && bestCross > 0; sweep++) {
      if (sweep % 2 == 0) {
        for (int c = 1; c < cols.size(); c++)
          sortBy(cols.get(c), cols.get(c - 1), near);
      } else {
        for (int c = cols.size() - 2; c >= 0; c--)
          sortBy(cols.get(c), cols.get(c + 1), near);
      }
      int cross = crossings(cols, near);
      if (cross < bestCross) {
        bestCross = cross;
        best = copy(cols);
      }
    }

    List<GoalStore.Node> loose = new ArrayList<>();
    for (GoalStore.Node node : all) {
      if (!depth.containsKey(node))
        loose.add(node);
    }
    looseColumn = !loose.isEmpty();
    if (looseColumn)
      best.add(loose);
    for (List<GoalStore.Node> col : best)
      laneCounts.add(assignLanes(col, near));
    return best;
  }

  /**
   * 줄 안 칸들에 갈래를 준다 — 위에서부터, 이미 갈래를 받은 같은 줄 이웃과는 꼭 다르게 (줄 안 선이 세로로 곧게 서지 않게),
   * 되도록 바로 위 칸과도 다르게 (지그재그). 갈래 수를 돌려준다 (칸이 하나면 1)
   */
  private int assignLanes(List<GoalStore.Node> col, Map<GoalStore.Node, List<GoalStore.Node>> near) {
    int count = col.size() > 1 ? 2 : 1;
    int prev = -1;
    for (GoalStore.Node node : col) {
      List<Integer> used = new ArrayList<>();
      for (GoalStore.Node other : near.get(node)) {
        if (col.contains(other) && lanes.containsKey(other))
          used.add(lanes.get(other));
      }
      int lane = -1;
      // 지금 있는 갈래 안에서 위 칸과 다른 데부터 — 없으면 이웃과만 안 겹치게 (모자라면 갈래를 늘린다)
      for (int k = 0; k < count && lane < 0; k++) {
        if (k != prev && !used.contains(k))
          lane = k;
      }
      if (lane < 0) {
        lane = 0;
        while (used.contains(lane))
          lane++;
      }
      lanes.put(node, lane);
      count = Math.max(count, lane + 1);
      prev = lane;
    }
    return count;
  }

  /** col 을 ref 줄에 이어진 칸들의 평균 자리 순으로. ref 에 이어진 데가 없는 칸은 지금 자리를 그대로 쓴다 */
  private static void sortBy(List<GoalStore.Node> col, List<GoalStore.Node> ref,
      Map<GoalStore.Node, List<GoalStore.Node>> near) {
    Map<GoalStore.Node, Double> key = new IdentityHashMap<>();
    for (int i = 0; i < col.size(); i++) {
      GoalStore.Node node = col.get(i);
      double sum = 0;
      int count = 0;
      for (GoalStore.Node other : near.get(node)) {
        int at = ref.indexOf(other);
        if (at >= 0) {
          sum += at;
          count++;
        }
      }
      // 줄 길이가 달라도 견주게 0 ~ 1 로 맞춘다
      key.put(node, count > 0 ? sum / count / Math.max(1, ref.size() - 1) : (double) i / Math.max(1, col.size() - 1));
    }
    col.sort((a, b) -> Double.compare(key.get(a), key.get(b)));
  }

  /** 이웃한 두 줄 사이 선들이 엇갈리는 수의 합 */
  private static int crossings(List<List<GoalStore.Node>> cols, Map<GoalStore.Node, List<GoalStore.Node>> near) {
    int total = 0;
    for (int c = 0; c + 1 < cols.size(); c++) {
      List<GoalStore.Node> left = cols.get(c);
      List<GoalStore.Node> right = cols.get(c + 1);
      List<int[]> pairs = new ArrayList<>();
      for (int i = 0; i < left.size(); i++) {
        for (GoalStore.Node other : near.get(left.get(i))) {
          int j = right.indexOf(other);
          if (j >= 0)
            pairs.add(new int[] { i, j });
        }
      }
      for (int a = 0; a < pairs.size(); a++) {
        for (int b = a + 1; b < pairs.size(); b++) {
          if ((long) (pairs.get(a)[0] - pairs.get(b)[0]) * (pairs.get(a)[1] - pairs.get(b)[1]) < 0)
            total++;
        }
      }
    }
    return total;
  }

  private static List<List<GoalStore.Node>> copy(List<List<GoalStore.Node>> cols) {
    List<List<GoalStore.Node>> out = new ArrayList<>();
    for (List<GoalStore.Node> col : cols)
      out.add(new ArrayList<>(col));
    return out;
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
    // 마우스를 올려두면 이 칸이 관계도 어디쯤인지 — 띄울 때마다 새로 센다
    Tooltip tip = new Tooltip(node.title);
    tip.setOnShowing(e -> tip.setText(about(node)));
    chip.setTooltip(tip);
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
      // 루트부터 볼 땐 자리를 판이 잡는다
      if (columns != null)
        return;
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
          // 이름 받는 창이 뜨니까 이 마우스 일이 다 끝난 뒤에. 루트부터 볼 땐 놓은 자리가 손으로 잡는 자리와 따로 놀아 from 둘레에
          Point2D spot = columns != null ? freeSpot(node) : toWorld(at.getX(), at.getY());
          Platform.runLater(() -> onSprout.accept(node, spot));
        }
        return;
      }
      if (!e.isStillSincePress() && columns == null)
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

  /** 메모 앞부분을 이만큼까지 */
  private static final int NOTE_PEEK = 80;

  /**
   * 칸에 마우스를 올려두면 뜨는 글 — 이름, 이어진 칸 수 (양방향 연결이 있어 연결 수와 다를 때만), 연결 수 (양방향은 둘로 센다)
   * 와 뒤로 · 앞으로 나가는 연결 수 (뒤로 없으면 말단, 중심 칸이면 앞 대신 루트), 깊이 (중심 칸에서 안 닿으면 고립), 메모 앞부분.
   * 방향 있는 관계도면 뒤 · 앞은 화살표대로 (방향 안 정한 연결은 양쪽 다), 없으면 깊이가 더 깊은 쪽이 뒤
   */
  private String about(GoalStore.Node node) {
    Map<GoalStore.Node, Integer> depth = new IdentityHashMap<>();
    Map<GoalStore.Node, Boolean> loose = new IdentityHashMap<>();
    depths(depth, loose);
    int d = depth.getOrDefault(node, 0);
    List<GoalStore.Node> near = new ArrayList<>();
    int ends = 0;
    int back = 0;
    int front = 0;
    boolean both = false;
    for (GoalStore.Link link : goal.links) {
      if (!link.touches(node.id))
        continue;
      GoalStore.Node other = goal.find(node.id.equals(link.from) ? link.to : link.from);
      if (other == null || other == node)
        continue;
      if (!near.contains(other))
        near.add(other);
      if (goal.directed) {
        ends += link.both() ? 2 : 1;
        both |= link.both();
        if (link.goes(node.id, other.id))
          back++;
        if (link.goes(other.id, node.id))
          front++;
      } else {
        ends++;
        int o = depth.getOrDefault(other, 0);
        if (o > d)
          back++;
        else if (o < d)
          front++;
      }
    }

    StringBuilder out = new StringBuilder(node.title);
    if (both)
      out.append("\n이어진 칸 ").append(near.size());
    out.append("\n연결 ").append(ends)
        .append(" (").append(back == 0 ? "말단" : "뒤로 " + back)
        .append(" · ").append(node == goal.root ? "루트" : "앞으로 " + front).append(')');
    out.append("\n깊이 ").append(d);
    if (loose.containsKey(node))
      out.append(" (고립)");
    if (node.note != null && !node.note.isBlank()) {
      String note = node.note.strip().replaceAll("\\s+", " ");
      out.append("\n\n").append(note.length() > NOTE_PEEK ? note.substring(0, NOTE_PEEK) + "…" : note);
    }
    return out.toString();
  }

  /**
   * 칸마다 깊이를 depth 에 — 중심 칸에서 연결 몇 번 만에 닿는지 (layers 처럼 방향 있는 관계도면 화살표를 따라서만).
   * 안 닿는 칸들은 loose 에 담고, 그 칸들끼리 들어오는 연결이 없는 칸 (없으면 먼저 만든 칸) 부터 따로 잰다
   */
  private void depths(Map<GoalStore.Node, Integer> depth, Map<GoalStore.Node, Boolean> loose) {
    List<GoalStore.Node> sources = List.of(goal.root);
    while (true) {
      List<GoalStore.Node> queue = new ArrayList<>(sources);
      for (GoalStore.Node source : sources)
        depth.put(source, 0);
      for (int i = 0; i < queue.size(); i++) {
        GoalStore.Node at = queue.get(i);
        for (GoalStore.Link link : goal.links) {
          GoalStore.Node next = across(link, at);
          if (next != null && !depth.containsKey(next)) {
            depth.put(next, depth.get(at) + 1);
            queue.add(next);
          }
        }
      }
      List<GoalStore.Node> left = new ArrayList<>();
      for (GoalStore.Node node : goal.graphNodes()) {
        if (!depth.containsKey(node))
          left.add(node);
      }
      if (left.isEmpty())
        return;
      for (GoalStore.Node node : left)
        loose.put(node, true);
      sources = new ArrayList<>();
      for (GoalStore.Node node : left) {
        boolean entered = false;
        for (GoalStore.Node other : left) {
          for (GoalStore.Link link : goal.links) {
            if (across(link, other) == node)
              entered = true;
          }
        }
        if (!entered)
          sources.add(node);
      }
      if (sources.isEmpty())
        sources.add(left.get(0));
    }
  }

  /** at 에서 이 연결로 건너간 칸 — 방향 있는 관계도면 화살표를 거스르지 않을 때만. 못 가면 null */
  private GoalStore.Node across(GoalStore.Link link, GoalStore.Node at) {
    if (!link.touches(at.id))
      return null;
    GoalStore.Node other = goal.find(at.id.equals(link.from) ? link.to : link.from);
    if (other == null || other == at)
      return null;
    return !goal.directed || link.goes(at.id, other.id) ? other : null;
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
    // 방향 안 정한 연결은 방향 있는 관계도에서도 화살촉 없이
    boolean pointed = goal.directed && (link.one() || link.both());
    return new Edge(link, from, to, line, hit, pointed ? arrow() : null,
        goal.directed && link.both() ? arrow() : null);
  }

  private static Polygon arrow() {
    Polygon arrow = new Polygon();
    arrow.getStyleClass().add("graph-arrow");
    arrow.setMouseTransparent(true);
    return arrow;
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
    double sx = px(selected);
    double sy = py(selected);
    GoalStore.Node best = null;
    double bestScore = Double.MAX_VALUE;
    for (GoalStore.Node node : goal.graphNodes()) {
      if (node == selected)
        continue;
      double dx = px(node) - sx;
      double dy = py(node) - sy;
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

    /**
     * 루트부터 볼 때 보이는 자리를 잡는다 (placed). 줄마다 칸들을 위아래 가운데로 쌓고, 줄 폭은 그 줄 가장 넓은 딱지.
     * 판은 0, 0 을 가운데로 양쪽을 똑같이 잡으니 전체 폭을 0 둘레로 맞춘다 — 중심 칸 줄이 맨 왼쪽에 온다
     */
    private void arrange() {
      placed.clear();
      if (columns == null)
        return;
      List<Double> centers = new ArrayList<>();
      List<Double> laneGaps = new ArrayList<>();
      double x = 0;
      for (int c = 0; c < columns.size(); c++) {
        if (c > 0)
          x += COL_GAP + (looseColumn && c == columns.size() - 1 ? COL_GAP : 0);
        double w = 0;
        for (GoalStore.Node node : columns.get(c))
          w = Math.max(w, chipW(chips.get(node)));
        double gap = Math.max(LANE_MIN, w * LANE_RATIO);
        // 갈래가 여럿이면 줄이 그만큼 넓어진다 — 줄 사이 틈 (COL_GAP) 은 그대로
        w += (laneCounts.get(c) - 1) * gap;
        laneGaps.add(gap);
        centers.add(x + w / 2);
        x += w;
      }
      double shift = x / 2;
      for (int c = 0; c < columns.size(); c++) {
        List<GoalStore.Node> col = columns.get(c);
        double total = ROW_GAP * (col.size() - 1);
        for (GoalStore.Node node : col)
          total += chipH(chips.get(node));
        double y = -total / 2;
        for (GoalStore.Node node : col) {
          double h = chipH(chips.get(node));
          double offset = (lanes.get(node) - (laneCounts.get(c) - 1) / 2.0) * laneGaps.get(c);
          placed.put(node, new Point2D(centers.get(c) - shift + offset, y + h / 2));
          y += h + ROW_GAP;
        }
      }
    }

    /** 0, 0 에서 가장 먼 딱지 끝까지 — xy[0] 가로, xy[1] 세로 */
    private double[] reach() {
      arrange();
      double rx = 0;
      double ry = 0;
      for (Map.Entry<GoalStore.Node, Label> entry : chips.entrySet()) {
        GoalStore.Node node = entry.getKey();
        Label chip = entry.getValue();
        rx = Math.max(rx, Math.abs(px(node)) + chipW(chip) / 2);
        ry = Math.max(ry, Math.abs(py(node)) + chipH(chip) / 2);
      }
      return new double[] { rx, ry };
    }

    @Override
    protected double computePrefWidth(double height) {
      return reach()[0] * 2 + (PAD + rim) * 2;
    }

    @Override
    protected double computePrefHeight(double width) {
      return reach()[1] * 2 + (PAD + rim) * 2;
    }

    @Override
    protected void layoutChildren() {
      if (goal == null)
        return;
      arrange();
      double ox = getWidth() / 2;
      double oy = getHeight() / 2;
      for (Map.Entry<GoalStore.Node, Label> entry : chips.entrySet()) {
        GoalStore.Node node = entry.getKey();
        Label chip = entry.getValue();
        double w = chipW(chip);
        double h = chipH(chip);
        double x = ox + px(node) - w / 2;
        double y = oy + py(node) - h / 2;
        chip.resizeRelocate(snapPositionX(x), snapPositionY(y), snapSizeX(w), snapSizeY(h));
      }
      for (Edge edge : edges)
        placeEdge(edge);
    }

    /** 칸 테두리에서 칸 테두리까지 */
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
      double[] start = border(fx, fy, ux, uy, from);
      double[] end = border(tx, ty, -ux, -uy, to);
      for (Line line : List.of(edge.line, edge.hit)) {
        line.setStartX(start[0]);
        line.setStartY(start[1]);
        line.setEndX(end[0]);
        line.setEndY(end[1]);
      }
      if (edge.arrow != null)
        head(edge.arrow, end, ux, uy);
      if (edge.back != null)
        head(edge.back, start, -ux, -uy);
    }

    /** tip 에 (ux, uy) 쪽을 가리키는 화살촉 */
    private void head(Polygon arrow, double[] tip, double ux, double uy) {
      double bx = tip[0] - ux * ARROW;
      double by = tip[1] - uy * ARROW;
      arrow.getPoints().setAll(tip[0], tip[1],
          bx - uy * ARROW * 0.55, by + ux * ARROW * 0.55,
          bx + uy * ARROW * 0.55, by - ux * ARROW * 0.55);
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
