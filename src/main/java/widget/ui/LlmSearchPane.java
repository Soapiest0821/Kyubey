package widget.ui;

import widget.llm.LlmClient;
import widget.voice.Dictation;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 왼쪽 화살표로 들어오는 여러 줄 LLM 검색 모드.
 *
 * Shift+Enter 로 물어보고, Esc 나 빈 입력창에서 → 로 원래 한 줄 검색으로 돌아간다.
 * (→ 는 들어올 때 누른 ← 의 반대 방향이다. 친 게 있을 땐 Alt+→.)
 * (여기선 Enter 가 줄바꿈이어야 해서 보내는 건 Shift+Enter 로 뺐음)
 * 물어보는 동안은 답이 올 때까지 입력창을 잠가둔다.
 *
 * 오른쪽 위 스위치로 일반/빠른 답변을 고를 수 있다. 기본은 빠른 답변.
 *
 * 답은 MarkdownView 로 그리는데, 그리고 나면 드래그 선택이 안 되니까
 * 마크다운 영역에서 마우스 오른쪽 버튼을 누르면 바로 원문이 클립보드로 복사된다.
 */
public class LlmSearchPane extends VBox {

  /** 마도카가 선 세로줄과 답 사이에 두는 틈. 그림에 딱 붙으면 글이 그림을 타는 것처럼 보인다 */
  private static final double MASCOT_GAP = 16;

  private final LlmClient llm;
  private final Runnable onExit;

  private final TextArea promptArea = new TextArea();
  private final MarkdownView answerView = new MarkdownView();
  private final Label statusLabel = new Label();
  private final ToggleButton modeToggle = new ToggleButton();

  /** 답 기다리는 중에 또 보내는 것 막기 */
  private boolean asking = false;

  public LlmSearchPane(LlmClient llm, Runnable onExit) {
    this.llm = llm;
    this.onExit = onExit;

    setSpacing(8);
    setPadding(new Insets(20));
    setAlignment(Pos.TOP_LEFT);

    Label header = new Label("무엇이든 물어봐~");
    header.getStyleClass().add("llm-header");

    // 눌러도 포커스를 안 가져가게 해둔다 (Esc/Shift+Enter 는 입력창이 받아야 해서)
    modeToggle.setFocusTraversable(false);
    modeToggle.getStyleClass().add("llm-mode");
    modeToggle.setSelected(true); // 기본은 빠른 답변
    updateModeLabel();
    modeToggle.selectedProperty().addListener((obs, was, is) -> {
      updateModeLabel();
      if (llm.isReady())
        showStatus(modelLine(), false);
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox headerRow = new HBox(8, header, spacer, modeToggle);
    headerRow.setAlignment(Pos.CENTER_LEFT);

    promptArea.setPromptText("길게 물어봐~");
    promptArea.setWrapText(true);
    promptArea.setPrefRowCount(3);
    promptArea.getStyleClass().add("llm-prompt");

    answerView.setVisible(false);
    answerView.setManaged(false);
    VBox.setVgrow(answerView, Priority.ALWAYS);

    statusLabel.getStyleClass().add("llm-status");
    statusLabel.setWrapText(true);
    statusLabel.setVisible(false);
    statusLabel.setManaged(false);

    getChildren().addAll(headerRow, promptArea, statusLabel, answerView);

    // 오른쪽 클릭 = 메뉴 없이 바로 복사
    answerView.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
      e.consume();
      copyAnswer();
    });

    promptArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
    answerView.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

    // 빈 질문칸에서 스페이스를 누르고 있으면 받아쓰기 (Dictation 주석 참고).
    // 받아 적은 말은 올려만 두고 안 보낸다 — 길게 물어보는 자리라 말한 김에 손으로 더
    // 붙여 치는 게 보통이고, 잘못 알아들었으면 고쳐서 Shift+Enter 를 치면 된다.
    Dictation.arm(promptArea, new Dictation.Ear() {
      @Override
      public void status(String message) {
        if (message == null)
          showDefaultStatus();
        else
          showStatus(message, false);
      }

      @Override
      public void heard(String text) {
        Dictation.fill(promptArea, text);
      }
    });
  }

  /**
   * 마도카가 서 있는 오른쪽 세로줄만큼 답 보여주는 칸을 좌로 물린다. 창 오른쪽 끝에서
   * 그림 왼쪽 끝까지가 얼마인지(그림 너비 + 그림이 오른쪽에서 띄워 선 거리)를 Main 이 재서 부른다
   * (수다 화면의 reserveRight 와 같은 이치).
   *
   * 머릿말과 입력칸은 그대로 둔다 — 둘 다 화면 위쪽이라 그림에 안 걸리고, 길게 치는 칸은
   * 넓을수록 좋아서. 그림 밑까지 내려오는 건 답뿐이라 여기만 줄여도 가려지지 않는다.
   */
  public void reserveRight(double column) {
    VBox.setMargin(answerView, new Insets(0, Math.max(0, column) + MASCOT_GAP, 0, 0));
  }

