package widget.voice;

import widget.ui.Ime;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 빈 입력칸에서 스페이스를 누르고 있는 동안 받아 적는 장치 (푸시 투 토크).
 *
 * 누르면 마이크가 열리고, 떼면 거기까지 들은 걸 Whisper 한테 넘겨 글자로 받는다.
 * 세 군데(한 줄 검색·LLM 모드·수다 화면)가 똑같이 쓰는데, 각 화면은 "지금 어떤 상태인지
 * 어디에 띄울지" 와 "받아 적은 말을 어떻게 쓸지" 만 Ear 로 일러주면 된다.
 *
 * 왜 하필 스페이스인가: 입력칸이 비어 있을 때 맨 앞 띄어쓰기는 어차피 아무 뜻이 없다.
 * 글자가 한 자라도 있으면 손대지 않고 평소대로 띄어쓰기로 둔다 — 말하다 말고 이어 치는
 * 사람한테서 스페이스를 뺏으면 그게 더 황당해서. 같은 이유로 Ctrl·Alt·Shift 를 같이
 * 누른 스페이스도 그냥 흘려보낸다 (한/영 전환이나 딴 프로그램 단축키일 수 있다).
 *
 * JavaFX 스레드에서는 기다리는 일을 하나도 안 한다. 마이크를 여는 것도(400ms 까지 걸린다),
 * wav 로 굽는 것도, 받아쓰는 것도 전부 딴 스레드 몫이다 — 누르는 순간 화면이 굳으면
 * 눌린 건지 아닌지도 모르게 된다.
 *
 * 손을 뗀 신호(KEY_RELEASED)가 영영 안 올 수도 있다 — 누른 채로 Esc 를 눌러 화면을
 * 떠나거나 창이 숨으면 그렇다. 그래서 입력칸이 포커스를 잃으면 녹음을 접는다.
 *
 * 한 번에 한 판만 돈다. 받아쓰는 중에 또 눌러도 못 들은 척한다 — 모델이 도는 중에
 * 새 녹음을 겹쳐 받으면 어느 말이 어디로 가는지 알 수 없게 된다.
 */
public final class Dictation {

  /** 이보다 짧게 눌렀다 뗐으면 말한 게 아니라 스페이스를 잘못 친 것으로 본다 */
  private static final double MIN_SECONDS = 0.4;

  /** 잘못됐다고 띄운 한 줄을 이만큼 뒀다가 원래 안내 문구로 돌린다 */
  private static final Duration COMPLAINT_STAY = Duration.seconds(2.4);

  private static final String LISTENING = "🎤 듣는 중… (스페이스를 떼면 끝)";
  private static final String WRITING = "✍ 받아 적는 중…";
  private static final String TOO_SHORT = "너무 짧아! 스페이스를 누른 채로 말해줘";
  private static final String NOTHING = "못 알아들었어 ㅠㅠ 다시 말해줄래?";

  /** 지금 열려 있는 마이크. 한 번에 하나뿐이라 클래스가 통째로 들고 있는다 */
  private static Mic mic;

  /** 지금 스페이스를 누르고 있는 입력칸. 뗀 신호를 이 칸 것만 받으려고 적어둔다 */
  private static TextInputControl holder;

  /** 받아쓰는 중 — 이 동안은 새 녹음을 안 받는다 */
  private static boolean writing;

  private Dictation() {
  }

  /** 각 화면이 받아쓰기한테 빌려주는 창구 */
  public interface Ear {
    /**
     * 지금 무슨 일이 일어나는지 한 줄. null 이면 "원래 안내 문구로 돌아가라" 는 뜻이다
     * (화면마다 평소에 띄워두는 게 달라서, 뭘로 돌아갈지는 각자가 안다).
     */
    void status(String message);

    /** 받아 적은 말. JavaFX 스레드에서 온다 */
    void heard(String text);
  }

  /**
   * 이 입력칸에 받아쓰기를 달아둔다. 화면을 만들 때 한 번만 부르면 된다.
   */
  public static void arm(TextInputControl field, Ear ear) {
    field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() != KeyCode.SPACE || !idle(field))
        return;
      if (e.isAltDown() || e.isControlDown() || e.isShiftDown() || e.isMetaDown())
        return; // 단축키는 떼고 누른 스페이스만 받는다

