package kyubey.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * 한글 조합(IME)을 켜 둔 채로 입력창에 포커스를 주는 자리.
 *
 * JavaFX 는 "포커스 주인이 바뀌는 그 순간"에 딱 한 번, 그 컨트롤이 IME 창구
 * (inputMethodRequests + onInputMethodTextChanged) 를 들고 있는지 보고
 * 조합 중인 글자를 우리한테 보낼지 말지 정한다. 한 번 "안 보냄"으로 정해지면
 * 포커스 주인이 또 바뀔 때까지 그대로라, 그 사이엔 조합 중인 글자가 안 보이고
 * 윈도우 IME 가 알아서 들고 있다가 음절이 끝나야 한 덩어리로 던져준다.
 * (Scene.focusOwner 의 invalidated() 안에서만 enableInputMethodEvents 를 부른다. JavaFX 21 기준)
 *
 * 그래서 이 위젯에선 두 군데가 걸렸다.
 *  - IME 창구는 스킨이 만들어질 때 달리는데, 스킨보다 포커스가 먼저 오는 경우.
 *    화면에 막 붙인 입력창(LLM 모드의 질문칸)에 곧장 포커스를 주면 이렇게 된다.
 *  - stage.hide() 는 OS 쪽 창을 통째로 닫아버린다(peer.close()). 다시 show() 하면
 *    창은 새것인데 포커스 주인은 그대로라, JavaFX 가 다시 켜줄 일이 없다.
 *    → 켠 직후엔 한글이 멀쩡하다가, Esc 로 숨겼다 Win+Alt+Space 로 부르면 깨진다.
 *
 * 둘 다 "스킨부터 만들어 두고, 포커스를 잠깐 뗐다 다시 붙인다"로 풀린다.
 * 포커스를 주는 길을 여기 하나로 모아 뒀으니, 새 입력창을 만들면 이걸 쓰면 된다.
 */
public final class Ime {

  private Ime() {
  }

  /** requestFocus() 대신 쓴다. 조합 중인 글자가 화면에 보이게 하고 포커스를 준다. */
  public static void focus(Node field) {
    Scene scene = field.getScene();
    if (scene == null) {
      // 아직 화면에 안 붙었으면 해줄 수 있는 게 없다 (requestFocus 도 어차피 빈손으로 돌아간다)
      field.requestFocus();
      return;
    }

    // IME 창구는 스킨에 달려 있다. 아직 스킨이 없으면 지금 만들어 둔다.
    field.applyCss();

    // 이미 이 입력창이 포커스 주인이면 그냥 다시 불러봐야 "안 바뀜"이라 무시당한다.
    // 창 아무 데나 잠깐 맡겼다가 도로 가져와서, JavaFX 가 IME 를 다시 보게 만든다.
    Parent root = scene.getRoot();
    if (scene.getFocusOwner() == field && root != field)
      root.requestFocus();

    field.requestFocus();
    // 목표 맵이 떼어 둔 IME 가 남아 있으면 (shortcutsOnly) 한/영 키가 안 먹는다 — 글자 칸으로 올 땐 늘 되돌린다
    restore();
  }

  /**
   * areas 안에 포커스가 있는 동안 창에서 IME 를 뗀다 — 글자 칸이 아니라 N · T 같은 맨손 키를 쓰는 판(목표 맵)용.
   *
   * JavaFX 는 글자 칸이 아닌 데로 포커스가 가면 조합 글자를 안 받을 뿐, 윈도우 IME 는 그대로 켜 둔다.
   * 그래서 한글 모드로 판을 누르고 N 을 치면 IME 가 'ㅜ' 로 조합해 먹어버려서 (따로 뜨는 IME 조합 창)
   * 판엔 N 이 안 온다. 판에 있는 동안만 창과 IME 를 떼어 두고, 나가면 도로 붙인다.
   * 한/영 상태는 IME 쪽에 남아 있어서 글자 칸으로 돌아가면 치던 모드 그대로다.
   */
  public static void shortcutsOnly(Node... areas) {
    if (!System.getProperty("os.name", "").startsWith("Windows") || areas.length == 0)
      return;
    javafx.beans.value.ChangeListener<Object> update = (obs, was, is) -> apply(areas);
    areas[0].sceneProperty().addListener((obs, was, scene) -> {
      if (was != null)
        was.focusOwnerProperty().removeListener(update);
      if (scene != null)
        scene.focusOwnerProperty().addListener(update);
    });
    if (areas[0].getScene() != null)
      areas[0].getScene().focusOwnerProperty().addListener(update);
    // stage.hide() 뒤 show() 는 OS 창이 새것이라 (맨 위 주석) 창이 앞에 올 때마다 다시 맞춘다. 대화창에서 돌아올 때도
    areas[0].sceneProperty().flatMap(Scene::windowProperty).flatMap(javafx.stage.Window::focusedProperty)
        .addListener(update);
    // 전체화면을 켜고 끌 때도 OS 창이 바뀐다 — 윈도우판 JavaFX 는 전체화면을 따로 만든 OS 창에 옮겨 담는다.
    // 포커스 주인도 창 포커스도 그대로라 위 두 신호가 안 오니, 바뀐 창이 앞에 선 뒤에 다시 맞춘다
    areas[0].sceneProperty().flatMap(Scene::windowProperty)
        .flatMap(w -> w instanceof javafx.stage.Stage s ? s.fullScreenProperty() : null)
        .addListener((obs, was, is) -> javafx.application.Platform.runLater(() -> apply(areas)));
  }

