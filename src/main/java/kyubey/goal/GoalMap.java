package kyubey.goal;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
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
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import kyubey.ui.Theme;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 목표 트리를 위에서 아래로 뻗는 마인드맵으로 그린다. 맨 위 칸이 꼭대기, 쪼갠 칸이 한 줄씩 밑으로.
 *
 * 칸마다 둥근 딱지(Label) 하나, 부모 밑변 가운데에서 자식 윗변 가운데로 곧은 선 하나.
 * 딱지는 폭이 좁고, 긴 이름은 여러 줄로 접힌다.
 * 자리는 layoutChildren 에서 잡는다 — 딱지 폭·높이는 CSS 가 먹은 뒤에야 알 수 있어서.
 * 자리 잡는 법은 단순하다: 칸마다 자기 밑 칸들이 차지할 폭(span)을 재고, 부모는 그 가운데에 선다.
 *
 * 화면보다 커지면 스크롤된다. 고른 칸은 늘 보이게 끌어온다 (reveal).
 *
 * 키 (맵에 포커스가 있을 때): ↑ 부모, ↓ 첫 자식, ←/→ 형제. 나머지 키는 GoalPane 이 받는다.
 * 마우스: 클릭하면 고르기, 고른 칸을 또 누르면 메모 보기 (GoalPane),
 * 고른 칸을 더블 클릭하면 부모 + 이 칸 이름으로 브라우저 검색, 오른쪽 클릭 메뉴.
 * 칸을 옮기는 중엔 (setMoving) 클릭은 고르기만 하고 메뉴도 안 띄운다 — 새 부모 칸을 고르는 중이라서.
 */
final class GoalMap extends ScrollPane {

  /** 형제 딱지 사이 */
  private static final double H_GAP = 10;
  /** 층 사이 — 선이 지나갈 자리 */
  private static final double V_GAP = 26;
  /** 판 가장자리 여백 */
  private static final double PAD = 10;
  /** 딱지 하나가 이보다 넓어지면 줄을 바꾼다 */
  private static final double CHIP_MAX = 96;

  private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

  private final Board board = new Board();
  private final Function<GoalStore.Node, ContextMenu> menus;
  /** 접고 편 걸 남기라고 — GoalPane 이 저장한다 */
  private final Runnable onFold;
  /** 고른 칸을 또 눌렀을 때 — GoalPane 이 메모 창을 띄운다 */
  private final Consumer<GoalStore.Node> onOpen;
  /** 고른 칸을 한 번 누른 뒤 두 번째 클릭을 기다리는 중 — 안 오면 메모를 연다 */
  private PauseTransition pendingOpen;
  /** 이 안에 또 누르면 더블 클릭 */
  private static final Duration DOUBLE_CLICK = Duration.millis(300);

  private GoalStore.Goal goal;
  private GoalStore.Node selected;
  /** 다른 칸 밑으로 옮기는 중인 칸 (GoalPane.startMove). 점선 테두리로 보인다 */
  private GoalStore.Node moving;
  /** 지금 떠 있는 칸 메뉴 — 다른 칸 메뉴를 띄우기 전에 닫는다 */
  private ContextMenu shownMenu;
  private final Map<GoalStore.Node, Label> chips = new IdentityHashMap<>();
  private final List<Edge> edges = new ArrayList<>();

  private record Edge(GoalStore.Node parent, GoalStore.Node child, Line line) {
  }

  GoalMap(Function<GoalStore.Node, ContextMenu> menus, Runnable onFold, Consumer<GoalStore.Node> onOpen) {
    this.menus = menus;
    this.onFold = onFold;
    this.onOpen = onOpen;

    getStyleClass().add("goal-map");
    setContent(board);
    // 판이 화면보다 작으면 화면만큼 펴서 가운데 그린다. 크면 판 크기대로 두고 스크롤
    setFitToWidth(true);
    setFitToHeight(true);
    setPannable(true);
    board.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    board.getStyleClass().add("goal-board");
    // 빈 데를 누르면 맵에 포커스만 (칸 고른 건 그대로)
    board.setOnMousePressed(e -> requestFocus());

    // ScrollPane 이 화살표를 스크롤로 먹기 전에 가로챈다
    addEventFilter(KeyEvent.KEY_PRESSED, this::arrowKey);
  }

