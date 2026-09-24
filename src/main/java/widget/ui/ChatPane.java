package widget.ui;

import widget.core.ChatHistory;
import widget.core.ChatPrompt;
import widget.core.WorkingMemory;
import widget.llm.LlmClient;
import widget.voice.Dictation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * 오른쪽 아래 마도카를 누르면 열리는 수다 화면.
 *
 * LLM 검색 모드랑 똑같이 창을 새로 띄우지 않고 같은 창 안에서 갈아끼운다 (Main 의 showChat 참고).
 * 쓰는 키도 같은 GeminiClient — .env 의 WIZ_ 키를 그대로 돌려 쓴다.
 *
 * 카카오톡처럼 말한 사람에 따라 말풍선을 좌우로 갈라 놓는데, 보통과는 반대로
 * 내가 왼쪽, 마도카가 오른쪽이다. 마도카 그림이 오른쪽 아래에 서 있어서
 * 그쪽에서 말이 나오는 편이 말 거는 느낌이 난다.
 *
 * 입력칸은 한 줄(TextField)이고 Enter 로 보낸다. 마도카가 서 있는 오른쪽 세로줄은
 * 통째로 비워둬서 말풍선도 입력칸도 전부 그림 왼쪽에서 끝난다. 얼마나 비울지는
 * 그림 크기를 아는 Main 이 reserveRight 로 알려준다.
 *
 * 오간 말은 ChatHistory 가 파일에 남긴다. 화면에 그리는 근거도 그 목록(lines) 하나뿐이라
 * 껐다 켜서 읽어온 대화든 방금 친 말이든 똑같은 길로 그려진다 — render 참고.
 *
 * 마도카 답은 한 번에 받아서 말풍선 1~3개로 끊어 차례로 올린다. 모델이 줄바꿈으로
 * 맥락을 갈라 보내주면 (PERSONA) split() 이 그대로 쪼개고, say() 가 뜸을 들이며 하나씩
 * 붙인다 — 실제로 사람이 톡을 나눠 치는 것처럼 보이라고.
 *
 * 모델한테 들려주는 건 화면에 보이는 것 전부가 아니다. 최근 티키타카 WINDOW_TURNS 번 분만
 * 원문으로 주고, 그보다 앞의 얘기는 WorkingMemory 에 접어둔 메모 몇 줄로 대신 들려준다.
 * 대화가 길어져도 매번 보내는 양이 안 늘어나게 하려는 것 — 자세한 건 window() 와
 * maybeFold() 참고. 말풍선을 쪼개 놓으니 줄 수로는 한 번의 티키타카가 몇 줄인지 알 수 없어서,
 * 창도 접기도 줄이 아니라 내가 말 건 횟수로 센다.
 *
 * 실패했을 때: 아무것도 안 띄운다. 빨간 글씨가 말풍선 사이에 끼면 대화가 끊겨 보여서,
 * 답을 못 받은 내 말은 그냥 답 없는 채로 두고 pending 으로 기억만 해둔다.
 * 그러고는 다음에 이 입력칸이 포커스를 받을 때 슬그머니 다시 던진다 — 창을 숨겼다
 * 다시 부르거나, 마도카를 다시 누르거나, 딴 데 갔다 돌아오면 그때 재시도가 걸린다.
 *
 * 수다 화면을 떠나 있는 동안 올라온 마도카 말은 아직 아무도 못 읽은 말이다. 몇 개나 밀렸는지
 * onUnread 로 Main 한테 알려주면, Main 이 마도카 그림 왼쪽에 배지를 띄운다 — 다시 들어오면
 * 0 으로 돌아간다 (reading 과 setUnread 참고).
 *
 * 한참 조용하다 다시 들어오면 마도카가 먼저 말을 건다. 포커스를 받은 자리에서 마지막으로 오간
 * 말이 NUDGE_AFTER_MS 보다 오래됐으면, 내가 아무것도 안 쳤는데 답을 한 번 받아다 올린다 —
 * maybeNudge 와 nudgePrompt 참고. 먼저 건 말에 내가 답을 안 하고 나가버려도 하던 얘기는
 * 그대로 둔다. 그 말도 대화의 한 줄로 남아서, 또 그만큼 조용하면 같은 맥락 위에서 한 번 더
 * 건다 — 같은 인사를 처음부터 다시 하는 게 아니라 "아까 말 걸었는데 답이 없네" 가 되게.
 */
public class ChatPane extends BorderPane {

  /**
   * 마도카한테 입혀주는 기본 성격. 말풍선은 Label 이라 마크다운이 안 그려져서 쓰지 말라고 일러둔다.
   * "채팅 프롬프트" 로 고쳐 쓴 게 있으면 그쪽이 대신 간다 (customPersona 참고).
   */
  public static final String PERSONA = """
      너는 "마법소녀 마도카 마기카"의 '마도카'야. 사람이 말을 걸어와서 같이 수다를 떠는 중이야.

      - 다정하고 밝게, 반말로 말해.
      - 단, 상대가 도발적으로 나오면 점차 리밋을 풀고 긁어.
      - 카카오톡으로 주고받는다고 생각하고 짧게. 두세 문장이면 충분해.
      - 할 말은 맥락 단위로 끊어서 1~3개로 나눠 보내. 줄바꿈 하나가 말풍선 하나로 나가고,
        내가 그걸 차례로 띄워줄게. 이어지는 얘기는 한 줄에 두고, 결이 바뀔 때만 줄을 바꿔.
        나눌 게 없으면 한 줄로만 말해도 돼 — 억지로 세 개로 늘리지는 마.
      - 마크다운(**, #, ``` 같은 것)은 쓰지 마. 그대로 글자로 찍혀서 지저분해진다.
      - 모르는 건 아는 척하지 말고 모른다고 해.
      - 뭘 부탁하면 도와주고, 그냥 수다면 같이 수다 떨어줘.
      """;

