package kyubey.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import kyubey.chat.ChatProfile;
import kyubey.core.SettingsDialog;
import kyubey.llm.LlmClient;
import kyubey.todo.TodoStore;
import kyubey.ui.ExpandCorner;
import kyubey.ui.FloatingPanel;
import kyubey.ui.Ime;
import kyubey.ui.Notice;
import kyubey.ui.Theme;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 왼쪽 화살표로 들어오는 생각 정리하기 화면. ＋ 로 새로 만들 때 두 가지 중 고른다 (GoalKindDialog) —
 * 만들고 나면 고른 방식의 예시 그림 길잡이가 뜬다 (GoalGuideDialog, "다음부터 안 보기" 면 Book.guideOff):
 *  - 목표 쪼개기 (GoalStore.TREE): 아래 설명대로 트리를 쪼갠다. 파트너(LLM)를 같이 쓸지 고를 수 있다 —
 *    안 쓰면 입력칸 Enter 가 고른 칸 밑에 친 이름으로 칸을 붙이고, 쪼개기 · 물어보기 · 메모 자동 생성이 빠진다
 *  - 관계도 (GoalStore.GRAPH): 중심 칸 + 아무 칸끼리나 잇는 그래프 (GraphMap). 파트너는 안 쓴다.
 *    고른 칸에서 N 은 이어진 칸, 아무것도 안 고르고 (빈 데 클릭 · Esc) N 은 따로 선 칸, Ctrl+끌기를 다른 칸에 놓으면 잇기 · 빈 데 놓으면 거기 이은 칸,
 *    사이클도 된다. 연결에 방향이 있을지는 만들 때 고르고, 빈 데 오른쪽 클릭 "설정…" 창 (GraphSettingsDialog) 에서도 바꾼다.
 *    관계도를 통째로 지우는 것도 그 창에서 루트 이름을 쳐서만 — 루트 칸 오른쪽 클릭엔 지우기가 없고, 루트에서 Delete 면 그 창이 뜬다
 *    방향 있는 관계도의 연결은 한 방향 · 양방향 · 안 정함 (방향 없을 때 이은 것) 셋 — 이어진 두 칸을 Ctrl+끌기 하면
 *    안 정한 건 그쪽으로 정하고, 반대로 가던 건 양방향이 된다. 연결 오른쪽 클릭으로도 고른다.
 *    판 왼쪽 위 (전체화면이면 띄운 판 맨 위) (자유 그래프) · (루트부터) 로 보는 법을 바꾼다 — 루트부터는 중심 칸에서 떨어진 만큼 세로줄로 늘어놓는다 (GraphMap)
 *
 * 목표 쪼개기:
 * 큰 목표를 위에서 아래로 뻗는 마인드맵으로 쪼개 나간다 — backend → arch, sec… → sec 를 고르고
 * 또 쪼개면 auth, crypto… 처럼 (GoalMap). 칸 이름은 짧게 — 좁은 화면에 가지가 옆으로 퍼지니까. 쪼개는 건 마도카랑 짧게 말을 주고받으며 한다. 마도카한테는 오간 말 대신
 * 지금 트리와 고른 칸을 보여준다 (ask) — 대화가 길어져도 맥락이 트리 하나로 정리돼 있게.
 * 수다 화면(ChatPane)과는 따로 논다. 호감도도 기억 메모도 안 건드린다.
 *
 * 목표는 여러 개를 두고 위쪽 목록에서 갈아탄다. 수다처럼 지워야 새로 시작하는 게 아니라
 * ＋ 로 새 목표를 세우면 되고, 하던 목표는 그대로 남는다 (GoalStore).
 *
 * 자리: 왼쪽은 머릿줄 · 맵 · 입력칸, 오른쪽 마도카가 선 세로줄의 그림 위쪽은 짧은 말 칸.
 *
 * 키:
 *  - 입력칸 Enter: 마도카한테 보내기. 비어 있으면 고른 칸을 쪼개 달라고 한다
 *  - 입력칸 ↑/↓: 맵에서 칸 고르기 (위→아래 읽는 차례로), Alt+↑/↓: 목표 갈아타기
 *  - 맵 ↑/↓/←/→: 부모/첫 자식/형제로 (GoalMap), Enter: 쪼개기, Space: 끝냄 표시, T: 할 일에 넣기/빼기,
 *    F: 접기/펼치기, N · Insert: 자식 칸 손으로 붙이기, F2 · E · R: 이름 고치기, L: 말단 (자식이 있으면 말단의 부모),
 *    M: 다른 칸 밑으로 옮기기, Delete: 지우기 (오른쪽 클릭 메뉴와 같다 — 메뉴 오른쪽에 키가 적혀 있다)
 *  - 칸 옮기기 (M · 오른쪽 클릭): 옮길 칸이 점선이 되고, 맵에서 새 부모 칸을 골라 확인 (Enter) · 취소 (Esc). 밑의 칸도 같이 간다
 *  - 칸 클릭: 고르기, 고른 칸을 또 누르면 그 칸 메모 — 쪼갤 때 마도카가 한 말이 적혀 있고, 손으로 고칠 수 있다 (GoalNoteDialog)
 *  - 칸 오른쪽 클릭 "말단으로 정하기": 흰 테두리가 둘리고, 그 칸을 파트너한테 물어볼 수 있다 — 파트너가 그 칸을 설명해 주고
 *    할 일 "목표" 카테고리에 넣는다 (askLeaf). 할 일에서 오른쪽 클릭 "목표로 이동하기" 면 여기 그 칸으로 온다 (reveal)
 *  - Esc, 빈 입력칸에서 → (친 게 있으면 Alt+→): 기본 화면으로
 *  - 맵 오른쪽 위 삼각 귀퉁이 (ExpandCorner) · F11 · 오른쪽 클릭 "전체화면": 맵을 전체화면으로 펴고,
 *    말 칸은 맵 안 오른쪽 위에 둥근 판으로 띄운다 (FloatingPanel — 손잡이로 옮긴다). 펴진 채에선 Esc 가 먼저 접는다 (설정의 escExitsFullScreen 이 켜져 있을 때)
 */
public class GoalPane extends HBox {

  /** 화면 가장자리 여백. 오른쪽은 마도카 그림 테두리에 맞춰 따로 잡힌다 (reserveRight) */
  private static final double PAD = 16;

  /** 말 칸 밑변과 마도카 그림 머리 사이 */
  private static final double MASCOT_GAP = 8;

  /** 그림이 좁아도 말 칸은 이만큼은 둔다 */
  private static final double LOG_MIN = 150;

  /** 새 목표 맨 위 칸에 친 말을 이만큼까지만 담는다. 마도카가 곧 짧게 다듬는다 */
  private static final int TITLE_MAX = 40;

  /** 맵을 크게 폈을 때 오른쪽에 띄우는 말 칸 폭 */
  private static final double FLOAT_WIDTH = 220;

  /** 띄운 말 칸과 맵 테두리 사이. 위는 귀퉁이 단추를 비켜 더 띄운다 */
  private static final double FLOAT_GAP = 10;
  private static final double FLOAT_TOP = 28;
  /** 띄운 판의 말 칸 높이 — 판이 작아 목록이 FLOAT_LIST_ROWS 줄까지 줄어든 뒤엔 이것도 줄어든다 */
  private static final double FLOAT_LOG_HEIGHT = 200;
  /** 띄운 판의 목표 목록은 이 줄 수만큼까지만 줄어든다 */
  private static final double FLOAT_LIST_ROWS = 1.5;
  /** 목표 줄 높이를 아직 못 잴 때 (줄이 없을 때) 쓰는 값 */
  private static final double FLOAT_ROW_GUESS = 26;


  /**
   * 파트너한테 입히는 말투와 할 일. 답은 JSON 하나로 받아서 말(reply)은 말풍선으로,
   * 고칠 거리(ops)는 트리에 그대로 먹인다 (apply). {이름} 은 부를 때 지금 파트너 이름으로 채운다 (send)
   */
  static final String SYSTEM = """
      너는 '{이름}'야. 지금은 사용자랑 큰 목표를 작은 단계로 쪼개서
      실제로 해낼 수 있게 돕는 중이야. 다정하고 밝게, 반말로, 문장 끝마다 '-모'를 붙여서.

      사용자 말과 함께 [지금 트리] 와 [고른 칸] 을 줄게. 대화 기록은 안 줘 — 트리가 곧 지금까지의 맥락이야.
      트리는 한 줄에 칸 하나, 들여쓰기가 깊이, 앞의 [n3] 같은 게 칸 id 야.
      ✓ 는 끝낸 칸, 📌 는 이미 할 일에 넣은 칸, 🎯 는 사용자가 말단으로 정한 칸 (더 안 쪼개고 바로 손댈 칸).

      반드시 JSON 객체 하나로만 답해. 코드블록(```)이나 다른 글은 붙이지 마.
      {"reply": "네가 할 말", "ops": [ ... ]}

      reply: 한두 문장. 마크다운 쓰지 마. 쪼갰으면 어떤 기준으로 나눴는지, 다음에 뭘 더 파면 좋을지 짧게.
      ops 에 쓸 수 있는 것:
      - {"op": "add", "parent": "칸 id", "items": ["하위 칸", ...]}  parent 밑에 새 칸을 붙인다
      - {"op": "rename", "id": "칸 id", "title": "새 이름"}
      - {"op": "remove", "id": "칸 id"}  밑의 칸까지 같이 지운다. n0 은 못 지운다
      - {"op": "done", "id": "칸 id", "done": true}
      - {"op": "todo", "ids": ["칸 id", ...]}  오늘 할 일에 넣는다. 사용자가 할 일/투두에 넣어 달라고 할 때만

      규칙:
      - 쪼갤 땐 3~6개로, 한 층은 같은 기준으로 겹치지 않게 (MECE).
      - 칸 이름은 아주 짧게 — 마인드맵 딱지라 가로 자리가 좁아. 한 단어, 길어야 10자 남짓.
        널리 쓰는 줄임말이 있으면 그걸 써 (프로그래밍 언어 → PL, 데이터베이스 → DB, 운영체제 → OS,
        자료구조 → DS, 알고리즘 → algo, 네트워크 → net, 보안 → sec, 아키텍처 → arch, 인증 → auth).
        줄임말이 없으면 핵심 낱말 하나로. 사용자가 쓰는 말(한/영)을 따라.
      - 이미 있는 칸과 겹치는 건 다시 만들지 마.
      - 어느 칸 얘기인지 말이 없으면 [고른 칸] 얘기로 알아들어.
      - 말이 칸 이름을 가리키면 (예: "security 더 쪼개줘") 트리에서 그 칸 id 를 찾아 써.
      - n0 이름이 사용자가 친 문장 그대로라 길면, 짧은 목표 이름으로 rename 해.
      - 이미 있는 칸 이름이 길면 짧게 rename 해도 돼.
      - 할 일에 넣을 땐 바로 손댈 수 있는 맨 끝 칸이 좋아. 너무 크면 먼저 더 쪼개자고 해.
      - 트리를 안 바꿀 말이면 ops 는 빈 배열.
      """;

  /**
   * 칸 메모 자동 생성 (GoalNoteDialog 의 단추) — 그 칸이 뭔지, 어떻게 손대면 좋을지 짧게.
   * {이름} 은 부를 때 지금 파트너 이름으로 채운다 (openNote)
   */
  static final String NOTE_SYSTEM = """
      너는 '{이름}'야. 사용자가 큰 목표를 작은 단계로 쪼갠 마인드맵에서
      칸 하나에 붙일 메모를 써 줘. 다정하고 밝게, 반말로, 문장 끝마다 '-모'를 붙여서.

      [지금 트리] 와 [메모 칸] 을 줄게. 트리는 한 줄에 칸 하나, 들여쓰기가 깊이야.
      메모에는 그 칸이 전체 목표에서 무슨 뜻인지, 어떻게 손대면 좋을지를 두세 문장으로 적어.
      메모 글만 답해. 마크다운이나 따옴표, 머리말은 붙이지 마.
      """;