  /** 목표를 새로 그린다. selectId 칸을 고른 채로 (없으면 맨 위 칸). goal 이 null 이면 빈 판 */
  void show(GoalStore.Goal goal, String selectId) {
    this.goal = goal;
    chips.clear();
    edges.clear();
    board.getChildren().clear();
    selected = null;
    if (goal == null)
      return;
    build(goal.root);
    // 선 → 딱지 차례로 쌓는다
    for (Edge edge : edges)
      board.getChildren().add(edge.line);
    board.getChildren().addAll(chips.values());
    GoalStore.Node target = goal.find(selectId);
    select(target != null && chips.containsKey(target) ? target : goal.root);
  }

  /** 이름·표시가 바뀌었을 때 — 고른 칸 그대로 다시 그린다 */
  void refresh() {
    show(goal, selected == null ? null : selected.id);
  }

  GoalStore.Node selected() {
    return selected;
  }

  void select(GoalStore.Node node) {
    if (node == null || !chips.containsKey(node))
      return;
    if (selected != null && chips.containsKey(selected))
      chips.get(selected).pseudoClassStateChanged(SELECTED, false);
    selected = node;
    chips.get(node).pseudoClassStateChanged(SELECTED, true);
    reveal(node);
  }

  /** 보이는 칸들을 위→아래, 왼→오른 읽는 차례 (전위 순회). 입력칸 ↑↓ 가 이 차례로 옮겨 다닌다 */
  List<GoalStore.Node> order() {
    List<GoalStore.Node> out = new ArrayList<>();
    if (goal != null)
      collect(goal.root, out);
    return out;
  }

  private static void collect(GoalStore.Node node, List<GoalStore.Node> out) {
    out.add(node);
    if (!node.folded) {
      for (GoalStore.Node child : node.children)
        collect(child, out);
    }
  }

  /** 옮기는 칸을 정하거나 (null 이면) 옮기기를 끝낸다 */
  void setMoving(GoalStore.Node node) {
    moving = node;
    refresh();
  }

  void toggleFold(GoalStore.Node node) {
    if (node == null || node.children.isEmpty())
      return;
    node.folded = !node.folded;
    onFold.run();
    refresh();
  }

  // ── 짓기 ──

  /** 칸마다 매긴 차례 — 파스텔 테마는 칸마다 색을 돌려 칠한다 (Theme.cycleClass) */
  private int colorIndex;

  /**
   * 맨 위 칸부터 칸마다 차례로 색을 돌려 칠한다. 부모 → 자식 선은 자식 칸의 색.
   */
  private void build(GoalStore.Node node) {
    colorIndex = 0;
    buildNode(node);
  }

  private void buildNode(GoalStore.Node node) {
    Label chip = chip(node);
    String color = Theme.cycleClass(colorIndex++);
    chip.getStyleClass().add(color);
    chips.put(node, chip);
    if (node.folded)
      return;
    for (GoalStore.Node child : node.children) {
      Line line = new Line();
      line.getStyleClass().add("goal-edge");
      line.setMouseTransparent(true);
      edges.add(new Edge(node, child, line));
      int before = colorIndex;
      buildNode(child);
      line.getStyleClass().add(Theme.cycleClass(before));
    }
  }

