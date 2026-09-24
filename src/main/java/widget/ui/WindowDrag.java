package widget.ui;

import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 타이틀바 없는 창(StageStyle.TRANSPARENT)을 빈 곳 아무 데나 잡고 끌어서 옮기게 해준다.
 *
 * 입력칸/버튼/목록 위에서는 드래그를 시작하지 않는다. 안 그러면 글자 선택이나
 * 스크롤이 전부 창 끌기로 먹혀버린다.
 */
public final class WindowDrag {

  private WindowDrag() {
  }

  public static void makeDraggable(Stage stage, Node root) {
    double[] offset = new double[2];
    boolean[] dragging = new boolean[1];

    // 자식들이 이벤트를 먹기 전에 먼저 본다. 대신 컨트롤 위면 그냥 흘려보낸다.
    root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      dragging[0] = false;
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      if (blocksDrag(e.getTarget(), root))
        return;
      offset[0] = e.getScreenX() - stage.getX();
      offset[1] = e.getScreenY() - stage.getY();
      dragging[0] = true;
    });

    root.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
      if (!dragging[0])
        return;
      stage.setX(e.getScreenX() - offset[0]);
      stage.setY(e.getScreenY() - offset[1]);
      e.consume();
    });

    root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> dragging[0] = false);
  }

  /** 누른 자리가 컨트롤(입력칸·버튼·목록…) 안이면 창을 끌지 않는다. 글자뿐인 Label 은 봐준다. */
  private static boolean blocksDrag(Object target, Node root) {
    if (!(target instanceof Node node))
      return false;
    for (Node n = node; n != null && n != root; n = n.getParent()) {
      if (n instanceof Label)
        continue;
      if (n instanceof Control)
        return true;
    }
    return false;
  }
}
