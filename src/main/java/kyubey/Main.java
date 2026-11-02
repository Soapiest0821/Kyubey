package kyubey;

import kyubey.capture.Capture;
import kyubey.search.Builtin;
import kyubey.todo.TodoDialog;
import kyubey.todo.TodoPane;
import kyubey.todo.TodoStore;
import kyubey.chat.BasePrompt;
import kyubey.chat.ChatProfile;
import kyubey.search.HistoryManager;
import kyubey.search.MacroManager;
import kyubey.search.SearchResult;
import kyubey.core.PatchNotesDialog;
import kyubey.core.SettingsDialog;
import kyubey.index.FolderMapper;
import kyubey.llm.GeminiClient;
import kyubey.llm.LlmClient;
import kyubey.ui.AppIcon;
import kyubey.search.AppRegisterDialog;
import kyubey.chat.ChatPane;
import kyubey.ui.Fonts;
import kyubey.win.Foreground;
import kyubey.win.GlobalHotkey;
import kyubey.win.WinNotify;
import kyubey.ui.Ime;
import kyubey.ui.Notice;
import kyubey.chat.ChatArchiveDialog;
import kyubey.llm.KeysDialog;
import kyubey.goal.GoalPane;
import kyubey.goal.GoalStore;
import kyubey.notepad.NotepadPane;
import kyubey.notepad.NotepadStore;
import kyubey.chat.PromptDialog;
import kyubey.share.ShareDialog;
import kyubey.ui.WindowDrag;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Main extends Application {

  /**
   * run.bat 이 사는 곳. 작업 폴더에서 못 찾았을 때만 쓰는 뒷길.
   * mvn 으로 띄우면 클래스가 target/classes 에 있으니 거기서 두 칸 올라간 곳이다.
   */
  private static final File PROJECT_DIR = projectDir();

  /** 폴더 이름 → 경로들. 처음 설정에서 고른 폴더를 훑어서 채운다 (FolderMapper) */
  private static final String DIRS_JSON_PATH = FolderMapper.OUTPUT_JSON_PATH;

  /** 빈 입력창에서 ↑ 로 꺼내 볼 최근 명령어 · /top 랭킹을 한 번에 몇 줄까지 보여줄지 */
  private static final int HISTORY_LIMIT = 8;

  /** 마도카를 창 오른쪽·아래 모서리에서 이만큼 띄워 세운다 */
  private static final double MASCOT_MARGIN = 12;

  /** 그림을 세우는 키 */
  private static final double MASCOT_HEIGHT = SettingsDialog.MASCOT_HEIGHT;

  /**
   * 그림이 없어도 자리를 비워둘 때(설정의 reserveMascotSpace) 비우는 너비 —
   * 기본 마도카 그림(485×367)을 MASCOT_HEIGHT 로 세웠을 때의 너비
   */
  private static final double EMPTY_MASCOT_WIDTH = SettingsDialog.MASCOT_WIDTH;

  /** 입력창 밑 할 일 목록 크기와, 창 왼쪽 끝에서 띄우는 거리 */
  private static final double TODO_WIDTH = 280;
  private static final double TODO_HEIGHT = 225;
  private static final double TODO_INSET = 24;

  /** 안 읽은 말 배지를 마도카 그림에서 왼쪽으로 이만큼 떼어 놓는다 */
  private static final double BADGE_GAP = 8;

  /** 입력창 · 결과 목록 너비 — 평소와 전체화면 기본 화면 */
  private static final double INPUT_WIDTH = 350;
  private static final double FULL_INPUT_WIDTH = 520;

  /** 전체화면 기본 화면에서 입력창 둘레로 번지는 빛의 여백 — 이 안을 누르면 안 닫힌다 */
  private static final double GLOW_PAD = 120;

  /** 전체화면 수다 화면에서 대화 칸 너비 — 화면 가운데에 이만큼만 세운다 */
  private static final double FULL_CHAT_WIDTH = 760;

  /** 아무것도 안 하고 있을 때 입력창에 떠 있는 안내 */
  private static final String PROMPT = "여기에 입력하세요";

  /**
   * 배지를 그림 발밑에서 그림 높이의 이만큼 위에 세운다.
   * 얼굴 옆쯤에 떠야 마도카가 말을 건 것처럼 보여서, 가운데보다 조금 위로 잡았다.
   */
  private static final double BADGE_RISE = 0.66;

  private MacroManager macro;
  /** 목표 쪼개기·수다·화면 캡쳐의 글자 읽기가 같이 쓰는 Gemini */
  private LlmClient llm;
  private HistoryManager history;
  private ListView<SearchResult> resultList;
  /** ← 로 들어오는 목표 쪼개기 화면 */
  private GoalPane goalPane;
  /** ↑ 로 들어오는 메모장 */
  private NotepadPane notepadPane;
  private ChatPane chatPane;
  private TextField inputField;
  /** 입력창 밑 오늘 할 일 (이 컴퓨터에 날마다 적어둔다) */
  private TodoPane todoPane;

  /** 수다 상대 말을 윈도우 알림으로 띄우는 곳 (buildChat) */
  private WinNotify notify;

  /** 상대와 상관없이 하나인 말하는 방식 (숨겨둔 기본 프롬프트) */
  private final BasePrompt basePrompt = new BasePrompt(BasePrompt.PATH);

  /** 마도카 그림 너비 + 띄운 거리 — 새로 만든 수다 화면에도 같은 세로줄을 비우게 기억해둔다 (placeBeside) */
  private double chatReserve = -1;

  /** 화면을 통째로 갈아끼우는 자리 — 한 줄 검색 / 목표 쪼개기 / 마도카랑 수다 가 여기서 바뀐다 */
  private BorderPane root;
  /** 기본 화면(한 줄 입력창 + 결과 목록). 모드에서 빠져나오면 이걸 도로 올린다 */
  private VBox topBox;
  /** 지금 올라와 있는 화면의 입력칸. 창을 다시 띄울 때 여기로 포커스를 돌려준다 */
  private Node focusTarget;

  /**
   * 그림 왼쪽에 뜨는 '안 읽은 말 있음' 표시. 그림 위치를 기준으로 선다 (placeBeside 참고).
   */
  private Label unreadBadge;
  /** 마도카가 목표를 쪼개 둔 게 있다는 표시 ("💬 !"). 안 읽은 말 배지 왼쪽에 나란히 선다 */
  private Label goalBadge;
  /** 배지 둘을 나란히 세우는 줄. 그림 옆자리 (placeBeside) 는 이 줄이 잡는다 */
  private HBox badges;
  /** 쪼개 둔 목표와 칸 — 배지나 알림을 누르면 여기로 간다. 없으면 null */
  private String[] splitAt;

  /** 우측 하단 상대 그림. 자리는 늘 있고, 그 상대 그림이 없으면 비어서 숨는다 (refreshMascot) */
  private ImageView mascotView;

  /** 안 읽은 마도카 말 개수. 마도카를 도로 세울 때 배지를 다시 띄울지 여기서 본다 */
  private int unread;

  /** 마도카를 치워둔 참인지. 마도카를 오른쪽 클릭하면 숨고, 도로 세우는 건 "설정" 에서 */
  private boolean mascotHidden;
  /** 목표 맵 · 메모를 크게 펴서 그림을 잠깐 치운 중 (expand) */
  private boolean mascotCovered;

  /** 그림이 없을 때도 오른쪽 아래 자리를 비워둘지 (설정의 reserveMascotSpace) */
  private boolean reserveEmptyMascot;

  /** 위젯 창 제목. 작업 표시줄에 뜨고, Foreground 가 창을 찾을 때도 쓴다 */
  private static final String TITLE = "Kyubey";

  /** Win+Alt+Space 가 실제로 걸렸는지. 못 걸었으면 Esc 를 숨기기로 쓰면 안 된다 */
  private boolean hotkeyReady;

  /**
   * 전체화면으로 쓰는 중인지. 기본 화면 · 수다에선 F11 로, 목표 맵 · 메모에선 크게 펴기로 같이 켜고 끈다 —
   * 그 화면들이 크게 펴졌는지가 곧 이 값이다 (applyFull). 켠 채 숨겼다 다시 부르면 도로 전체화면으로 뜬다
   */
  private boolean fullMode;
  /** applyFull 이 목표 맵 · 메모를 펴고 접는 중 — 그 알림(expand)이 fullMode 를 되건드리지 않게 */
  private boolean syncing;
  /** 창을 숨기는 중 — 숨기면서 전체화면이 풀려도 fullMode 는 그대로 둔다 */
  private boolean hiding;
  /** 기본 화면을 전체화면으로 띄운 중 — 할 일을 감추고, 입력창이 가운데에 박혀 있게 결과 목록 자리를 늘 잡아둔다 */
  private final BooleanProperty homeFull = new SimpleBooleanProperty();
  /** 전체화면 수다라 그림을 끌어 옮길 수 있는 중. 끌어둔 자리는 거기서 나오면 되돌린다 */
  private boolean mascotFloating;
  /** 마지막으로 fullMode 를 바꾼 때 (System.nanoTime) — 포커스로 전체화면을 켤 때 본다 */
  private long fullChangedAt = System.nanoTime() - 1_000_000_000L;

  @Override
  public void start(Stage stage) {
    // CSS 가 'Pretendard' 를 찾기 전에 폰트부터 올려둔다 (설정에서 껐으면 안 올린다)
    if (SettingsDialog.pretendard())
      Fonts.loadPretendard();
    // 상대 파일이 아직 예전 자리에 있으면 지금 자리로 (ChatProfile)
    ChatProfile.moveOldFiles();

    macro = new MacroManager(DIRS_JSON_PATH);
    history = new HistoryManager("src/main/resources/json/history.json");
    // 처음 켠 날에도 /top 이 빈손이 아니게, 예전부터 세어 둔 top.json 을 한 번 옮겨 깐다
    history.seedFrom(macro.getFrequencyData());

    // 입력창
    inputField = new TextField();
    inputField.setPromptText(PROMPT);
    inputField.setPrefWidth(INPUT_WIDTH);
    inputField.setPrefHeight(45);
    inputField.setStyle("-fx-font-size: 16px;");

    // 결과 리스트
    resultList = new ListView<>();
    resultList.setPrefHeight(200);
    resultList.setMaxWidth(INPUT_WIDTH);
    resultList.setVisible(false);
    // 전체화면 기본 화면에선 안 보일 때도 자리를 잡는다 — 목록이 뜨고 져도 입력창이 가운데에서 안 움직이게
    resultList.managedProperty().bind(resultList.visibleProperty().or(homeFull));

    resultList.setCellFactory(lv -> {
      ListCell<SearchResult> cell = new ListCell<>() {
        @Override
        protected void updateItem(SearchResult item, boolean empty) {
          super.updateItem(item, empty);
          setText(empty ? null : item.getDisplayText());
        }
      };
      cell.setOnMouseEntered(e -> {
        if (!cell.isEmpty()) {
          resultList.getSelectionModel().select(cell.getIndex());
        }
      });
      // 클릭도 Enter 와 똑같이 친다. 빈 줄을 눌렀을 땐 아무 일도 없어야 하고,
      // 클릭하면 포커스가 목록으로 넘어가 다음 타자가 안 먹혀서 입력창으로 도로 돌려준다.
      cell.setOnMouseClicked(e -> {
        if (cell.isEmpty() || e.getButton() != MouseButton.PRIMARY)
          return;
        e.consume();
        activate(stage, cell.getItem());
        Platform.runLater(() -> Ime.focus(inputField));
      });
      return cell;
    });

    // 입력할 때마다 결과 갱신
    inputField.textProperty().addListener((obs, oldText, newText) -> {
      List<SearchResult> results = macro.resolve(newText);
      if (results.isEmpty()) {
        hideResults();
      } else {
        resultList.getItems().setAll(results);
        selectResult(0, 0);
        resultList.setVisible(true);
      }
    });

    // 키 입력 처리
    inputField.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        // 꺼내 본 기록은 창까지 내리지 말고 목록만 닫는다 (입력창이 비어 있을 땐 그것뿐이라 구분이 된다)
        if (resultList.isVisible() && inputField.getText().isBlank()) {
          hideResults();
          e.consume();
          return;
        }
        e.consume();
        escape(stage);
        return;
      }

      // 빈 입력창에서 ↑ : 최근에 쓴 명령어 꺼내 보기. 많이 쓴 순은 /top, 맨손 ↓ 는 메모장 (아래 필터)
      // 글자가 있을 땐 평소대로 목록 오르내리기라, 목록이 닫혀 있고 입력창도 빈 지금만 가로챈다
      if (!resultList.isVisible() && inputField.getText().isBlank() && e.getCode() == KeyCode.UP) {
        showHistory(history.recent(HISTORY_LIMIT), "🕘", false);
        e.consume();
        return;
      }

      if (!resultList.isVisible())
        return;

      int currentIndex = resultList.getSelectionModel().getSelectedIndex();
      int size = resultList.getItems().size();

      if (e.getCode() == KeyCode.DOWN) {
        selectResult(Math.min(currentIndex + 1, size - 1), 2);
        e.consume();
      } else if (e.getCode() == KeyCode.UP) {
        selectResult(Math.max(currentIndex - 1, 0), 1);
        e.consume();
      } else if (e.getCode() == KeyCode.ENTER) {
        activate(stage, resultList.getSelectionModel().getSelectedItem());
        e.consume();
      }
    });

    // 오늘 할 일. 검색 목록이 뜨면 그 자리를 내준다 — 둘 다 입력창 바로 밑에 있어야 하는 것들이라
    todoPane = new TodoPane();
    todoPane.setColors(SettingsDialog.load().todoColors());
    todoPane.setPrefSize(TODO_WIDTH, TODO_HEIGHT);
    todoPane.setMaxSize(TODO_WIDTH, TODO_HEIGHT);
    // 입력창은 가운데지만 할 일은 왼쪽에 붙인다 — 오른쪽 아래엔 마도카가 서 있어서
    HBox todoRow = new HBox(todoPane);
    todoRow.setPadding(new Insets(0, 0, 0, TODO_INSET));
    todoRow.setMaxWidth(Double.MAX_VALUE);
    // 전체화면 기본 화면에선 입력창만 남긴다
    todoRow.visibleProperty().bind(resultList.visibleProperty().not().and(homeFull.not()));
    todoRow.managedProperty().bind(todoRow.visibleProperty());
    // 새로 읽는 건 창에 포커스가 들어올 때만 (처음 켤 때도 포커스가 들어오면서 읽는다).
    // 아침 여섯 시가 지나 전날 못 끝낸 게 넘어왔으면 마도카가 재촉한다 (하루 한 번, ChatPane.remindTodos)
    stage.focusedProperty().addListener((obs, was, is) -> {
      if (!is)
        return;
      todoPane.refresh();
      List<String> overdue = new ArrayList<>();
      for (TodoStore.Todo todo : todoPane.overdue())
        overdue.add(todo.content + " (" + todo.category + ", " + todo.overdueSince + "부터)");
      chatPane.remindTodos(todoPane.day(), overdue);
    });
    // 메인 화면에선 할 일을 적는 동안 말고는 늘 입력창에 포커스가 있어야 한다.
    // 할 일 칸이 닫히면(Esc / Enter / 딴 데 누름) 돌려주되, 그새 다른 할 일 칸이 열렸으면 그대로 둔다
    todoPane.setOnRelease(() -> Platform.runLater(() -> {
      Node owner = inputField.getScene() == null ? null : inputField.getScene().getFocusOwner();
      if (focusTarget == inputField && owner != inputField && !inTodoField(owner))
        Ime.focus(inputField);
    }));
    // 할 일 칸 말고 할 일 자리 아무 데나 누르면 (빈 데, 체크칸, 이름표…) 곧장 입력창으로 — 적던 칸은 닫힌다
    todoPane.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      if (focusTarget != inputField || inTodoField(e.getPickResult().getIntersectedNode()))
        return;
      Platform.runLater(() -> {
        if (!inputField.isFocused())
          Ime.focus(inputField);
      });
    });
    // 할 일 자리 빈 데를 두 번 누르면 할 일 창 (메뉴의 TODO 와 같다)
    todoPane.setOnOpen(() -> {
      TodoDialog.show(stage, todoPane);
      todoPane.refresh();
    });
    // 할 일을 절반 · 다 끝냈을 때, 다 끝낸 뒤 더 적은 걸 끝냈을 때 마도카가 칭찬한다 (TodoPane.milestone, ChatPane.praiseTodo)
    todoPane.setOnDone((todo, milestone) -> chatPane.praiseTodo(todo.content + " (" + todo.category
        + (todo.overdue() ? ", " + todo.overdueSince + "부터 밀려 있던 것" : "") + ")"
        + switch (milestone) {
          case HALF -> " — 이걸로 오늘 할 일을 절반 끝냈어";
          case ALL -> " — 이걸로 오늘 할 일을 다 끝냈어";
          case EXTRA -> " — 오늘 할 일을 다 끝내고 더 적어서 끝낸 거야";
        }));
    // 목표 맵에서 내려온 할 일을 오른쪽 클릭해 "목표로 이동하기" 면 목표 쪼개기 화면의 그 칸으로 간다 (GoalPane.reveal)
    todoPane.setOnGoal((goalId, nodeId) -> {
      showGoals("");
      if (root.getCenter() == goalPane)
        goalPane.reveal(goalId, nodeId);
    });
    // 목표 맵에서 내려온 할 일을 체크하거나 풀면 목표 맵의 그 칸도 끝냄 · 안 끝냄으로 (GoalPane.markDone)
    todoPane.setOnGoalDone((goalId, nodeId, done) -> goalPane.markDone(goalId, nodeId, done));
    // 목표 맵에서 내려온 할 일을 지우면 목표 맵의 그 칸도 할 일에서 빠진 걸로 (GoalPane.dropTodo)
    todoPane.setOnGoalRemove((goalId, nodeId) -> goalPane.dropTodo(goalId, nodeId));
    // 할 일을 오른쪽 클릭해서 내일로 미루면 마도카가 비밀 쪽지로 혼낸다 (ChatPane.scoldPostpone)
    todoPane.setOnPostpone(todo -> chatPane.scoldPostpone(todo.content + " (" + todo.category
        + (todo.overdue() ? ", " + todo.overdueSince + "부터 밀려 있던 것" : "") + ")"));

    // 레이아웃 배치
    topBox = new VBox(10, inputField, resultList, todoRow);
    topBox.setAlignment(Pos.CENTER);
    topBox.setPadding(new Insets(100, 0, 0, 0));

    root = new BorderPane();
    root.setTop(topBox);
    focusTarget = inputField;
    // 전체화면 기본 화면에선 입력창 둘레 빛(topBox) 밖 — 어두운 바탕 — 을 누르면 위젯을 닫는다
    root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      if (!homeFull.get() || e.getTarget() != root)
        return;
      e.consume();
      // 단축키를 못 걸었으면 숨겼다간 못 부른다 — 그땐 Esc 처럼 끄지 말고 그냥 둔다
      if (hotkeyReady)
        hide(stage);
    });

    // ── 화살표 키 ──
    // Alt+→ : 구글 바로 검색
    // Alt+← : 목표 쪼개기 화면으로 전환 (치던 말을 들고 간다)
    // 맨손 ← : 입력창이 완전히 비어 있을 때만 목표 쪼개기로 (옮길 캐럿이 없으니 뺏어도 안전)
    // 맨손 → : 입력창이 완전히 비어 있을 때만 마도카 수다 화면으로 (← 와 같은 이치)
    // 돌아오는 길은 각 화면이 들고 있다 — 목표 쪼개기에서 →, 수다 화면에서 ← 를 누르면 여기로 온다.
    // Alt 를 문지기로 뒀으니 캐럿이 어디 있든 상관없고, 글자가 있을 땐 맨손 ←/→ 가 평소대로 커서 이동.
    // isAltDown() 은 왼쪽/오른쪽 Alt 를 안 가린다. 다만 한글 키보드에서 오른쪽 Alt 는
    // 한/영 키로 먹히는 경우가 많아서, 그럴 땐 왼쪽 Alt 를 써야 한다.
    // 스킨의 기본 동작보다 먼저 가로채야 해서 핸들러 말고 필터로 단다.
    // 두 화면이 키(.env 의 WIZ_*)를 같이 쓰게 클라이언트는 하나만 만들어 나눠준다
    llm = new GeminiClient();
    // 목표 쪼개기는 수다와 따로 논다 — 마도카한테 대화 대신 트리를 보여준다 (GoalPane 주석 참고).
    // 칸을 할 일로 내리면 입력창 밑 할 일이 바로 새로 읽히게 한다
    goalPane = new GoalPane(llm, new GoalStore(GoalStore.PATH), this::showHome, () -> todoPane.refresh());
    // 목표 맵에서 칸을 끝내면 그 칸에서 내려온 할 일도 손으로 체크한 것처럼 끝낸다 (칭찬까지)
    goalPane.setTodoSync(todoPane::finishGoal);
    notepadPane = new NotepadPane(new NotepadStore(NotepadStore.PATH), this::showHome);
    goalPane.setOnExpand(this::expand);
    notepadPane.setOnExpand(this::expand);
    goalPane.setOnEscFull(this::escFull);
    notepadPane.setOnEscFull(this::escFull);
    // 위젯을 안 보고 있을 때 (숨겼거나, 딴 창을 쓰는 중) 상대가 말을 하면 윈도우 알림으로도 띄운다.
    // 눈앞에 있는 창이면 말풍선이나 배지로 이미 보이니 안 띄운다. 알림을 누르면 수다 화면으로 들어온다.
    notify = new WinNotify(TITLE, () -> {
      showChat();
      reveal(stage);
    });
    // 목표 쪼개기는 답이 오래 걸릴 수 있어서, 마도카가 칸을 쪼개 주면 무엇을 무엇무엇으로 쪼갰는지 윈도우 알림으로도 띄운다
    // 그 목표를 펴 놓고 보고 있으면 아무것도 안 띄우고, 아니면 그림 옆에 "💬 !" 배지를 세운다.
    // 윈도우 알림은 위젯을 안 보고 있을 때만. 배지나 알림을 누르면 그 목표의 쪼갠 칸으로 간다
    goalPane.setOnSplit((goalId, nodeId, title, message) -> {
      boolean looking = stage.isShowing() && stage.isFocused() && !stage.isIconified();
      if (looking && root.getCenter() == goalPane && goalPane.showing(goalId))
        return;
      showSplit(new String[] { goalId, nodeId });
      if (!looking)
        notify.show(title, message, () -> {
          openSplit();
          reveal(stage);
        });
    });
    // 수다 상대는 파트너 한 명이고, 수다 화면 위 목록에서 딴 상대로 바꿀 수 있다 (ChatProfile). 나눈 수다는 상대마다 파일에 남겨서,
    // 껐다 켜도 하던 얘기가 그대로 있게 한다.
    chatPane = buildChat(stage, SettingsDialog.load());
    // 언제 들렀는지 ChatPane 이 센다. 한참 뜸했으면 수다 화면에 안 들어와도 창을 부르는 것만으로
    // 상대가 먼저 말을 건다 (배지로 뜬다)
    stage.focusedProperty().addListener((obs, was, is) -> chatPane.widgetFocused(is));

    inputField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      String text = inputField.getText();

      // Ctrl+V 로 그림을 붙여넣으면 구글 렌즈로 (캡쳐의 "검색" 과 같은 길). 글자면 평소대로 붙여넣기
      if (e.getCode() == KeyCode.V && e.isShortcutDown() && !e.isAltDown() && Capture.pasteToLens()) {
        e.consume();
        return;
      }

      if (e.getCode() == KeyCode.TAB) {
        e.consume(); // 안 막으면 포커스가 딴 데로 튄다
        complete();
        return;
      }

      if (e.getCode() == KeyCode.LEFT && (e.isAltDown() || text.isEmpty())) {
        e.consume();
        showGoals(text);
        return;
      }

      // 빈 입력창에서 맨손 ↓ : 메모장으로. 돌아오는 길은 메모장의 ↑ (NotepadPane 참고)
      if (e.getCode() == KeyCode.DOWN && text.isEmpty() && !e.isAltDown() && !resultList.isVisible()) {
        e.consume();
        showNotepad();
        return;
      }

      // 빈 입력창에서 맨손 → : 마도카를 누른 것과 같다 (그림까지 손을 안 옮겨도 되게).
      // Alt+→ 는 아래에서 구글 검색인데, 빈 입력창에선 검색할 말이 없어 어차피 놀던 자리다.
      if (e.getCode() == KeyCode.RIGHT && text.isEmpty()) {
        e.consume();
        showChat();
        return;
      }

      if (!e.isAltDown())
        return;

      if (e.getCode() == KeyCode.RIGHT && !text.isBlank()) {
        e.consume();
        history.record(text);
        macro.execute(new SearchResult("🔍 " + text.trim() + " 검색하기!",
            SearchResult.Type.GOOGLE, text.trim(), null));
        inputField.clear();
        hideResults();
        return;
      }
    });

    // 바탕색은 style.css 의 .widget-root — 거기 있어야 "설정" 의 투명도가 먹는다
    root.getStyleClass().add("widget-root");

    // 우측 하단 마스코트. 창을 끌 때 걸리적거리지 않게 마우스는 그냥 통과시킨다.
    StackPane layers = new StackPane(root);
    layers.setStyle("-fx-background-color: transparent;");
    // 그림 자리는 늘 만들어둔다 — 지금 상대 그림이 없어도 상대가 바뀌면 그 그림이 들어선다
    ImageView mascot = mascot(stage);
    mascotView = mascot;
    StackPane.setAlignment(mascot, Pos.BOTTOM_RIGHT);
    StackPane.setMargin(mascot, new Insets(0, MASCOT_MARGIN, MASCOT_MARGIN, 0));
    layers.getChildren().add(mascot);

    // 안 읽은 말 배지는 그림보다 나중에 얹는다 — 겹치는 자리에서 배지가 위로 오게
    unreadBadge = unreadBadge();
    unreadBadge.managedProperty().bind(unreadBadge.visibleProperty());
    goalBadge = goalBadge();
    badges = new HBox(BADGE_GAP, goalBadge, unreadBadge);
    badges.setAlignment(Pos.BOTTOM_RIGHT);
    // 배지 크기만큼만 잡는다 — 줄이 판 전체로 늘어나 창 끄는 걸 가로채지 않게
    badges.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    badges.setPickOnBounds(false);
    StackPane.setAlignment(badges, Pos.BOTTOM_RIGHT);
    layers.getChildren().add(badges);

    reserveEmptyMascot = SettingsDialog.load().reserveMascotSpace;
    // 수다 화면이 그림 밑에 깔리지 않게 그림이 선 세로줄을 통째로 비우고,
    // 배지는 그 세로줄 왼쪽에 세운다. 그림 너비는 이미지가 다 올라온 뒤에야 잡히니
    // 한 번 재고 끝내면 0 을 집을 수도 있어서, 폭이 잡히거나 바뀔 때마다 다시 잰다.
    placeBeside(mascot);
    mascot.layoutBoundsProperty().addListener((obs, was, is) -> placeBeside(mascot));
    mascot.imageProperty().addListener((obs, was, is) -> placeBeside(mascot));

    // 지난번에 오른쪽 클릭으로 치워뒀으면 숨긴 채로 시작
    hideMascot(SettingsDialog.load().mascotHidden);
    // 수다 화면을 드나들 때마다 그림을 갈아 세운다 — 안에선 얘기하는 상대, 밖에선 파트너
    root.centerProperty().addListener((obs, was, is) -> {
      if (was == chatPane || is == chatPane)
        refreshMascot();
    });

    // Scene & Stage 설정
    Scene scene = new Scene(layers, 600, 400);
    // style.css 를 건다 — "설정" 에서 골라둔 색 테마를 입히고, 투명도만큼 바탕 알파를 곱해서
    SettingsDialog look = SettingsDialog.load();
    SettingsDialog.applyStyle(scene, look.widgetOpacity, look.widgetTheme);
    scene.setFill(Color.TRANSPARENT);

    // 입력칸 밖 ←/→ : 포커스가 입력칸이 아닌 데 (할 일 목록, 맵, 대화 스크롤, 아무 데도 아님…) 있어도
    // 화면을 오간다. 기본 화면 ← 목표 쪼개기, → 수다 / 목표 쪼개기 → 기본 / 수다 ← 기본.
    // 글 칸에 포커스가 있으면 비켜준다 — 거기선 각 칸 필터가 "비었거나 Alt" 규칙으로 따로 받는다.
    // 목표 맵에선 맨손 ←/→ 가 옆 칸 옮기기라 Alt 를 같이 눌러야 한다.
    // 스크롤 같은 안쪽 기본 동작보다 먼저 받아야 해서 씬 필터로 단다.
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      KeyCode code = e.getCode();
      if (code != KeyCode.LEFT && code != KeyCode.RIGHT)
        return;
      Node owner = scene.getFocusOwner();
      if (owner instanceof TextInputControl)
        return;
      if (goalPane.inMap(owner) && !e.isAltDown())
        return;
      Node screen = root.getCenter();
      if (screen == goalPane) {
        if (code == KeyCode.RIGHT) {
          e.consume();
          showHome();
        }
      } else if (screen == chatPane) {
        if (code == KeyCode.LEFT) {
          e.consume();
          chatPane.exit();
        }
      } else if (screen == notepadPane) {
        // 메모장에선 ←/→ 로 딴 화면에 안 간다 — 돌아오는 길은 ↑ 하나
      } else {
        e.consume();
        if (code == KeyCode.LEFT)
          showGoals("");
        else
          showChat();
      }
    });

    // F11 : 기본 화면 · 수다를 전체화면으로 켜고 끈다. 목표 맵 · 메모는 그 화면이 직접 받아서 크게 편다 (expand)
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() != KeyCode.F11 || root.getCenter() == goalPane || root.getCenter() == notepadPane)
        return;
      e.consume();
      setFullMode(!fullMode);
    });

    // Esc 안전망. 각 화면이 자기 안에서 먼저 받고(필터), 포커스가 화면 밖 —
    // 씬 루트나 마스코트 쪽 — 에 가 있어서 아무도 안 받았을 때만 여기까지 올라온다.
    // 필터가 아니라 핸들러라 거품 단계 맨 끝에 돈다 (필터로 달면 안쪽 처리를 다 가로챈다).
    scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() != KeyCode.ESCAPE)
        return;
      e.consume();
      if (escFull())
        return;
      if (root.getCenter() == notepadPane)
        notepadPane.exit();
      else if (root.getCenter() == chatPane || root.getCenter() == goalPane)
        showHome();
      else
        escape(stage);
    });

    stage.initStyle(StageStyle.TRANSPARENT);
    // 작업 표시줄에 뜰 이름이자, Foreground 가 이 창을 찾아내는 이름표
    stage.setTitle(TITLE);
    stage.setScene(scene);
    // 작업 표시줄·Alt+Tab 에 뜰 얼굴 (show() 전에 걸어야 첫 그림부터 제대로 나온다)
    AppIcon.apply(stage);
    // 타이틀바가 없으니 배경 아무 데나 잡고 끌어서 옮긴다. 전체화면이면 창 대신 판을 잠깐 옮긴다 —
    // 기본 화면은 입력창 둘레 빛(topBox)을, 나머지는 화면 통째로. 화면을 바꾸거나 숨기면 제자리로 (applyFull, hide)
    WindowDrag.makeDraggable(stage, root, () -> homeFull.get() ? topBox : root);
    stage.setX(800);
    stage.setY(120);
    // 전체화면은 F11 이나 목표 맵 · 메모를 크게 펼 때 (applyFull). Esc 는 각 화면이 먼저 받게 —
    // JavaFX 기본 Esc 풀기와 안내 문구는 끈다. 숨기면서 풀린 건 다시 부를 때 도로 켜고 (reveal),
    // 그 밖에 딴 데서 풀리면 전체화면을 그만 쓰는 걸로 친다
    stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
    stage.setFullScreenExitHint("");
    stage.fullScreenProperty().addListener((obs, was, is) -> {
      if (!is && fullMode && !hiding && stage.isShowing())
        setFullMode(false);
    });
    // 포커스가 들어올 때마다 전체화면 (설정의 focusFullScreen). 이 앱의 딴 창(설정 · 지난 대화…)을 닫고
    // 돌아온 건 빼고 — 떠 있는 동안 F11 로 꺼둔 걸 대화창 하나 닫았다고 도로 켜면 곤란하다
    boolean[] lostToOwn = new boolean[1];
    stage.focusedProperty().addListener((obs, was, is) -> {
      if (!is) {
        Platform.runLater(() -> lostToOwn[0] = Window.getWindows().stream().anyMatch(w -> w != stage && w.isFocused()));
        return;
      }
      // 전체화면을 막 켜고 끈 참이면 그 바람에 포커스가 잠깐 들락인 것일 수 있어 넘긴다
      boolean settling = System.nanoTime() - fullChangedAt < 500_000_000L;
      if (!lostToOwn[0] && !settling && !fullMode && SettingsDialog.load().focusFullScreen)
        setFullMode(true);
      lostToOwn[0] = false;
    });
    stage.show();
    // 처음 뜰 때 포커스가 안 들어와도 설정대로 띄운다
    if (look.focusFullScreen && !fullMode)
      setFullMode(true);
    // 처음 켤 때도 한글 조합이 보이게, 포커스는 Ime 를 거쳐서 준다 (Ime 주석 참고)
    Platform.runLater(() -> Ime.focus(inputField));
    // 처음 켰으면 (settings.json 이 없으면) 훑을 폴더부터 고르게 한다
    if (!SettingsDialog.exists())
      Platform.runLater(() -> settings(stage));

    // 창을 숨겨도 앱이 안 죽게. 이제 나가는 길은 "exit" 뿐이다.
    Platform.setImplicitExit(false);

    // 전역 단축키는 한 번에 몰아서 건다 (GlobalHotkey 주석 참고 — 등록한 스레드로만 신호가 온다)
    List<String> missed = GlobalHotkey.register(
        new GlobalHotkey.Hotkey(GlobalHotkey.VK_SPACE, "Win+Alt+Space",
            () -> Platform.runLater(() -> toggle(stage))),
        // 캡쳐는 위젯 창과 상관없이 혼자 돈다 — 위젯이 떠 있든 숨어 있든 손대지 않는다
        new GlobalHotkey.Hotkey(GlobalHotkey.VK_Z, "Win+Alt+Z",
            () -> Platform.runLater(() -> Capture.start(llm))));

    hotkeyReady = !missed.contains("Win+Alt+Space");
    if (!hotkeyReady) {
      // 못 걸었으면 숨겼다가 못 부르는 신세가 되니까, Esc 는 예전처럼 끄기로 남겨둔다.
      System.err.println("[hotkey] Win+Alt+Space 를 못 잡았어 (다른 프로그램이 먼저 쓰는 듯). "
          + "Esc 는 숨기기 대신 종료로 둘게.");
    }
    if (missed.contains("Win+Alt+Z")) {
      // 캡쳐는 못 걸려도 치명적이진 않다 — 위젯에 "캡쳐" 라고 치면 같은 판이 뜬다.
      System.err.println("[hotkey] Win+Alt+Z 를 못 잡았어 (다른 프로그램이 먼저 쓰는 듯). "
          + "캡쳐는 위젯에 '캡쳐' 라고 쳐서 불러.");
    }
  }

  /**
   * 마도카를 재서 옆자리를 잡는다. 수다 화면에는 그림이 선 세로줄(그림 너비 +
   * 모서리에서 띄운 거리)을 비우라고, 목표 쪼개기에는 그 세로줄 그림 위쪽을 말 칸으로 쓰라고
   * 알려주고, 안 읽은 말 배지는 그 세로줄 바로 왼쪽 — 그림
   * 발밑에서 BADGE_RISE 만큼 올라간 높이 — 에 세운다.
   */
  private void placeBeside(ImageView mascot) {
    // 그림이 없으면 설정대로 — 기본 그림만 한 자리를 비워두거나, 아예 안 비운다
    // (빈 ImageView 도 fitHeight 만큼 키를 잡아서 따로 본다)
    boolean has = mascot.getImage() != null;
    double width = has ? mascot.getLayoutBounds().getWidth() : reserveEmptyMascot ? EMPTY_MASCOT_WIDTH : 0;
    double height = has ? mascot.getLayoutBounds().getHeight() : reserveEmptyMascot ? MASCOT_HEIGHT : 0;

    // 비우는 세로줄은 기본 마도카 너비까지만 — 옆으로 넓은 그림(왼쪽으로 넓게)이 서도
    // 수다 칸이 줄지 않게, 넘친 만큼은 그림이 말풍선 위로 살짝 덮는다. 배지는 실제 그림 옆에 선다
    double reserve = Math.min(width, EMPTY_MASCOT_WIDTH);
    chatReserve = reserve + MASCOT_MARGIN;
    // 전체화면 수다는 가운데에 서고 그림은 떠 있어서 비울 세로줄이 없다 (applyFull)
    chatPane.reserveRight(mascotFloating ? 0 : chatReserve);
    goalPane.reserveRight(reserve, height, MASCOT_MARGIN);
    notepadPane.reserveRight(reserve, height, MASCOT_MARGIN);
    StackPane.setMargin(badges, new Insets(
        0, width + MASCOT_MARGIN + BADGE_GAP, MASCOT_MARGIN + height * BADGE_RISE, 0));
  }

  /**
   * 마도카 왼쪽에 뜨는 작은 말풍선. 수다 화면 밖에 있는 사이에 마도카가 말을 걸어두면
   * ChatPane 이 알려주고 (showUnread), 여기 떠서 "가서 볼 게 있다" 를 알린다.
   *
   * 눌러도 수다 화면이 열린다 — 표시를 보고 손이 먼저 가는 곳이 그림보다 여기라서.
   * 그림과 마찬가지로 배지를 잡으면 창은 안 끌린다.
   */
  private Label unreadBadge() {
    Label badge = new Label();
    badge.getStyleClass().add("unread-badge");
    // 안 읽은 게 없을 땐 아예 없는 셈 — 안 보이는 노드는 클릭도 안 먹으니 창 끄는 데 안 걸린다
    badge.setVisible(false);
    badge.setCursor(Cursor.HAND);
    badge.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        showChat();
    });
    return badge;
  }

  /** 목표를 쪼개 뒀다는 배지. 생김새는 안 읽은 말 배지와 같고 숫자 자리에 "!" — 누르면 그 목표로 간다 */
  private Label goalBadge() {
    Label badge = new Label("💬 !");
    badge.getStyleClass().add("unread-badge");
    badge.setVisible(false);
    // 안 보일 땐 줄에서 자리도 안 잡는다 — 수다 배지가 그림 옆에 그대로 붙어 있게
    badge.managedProperty().bind(badge.visibleProperty());
    badge.setCursor(Cursor.HAND);
    badge.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        openSplit();
    });
    return badge;
  }

  /** 쪼갠 목표를 배지로 알린다. null 이면 거둔다 */
  private void showSplit(String[] at) {
    splitAt = at;
    if (goalBadge != null)
      goalBadge.setVisible(at != null && !mascotHidden && !mascotCovered);
  }

  /** 쪼개 둔 목표의 그 칸으로 간다 */
  private void openSplit() {
    String[] at = splitAt;
    if (at == null)
      return;
    showGoals("");
    if (root.getCenter() == goalPane) {
      goalPane.reveal(at[0], at[1]);
      showSplit(null);
    }
  }

  /**
   * 안 읽은 마도카 말이 몇 개인지 그림 옆에 띄운다. 0 이면 표시를 거둔다.
   * 수다 화면 밖에서 말이 올라올 때마다 ChatPane 이 여기로 알려준다.
   *
   * 한 개일 땐 숫자를 안 붙인다 — "💬 1" 은 세어 보라는 말처럼 보이는데,
   * 하나뿐일 땐 셀 게 없어서 그냥 말풍선만 띄우는 편이 조용하다.
   */
  private void showUnread(int count) {
    if (unreadBadge == null)
      return;
    unread = count;
    unreadBadge.setText(count > 1 ? "💬 " + count : "💬");
    unreadBadge.setVisible(count > 0 && !mascotHidden && !mascotCovered);
  }

  /**
   * 마도카(와 배지)를 치우거나 도로 세운다. 그림이 가린 글이나 뒤를 보려는 것이라,
   * 그림이 섰던 세로줄은 그대로 비워둔다 (자리까지 돌려주면 말풍선이 들썩인다).
   */
  private void hideMascot(boolean hide) {
    mascotHidden = hide;
    mascotView.setVisible(!hide && !mascotCovered && mascotView.getImage() != null);
    if (unreadBadge != null)
      unreadBadge.setVisible(!hide && !mascotCovered && unread > 0);
    showSplit(splitAt);
  }

  /** 목표 맵 · 메모를 크게 펴거나 접었을 때 (GoalPane / NotepadPane.setExpanded) — 전체화면도 같이 켜고 끈다 */
  private void expand(boolean on) {
    if (!syncing)
      setFullMode(on);
  }

  /**
   * 전체화면에서 Esc — 설정(escExitsFullScreen)이 켜져 있으면 나가기 · 숨기기 전에 전체화면부터 푼다.
   * 풀었으면 true. 꺼져 있으면 Esc 는 전체화면 그대로 평소 일을 한다 (목표 맵 · 메모도 펴진 채 기본 화면으로)
   */
  private boolean escFull() {
    if (!fullMode || !SettingsDialog.load().escExitsFullScreen)
      return false;
    setFullMode(false);
    return true;
  }

  private void setFullMode(boolean on) {
    fullMode = on;
    fullChangedAt = System.nanoTime();
    applyFull();
  }

  /** 기본 화면이 올라와 있는지 — 평소엔 위쪽, 전체화면이면 가운데에 선다 */
  private boolean atHome() {
    return root.getTop() == topBox || root.getCenter() == topBox;
  }

  /**
   * 지금 화면을 fullMode 에 맞춰 세운다. 화면을 갈아끼울 때마다도 부른다.
   *  - 기본 화면: 창 전체를 테마색으로 어둡게 깔고 (.full-dim), 입력창만 가운데에 빛(.full-glow)을 두르고 띄운다.
   *    할 일 · 그림 · 배지는 감춘다. 빛 밖을 누르면 닫힌다 (root 의 MOUSE_PRESSED 필터)
   *  - 수다: 대화 칸을 가운데에 FULL_CHAT_WIDTH 로 세우고, 그림은 끌어 옮길 수 있게 띄운다
   *  - 목표 맵 · 메모: 크게 편다. 그림과 배지는 펴진 판 오른쪽 아래를 덮지 않게 치운다
   * 숨김 설정(mascotHidden)은 그대로 둔다.
   */
  private void applyFull() {
    Node screen = root.getCenter();
    syncing = true;
    goalPane.setExpanded(fullMode && screen == goalPane);
    notepadPane.setExpanded(fullMode && screen == notepadPane);
    syncing = false;

    boolean home = fullMode && atHome();
    homeFull.set(home);
    // 전체화면에서 끌어 옮겨둔 판은 화면이 바뀌면 제자리로 (WindowDrag)
    resetDrag();
    if (atHome() && home != (root.getCenter() == topBox)) {
      // 한 노드는 한 자리에만 — 빼고 나서 건다
      root.setTop(null);
      root.setCenter(null);
      if (home)
        root.setCenter(topBox);
      else
        root.setTop(topBox);
    }
    double width = home ? FULL_INPUT_WIDTH : INPUT_WIDTH;
    inputField.setPrefWidth(width);
    resultList.setMaxWidth(width);
    // 빛은 위아래가 같아야 입력창이 그 한가운데에 온다 — 밑에 늘 잡아둔 결과 목록 자리만큼 위도 비운다
    double below = resultList.getPrefHeight() + topBox.getSpacing();
    topBox.setPadding(home ? new Insets(GLOW_PAD + below, GLOW_PAD, GLOW_PAD, GLOW_PAD) : new Insets(100, 0, 0, 0));
    double max = home ? Region.USE_PREF_SIZE : Double.MAX_VALUE;
    topBox.setMaxSize(max, max);
    styleIf(topBox, "full-glow", home);
    styleIf(root, "full-dim", home);

    boolean chat = fullMode && screen == chatPane;
    mascotFloating = chat;
    chatPane.setMaxWidth(chat ? FULL_CHAT_WIDTH : Double.MAX_VALUE);
    chatPane.reserveRight(chat ? 0 : chatReserve);
    if (!chat) {
      mascotView.setTranslateX(0);
      mascotView.setTranslateY(0);
    }

    mascotCovered = fullMode && !chat;
    hideMascot(mascotHidden);
    Stage stage = (Stage) root.getScene().getWindow();
    if (stage != null && stage.isShowing() && stage.isFullScreen() != fullMode)
      stage.setFullScreen(fullMode);
  }

  private void resetDrag() {
    for (Node node : new Node[] { root, topBox }) {
      node.setTranslateX(0);
      node.setTranslateY(0);
    }
  }

  private static void styleIf(Node node, String styleClass, boolean on) {
    node.getStyleClass().remove(styleClass);
    if (on)
      node.getStyleClass().add(styleClass);
  }

  /**
   * 우측 하단에 얹을 마스코트 그림. 누르면 같은 창 안에서 수다 화면이 열린다.
   * 그림 하나 없다고 위젯까지 못 뜨면 곤란하니, 못 읽으면 조용히 없는 셈 친다.
   */
  /** 숨긴 걸 settings.json 에 남긴다 — 다시 켜도, "설정" 창을 열어도 그대로 보이게 */
  private static void saveMascotHidden(boolean hidden) {
    SettingsDialog settings = SettingsDialog.load();
    settings.mascotHidden = hidden;
    try {
      settings.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private ImageView mascot(Stage stage) {
    // 지금 말 거는 상대의 그림 ("설정" 에서 상대마다 고른다). 없으면 빈 채로 숨어 있다
    SettingsDialog settings = SettingsDialog.load();
    ImageView view = new ImageView(settings.mascotImage(false));
    // 옆으로 넓은 그림은 설정대로 — 마도카 너비에 맞추거나, 키 그대로 왼쪽으로 넓게
    SettingsDialog.fitMascot(view, settings.mascotFitsWidth(false));
    // 예전엔 마우스를 통째로 통과시켰는데(창 끌 때 걸리적거려서), 이제 말을 걸 수 있으니 받는다.
    // 대신 그림 위를 잡으면 창이 안 끌린다 — 창은 넓으니 다른 빈 곳을 잡으면 된다.
    view.setCursor(Cursor.HAND);
    // 전체화면 수다에선 그림을 끌어 옮긴다 (mascotFloating). 판 밖으로는 안 나가게
    double[] press = new double[4];
    view.setOnMousePressed(e -> {
      press[0] = e.getSceneX();
      press[1] = e.getSceneY();
      press[2] = view.getTranslateX();
      press[3] = view.getTranslateY();
    });
    view.setOnMouseDragged(e -> {
      if (!mascotFloating || !(view.getParent() instanceof Region box))
        return;
      double x = press[2] + e.getSceneX() - press[0];
      double y = press[3] + e.getSceneY() - press[1];
      double w = view.getLayoutBounds().getWidth();
      double h = view.getLayoutBounds().getHeight();
      view.setTranslateX(Math.max(-view.getLayoutX(), Math.min(box.getWidth() - w - view.getLayoutX(), x)));
      view.setTranslateY(Math.max(-view.getLayoutY(), Math.min(box.getHeight() - h - view.getLayoutY(), y)));
    });
    // 수다 화면 안에서 누르면 들어갈 데가 없으니, 대신 지운 지난 대화 목록을 띄운다
    view.setOnMouseClicked(e -> {
      // 끌어 옮긴 건 누른 게 아니다
      if (!e.isStillSincePress())
        return;
      // 오른쪽 클릭은 치워두기. 숨은 그림은 클릭할 수 없으니 도로 세우는 건 "설정" 에서 한다
      if (e.getButton() == MouseButton.SECONDARY) {
        hideMascot(true);
        saveMascotHidden(true);
        return;
      }
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      if (root.getCenter() == chatPane) {
        ChatArchiveDialog.show(stage, chatPane.archive(), chatPane.name(), chatPane.noteSingleClick(), chatPane.likingEmojis(),
            chatPane.reasonRevealed());
        Platform.runLater(() -> Ime.focus(chatPane.input()));
      } else {
        showChat();
      }
    });
    return view;
  }

  /**
   * 기본 화면(한 줄 검색)으로 돌아온다. 목표 쪼개기도 수다 화면도 Esc 로 여기로 나오고,
   * 들어올 때 누른 화살표의 반대쪽 — 목표 쪼개기에서 →, 수다 화면에서 ← — 으로도 나온다.
   */
  private void showHome() {
    root.setCenter(null);
    root.setTop(topBox);
    inputField.clear();
    hideResults();
    focusTarget = inputField;
    // 크게 펴둔 목표 맵 · 메모는 접히고, 전체화면이면 기본 화면도 전체화면으로 선다
    applyFull();
    Platform.runLater(() -> Ime.focus(inputField));
  }

  /**
   * 생각 정리하기 화면으로. 기본 화면에서 치던 말이 있으면 들고 간다.
   * 관계도 · 파트너 없는 목표 쪼개기는 Gemini 없이도 되니 키가 없어도 들어간다
   */
  private void showGoals(String carried) {
    root.setTop(null);
    root.setCenter(goalPane);
    focusTarget = goalPane.input();
    goalPane.enterWith(carried);
    applyFull();
    // 쪼개 둔 그 목표가 펴졌으면 배지는 할 일을 다 했다
    if (splitAt != null && goalPane.showing(splitAt[0]))
      showSplit(null);
  }

  /** 빈 입력창에서 ↓ 로 여는 메모장. 오른쪽 그림 위에 메모 목록이 선다 */
  private void showNotepad() {
    root.setTop(null);
    root.setCenter(notepadPane);
    focusTarget = notepadPane.input();
    notepadPane.enter();
    applyFull();
  }

  /**
   * 마도카를 눌렀을 때 여는 수다 화면. 목표 쪼개기와 똑같이 창을 새로 띄우지 않고
   * 같은 창의 내용만 갈아끼운다. 둘 다 top 이 아니라 center 로 건다 — top 은 창 높이를 안 따라 아래가 잘린다.
   * 나눠 온 대화는 안 지운다 — 나갔다 들어와도 하던 얘기가 그대로 있게.
   */
  private void showChat() {
    if (!keyReady())
      return;
    root.setTop(null);
    root.setCenter(chatPane);
    focusTarget = chatPane.input();
    chatPane.enter();
    applyFull();
  }

  /**
   * 수다는 Gemini 없이는 굴러가지 않으니, 키가 하나도 없으면 들어가기 전에
   * 키 창부터 권한다. 거기서 키를 넣고 저장하면 그대로 들어가고, 아니면 기본 화면에 남는다.
   */
  private boolean keyReady() {
    if (llm.isReady())
      return true;
    Stage stage = (Stage) root.getScene().getWindow();
    int choice = Notice.ask(stage, "Gemini 키가 하나도 없어서 수다를 못 해.\n지금 등록할래?",
        "키 등록", "나중에");
    if (choice == 0 && KeysDialog.show(stage, llm) && llm.isReady())
      return true;
    inputField.setPromptText("Gemini 키부터 등록해줘! (keys)");
    return false;
  }

  /**
   * 빈 입력창에서 ↑ 로 꺼내 보는 최근 명령어, /top 으로 여는 많이 쓴 순 랭킹.
   * 줄을 누르거나 Enter 면 그 검색어가 입력창에 올라간다 (activate).
   * 남은 기록이 없으면 아무 일도 안 일어난다 (빈 목록을 띄워봐야 볼 게 없어서).
   */
  private void showHistory(List<HistoryManager.Entry> entries, String icon, boolean withCount) {
    if (entries.isEmpty()) {
      // 조용히 아무 일도 안 일어나면 고장으로 보여서, 왜 빈손인지는 말해준다
      inputField.setPromptText("아직 쌓인 기록이 없어! 한 번 쓰고 나면 여기 뜬다");
      return;
    }

    List<SearchResult> items = entries.stream()
        .map(entry -> new SearchResult(
            icon + " " + entry.cmd + (withCount ? "  (" + entry.count + "번)" : ""),
            SearchResult.Type.HISTORY,
            entry.cmd,
            null,
            entry.cmd))
        .collect(Collectors.toList());

    resultList.getItems().setAll(items);
    selectResult(0, 0);
    resultList.setVisible(true);
  }

  /**
   * 목록에서 그 줄을 고르고, 고른 줄 위로 above 줄만 남게 굴린다.
   * 포커스는 입력창에 남아 있어서 고르기만 하면 목록이 따라 내려가지 않는다.
   * 목록은 네 줄쯤에서 잘리니 ↓ 는 2 (고른 줄이 세 번째), ↑ 는 1 (두 번째) — 가는 쪽으로 한두 줄 미리 보이게.
   * 끝에선 ListView 가 알아서 멈춘다 (빈 데까지 안 굴린다).
   */
  private void selectResult(int index, int above) {
    resultList.getSelectionModel().select(index);
    resultList.scrollTo(Math.max(index - above, 0));
  }

  /**
   * 목록에서 고른 줄을 실행한다. Enter 로 눌러도, 마우스로 클릭해도 여기로 온다.
   * 둘이 따로 놀면 한쪽만 고쳐지기 쉬워서 길을 하나로 모아뒀다.
   */
  private void activate(Stage stage, SearchResult selected) {
    if (selected != null && selected.getType() == SearchResult.Type.HISTORY) {
      // 바로 실행하지 않고 입력창에 올려만 둔다. 고쳐 쓸 수도 있으니 확인하고 한 번 더 Enter.
      inputField.setText(selected.getPrimaryValue());
      inputField.positionCaret(inputField.getLength());
      return;
    }
    if (selected != null && selected.getType() == SearchResult.Type.BUILTIN) {
      runBuiltin(stage, selected);
      return;
    }
    if (selected != null) {
      if (selected.getType() == SearchResult.Type.FOLDER
          || selected.getType() == SearchResult.Type.APP) {
        macro.incrementFrequency(inputField.getText().trim(), selected.getPrimaryValue());
      }
      // 위젯 자체 명령어(exit·restart 같은 것)는 위에서 빠져나가서 안 쌓인다. 꺼내 써봐야 곤란해서.
      history.record(inputField.getText());
      macro.execute(selected);
    }
    inputField.clear();
    hideResults();
  }

  private void hideResults() {
    resultList.setVisible(false);
  }

  /**
   * Tab: 고른 줄의 이름을 입력창에 채운다.
   * 구글 검색 줄은 친 글자가 그대로 검색어라서 채울 게 없으니 건너뛴다.
   */
  private void complete() {
    if (!resultList.isVisible())
      return;

    SearchResult selected = resultList.getSelectionModel().getSelectedItem();
    if (selected == null || selected.getType() == SearchResult.Type.GOOGLE)
      return;

    String completion = selected.getCompletion();
    if (completion == null || completion.isBlank())
      return;

    inputField.setText(completion);
    inputField.positionCaret(inputField.getLength());
  }

  /**
   * 목록에서 고른 위젯 자체 명령어를 실행한다.
   * 창을 띄우거나 JVM 을 끄는 일이라 MacroManager 말고 여기서 맡는다.
   */
  private void runBuiltin(Stage stage, SearchResult result) {
    Builtin builtin = Builtin.of(result.getPrimaryValue());
    if (builtin == null)
      return;

    String arg = result.getSecondaryValue() == null ? "" : result.getSecondaryValue().trim();
    inputField.clear();
    hideResults();

    switch (builtin) {
      case EXIT -> System.exit(0);
      case RESTART -> restart();
      case TOP -> showHistory(history.frequent(HISTORY_LIMIT), "🔥", true);
      case PATCH -> PatchNotesDialog.show(stage);
      // 못 찾아서 내려온 "새로 등록하기" 면 찾던 그 글자를 키워드 칸에 담아 보낸다
      case NEW -> AppRegisterDialog.show(stage, macro, arg);
      // 단축키(Win+Alt+Z)와 같은 판. 위젯은 뜬 채로 남아서 화면에 같이 찍힌다 —
      // 빼고 찍고 싶으면 Esc 로 내려두고 단축키로 부르면 된다.
      case CAPTURE -> Capture.start(llm);
      // 같은 와이파이에 있는 폰이랑 주고받기 — 창에 뜨는 QR 을 폰으로 찍으면 된다
      case SEND_FILE -> ShareDialog.send(stage);
      case RECEIVE_FILE -> ShareDialog.receive(stage);
      case SETTINGS -> settings(stage);
      case SCAN -> {
        int count = macro.rescanInstalledApps();
        inputField.setPromptText("설치된 앱 " + count + "개를 새로 훑었어!");
      }
      // 수다 화면에 들어가 있지 않아도 여기서 지운다 — 대화와 호감도 기록만. 기억과 호감도 값은 남는다 (clearChat 참고)
      // 지우기 전에 보관함에 남길지 묻는다. Esc 로 닫으면 안 지운다.
      case CLEAR_CHAT -> {
        int choice = Notice.ask(stage, chatPane.name() + "랑 나눈 얘기를 지울게.\n(호감도랑 기억은 그대로 남아)\n보관함에 남겨둘까?",
            "보관하고 지우기", "그냥 지우기", "취소");
        if (choice == 0 || choice == 1) {
          chatPane.clearChat(choice == 0);
          inputField.setPromptText(choice == 0 ? "보관해두고 지웠어!" : chatPane.name() + "랑 나눈 얘기 지웠어!");
        }
      }
      // 대화는 그대로 두고 호감도만 0 으로. 되돌릴 수 없어서 한 번 묻는다
      case RESET_LIKING -> {
        int choice = Notice.ask(stage, chatPane.name() + " 호감도를 0 으로 되돌릴게.\n(지금 " + chatPane.liking() + ")",
            "초기화", "취소");
        if (choice == 0) {
          chatPane.resetLiking();
          inputField.setPromptText("호감도 0 으로 돌려놨어!");
        }
      }
      // 저장하면 다음 답변부터 바로 먹는다. 취소하면 아무 일도 없다.
      case CHAT_PROMPT -> {
        if (PromptDialog.show(stage, chatPane))
          inputField.setPromptText(chatPane.name() + " 성격 바꿔뒀어!");
      }
      // 한 주 줄 + 고른 날 할 일 창. 거기서 고친 게 입력창 밑 할 일에도 바로 보이게 닫으면 새로 읽는다
      case TODO -> {
        TodoDialog.show(stage, todoPane);
        todoPane.refresh();
      }
      // 저장하면 .env 를 고쳐 쓰고 키를 새로 읽는다 — 껐다 켤 필요 없다
      case KEYS -> {
        if (KeysDialog.show(stage, llm))
          inputField.setPromptText(llm.isReady() ? "키 바꿔뒀어!" : "키가 하나도 없어 — LLM 은 쉬어");
      }
      // 키워드에 띄어쓰기가 있는 옛날 항목("한글 2024")도 있어서 뒤는 통째로 넘긴다
      case EDIT -> {
        if (arg.isEmpty())
          inputField.setPromptText("고칠 키워드도 같이 쳐줘! (예: edit yt)");
        else
          AppRegisterDialog.edit(stage, macro, arg);
      }
    }
  }

  /**
   * 고른 폴더들을 새로 훑는다.
   * 드라이브 통째로 고르면 한참 걸려서 뒤에서 돌리고, 끝나면 입력창에 알려준다.
   */
  private void scanFolders(SettingsDialog settings) {
    inputField.setPromptText("폴더 훑는 중…");
    Thread scan = new Thread(() -> {
      FolderMapper.Run(settings.scanRoots);
      Platform.runLater(() -> {
        int count = macro.reloadDirs(DIRS_JSON_PATH);
        inputField.setPromptText("폴더 " + count + "개를 훑었어!");
      });
    }, "folder-scan");
    scan.setDaemon(true);
    scan.start();
  }

  /**
   * 훑을 폴더, 상대 그림, 할 일 색, 위젯 색 테마와 투명도를 고치는 창. 저장하면 껐다 켜지 않아도 바로 바뀐다.
   * 폴더가 바뀌었으면 새로 훑는다 (처음 켤 때 폴더를 고른 것도 여기로 온다).
   */
  private void settings(Stage stage) {
    List<String> rootsBefore = SettingsDialog.load().scanRoots;
    SettingsDialog settings = SettingsDialog.show(stage);
    if (settings == null)
      return;

    todoPane.setColors(settings.todoColors());
    todoPane.refresh();
    chatPane.setLikingDisplay(settings.showLiking, settings.likingEmojis);
    chatPane.setNoteOptions(settings.secretNotes, settings.noteSingleClick);
    chatPane.setTypingDelay(settings.typingDelay, settings.typingMsPerByte);
    // 파트너를 바꿨으면 호감도가 파일에서 흔들렸다 — 들고 있던 값과 기록을 새로 읽는다
    chatPane.reloadLiking();
    // 파트너가 바뀌었으면 수다 상대도 새 파트너로 (ChatProfile)
    switchChat(stage);
    // 색 테마와 투명도는 창에서 고르는 동안 이미 걸려 있다 — 글꼴은 저장해야 바뀌니 한 번 더 건다
    SettingsDialog.applyStyle(stage.getScene(), settings.widgetOpacity, settings.widgetTheme);
    if (!settings.scanRoots.equals(rootsBefore)) {
      scanFolders(settings);
      return;
    }
    inputField.setPromptText("설정 바꿔뒀어!");
  }

  /**
   * 그림을 갈아 세운다 — 수다 화면에선 지금 얘기하는 상대, 밖에선 파트너 (없으면 설정에서 고른 캐릭터).
   * 그 상대 그림을 안 골랐으면 안 띄우고,
   * 그 자리를 비워둘지는 설정(reserveMascotSpace)대로.
   */
  private void refreshMascot() {
    SettingsDialog settings = SettingsDialog.load();
    reserveEmptyMascot = settings.reserveMascotSpace;
    boolean inChat = root.getCenter() == chatPane;
    mascotView.setImage(settings.mascotImage(inChat));
    SettingsDialog.fitMascot(mascotView, settings.mascotFitsWidth(inChat));
    placeBeside(mascotView);
    hideMascot(settings.mascotHidden);
  }

  /**
   * 지금 얘기하는 상대(처음엔 파트너)로 수다 화면을 새로 만든다.
   * 머리말 오른쪽 목록에서 딴 상대를 고르면 그 자리에서 갈아끼운다 (switchChat).
   */
  private ChatPane buildChat(Stage stage, SettingsDialog settings) {
    ChatProfile profile = settings.talkingTo();
    ChatPane pane = new ChatPane(llm, profile, basePrompt, this::showHome, this::showUnread);
    pane.setOnEscFull(this::escFull);
    pane.setLikingDisplay(settings.showLiking, settings.likingEmojis);
    pane.setNoteOptions(settings.secretNotes, settings.noteSingleClick);
    pane.setTypingDelay(settings.typingDelay, settings.typingMsPerByte);
    if (chatReserve >= 0)
      pane.reserveRight(chatReserve);
    // 수다에 "할 일" 얘기가 나오거나 오늘 할 일 하나를 콕 집어 말하면 상대가 오늘 할 일을 보고 답한다 (ChatPane.withTodos)
    pane.setTodoSource(() -> {
      List<ChatPane.TodoLine> todos = new ArrayList<>();
      for (TodoStore.Todo todo : todoPane.todos())
        todos.add(new ChatPane.TodoLine(todo.content, (todo.done ? "[x] " : "[ ] ") + todo.content + " (" + todo.category
            + (todo.overdue() ? ", " + todo.overdueSince + "부터 밀려 있던 것" : "") + ")"));
      return todos;
    });
    pane.setOnMadoka(text -> {
      if (stage.isShowing() && stage.isFocused() && !stage.isIconified())
        return;
      notify.show(pane.name(), text);
    });
    wirePartner(stage, pane, settings);
    return pane;
  }

  /** 파트너만 먼저 말을 걸고 할 일에 반응한다. 목록에서 딴 상대를 고르면 그 상대로 갈아끼운다 */
  private void wirePartner(Stage stage, ChatPane pane, SettingsDialog settings) {
    ChatProfile main = settings.mainPartner();
    pane.setProactive(main != null && pane.profile() == main);
    pane.setPartnerPicker(main, picked -> {
      SettingsDialog.saveChatPartner(picked.id);
      switchChat(stage);
    });
  }

  /**
   * 설정대로 상대가 바뀌었으면 수다 화면을 새 상대 것으로 갈아끼운다. 보던 중이면 새 화면으로 바로 들어간다.
   * 옛 화면은 retire 로 물려서, 늦게 온 답이 배지나 알림을 건드리지 않게 한다.
   */
  private void switchChat(Stage stage) {
    SettingsDialog settings = SettingsDialog.load();
    // 그림은 상대가 그대로여도 다시 건다 — 설정에서 그림만 바꿨을 수도 있어서
    refreshMascot();
    ChatProfile next = settings.talkingTo();
    boolean showing = root.getCenter() == chatPane;
    // 상대가 그대로면 파트너가 바뀌었을 수만 있다 — 먼저 말 걸지와 목록의 ♥ 만 새로 단다
    if (next == chatPane.profile()) {
      wirePartner(stage, chatPane, settings);
      return;
    }
    chatPane.retire();
    chatPane = buildChat(stage, settings);
    showUnread(0);
    if (showing)
      showChat();
  }

  /** 이 클래스가 올라온 곳(target/classes)에서 두 칸 위. 못 알아내면 작업 폴더 */
  private static File projectDir() {
    try {
      File classes = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      File target = classes.getParentFile();
      if (target != null && target.getParentFile() != null)
        return target.getParentFile();
    } catch (Exception e) {
      e.printStackTrace();
    }
    return new File(System.getProperty("user.dir"));
  }

  /**
   * 코드를 고친 뒤 앱 안에서 바로 새 버전으로 갈아타는 길.
   * run.bat 이 mvn clean javafx:run 이라, 새로 빌드한 걸 띄운다.
   *
   * restart.vbs 를 거쳐 부른다 — cmd /c start 로 띄우면 빌드하는 내내,
   * 이어서 새 앱이 살아 있는 내내 콘솔 창이 같이 떠 있어서. (run.vbs 와 같은 수법)
   * vbs 가 창 없이 띄워주고, 이 JVM 이 죽어도 남의 프로세스라 끌려가지 않는다.
   * 기다리는 것(잠긴 target/ 을 mvn clean 이 못 지우는 걸 피하려고)과 로그 남기기는
   * restart.bat 이, 빌드가 엎어졌을 때 알리는 건 restart.vbs 가 맡는다.
   */
  private void restart() {
    File vbs = new File(System.getProperty("user.dir"), "restart.vbs");
    if (!vbs.isFile())
      vbs = new File(PROJECT_DIR, "restart.vbs");
    if (!vbs.isFile()) {
      System.err.println("[restart] restart.vbs 를 못 찾았어: " + vbs);
      inputField.clear();
      inputField.setPromptText("restart.vbs 를 못 찾아서 재시작 못 했어");
      return;
    }

    try {
      // 기다리기·로그·실패 처리는 전부 restart.bat 이 한다. 여긴 vbs 를 깨우기만.
      // wscript 는 cscript 와 달리 콘솔을 안 달고 뜬다.
      new ProcessBuilder("wscript.exe", vbs.getAbsolutePath())
          .directory(vbs.getParentFile())
          .start();
    } catch (IOException ex) {
      ex.printStackTrace();
      inputField.clear();
      inputField.setPromptText("재시작에 실패했어: " + ex.getMessage());
      return;
    }

    System.exit(0);
  }

  /**
   * Esc: 끄는 게 아니라 숨긴다. 다시 부르는 건 Win+Alt+Space. 전체화면이면 설정대로 그것부터 푼다 (escFull).
   * 단축키를 못 걸었을 땐 숨기면 영영 못 부르니까 그냥 종료한다.
   */
  private void escape(Stage stage) {
    if (escFull())
      return;
    if (hotkeyReady)
      hide(stage);
    else
      System.exit(0);
  }

  private void toggle(Stage stage) {
    if (!stage.isShowing())
      reveal(stage);
    else if (stage.isFocused() && !stage.isIconified())
      // 내가 지금 쓰고 있던 창이면 접는다
      hide(stage);
    else
      // 떠 있긴 한데 뒤에 깔렸거나 내려가 있으면, 닫지 말고 앞으로 끌어온다
      bringToFront(stage);
  }

  private void hide(Stage stage) {
    // 다음에 열었을 때 아까 치던 게 남아 있으면 지저분해서 비워둔다.
    // (수다 화면은 안 건드린다 — 하던 얘기가 사라지면 곤란해서)
    inputField.clear();
    hideResults();
    resetDrag();
    hiding = true;
    stage.hide();
    hiding = false;
  }

  private void reveal(Stage stage) {
    stage.show();
    // 숨기면서 풀린 전체화면을 도로 켠다
    if (fullMode && !stage.isFullScreen())
      stage.setFullScreen(true);
    bringToFront(stage);
  }

  private void bringToFront(Stage stage) {
    stage.setIconified(false);
    // JavaFX 의 toFront() 는 윈도우에서 자주 씹혀서, 잠깐 '항상 위'로 올렸다 내린다
    stage.setAlwaysOnTop(true);
    stage.toFront();
    stage.requestFocus();
    // 위의 것만으론 창만 위에 그려지고 포커스는 원래 창에 남을 때가 있다 (Foreground 주석 참고)
    Foreground.force(TITLE);
    Platform.runLater(() -> {
      stage.setAlwaysOnTop(false);
      // hide() 가 OS 쪽 창을 닫아버려서, 다시 뜬 창엔 한글 조합을 새로 켜줘야 한다.
      // 수다 화면이 올라와 있으면 그쪽 입력칸으로 — 답을 못 받고 남은 말이 있으면
      // 이 포커스를 신호로 ChatPane 이 알아서 다시 던진다.
      // 메모를 크게 펴 둔 채면 적는 칸 대신 지금 쓰던 단으로
      Node target = focusTarget == notepadPane.input() ? notepadPane.typingField() : focusTarget;
      Ime.focus(target == null ? inputField : target);
    });
  }

  /** 할 일 자리의 입력칸(할 일 적기 · 카테고리 이름) 안이면 true */
  private boolean inTodoField(Node node) {
    for (Node n = node; n != null && n != todoPane; n = n.getParent()) {
      if (n instanceof TextField)
        return true;
    }
    return false;
  }

  public static void main(String[] args) {
    // 윈도우 알림 머리에 "OpenJDK Platform Binary" 대신 Kyubey Chat 이 뜨게 (창을 띄우기 전에 해야 먹는다)
    WinNotify.register("Kyubey Chat");
    launch(args);
  }
}
