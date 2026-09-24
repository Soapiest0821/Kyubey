package widget.capture;

import widget.llm.LlmClient;
import widget.ui.Notice;
import widget.ui.Toast;

import javafx.application.Platform;

/**
 * 화면 자르기 한 판의 진행을 맡는다 (Win+Alt+Z, 또는 "캡쳐" 명령어).
 *
 * 하는 일은 셋:
 * 1. 자르기 판(Overlay)을 띄운다 — 여기서 드래그로 고르고 X·C 로 뭘 할지 정한다
 * 2. 고른 걸 클립보드에 얹는다 (그림이든, 읽어낸 글자든)
 * 3. 검색이 켜져 있으면 브라우저까지 열어준다
 *
 * 위젯 창은 건드리지 않는다. 한 JVM 안에 얹혀 살 뿐 캡쳐는 캡쳐고 위젯은 위젯이라,
 * 캡쳐가 남의 창을 숨겼다 세웠다 하지 않는다 — 화면에 떠 있는 건 떠 있는 대로 찍힌다.
 * (위젯이 안 찍히게 하고 싶으면 Esc 로 내려두고 부르면 된다.)
 *
 * 네트워크를 타는 일(렌즈 업로드·OCR)은 전부 딴 스레드로 보낸다. 잘라놓고 몇 초씩
 * 화면이 굳으면 캡쳐 도구로선 못 쓸 물건이라서.
 */
public final class Capture {

  /** 검색창에 넣을 글자 길이 한도 — 너무 길면 주소가 통째로 거절당한다 */
  private static final int QUERY_LIMIT = 300;

  /** 알림에 미리보기로 붙일 글자 길이 */
  private static final int PREVIEW = 40;

  /** 판이 한 번에 하나만 뜨게. 단축키를 연타해도 판이 겹쳐 쌓이면 못 빠져나온다 */
  private static boolean busy;

  private Capture() {
  }

  /**
   * 자르기 한 판을 시작한다. JavaFX 스레드에서 부를 것.
   *
   * @param llm 글자 읽기(OCR)를 시킬 곳. 없으면 텍스트 모드만 못 쓴다
   */
  public static void start(LlmClient llm) {
    if (busy)
      return;
    busy = true;

    // 판이 닫히면 — 골랐든 그만뒀든 — 다음 판을 받을 수 있게 풀어준다
    Runnable freed = () -> busy = false;

    try {
      Overlay.open(shot -> {
        freed.run();
        act(shot, llm);
      }, freed);
    } catch (Exception ex) {
      freed.run();
      ex.printStackTrace();
      Notice.warn(null, "화면을 못 찍었어\n\n" + ex);
    }
  }

  /** 잘라낸 걸 토글대로 처리한다 (JavaFX 스레드) */
  private static void act(Overlay.Shot shot, LlmClient llm) {
    byte[] png;
    try {
      // PNG 로 굽는 건 여기서 해둔다 — 그림 픽셀을 읽는 일은 JavaFX 스레드 몫이라,
      // 딴 스레드로 그림째 넘기면 언제 깨질지 모르는 길이 된다
      png = Png.encode(shot.image());
    } catch (Exception e) {
      e.printStackTrace();
      Notice.warn(null, "잘라낸 그림을 옮겨 담다가 실패했어\n\n" + e.getMessage());
      return;
    }

    if (shot.grab() == Overlay.Grab.IMAGE) {
      Clip.put(shot.image());
      if (shot.then() == Overlay.Then.COPY) {
        Toast.show("잘라낸 그림을 클립보드에 복사했어!");
        return;
      }
      lens(png);
      return;
    }

    if (llm == null || !llm.isReady()) {
      Notice.warn(null, "글자 읽기는 Gemini 를 쓰는데 지금은 못 불러\n\n"
          + (llm == null ? "LLM 이 아예 안 붙어 있어" : llm.unavailableReason()));
      return;
    }
    ocr(png, shot.then(), llm);
  }

  /** 그림을 구글 렌즈로 넘긴다 — 올리는 건 브라우저가 한다 (Lens 주석 참고) */
  private static void lens(byte[] png) {
    Runnable close = Toast.progress("구글 렌즈로 보내는 중…");
    background(() -> {
      try {
        Lens.open(Lens.handoff(png));
        fx(() -> {
          close.run();
          Toast.show("구글 렌즈로 보냈어! (그림은 클립보드에도 있어)");
        });
      } catch (Exception e) {
        // 넘길 페이지를 못 썼을 때. 복사는 이미 끝났으니 업로드 페이지만 열어주고
        // 붙여넣으라고 안내한다 — 여기서 그냥 실패로 끝내면 헛수고가 된다.
        System.err.println("[capture] 렌즈로 못 넘김: " + e);
        try {
          Lens.open(Lens.UPLOAD_PAGE);
        } catch (Exception ignored) {
          // 브라우저까지 못 열면 더 해볼 게 없다 — 아래 알림으로 상황만 알린다
        }
        fx(() -> {
          close.run();
          Toast.show("렌즈로 바로 못 보냈어. 열어둔 페이지에 Ctrl+V 로 붙여넣어!");
        });
      }
    });
  }

  /** 그림 속 글자를 Gemini 한테 읽히고, 복사(+검색)한다 */
  private static void ocr(byte[] png, Overlay.Then then, LlmClient llm) {
    Runnable close = Toast.progress("글자 읽는 중…");
    background(() -> {
      try {
        String text = llm.ocr(png);
        String trimmed = text == null ? "" : text.trim();

        if (!trimmed.isEmpty() && then == Overlay.Then.SEARCH) {
          String query = trimmed.length() > QUERY_LIMIT ? trimmed.substring(0, QUERY_LIMIT) : trimmed;
          Lens.open(Lens.googleSearch(query));
        }

        fx(() -> {
          close.run();
          if (trimmed.isEmpty()) {
            Toast.show("읽을 만한 글자가 안 보였어");
            return;
          }
          Clip.put(trimmed);
          Toast.show("글자를 복사했어: " + preview(trimmed));
        });
      } catch (Exception e) {
        System.err.println("[capture] OCR 실패: " + e);
        fx(() -> {
          close.run();
          Notice.warn(null, "글자를 못 읽었어\n\n" + e.getMessage());
        });
      }
    });
  }

  /** 알림에 붙일 한 줄 미리보기 — 줄바꿈은 빼고 앞머리만 */
  private static String preview(String text) {
    String line = text.replaceAll("\\s+", " ").trim();
    return line.length() > PREVIEW ? line.substring(0, PREVIEW) + "…" : line;
  }

  private static void background(Runnable work) {
    Thread thread = new Thread(work, "capture-work");
    thread.setDaemon(true);
    thread.start();
  }

  private static void fx(Runnable work) {
    Platform.runLater(work);
  }
}