  /**
   * 말단 칸을 파트너한테 물을 때 (askLeaf) — 그 칸이 뭔지 설명하고, 바로 손대는 법을 짧게.
   * 답은 목표 화면 말 칸에 띄우고 칸 메모에도 쌓는다. {이름} 은 지금 파트너 이름으로 채운다
   */
  static final String LEAF_SYSTEM = """
      너는 '{이름}'야. 사용자가 큰 목표를 작은 단계로 쪼갠 마인드맵에서
      더 쪼개지 않고 바로 손댈 맨 끝 칸 하나를 물어봤어. 다정하고 밝게, 반말로, 문장 끝마다 '-모'를 붙여서.

      [지금 트리] 와 [물어본 칸] 을 줄게. 트리는 한 줄에 칸 하나, 들여쓰기가 깊이야.
      그 칸이 뭔지 (칸 이름이 줄임말이면 풀어서), 전체 목표에서 왜 필요한지, 오늘 어떻게 첫 손을 대면 좋을지
      서너 문장으로 설명해 줘. 이 칸은 사용자 할 일에 들어가.
      설명 글만 답해. 마크다운이나 따옴표, 머리말은 붙이지 마.
      """;

  private static final ObjectMapper mapper = new ObjectMapper();

  private final LlmClient llm;
  private final GoalStore store;
  private final TodoStore todoStore = new TodoStore();
  private final Runnable onExit;

  /** 펴진 채 Esc — 전체화면을 풀지는 Main 이 설정대로 정한다 (escExitsFullScreen). 풀었으면 true */
  private BooleanSupplier onEscFull = () -> {
    if (!this.expanded)
      return false;
    setExpanded(false);
    return true;
  };
  /** 할 일에 뭔가 넣었을 때 — Main 이 기본 화면의 할 일을 새로 읽게 */
  private final Runnable onTodo;

  /** Esc 로 전체화면을 풀지 — 풀었으면 true 를 돌려준다. 아니면 Esc 는 평소대로 나간다 */
  public void setOnEscFull(BooleanSupplier onEscFull) {
    this.onEscFull = onEscFull;
  }

  private final GoalStore.Book book;
  /** 지금 보는 목표. 새 목표를 세우려고 비워둔 참이면 null */
  private GoalStore.Goal current;

  private final ComboBox<GoalStore.Goal> picker = new ComboBox<>();
  private final GoalMap map = new GoalMap(this::menu, this::save, this::openNote);
  /** 관계도 목표일 때 map 대신 보이는 판 */
  private final GraphMap graph = new GraphMap(this::graphMenu, this::linkMenu, this::save, this::openNote, this::connect,
      this::sproutGraphNode);
  /** 관계도 판 왼쪽 위 보는 법 단추 — 손으로 잡은 자리대로 · 루트부터 세로줄로 (Goal.layered) */
  private final ToggleButton freeMode = new ToggleButton("자유 그래프");
  private final ToggleButton rootMode = new ToggleButton("루트부터");
  private final HBox modeBar = new HBox(freeMode, rootMode);
  /** 관계도 빈 데 메뉴 — 또 띄우기 전에 닫는다 */
  private ContextMenu shownBoardMenu;
  /** 글은 파트너 이름이 들어가서 refreshPartner 가 채운다 */
  private final Label emptyHint = new Label();
  private final VBox log = new VBox(6);
  private final ScrollPane logScroll = new ScrollPane(log);
  private final TextField input = new TextField();
  private final Label status = new Label();
  /** 오른쪽 말 칸 — 마도카 그림 위쪽 자리 (reserveRight) */
  private final VBox side;
  private final HBox headerRow;
  /** 맵 · 빈 목표 안내 · 귀퉁이 단추를 겹친 판. 크게 펴면 말 칸도 여기 뜬다 */
  private final StackPane mapBox;
  private final ExpandCorner corner = new ExpandCorner(() -> setExpanded(!isExpanded()));
  /** 크게 폈을 때 맵 안 오른쪽에 뜨는 둥근 말 칸 — logScroll 을 side 에서 옮겨 담는다 */
  private final FloatingPanel floating = new FloatingPanel(FLOAT_WIDTH, new Insets(FLOAT_TOP, FLOAT_GAP, FLOAT_GAP, 0));
  /** 크게 폈을 때 띄운 판 위쪽의 목표 목록 — 머릿줄 목록(picker)이 걷히니 여기서 갈아탄다 */
  private final ListView<GoalStore.Goal> goalList = new ListView<>();
  /** 띄운 판 안 — 목표 목록 위, 말 칸 아래. 높이 나누기는 layoutFloatBody */
  private final VBox floatBody = new VBox(4) {
    @Override
    protected void layoutChildren() {
      layoutFloatBody(this);
    }
  };

  /** reserveRight 가 잡은 여백 — 크게 폈다 접을 때 되돌린다 */
  private Insets reserved = new Insets(PAD, 20, PAD, 20);
  private boolean expanded;
  /** 크게 펴고 접을 때 — Main 이 그동안 마도카 그림을 치운다 */
  private Consumer<Boolean> onExpand = is -> {
  };

  /** 목록을 코드로 고치는 중 — 그 사이 값이 바뀐 걸 내가 고른 걸로 알아듣지 않게 */
  private boolean syncing;

  /** 답 기다리는 중에 또 보내는 것 막기 */
  private boolean asking;

  /** 다른 칸 밑으로 옮기는 중인 칸. 맵에서 새 부모를 고르는 동안만 (startMove) */
  private GoalStore.Node moving;
  /** 옮기는 동안 맵 밑에 뜨는 확인 · 취소 줄 */
  private final HBox moveBar;
  private final Label moveHint = new Label();

  /** 칸을 끝냄 · 안 끝냄으로 바꿨을 때 그 칸에서 내려간 할 일도 맞춘다 (Main 이 TodoPane.finishGoal 로 단다) */
  public interface TodoSync {
    void finish(String goalId, List<String> nodeIds, boolean done);
  }

  private TodoSync todoSync = (goalId, nodeIds, done) -> {
  };

  public void setTodoSync(TodoSync todoSync) {
    this.todoSync = todoSync;
  }

  /** 마도카가 칸을 쪼개 줬을 때 — Main 이 그림 옆 배지와 윈도우 알림으로 띄우고, 누르면 그 목표의 그 칸으로 온다 (reveal) */
  public interface SplitListener {
    void split(String goalId, String nodeId, String title, String message);
  }

  private SplitListener onSplit = (goalId, nodeId, title, message) -> {
  };

  public void setOnSplit(SplitListener onSplit) {
    this.onSplit = onSplit;
  }

  /** 지금 이 목표를 펴 두고 있는지 */
  public boolean showing(String goalId) {
    return current != null && current.id.equals(goalId);
  }

