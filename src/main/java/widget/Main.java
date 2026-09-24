package widget;

import widget.capture.Capture;
import widget.core.Builtin;
import widget.core.ChatHistory;
import widget.core.ChatPrompt;
import widget.core.HistoryManager;
import widget.core.MacroManager;
import widget.core.SearchResult;
import widget.core.Settings;
import widget.core.WorkingMemory;
import widget.index.FolderMapper;
import widget.llm.GeminiClient;
import widget.llm.LlmClient;
import widget.ui.AppIcon;
import widget.ui.AppRegisterDialog;
import widget.ui.ChatPane;
import widget.ui.Fonts;
import widget.ui.Foreground;
import widget.ui.GlobalHotkey;
import widget.ui.Ime;
import widget.ui.KeysDialog;
import widget.ui.LlmSearchPane;
import widget.ui.PromptDialog;
import widget.ui.SetupDialog;
import widget.ui.ShareDialog;
import widget.ui.WindowDrag;
import widget.voice.Dictation;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main extends Application {

  /**
   * run.bat 이 사는 곳. 작업 폴더에서 못 찾았을 때만 쓰는 뒷길.
   * mvn 으로 띄우면 클래스가 target/classes 에 있으니 거기서 두 칸 올라간 곳이다.
   */
  private static final File PROJECT_DIR = projectDir();

  /** 폴더 이름 → 경로들. 처음 설정에서 고른 폴더를 훑어서 채운다 (FolderMapper) */
  private static final String DIRS_JSON_PATH = FolderMapper.OUTPUT_JSON_PATH;

  /** 빈 입력창에서 ↑/↓ 로 꺼내 볼 지난 명령어를 한 번에 몇 줄까지 보여줄지 */
  private static final int HISTORY_LIMIT = 8;

  /** 마도카를 창 오른쪽·아래 모서리에서 이만큼 띄워 세운다 */
  private static final double MASCOT_MARGIN = 12;

  /** 안 읽은 말 배지를 마도카 그림에서 왼쪽으로 이만큼 떼어 놓는다 */
  private static final double BADGE_GAP = 8;

  /** 아무것도 안 하고 있을 때 입력창에 떠 있는 안내. 받아쓰기가 한 줄 띄웠다 여기로 돌아온다 */
  private static final String PROMPT = "여기에 입력하세요";

  /**
   * 말로 시키는 "~ 열어" 를 알아듣는 말투. 스페이스를 누르고 "유튜브 열어줘" 라고 하면
   * 앞머리("유튜브")만 떼어다 목록에서 찾아 바로 연다.
   *
   * 끝에 붙는 것들을 넉넉히 받아준다 — 받아쓰기는 "열어 줘." 처럼 띄어 쓰거나 마침표를
   * 붙여 오는 일이 흔해서, 그걸 못 알아듣고 입력창에만 꽂히면 말로 시킨 보람이 없다.
   */
  private static final Pattern OPEN_ORDER = Pattern.compile(
      "^(.*?)\\s*(?:좀\\s*)?열어\\s*(?:줘|줄래|주라|라|봐)?\\s*[.!?~]*$");

  /**
   * 이름 뒤에 습관처럼 붙여 부르는 종류 — "다운로드 폴더", "카톡 앱" 의 뒷말.
   * 등록해둔 이름으로 못 찾았을 때만 한 번 떼어내고 다시 찾아본다 (nameGuesses 참고).
   */
  private static final Pattern TRAILING_KIND = Pattern.compile(
      "\\s*(?:폴더|디렉터리|디렉토리|앱|어플|어플리케이션|프로그램|사이트|창|페이지)$");

  /**
   * 배지를 그림 발밑에서 그림 높이의 이만큼 위에 세운다.
   * 얼굴 옆쯤에 떠야 마도카가 말을 건 것처럼 보여서, 가운데보다 조금 위로 잡았다.
   */
  private static final double BADGE_RISE = 0.66;

  private MacroManager macro;
  /** 한 줄 검색·수다·화면 캡쳐의 글자 읽기가 같이 쓰는 Gemini */
  private LlmClient llm;
  private HistoryManager history;
  private ListView<SearchResult> resultList;
  private LlmSearchPane llmPane;
  private ChatPane chatPane;
  private TextField inputField;

  /** 화면을 통째로 갈아끼우는 자리 — 한 줄 검색 / LLM 모드 / 마도카랑 수다 가 여기서 바뀐다 */
  private BorderPane root;
  /** 기본 화면(한 줄 입력창 + 결과 목록). 모드에서 빠져나오면 이걸 도로 올린다 */
  private VBox topBox;
  /** 지금 올라와 있는 화면의 입력칸. 창을 다시 띄울 때 여기로 포커스를 돌려준다 */
  private Node focusTarget;

  /**
   * 마도카 왼쪽에 뜨는 '안 읽은 말 있음' 표시. 그림을 못 읽었으면 세울 자리가 없어서 null 이다
   * (배지는 그림 위치를 기준으로 서기 때문 — placeBeside 참고).
   */
  private Label unreadBadge;

  /** 위젯 창 제목. 작업 표시줄에 뜨고, Foreground 가 창을 찾을 때도 쓴다 */
  private static final String TITLE = "Kyubey";

  /** Win+Alt+Space 가 실제로 걸렸는지. 못 걸었으면 Esc 를 숨기기로 쓰면 안 된다 */
  private boolean hotkeyReady;

  @Override
  public void start(Stage stage) {
    // CSS 가 'Pretendard' 를 찾기 전에 폰트부터 올려둔다
    Fonts.loadPretendard();

    macro = new MacroManager(DIRS_JSON_PATH);
    history = new HistoryManager("src/main/resources/json/history.json");
    // 처음 켠 날에도 ↓ 가 빈손이 아니게, 예전부터 세어 둔 top.json 을 한 번 옮겨 깐다
    history.seedFrom(macro.getFrequencyData());

    // 입력창
    inputField = new TextField();
    inputField.setPromptText(PROMPT);
    inputField.setPrefWidth(350);
    inputField.setPrefHeight(45);
    inputField.setStyle("-fx-font-size: 16px;");

    // 결과 리스트
    resultList = new ListView<>();
    resultList.setPrefHeight(200);
    resultList.setMaxWidth(350);
    resultList.setVisible(false);
    resultList.setManaged(false);

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
        resultList.getSelectionModel().select(0);
        resultList.setVisible(true);
        resultList.setManaged(true);
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

      // 빈 입력창에서 ↑/↓ : 예전에 쓴 명령어 꺼내 보기 (↑ 최근 순, ↓ 많이 쓴 순)
      // 글자가 있을 땐 평소대로 목록 오르내리기라, 목록이 닫혀 있고 입력창도 빈 지금만 가로챈다
      if (!resultList.isVisible() && inputField.getText().isBlank()) {
        if (e.getCode() == KeyCode.UP) {
          showHistory(history.recent(HISTORY_LIMIT), "🕘", false);
          e.consume();
          return;
        }
        if (e.getCode() == KeyCode.DOWN) {
          showHistory(history.frequent(HISTORY_LIMIT), "🔥", true);
          e.consume();
          return;
        }
      }

      if (!resultList.isVisible())
        return;

      int currentIndex = resultList.getSelectionModel().getSelectedIndex();
      int size = resultList.getItems().size();

      if (e.getCode() == KeyCode.DOWN) {
        resultList.getSelectionModel().select(Math.min(currentIndex + 1, size - 1));
        e.consume();
      } else if (e.getCode() == KeyCode.UP) {
        resultList.getSelectionModel().select(Math.max(currentIndex - 1, 0));
        e.consume();
      } else if (e.getCode() == KeyCode.ENTER) {
        activate(stage, resultList.getSelectionModel().getSelectedItem());
        e.consume();
      }
    });

    // 레이아웃 배치
    topBox = new VBox(10, inputField, resultList);
    topBox.setAlignment(Pos.CENTER);
    topBox.setPadding(new Insets(100, 0, 0, 0));

    root = new BorderPane();
    root.setTop(topBox);
    focusTarget = inputField;

    // ── 화살표 키 ──
    // Alt+→ : 구글 바로 검색
    // Alt+← : 여러 줄 LLM 모드로 전환
    // 맨손 ← : 입력창이 완전히 비어 있을 때만 LLM 모드로 (옮길 캐럿이 없으니 뺏어도 안전)
    // 맨손 → : 입력창이 완전히 비어 있을 때만 마도카 수다 화면으로 (← 와 같은 이치)
    // 돌아오는 길은 각 화면이 들고 있다 — LLM 모드에서 →, 수다 화면에서 ← 를 누르면 여기로 온다.
    // Alt 를 문지기로 뒀으니 캐럿이 어디 있든 상관없고, 글자가 있을 땐 맨손 ←/→ 가 평소대로 커서 이동.
    // isAltDown() 은 왼쪽/오른쪽 Alt 를 안 가린다. 다만 한글 키보드에서 오른쪽 Alt 는
    // 한/영 키로 먹히는 경우가 많아서, 그럴 땐 왼쪽 Alt 를 써야 한다.
    // 스킨의 기본 동작보다 먼저 가로채야 해서 핸들러 말고 필터로 단다.
    // 두 화면이 키(.env 의 WIZ_*)를 같이 쓰게 클라이언트는 하나만 만들어 나눠준다
    llm = new GeminiClient();
    llmPane = new LlmSearchPane(llm, this::showHome);
    // 나눈 수다는 파일에 남겨서, 껐다 켜도 하던 얘기가 그대로 있게 한다.
    // memo.json 은 그중 오래 알아둘 것만 추려둔 곳 — 옛날 대화를 원문으로 다시 안 보내려고 둔다.
    chatPane = new ChatPane(llm,
        new ChatHistory("src/main/resources/json/chat.json"),
        new WorkingMemory("src/main/resources/json/memo.json"),
        new ChatPrompt("src/main/resources/json/prompt.json"),
        this::showHome, this::showUnread);

    inputField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      String text = inputField.getText();

      if (e.getCode() == KeyCode.TAB) {
        e.consume(); // 안 막으면 포커스가 딴 데로 튄다
        complete();
        return;
      }

      if (e.getCode() == KeyCode.LEFT && (e.isAltDown() || text.isEmpty())) {
        e.consume();
        llmPane.enterWith(text);
        root.setTop(llmPane);
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

    // 빈 입력창에서 스페이스를 누르고 있으면 받아쓰기 (Dictation 주석 참고).
    // 여기선 받아 적은 말이 곧 명령일 수 있어서 한 번 걸러 듣는다 — "유튜브 열어줘" 는
    // 입력창에 올리는 게 아니라 바로 연다 (heard 참고).
    Dictation.arm(inputField, new Dictation.Ear() {
      @Override
      public void status(String message) {
        inputField.setPromptText(message == null ? PROMPT : message);
      }

      @Override
      public void heard(String text) {
        Main.this.heard(stage, text);
      }
    });

    root.setStyle(
        "-fx-background-color: rgba(255, 181, 217, 0.5);" +
            "-fx-background-radius: 20;" +
            "-fx-border-radius: 20;" +
            "-fx-border-color: rgba(255, 255, 255, 0.2);" +
            "-fx-border-width: 1;");

    // 우측 하단 마스코트. 창을 끌 때 걸리적거리지 않게 마우스는 그냥 통과시킨다.
    StackPane layers = new StackPane(root);
    layers.setStyle("-fx-background-color: transparent;");
    ImageView mascot = mascot();
    if (mascot != null) {
      StackPane.setAlignment(mascot, Pos.BOTTOM_RIGHT);
      StackPane.setMargin(mascot, new Insets(0, MASCOT_MARGIN, MASCOT_MARGIN, 0));
      layers.getChildren().add(mascot);

      // 안 읽은 말 배지는 그림보다 나중에 얹는다 — 겹치는 자리에서 배지가 위로 오게
      unreadBadge = unreadBadge();
      StackPane.setAlignment(unreadBadge, Pos.BOTTOM_RIGHT);
      layers.getChildren().add(unreadBadge);

      // 수다 화면이 마도카 밑에 깔리지 않게 그림이 선 세로줄을 통째로 비우고,
      // 배지는 그 세로줄 왼쪽에 세운다. 그림 너비는 이미지가 다 올라온 뒤에야 잡히니
      // 한 번 재고 끝내면 0 을 집을 수도 있어서, 폭이 잡히거나 바뀔 때마다 다시 잰다.
      placeBeside(mascot);
      mascot.layoutBoundsProperty().addListener((obs, was, is) -> placeBeside(mascot));
    }

    // Scene & Stage 설정
    Scene scene = new Scene(layers, 600, 400);
    scene.getStylesheets().add(getClass().getResource("/style/style.css").toExternalForm());
    scene.setFill(Color.TRANSPARENT);

    // Esc 안전망. 각 화면이 자기 안에서 먼저 받고(필터), 포커스가 화면 밖 —
    // 씬 루트나 마스코트 쪽 — 에 가 있어서 아무도 안 받았을 때만 여기까지 올라온다.
    // 필터가 아니라 핸들러라 거품 단계 맨 끝에 돈다 (필터로 달면 안쪽 처리를 다 가로챈다).
    scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() != KeyCode.ESCAPE)
        return;
      e.consume();
      if (root.getCenter() == chatPane || root.getTop() == llmPane)
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
    // 타이틀바가 없으니 배경 아무 데나 잡고 끌어서 옮긴다
    WindowDrag.makeDraggable(stage, root);
    stage.setX(800);
    stage.setY(120);
    stage.show();
    // 처음 켤 때도 한글 조합이 보이게, 포커스는 Ime 를 거쳐서 준다 (Ime 주석 참고)
    Platform.runLater(() -> Ime.focus(inputField));
    // 처음 켰으면 (settings.json 이 없으면) 훑을 폴더부터 고르게 한다
    if (!Settings.exists())
      Platform.runLater(() -> setup(stage));

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
   * 마도카를 재서 옆자리를 잡는다. 수다 화면과 LLM 모드에는 그림이 선 세로줄(그림 너비 +
   * 모서리에서 띄운 거리)을 비우라고 알려주고, 안 읽은 말 배지는 그 세로줄 바로 왼쪽 — 그림
   * 발밑에서 BADGE_RISE 만큼 올라간 높이 — 에 세운다.
   */
  private void placeBeside(ImageView mascot) {
    double width = mascot.getLayoutBounds().getWidth();
    double height = mascot.getLayoutBounds().getHeight();

    chatPane.reserveRight(width + MASCOT_MARGIN);
    llmPane.reserveRight(width + MASCOT_MARGIN);
    StackPane.setMargin(unreadBadge, new Insets(
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
    unreadBadge.setText(count > 1 ? "💬 " + count : "💬");
    unreadBadge.setVisible(count > 0);
  }

  /**
   * 우측 하단에 얹을 마스코트 그림. 누르면 같은 창 안에서 수다 화면이 열린다.
   * 그림 하나 없다고 위젯까지 못 뜨면 곤란하니, 못 읽으면 조용히 없는 셈 친다.
   */
  private ImageView mascot() {
    var stream = getClass().getResourceAsStream("/img/마도카.png");
    if (stream == null) {
      System.err.println("[mascot] /img/마도카.png 를 못 찾았어");
      return null;
    }
    ImageView view = new ImageView(new Image(stream));
    view.setPreserveRatio(true);
    view.setFitHeight(170);
    // 예전엔 마우스를 통째로 통과시켰는데(창 끌 때 걸리적거려서), 이제 말을 걸 수 있으니 받는다.
    // 대신 그림 위를 잡으면 창이 안 끌린다 — 창은 넓으니 다른 빈 곳을 잡으면 된다.
    view.setCursor(Cursor.HAND);
    view.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        showChat();
    });
    return view;
  }

  /**
   * 기본 화면(한 줄 검색)으로 돌아온다. LLM 모드도 수다 화면도 Esc 로 여기로 나오고,
   * 들어올 때 누른 화살표의 반대쪽 — LLM 모드에서 →, 수다 화면에서 ← — 으로도 나온다.
   */
  private void showHome() {
    root.setCenter(null);
    root.setTop(topBox);
    inputField.clear();
    hideResults();
    focusTarget = inputField;
    Platform.runLater(() -> Ime.focus(inputField));
  }

  /**
   * 마도카를 눌렀을 때 여는 수다 화면. LLM 모드와 똑같이 창을 새로 띄우지 않고
   * 같은 창의 내용만 갈아끼운다. 다만 입력칸이 아래에 있어야 해서 top 이 아니라 center 로 건다.
   * 나눠 온 대화는 안 지운다 — 나갔다 들어와도 하던 얘기가 그대로 있게.
   */
  private void showChat() {
    root.setTop(null);
    root.setCenter(chatPane);
    focusTarget = chatPane.input();
    chatPane.enter();
  }

  /**
   * 빈 입력창에서 꺼내 보는 지난 명령어 목록.
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
    resultList.getSelectionModel().select(0);
    resultList.setVisible(true);
    resultList.setManaged(true);
  }

  /**
   * 받아 적은 말이 도착했을 때 (한 줄 검색 화면).
   *
   * "유튜브 열어" / "유튜브 열어줘" 처럼 열라고 시킨 말이면 곧장 연다. 말로 시킬 땐 목록을
   * 보고 고를 손이 이미 놀고 있는 게 아니라서, 한 번 더 Enter 를 치게 하면 말한 보람이 없다.
   * 그 말투가 아니거나 열 만한 게 안 나오면 평소처럼 입력창에 올려만 둔다 — 그럼 목록이
   * 뜨니까 눈으로 고르면 된다.
   */
  private void heard(Stage stage, String text) {
    Matcher order = OPEN_ORDER.matcher(text);
    if (order.matches()) {
      String target = order.group(1).trim();
      if (!target.isEmpty() && open(stage, target))
        return;
    }
    Dictation.fill(inputField, text);
  }

  /**
   * 말로 시킨 "~ 열어" 를 실제로 연다. 열 만한 게 하나도 없으면 아무 일도 안 하고 false —
   * 부르는 쪽(heard)이 들은 말을 그대로 입력창에 올린다.
   *
   * 고르는 길은 손으로 칠 때와 똑같다. 찾은 목록 맨 위를 집되 "여는 일" 이 아닌 줄은 건너뛴다:
   * 구글 검색과 등록 창(new·edit)은 못 찾았을 때 늘 따라붙는 줄이라 그걸 집으면 "못 찾았다"
   * 가 "뭔가 열었다" 로 둔갑하고, exit·restart 같은 위젯 명령어는 잘못 알아들은 한 마디로
   * 위젯이 꺼져버릴 수 있어서 말로는 아예 안 닿게 해뒀다.
   */
  private boolean open(Stage stage, String heard) {
    for (String keyword : nameGuesses(heard)) {
      SearchResult target = openable(keyword);
      if (target == null)
        continue;

      // 자주 쓴 것 세기와 지난 명령어 기록이 입력창 글자를 근거로 삼는다 (activate 참고).
      // 말로 시킨 것도 똑같이 쌓이게, 떼어낸 키워드를 올려놓고 평소 길로 보낸다.
      inputField.setText(keyword);
      activate(stage, target);
      inputField.setPromptText("🎤 " + keyword + " 열었어!");
      return true;
    }
    return false;
  }

  /**
   * 들은 이름을 등록해둔 이름에 맞춰볼 순서. 들은 그대로가 언제나 먼저다.
   *
   * 받아쓰기는 아는 이름을 제 식대로 적어 온다. "유튜브" 라고 말해도 "YouTube" 로 오는데
   * 등록해둔 별칭은 소문자("youtube")라 그대로는 안 걸리고, 말할 땐 "다운로드 폴더 열어"
   * 처럼 뒤에 종류를 붙여 부르는 게 자연스러운데 등록된 이름은 "다운로드" 뿐이다.
   * 둘 다 사람이 잘못 말한 게 아니라 적히는 모양이 다른 것뿐이라 여기서 맞춰본다.
   */
  private static List<String> nameGuesses(String heard) {
    List<String> guesses = new ArrayList<>();
    String bare = TRAILING_KIND.matcher(heard).replaceAll("").trim();

    for (String guess : List.of(heard, heard.toLowerCase(), bare, bare.toLowerCase())) {
      if (!guess.isEmpty() && !guesses.contains(guess))
        guesses.add(guess);
    }
    return guesses;
  }

  /** 이 이름으로 찾아서 정말 열어도 되는 첫 줄. 없으면 null (open 주석 참고) */
  private SearchResult openable(String keyword) {
    return macro.resolve(keyword).stream()
        .filter(result -> switch (result.getType()) {
          case APP, FOLDER, SITE_HOME, SITE_SEARCH, EDITOR_FOLDER, CMD -> true;
          case GOOGLE, BUILTIN, HISTORY -> false;
        })
        .findFirst()
        .orElse(null);
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
    resultList.setManaged(false);
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
      // 못 찾아서 내려온 "새로 등록하기" 면 찾던 그 글자를 키워드 칸에 담아 보낸다
      case NEW -> AppRegisterDialog.show(stage, macro, arg);
      // 단축키(Win+Alt+Z)와 같은 판. 위젯은 뜬 채로 남아서 화면에 같이 찍힌다 —
      // 빼고 찍고 싶으면 Esc 로 내려두고 단축키로 부르면 된다.
      case CAPTURE -> Capture.start(llm);
      // 같은 와이파이에 있는 폰이랑 주고받기 — 창에 뜨는 QR 을 폰으로 찍으면 된다
      case SEND_FILE -> ShareDialog.send(stage);
      case RECEIVE_FILE -> ShareDialog.receive(stage);
      case SETUP -> setup(stage);
      case SCAN -> {
        int count = macro.rescanInstalledApps();
        inputField.setPromptText("설치된 앱 " + count + "개를 새로 훑었어!");
      }
      // 수다 화면에 들어가 있지 않아도 여기서 지운다 — 남은 대화도 접어둔 기억도 같이 (clearChat 참고)
      case CLEAR_CHAT -> {
        chatPane.clearChat();
        inputField.setPromptText("마도카랑 나눈 얘기 지웠어!");
      }
      // 저장하면 다음 답변부터 바로 먹는다. 취소하면 아무 일도 없다.
      case CHAT_PROMPT -> {
        if (PromptDialog.show(stage, chatPane))
          inputField.setPromptText("마도카 성격 바꿔뒀어!");
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
   * 설정 창을 띄우고, 저장했으면 고른 폴더들을 새로 훑는다.
   * 드라이브 통째로 고르면 한참 걸려서 뒤에서 돌리고, 끝나면 입력창에 알려준다.
   */
  private void setup(Stage stage) {
    Settings settings = SetupDialog.show(stage);
    if (settings == null)
      return;

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
   * Esc: 끄는 게 아니라 숨긴다. 다시 부르는 건 Win+Alt+Space.
   * 단축키를 못 걸었을 땐 숨기면 영영 못 부르니까 그냥 종료한다.
   */
  private void escape(Stage stage) {
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
    stage.hide();
  }

  private void reveal(Stage stage) {
    stage.show();
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
      Ime.focus(focusTarget == null ? inputField : focusTarget);
    });
  }

  public static void main(String[] args) {
    launch(args);
  }
}
