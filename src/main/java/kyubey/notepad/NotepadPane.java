package kyubey.notepad;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import kyubey.ui.ExpandCorner;
import kyubey.ui.FloatingPanel;
import kyubey.ui.Ime;
import kyubey.ui.Notice;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 기본 화면에서 ↓ 로 들어오는 간단한 메모장.
 *
 * 왼쪽은 지금 메모를 적는 칸, 오른쪽 마도카 그림 위 세로줄은 메모 목록이다 (reserveRight —
 * 목표 쪼개기의 말 칸과 같은 자리). 적는 대로 notepad.json 에 저장되고, 첫 줄이 목록 이름이 된다.
 *
 * 나가는 길은 들어올 때 누른 ↓ 의 반대인 ↑ — 적는 칸에선 캐럿이 글 맨 앞에 있을 때만
 * (그 밑에선 ↑ 가 평소대로 줄 옮기기), Alt+↑ 는 어디서든. Esc 로도 나간다.
 *
 * 적는 칸 오른쪽 위 삼각 귀퉁이 (ExpandCorner) · F11 · 오른쪽 클릭 "전체화면" 이면 적는 칸을 전체화면으로 펴고,
 * 목록은 칸 안 오른쪽 위에 둥근 판으로 띄운다 (FloatingPanel — 손잡이로 옮긴다). 펴진 채에선 Esc 가 먼저 접는다 (설정의 escExitsFullScreen 이 켜져 있을 때).
 * 펴진 칸은 세로 세 단으로 나눠 흘려 쓰고, 넘치면 단을 오른쪽에 덧붙여 옆으로 스크롤한다 (ColumnEditor).
 */
public class NotepadPane extends HBox {

  /** 화면 가장자리 여백. 오른쪽은 마도카 그림 테두리에 맞춰 따로 잡힌다 (reserveRight) */
  private static final double PAD = 16;

  /** 목록 밑변과 마도카 그림 머리 사이 */
  private static final double MASCOT_GAP = 8;

  /** 그림이 좁아도 목록은 이만큼은 둔다 */
  private static final double LIST_MIN = 150;

  /** 치다 멈추고 이만큼 지나면 저장한다 — 한 글자마다 파일을 쓰지 않게 */
  private static final Duration SAVE_DELAY = Duration.millis(400);

  /** 크게 폈을 때 오른쪽에 띄우는 목록 폭 */
  private static final double FLOAT_WIDTH = 200;

  /** 띄운 목록과 적는 칸 테두리 사이. 위는 귀퉁이 단추를 비켜 더 띄운다 */
  private static final double FLOAT_GAP = 10;
  private static final double FLOAT_TOP = 28;

  private final NotepadStore store;
  private final NotepadStore.Book book;
  private final Runnable onExit;

  /** 펴진 채 Esc — 전체화면을 풀지는 Main 이 설정대로 정한다 (escExitsFullScreen). 풀었으면 true */
  private BooleanSupplier onEscFull = () -> {
    if (!this.expanded)
      return false;
    setExpanded(false);
    return true;
  };

  /** Esc 로 전체화면을 풀지 — 풀었으면 true 를 돌려준다. 아니면 Esc 는 평소대로 나간다 */
  public void setOnEscFull(BooleanSupplier onEscFull) {
    this.onEscFull = onEscFull;
  }

  private final TextArea editor = new TextArea();
  private final ListView<NotepadStore.Note> list = new ListView<>();
  /** 오른쪽 목록 칸 — 마도카 그림 위쪽 자리 (reserveRight) */
  private final VBox side;
  private final HBox headerRow;
  private final Label hint;
  /** 적는 칸 · 귀퉁이 단추를 겹친 판. 크게 펴면 목록도 여기 뜬다 */
  private final StackPane editorBox;
  private final ExpandCorner corner = new ExpandCorner(() -> setExpanded(!isExpanded()));
  /** 크게 폈을 때 적는 칸 안 오른쪽에 뜨는 둥근 목록 — list 를 side 에서 옮겨 담는다 */
  private final FloatingPanel floating = new FloatingPanel(FLOAT_WIDTH, new Insets(FLOAT_TOP, FLOAT_GAP, FLOAT_GAP, 0));
  /** 크게 폈을 때 적는 칸 대신 들어가는 세 단 칸 — 글은 editor 와 묶여 있다 */
  private final ColumnEditor columns = new ColumnEditor(editor, this::setupColumn);
  private final PauseTransition saveLater = new PauseTransition(SAVE_DELAY);

