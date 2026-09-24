package widget.llm;

import java.util.List;

/**
 * LLM 백엔드 갈아끼울 수 있게 해두는 인터페이스.
 * 지금은 GeminiClient 하나지만, 나중에 Groq/Ollama 붙일 때 여기만 구현하면 됨.
 */
public interface LlmClient {

  /**
   * 답변 모드. 화면의 스위치 버튼이 이걸 고른다.
   * 구현체가 모드를 못 나누면 둘 다 똑같이 처리해도 된다.
   */
  enum Mode {
    /** 기본값. 모델이 생각할 시간을 그대로 준다 */
    NORMAL("일반"),
    /** 생각을 줄이고 (또는 가벼운 모델로) 빨리 답한다 */
    FAST("빠른");

    private final String label;

    Mode(String label) {
      this.label = label;
    }

    /** 버튼에 쓸 한글 이름 */
    public String label() {
      return label;
    }
  }

  /** 바로 물어볼 수 있는 상태인지 (키가 있는지 등) */
  boolean isReady();

  /** 설정(.env 의 키 등)을 새로 읽는다. 들고 있는 설정이 없는 백엔드는 할 일이 없다 */
  default void reload() {
  }

  /** isReady() 가 false 일 때 화면에 띄워줄 이유 */
  String unavailableReason();

  /** 화면 구석에 표시할 모델 이름 (모드마다 다를 수 있다) */
  String modelName(Mode mode);

  /**
   * 물어보고 답을 받는다. 네트워크를 타니까 절대 JavaFX 스레드에서 부르면 안 됨.
   *
   * @throws Exception 네트워크/HTTP/파싱 실패. 메시지는 그대로 화면에 보여줄 수 있는 수준으로 던진다.
   */
  String ask(String prompt, Mode mode) throws Exception;

  /**
   * 그림 속 글자를 읽어서 돌려준다 (OCR). 화면 캡쳐의 텍스트 모드가 이걸 부른다.
   * ask() 와 마찬가지로 네트워크를 타니까 JavaFX 스레드에서 부르면 안 된다.
   *
   * 그림은 PNG 바이트로 넘긴다 — 백엔드마다 그림을 싣는 방법이 달라서,
   * JavaFX Image 같은 화면 쪽 물건 대신 어디서나 통하는 모양으로 건넨다.
   * 글자가 하나도 없으면 빈 문자열이 온다 (없는 것도 결과라서 예외로 안 던진다).
   *
   * @throws Exception ask() 와 같다
   */
  default String ocr(byte[] png) throws Exception {
    throw new UnsupportedOperationException("이 백엔드는 그림 속 글자를 못 읽어");
  }

  /** 오간 말 한 줄. mine 이 true 면 내가 한 말, false 면 상대가 한 말 */
  record Message(boolean mine, String text) {
  }

  /**
   * 주고받는 대화용. 성격(persona)과 지금까지 오간 말을 통째로 넘긴다 —
   * 한 번 묻고 마는 ask() 와 달리 앞말을 기억해야 해서 목록으로 받는다.
   * ask() 와 마찬가지로 JavaFX 스레드에서 부르면 안 된다.
   *
   * 기본 구현은 마지막으로 내가 한 말만 ask() 로 흘려보낸다. 대화를 못 싣는 백엔드를
   * 붙여도 일단 돌아가게 해두는 것뿐이니, 기억시키려면 구현체가 덮어쓰면 된다.
   *
   * @throws Exception ask() 와 같다
   */
  default String chat(String persona, List<Message> turns, Mode mode) throws Exception {
    for (int i = turns.size() - 1; i >= 0; i--) {
      if (turns.get(i).mine())
        return ask(turns.get(i).text(), mode);
    }
    throw new IllegalArgumentException("건넬 말이 하나도 없어");
  }
}