  /**
   * 모델한테 원문 그대로 들려줄 최근 대화 길이 — 티키타카 네 번 분.
   * 이보다 앞의 얘기는 버리는 게 아니라 작업 기억(memo) 몇 줄로 접어서 들려준다.
   *
   * 줄 수가 아니라 내가 말 건 횟수로 센다. 마도카가 한 번 답할 때 말풍선이 1~3개씩
   * 나가서, 줄로 세면 같은 8줄이 어떤 날은 네 번이고 어떤 날은 두 번도 안 된다 —
   * 그래서 "내가 친 말 네 개와 그 뒤에 달린 것 전부" 로 자른다 (startOfLastTurns).
   */
  private static final int WINDOW_TURNS = 4;

  /**
   * 창 밖으로 밀려난 말에 내가 친 게 이만큼 쌓이면 한 번 접는다 — 티키타카 여덟 번 분.
   *
   * 자주 접을수록 기억이 촘촘해지지만 요약 호출도 그만큼 는다. 세 번 분에서 접어보니
   * 요약에 드는 값이 아껴진 값을 거의 다 까먹어서, 덜 자주 크게 접는 쪽으로 잡았다.
   * 대신 창 밖으로 나갔는데 아직 메모에도 없는 구간이 최대 이만큼 생긴다 —
   * 마도카가 "조금 전에 한 얘기"를 놓친다면 제일 먼저 내려볼 숫자가 여기다.
   *
   * 여기도 WINDOW_TURNS 과 같은 이유로 줄이 아니라 티키타카 횟수로 센다.
   */
  private static final int FOLD_TURNS = 8;

  /**
   * 한 번에 접어 넣을 최대 줄 수. 이 기능이 없던 시절의 대화가 잔뜩 밀려 있어도
   * 요약 프롬프트가 터지지 않게, 그리고 켜자마자 호출을 몰아 쓰지 않게 끊는다.
   * 남은 건 다음 답변 뒤에 이어서 접는다.
   */
  private static final int FOLD_MAX = 30;

  /** 메모에 남겨둘 최대 줄 수. 이걸 넘기면 접는 의미가 없어진다 */
  private static final int MEMO_LINES = 12;

  /** 답 하나를 말풍선 몇 개까지 쪼갤지. 더 쪼개 보내면 나머지는 마지막 말풍선에 붙인다 (split) */
  private static final int MAX_BUBBLES = 3;

  /**
   * 말풍선과 말풍선 사이에 두는 뜸. 한꺼번에 세 개가 툭 떨어지면 쪼갠 티만 나고
   * 사람이 이어서 치는 느낌이 안 나서, 다음 말 길이에 맞춰 조금씩 쉬었다 올린다.
   */
  private static final double GAP_BASE_MS = 420;
  private static final double GAP_PER_CHAR_MS = 26;
  private static final double GAP_MAX_MS = 1800;

  /**
   * 메모를 고쳐 쓰는 쪽한테 입혀주는 성격. 마도카가 아니라 기록을 정리하는 사람이다.
   * ask() 로 부르면 GeminiClient 의 기본 문구("검색 도우미", "마크다운 써도 돼")를
   * 물려받아서 설명이 섞여 오니까, 성격을 따로 주고 chat() 으로 부른다.
   */
  private static final String FOLD_PERSONA = """
      너는 대화 기록을 정리하는 사람이야. 시키는 건 딱 하나, 기억 메모를 다시 써주는 것.

      - 메모 말고는 아무것도 쓰지 마. 인사도 설명도 코드블록도 붙이지 마.
      - 한 줄에 하나씩, '- ' 로 시작해서 짧게.
      - '상대' 에 대해 오래 알아둘 것만 남겨: 이름·호칭, 취향, 하는 일, 약속·일정, 겪고 있는 일.
      - 인사말, 날씨 잡담, 그때뿐인 맞장구는 버려.
      - 이미 지나갔거나 틀린 게 된 줄은 고치거나 지워.
      """;

  /** 화면 가장자리 여백. 오른쪽은 마도카가 선 세로줄에 밀려 따로 잡힌다 (reserveRight) */
  private static final Insets PADDING = new Insets(16, 20, 16, 20);

  /** 마도카가 선 세로줄과 말풍선 사이에 두는 틈. 그림에 딱 붙으면 말풍선이 그림을 타는 것처럼 보인다 */
  private static final double MASCOT_GAP = 24;