  public GoalPane(LlmClient llm, GoalStore store, Runnable onExit, Runnable onTodo) {
    this.llm = llm;
    this.store = store;
    this.onExit = onExit;
    this.onTodo = onTodo;
    this.book = store.load();

    setSpacing(10);
    setPadding(new Insets(PAD, 20, PAD, 20));

    Label header = new Label("🌱 생각 정리하기");
    header.getStyleClass().add("goal-header");

    picker.getStyleClass().add("goal-picker");
    picker.setFocusTraversable(false);
    picker.setPromptText("새 목표");
    picker.setMaxWidth(220);
    picker.setCellFactory(lv -> new GoalCell(false));
    picker.setButtonCell(new GoalCell(true));
    picker.valueProperty().addListener((obs, was, is) -> {
      if (syncing || is == null || is == current)
        return;
      // 갈래 머리 (목표 · 관계도) 는 고르는 줄이 아니다 — 키로 닿았으면 하던 목표로 되돌린다
      if (isGroup(is)) {
        Platform.runLater(this::syncPicker);
        return;
      }
      open(is);
    });

    Button add = new Button("＋");
    add.getStyleClass().add("goal-new");
    add.setFocusTraversable(false);
    add.setOnAction(e -> startNewDialog(""));

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    headerRow = new HBox(8, header, spacer, picker, add);
    headerRow.setAlignment(Pos.CENTER_LEFT);

    map.addEventFilter(KeyEvent.KEY_PRESSED, this::mapKey);
    // 칸 밖 빈 데를 오른쪽 클릭하면 — 칸은 자기 메뉴가 먼저 받는다 (menu)
    map.setContextMenu(new ContextMenu(corner.menuItem()));
    graph.addEventFilter(KeyEvent.KEY_PRESSED, this::graphKey);
    // 한글 모드여도 판에선 N · T 같은 맨손 키가 먹게
    Ime.shortcutsOnly(map, graph);
    graph.setVisible(false);
    // 관계도 빈 데 오른쪽 클릭 — 따로 선 칸 · 방향 바꾸기 · 전체화면 (칸 · 선은 자기 메뉴가 먼저 받는다)
    graph.setOnContextMenuRequested(e -> {
      if (!isGraph())
        return;
      e.consume();
      if (shownBoardMenu != null)
        shownBoardMenu.hide();
      shownBoardMenu = graphBoardMenu();
      shownBoardMenu.show(graph, e.getScreenX(), e.getScreenY());
    });
    // F11 은 이 화면 어디서든
    addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.F11) {
        e.consume();
        setExpanded(!expanded);
      }
    });

    emptyHint.getStyleClass().add("goal-empty");
    emptyHint.setWrapText(true);
    emptyHint.setMouseTransparent(true);
    moveHint.getStyleClass().add("goal-move-hint");
    Button moveOk = new Button("확인");
    moveOk.getStyleClass().add("dialog-button-primary");
    moveOk.setFocusTraversable(false);
    moveOk.setOnAction(e -> confirmMove());
    Button moveCancel = new Button("취소");
    moveCancel.getStyleClass().add("dialog-button");
    moveCancel.setFocusTraversable(false);
    moveCancel.setOnAction(e -> cancelMove());
    moveBar = new HBox(8, moveHint, moveOk, moveCancel);
    moveBar.getStyleClass().add("goal-move-bar");
    moveBar.setAlignment(Pos.CENTER);
    moveBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    moveBar.setVisible(false);
    StackPane.setAlignment(moveBar, Pos.BOTTOM_CENTER);
    StackPane.setMargin(moveBar, new Insets(0, 10, 10, 10));
    for (ToggleButton mode : List.of(freeMode, rootMode)) {
      mode.getStyleClass().add("graph-mode-button");
      mode.setFocusTraversable(false);
      mode.setOnAction(e -> setLayered(mode == rootMode));
      // 띄운 판에 들어가면 판 폭을 반씩 나눠 쓴다
      mode.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(mode, Priority.ALWAYS);
    }
    modeBar.getStyleClass().add("graph-mode");
    modeBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    modeBar.setVisible(false);
    modeBar.managedProperty().bind(modeBar.visibleProperty());
    StackPane.setAlignment(modeBar, Pos.TOP_LEFT);
    StackPane.setMargin(modeBar, new Insets(8, 0, 0, 8));
    mapBox = new StackPane(map, graph, emptyHint, moveBar, modeBar, corner);

    VBox.setVgrow(mapBox, Priority.ALWAYS);

    log.setPadding(new Insets(2, 4, 2, 2));
    logScroll.getStyleClass().add("chat-scroll");
    logScroll.setFitToWidth(true);
    logScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    logScroll.setFocusTraversable(false);
    VBox.setVgrow(logScroll, Priority.ALWAYS);
    // 말이 붙으면 맨 밑으로
    log.heightProperty().addListener((obs, was, is) -> logScroll.setVvalue(1.0));

    goalList.getStyleClass().addAll("notepad-list", "float-list");
    goalList.setPlaceholder(new Label("목표가 없어"));
    // 펴진 채엔 머릿줄 ＋ 가 걷히니 목록 맨 끝 줄 (NEW_ROW) 의 단추로 새로 만든다
    goalList.setCellFactory(lv -> new GoalCell(false, () -> startNewDialog("")));
    goalList.setFocusTraversable(false);
    goalList.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
      if (syncing || is == null || is == current)
        return;
      if (isGroup(is) || is == NEW_ROW) {
        Platform.runLater(this::syncPicker);
        return;
      }
      open(is);
      // 펴진 채엔 판 키가 먹게 포커스를 판으로 돌려준다
      Platform.runLater(() -> view().requestFocus());
    });
    VBox.setVgrow(goalList, Priority.ALWAYS);
    floatBody.getChildren().add(goalList);

    input.getStyleClass().add("chat-input");
    refreshPartner();
    input.addEventFilter(KeyEvent.KEY_PRESSED, this::inputKey);

    status.getStyleClass().add("goal-status");
    status.setWrapText(true);

    VBox main = new VBox(8, headerRow, mapBox, input, status);
    main.setMinWidth(0);
    HBox.setHgrow(main, Priority.ALWAYS);

    side = new VBox(logScroll);
    side.setPrefWidth(LOG_MIN);
    side.setMinWidth(LOG_MIN);

    getChildren().addAll(main, side);

    GoalStore.Goal last = null;
    for (GoalStore.Goal goal : book.goals) {
      if (goal.id.equals(book.current))
        last = goal;
    }
    if (last != null)
      open(last);
    else
      startNew();
  }

  /** 지금 화면의 입력칸 — Main 이 창을 다시 띄울 때 여기로 포커스를 돌려준다 */
  public TextField input() {
    return input;
  }

  /**
   * 마도카가 선 오른쪽 세로줄을 말 칸 자리로 쓴다 — 폭은 그림 폭, 세로는 그림 머리 위까지.
   * 맵과 입력칸은 그 세로줄 왼쪽에 선다. 오른쪽 여백을 그림이 모서리에서 띄운 거리(margin)와
   * 맞춰서 말 칸이 그림과 한 줄로 선다.
   */
  public void reserveRight(double width, double height, double margin) {
    reserved = new Insets(PAD, margin, PAD, 20);
    if (!expanded)
      setPadding(reserved);
    double w = Math.max(LOG_MIN, width);
    side.setPrefWidth(w);
    side.setMinWidth(w);
    side.setMaxWidth(w);
    side.setPadding(new Insets(0, 0, Math.max(0, margin + height + MASCOT_GAP - PAD), 0));
  }

  /**
   * node 가 목표 맵 안에 있는지. 맵에선 맨손 ←/→ 가 옆 칸 옮기기라,
   * Main 이 입력칸 밖 ←/→ 로 화면을 바꿀 때 여기선 Alt 를 같이 눌러야 바꾸게 비켜준다.
   */
  public boolean inMap(javafx.scene.Node node) {
    for (javafx.scene.Node at = node; at != null; at = at.getParent()) {
      if (at == map || at == graph)
        return true;
    }
    return false;
  }

  public void setOnExpand(Consumer<Boolean> onExpand) {
    this.onExpand = onExpand;
  }

  public boolean isExpanded() {
    return expanded;
  }

  /**
   * 맵을 화면 가득 펴거나 접는다. 펴면 머릿줄 · 입력칸 · 오른쪽 세로줄을 걷고, 말 칸은
   * 맵 안 오른쪽에 둥근 판으로 띄운다. 펴진 채엔 입력칸이 없으니 맵에 포커스를 준다 — 맵 키는 그대로 먹는다.
   */
  public void setExpanded(boolean on) {
    if (expanded == on)
      return;
    expanded = on;
    for (javafx.scene.Node node : List.of(headerRow, input, status, side)) {
      node.setVisible(!on);
      node.setManaged(!on);
    }
    if (on) {
      floatBody.getChildren().remove(logScroll);
      floatBody.getChildren().add(logScroll);
      // 보는 법 단추 (자유 그래프 · 루트부터) 는 띄운 판 맨 위로
      mapBox.getChildren().remove(modeBar);
      floatBody.getChildren().add(0, modeBar);
      modeBar.setMaxWidth(Double.MAX_VALUE);
      floating.setContent(floatBody);
      floating.resetPosition();
      mapBox.getChildren().add(floating);
      corner.toFront();
      setPadding(new Insets(PAD));
    } else {
      mapBox.getChildren().remove(floating);
      floatBody.getChildren().remove(logScroll);
      floatBody.getChildren().remove(modeBar);
      modeBar.setMaxWidth(Region.USE_PREF_SIZE);
      mapBox.getChildren().add(mapBox.getChildren().indexOf(corner), modeBar);
      side.getChildren().setAll(logScroll);
      setPadding(reserved);
    }
    syncFloatLog();
    // 펴진 채엔 끝 칸 바깥에 띄운 판 폭만큼 여백을 둔다 — 끝까지 스크롤하면 판에 가렸던 칸이 드러나게
    double rim = on ? FLOAT_WIDTH + FLOAT_GAP : 0;
    map.setRim(rim);
    graph.setRim(rim);
    logScroll.setVvalue(1.0);
    corner.setExpanded(on);
    onExpand.accept(on);
    if (on)
      view().requestFocus();
    else
      Platform.runLater(() -> Ime.focus(input));
  }

  /**
   * 띄운 판의 말 칸은 파트너랑 같이 쓰는 목표이거나 오간 말이 남아 있을 때만 — 아니면 목표 목록이 판을 다 쓴다.
   * 접혀 있으면 오른쪽 세로줄의 말 칸은 늘 보인다
   */
  private void syncFloatLog() {
    boolean show = !expanded || (current != null && (current.usesLlm() || !current.log.isEmpty()));
    logScroll.setVisible(show);
    logScroll.setManaged(show);
  }

  /**
   * 띄운 판 안 높이 나누기. 보는 법 단추 · 새로 만들기 줄은 제 키대로 서고, 남는 자리를
   * 말 칸 (FLOAT_LOG_HEIGHT 고정) 과 목표 목록 (나머지 전부) 이 나눈다. 판이 작아지면 목록이 먼저
   * FLOAT_LIST_ROWS 줄까지 줄고, 그래도 모자라면 말 칸도 줄어든다.
   */
  private void layoutFloatBody(VBox body) {
    Insets in = body.getInsets();
    double w = Math.max(0, body.getWidth() - in.getLeft() - in.getRight());
    double h = Math.max(0, body.getHeight() - in.getTop() - in.getBottom());
    List<javafx.scene.Node> kids = body.getChildren().filtered(javafx.scene.Node::isManaged);
    double gap = body.getSpacing();
    double others = gap * Math.max(0, kids.size() - 1);
    for (javafx.scene.Node kid : kids) {
      if (kid != goalList && kid != logScroll)
        others += Math.ceil(kid.prefHeight(w));
    }
    double room = Math.max(0, h - others);
    double listH = room;
    double logH = 0;
    if (logScroll.isManaged()) {
      double full = floatListFull();
      double min = Math.min(floatListMin(), full);
      logH = Math.max(0, Math.min(FLOAT_LOG_HEIGHT, room - min));
      listH = room - logH;
      // 목록이 다 보이고도 남으면 목록은 제 키에 멈추고 남는 건 말 칸이 갖는다
      if (listH > full) {
        listH = full;
        logH = room - full;
      }
    }

    double y = in.getTop();
    for (javafx.scene.Node kid : kids) {
      double kh = kid == goalList ? listH : kid == logScroll ? logH : Math.ceil(kid.prefHeight(w));
      kid.resizeRelocate(in.getLeft(), y, w, kh);
      y += kh + gap;
    }
  }

  /** 띄운 판의 목표 목록이 줄어들 수 있는 끝 — 목표 줄 FLOAT_LIST_ROWS 개 키 */
  private double floatListMin() {
    return listInsets() + rowHeight("goal-item") * FLOAT_LIST_ROWS;
  }

  /** 띄운 판의 목표 목록이 줄을 다 보이는 키 — 갈래 머리 · 목표 · 새로 만들기 줄을 다 더한 만큼 */
  private double floatListFull() {
    double sum = listInsets();
    for (GoalStore.Goal goal : goalList.getItems())
      sum += rowHeight(isGroup(goal) ? "goal-group" : goal == NEW_ROW ? "goal-new-row" : "goal-item");
    return sum;
  }

  private double listInsets() {
    return goalList.snappedTopInset() + goalList.snappedBottomInset();
  }

  /** 잰 줄 높이 — 줄 종류 (칸 스타일 이름) 마다. 그려진 적 없으면 목표 줄 높이, 그것도 없으면 짐작 값 */
  private final java.util.Map<String, Double> rowHeights = new java.util.HashMap<>();

  private double rowHeight(String kind) {
    for (javafx.scene.Node cell : goalList.lookupAll("." + kind)) {
      double ch = cell.getLayoutBounds().getHeight();
      if (ch > 0) {
        Double was = rowHeights.put(kind, ch);
        // 짐작으로 나눴던 거면 잰 값으로 다시 나눈다
        if (was == null || Math.abs(was - ch) > 0.5)
          Platform.runLater(floatBody::requestLayout);
        break;
      }
    }
    Double known = rowHeights.get(kind);
    if (known != null)
      return known;
    Double item = rowHeights.get("goal-item");
    return item != null ? item : FLOAT_ROW_GUESS;
  }

  /** 지금 목표를 그리는 판 — 관계도면 graph, 아니면 map */
  private javafx.scene.Node view() {
    return isGraph() ? graph : map;
  }

  private boolean isGraph() {
    return current != null && current.graph();
  }

  /** 지금 목표를 파트너랑 같이 쪼개는지. 아직 목표가 없으면 (첫 말로 세울 참이면) 파트너가 있는지 */
  private boolean usesLlm() {
    return current == null ? llm.isReady() : current.usesLlm();
  }

  /** 기본 화면에서 ← 로 들어올 때. 치던 말이 있으면 들고 들어온다 */
  public void enterWith(String carriedText) {
    input.setText(carriedText == null ? "" : carriedText);
    input.positionCaret(input.getLength());
    // 옮기다 나갔으면 없던 일로
    cancelMove();
    // 그새 파트너가 바뀌었을 수 있다
    refreshPartner();
    showDefaultStatus();
    // 방금 화면에 붙은 입력칸이라 그냥 포커스를 주면 한글 조합이 안 보인다 (Ime 주석 참고)
    Platform.runLater(() -> Ime.focus(input));
  }

  // ── 키 ──

  private void inputKey(KeyEvent e) {
    KeyCode code = e.getCode();
    // 옮길 부모를 고르는 중엔 Enter 가 확인, Esc 가 취소 (↑↓ 로 고르는 건 그대로)
    if (moving != null && (code == KeyCode.ENTER || code == KeyCode.ESCAPE)) {
      e.consume();
      if (code == KeyCode.ENTER)
        confirmMove();
      else
        cancelMove();
      return;
    }
    if (code == KeyCode.ESCAPE) {
      e.consume();
      if (!onEscFull.getAsBoolean())
        onExit.run();
      return;
    }
    // 들어올 때 누른 ← 의 반대. 친 게 있으면 → 는 캐럿 옮기기라 Alt 를 같이 (Main 과 같은 규칙)
    if (code == KeyCode.RIGHT && (e.isAltDown() || input.getText().isEmpty())) {
      e.consume();
      onExit.run();
      return;
    }
    if ((code == KeyCode.UP || code == KeyCode.DOWN) && e.isAltDown()) {
      e.consume();
      switchGoal(code == KeyCode.UP ? -1 : 1);
      return;
    }
    if (code == KeyCode.UP || code == KeyCode.DOWN) {
      e.consume();
      moveSelection(code == KeyCode.UP ? -1 : 1);
      return;
    }
    if (code == KeyCode.ENTER) {
      e.consume();
      String text = input.getText().trim();
      if (isGraph()) {
        // 관계도: 친 이름으로 칸 — 고른 칸이 있으면 거기 이어서, 없으면 따로. 고른 칸은 그대로 둬서 줄줄이 붙인다
        if (!text.isEmpty() && addGraphNode(graph.selected(), text, false) != null)
          input.clear();
      } else if (current != null && !current.usesLlm()) {
        // 파트너 없는 트리: 친 이름으로 고른 칸 밑에 붙인다. 비었으면 이름 창
        if (text.isEmpty())
          addChild(selected());
        else if (addChildTitled(selected(), text, false))
          input.clear();
      } else if (current == null && !llm.isReady()) {
        // 파트너가 없으면 첫 말로 세우지 못하니 만들기 창으로
        if (!text.isEmpty())
          startNewDialog(text);
      } else if (text.isEmpty()) {
        split(selected());
      } else {
        send(text);
      }
    }
  }

  private void mapKey(KeyEvent e) {
    GoalStore.Node node = selected();
    // 옮길 부모를 고르는 중엔 화살표 (GoalMap) 와 확인 · 취소만
    if (moving != null) {
      switch (e.getCode()) {
        case ENTER -> confirmMove();
        case ESCAPE -> cancelMove();
        default -> {
          return;
        }
      }
      e.consume();
      return;
    }
    switch (e.getCode()) {
      case ESCAPE -> {
        if (!onEscFull.getAsBoolean())
          onExit.run();
      }
      case ENTER -> split(node);
      case SPACE -> toggleDone(node);
      case T -> toggleTodo(node);
      case F -> map.toggleFold(node);
      case N, INSERT -> addChild(node);
      case F2, E, R -> rename(node);
      case L -> leafKey(node);
      case M -> startMove(node);
      case DELETE, BACK_SPACE -> remove(node);
      default -> {
        return;
      }
    }
    e.consume();
  }

  /** 관계도 판의 키 — 화살표는 GraphMap 이 먼저 받는다 */
  private void graphKey(KeyEvent e) {
    GoalStore.Link link = graph.selectedLink();
    if (link != null && linkKey(e, link))
      return;
    GoalStore.Node node = graph.selected();
    switch (e.getCode()) {
      case ESCAPE -> {
        // 고른 칸이 있으면 먼저 푼다 — 그래야 N 이 따로 선 칸을 만든다
        if (node != null)
          graph.select(null);
        else if (!onEscFull.getAsBoolean())
          onExit.run();
      }
      case N, INSERT -> addGraphNode(node);
      case SPACE -> toggleDone(node);
      case F2, E, R -> rename(node);
      case DELETE, BACK_SPACE -> remove(node);
      default -> {
        return;
      }
    }
    e.consume();
  }

  /** 연결을 골랐을 때의 키. 받았으면 true — 나머지 키는 graphKey 가 칸 없이 받는다 */
  private boolean linkKey(KeyEvent e, GoalStore.Link link) {
    switch (e.getCode()) {
      case ESCAPE -> graph.select(null);
      case E, R -> {
        if (current.directed)
          flipLink(link);
        else
          showStatus("방향 없는 연결이야", false);
      }
      case DELETE, BACK_SPACE -> removeLink(link);
      default -> {
        return false;
      }
    }
    e.consume();
    return true;
  }

  private void moveSelection(int step) {
    if (current == null)
      return;
    List<GoalStore.Node> order = isGraph() ? graph.order() : map.order();
    if (order.isEmpty())
      return;
    int row = order.indexOf(selected());
    int next = row < 0 ? 0 : Math.max(0, Math.min(order.size() - 1, row + step));
    if (isGraph())
      graph.select(order.get(next));
    else
      map.select(order.get(next));
  }

  private void switchGoal(int step) {
    if (book.goals.isEmpty())
      return;
    int at = current == null ? (step > 0 ? -1 : book.goals.size()) : book.goals.indexOf(current);
    int next = Math.floorMod(at + step, book.goals.size());
    open(book.goals.get(next));
  }

  // ── 목표 갈아타기 ──

  private void open(GoalStore.Goal goal) {
    cancelMove();
    current = goal;
    book.current = goal.id;
    save();
    syncPicker();
    renderTree(goal.graph() ? null : goal.root.id);
    renderLog();
    refreshPartner();
    showDefaultStatus();
  }

  /** 새 목표를 세울 자리로. 목표는 첫 말을 보낼 때 생긴다 (send) — 빈 목표가 쌓이지 않게 */
  private void startNew() {
    cancelMove();
    current = null;
    book.current = null;
    save();
    syncPicker();
    renderTree(null);
    renderLog();
    refreshPartner();
    showDefaultStatus();
    Platform.runLater(() -> Ime.focus(input));
  }

  /** ＋ — 이름과 종류를 고르는 창을 띄워 새로 만든다. 취소면 그대로 */
  private void startNewDialog(String initial) {
    GoalKindDialog.Choice choice = GoalKindDialog.show(getScene() == null ? null : getScene().getWindow(), initial,
        llm.isReady(), splitWithPartner());
    if (choice == null)
      return;
    GoalStore.Goal goal = GoalStore.newGoal(choice.title(), choice.kind(), choice.llm(), choice.directed());
    book.goals.add(0, goal);
    input.clear();
    open(goal);
    // 만든 판이 뒤에 깔린 채로 — 고른 방식에 맞는 예시 그림을 보여준다
    if (!book.guideOff.contains(choice.kind())
        && GoalGuideDialog.show(getScene() == null ? null : getScene().getWindow(), choice.kind(), goal.llm,
            goal.directed, partnerWith())) {
      book.guideOff.add(choice.kind());
      save();
    }
    // 펴진 채엔 입력칸이 걷혀 있으니 판으로
    if (expanded)
      Platform.runLater(() -> view().requestFocus());
    else
      Platform.runLater(() -> Ime.focus(input));
  }

  private void syncPicker() {
    syncing = true;
    List<GoalStore.Goal> grouped = grouped();
    picker.getItems().setAll(grouped);
    picker.setValue(current);
    // 띄운 목록은 맨 끝에 새로 만들기 줄을 단다
    List<GoalStore.Goal> listed = new ArrayList<>(grouped);
    listed.add(NEW_ROW);
    goalList.getItems().setAll(listed);
    if (current == null) {
      goalList.getSelectionModel().clearSelection();
    } else {
      goalList.getSelectionModel().select(current);
      goalList.scrollTo(current);
    }
    syncing = false;
    syncFloatLog();
    floatBody.requestLayout();
  }

  /** 목표 목록 줄 — 목표 쪼개기 갈래, 관계도 갈래 차례로. 갈래마다 머리 줄 (TREES · GRAPHS) 을 앞에 단다 */
  private List<GoalStore.Goal> grouped() {
    List<GoalStore.Goal> trees = new ArrayList<>();
    List<GoalStore.Goal> graphs = new ArrayList<>();
    for (GoalStore.Goal goal : book.goals)
      (goal.graph() ? graphs : trees).add(goal);
    List<GoalStore.Goal> out = new ArrayList<>();
    if (!trees.isEmpty()) {
      out.add(TREES);
      out.addAll(trees);
    }
    if (!graphs.isEmpty()) {
      out.add(GRAPHS);
      out.addAll(graphs);
    }
    return out;
  }

  /** 목록의 갈래 머리 줄 — 고를 수 있는 목표가 아니다 */
  private static boolean isGroup(GoalStore.Goal goal) {
    return goal == TREES || goal == GRAPHS;
  }

  // ── 그리기 ──

  /** 맵을 다시 그린다. selectId 칸을 고른 채로 (없으면 맨 위 칸, 관계도면 안 고른다). 관계도면 graph 판을 띄운다 */
  private void renderTree(String selectId) {
    emptyHint.setVisible(current == null);
    boolean isGraph = isGraph();
    map.setVisible(!isGraph);
    graph.setVisible(isGraph);
    map.show(isGraph ? null : current, selectId);
    graph.show(isGraph ? current : null, selectId);
    syncMode();
  }

  /** 보는 법 단추를 지금 관계도에 맞춘다. 관계도가 아니면 숨긴다 */
  private void syncMode() {
    boolean isGraph = isGraph();
    modeBar.setVisible(isGraph);
    freeMode.setSelected(isGraph && !current.layered);
    rootMode.setSelected(isGraph && current.layered);
  }

  /** 관계도를 루트부터 늘어놓아 볼지 (아니면 자유 그래프). 고른 칸 · 연결은 그대로 */
  private void setLayered(boolean on) {
    if (!isGraph())
      return;
    if (current.layered != on) {
      current.layered = on;
      save();
      graph.refresh();
    }
    syncMode();
    graph.requestFocus();
  }

  /** 이름 · 메모 · 표시가 바뀌었을 때 지금 판을 다시 그린다 */
  private void refreshView() {
    if (isGraph())
      graph.refresh();
    else
      map.refresh();
  }

  private void renderLog() {
    log.getChildren().clear();
    if (current == null)
      return;
    for (GoalStore.Line line : current.log)
      log.getChildren().add(bubble(line, log.getChildren().size()));
  }

  /** index 는 대화 속 몇 번째 말인지 — 파스텔 테마는 이 차례로 상대 말풍선 색을 돌린다 (Theme.cycleClass) */
  private HBox bubble(GoalStore.Line line, int index) {
    Label text = new Label(line.text);
    text.setWrapText(true);
    text.getStyleClass().addAll("chat-bubble", "goal-bubble", line.mine ? "chat-mine" : "chat-theirs");
    if (!line.mine)
      text.getStyleClass().add(Theme.cycleClass(index / 2));
    text.setMaxWidth(Double.MAX_VALUE);
    text.setContextMenu(bubbleMenu(line));
    HBox row = new HBox(text);
    row.setAlignment(line.mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    // 상대 쪽으로 조금 비워둬야 누가 한 말인지 한눈에 보인다
    row.setPadding(line.mine ? new Insets(0, 0, 0, 18) : new Insets(0, 18, 0, 0));
    return row;
  }

  /**
   * 말풍선 오른쪽 클릭 메뉴 — 복사 · 지우기.
   * 마도카한테는 말 대신 트리만 보여주니까 (send) 말을 지워도 마도카가 아는 건 그대로다.
   */
  private ContextMenu bubbleMenu(GoalStore.Line line) {
    MenuItem copy = new MenuItem("복사");
    copy.setOnAction(e -> {
      ClipboardContent content = new ClipboardContent();
      content.putString(line.text);
      Clipboard.getSystemClipboard().setContent(content);
      showStatus("복사했어!", false);
    });
    MenuItem remove = new MenuItem("지우기");
    remove.setOnAction(e -> removeLine(line));
    return new ContextMenu(copy, remove);
  }

  private void removeLine(GoalStore.Line line) {
    if (current == null || !current.log.remove(line))
      return;
    save();
    // 말풍선 색이 차례로 돌아서 (Theme.cycleClass) 하나만 빼지 않고 다시 그린다
    renderLog();
  }

  private void addLine(GoalStore.Goal goal, boolean mine, String text) {
    GoalStore.Line line = GoalStore.line(mine, text);
    goal.log.add(line);
    if (goal == current)
      log.getChildren().add(bubble(line, log.getChildren().size()));
  }

  private GoalStore.Node selected() {
    return isGraph() ? graph.selected() : map.selected();
  }

  // ── 손으로 하는 것 (맵 키 · 오른쪽 클릭) ──

  /** 고른 칸을 쪼개 달라고 마도카한테 보낸다. 말단 칸은 더 쪼개지 않는다 */
  private void split(GoalStore.Node node) {
    if (node == null)
      return;
    if (!usesLlm()) {
      addChild(node);
      return;
    }
    if (node.leaf) {
      showStatus("말단 칸은 더 안 쪼개. 말단을 풀거나 " + partnerName() + "한테 물어봐", false);
      return;
    }
    // 말 칸엔 칸 이름만 — "쪼개줘" 는 마도카한테만 간다
    send(node.title, "「" + node.title + "」 쪼개줘");
  }

  private void toggleDone(GoalStore.Node node) {
    if (node == null)
      return;
    boolean done = !node.done;
    if (isGraph()) {
      // 관계도는 할 일로 안 내리고, 이어진 칸도 그대로 둔다
      node.done = done;
      save();
      graph.refresh();
      return;
    }
    List<GoalStore.Node> changed = setDone(node, done);
    save();
    map.refresh();
    syncTodos(current, changed, done);
  }

  /**
   * 할 일에서 목표 맵에서 내려온 걸 체크하거나 풀었을 때 (Main 이 단다) — 그 칸도 끝냄 · 안 끝냄으로.
   * 같은 칸에서 내려온 다른 할 일(다른 날에 남은 것)도 syncTodos 로 맞춘다
   */
  public void markDone(String goalId, String nodeId, boolean done) {
    for (GoalStore.Goal goal : book.goals) {
      if (!goal.id.equals(goalId))
        continue;
      GoalStore.Node node = goal.find(nodeId);
      if (node == null || node.done == done)
        return;
      List<GoalStore.Node> changed = setDone(node, done);
      save();
      if (goal == current)
        map.refresh();
      syncTodos(goal, changed, done);
      return;
    }
  }

  /**
   * 칸을 끝냈으면 (안 끝낸 걸로 돌렸으면) 그 칸에서 내려간 할 일도 똑같이 (TodoPane.finishGoal).
   * 끝낼 땐 밑에 칸 할 일도 다 끝낸다 — 칸은 벌써 끝냄이라 setDone 이 안 돌려준 것까지
   */
  private void syncTodos(GoalStore.Goal goal, List<GoalStore.Node> nodes, boolean done) {
    if (goal == null || nodes.isEmpty())
      return;
    Set<String> ids = new LinkedHashSet<>();
    for (GoalStore.Node node : nodes)
      collectIds(node, done, ids);
    todoSync.finish(goal.id, new ArrayList<>(ids), done);
  }

  private static void collectIds(GoalStore.Node node, boolean deep, Set<String> ids) {
    ids.add(node.id);
    if (deep)
      for (GoalStore.Node child : node.children)
        collectIds(child, true, ids);
  }

  private void remove(GoalStore.Node node) {
    if (node == null || current == null)
      return;
    if (node == current.root && isGraph()) {
      // 관계도는 설정 창에서 루트 이름을 쳐야만 지운다 — 루트에서 Delete 면 그 확인 칸을 펴서 띄운다
      openGraphSettings(true);
      return;
    }
    if (node == current.root) {
      int choice = Notice.ask(getScene().getWindow(),
          "「" + node.title + "」 목표를 통째로 지울게.\n쪼갠 칸이랑 마케몬이랑 나눈 말도 같이 사라져.", "지우기", "취소");
      if (choice != 0)
        return;
      removeCurrentGoal();
      return;
    }
    if (isGraph()) {
      removeGraphNode(node);
      return;
    }
    GoalStore.Node parent = current.parentOf(node);
    if (parent == null)
      return;
    // 잘못 눌러 지우는 일 없게 늘 묻는다
    String question = node.children.isEmpty()
        ? "「" + node.title + "」 칸을 지울까?"
        : "「" + node.title + "」 밑에 칸이 " + count(node) + "개 있어. 같이 지울까?";
    if (Notice.ask(getScene().getWindow(), question, "지우기", "취소") != 0)
      return;
    // 지운 자리 바로 위 형제(없으면 부모)를 고른 채로
    int index = parent.children.indexOf(node);
    parent.children.remove(node);
    save();
    renderTree(index > 0 ? parent.children.get(index - 1).id : parent.id);
  }

  /** 지금 목표를 통째로 지우고 남은 첫 목표로 (없으면 새로 세울 자리로) */
  private void removeCurrentGoal() {
    book.goals.remove(current);
    if (book.goals.isEmpty())
      startNew();
    else
      open(book.goals.get(0));
  }

  /**
   * 관계도 설정 창 (빈 데 오른쪽 클릭 "설정…") — 무향 · 유향을 바로 바꾸고, 루트 이름을 똑같이 쳐서 확인하면 관계도를 지운다.
   * askDelete 면 지우기 확인 칸을 펴서 띄운다 (루트에서 Delete)
   */
  private void openGraphSettings(boolean askDelete) {
    if (!isGraph())
      return;
    GoalStore.Goal goal = current;
    boolean delete = GraphSettingsDialog.show(getScene().getWindow(), goal.root.title, goal.directed, this::setDirected,
        askDelete);
    if (delete && goal == current) {
      removeCurrentGoal();
      showStatus("「" + goal.root.title + "」 관계도를 지웠어", false);
    }
    if (expanded)
      Platform.runLater(() -> view().requestFocus());
  }

  /** 파트너 없이 손으로 자식 칸 하나를 붙인다. 붙인 칸을 고른 채로 */
  private void addChild(GoalStore.Node parent) {
    if (parent == null || current == null)
      return;
    if (parent.leaf) {
      showStatus("말단 칸엔 칸을 못 붙여. 말단을 먼저 풀어줘", false);
      return;
    }
    String title = GoalTitleDialog.show(getScene().getWindow(), "➕ 「" + parent.title + "」 밑에 칸 붙이기", "");
    if (title != null)
      addChildTitled(parent, title, true);
  }

  /** parent 밑에 title 칸을 붙인다. selectChild 면 붙인 칸을, 아니면 parent 를 고른 채로. 붙였으면 true */
  private boolean addChildTitled(GoalStore.Node parent, String title, boolean selectChild) {
    if (parent == null || current == null)
      return false;
    if (parent.leaf) {
      showStatus("말단 칸엔 칸을 못 붙여. 말단을 먼저 풀어줘", false);
      return false;
    }
    if (hasChild(parent, title)) {
      showStatus("「" + title + "」 칸은 벌써 있어!", false);
      return false;
    }
    GoalStore.Node child = current.node(title);
    parent.children.add(child);
    // 붙인 칸이 보이게 펴둔다
    parent.folded = false;
    save();
    renderTree(selectChild ? child.id : parent.id);
    return true;
  }

  // ── 관계도 ──

  /** N — 고른 칸이 있으면 이어진 칸, 없으면 따로 선 칸. 이름은 창으로 받고, 만든 칸을 고른 채로 */
  private void addGraphNode(GoalStore.Node near) {
    if (!isGraph())
      return;
    String heading = near == null ? "➕ 따로 선 칸 만들기" : "➕ 「" + near.title + "」에 이은 칸 만들기";
    String title = GoalTitleDialog.show(getScene().getWindow(), heading, "");
    if (title != null)
      addGraphNode(near, title, true);
  }

  /**
   * 관계도에 title 칸을 만든다. near 가 있으면 그 둘레 빈자리에 두고 near → 새 칸으로 잇는다,
   * 없으면 마우스 자리 (판 밖이면 화면 가운데) 둘레에 따로 세운다. select 면 새 칸을, 아니면 near 를 고른 채로
   */
  private GoalStore.Node addGraphNode(GoalStore.Node near, String title, boolean select) {
    if (!isGraph())
      return null;
    Point2D at = graph.freeSpot(near);
    GoalStore.Node node = current.node(title);
    node.x = at.getX();
    node.y = at.getY();
    current.nodes.add(node);
    if (near != null)
      current.connect(near, node);
    save();
    renderTree(select || near == null ? node.id : near.id);
    if (!select && near == null)
      graph.select(null);
    return node;
  }

  /** Ctrl+끌기를 빈 데 놓았을 때 — 이름을 받아 놓은 자리 (겹치면 그 둘레 빈자리) 에 from 에서 이은 칸을 만들고 고른다 */
  private void sproutGraphNode(GoalStore.Node from, Point2D at) {
    if (!isGraph() || current.find(from.id) != from)
      return;
    String title = GoalTitleDialog.show(getScene().getWindow(), "➕ 「" + from.title + "」에 이은 칸 만들기", "");
    if (title == null)
      return;
    Point2D spot = graph.freeSpotAround(at);
    GoalStore.Node node = current.node(title);
    node.x = spot.getX();
    node.y = spot.getY();
    current.nodes.add(node);
    current.connect(from, node);
    save();
    renderTree(node.id);
  }

  /**
   * Ctrl+끌기 — from 에서 to 로 잇는다. 이미 이어져 있으면 방향 있는 관계도에선 방향을 고친다 —
   * 안 정한 연결은 from → to 로, to → from 이던 건 양방향으로
   */
  private void connect(GoalStore.Node from, GoalStore.Node to) {
    if (!isGraph())
      return;
    String pair = "「" + from.title + "」 · 「" + to.title + "」";
    GoalStore.Link link = current.link(from, to);
    if (link == null) {
      current.connect(from, to);
      save();
      renderTree(to.id);
      showStatus("「" + from.title + "」 " + (current.directed ? "→" : "—") + " 「" + to.title + "」 이었어!", false);
      return;
    }
    if (!current.directed || link.both() || (link.one() && link.from.equals(from.id))) {
      showStatus(pair + " 벌써 이어져 있어", false);
      return;
    }
    if (link.one()) {
      setDir(link, GoalStore.Link.BOTH, null);
      showStatus(pair + " 양방향이 됐어!", false);
    } else {
      setDir(link, GoalStore.Link.ONE, from);
      showStatus("「" + from.title + "」 → 「" + to.title + "」 방향을 정했어!", false);
    }
  }

  /** 연결 방향을 바꾼다. ONE 이면 start 에서 나가게 (null 이면 지금 그대로) */
  private void setDir(GoalStore.Link link, String dir, GoalStore.Node start) {
    if (!isGraph())
      return;
    if (start != null && !link.from.equals(start.id)) {
      link.to = link.from;
      link.from = start.id;
    }
    link.dir = dir;
    save();
    graph.refresh();
  }

  /** 관계도 칸을 지운다 — 그 칸에 붙은 연결도 같이. 이어진 칸들은 그대로 남는다 */
  private void removeGraphNode(GoalStore.Node node) {
    long links = current.links.stream().filter(l -> l.touches(node.id)).count();
    String question = links == 0 ? "「" + node.title + "」 칸을 지울까?"
        : "「" + node.title + "」 칸을 지울까? 연결 " + links + "개도 같이 끊겨";
    if (Notice.ask(getScene().getWindow(), question, "지우기", "취소") != 0)
      return;
    current.nodes.remove(node);
    current.links.removeIf(l -> l.touches(node.id));
    save();
    renderTree(null);
  }

  private void removeLink(GoalStore.Link link) {
    if (!isGraph() || !current.links.remove(link))
      return;
    save();
    graph.refresh();
  }

  /** 한 방향 연결을 뒤집는다. 양방향 · 안 정한 연결은 뒤집을 게 없다 */
  private void flipLink(GoalStore.Link link) {
    if (!isGraph())
      return;
    if (!link.one()) {
      showStatus(link.both() ? "양방향 연결이야" : "아직 방향을 안 정했어 — Ctrl+끌기로 정해 줘", false);
      return;
    }
    String from = link.from;
    link.from = link.to;
    link.to = from;
    save();
    graph.refresh();
  }

  /**
   * 관계도를 방향 있게 · 없게. 연결마다 정해 둔 방향은 지우지 않고 남겨서, 다시 방향을 달면 되살아난다 —
   * 방향 없을 때 이은 연결은 안 정한 채로 (화살촉 없이) 남는다
   */
  private void setDirected(boolean on) {
    if (!isGraph() || current.directed == on)
      return;
    current.directed = on;
    save();
    graph.refresh();
    showStatus(on ? "이제 연결에 방향이 있어 (화살표) — 안 정한 연결은 Ctrl+끌기로 정해 줘" : "이제 연결에 방향이 없어", false);
  }

  /** 칸 이름을 손으로 고친다. 맨 위 칸이면 목표 이름도 바뀌니 목록도 맞춘다 */
  private void rename(GoalStore.Node node) {
    if (node == null || current == null)
      return;
    String title = GoalTitleDialog.show(getScene().getWindow(), "✏ 칸 이름 고치기", node.title);
    if (title == null || title.equals(node.title))
      return;
    GoalStore.Node parent = current.parentOf(node);
    if (parent != null && !title.equalsIgnoreCase(node.title) && hasChild(parent, title)) {
      showStatus("「" + title + "」 칸은 벌써 있어!", false);
      return;
    }
    node.title = title;
    save();
    if (node == current.root)
      syncPicker();
    refreshView();
  }

  /** 칸 메모 창. 닫히면 적힌 대로 저장하고, 비워 두고 닫으면 메모를 지운다 */
  private void openNote(GoalStore.Node node) {
    if (node == null)
      return;
    GoalStore.Goal goal = current;
    // 파트너 없이 쓰는 목표면 자동 생성 단추를 안 띄운다
    String text = GoalNoteDialog.show(getScene().getWindow(), node.title, node.note, !goal.usesLlm() ? null : () -> {
      if (!llm.isReady())
        throw new IllegalStateException(llm.unavailableReason());
      String message = "[지금 트리]\n" + outline(goal.root, 0) + "\n[메모 칸] [" + node.id + "] " + node.title;
      return llm.chat(NOTE_SYSTEM.replace("{이름}", partnerName()), List.of(new LlmClient.Message(true, message)),
          LlmClient.Mode.FAST);
    }).strip();
    if (text.equals(node.note == null ? "" : node.note.strip()))
      return;
    node.note = text.isEmpty() ? null : text;
    save();
    refreshView();
  }

  /** 같이 쪼개고 메모 · 말단 설명을 해 줄 파트너 이름. 파트너가 없으면 마도카 */
  private static String partnerName() {
    ChatProfile partner = SettingsDialog.load().mainPartner();
    return (partner != null ? partner : ChatProfile.MAKEMON).displayName();
  }

  /** 파트너 이름이 들어간 글들을 지금 파트너 · 지금 목표 종류로 다시 쓴다 */
  private void refreshPartner() {
    String name = partnerName();
    if (isGraph())
      input.setPromptText("칸 이름 치고 Enter = 고른 칸에 이어 붙이기 (안 골랐으면 따로)");
    else if (!usesLlm())
      input.setPromptText("칸 이름 치고 Enter = 고른 칸 밑에 붙이기");
    else
      input.setPromptText(splitWithPartner() + " (빈칸 Enter = 고른 칸 쪼개기)");
    emptyHint.setText(llm.isReady()
        ? "이루고 싶은 걸 말해줘!\n" + name + GoalMap.subjectParticle(name) + " 같이 쪼개 줄게 ✂\n(＋ 로 관계도도 만들 수 있어)"
        : "＋ 로 목표 쪼개기나 관계도를 만들어 봐 🌱");
  }

  /** "마도카가 생각 중…" 처럼 — 받침 따라 이/가 */
  private static String partnerThinking() {
    String name = partnerName();
    return name + GoalMap.subjectParticle(name) + " 생각 중…";
  }

  /** "마도카랑 쪼개기" 처럼 — 받침 따라 이랑/랑 */
  private static String splitWithPartner() {
    return partnerWith() + " 쪼개기";
  }

  /** "마도카랑" 처럼 파트너 이름에 "(이)랑" 을 붙인 것 */
  private static String partnerWith() {
    String name = partnerName();
    return name + ("이".equals(GoalMap.subjectParticle(name)) ? "이랑" : "랑");
  }

  private void toggleLeaf(GoalStore.Node node) {
    if (node == null)
      return;
    node.leaf = !node.leaf;
    save();
    map.refresh();
  }

  /** 자식이 있는 칸은 말단이 될 수 없다. 이미 말단이면 풀 수는 있게 둔다 */
  private static boolean canLeaf(GoalStore.Node node) {
    return node.leaf || node.children.isEmpty();
  }

  /** 말단이 될 수 있는 자식 — 밑에 칸이 또 없는 자식 (canLeaf). 이게 하나라도 있어야 말단의 부모로 정할 수 있다 */
  private static List<GoalStore.Node> leafChildren(GoalStore.Node node) {
    List<GoalStore.Node> out = new ArrayList<>();
    for (GoalStore.Node child : node.children) {
      if (canLeaf(child))
        out.add(child);
    }
    return out;
  }

  /** 자식들이 다 말단이면 (말단의 부모면) true */
  private static boolean isLeafParent(GoalStore.Node node) {
    List<GoalStore.Node> kids = leafChildren(node);
    if (kids.isEmpty())
      return false;
    for (GoalStore.Node child : kids) {
      if (!child.leaf)
        return false;
    }
    return true;
  }

  /**
   * 말단의 부모로 정한다 — 자식 칸들을 한꺼번에 말단으로. 이미 말단의 부모면 자식 말단을 다 푼다.
   * 밑에 칸이 또 있는 자식은 말단이 될 수 없어서 건너뛴다
   */
  private void toggleLeafParent(GoalStore.Node node) {
    if (node == null)
      return;
    List<GoalStore.Node> kids = leafChildren(node);
    if (kids.isEmpty())
      return;
    boolean on = !isLeafParent(node);
    for (GoalStore.Node child : kids)
      child.leaf = on;
    // 말단이 된 칸들이 보이게 펴둔다
    node.folded = false;
    save();
    map.refresh();
    int skipped = node.children.size() - kids.size();
    if (on && skipped > 0)
      showStatus("밑에 칸이 있는 " + skipped + "개는 말단으로 못 정해서 뺐어", false);
  }

  /** L — 말단으로 정할 수 있는 칸이면 말단, 아니면 말단의 부모 (메뉴와 같은 자리) */
  private void leafKey(GoalStore.Node node) {
    if (node == null)
      return;
    if (canLeaf(node))
      toggleLeaf(node);
    else if (!leafChildren(node).isEmpty())
      toggleLeafParent(node);
    else
      showStatus("자식 칸들 밑에 칸이 또 있어서 말단으로 못 정해", false);
  }

  // ── 칸 옮기기 ──

  /** 옮기기 시작 — 옮길 칸은 점선으로, 맵 밑에 확인 · 취소 줄을 띄우고 지금 부모를 고른 채로 둔다 */
  private void startMove(GoalStore.Node node) {
    if (node == null || current == null)
      return;
    GoalStore.Node parent = current.parentOf(node);
    if (parent == null) {
      showStatus("맨 위 칸은 못 옮겨", false);
      return;
    }
    moving = node;
    moveHint.setText("「" + node.title + "」 옮길 부모 칸을 골라줘");
    moveBar.setVisible(true);
    map.setMoving(node);
    map.select(parent);
    map.requestFocus();
    showStatus("↑↓←→ · 클릭으로 새 부모 칸 고르기 · Enter 확인 · Esc 취소", false);
  }

  /** 고른 칸 밑으로 옮긴다 (밑의 칸까지 통째로). 못 가는 데를 골랐으면 알려주고 계속 고르게 둔다 */
  private void confirmMove() {
    if (moving == null || current == null)
      return;
    GoalStore.Node node = moving;
    GoalStore.Node target = selected();
    GoalStore.Node parent = current.parentOf(node);
    if (parent == null) {
      // 기다리던 답이 그 칸을 지웠을 수 있다
      endMove();
      renderTree(null);
      showStatus("옮기던 칸이 없어졌어…", true);
      return;
    }
    if (target == null)
      return;
    if (target == node || contains(node, target)) {
      showStatus("자기 자신이나 자기 밑의 칸으로는 못 옮겨", true);
      return;
    }
    // 이름이 같은 칸에 옮기면 — 합칠지, 그 밑에 자식으로 둘지 묻는다 (지금 부모면 자식으로는 이미 있으니 합치기만)
    if (target.title.equalsIgnoreCase(node.title)) {
      boolean asChild = target != parent;
      int choice = asChild
          ? Notice.ask(getScene().getWindow(), "「" + target.title + "」 칸이랑 이름이 같아. 어떡할까?", "취소", "합치기", "자식으로 정하기")
          : Notice.ask(getScene().getWindow(), "「" + target.title + "」 칸이랑 이름이 같아. 합칠까?", "취소", "합치기");
      if (choice == 1) {
        mergeMove(node, parent, target);
        return;
      }
      if (choice != 2)
        return;
    } else if (target == parent) {
      showStatus("벌써 「" + target.title + "」 밑에 있어. 다른 칸을 골라줘", false);
      return;
    }
    if (target.leaf) {
      showStatus("말단 칸 밑으로는 못 옮겨. 말단을 먼저 풀어줘", true);
      return;
    }
    // 이름이 같은 칸의 부모에 옮기면 — 그 자식 칸이랑 합칠지 묻는다
    GoalStore.Node twin = childNamed(target, node.title);
    if (twin != null) {
      int choice = Notice.ask(getScene().getWindow(),
          "「" + target.title + "」 밑에 「" + twin.title + "」 칸이 벌써 있어. 어떡할까?", "취소", "자식 칸이랑 합치기");
      if (choice == 1)
        mergeMove(node, parent, twin);
      return;
    }
    parent.children.remove(node);
    target.children.add(node);
    // 옮긴 칸이 보이게 펴둔다
    target.folded = false;
    endMove();
    save();
    renderTree(node.id);
    showStatus("「" + node.title + "」 칸을 「" + target.title + "」 밑으로 옮겼어!", false);
  }

  /** node 를 떼어 into 칸에 합친다 (밑의 칸까지). 합친 칸을 고른 채로 */
  private void mergeMove(GoalStore.Node node, GoalStore.Node parent, GoalStore.Node into) {
    parent.children.remove(node);
    Map<String, String> relink = new LinkedHashMap<>();
    merge(into, node, relink);
    // 합친 칸이 보이게 펴둔다
    into.folded = false;
    endMove();
    save();
    renderTree(into.id);
    // 없어진 칸에서 내려간 할 일은 남기고, 합쳐진 칸으로 다시 잇는다
    try {
      if (todoStore.relinkGoal(current.id, relink) > 0)
        onTodo.run();
    } catch (IOException e) {
      e.printStackTrace();
      showStatus("합쳤는데 할 일을 못 이었어: " + e.getMessage(), true);
      return;
    }
    showStatus("「" + node.title + "」 칸을 「" + into.title + "」 칸에 합쳤어!", false);
  }

  /**
   * from 을 into 에 합친다. 이름이 같은 자식끼리는 또 합치고, 나머지 자식은 into 밑으로 옮긴다.
   * 메모는 이어 붙이고, 둘 다 끝냈을 때만 끝낸 칸 · 밑에 칸이 생기면 말단은 푼다.
   * 없어지는 칸 id → 합쳐진 칸 id 를 relink 에 적는다 (할 일 다시 잇기)
   */
  private static void merge(GoalStore.Node into, GoalStore.Node from, Map<String, String> relink) {
    relink.put(from.id, into.id);
    into.done = into.done && from.done;
    into.todo = into.todo || from.todo;
    if (from.note != null && !from.note.isBlank())
      into.note = into.note == null || into.note.isBlank() ? from.note : into.note.strip() + "\n\n" + from.note.strip();
    for (GoalStore.Node child : from.children) {
      GoalStore.Node twin = childNamed(into, child.title);
      if (twin != null)
        merge(twin, child, relink);
      else
        into.children.add(child);
    }
    if (!into.children.isEmpty())
      into.leaf = false;
    else
      into.leaf = into.leaf || from.leaf;
  }

  /** 옮기기를 없던 일로. 옮기던 칸을 다시 고른 채로 */
  private void cancelMove() {
    if (moving == null)
      return;
    GoalStore.Node node = moving;
    endMove();
    renderTree(node.id);
    showDefaultStatus();
  }

  private void endMove() {
    moving = null;
    moveBar.setVisible(false);
    map.setMoving(null);
  }

  /** at 밑 어딘가에 node 가 있는지 */
  private static boolean contains(GoalStore.Node at, GoalStore.Node node) {
    for (GoalStore.Node child : at.children) {
      if (child == node || contains(child, node))
        return true;
    }
    return false;
  }

  /**
   * 말단 칸을 파트너한테 묻는다. 파트너가 그 칸을 설명해 주면 말 칸에 띄우고 칸 메모에도 쌓은 뒤,
   * 그 칸을 할 일 "목표" 카테고리 (TodoStore.GOAL_CATEGORY) 에 넣는다. 할 일은 목표 id · 칸 id 로 이어둬서
   * 칸이나 목표 이름을 바꿔도 "목표로 이동하기" 가 이 칸을 찾아온다 (reveal).
   */
  private void askLeaf(GoalStore.Node node) {
    if (node == null || current == null)
      return;
    if (asking) {
      showStatus("아직 앞의 말을 생각 중이야… 답 오면 다시 물어봐줘", false);
      return;
    }
    if (!llm.isReady()) {
      showStatus(llm.unavailableReason(), true);
      return;
    }

    GoalStore.Goal goal = current;
    String partner = partnerName();
    addLine(goal, true, "「" + node.title + "」 설명해줘");
    save();
    String message = "[지금 트리]\n" + outline(goal.root, 0) + "\n[물어본 칸] [" + node.id + "] " + node.title;

    setLocked(true);
    showStatus(partner + "한테 묻는 중…", false);

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.chat(LEAF_SYSTEM.replace("{이름}", partner), List.of(new LlmClient.Message(true, message)),
            LlmClient.Mode.FAST);
      }
    };
    task.setOnSucceeded(e -> {
      setLocked(false);
      String reply = task.getValue() == null ? "" : task.getValue().strip();
      if (reply.isEmpty())
        reply = "음… 지금은 설명이 잘 안 떠올라. 그래도 할 일에 넣어둘게!";
      addLine(goal, false, reply);
      note(node, reply);
      save();
      showDefaultStatus();
      try {
        int added = todoStore.addGoal(TodoStore.GOAL_CATEGORY, TodoStore.GOAL_CATEGORY_TITLE, goal.id,
            List.of(new TodoStore.GoalItem(node.id, node.title)));
        node.todo = true;
        save();
        if (goal == current)
          map.refresh();
        onTodo.run();
        showStatus(added > 0 ? "할 일 목표에 넣었어!" : "이미 오늘 할 일에 있어!", false);
      } catch (IOException ex) {
        ex.printStackTrace();
        showStatus("할 일에 못 넣었어: " + ex.getMessage(), true);
      }
    });
    task.setOnFailed(e -> {
      setLocked(false);
      Throwable ex = task.getException();
      showStatus("실패했어 ㅠㅠ " + (ex == null ? "" : ex.getMessage()), true);
    });

    Thread worker = new Thread(task, "goal-leaf");
    worker.setDaemon(true);
    worker.start();
  }

  /**
   * 할 일의 "목표로 이동하기" — 그 목표를 열고 칸을 고른다. 접힌 가지 안이면 펴서 보이게.
   * 목표 · 칸 모두 id 로 찾으니 이름이 바뀌었어도 찾아간다. Main 이 이 화면으로 바꾼 뒤에 부른다.
   */
  public void reveal(String goalId, String nodeId) {
    GoalStore.Goal goal = null;
    for (GoalStore.Goal g : book.goals) {
      if (g.id.equals(goalId))
        goal = g;
    }
    if (goal == null) {
      showStatus("그 목표는 지워졌나 봐…", true);
      return;
    }
    GoalStore.Node node = goal.find(nodeId);
    for (GoalStore.Node at = node == null ? null : goal.parentOf(node); at != null; at = goal.parentOf(at))
      at.folded = false;
    open(goal);
    if (node == null) {
      showStatus("그 칸은 지워졌나 봐… 목표만 열어둘게", true);
      return;
    }
    renderTree(node.id);
  }

  private static int count(GoalStore.Node node) {
    int n = 0;
    for (GoalStore.Node child : node.children)
      n += 1 + count(child);
    return n;
  }

  /** 칸을 끝냄 · 안 끝냄으로. 끝낼 땐 밑에 칸도 모두 같이 끝낸다 — 실제로 바뀐 칸들을 돌려준다 */
  private static List<GoalStore.Node> setDone(GoalStore.Node node, boolean done) {
    List<GoalStore.Node> changed = new ArrayList<>();
    if (node.done != done) {
      node.done = done;
      changed.add(node);
    }
    if (done)
      for (GoalStore.Node child : node.children)
        changed.addAll(setDone(child, true));
    return changed;
  }

  /**
   * 칸들을 오늘 할 일로 내린다. 카테고리는 목표마다 하나 — 처음 만들 때 이름은 목표 이름이고,
   * 찾는 건 목표 id 로 해서 목표나 카테고리 이름을 바꿔도 같은 데로 모인다 (TodoStore.addGoal)
   */
  private void addTodos(GoalStore.Goal goal, List<GoalStore.Node> nodes) {
    if (goal == null || nodes.isEmpty())
      return;
    List<TodoStore.GoalItem> items = new ArrayList<>();
    for (GoalStore.Node node : nodes)
      items.add(new TodoStore.GoalItem(node.id, node.title));
    try {
      int added = todoStore.addGoal("goal-" + goal.id, goal.root.title, goal.id, items);
      for (GoalStore.Node node : nodes)
        node.todo = true;
      save();
      if (goal == current)
        map.refresh();
      onTodo.run();
      showStatus(added == 0 ? "이미 오늘 할 일에 있어!" : "오늘 할 일에 " + added + "개 넣었어!", false);
    } catch (IOException e) {
      e.printStackTrace();
      showStatus("할 일에 못 넣었어: " + e.getMessage(), true);
    }
  }

  /** 할 일에 넣은 칸이면 빼고, 아니면 넣는다 (메뉴 · T) */
  private void toggleTodo(GoalStore.Node node) {
    if (node == null || current == null)
      return;
    if (node.todo)
      removeTodo(current, node);
    else
      addTodos(current, List.of(node));
  }

  /** 그 칸에서 내려간 할 일을 오늘 (과 미뤄둔 날) 할 일에서 지운다 (TodoStore.removeGoal) */
  private void removeTodo(GoalStore.Goal goal, GoalStore.Node node) {
    try {
      todoStore.removeGoal(goal.id, List.of(node.id));
      node.todo = false;
      save();
      if (goal == current)
        map.refresh();
      onTodo.run();
      showStatus("할 일에서 뺐어!", false);
    } catch (IOException e) {
      e.printStackTrace();
      showStatus("할 일에서 못 뺐어: " + e.getMessage(), true);
    }
  }

  /** 할 일에서 목표 맵에서 내려온 걸 손으로 지웠을 때 (Main 이 단다) — 그 칸도 할 일에서 빠진 걸로 */
  public void dropTodo(String goalId, String nodeId) {
    for (GoalStore.Goal goal : book.goals) {
      if (!goal.id.equals(goalId))
        continue;
      GoalStore.Node node = goal.find(nodeId);
      if (node == null || !node.todo)
        return;
      node.todo = false;
      save();
      if (goal == current)
        map.refresh();
      return;
    }
  }

  // ── 마도카한테 묻기 ──

  private void send(String text) {
    send(text, text);
  }

  /** shown 은 말 칸에 적히는 내 말, text 는 마도카한테 실제로 가는 말 */
  private void send(String shown, String text) {
    if (asking) {
      showStatus(partnerName() + GoalMap.subjectParticle(partnerName()) + " 아직 앞의 말을 생각 중이야… 답 오면 다시 보내줘", false);
      return;
    }
    if (!llm.isReady()) {
      showStatus(llm.unavailableReason(), true);
      return;
    }

    if (current == null) {
      GoalStore.Goal goal = GoalStore.newGoal(text.length() > TITLE_MAX ? text.substring(0, TITLE_MAX) + "…" : text);
      book.goals.add(0, goal);
      open(goal);
    }

    GoalStore.Goal goal = current;
    GoalStore.Node focus = selected();
    input.clear();
    addLine(goal, true, shown);
    save();

    String message = "[지금 트리]\n" + outline(goal.root, 0)
        + "\n[고른 칸] " + (focus == null ? "없음" : "[" + focus.id + "] " + focus.title)
        + "\n\n[사용자] " + text;

    String system = SYSTEM.replace("{이름}", partnerName());
    setLocked(true);
    showStatus(partnerThinking(), false);

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.chat(system, List.of(new LlmClient.Message(true, message)), LlmClient.Mode.FAST);
      }
    };
    task.setOnSucceeded(e -> {
      setLocked(false);
      apply(goal, task.getValue(), focus == null ? null : focus.id);
    });
    task.setOnFailed(e -> {
      setLocked(false);
      Throwable ex = task.getException();
      showStatus("실패했어 ㅠㅠ " + (ex == null ? "" : ex.getMessage()), true);
    });

    Thread worker = new Thread(task, "goal-ask");
    worker.setDaemon(true);
    worker.start();
  }

  /** 마도카한테 보여줄 트리 — 한 줄에 칸 하나, 들여쓰기가 깊이 */
  static String outline(GoalStore.Node node, int depth) {
    StringBuilder out = new StringBuilder();
    out.append("  ".repeat(depth)).append("- [").append(node.id).append("] ").append(node.title);
    if (node.done)
      out.append(" ✓");
    if (node.todo)
      out.append(" 📌");
    // 화면엔 흰 테두리로 보이지만 마도카한테는 글자로 (SYSTEM 의 🎯)
    if (node.leaf)
      out.append(" 🎯");
    out.append('\n');
    for (GoalStore.Node child : node.children)
      out.append(outline(child, depth + 1));
    return out.toString();
  }

  /**
   * 받은 답을 먹인다 — ops 대로 트리를 고치고 reply 를 말풍선으로.
   * JSON 이 아니게 왔으면 트리는 그대로 두고 온 글을 그냥 말로 띄운다.
   * 기다리는 사이 목표를 갈아탔어도 물어본 그 목표에 먹인다 (화면은 그게 지금 목표일 때만 다시 그린다).
   */
  private void apply(GoalStore.Goal goal, String raw, String selectId) {
    JsonNode json = parse(raw);
    if (json == null) {
      addLine(goal, false, raw == null ? "…" : raw.trim());
      save();
      showDefaultStatus();
      return;
    }

    int added = 0;
    String reveal = selectId;
    Set<GoalStore.Node> todos = new LinkedHashSet<>();
    // 끝냄 · 안 끝냄으로 바뀐 칸 — 할 일에도 똑같이 적는다
    Set<GoalStore.Node> finished = new LinkedHashSet<>();
    Set<GoalStore.Node> reopened = new LinkedHashSet<>();
    // 새 칸이 붙은 칸과 거기 붙은 칸 이름 — 마도카가 한 말을 거기 메모해 두고, 알림에도 띄운다
    Map<GoalStore.Node, List<String>> split = new LinkedHashMap<>();
    for (JsonNode op : json.path("ops")) {
      GoalStore.Node node = goal.find(op.path("id").asText(null));
      switch (op.path("op").asText("")) {
        case "add" -> {
          GoalStore.Node parent = goal.find(op.path("parent").asText(null));
          if (parent == null)
            parent = goal.root;
          for (JsonNode item : op.path("items")) {
            String title = item.asText("").trim();
            if (title.isEmpty() || hasChild(parent, title))
              continue;
            parent.children.add(goal.node(title));
            split.computeIfAbsent(parent, p -> new ArrayList<>()).add(title);
            added++;
          }
          // 새로 붙은 칸이 보이게 펴둔다
          parent.folded = false;
          if (reveal == null)
            reveal = parent.id;
        }
        case "rename" -> {
          String title = op.path("title").asText("").trim();
          if (node != null && !title.isEmpty())
            node.title = title;
        }
        case "remove" -> {
          GoalStore.Node parent = node == null ? null : goal.parentOf(node);
          if (parent != null) {
            parent.children.remove(node);
            if (node.id.equals(reveal))
              reveal = parent.id;
          }
        }
        case "done" -> {
          if (node != null) {
            boolean done = op.path("done").asBoolean(true);
            for (GoalStore.Node changed : setDone(node, done)) {
              (done ? finished : reopened).add(changed);
              (done ? reopened : finished).remove(changed);
            }
          }
        }
        case "todo" -> {
          for (JsonNode id : op.path("ids")) {
            GoalStore.Node target = goal.find(id.asText(null));
            if (target != null)
              todos.add(target);
          }
        }
        default -> {
        }
      }
    }

    String reply = json.path("reply").asText("").trim();
    if (reply.isEmpty())
      reply = added > 0 ? added + "개로 쪼개 봤어!" : "음… 다시 말해줄래?";
    addLine(goal, false, reply);
    for (GoalStore.Node parent : split.keySet())
      note(parent, reply);
    save();
    notifySplit(goal, split);

    if (goal == current) {
      syncPicker(); // 목표 이름이 다듬어졌을 수 있다
      renderTree(reveal);
    }
    showDefaultStatus();
    addTodos(goal, new ArrayList<>(todos));
    syncTodos(goal, new ArrayList<>(finished), true);
    syncTodos(goal, new ArrayList<>(reopened), false);
  }

  /** 무엇을 무엇무엇으로 쪼갰는지 한 줄씩 — 「backend」 → arch, sec, ops */
  private void notifySplit(GoalStore.Goal goal, Map<GoalStore.Node, List<String>> split) {
    if (split.isEmpty())
      return;
    StringBuilder message = new StringBuilder();
    for (Map.Entry<GoalStore.Node, List<String>> entry : split.entrySet()) {
      if (!message.isEmpty())
        message.append('\n');
      message.append("「").append(entry.getKey().title).append("」 → ").append(String.join(", ", entry.getValue()));
    }
    String first = split.keySet().iterator().next().id;
    onSplit.split(goal.id, first, "✂ " + partnerName() + " · " + goal.root.title, message.toString());
  }

  /** 칸 메모에 말 한 덩이를 덧붙인다. 다시 쪼개도, 손으로 고친 메모가 있어도 지우지 않고 밑에 쌓는다 */
  private static void note(GoalStore.Node node, String text) {
    if (node.note == null || node.note.isBlank())
      node.note = text;
    else if (!node.note.contains(text))
      node.note = node.note + "\n\n" + text;
  }

  private static boolean hasChild(GoalStore.Node parent, String title) {
    return childNamed(parent, title) != null;
  }

  /** 이름이 같은 자식 칸 (대소문자 무시). 없으면 null */
  private static GoalStore.Node childNamed(GoalStore.Node parent, String title) {
    for (GoalStore.Node child : parent.children) {
      if (child.title.equalsIgnoreCase(title))
        return child;
    }
    return null;
  }

  /** 답에서 JSON 객체만 떼어 읽는다. 시켜도 가끔 코드블록으로 싸 오거나 앞말을 붙여 와서 */
  private static JsonNode parse(String raw) {
    if (raw == null)
      return null;
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start)
      return null;
    try {
      JsonNode json = mapper.readTree(raw.substring(start, end + 1));
      return json.isObject() ? json : null;
    } catch (IOException e) {
      return null;
    }
  }

  // ── 상태 ──

  /** 답 기다리는 동안은 입력칸을 잠근다 (Esc 로 나가는 건 그대로 된다) */
  private void setLocked(boolean locked) {
    asking = locked;
    input.setEditable(!locked);
  }

  private void showDefaultStatus() {
    // 화면을 나갔다 오거나 목표를 갈아타도 답은 뒤에서 계속 기다린다 — 그동안은 생각 중이라고 둔다
    if (asking)
      showStatus(partnerThinking(), false);
    else if (!llm.isReady() && usesLlm())
      showStatus(llm.unavailableReason(), true);
    else if (current == null)
      showStatus("목표 " + book.goals.size() + "개 · ＋ 새로 만들기, Alt+↑↓ 갈아타기", false);
    else if (isGraph())
      showStatus("판에서 N 칸 추가 (고른 칸에 이어서 · 안 골랐으면 따로) · Esc 고르기 풀기 · Ctrl+끌기 잇기 · 끌기 옮기기 · F2 이름 · Space 끝냄 · Del 지우기", false);
    else
      showStatus("↑↓ 칸 고르기 · 맵에서 Space 끝냄 · T 할 일 넣기/빼기 · F 접기 · N 칸 추가 · F2 이름 · L 말단 · M 옮기기 · Del 지우기", false);
  }

  private void showStatus(String text, boolean isError) {
    status.setText(text);
    status.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), isError);
  }

  private void save() {
    try {
      store.save(book);
    } catch (IOException e) {
      e.printStackTrace();
      showStatus("목표를 못 적었어: " + e.getMessage(), true);
    }
  }

  // ── 칸 모양 ──

  /** 목표 목록의 갈래 머리 줄 (grouped) — 목표가 아니라 자리만 차지한다 */
  private static final GoalStore.Goal TREES = new GoalStore.Goal();
  private static final GoalStore.Goal GRAPHS = new GoalStore.Goal();
  /** 띄운 목록 맨 끝의 새로 만들기 줄 — 목표가 아니라 단추 하나를 담는다 */
  private static final GoalStore.Goal NEW_ROW = new GoalStore.Goal();

  /**
   * 목표 목록의 한 줄 — 맨 위 칸 이름. 갈래 머리 (목표 · 관계도) 는 못 고르는 흐린 줄로.
   * 빈 줄은 비워 둔다 — 목록 밑 빈 자리마다 "새 목표" 가 되풀이되지 않게. 콤보 단추 칸 (button) 만
   * 아무것도 안 골랐을 때 "새 목표" 를 띄운다
   */
  private static final class GoalCell extends ListCell<GoalStore.Goal> {
    private final boolean button;
    /** 새로 만들기 줄 (NEW_ROW) 의 단추가 부를 일. 콤보에선 null */
    private final Runnable onNew;
    private Button newButton;

    GoalCell(boolean button) {
      this(button, null);
    }

    GoalCell(boolean button, Runnable onNew) {
      this.button = button;
      this.onNew = onNew;
    }

    @Override
    protected void updateItem(GoalStore.Goal goal, boolean empty) {
      super.updateItem(goal, empty);
      getStyleClass().removeAll("goal-group", "goal-item", "goal-new-row");
      boolean group = !empty && isGroup(goal);
      setDisable(group);
      setGraphic(null);
      setAlignment(Pos.CENTER_LEFT);
      if (empty || goal == null) {
        setText(button ? "새 목표" : null);
        return;
      }
      if (goal == NEW_ROW) {
        if (newButton == null) {
          newButton = new Button("＋ 새로 만들기");
          newButton.getStyleClass().add("float-new");
          newButton.setFocusTraversable(false);
          newButton.setOnAction(e -> {
            if (onNew != null)
              onNew.run();
          });
        }
        getStyleClass().add("goal-new-row");
        setAlignment(Pos.CENTER_RIGHT);
        setText(null);
        setGraphic(newButton);
        return;
      }
      if (group) {
        // 글자만 둥글게 감싼다 — 칸 바탕에 칠하면 줄 폭 전체가 칠해져서 딱지 하나를 따로 얹는다
        getStyleClass().add("goal-group");
        Label pill = new Label(goal == TREES ? "목표" : "관계도");
        pill.getStyleClass().add("goal-group-pill");
        setText(null);
        setGraphic(pill);
        return;
      }
      if (!button)
        getStyleClass().add("goal-item");
      setText(goal.root.title);
    }
  }

  /**
   * 맵 딱지의 오른쪽 클릭 메뉴. 맵 키 (mapKey) 가 있는 건 오른쪽에 키를 적는다 —
   * GoalMap 이 setContextMenu 로 달지 않고 누를 때 띄우니 장면에 단축키로 걸리지는 않는다. 보여주기만 한다
   */
  private ContextMenu menu(GoalStore.Node node) {
    ContextMenu menu = new ContextMenu();
    // 맨 위 자리: 말단이면 더 쪼개지 않으니 물어보기, 아니면 쪼개기. 파트너 없이 쓰는 목표면 둘 다 없다
    if (usesLlm() && node.leaf) {
      MenuItem ask = new MenuItem(partnerName() + "한테 물어보기");
      ask.setOnAction(e -> askLeaf(node));
      menu.getItems().add(ask);
    } else if (usesLlm()) {
      MenuItem split = item(splitWithPartner(), KeyCode.ENTER, () -> split(node));
      menu.getItems().add(split);
    }
    MenuItem todo = item(node.todo ? "할 일에서 빼기" : "오늘 할 일에 넣기", KeyCode.T, () -> toggleTodo(node));
    MenuItem done = item(node.done ? "안 끝낸 걸로" : "끝냄", KeyCode.SPACE, () -> toggleDone(node));
    MenuItem note = new MenuItem("메모 보기");
    note.setOnAction(e -> openNote(node));
    MenuItem rename = item("이름 고치기", KeyCode.F2, () -> rename(node));
    menu.getItems().add(rename);
    // 말단 칸은 더 쪼개지 않으니 손으로도 못 붙인다 (addChild)
    if (!node.leaf) {
      MenuItem child = item("자식 칸 추가", KeyCode.N, () -> addChild(node));
      menu.getItems().add(child);
    }
    boolean root = current != null && node == current.root;
    if (!root)
      menu.getItems().add(item("다른 칸 밑으로 옮기기", KeyCode.M, () -> startMove(node)));
    menu.getItems().addAll(new SeparatorMenuItem(), note, todo, done);
    // 자식이 있는 칸은 말단으로 정할 수 없다 — 대신 자식들을 말단으로 (말단의 부모)
    if (canLeaf(node)) {
      MenuItem leaf = item(node.leaf ? "말단 풀기" : "말단으로 정하기", KeyCode.L, () -> toggleLeaf(node));
      menu.getItems().add(leaf);
    } else if (!leafChildren(node).isEmpty()) {
      MenuItem leafParent = item(isLeafParent(node) ? "말단의 부모 풀기" : "말단의 부모로 정하기", KeyCode.L,
          () -> toggleLeafParent(node));
      menu.getItems().add(leafParent);
    }
    if (!node.children.isEmpty()) {
      MenuItem fold = item(node.folded ? "펼치기" : "접기", KeyCode.F, () -> map.toggleFold(node));
      // 접힌 칸은 펴는 게 제일 먼저 찾는 일이라 맨 위로 올린다
      if (node.folded) {
        menu.getItems().addAll(0, List.of(fold, new SeparatorMenuItem()));
      } else {
        menu.getItems().add(fold);
      }
    }
    MenuItem remove = item(root ? "목표 지우기" : "지우기", KeyCode.DELETE, () -> remove(node));
    // 귀퉁이 메뉴 (corner.menuItem) 는 이름에 (F11) 이 박혀 있어서, 여기선 키를 오른쪽에 적는 걸로 따로 만든다
    MenuItem full = item(expanded ? "전체화면 끄기" : "전체화면", KeyCode.F11, () -> setExpanded(!expanded));
    menu.getItems().addAll(new SeparatorMenuItem(), remove, new SeparatorMenuItem(), full);
    return menu;
  }

  /** 관계도 칸의 오른쪽 클릭 메뉴 (키는 graphKey) */
  private ContextMenu graphMenu(GoalStore.Node node) {
    ContextMenu menu = new ContextMenu();
    boolean root = current != null && node == current.root;
    MenuItem note = new MenuItem("메모 보기");
    note.setOnAction(e -> openNote(node));
    menu.getItems().addAll(
        item("이은 칸 추가", KeyCode.N, () -> addGraphNode(node)),
        item("이름 고치기", KeyCode.F2, () -> rename(node)),
        new SeparatorMenuItem(),
        note,
        item(node.done ? "안 끝낸 걸로" : "끝냄", KeyCode.SPACE, () -> toggleDone(node)));
    // 루트 칸은 여기서 못 지운다 — 관계도를 통째로 지우는 건 빈 데 오른쪽 클릭 "설정…" 에서 루트 이름을 쳐서만
    if (!root)
      menu.getItems().addAll(new SeparatorMenuItem(), item("지우기", KeyCode.DELETE, () -> remove(node)));
    menu.getItems().addAll(new SeparatorMenuItem(),
        item(expanded ? "전체화면 끄기" : "전체화면", KeyCode.F11, () -> setExpanded(!expanded)));
    return menu;
  }

  /** 관계도 연결 선의 오른쪽 클릭 메뉴 */
  private ContextMenu linkMenu(GoalStore.Link link) {
    ContextMenu menu = new ContextMenu();
    GoalStore.Node from = current == null ? null : current.find(link.from);
    GoalStore.Node to = current == null ? null : current.find(link.to);
    if (current != null && current.directed && from != null && to != null) {
      // 방향 셋 중 하나 — 안 정한 연결이면 아무것도 안 골라져 있다
      ToggleGroup group = new ToggleGroup();
      RadioMenuItem forward = new RadioMenuItem("「" + from.title + "」 → 「" + to.title + "」");
      forward.setSelected(link.one());
      forward.setOnAction(e -> setDir(link, GoalStore.Link.ONE, from));
      RadioMenuItem backward = new RadioMenuItem("「" + to.title + "」 → 「" + from.title + "」");
      backward.setOnAction(e -> setDir(link, GoalStore.Link.ONE, to));
      RadioMenuItem both = new RadioMenuItem("양방향");
      both.setSelected(link.both());
      both.setOnAction(e -> setDir(link, GoalStore.Link.BOTH, null));
      for (RadioMenuItem radio : List.of(forward, backward, both))
        radio.setToggleGroup(group);
      menu.getItems().addAll(forward, backward, both, new SeparatorMenuItem());
    }
    MenuItem cut = new MenuItem("연결 끊기");
    cut.setOnAction(e -> removeLink(link));
    menu.getItems().add(cut);
    return menu;
  }

  /** 관계도 빈 데 오른쪽 클릭 메뉴 */
  private ContextMenu graphBoardMenu() {
    MenuItem add = new MenuItem("따로 선 칸 추가");
    add.setOnAction(e -> addGraphNode(null));
    // 설정 — 관계도 전체에 걸리는 것 (무향 · 유향, 관계도 지우기) 은 팝업 창으로 (GraphSettingsDialog)
    MenuItem settings = new MenuItem("설정…");
    settings.setOnAction(e -> openGraphSettings(false));
    boolean layered = current != null && current.layered;
    MenuItem mode = new MenuItem(layered ? "자유 그래프로 보기" : "루트부터 보기");
    mode.setOnAction(e -> setLayered(!layered));
    MenuItem full = item(expanded ? "전체화면 끄기" : "전체화면", KeyCode.F11, () -> setExpanded(!expanded));
    return new ContextMenu(add, mode, settings, new SeparatorMenuItem(), full);
  }

  /** 메뉴 한 줄 — 오른쪽에 그 일의 맵 키를 적는다 */
  private static MenuItem item(String text, KeyCode key, Runnable action) {
    MenuItem item = new MenuItem(text);
    item.setAccelerator(new KeyCodeCombination(key));
    item.setOnAction(e -> action.run());
    return item;
  }
}
