package kyubey.goal;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.core.SettingsDialog;
import kyubey.ui.Theme;
import kyubey.ui.WindowDrag;

import java.util.ArrayList;
import java.util.List;

/**
 * 새로 정리하기 창 (GoalKindDialog) 에서 고르고 만든 뒤 뜨는 길잡이 — 고른 방식에 맞는 작은 예시 그림과 쓰는 법 몇 줄.
 *  - 목표 쪼개기: 목표 하나를 아래로 쪼갠 트리
 *  - 관계도: 중심 칸 둘레에 이어진 칸들 + 옆 칸끼리 이은 선 (방향 있으면 화살표)
 * 예시 칸은 맵 칸 (goal-chip) 과 같은 생김새로 그려서 실제 판이 어떻게 보일지 바로 알게 한다.
 * "다음부터 안 보기" 를 켜고 닫으면 true — GoalPane 이 그 방식은 다시 안 띄운다 (Book.guideOff).
 */
final class GoalGuideDialog {

  /** 예시 칸 하나 — 판 가운데 기준 자리 */
  private record Spot(String title, double x, double y, boolean root) {
  }

  /** 예시 선 — spots 의 차례 번호끼리 */
  private record Bond(int from, int to) {
  }

  private GoalGuideDialog() {
  }

  /**
   * 창을 띄우고 닫힐 때까지 기다린다. kind 는 GoalStore.TREE · GRAPH, llm 은 트리를 partner 랑 쪼개는지,
   * directed 는 관계도 연결에 방향이 있는지. "다음부터 안 보기" 를 켰으면 true
   */
  static boolean show(Window owner, String kind, boolean llm, boolean directed, String partner) {
    boolean isGraph = GoalStore.GRAPH.equals(kind);
    Stage dialog = new Stage();
    if (owner != null)
      dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label(isGraph ? "🕸 관계도는 이렇게 써" : "✂ 목표 쪼개기는 이렇게 써");
    title.getStyleClass().add("notice-title");

    Pane example = isGraph ? graphExample(directed) : treeExample();

    VBox steps = new VBox(4);
    for (String step : isGraph ? graphSteps(directed) : treeSteps(llm, partner)) {
      Label line = new Label(step);
      line.getStyleClass().add("notice-message");
      line.setWrapText(true);
      line.setMaxWidth(340);
      steps.getChildren().add(line);
    }

    CheckBox off = new CheckBox("다음부터 안 보기");
    off.getStyleClass().add("dialog-path-label");
    off.setFocusTraversable(false);

    Button ok = new Button("알겠어!");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setDefaultButton(true);
    ok.setOnAction(e -> dialog.close());

    Pane gap = new Pane();
    HBox.setHgrow(gap, Priority.ALWAYS);
    HBox bottom = new HBox(8, off, gap, ok);
    bottom.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(12, title, example, steps, bottom);
    root.getStyleClass().addAll("dialog-root", "notice-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(384);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE)
        dialog.close();
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      if (owner == null || owner.getWidth() <= 0) {
        dialog.centerOnScreen();
      } else {
        dialog.setX(owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2);
        dialog.setY(owner.getY() + (owner.getHeight() - dialog.getHeight()) / 2);
      }
      ok.requestFocus();
    });
    dialog.showAndWait();
    return off.isSelected();
  }

  // ── 예시 ──

  /** 「블로그 열기」 를 쪼갠 트리 — 위에서 아래로 */
  private static Pane treeExample() {
    List<Spot> spots = List.of(
        new Spot("블로그 열기", 0, -62, true),
        new Spot("글감", -110, 0, false),
        new Spot("꾸미기", 0, 0, false),
        new Spot("올리기", 110, 0, false),
        new Spot("주제 10개", -130, 58, false),
        new Spot("주 1편", -55, 58, false),
        new Spot("도메인", 110, 58, false));
    List<Bond> bonds = List.of(new Bond(0, 1), new Bond(0, 2), new Bond(0, 3),
        new Bond(1, 4), new Bond(1, 5), new Bond(3, 6));
    return new Example(spots, bonds, false);
  }

  /** 「이사」 를 가운데 두고 따져볼 것들 — 옆 칸끼리도 잇는다 */
  private static Pane graphExample(boolean directed) {
    List<Spot> spots = List.of(
        new Spot("이사", 0, 0, true),
        new Spot("예산", -120, -48, false),
        new Spot("집 크기", -120, 48, false),
        new Spot("동네", 120, -48, false),
        new Spot("출퇴근", 120, 48, false),
        new Spot("대출", -40, -72, false));
    List<Bond> bonds = directed
        ? List.of(new Bond(0, 1), new Bond(0, 3), new Bond(0, 2), new Bond(0, 4),
            new Bond(1, 2), new Bond(3, 4), new Bond(5, 1))
        : List.of(new Bond(0, 1), new Bond(0, 3), new Bond(0, 2), new Bond(0, 4),
            new Bond(1, 2), new Bond(3, 4), new Bond(1, 5));
    return new Example(spots, bonds, directed);
  }

  /** partner 는 "마도카랑" 처럼 조사까지 붙인 이름 */
  private static List<String> treeSteps(boolean llm, String partner) {
    List<String> steps = new ArrayList<>();
    steps.add(llm
        ? "• 맨 위 칸이 목표야. 칸을 고르고 Enter 면 " + partner + " 말하면서 작은 칸들로 쪼개."
        : "• 맨 위 칸이 목표야. 칸을 고르고 Enter · N 이면 그 밑에 칸을 손으로 붙여. 입력칸에 이름 치고 Enter 도 돼.");
    steps.add("• Space 는 끝냄 표시, F 는 접기, M 은 다른 칸 밑으로 옮기기, Delete 는 지우기.");
    steps.add(llm
        ? "• 더 안 쪼갤 칸은 L 로 말단 — 흰 테두리가 둘리고, " + partner + " 물어서 할 일로 내릴 수 있어."
        : "• 더 안 쪼갤 칸은 L 로 말단 — 흰 테두리가 둘려서 바로 손댈 칸인 게 보여.");
    steps.add("• 칸을 한 번 더 누르면 메모, 오른쪽 클릭이면 메뉴 (키도 적혀 있어).");
    return steps;
  }

  private static List<String> graphSteps(boolean directed) {
    List<String> steps = new ArrayList<>(List.of(
        "• 가운데 칸이 중심 생각이야. 칸을 고르고 N 이면 이은 칸, 빈 데 누르고 N 이면 따로 선 칸.",
        "• Ctrl 누른 채 칸을 끌어서 다른 칸에 놓으면 둘을 잇고, 빈 데 놓으면 거기 이은 새 칸이 생겨.",
        "• 그냥 끌면 칸 옮기기. 선을 오른쪽 클릭하면 그 연결을 끊을 수 있어."));
    steps.add(directed
        ? "• 연결엔 방향이 있어 — 끈 칸에서 놓은 칸 쪽으로 화살표가 가. 반대로 한 번 더 끌면 양방향, 선 오른쪽 클릭으로도 골라."
        : "• 연결엔 방향이 없어 — 빈 데 오른쪽 클릭 \"설정\" 에서 화살표를 켤 수도 있어.");
    return steps;
  }

  /** 예시 칸과 선을 얹는 작은 판 — GraphMap.Board 처럼 한가운데가 0, 0 이고 선은 칸 테두리에서 끊는다 */
  private static final class Example extends Pane {

    private static final double W = 340;
    private static final double H = 180;
    private static final double ARROW = 7;

    private final List<Spot> spots;
    private final List<Bond> bonds;
    private final List<Label> chips = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Polygon> arrows = new ArrayList<>();

    Example(List<Spot> spots, List<Bond> bonds, boolean directed) {
      this.spots = spots;
      this.bonds = bonds;
      getStyleClass().add("goal-map");
      setMinSize(W, H);
      setPrefSize(W, H);
      setMaxSize(W, H);
      // 예시라 누를 데가 없다
      setMouseTransparent(true);
      for (Bond bond : bonds) {
        Line line = new Line();
        line.getStyleClass().add("goal-edge");
        lines.add(line);
        getChildren().add(line);
        if (directed) {
          Polygon arrow = new Polygon();
          arrow.getStyleClass().add("graph-arrow");
          arrows.add(arrow);
          getChildren().add(arrow);
        }
      }
      int color = 0;
      for (Spot spot : spots) {
        Label chip = new Label(spot.title);
        chip.getStyleClass().addAll("goal-chip", Theme.cycleClass(color++));
        if (spot.root)
          chip.getStyleClass().add("goal-chip-root");
        chips.add(chip);
        getChildren().add(chip);
      }
    }

    @Override
    protected void layoutChildren() {
      double ox = getWidth() / 2;
      double oy = getHeight() / 2;
      for (int i = 0; i < spots.size(); i++) {
        Label chip = chips.get(i);
        double w = chip.prefWidth(-1);
        double h = chip.prefHeight(w);
        chip.resizeRelocate(snapPositionX(ox + spots.get(i).x - w / 2), snapPositionY(oy + spots.get(i).y - h / 2),
            snapSizeX(w), snapSizeY(h));
      }
      for (int i = 0; i < bonds.size(); i++) {
        Label from = chips.get(bonds.get(i).from);
        Label to = chips.get(bonds.get(i).to);
        double fx = from.getLayoutX() + from.getWidth() / 2;
        double fy = from.getLayoutY() + from.getHeight() / 2;
        double tx = to.getLayoutX() + to.getWidth() / 2;
        double ty = to.getLayoutY() + to.getHeight() / 2;
        double len = Math.max(1, Math.hypot(tx - fx, ty - fy));
        double ux = (tx - fx) / len;
        double uy = (ty - fy) / len;
        double[] start = border(fx, fy, ux, uy, from);
        double[] end = border(tx, ty, -ux, -uy, to);
        Line line = lines.get(i);
        line.setStartX(start[0]);
        line.setStartY(start[1]);
        line.setEndX(end[0]);
        line.setEndY(end[1]);
        if (!arrows.isEmpty()) {
          double bx = end[0] - ux * ARROW;
          double by = end[1] - uy * ARROW;
          arrows.get(i).getPoints().setAll(end[0], end[1],
              bx - uy * ARROW * 0.55, by + ux * ARROW * 0.55,
              bx + uy * ARROW * 0.55, by - ux * ARROW * 0.55);
        }
      }
    }

    /** 가운데 (cx, cy) 에서 (ux, uy) 쪽으로 나가다 딱지 테두리에 닿는 자리 (GraphMap.Board.border 와 같다) */
    private static double[] border(double cx, double cy, double ux, double uy, Label chip) {
      double hw = chip.getWidth() / 2 + 2;
      double hh = chip.getHeight() / 2 + 2;
      double t = Math.min(Math.abs(ux) < 1e-6 ? Double.MAX_VALUE : hw / Math.abs(ux),
          Math.abs(uy) < 1e-6 ? Double.MAX_VALUE : hh / Math.abs(uy));
      return new double[] { cx + ux * t, cy + uy * t };
    }
  }
}
