package kyubey.ui;

import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import java.util.function.Supplier;

/**
 * 타이틀바 없는 창(StageStyle.TRANSPARENT)을 빈 곳 아무 데나 잡고 끌어서 옮기게 해준다.
 *
 * 입력칸/버튼/목록 위에서는 드래그를 시작하지 않는다. 안 그러면 글자 선택이나
 * 스크롤이 전부 창 끌기로 먹혀버린다.
 */
public final class WindowDrag {

  /** 노드 properties 에 이 키를 달면 그 위에선 창을 끌지 않는다 — 스스로 끌리는 판 (FloatingPanel) 같은 것 */
  public static final String NO_DRAG = "kyubey.noWindowDrag";

  private WindowDrag() {
  }

  public static void makeDraggable(Stage stage, Node root) {
    makeDraggable(stage, root, () -> null);
  }

  /**
   * 전체화면이면 창은 못 옮기니, 대신 fullTarget 이 주는 판을 끌어 옮긴다 (translate). 잠깐 비키는 것이라
   * 되돌리는 건 부르는 쪽 몫이다. null 을 주면 전체화면에선 안 끌린다
   */
  public static void makeDraggable(Stage stage, Node root, Supplier<Node> fullTarget) {
    double[] offset = new double[2];
    boolean[] dragging = new boolean[1];
    Node[] moving = new Node[1];

    // 자식들이 이벤트를 먹기 전에 먼저 본다. 대신 컨트롤 위면 그냥 흘려보낸다.
    root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      dragging[0] = false;
      // 같은 자리 먼저 단 필터가 먹은 누름 (전체화면 기본 화면의 바깥 누르기 같은 것) 은 안 끈다
      if (e.getButton() != MouseButton.PRIMARY || e.isConsumed())
        return;
      if (blocksDrag(e.getTarget(), root))
        return;
      moving[0] = stage.isFullScreen() ? fullTarget.get() : null;
      if (stage.isFullScreen()) {
        if (moving[0] == null || !within(e.getTarget(), moving[0]))
          return;
        offset[0] = e.getScreenX() - moving[0].getTranslateX();
        offset[1] = e.getScreenY() - moving[0].getTranslateY();
      } else {
        offset[0] = e.getScreenX() - stage.getX();
        offset[1] = e.getScreenY() - stage.getY();
      }
      dragging[0] = true;
    });

    root.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
      if (!dragging[0])
        return;
      if (moving[0] != null) {
        moving[0].setTranslateX(e.getScreenX() - offset[0]);
        moving[0].setTranslateY(e.getScreenY() - offset[1]);
      } else {
        stage.setX(e.getScreenX() - offset[0]);
        stage.setY(e.getScreenY() - offset[1]);
      }
      e.consume();
    });

    root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> dragging[0] = false);
  }

  /** 누른 자리가 그 판 안인지 */
  private static boolean within(Object target, Node box) {
    if (!(target instanceof Node node))
      return false;
    for (Node n = node; n != null; n = n.getParent())
      if (n == box)
        return true;
    return false;
  }

  /** 누른 자리가 컨트롤(입력칸·버튼·목록…) 안이면 창을 끌지 않는다. 글자뿐인 Label 은 봐준다. */
  private static boolean blocksDrag(Object target, Node root) {
    if (!(target instanceof Node node))
      return false;
    for (Node n = node; n != null && n != root; n = n.getParent()) {
      if (n instanceof Label)
        continue;
      if (n instanceof Control || n.getProperties().containsKey(NO_DRAG))
        return true;
    }
    return false;
  }
}