      e.consume();
      press(field, ear);
    });

    // 누르고 있는 동안 글쇠가 자동으로 되풀이되면 띄어쓰기가 한 글자씩 들어간다.
    // KEY_PRESSED 를 막아도 글자가 찍히는 건 KEY_TYPED 쪽이라 여기서 따로 막는다.
    field.addEventFilter(KeyEvent.KEY_TYPED, e -> {
      if (holder == field && " ".equals(e.getCharacter()))
        e.consume();
    });

    field.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
      if (e.getCode() != KeyCode.SPACE || holder != field)
        return;
      e.consume();
      release(ear);
    });

    // 누른 채로 Esc 를 눌러 나가거나 창이 숨으면 뗀 신호가 안 온다. 그땐 조용히 접는다.
    field.focusedProperty().addListener((obs, was, is) -> {
      if (!is && holder == field)
        abort(ear);
    });
  }

  /** 받아쓰기가 끼어들 자리인가 — 빈 칸이고, 앞 판이 아직 안 끝났으면 아니다 */
  private static boolean idle(TextInputControl field) {
    return field.getText().isEmpty() && holder == null && !writing;
  }

  private static void press(TextInputControl field, Ear ear) {
    // 마이크를 못 열어도 이 칸을 임자로 적어둔다 — 그래야 뒤따라오는 띄어쓰기(KEY_TYPED)와
    // 뗀 신호를 같이 걷어내서, 안내만 한 줄 뜨고 입력칸은 비어 있는 채로 남는다.
    holder = field;

    if (!Whisper.isReady()) {
      complain(ear, Whisper.unavailableReason());
      return;
    }

    // 말하는 동안 받아쓸 채비를 시켜둔다. 모델을 올리는 데 드는 두어 초가 여기 숨는다
    // (Server 주석 참고 — 손을 뗄 때쯤이면 이미 서 있다).
    Whisper.warmUp();

    Mic opening = new Mic();
    mic = opening;
    ear.status(LISTENING);

    // 여는 데 드는 시간(처음엔 400ms 쯤)을 JavaFX 스레드에서 기다리지 않는다.
    // 다 열리기 전에 손을 떼도 괜찮다 — 그땐 Mic 이 열자마자 도로 닫는다.
    background("voice-open", () -> {
      try {
        opening.open();
      } catch (Exception ex) {
        System.err.println("[voice] 마이크를 못 열었어: " + ex);
        Platform.runLater(() -> {
          if (mic != opening)
            return; // 이미 손을 뗐거나 딴 판이 시작했다
          mic = null;
          complain(ear, "마이크를 못 열었어 — 딴 프로그램이 쓰고 있는지 봐줘");
        });
      }
    });
  }

  private static void release(Ear ear) {
    holder = null;

    Mic closing = mic;
    mic = null;
    if (closing == null)
      return; // 애초에 못 연 판 — 안내는 누를 때 이미 띄웠다

    writing = true;
    ear.status(WRITING);
    // 마이크를 닫고 wav 로 굽는 것부터 받아쓰기까지 한 스레드에서 이어 한다
    background("voice-write", () -> write(closing, ear));
  }

  /** 스페이스를 누른 채로 화면을 떠났을 때 — 들은 건 버리고 안내도 거둔다 */
  private static void abort(Ear ear) {
    holder = null;
    Mic closing = mic;
    mic = null;
    if (closing != null)
      background("voice-cancel", closing::cancel);
    ear.status(null);
  }

  /** 담은 소리를 글자로 바꿔서 화면에 돌려준다 (딴 스레드) */
  private static void write(Mic closing, Ear ear) {
    double seconds = closing.seconds();

    Path wav;
    try {
      wav = closing.close();
    } catch (Exception ex) {
      System.err.println("[voice] 녹음한 걸 못 담았어: " + ex);
      done(ear, () -> complain(ear, "녹음한 걸 못 담았어 ㅠㅠ"));
      return;
    }

    if (wav == null || seconds < MIN_SECONDS) {
      erase(wav);
      done(ear, () -> complain(ear, TOO_SHORT));
      return;
    }

    String heard;
    try {
      heard = Whisper.transcribe(wav, seconds).trim();
    } catch (Exception ex) {
      System.err.println("[voice] 받아쓰기 실패: " + ex);
      done(ear, () -> complain(ear, "받아쓰기에 실패했어: " + firstLine(ex.getMessage())));
      return;
    }

    String text = heard;
    done(ear, () -> {
      if (text.isEmpty()) {
        complain(ear, NOTHING);
        return;
      }
      ear.status(null);
      ear.heard(text);
    });
  }

  /** 한 판이 끝났다고 알리고 마무리를 JavaFX 스레드에서 한다 */
  private static void done(Ear ear, Runnable finish) {
    Platform.runLater(() -> {
      writing = false;
      finish.run();
    });
  }

  /** 잘못된 걸 한 줄 띄웠다가 조금 뒤에 원래 문구로 돌려놓는다 */
  private static void complain(Ear ear, String message) {
    ear.status(message);
    PauseTransition stay = new PauseTransition(COMPLAINT_STAY);
    stay.setOnFinished(e -> ear.status(null));
    stay.play();
  }

  private static void background(String name, Runnable work) {
    Thread worker = new Thread(work, name);
    worker.setDaemon(true); // 받아쓰는 중에 위젯을 꺼도 프로세스가 안 남게
    worker.start();
  }

  private static void erase(Path wav) {
    if (wav == null)
      return;
    try {
      Files.deleteIfExists(wav);
    } catch (Exception ignored) {
      // 임시 폴더에 몇 KB 남는 것뿐이다
    }
  }

  private static String firstLine(String message) {
    if (message == null || message.isBlank())
      return "알 수 없는 이유";
    int br = message.indexOf('\n');
    return br < 0 ? message : message.substring(0, br);
  }

  /**
   * 받아 적은 말을 입력칸에 올리고 커서를 끝에 둔다. 세 화면이 다 하는 일이라 여기 모아뒀다.
   * 바로 보내지는 않는다 — 잘못 알아들었을 때 고쳐 칠 틈은 남겨두는 게 맞아서
   * (한 줄 검색의 "~ 열어" 만 예외인데, 그건 Main 이 따로 가로챈다).
   */
  public static void fill(TextInputControl field, String text) {
    field.setText(text);
    field.positionCaret(field.getLength());
    // 받아쓰는 사이에 포커스가 딴 데로 갔으면 이어 칠 수 있게 도로 붙여준다.
    // 그대로 잡고 있었으면 손대지 않는다 — Ime.focus 는 포커스를 뗐다 붙이는데,
    // 그 한 번이 "포커스를 새로 받았다" 로 읽혀서 화면마다 딸린 일이 괜히 돈다
    // (수다 화면은 그 신호로 못 보낸 말을 다시 던지고 뜸했으면 먼저 말도 건다).
    if (field.getScene() == null || field.getScene().getFocusOwner() != field)
      Ime.focus(field);
  }
}