  /**
   * IME 를 뗀 OS 창들. 떼는 건 그때 앞에 있던 창 하나에 걸리는데, 붙일 때 앞에 있는 창이 그 창이란 보장이 없다 —
   * 목표 맵에서 떼 놓고 전체화면을 켜고 끄거나 숨겼다 부르면 OS 창이 바뀌어서, 붙이는 건 새 창에 가고 뗀 창은
   * 뗀 채로 남는다. 그 창으로 돌아와 입력창에 치면 IME 가 없으니 한/영 키도 안 먹고 영문만 나왔다.
   * 그래서 뗀 창을 다 적어 두고, 붙일 땐 지금 창과 함께 적어 둔 창을 모두 되돌린다
   */
  private static final java.util.Set<Pointer> detached = new java.util.HashSet<>();

  /** 지금 포커스가 areas 안이면 IME 를 떼고, 아니면 붙인다. 떼는 건 창이 앞에 있을 때만 — 대화창 IME 를 건드리지 않게 */
  private static void apply(Node[] areas) {
    Scene scene = areas[0].getScene();
    if (scene == null || scene.getWindow() == null)
      return;
    boolean inside = false;
    for (Node at = scene.getFocusOwner(); at != null && !inside; at = at.getParent()) {
      for (Node area : areas)
        inside |= at == area;
    }
    if (!inside) {
      // 붙이는 건 창이 앞에 없어도 한다 — 뗀 창을 되돌리는 것뿐이라 남의 IME 를 건드릴 일이 없다
      restore();
      return;
    }
    if (!scene.getWindow().isFocused())
      return;
    try {
      // FX 스레드가 곧 창 메시지를 돌리는 스레드라, 지금 앞에 있는 우리 창이 잡힌다
      Pointer hwnd = Imm32.User32.INSTANCE.GetActiveWindow();
      if (hwnd != null && Imm32.INSTANCE.ImmAssociateContextEx(hwnd, null, 0))
        detached.add(hwnd);
    } catch (Throwable t) {
      // JNA 가 안 올라오면 예전처럼 — 한글 모드에선 영문으로 바꿔 쳐야 할 뿐
    }
  }

  /** 뗀 IME 를 모두 도로 붙인다 (지금 앞에 있는 창도 함께). 글자 칸에 포커스를 줄 때마다 부른다 */
  private static void restore() {
    if (!System.getProperty("os.name", "").startsWith("Windows"))
      return;
    try {
      Pointer active = Imm32.User32.INSTANCE.GetActiveWindow();
      if (active != null)
        detached.add(active);
      for (Pointer hwnd : detached) {
        if (Imm32.User32.INSTANCE.IsWindow(hwnd))
          Imm32.INSTANCE.ImmAssociateContextEx(hwnd, null, Imm32.IACE_DEFAULT);
      }
      detached.clear();
    } catch (Throwable t) {
      // 위와 같다
    }
  }

  /** IME 를 떼고 붙이는 데 필요한 것만 직접 맵핑한다 (Toast 와 같은 수법) */
  private interface Imm32 extends StdCallLibrary {
    Imm32 INSTANCE = Native.load("imm32", Imm32.class, W32APIOptions.DEFAULT_OPTIONS);

    /** hIMC 없이 이걸 주면 창의 기본 IME 로 되돌린다. 0 이면 IME 를 뗀다 */
    int IACE_DEFAULT = 0x10;

    boolean ImmAssociateContextEx(Pointer hWnd, Pointer hIMC, int dwFlags);

    interface User32 extends StdCallLibrary {
      User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

      Pointer GetActiveWindow();

      boolean IsWindow(Pointer hWnd);
    }
  }
}