  /** 지금 적는 메모. 들어오기 전엔 null */
  private NotepadStore.Note current;

  /** 칸이나 목록을 코드로 고치는 중 — 그 사이 바뀐 걸 내가 친 걸로 알아듣지 않게 */
  private boolean syncing;
  /** ↑ 를 누르고 있는 중 — 길게 눌러 줄을 올라가다 맨 앞에 닿아도 메모 밖으로 튕기지 않게 */
  private boolean upHeld;

  /** reserveRight 가 잡은 여백 — 크게 폈다 접을 때 되돌린다 */
  private Insets reserved = new Insets(PAD, 20, PAD, 20);
  private boolean expanded;
  /** 크게 펴고 접을 때 — Main 이 그동안 마도카 그림을 치운다 */
  private Consumer<Boolean> onExpand = is -> {
  };

  public NotepadPane(NotepadStore store, Runnable onExit) {
    this.store = store;
    this.onExit = onExit;
    this.book = store.load();

    setSpacing(10);
    setPadding(new Insets(PAD, 20, PAD, 20));

    Label header = new Label("📝 메모");
    header.getStyleClass().add("goal-header");

    Button add = new Button("＋");
    add.getStyleClass().add("goal-new");
    add.setFocusTraversable(false);
    add.setOnAction(e -> startNew());

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    headerRow = new HBox(8, header, spacer, add);
    headerRow.setAlignment(Pos.CENTER_LEFT);

    editor.getStyleClass().add("notepad-editor");
    editor.setWrapText(true);
    editor.setPromptText("생각나는 걸 적어둬 (첫 줄이 이름이 돼)");
    editor.addEventFilter(KeyEvent.KEY_PRESSED, this::editorKey);
    editor.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
      if (e.getCode() == KeyCode.UP)
        upHeld = false;
    });
    editor.textProperty().addListener((obs, was, is) -> {
      if (syncing || current == null)
        return;
      current.text = is;
      current.updated = System.currentTimeMillis();
      list.refresh();
      saveLater.playFromStart();
    });
    editor.setContextMenu(editorMenu(editor));
    editorBox = new StackPane(editor, corner);
    // F11 은 이 화면 어디서든
    addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.F11) {
        e.consume();
        setExpanded(!expanded);
      }
    });
    VBox.setVgrow(editorBox, Priority.ALWAYS);


    hint = new Label("↑ (글 맨 앞에서) / Esc : 돌아가기 · Ctrl+N : 새 메모");
    hint.getStyleClass().add("goal-status");

    VBox main = new VBox(8, headerRow, editorBox, hint);
    main.setMinWidth(0);
    HBox.setHgrow(main, Priority.ALWAYS);

    list.getStyleClass().add("notepad-list");
    list.setPlaceholder(new Label("메모가 없어"));
    list.setCellFactory(lv -> new NoteCell());
    list.getItems().setAll(book.notes);
    list.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
      if (!syncing && is != null && is != current)
        open(is);
    });
    list.addEventFilter(KeyEvent.KEY_PRESSED, this::listKey);
    VBox.setVgrow(list, Priority.ALWAYS);

    side = new VBox(list);
    side.setPrefWidth(LIST_MIN);
    side.setMinWidth(LIST_MIN);

    getChildren().addAll(main, side);

    saveLater.setOnFinished(e -> save());
  }

  /** 지금 화면의 입력칸 — Main 이 창을 다시 띄울 때 여기로 포커스를 돌려준다 */
  public TextArea input() {
    return editor;
  }

  /** 지금 실제로 적고 있는 칸 — 크게 펴 둔 채면 캐럿이 있는 단 */
  public TextArea typingField() {
    TextArea column = expanded ? columns.current() : null;
    return column != null ? column : editor;
  }

  /**
   * 마도카가 선 오른쪽 세로줄을 목록 자리로 쓴다 — 폭은 그림 폭, 세로는 그림 머리 위까지.
   * GoalPane.reserveRight 와 같은 자리 잡기.
   */
  public void reserveRight(double width, double height, double margin) {
    reserved = new Insets(PAD, margin, PAD, 20);
    if (!expanded)
      setPadding(reserved);
    double w = Math.max(LIST_MIN, width);
    side.setPrefWidth(w);
    side.setMinWidth(w);
    side.setMaxWidth(w);
    side.setPadding(new Insets(0, 0, Math.max(0, margin + height + MASCOT_GAP - PAD), 0));
  }

  public void setOnExpand(Consumer<Boolean> onExpand) {
    this.onExpand = onExpand;
  }

  public boolean isExpanded() {
    return expanded;
  }

  /** 적는 칸을 화면 가득 펴거나 접는다. 펴면 머릿줄 · 도움말 · 오른쪽 세로줄을 걷고 목록을 칸 안에 띄운다 */
  public void setExpanded(boolean on) {
    if (expanded == on)
      return;
    expanded = on;
    for (javafx.scene.Node node : List.of(headerRow, hint, side)) {
      node.setVisible(!on);
      node.setManaged(!on);
    }
    if (on) {
      floating.setContent(list);
      floating.resetPosition();
      editorBox.getChildren().set(0, columns);
      editorBox.getChildren().add(floating);
      corner.toFront();
      setPadding(new Insets(PAD));
    } else {
      editorBox.getChildren().remove(floating);
      editorBox.getChildren().set(0, editor);
      side.getChildren().setAll(list);
      setPadding(reserved);
    }
    if (current != null)
      list.scrollTo(current);
    corner.setExpanded(on);
    onExpand.accept(on);
    Platform.runLater(this::focusEditor);
  }

  /** 적는 칸에 포커스 — 크게 펴 둔 채면 캐럿이 있는 단으로 */
  private void focusEditor() {
    if (expanded)
      columns.focusAt(editor.getAnchor(), editor.getCaretPosition());
    else
      Ime.focus(editor);
  }

  /** 세 단 칸이 단을 새로 만들 때마다 — 적는 칸과 같은 키 · 메뉴를 단다 */
  private void setupColumn(TextArea area) {
    area.addEventFilter(KeyEvent.KEY_PRESSED, this::editorKey);
    area.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
      if (e.getCode() == KeyCode.UP)
        upHeld = false;
    });
    area.setContextMenu(editorMenu(area));
  }

  /** 적는 칸 오른쪽 클릭 메뉴 — 기본 메뉴를 갈아끼우는 거라 고치기 메뉴도 같이 둔다 */
  private ContextMenu editorMenu(TextArea editor) {
    MenuItem undo = new MenuItem("실행 취소");
    undo.setOnAction(e -> editor.undo());
    MenuItem cut = new MenuItem("잘라내기");
    cut.setOnAction(e -> editor.cut());
    MenuItem copy = new MenuItem("복사");
    copy.setOnAction(e -> editor.copy());
    MenuItem paste = new MenuItem("붙여넣기");
    paste.setOnAction(e -> editor.paste());
    MenuItem all = new MenuItem("모두 선택");
    all.setOnAction(e -> editor.selectAll());
    ContextMenu menu = new ContextMenu(undo, cut, copy, paste, all, new SeparatorMenuItem(), corner.menuItem());
    menu.setOnShowing(e -> {
      boolean picked = editor.getSelection().getLength() > 0;
      undo.setDisable(!editor.isUndoable());
      cut.setDisable(!picked);
      copy.setDisable(!picked);
    });
    return menu;
  }

  /** 기본 화면에서 ↓ 로 들어올 때. 지난번에 보던 메모를 열고, 하나도 없으면 새로 하나 */
  public void enter() {
    upHeld = false;
    NotepadStore.Note last = null;
    for (NotepadStore.Note note : book.notes) {
      if (note.id.equals(book.current))
        last = note;
    }
    if (last == null && !book.notes.isEmpty())
      last = book.notes.get(0);
    if (last != null)
      open(last);
    else
      startNew();
    // 방금 화면에 붙은 칸이라 그냥 포커스를 주면 한글 조합이 안 보인다 (Ime 주석 참고).
    // 캐럿은 맨 앞 — 들어오자마자 ↑ 한 번이면 도로 나가게
    Platform.runLater(() -> {
      editor.positionCaret(0);
      Ime.focus(editor);
    });
  }

  /** 기본 화면으로. 비워둔 메모는 남기지 않는다 */
  public void exit() {
    dropIfEmpty(null);
    save();
    onExit.run();
  }

  // ── 키 ──

  private void editorKey(KeyEvent e) {
    // 적는 칸이나 크게 폈을 때의 단 하나
    TextArea editor = (TextArea) e.getSource();
    KeyCode code = e.getCode();
    if (code == KeyCode.ESCAPE) {
      e.consume();
      if (!onEscFull.getAsBoolean())
        exit();
      return;
    }
    // 들어올 때 누른 ↓ 의 반대. 캐럿 위에 글이 남아 있으면 ↑ 는 줄 옮기기라 Alt 를 같이.
    // 길게 눌러 생긴 반복 입력으로는 나가지 않는다 — 새로 누른 ↑ 만
    if (code == KeyCode.UP) {
      boolean repeat = upHeld;
      upHeld = true;
      if (!repeat && (e.isAltDown() || editor.getCaretPosition() == 0)) {
        e.consume();
        exit();
        return;
      }
    }
    if (code == KeyCode.N && e.isShortcutDown()) {
      e.consume();
      startNew();
    }
  }

  private void listKey(KeyEvent e) {
    KeyCode code = e.getCode();
    if (code == KeyCode.ESCAPE && onEscFull.getAsBoolean()) {
      e.consume();
    } else if (code == KeyCode.ESCAPE || (code == KeyCode.UP && e.isAltDown())) {
      e.consume();
      exit();
    } else if (code == KeyCode.ENTER) {
      e.consume();
      focusEditor();
    } else if (code == KeyCode.DELETE) {
      e.consume();
      NotepadStore.Note picked = list.getSelectionModel().getSelectedItem();
      if (picked != null)
        delete(picked);
    }
  }

  // ── 메모 ──

  private void open(NotepadStore.Note note) {
    dropIfEmpty(note);
    current = note;
    book.current = note.id;
    syncing = true;
    editor.setText(note.text);
    list.getSelectionModel().select(note);
    list.scrollTo(note);
    syncing = false;
    editor.positionCaret(editor.getLength());
    save();
  }

  /** 새 메모. 지금 메모가 비어 있으면 그걸 그대로 쓴다 */
  private void startNew() {
    if (current != null && current.text.isBlank()) {
      focusEditor();
      return;
    }
    NotepadStore.Note note = NotepadStore.newNote();
    book.notes.add(0, note);
    list.getItems().add(0, note);
    open(note);
    focusEditor();
  }

  private void delete(NotepadStore.Note note) {
    if (!note.text.isBlank()
        && Notice.ask(getScene().getWindow(), "\"" + note.title() + "\" 메모를 지울까?", "지우기", "취소") != 0)
      return;
    int at = book.notes.indexOf(note);
    book.notes.remove(note);
    list.getItems().remove(note);
    if (note == current) {
      current = null;
      if (book.notes.isEmpty()) {
        startNew();
      } else {
        open(book.notes.get(Math.min(at, book.notes.size() - 1)));
      }
    }
    save();
  }

  /** 지금 메모가 비어 있으면 목록에서 치운다 — 다른 메모(next)로 넘어가거나 나갈 때 */
  private void dropIfEmpty(NotepadStore.Note next) {
    if (current == null || current == next || !current.text.isBlank())
      return;
    book.notes.remove(current);
    syncing = true;
    list.getItems().remove(current);
    syncing = false;
    current = null;
  }

  private void save() {
    saveLater.stop();
    try {
      store.save(book);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 목록 한 줄 = 메모 첫 줄. 오른쪽 클릭으로 지운다 */
  private final class NoteCell extends ListCell<NotepadStore.Note> {
    NoteCell() {
      MenuItem remove = new MenuItem("지우기");
      remove.setOnAction(e -> {
        if (getItem() != null)
          delete(getItem());
      });
      ContextMenu menu = new ContextMenu(remove, new SeparatorMenuItem(), corner.menuItem());
      setOnMouseClicked(e -> {
        if (e.getButton() == MouseButton.PRIMARY && !isEmpty())
          Platform.runLater(NotepadPane.this::focusEditor);
      });
      emptyProperty().addListener((obs, was, is) -> setContextMenu(is ? null : menu));
    }

    @Override
    protected void updateItem(NotepadStore.Note item, boolean empty) {
      super.updateItem(item, empty);
      setText(empty || item == null ? null : item.title());
    }
  }
}
