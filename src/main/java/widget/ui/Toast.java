package widget.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 화면 아래쪽에 잠깐 떴다 사라지는 한 줄 알림.
 *
 * 캡쳐는 창 없이 도는 일이라 (Win+Alt+Z → 드래그 → 끝) 결과를 말해줄 자리가 없다.
 * 그렇다고 Notice 처럼 눌러야 닫히는 창을 띄우면 복사해놓고 손이 한 번 더 가서,
 * 잘 된 얘기는 여기서 조용히 흘리고 잘못된 얘기만 Notice 로 세운다.
 *
 * OCR 처럼 몇 초 걸리는 일은 progress() 로 띄워두고, 끝나면 돌려받은 걸 close() 한다.
 *
 * 창을 띄우면 윈도우가 거기로 포커스를 옮겨버리는데, 하필 캡쳐 직후는 사람이
 * 붙여넣으려고 Ctrl+V 를 치는 순간이다. 그 타자가 이 알림한테 먹히면 복사를 해놓고도
 * 안 붙는 것처럼 보여서, 뜨자마자 아까 쓰던 창으로 포커스를 돌려준다.
 */
public final class Toast {

  /** 뜨고 지는 데 쓰는 시간 — 눈에 걸리지 않게 짧게 */
  private static final Duration FADE = Duration.millis(140);

  /** 그냥 알림이 떠 있는 시간 */
  private static final Duration STAY = Duration.seconds(2.2);

  /** 화면 밑에서 이만큼 띄워 세운다 (작업표시줄 위) */
  private static final double BOTTOM_MARGIN = 90;

  /** 지금 떠 있는 알림. 새 알림이 오면 앞엣것은 바로 치운다 — 두 개가 겹쳐 뜨면 못 읽어서 */
  private static Stage showing;

  /** 포커스를 도로 돌려주려고 필요한 것만 직접 맵핑한다 (GlobalHotkey 와 같은 수법) */
  private interface User32 extends StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

    Pointer GetForegroundWindow();

    boolean SetForegroundWindow(Pointer hWnd);
  }

  private Toast() {
  }

  /** 한 줄 띄우고 알아서 사라지게 둔다 */
  public static void show(String message) {
    Stage stage = open(message);
    PauseTransition stay = new PauseTransition(STAY);
    stay.setOnFinished(e -> close(stage));
    stay.play();
  }

  /**
   * 일이 끝날 때까지 떠 있는 알림. 돌려받은 걸 부르면 닫힌다.
   * 닫는 건 JavaFX 스레드에서 불러야 한다.
   */
  public static Runnable progress(String message) {
    Stage stage = open(message);
    return () -> close(stage);
  }

  private static Stage open(String message) {
    if (showing != null)
      close(showing);

    // 알림이 뜨기 전에 쓰고 있던 창을 적어둔다 — 뜬 직후 여기로 포커스를 돌려줄 것
    Pointer previous = foreground();

    Label label = new Label(message);
    label.getStyleClass().add("toast-label");
    label.setWrapText(true);
    label.setMaxWidth(520);

    StackPane root = new StackPane(label);
    root.getStyleClass().add("toast-root");
    root.setPadding(new Insets(12, 18, 12, 18));
    root.setAlignment(Pos.CENTER);
    root.setOpacity(0);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(Toast.class.getResource("/style/style.css").toExternalForm());

    Stage stage = new Stage(StageStyle.TRANSPARENT);
    stage.setScene(scene);
    stage.setAlwaysOnTop(true);
    // 크기는 글자를 재봐야 나오니까, 뜬 뒤에 자리를 잡는다
    stage.setOnShown(e -> {
      var area = Screen.getPrimary().getVisualBounds();
      stage.setX(area.getMinX() + (area.getWidth() - stage.getWidth()) / 2);
      stage.setY(area.getMaxY() - stage.getHeight() - BOTTOM_MARGIN);
      fade(root, 0, 1, null);
      // 창이 다 뜬 뒤라야 포커스를 되돌릴 수 있어서 한 박자 미룬다
      Platform.runLater(() -> restore(previous));
    });
    stage.show();

    showing = stage;
    return stage;
  }

  private static void close(Stage stage) {
    if (showing == stage)
      showing = null;
    if (!stage.isShowing())
      return;
    fade((StackPane) stage.getScene().getRoot(), stage.getScene().getRoot().getOpacity(), 0,
        stage::close);
  }

  /** 지금 쓰고 있는 창. JNA 가 안 올라온 환경이면 없는 셈 친다 */
  private static Pointer foreground() {
    try {
      return User32.INSTANCE.GetForegroundWindow();
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * 적어둔 창으로 포커스를 돌려준다.
   * 지금 앞에 나와 있는 게 우리(알림)라서 윈도우가 이 부탁을 들어준다 —
   * 남의 창이 앞에 있을 땐 애초에 막히는 호출이지만, 그 경우엔 우리가 뺏은 게 아니라 할 일도 없다.
   */
  private static void restore(Pointer window) {
    if (window == null)
      return;
    try {
      User32.INSTANCE.SetForegroundWindow(window);
    } catch (Throwable t) {
      // 포커스를 못 돌려줘도 알림 자체는 제 할 일을 한다
    }
  }

  private static void fade(javafx.scene.Node node, double from, double to, Runnable then) {
    FadeTransition fade = new FadeTransition(FADE, node);
    fade.setFromValue(from);
    fade.setToValue(to);
    if (then != null)
      fade.setOnFinished(e -> then.run());
    fade.play();
  }
}