  private Label chip(GoalStore.Node node) {
    String text = node.title;
    if (node.folded && !node.children.isEmpty())
      text += "  ▸" + count(node);
    Label chip = new Label(text);
    chip.getStyleClass().add("goal-chip");
    if (goal != null && node == goal.root)
      chip.getStyleClass().add("goal-chip-root");
    if (node.done)
      chip.getStyleClass().add("goal-chip-done");
    // 말단 칸은 흰 테두리
    if (node.leaf)
      chip.getStyleClass().add("goal-chip-leaf");
    if (node == moving)
      chip.getStyleClass().add("goal-chip-moving");
    chip.setMaxWidth(CHIP_MAX);
    chip.setWrapText(true);
    chip.setTextAlignment(TextAlignment.CENTER);
    chip.setAlignment(Pos.CENTER);
    boolean noted = node.note != null && !node.note.isBlank();
    // 메모가 있으면 마우스만 올려도 보이게
    chip.setTooltip(new Tooltip(noted ? node.title + "\n\n" + node.note : node.title));
    // setContextMenu 로 달면 메뉴 단축키(accelerator)가 장면 전체에 걸려서, 입력칸에 T 만 쳐도 메뉴가 돈다.
    // 단축키는 글자로만 보이게 (GoalPane.menu) 누를 때 만들어 띄운다 — 키는 GoalPane.mapKey 가 맵에서만 받는다
    chip.setOnContextMenuRequested(e -> {
      e.consume();
      if (moving != null)
        return;
      if (shownMenu != null)
        shownMenu.hide();
      shownMenu = menus.apply(node);
      shownMenu.show(chip, e.getScreenX(), e.getScreenY());
    });
    chip.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      requestFocus();
      e.consume();
      // 옮길 부모를 고르는 중엔 고르기만
      if (moving != null) {
        cancelPendingOpen();
        select(node);
        return;
      }
      // 두 번 누르면 (골랐든 안 골랐든) 부모 + 이 칸으로 검색
      if (e.getClickCount() >= 2) {
        cancelPendingOpen();
        select(node);
        search(node);
        return;
      }
      // 안 고른 칸은 누르면 고르기만
      if (selected() != node) {
        cancelPendingOpen();
        select(node);
        return;
      }
      // 한 번이면 두 번째 클릭이 안 오는 걸 확인하고 메모를 연다
      pendingOpen = new PauseTransition(DOUBLE_CLICK);
      pendingOpen.setOnFinished(done -> {
        pendingOpen = null;
        // 애니메이션 안에서는 showAndWait 가 안 되니까 (IllegalStateException) 한 박자 미뤄서 연다
        Platform.runLater(() -> {
          if (selected() == node)
            onOpen.accept(node);
        });
      });
      pendingOpen.play();
    });
    return chip;
  }

  private void cancelPendingOpen() {
    if (pendingOpen != null) {
      pendingOpen.stop();
      pendingOpen = null;
    }
  }

  /** "(부모)의 (이 칸)가 뭐야" 로 기본 브라우저에서 구글 검색. 맨 위 칸이면 "(이 칸)가 뭐야" */
  private void search(GoalStore.Node node) {
    GoalStore.Node parent = goal == null ? null : goal.parentOf(node);
    String title = node.title.strip();
    String subject = title + subjectParticle(title) + " 뭐야";
    String query = parent == null ? subject : parent.title.strip() + "의 " + subject;
    String url = "https://www.google.com/search?q=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
    // 브라우저 띄우는 동안 화면이 멈추지 않게
    Thread t = new Thread(() -> {
      try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
          Desktop.getDesktop().browse(URI.create(url));
          return;
        }
      } catch (Exception e) {
        // 데스크톱 연동이 막힌 환경 — 아래 뒷길로 간다
      }
      try {
        Runtime.getRuntime().exec(new String[] { "rundll32", "url.dll,FileProtocolHandler", url });
      } catch (IOException ignored) {
      }
    }, "goal-search");
    t.setDaemon(true);
    t.start();
  }

  /** 끝 글자에 받침이 있으면 "이", 없으면 "가". 한글이 아니면 "가" */
  static String subjectParticle(String word) {
    if (word.isEmpty())
      return "가";
    char last = word.charAt(word.length() - 1);
    if (last < '가' || last > '힣')
      return "가";
    return (last - '가') % 28 == 0 ? "가" : "이";
  }

  private static int count(GoalStore.Node node) {
    int n = 0;
    for (GoalStore.Node child : node.children)
      n += 1 + count(child);
    return n;
  }

  // ── 키 ──

  private void arrowKey(KeyEvent e) {
    if (selected == null || goal == null || e.isAltDown())
      return;
    GoalStore.Node next = null;
    KeyCode code = e.getCode();
    if (code == KeyCode.UP) {
      next = goal.parentOf(selected);
    } else if (code == KeyCode.DOWN) {
      if (selected.folded)
        toggleFold(selected);
      next = selected.children.isEmpty() ? null : selected.children.get(0);
    } else if (code == KeyCode.LEFT || code == KeyCode.RIGHT) {
      GoalStore.Node parent = goal.parentOf(selected);
      if (parent != null) {
        int at = parent.children.indexOf(selected) + (code == KeyCode.LEFT ? -1 : 1);
        if (at >= 0 && at < parent.children.size())
          next = parent.children.get(at);
      }
    } else {
      return;
    }
    e.consume();
    select(next);
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
    if (extraW > 0) {
      double left = getHvalue() * extraW;
      if (chip.getLayoutX() - PAD < left)
        left = chip.getLayoutX() - PAD;
      else if (chip.getLayoutX() + chip.getWidth() + PAD > left + view.getWidth())
        left = chip.getLayoutX() + chip.getWidth() + PAD - view.getWidth();
      setHvalue(Math.max(0, Math.min(1, left / extraW)));
    }
    if (extraH > 0) {
      double top = getVvalue() * extraH;
      if (chip.getLayoutY() - PAD < top)
        top = chip.getLayoutY() - PAD;
      else if (chip.getLayoutY() + chip.getHeight() + PAD > top + view.getHeight())
        top = chip.getLayoutY() + chip.getHeight() + PAD - view.getHeight();
      setVvalue(Math.max(0, Math.min(1, top / extraH)));
    }
  }

  /** 딱지와 선을 얹는 판. 크기 재기와 자리 잡기를 같은 셈(span · rowHeights)으로 한다 */
  private final class Board extends Pane {

    private final Map<GoalStore.Node, Double> spans = new IdentityHashMap<>();
    private final List<Double> rows = new ArrayList<>();

    private double chipW(GoalStore.Node node) {
      Label chip = chips.get(node);
      return Math.min(CHIP_MAX, chip.prefWidth(-1));
    }

    private double chipH(GoalStore.Node node) {
      // 줄바꿈이 있어서 높이는 정해진 폭에 따라 달라진다
      return chips.get(node).prefHeight(chipW(node));
    }

    /** 칸 하나와 그 밑 칸들이 차지하는 폭. 겸사겸사 층마다 가장 큰 딱지 높이도 잰다 */
    private double measure(GoalStore.Node node, int depth) {
      if (rows.size() <= depth)
        rows.add(0.0);
      rows.set(depth, Math.max(rows.get(depth), chipH(node)));
      double kids = 0;
      if (!node.folded && !node.children.isEmpty()) {
        for (GoalStore.Node child : node.children)
          kids += measure(child, depth + 1);
        kids += H_GAP * (node.children.size() - 1);
      }
      double span = Math.max(chipW(node), kids);
      spans.put(node, span);
      return span;
    }

    private double treeWidth() {
      spans.clear();
      rows.clear();
      return goal == null ? 0 : measure(goal.root, 0);
    }

    private double treeHeight() {
      double h = 0;
      for (double row : rows)
        h += row;
      return h + V_GAP * Math.max(0, rows.size() - 1);
    }

    @Override
    protected double computePrefWidth(double height) {
      return treeWidth() + PAD * 2;
    }

    @Override
    protected double computePrefHeight(double width) {
      treeWidth();
      return treeHeight() + PAD * 2;
    }

    @Override
    protected void layoutChildren() {
      if (goal == null)
        return;
      double width = treeWidth();
      // 판이 나무보다 넓으면 가운데로
      double left = Math.max(PAD, (getWidth() - width) / 2);
      place(goal.root, left, 0, PAD);
      for (Edge edge : edges) {
        Label from = chips.get(edge.parent);
        Label to = chips.get(edge.child);
        double sx = from.getLayoutX() + from.getWidth() / 2;
        double sy = from.getLayoutY() + from.getHeight();
        double ex = to.getLayoutX() + to.getWidth() / 2;
        double ey = to.getLayoutY();
        Line line = edge.line;
        line.setStartX(sx);
        line.setStartY(sy);
        line.setEndX(ex);
        line.setEndY(ey);
      }
    }

    /** node 를 [left, left+span] 가운데에, depth 층 줄 높이 안에서 세로 가운데로 세운다 */
    private void place(GoalStore.Node node, double left, int depth, double top) {
      double span = spans.get(node);
      double rowH = rows.get(depth);
      Label chip = chips.get(node);
      double w = chipW(node);
      double h = chipH(node);
      chip.resizeRelocate(snapPositionX(left + (span - w) / 2), snapPositionY(top + (rowH - h) / 2),
          snapSizeX(w), snapSizeY(h));
      if (node.folded || node.children.isEmpty())
        return;
      double kids = H_GAP * (node.children.size() - 1);
      for (GoalStore.Node child : node.children)
        kids += spans.get(child);
      double x = left + (span - kids) / 2;
      double nextTop = top + rowH + V_GAP;
      for (GoalStore.Node child : node.children) {
        place(child, x, depth + 1, nextTop);
        x += spans.get(child) + H_GAP;
      }
    }
  }
}