  private void handleKey(KeyEvent e) {
    if (e.getCode() == KeyCode.ESCAPE) {
      e.consume();
      onExit.run();
      return;
    }
    // 빈 입력창에서 맨손 → : 한 줄 검색으로 돌아간다. 이 모드는 기본 화면 왼쪽에 있어서
    // 들어올 때 누른 ← 를 거꾸로 누르면 그대로 되돌아 나가는 셈이다.
    // 친 게 있을 땐 → 가 평소대로 캐럿 옮기기 — 그땐 Alt 를 같이 눌러야 나간다 (Main 과 같은 규칙).
    if (e.getCode() == KeyCode.RIGHT && (e.isAltDown() || promptArea.getText().isEmpty())) {
      e.consume();
      onExit.run();
      return;
    }
    if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
      e.consume();
      ask();
    }
  }

  private LlmClient.Mode mode() {
    return modeToggle.isSelected() ? LlmClient.Mode.FAST : LlmClient.Mode.NORMAL;
  }

  private void updateModeLabel() {
    modeToggle.setText(mode().label() + " 답변");
  }

  private String modelLine() {
    return "모델: " + llm.modelName(mode());
  }

  /** 그려진 답 말고 받은 마크다운 원문을 복사한다 (붙여넣으면 서식이 살아있게) */
  private void copyAnswer() {
    String answer = answerView.getSource();
    if (answer.isBlank())
      return;

    ClipboardContent content = new ClipboardContent();
    content.putString(answer);
    Clipboard.getSystemClipboard().setContent(content);
    showStatus("답 복사했어!", false);
  }

  /** 한 줄 검색창에 치던 걸 그대로 들고 들어온다 */
  public void enterWith(String carriedText) {
    promptArea.setText(carriedText == null ? "" : carriedText);
    promptArea.positionCaret(promptArea.getLength());
    hideAnswer();
    showDefaultStatus();
    // 방금 화면에 붙은 입력창이라 그냥 포커스를 주면 한글 조합이 안 보인다 (Ime 주석 참고)
    Platform.runLater(() -> Ime.focus(promptArea));
  }

  /**
   * 아무 일도 없을 때 띄워두는 상태 한 줄 — 쓸 수 있으면 모델 이름, 아니면 왜 못 쓰는지.
   * 받아쓰기가 잠깐 자기 얘기("듣는 중…")를 띄웠다가도 여기로 돌아온다.
   */
  private void showDefaultStatus() {
    if (llm.isReady())
      showStatus(modelLine(), false);
    else
      showStatus(llm.unavailableReason(), true);
  }

  private void ask() {
    if (asking)
      return;

    String prompt = promptArea.getText().trim();
    if (prompt.isEmpty())
      return;

    if (!llm.isReady()) {
      showStatus(llm.unavailableReason(), true);
      return;
    }

    LlmClient.Mode mode = mode();
    setLocked(true);
    showStatus("생각 중...", false);

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.ask(prompt, mode);
      }
    };

    task.setOnSucceeded(e -> {
      setLocked(false);
      showAnswer(task.getValue());
      showStatus(modelLine(), false);
    });

    task.setOnFailed(e -> {
      setLocked(false);
      Throwable ex = task.getException();
      showAnswer(ex == null ? "알 수 없는 오류" : ex.getMessage());
      showStatus("실패했어 ㅠㅠ", true);
    });

    Thread worker = new Thread(task, "llm-ask");
    worker.setDaemon(true); // 답 기다리는 중에 위젯 꺼도 프로세스가 안 남게
    worker.start();
  }

  /** 답 기다리는 동안은 질문을 못 고치게 잠근다 (Esc 로 나가는 건 그대로 된다) */
  private void setLocked(boolean locked) {
    asking = locked;
    promptArea.setEditable(!locked);
    modeToggle.setDisable(locked);
    promptArea.pseudoClassStateChanged(
        javafx.css.PseudoClass.getPseudoClass("locked"), locked);
  }

  private void showAnswer(String text) {
    answerView.setMarkdown(text);
    answerView.setVisible(true);
    answerView.setManaged(true);
  }

  private void hideAnswer() {
    answerView.clear();
    answerView.setVisible(false);
    answerView.setManaged(false);
  }

  private void showStatus(String text, boolean isError) {
    statusLabel.setText(text);
    statusLabel.pseudoClassStateChanged(
        javafx.css.PseudoClass.getPseudoClass("error"), isError);
    statusLabel.setVisible(true);
    statusLabel.setManaged(true);
  }
}