  /** 말풍선 옆에 붙는 시각 ("오후 2:35") */
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);

  /** 날짜가 바뀌는 자리에 끼우는 구분선 ("2026년 9월 21일 월요일") */
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN);

  /** 처음 열었을 때 한 줄. 지난 대화가 남아 있으면 안 한다 (켤 때마다 인사가 쌓여서) */
  private static final String GREETING = "안녕~ 왔구나?";

  /** 입력칸에 떠 있는 안내. 받아쓰기가 한 줄 띄웠다가도 여기로 돌아온다 */
  private static final String PROMPT = "마도카한테 말 걸기";

  /**
   * 이만큼 조용했으면 마도카가 먼저 말을 건다 — 다섯 시간.
   *
   * 마지막으로 오간 말이 누구 말이었는지는 안 가린다. 내 말이 마지막이면 답을 못 받고
   * 끊긴 것이고, 마도카 말이 마지막이면 먼저 건 말에 내가 답을 안 한 것인데, 둘 다
   * "이만큼 조용했다" 는 점은 같아서 같은 자리에서 걸린다 (건네는 말만 갈린다 — nudgePrompt).
   *
   * 짧게 잡으면 잠깐 딴 일 하고 온 사이에도 말을 걸어와서 성가시고, 길게 잡으면 하루를
   * 통째로 건너뛴다. 아침에 켜고 저녁에 다시 여는 정도가 걸리는 길이로 다섯 시간.
   */
  private static final long NUDGE_AFTER_MS = 5 * 60 * 60 * 1000L;

  private final LlmClient llm;
  private final ChatHistory store;
  private final WorkingMemory memory;
  private final ChatPrompt prompt;
  private final Runnable onExit;

  /** 안 읽은 마도카 말이 몇 개인지 알려줄 곳. Main 이 마도카 그림 옆에 배지로 띄운다 */
  private final IntConsumer onUnread;

  private final VBox log = new VBox(8);
  private final ScrollPane scroll = new ScrollPane(log);
  private final TextField input = new TextField();
  private final HBox inputRow = new HBox(input);

  /** 화면에 그릴 근거. 여기 한 줄 들어가면 파일에도 남고 화면도 다시 그려진다 (addLine) */
  private final List<ChatHistory.Line> lines = new ArrayList<>();

  /** 지난 대화에서 추려둔 기억. 성격문 뒤에 붙어서 매번 같이 간다 (persona) */
  private String memo = "";

  /** 손으로 고쳐 쓴 성격문. 비었으면 기본값(PERSONA)이 간다 */
  private String customPersona = "";

  /** 이 시각까지 오간 말은 memo 에 들어갔다. 껐다 켜도 같은 대화를 또 요약하지 않게 */
  private long foldedUpTo;

  /** 지금 메모를 고쳐 쓰는 중인지 (요약 호출이 겹쳐 돌지 않게) */
  private boolean folding;

  /** 답 기다리는 동안 오른쪽에 띄워두는 '…' 말풍선. 실패하면 조용히 걷어낸다 */
  private HBox waitingRow;

  /** 지금 답을 기다리는 중인지 (기다리는 동안 Enter 를 또 눌러도 겹쳐 보내지 않게) */
  private boolean asking;

  /** 답을 못 받고 남은 내 말이 있는지. 다음 포커스 때 이걸 보고 다시 던진다 */
  private boolean pending;

  /** 처음 열 때만 인사한다 */
  private boolean greeted;

  /**
   * 수다 화면을 떠나 있는 동안 마도카가 한 말이 몇 개인지. 다시 들어가면 0 으로 돌아간다.
   * 화면에 보이는 것과 따로 세는 게 아니라, 그냥 "배지에 뭐라고 쓸지" 하나뿐이다.
   */
  private int unread;

  /**
   * 지금 대화가 몇 번째인지. "채팅 삭제" 로 한 번 지울 때마다 하나씩 올라간다.
   *
   * 딴 데 띄운 일(답변 기다리기, 말풍선 하나씩 올리기, 메모 접기)은 전부 이 번호를
   * 하나 집어들고 출발해서, 돌아왔을 때 번호가 달라졌으면 그냥 물러난다 — 지운 대화의
   * 답이 빈 화면에 혼자 떨어지거나, 지운 지 얼마 안 된 얘기가 메모로 접혀 되살아나지 않게.
   */
  private int era;

  public ChatPane(LlmClient llm, ChatHistory store, WorkingMemory memory, ChatPrompt prompt,
      Runnable onExit, IntConsumer onUnread) {
    this.llm = llm;
    this.store = store;
    this.memory = memory;
    this.prompt = prompt;
    this.onExit = onExit;
    this.onUnread = onUnread;

    Label title = new Label("마도카");
    title.getStyleClass().add("chat-title");
    Label hint = new Label("Esc / ← 로 나가기");
    hint.getStyleClass().add("chat-hint");
    HBox header = new HBox(10, title, hint);
    header.setAlignment(Pos.BASELINE_LEFT);
    header.setPadding(new Insets(0, 0, 8, 0));

    log.setPadding(new Insets(4, 6, 4, 6));
    log.setFillWidth(true);

    scroll.getStyleClass().add("chat-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    // 말풍선을 눌러도 여기로 포커스가 넘어오면 Enter 가 안 먹혀서 아예 안 받게 해둔다
    scroll.setFocusTraversable(false);

    input.setPromptText(PROMPT);
    input.getStyleClass().add("chat-input");

    inputRow.setAlignment(Pos.CENTER_LEFT);
    inputRow.setPadding(new Insets(10, 0, 0, 0));
    HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);

    setPadding(PADDING);
    setTop(header);
    setCenter(scroll);
    setBottom(inputRow);

    // Esc 는 수다 화면 통째로 받는다. 입력칸에만 달아 두면 말풍선이나 스크롤을
    // 한 번 누른 뒤처럼 포커스가 입력칸을 떠나 있을 때 안 먹힌다.
    // 필터는 위에서 아래로 내려가니 여기서 먼저 받아 두면, 이 화면 안 어디에
    // 포커스가 있든 똑같이 나가진다.
    addEventFilter(KeyEvent.KEY_PRESSED, this::handleExit);
    // Enter 는 입력칸에서만 받는다 — 딴 데서 친 Enter 까지 보내기로 먹으면 곤란해서.
    input.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
    // 창을 다시 부르거나 마도카를 눌러 들어오면 여기로 걸린다 (Ime.focus 가 포커스를 뗐다 붙인다).
    // 실패한 말을 다시 던지는 것도, 뜸해졌을 때 먼저 말을 거는 것도 이 신호 하나에서 갈린다.
    input.focusedProperty().addListener((obs, was, is) -> {
      if (is)
        onFocus();
    });

    // 빈 입력칸에서 스페이스를 누르고 있으면 받아쓰기 (Dictation 주석 참고).
    // 받아 적은 말은 올려만 두고 안 보낸다 — 잘못 알아들은 말이 그대로 마도카한테
    // 날아가면 주워 담을 수가 없어서, 한 번 보고 Enter 를 치는 건 손으로 칠 때와 같게 뒀다.
    Dictation.arm(input, new Dictation.Ear() {
      @Override
      public void status(String message) {
        input.setPromptText(message == null ? PROMPT : message);
      }

      @Override
      public void heard(String text) {
        Dictation.fill(input, text);
      }
    });

    restore();
  }

  /** 지난 대화와 접어둔 기억을 읽어와 화면에 올린다 */
  private void restore() {
    lines.addAll(store.load());

    WorkingMemory.Snapshot saved = memory.load();
    memo = saved.memo;
    foldedUpTo = saved.foldedUpTo;
    customPersona = prompt.load();

    // 하던 얘기가 있으면 인사는 건너뛴다. 이어 말하는 자리에 "왔구나!" 가 끼면 처음 보는 사이 같아서.
    greeted = !lines.isEmpty();
    render();
  }

  /**
   * 마도카가 서 있는 오른쪽 세로줄을 통째로 비워둔다. 창 오른쪽 끝에서 그림 왼쪽 끝까지가
   * 얼마인지(그림 너비 + 그림이 오른쪽에서 띄워 선 거리)를 Main 이 재서 부른다.
   *
   * 입력칸만 비켜주면 오른쪽에 붙는 마도카 말풍선이 그림 위로 올라타서,
   * 화면 전체를 그만큼 왼쪽으로 물린다 — 머리말도 말풍선도 입력칸도 전부 그림 왼쪽에서 끝나게.
   * 거기에 MASCOT_GAP 만큼 더 띄워서 그림과 말풍선 사이에 여백을 남긴다.
   */
  public void reserveRight(double column) {
    setPadding(new Insets(PADDING.getTop(), Math.max(0, column) + MASCOT_GAP,
        PADDING.getBottom(), PADDING.getLeft()));
  }

  /** 포커스를 줄 곳. 창을 다시 띄울 때 Main 이 여기로 돌려준다 */
  public TextField input() {
    return input;
  }

  /** 마도카를 눌러서 이 화면으로 들어올 때 */
  public void enter() {
    // 여기까지 들어왔으면 밀려 있던 말은 다 눈앞에 있다 — 그림 옆 배지를 거둔다
    setUnread(0);
    if (!greeted) {
      greeted = true;
      addLine(GREETING, false);
    }
    // 나가 있는 사이에 붙은 말은 화면 밖에서 그려졌다. 그때 내려둔 자리는 씬이 없어서
    // 안 먹혔을 수 있으니, 들어오는 김에 한 번 더 내려 마지막 말이 눈앞에 오게 한다.
    scrollToBottom();
    // 방금 화면에 붙은 입력칸이라 그냥 포커스를 주면 한글 조합이 안 보인다 (Ime 주석 참고)
    Platform.runLater(() -> Ime.focus(input));
  }

  /**
   * 마도카랑 나눈 얘기를 통째로 지운다. 기본 화면에 "채팅 삭제" 를 치면 Main 이 여기로 부른다.
   *
   * 화면에 그려둔 말풍선만이 아니라 파일에 남은 대화(chat.json)와 접어둔 기억(memo.json)까지
   * 같이 비운다. 말풍선만 걷어내면 마도카는 지운 얘기를 그대로 기억한 채 말을 이어서,
   * 지운 사람 쪽에서 보면 안 지운 것과 다를 게 없다.
   *
   * 답을 기다리는 중에 지울 수도 있다 (답이 늦어서 Esc 로 나왔다가 지우는 경우). era 를 한 칸
   * 올려두면 뒤늦게 온 답도, 돌던 요약도 제 발로 물러난다 — era 주석 참고.
   *
   * 되돌릴 수는 없지만 따로 묻지는 않는다. 치는 것만으로는 안 지워지고 목록에서 한 번 더
   * 골라야 실행되니, 확인 한 번은 이미 거치는 셈이라서.
   */
  public void clearChat() {
    era++;

    lines.clear();
    store.save(lines);

    memo = "";
    foldedUpTo = 0;
    memory.save(memo, foldedUpTo);

    asking = false;
    folding = false;
    pending = false;
    // 지워버린 말이 "안 읽은 말" 로 남아 배지가 안 꺼지는 일이 없게
    setUnread(0);
    hideWaiting();
    // 다시 들어오면 처음 만난 것처럼 인사부터 (enter 참고)
    greeted = false;
    render();
  }

  /** 지금 입혀둔 성격문 — 고쳐 쓴 게 없으면 기본값. "채팅 프롬프트" 창이 이걸 채워서 연다 */
  public String prompt() {
    return customPersona.isBlank() ? PERSONA : customPersona;
  }

  /**
   * 성격문을 갈아끼운다. 비우거나 기본값과 똑같이 두면 고친 게 없는 것으로 친다 —
   * 나중에 코드에서 기본 성격문을 다듬었을 때 그대로 따라가게.
   *
   * 다음 답변부터 바로 먹는다. 대화나 접어둔 기억은 안 건드린다.
   */
  public void setPrompt(String text) {
    String trimmed = text == null ? "" : text.strip();
    customPersona = trimmed.equals(PERSONA.strip()) ? "" : trimmed;
    prompt.save(customPersona);
  }

  /** Esc: 수다 화면을 접고 기본 화면으로. 포커스가 이 화면 안 어디에 있든 같다 */
  private void handleExit(KeyEvent e) {
    if (e.getCode() == KeyCode.ESCAPE) {
      e.consume();
      onExit.run();
    }
  }

  private void handleKey(KeyEvent e) {
    if (e.getCode() == KeyCode.ENTER) {
      e.consume();
      send();
      return;
    }
    // 빈 입력칸에서 맨손 ← : 기본 화면으로 나간다. 수다 화면은 기본 화면 오른쪽에 있어서
    // 들어올 때 누른 → 를 거꾸로 누르면 그대로 되돌아 나가는 셈이다.
    // 친 게 있을 땐 ← 가 평소대로 캐럿 옮기기 — 그땐 Alt 를 같이 눌러야 나간다 (Main 과 같은 규칙).
    if (e.getCode() == KeyCode.LEFT && (e.isAltDown() || input.getText().isEmpty())) {
      e.consume();
      onExit.run();
    }
  }

  /** 친 걸 말풍선으로 올리고 마도카한테 넘긴다 */
  private void send() {
    String text = input.getText().trim();
    if (text.isEmpty() || asking)
      return;

    input.clear();
    addLine(text, true);
    pending = true;
    ask();
  }

  /**
   * 이 입력칸이 포커스를 받을 때마다 — 창을 다시 부르거나, 마도카를 눌러 들어오거나,
   * 딴 데 갔다 돌아왔을 때. 할 일이 없으면 아무 일도 안 일어난다.
   *
   * 못 보낸 내 말이 먼저다. 그걸 놔두고 마도카가 딴 말을 먼저 꺼내면 내가 친 말은 영영
   * 답 없이 묻히고, 답이 오면 그 줄에 시각이 새로 찍혀서 뜸해진 것도 어차피 풀린다.
   */
  private void onFocus() {
    if (pending) {
      retryPending();
      return;
    }
    maybeNudge();
  }

  /** 답을 못 받고 남은 말이 있으면 다시 던진다. 없으면 아무 일도 안 일어난다 */
  private void retryPending() {
    if (pending && !asking)
      ask();
  }

  /**
   * 최근 몇 줄과 접어둔 기억을 넘기고 답을 기다린다.
   * 실패는 화면에 안 남긴다 — pending 을 켜둔 채 물러나면 다음 포커스 때 여기로 다시 온다.
   */
  private void ask() {
    if (lines.isEmpty())
      return;
    request(window());
  }

  /**
   * 마도카한테 넘기고 답을 말풍선으로 올리는 자리. 내 말에 답하는 것(ask)도, 뜸해져서
   * 먼저 말을 거는 것(nudge)도 결국 여기로 모인다 — 건네는 목록만 다르고 나머지는 같다.
   */
  private void request(List<LlmClient.Message> snapshot) {
    if (asking || snapshot.isEmpty())
      return;

    if (!llm.isReady()) {
      // 키가 없는 것도 실패로 친다. 여기서 안내를 띄우면 대화창이 설명서가 돼버려서,
      // 길게 물어보는 LLM 모드(←)에 맡기고 여기선 조용히 접는다.
      return;
    }

    String persona = persona();
    int startedIn = era;
    asking = true;
    showWaiting();

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.chat(persona, snapshot, LlmClient.Mode.FAST);
      }
    };

    task.setOnSucceeded(e -> {
      // 기다리는 사이에 대화를 지웠으면 이 답은 갈 데가 없다 (정리는 clearChat 이 이미 해뒀다)
      if (startedIn != era)
        return;
      List<String> bubbles = split(task.getValue());
      if (bubbles.isEmpty()) {
        // 내용 없이 빈 답이 온 경우. 빈 말풍선을 올려봐야 대화만 끊겨 보이니
        // 실패했을 때와 똑같이 pending 을 켜둔 채 물러난다 (다음 포커스 때 다시 던진다).
        asking = false;
        hideWaiting();
        return;
      }
      pending = false;
      // asking 은 마지막 말풍선이 올라갈 때까지 켜둔다 — 마도카가 아직 말하는 중에
      // 내 말이 끼어들면 주고받은 순서가 엉켜서.
      say(bubbles, 0, startedIn);
    });

    task.setOnFailed(e -> {
      // 말없이 물러난다. pending 은 켜둔 채라 다음 포커스 때 또 던진다.
      if (startedIn != era)
        return;
      asking = false;
      hideWaiting();
    });

    Thread worker = new Thread(task, "madoka-chat");
    worker.setDaemon(true); // 답 기다리는 중에 위젯 꺼도 프로세스가 안 남게
    worker.start();
  }

  /**
   * 마지막으로 오간 말이 NUDGE_AFTER_MS 보다 오래됐으면, 내가 아무것도 안 쳤는데
   * 마도카가 먼저 한마디 건네게 한다.
   *
   * 뜸해진 길이는 화면에 그려져 있는 마지막 줄에 붙은 시각으로 잰다. 그 시각은 chat.json 에
   * 같이 남으니 껐다 켠 뒤에도 그대로고, 그래서 "위젯을 얼마나 안 켰나" 가 아니라 정말로
   * "말이 얼마나 안 오갔나" 로 걸린다.
   *
   * 한 번 걸리고 나면 방금 건 말이 맨 아래 줄이 되면서 뜸한 시간이 0 으로 돌아간다 —
   * 포커스를 몇 번 더 받아도 다시 그만큼 조용해지기 전엔 또 말을 걸지 않는다.
   * 답을 못 받고 물러난 경우(실패·빈 답)에는 줄이 안 늘어서 다음 포커스 때 다시 걸린다.
   *
   * 아직 한 줄도 없으면 여기 올 일이 없다 — 그건 처음 열었을 때의 인사(GREETING) 몫이다.
   */
  private void maybeNudge() {
    if (asking || lines.isEmpty())
      return;

    ChatHistory.Line last = lines.get(lines.size() - 1);
    long idle = System.currentTimeMillis() - last.at;
    if (idle < NUDGE_AFTER_MS)
      return;

    nudge(idle, last.mine);
  }

  /**
   * 먼저 말을 걸어달라고 한 번 부른다.
   *
   * 평소 답변과 다른 건 맨 뒤에 붙는 안내 한 덩어리뿐이다. 앞에 오는 건 늘 쓰던 그 창
   * (window) 과 접어둔 기억(persona) 이라, 하던 얘기를 하나도 안 지운 채로 그 위에서
   * 말이 이어진다 — 오래 조용했다고 대화를 처음부터 다시 시작하지는 않는다.
   *
   * 안내는 chat.json 에 안 남긴다. 화면에 안 보이는 말이 기록에 끼면 다음번 window() 에
   * 딸려 들어가서, 조용하지도 않은데 "오랜만이야" 를 또 하게 된다.
   */
  private void nudge(long idleMs, boolean lastWasMine) {
    List<LlmClient.Message> snapshot = window();
    snapshot.add(new LlmClient.Message(true, nudgePrompt(idleMs, lastWasMine)));
    request(snapshot);
  }

  /**
   * 먼저 말을 걸라고 끼워 넣는 안내. 내가 친 말인 척 user 쪽에 붙지만 화면에도 기록에도 안 남는다.
   *
   * 마지막 말이 누구 것이었냐에 따라 할 말이 갈린다. 내 말이 마지막이면 답을 못 받고 끊긴
   * 자리라 그 말부터 받아줘야 하고, 마도카 말이 마지막이면 먼저 걸었는데 내가 답을 안 한
   * 자리라 같은 말을 되풀이하면 안 된다 — 후자를 안 갈라주면 아까 한 인사를 글자 그대로
   * 다시 해서, 말을 건 게 아니라 고장 난 것처럼 보인다.
   */
  private static String nudgePrompt(long idleMs, boolean lastWasMine) {
    long hours = Math.max(1, idleMs / (60 * 60 * 1000L));
    String situation = lastWasMine
        ? "상대가 마지막으로 한 말에 네가 아직 답을 못 했어. 늦었지만 그 말부터 받아주면서 말을 걸어."
        : "네가 마지막으로 건 말에 상대는 아직 답이 없어. 아까 한 말을 그대로 되풀이하지 말고, 결을 바꿔서 한 번 더 걸어봐.";

    return """
        (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
        말이 오간 지 %d시간쯤 됐고, 방금 상대가 수다 화면을 다시 열었어.
        %s
        지금까지 나눈 얘기는 그대로 기억한 채로, 오랜만이라는 티만 자연스럽게 내면서
        네가 먼저 한마디 건네줘. 무슨 일 있었냐고 캐묻지는 말고 가볍게.
        """.formatted(hours, situation);
  }

  /**
   * 쪼개진 답을 한 덩어리씩 차례로 올린다 (index 번째부터, 다음 것은 뜸 들였다 제 발로 이어진다).
   *
   * 남은 말풍선이 있는 동안에는 '…' 를 도로 띄워둔다 — 실제로 아직 할 말이 남은 거라
   * 기다리는 표시가 맞고, 안 띄우면 대화가 끝난 줄 알고 말을 걸게 된다.
   * 다 올리고 나서야 asking 을 내리고, 그때 한 번만 접기를 돌아본다.
   *
   * 뜿 들이는 사이에 대화를 지울 수도 있어서, 출발할 때의 era 를 끝까지 들고 다닌다 —
   * 달라졌으면 남은 말풍선은 그냥 삼킨다.
   */
  private void say(List<String> bubbles, int index, int startedIn) {
    if (startedIn != era)
      return;

    hideWaiting();
    addLine(bubbles.get(index), false);

    if (index + 1 >= bubbles.size()) {
      asking = false;
      // 답이 붙어서 창 밖으로 밀려난 말이 생겼을 수 있다. 수다가 끊긴 이 틈에 접어둔다.
      maybeFold();
      return;
    }

    showWaiting();
    String next = bubbles.get(index + 1);
    PauseTransition gap = new PauseTransition(Duration.millis(
        Math.min(GAP_MAX_MS, GAP_BASE_MS + next.length() * GAP_PER_CHAR_MS)));
    gap.setOnFinished(e -> say(bubbles, index + 1, startedIn));
    gap.play();
  }

  /**
   * 한 번에 받아온 답을 말풍선 단위로 쪼갠다. 줄바꿈 하나가 곧 새 말풍선이다 (성격문에서 그렇게 시켜뒀다).
   *
   * 시킨 것보다 잘게 쪼개 보내는 날도 있어서 MAX_BUBBLES 개까지만 나누고 나머지는 마지막에 붙인다 —
   * 말풍선이 다섯 개씩 쏟아지느니 마지막 하나가 길어지는 편이 낫다. 어차피 모델한테 되돌려 보낼 때는
   * 연달아 한 말이 한 덩어리로 합쳐져서 (GeminiClient.merge), 몇 개로 갈랐든 대화는 그대로 이어진다.
   */
  private static List<String> split(String raw) {
    List<String> bubbles = new ArrayList<>();
    if (raw == null)
      return bubbles;

    for (String piece : raw.strip().split("\n")) {
      String trimmed = piece.strip();
      if (trimmed.isEmpty())
        continue;
      if (bubbles.size() < MAX_BUBBLES)
        bubbles.add(trimmed);
      else
        bubbles.set(bubbles.size() - 1, bubbles.get(bubbles.size() - 1) + "\n" + trimmed);
    }
    return bubbles;
  }

  /** 오간 말 한 줄. 화면에 올리고 파일에 남긴다 */
  private void addLine(String text, boolean mine) {
    lines.add(new ChatHistory.Line(mine, text, System.currentTimeMillis()));
    store.save(lines);
    render();
    // 이 화면 밖에서 올라온 마도카 말은 아직 아무도 못 봤다 — 그림 옆에 표시를 띄워둔다
    if (!mine && !reading())
      setUnread(unread + 1);
  }

  /**
   * 지금 이 화면을 보고 있는지. 기본 화면으로 나가면 root 에서 떨어져 나가 씬이 없어져서
   * (Main 의 showHome 참고) 씬이 붙어 있는지만 보면 된다.
   *
   * 나가 있는 사이에도 말은 올라온다 — 답이 늦게 와서 말풍선이 하나씩 붙는 중에 Esc 로
   * 나가버린 경우다. 그렇게 뒤늦게 붙은 말이 곧 '안 읽은 말' 이 된다.
   *
   * 창을 숨긴 건 안 가린다. 수다 화면을 켜 둔 채 숨겼다 다시 부르면 포커스가 이 입력칸으로
   * 돌아오면서 화면도 그대로 눈앞에 뜨니까 (Main 의 bringToFront), 배지를 거칠 일이 없다.
   */
  private boolean reading() {
    return getScene() != null;
  }

  /** 안 읽은 수를 고쳐 쓰고 Main 한테 알린다. 달라진 게 없으면 조용히 넘어간다 */
  private void setUnread(int count) {
    if (unread == count)
      return;
    unread = count;
    onUnread.accept(unread);
  }

  // ── 기억 ───────────────────────────────────────────────────────────────────
  // 모델한테 건네는 건 두 덩어리다: 성격문 뒤에 붙는 메모(접어둔 옛날 얘기)와
  // 원문 그대로 가는 최근 티키타카 WINDOW_TURNS 번 분. 대화가 아무리 길어져도 보내는 양이 여기서 멈춘다.

  /** 성격 + 지금까지 접어둔 기억. 메모가 비었으면 성격만 간다 */
  private String persona() {
    String base = prompt();
    if (memo.isBlank())
      return base;
    // 굳이 먼저 꺼내 말하라고 하면 "너 고양이 키우잖아!" 를 인사처럼 하게 돼서 일러둔다
    return base.stripTrailing() + "\n" + """

        [기억해 둔 것]
        예전에 나눈 얘기에서 추려둔 거야. 알고만 있고, 묻지도 않았는데 먼저 꺼내진 마.
        """ + memo;
  }

  /** 원문 그대로 들려줄 몫 — 티키타카 WINDOW_TURNS 번 분. 그 앞은 메모가 대신한다 */
  private List<LlmClient.Message> window() {
    List<LlmClient.Message> out = new ArrayList<>();
    for (int i = startOfLastTurns(WINDOW_TURNS); i < lines.size(); i++) {
      ChatHistory.Line line = lines.get(i);
      out.add(new LlmClient.Message(line.mine, line.text));
    }
    return out;
  }

  /**
   * 뒤에서부터 내가 친 말 turns 개를 품는 첫 줄 자리. 내가 그만큼 말한 적이 없으면 0 (통째로).
   *
   * 내 말을 기준점으로 잡으면 그 뒤에 붙은 마도카 말풍선은 몇 개든 같이 딸려 들어간다 —
   * 한 번의 티키타카를 중간에서 자르지 않으려는 것. 답만 반쪽 남으면 무슨 말에 대한
   * 답인지가 창 밖으로 나가버려서 문맥이 되레 헷갈린다.
   */
  private int startOfLastTurns(int turns) {
    int seen = 0;
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).mine && ++seen == turns)
        return i;
    }
    return 0;
  }

  /** 이 줄들 중 내가 친 게 몇 개인지 — 쌓인 양을 티키타카 횟수로 셀 때 쓴다 */
  private static int turnsIn(List<ChatHistory.Line> pile) {
    int mine = 0;
    for (ChatHistory.Line line : pile) {
      if (line.mine)
        mine++;
    }
    return mine;
  }

  /**
   * 창 밖으로 밀려났는데 아직 메모에 못 들어간 말들. 오래된 것부터.
   *
   * 줄 번호가 아니라 시각으로 가르는 건 chat.json 이 300줄에서 앞을 잘라내기 때문 —
   * 껐다 켜면 번호는 밀려도 줄에 붙은 시각은 그대로라 같은 걸 또 요약하지 않는다.
   */
  private List<ChatHistory.Line> unfolded() {
    List<ChatHistory.Line> out = new ArrayList<>();
    int edge = startOfLastTurns(WINDOW_TURNS);
    for (int i = 0; i < edge; i++) {
      if (lines.get(i).at > foldedUpTo)
        out.add(lines.get(i));
    }
    return out;
  }

  /**
   * 밀려난 말이 티키타카 FOLD_TURNS 번 분만큼 쌓였으면 메모를 다시 써달라고 한 번 부른다.
   *
   * 답변과 같은 FAST 모드로 부르지만 사람을 기다리게 하는 호출이 아니라서 화면에는
   * 아무 표시도 안 한다. 실패하면 foldedUpTo 를 안 옮기고 물러나니까, 다음 답변 뒤에
   * 같은 자리에서 다시 시도한다 — 실패한 내 말을 pending 으로 들고 있는 것과 같은 결.
   */
  private void maybeFold() {
    if (folding || !llm.isReady())
      return;

    List<ChatHistory.Line> pile = unfolded();
    if (turnsIn(pile) < FOLD_TURNS)
      return;
    if (pile.size() > FOLD_MAX)
      pile = pile.subList(0, FOLD_MAX);

    // 접기에 성공했을 때 여기까지 들어갔다고 찍어둘 시각
    long mark = pile.get(pile.size() - 1).at;
    String prompt = foldPrompt(memo, pile);
    int startedIn = era;
    folding = true;

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.chat(FOLD_PERSONA, List.of(new LlmClient.Message(true, prompt)), LlmClient.Mode.FAST);
      }
    };

    task.setOnSucceeded(e -> {
      // 요약하는 사이에 대화를 지웠으면, 지운 얘기를 메모로 되살리는 꼴이라 버린다
      if (startedIn != era)
        return;
      folding = false;
      String folded = clean(task.getValue());
      // 빈 답이 오면 있던 기억까지 날아가니까 그때는 그냥 하던 걸 들고 간다
      if (folded.isBlank())
        return;
      memo = folded;
      foldedUpTo = mark;
      memory.save(memo, foldedUpTo);
    });

    // 말없이 물러난다. foldedUpTo 를 안 옮겼으니 다음 답변 뒤에 또 접어본다.
    task.setOnFailed(e -> {
      if (startedIn == era)
        folding = false;
    });

    Thread worker = new Thread(task, "madoka-memo");
    worker.setDaemon(true);
    worker.start();
  }

  /** 메모를 다시 써달라고 시키면서 건네는 말 — 지금 메모와, 새로 접어 넣을 대화 */
  private static String foldPrompt(String memo, List<ChatHistory.Line> pile) {
    StringBuilder sb = new StringBuilder();
    sb.append("""
        [지금 메모] 에 [새로 오간 말] 에서 알게 된 걸 접어 넣고, 갱신된 메모 전문을 돌려줘.
        다 합쳐 %d줄을 넘기지 마. 넘치면 덜 중요한 것부터 버려.

        [지금 메모]
        """.formatted(MEMO_LINES));
    sb.append(memo.isBlank() ? "(아직 없음)" : memo);
    sb.append("\n\n[새로 오간 말]\n");
    for (ChatHistory.Line line : pile)
      sb.append(line.mine ? "상대: " : "마도카: ").append(line.text).append("\n");
    return sb.toString();
  }

  /**
   * 시킨 대로 메모만 오면 좋겠지만 "알겠어!" 나 코드블록을 얹어 보낼 때가 있다.
   * 쓸 만한 줄만 남기고 줄 수도 여기서 한 번 더 자른다 — 프롬프트로만 막으면 언젠가 샌다.
   */
  private static String clean(String raw) {
    if (raw == null)
      return "";
    List<String> kept = new ArrayList<>();
    for (String line : raw.strip().split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("```"))
        continue;
      kept.add(trimmed);
      if (kept.size() >= MEMO_LINES)
        break;
    }
    return String.join("\n", kept);
  }

  /**
   * 말풍선을 처음부터 다시 그린다. 한 줄 늘 때마다 통째로 그리는 게 낭비 같아 보여도,
   * 날짜 구분선이며 "이 줄에 시각을 붙일지" 가 앞뒤 줄을 봐야 정해지는 것들이라
   * 한 줄만 끼워 넣는 길을 따로 두면 금세 어긋난다. 길어야 수백 줄이라 값도 싸다.
   */
  private void render() {
    log.getChildren().clear();

    LocalDate drawnDay = null;
    for (int i = 0; i < lines.size(); i++) {
      ChatHistory.Line line = lines.get(i);
      LocalDateTime when = when(line);

      if (!when.toLocalDate().equals(drawnDay)) {
        drawnDay = when.toLocalDate();
        log.getChildren().add(dateMark(drawnDay));
      }
      log.getChildren().add(bubble(line.text, line.mine, endsGroup(i) ? when : null));
    }

    // 기다림 표시는 늘 맨 아래
    if (waitingRow != null)
      log.getChildren().add(waitingRow);

    scrollToBottom();
  }

  /**
   * 이 줄에 시각을 붙일지. 같은 사람이 같은 분에 잇따라 한 말은 맨 끝 줄에만 붙인다 —
   * 카카오톡이 하는 대로, 줄마다 시각이 붙어 어지러워지지 않게.
   */
  private boolean endsGroup(int index) {
    if (index == lines.size() - 1)
      return true;

    ChatHistory.Line line = lines.get(index);
    ChatHistory.Line next = lines.get(index + 1);
    if (line.mine != next.mine)
      return true;
    return !when(line).truncatedTo(ChronoUnit.MINUTES)
        .equals(when(next).truncatedTo(ChronoUnit.MINUTES));
  }

  private LocalDateTime when(ChatHistory.Line line) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(line.at), ZoneId.systemDefault());
  }

  private void showWaiting() {
    if (waitingRow != null)
      return;
    waitingRow = bubble("…", false, null);
    log.getChildren().add(waitingRow);
    scrollToBottom();
  }

  private void hideWaiting() {
    if (waitingRow == null)
      return;
    log.getChildren().remove(waitingRow);
    waitingRow = null;
  }

  /**
   * 말풍선 한 줄. 내 말이면 왼쪽, 마도카 말이면 오른쪽에 붙인다.
   * 시각(at)은 말풍선 안쪽 — 내 말은 오른쪽, 마도카 말은 왼쪽 — 에 아래를 맞춰 작게 붙는다.
   * null 이면 시각 없이 말풍선만 (같은 분에 이어지는 줄, 그리고 기다림 표시).
   */
  private HBox bubble(String text, boolean mine, LocalDateTime at) {
    Label label = new Label(text);
    label.setWrapText(true);
    label.getStyleClass().addAll("chat-bubble", mine ? "chat-mine" : "chat-theirs");
    // 창 폭이 변해도 말풍선이 한쪽을 다 먹지 않게
    label.maxWidthProperty().bind(scroll.widthProperty().multiply(0.7));

    HBox row = new HBox(4);
    // 여러 줄짜리 말풍선 옆에서 시각이 가운데 뜨지 않게, 아래를 맞춘다
    row.setAlignment(mine ? Pos.BOTTOM_LEFT : Pos.BOTTOM_RIGHT);

    if (at == null) {
      row.getChildren().add(label);
      return row;
    }

    Label time = new Label(TIME.format(at));
    time.getStyleClass().add("chat-time");
    if (mine)
      row.getChildren().addAll(label, time);
    else
      row.getChildren().addAll(time, label);
    return row;
  }

  /** 날짜가 바뀌는 자리에 끼우는 구분선 */
  private HBox dateMark(LocalDate day) {
    Label label = new Label(DATE.format(day));
    label.getStyleClass().add("chat-date");
    HBox row = new HBox(label);
    row.setAlignment(Pos.CENTER);
    row.setPadding(new Insets(6, 0, 2, 0));
    return row;
  }

  /**
   * 맨 아래로 내린다. 마도카 말이 한 줄 붙을 때마다 여기로 온다.
   *
   * 방금 넣은 말풍선은 아직 제 높이가 안 잡혀 있다. 그 자리에서 vvalue 만 올려두면
   * 늘어나기 전 높이를 기준으로 끝까지 간 셈이 돼서, 배치가 돌고 나면 새로 붙은
   * 말풍선만큼이 아래에 남는다 — 긴 답이 올수록 더 많이 남아서, 정작 읽어야 할
   * 마지막 말이 화면 밖으로 밀린다.
   *
   * 그래서 한 박자 재운 뒤(runLater) 그 자리에서 배치를 강제로 돌려(applyCss/layout)
   * 늘어난 높이를 먼저 확정하고, 그다음에 내린다.
   */
  private void scrollToBottom() {
    Platform.runLater(() -> {
      scroll.applyCss();
      scroll.layout();
      scroll.setVvalue(1.0);
    });
  }
}
