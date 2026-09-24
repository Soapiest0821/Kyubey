package widget.ui;

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
  }
}
