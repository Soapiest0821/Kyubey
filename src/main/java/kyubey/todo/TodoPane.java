package kyubey.todo;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * 한 줄 입력창 밑 왼쪽에 붙는 오늘 할 일. 이 컴퓨터에만 적어둔다 (TodoStore).
 *
 * 생김새는 투두메이트 앱을 따른다 — 카테고리마다 이름표(색 글씨 + ＋) 를 달고 그 밑에 할 일을 늘어놓는다.
 * 체크칸은 앱의 네잎 모양: 안 한 건 회색, 한 건 카테고리 색에 흰 체크.
 * 카테고리는 맨 밑 "＋ 카테고리" 로 만들고, 이름표를 오른쪽 클릭해서 이름을 바꾸거나 지운다.
 * 할 일도 오른쪽 클릭해서 이름을 바꾸거나 다른 카테고리로 옮기거나 내일로 미룬다 — 미루면 마도카가 비밀 쪽지로 혼낸다.
 *
 * 새로 읽는 건 창에 포커스가 들어올 때뿐이다 (Main 이 refresh 를 부른다) — 아침 여섯 시를 넘기면
 * 그때 오늘로 바뀌고, 전날 못 끝낸 일이 오늘로 넘어와 진한 핑크로 뜬다 (TodoStore.carryOver).
 */
public final class TodoPane extends ScrollPane {

  /** 카테고리에 차례로 돌려 쓰는 색 — 앱 기본 색 비슷하게 */
  public static final List<String> DEFAULT_PALETTE = List.of("#7cc33f", "#f5b915", "#f27a8f", "#5aa9e6", "#a07be0",
      "#ff8a4c");
  private static final Color UNDONE = Color.web("#d9dde2");
  /** 전날 못 끝내서 넘어온 할 일의 글씨 색 — 진한 핑크 */
  private static final String OVERDUE = "todo-text-overdue";
  /** 밀려서 나중 날에 끝낸 할 일을 그 전 날 기록에서 볼 때 — 취소선 */
  private static final String STRUCK = "todo-text-struck";
  private static final String PROMPT = "할 일 입력 (Enter)";

  private final TodoStore store = new TodoStore();

  /** 카테고리에 돌려 쓰는 색. "설정" 창에서 바꾼다 (setColors) */
  private List<String> palette = DEFAULT_PALETTE;

  /** 칭찬할 때 — 절반 끝냄(할 일 두 개 이상), 다 끝냄, 다 끝낸 뒤 더 적은 걸 끝냄 */
  public enum Milestone {
    HALF, ALL, EXTRA
  }

  /** 체크해서 칭찬할 때가 되면 부른다 (milestone) — 마도카가 칭찬하게 (Main 이 단다) */
  private BiConsumer<TodoStore.Todo, Milestone> onDone = (todo, milestone) -> {
  };

  /** 할 일을 내일로 미루면 부른다 — 마도카가 비밀 쪽지로 혼내게 (Main 이 단다) */
  private Consumer<TodoStore.Todo> onPostpone = todo -> {
  };

  /** 할 일을 적거나 지우거나 체크해서 저장할 때마다 부른다 — 할 일 창(TodoDialog)이 주간 줄 숫자를 고치게 */
  private Runnable onChange = () -> {
  };

  /** 할 일 칸이 닫혀 포커스를 놓을 때 부른다 (Esc / Enter / 딴 데 누름) — 메인 화면에선 입력창으로 돌려주게 (Main 이 단다) */
  private Runnable onRelease = () -> {
  };

  /** 목표 맵에서 내려온 할 일을 "목표로 이동하기" 하면 부른다 — 목표 id, 칸 id. 목표 쪼개기 화면의 그 칸으로 가게 (Main 이 단다) */
  private BiConsumer<String, String> onGoal = (goalId, nodeId) -> {
  };

  /** 목표 맵에서 내려온 할 일을 체크하거나 풀면 부른다 — 목표 맵의 그 칸도 끝냄 · 안 끝냄으로 (Main 이 단다) */
  private GoalDone onGoalDone = (goalId, nodeId, done) -> {
  };

  public interface GoalDone {
    void accept(String goalId, String nodeId, boolean done);
  }

  /** 목표 맵에서 내려온 할 일을 손으로 지우면 부른다 — 목표 id, 칸 id. 목표 맵의 그 칸도 할 일에서 빠진 걸로 (Main 이 단다) */
  private BiConsumer<String, String> onGoalRemove = (goalId, nodeId) -> {
  };

  /** 빈 데를 두 번 누르면 부른다 — 할 일 창(TodoDialog)을 열게 (Main 이 단다) */
  private Runnable onOpen = () -> {
  };

  private final VBox body = new VBox(14);
  private final Label status = new Label();

  private List<TodoStore.Category> categories = new ArrayList<>();
  /** 지금 보이는 날. 아침 여섯 시를 넘기면 refresh 때 오늘로 바뀐다 */
  private TodoStore.Day day;

  public TodoPane() {
    getStyleClass().add("chat-scroll");
    status.getStyleClass().add("todo-hint");
    status.setWrapText(true);
    setContent(body);
    setFitToWidth(true);
    setHbarPolicy(ScrollBarPolicy.NEVER);
    setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
    // 굴리거나 눌러도 포커스는 가져가지 않는다 — 메인 화면에선 입력창에 그대로 남아 있어야 해서
    setFocusTraversable(false);
    // 체크칸 · 버튼 · 입력칸 말고 아무 데나 두 번 누르면 할 일 창
    addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
          && !onControl(e.getPickResult().getIntersectedNode()))
        onOpen.run();
    });
  }

  /** 체크칸 · 버튼 · 입력칸 위면 true — 거기선 두 번 눌러도 할 일 창을 안 연다 */
  private boolean onControl(Node node) {
    for (Node n = node; n != null && n != this; n = n.getParent()) {
      if (n instanceof Clover || n instanceof Button || n instanceof TextField)
        return true;
    }
    return false;
  }

  /** 카테고리 색을 바꾼다. 빈 목록이나 null 이면 기본 색으로. 다음 refresh 부터 먹는다 */
  public void setColors(List<String> palette) {
    this.palette = palette == null || palette.isEmpty() ? DEFAULT_PALETTE : List.copyOf(palette);
  }

  public void setOnDone(BiConsumer<TodoStore.Todo, Milestone> onDone) {
    this.onDone = onDone;
  }

  public BiConsumer<TodoStore.Todo, Milestone> onDone() {
    return onDone;
  }

  public void setOnPostpone(Consumer<TodoStore.Todo> onPostpone) {
    this.onPostpone = onPostpone;
  }

  public Consumer<TodoStore.Todo> onPostpone() {
    return onPostpone;
  }

  public void setOnChange(Runnable onChange) {
    this.onChange = onChange;
  }

  public void setOnGoal(BiConsumer<String, String> onGoal) {
    this.onGoal = onGoal;
  }

  public BiConsumer<String, String> onGoal() {
    return onGoal;
  }

  public void setOnGoalDone(GoalDone onGoalDone) {
    this.onGoalDone = onGoalDone;
  }

  public GoalDone onGoalDone() {
    return onGoalDone;
  }

  public void setOnGoalRemove(BiConsumer<String, String> onGoalRemove) {
    this.onGoalRemove = onGoalRemove;
  }

  public BiConsumer<String, String> onGoalRemove() {
    return onGoalRemove;
  }

  public void setOnRelease(Runnable onRelease) {
    this.onRelease = onRelease;
  }

  public void setOnOpen(Runnable onOpen) {
    this.onOpen = onOpen;
  }

  /** 지금 쓰는 카테고리 색 */
  public List<String> colors() {
    return palette;
  }

  /** 할 일 기록이 쌓이는 폴더 */
  public File recordDir() {
    return store.dir();
  }

  /** 파일에서 카테고리와 오늘 할 일을 새로 읽어 그린다 */
  public void refresh() {
    show(TodoStore.today());
  }

  /** 그 날 할 일을 읽어 그린다. 전날 못 끝낸 일을 넘겨받는 건 오늘일 때만 */
  public void show(LocalDate date) {
    try {
      categories = store.categories();
      day = store.day(date);
      if (date.equals(TodoStore.today()))
        store.carryOver(day);
      render(null);
    } catch (IOException e) {
      categories = new ArrayList<>();
      day = null;
      render("할 일 파일을 못 읽었어: " + e.getMessage());
    }
  }

  /** 지금 보이는 날. 아직 못 읽었으면 null */
  public LocalDate day() {
    return day == null ? null : LocalDate.parse(day.date);
  }

  /** 지금 보이는 날의 할 일 전부 (없던 게 된 건 빼고). 아직 못 읽었으면 빈 목록 — 수다에서 할 일 얘기가 나오면 마도카한테 보여준다 */
  public List<TodoStore.Todo> todos() {
    return day == null ? List.of() : List.copyOf(day.shown());
  }

  /** 전날에서 넘어와 아직 못 끝낸 할 일 — 마도카가 재촉할 거리 */
  public List<TodoStore.Todo> overdue() {
    List<TodoStore.Todo> out = new ArrayList<>();
    if (day != null) {
      for (TodoStore.Todo todo : day.todos) {
        if (todo.overdue() && !todo.done && todo.doneOn == null)
          out.add(todo);
      }
    }
    return out;
  }

  private void render(String warning) {
    // 카테고리 순서대로 묶는다 (색은 숨긴 카테고리까지 세어 돌려서 숨겼다 다시 떠도 같은 색). 지운 카테고리에 남은 할 일은 맨 끝 '기타' 로
    Map<String, List<TodoStore.Todo>> byCategory = new LinkedHashMap<>();
    for (TodoStore.Category category : categories)
      byCategory.put(category.id, new ArrayList<>());
    List<TodoStore.Todo> orphans = new ArrayList<>();
    if (day != null) {
      for (TodoStore.Todo todo : day.shown()) {
        List<TodoStore.Todo> list = byCategory.get(todo.categoryId);
        (list == null ? orphans : list).add(todo);
      }
    }

    body.getChildren().clear();
    if (warning != null) {
      status.setText("⚠ " + warning);
      body.getChildren().add(status);
    }
    for (int i = 0; i < categories.size(); i++) {
      TodoStore.Category category = categories.get(i);
      // 목표 카테고리는 할 일이 없으면 숨긴다 — 손으로 적는 게 아니라 목표 맵에서 내려올 때만 채워져서
      if (TodoStore.isGoalCategory(category.id) && byCategory.get(category.id).isEmpty())
        continue;
      body.getChildren().add(group(category, palette.get(i % palette.size()), byCategory.get(category.id)));
    }
    if (!orphans.isEmpty())
      body.getChildren().add(group(null, "#9b9b9b", orphans));
    // if (day != null)
    // body.getChildren().add(categoryAdder());
  }

  /** 카테고리 하나 — 이름표와 그 밑의 할 일들. category 가 null 이면 '기타' */
  private VBox group(TodoStore.Category category, String color, List<TodoStore.Todo> todos) {
    VBox group = new VBox(8);

    Label name = new Label(category == null ? "기타" : category.title);
    name.getStyleClass().add("todo-goal-title");
    name.setStyle("-fx-text-fill: " + color + ";");

    HBox chip = new HBox(6, name);
    chip.getStyleClass().add("todo-goal");
    chip.setAlignment(Pos.CENTER_LEFT);
    chip.setMaxWidth(HBox.USE_PREF_SIZE);

    if (category != null) {
      Button plus = new Button("+");
      plus.getStyleClass().add("todo-goal-add");
      plus.setFocusTraversable(false);
      plus.setOnAction(e -> openAdder(group, category, color));
      chip.getChildren().add(plus);

      MenuItem rename = new MenuItem("이름 바꾸기");
      rename.setOnAction(e -> openRename(group, chip, category));
      MenuItem remove = new MenuItem("카테고리 지우기");
      remove.setOnAction(e -> removeCategory(category));
      ContextMenu menu = new ContextMenu(rename, remove);
      chip.setOnContextMenuRequested(e -> {
        menu.show(chip, e.getScreenX(), e.getScreenY());
        e.consume();
      });
    }

    group.getChildren().add(chip);
    for (TodoStore.Todo todo : todos)
      group.getChildren().add(row(todo, color));
    return group;
  }

  /** 할 일 한 줄: 네잎 체크칸 · 내용 */
  private HBox row(TodoStore.Todo todo, String color) {
    Clover clover = new Clover(Color.web(color), todo.done);

    Label text = new Label(todo.content);
    text.getStyleClass().add("todo-text");
    text.setWrapText(true);
    markText(text, todo);

    HBox row = new HBox(10, clover, text);
    row.setAlignment(Pos.CENTER_LEFT);
    row.getStyleClass().add("todo-row");

    clover.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      e.consume();
      // 끝낸 날은 이 날 — 밀려 다닌 사본들도 같이 고친다 (그 전 날은 취소선, 그 뒤 날은 없던 것)
      boolean done = !clover.done;
      try {
        store.markDone(day, todo, done ? LocalDate.parse(day.date) : null);
      } catch (IOException ex) {
        showWarning("다른 날 기록을 못 고쳤어: " + ex.getMessage());
      }
      clover.set(todo.done);
      markText(text, todo);
      Milestone milestone = todo.done ? milestone(todo) : null;
      saveDay();
      if (milestone != null)
        onDone.accept(todo, milestone);
      // 목표 칸도 맞추면 같은 칸에서 온 다른 날 할 일이 파일에서 바뀔 수 있어서 이 날을 다시 읽는다
      if (todo.goalId != null && todo.goalNode != null) {
        onGoalDone.accept(todo.goalId, todo.goalNode, todo.done);
        show(LocalDate.parse(day.date));
        onChange.run();
      }
    });
    // 오른쪽 클릭: 이름 바꾸기 · 카테고리 옮기기 · 내일로 미루기 · 지우기 (목표 맵에서 온 거면 맨 위에 목표로 이동하기).
    // 미루는 건 아직 안 끝낸 것만, 지난 날 기록에선 못 한다
    MenuItem rename = new MenuItem("이름 바꾸기");
    rename.setOnAction(e -> openTodoRename(row, text, todo));
    Menu move = moveMenu(todo);
    MenuItem postpone = new MenuItem("내일로 미루기");
    postpone.setOnAction(e -> postpone(todo));
    MenuItem remove = new MenuItem("지우기");
    // 줄만 빼지 않고 다시 그린다 — 비게 된 목표 카테고리 · '기타' 가 바로 숨도록
    remove.setOnAction(e -> {
      day.todos.remove(todo);
      render(null);
      saveDay();
      if (todo.goalId != null && todo.goalNode != null)
        onGoalRemove.accept(todo.goalId, todo.goalNode);
    });
    ContextMenu menu = new ContextMenu(rename, move, postpone, remove);
    if (todo.goalId != null && todo.goalNode != null) {
      MenuItem goal = new MenuItem("목표로 이동하기");
      goal.setOnAction(e -> onGoal.accept(todo.goalId, todo.goalNode));
      menu.getItems().add(0, goal);
    }
    row.setOnContextMenuRequested(e -> {
      postpone.setDisable(todo.done || todo.doneOn != null
          || LocalDate.parse(day.date).isBefore(TodoStore.today()));
      menu.show(row, e.getScreenX(), e.getScreenY());
      e.consume();
    });
    return row;
  }

  /** 할 일 글자 자리에 칸을 띄워 고친다. Enter 로 바꾸고 Esc / 딴 데 누르면 그대로 */
  private void openTodoRename(HBox row, Label text, TodoStore.Todo todo) {
    TextField field = inlineField(PROMPT);
    field.setText(todo.content);
    HBox.setHgrow(field, Priority.ALWAYS);
    int at = row.getChildren().indexOf(text);
    row.getChildren().set(at, field);
    Runnable close = () -> {
      int i = row.getChildren().indexOf(field);
      if (i >= 0)
        row.getChildren().set(i, text);
    };
    onKeys(field, () -> {
      close.run();
      onRelease.run();
    }, content -> {
      todo.content = content;
      text.setText(content);
      close.run();
      saveDay();
      onRelease.run();
    });
    field.focusedProperty().addListener((obs, was, now) -> {
      if (!now) {
        close.run();
        onRelease.run();
      }
    });
    Platform.runLater(() -> {
      field.requestFocus();
      field.selectAll();
    });
  }

  /**
   * "카테고리 옮기기" 밑에 지금 카테고리 말고 다른 카테고리를 늘어놓는다. 목표 카테고리는 목표 맵에서만 채우니 뺀다.
   * 옮길 데가 없으면 메뉴를 흐리게 둔다
   */
  private Menu moveMenu(TodoStore.Todo todo) {
    Menu move = new Menu("카테고리 옮기기");
    for (TodoStore.Category category : categories) {
      if (category.id.equals(todo.categoryId) || TodoStore.isGoalCategory(category.id))
        continue;
      MenuItem item = new MenuItem(category.title);
      item.setOnAction(e -> moveTodo(todo, category));
      move.getItems().add(item);
    }
    move.setDisable(move.getItems().isEmpty());
    return move;
  }

  /** 할 일을 다른 카테고리로 옮긴다. 이 날 기록만 고친다 — 지난 날엔 그때 있던 카테고리로 남게 */
  private void moveTodo(TodoStore.Todo todo, TodoStore.Category category) {
    todo.categoryId = category.id;
    todo.category = category.title;
    saveDay();
    render(null);
  }

  /** 할 일을 다음 날로 넘긴다 (TodoStore.postpone). 넘기고 나면 마도카가 혼낸다 (onPostpone) */
  private void postpone(TodoStore.Todo todo) {
    try {
      store.postpone(day, todo);
    } catch (IOException ex) {
      showWarning("내일로 못 미뤘어: " + ex.getMessage());
      return;
    }
    saveDay();
    render(null);
    onPostpone.accept(todo);
  }

  /**
   * 방금 todo 를 끝내서 칭찬할 때가 됐는지. 칭찬은 아무 때나 하지 않고 이때만 한다 —
   * 절반 끝냈을 때 (할 일이 두 개 이상일 때, 하루 한 번) · 다 끝냈을 때 (하루 한 번) ·
   * 다 끝낸 뒤 새로 적은 걸 끝냈을 때 (그 할 일마다 한 번). 칭찬했다는 건 하루 파일에 적어둔다
   * (저장은 부른 쪽이) — 체크를 풀었다 다시 하거나 껐다 켜도 또 칭찬하지 않게.
   */
  private Milestone milestone(TodoStore.Todo todo) {
    if (todo.late) {
      if (todo.praised)
        return null;
      todo.praised = true;
      return Milestone.EXTRA;
    }
    int finished = day.finished();
    int total = finished + day.left();
    if (finished == total && !day.allPraised) {
      day.allPraised = true;
      day.halfPraised = true;
      return Milestone.ALL;
    }
    if (total >= 2 && finished * 2 >= total && !day.halfPraised) {
      day.halfPraised = true;
      return Milestone.HALF;
    }
    return null;
  }

  /**
   * 넘어온 할 일은 끝낼 때까지 진한 핑크. 끝내면 여느 할 일처럼 돌아간다.
   * 나중 날에 끝낸 걸 그 전 날 기록에서 보면 취소선 (그날 체크칸은 빈 채로)
   */
  private void markText(Label text, TodoStore.Todo todo) {
    text.getStyleClass().removeAll(OVERDUE, STRUCK);
    if (todo.struck(day.date))
      text.getStyleClass().add(STRUCK);
    else if (todo.overdue() && !todo.done)
      text.getStyleClass().add(OVERDUE);
  }

  /** ＋ 를 누르면 그 카테고리 이름표 바로 밑에 칸이 열린다. Enter 넣기 · Esc / 딴 데 누르면 닫기 */
  private void openAdder(VBox group, TodoStore.Category category, String color) {
    for (var node : group.getChildren()) {
      if (node instanceof TextField open) {
        open.requestFocus();
        return;
      }
    }
    TextField field = inlineField(PROMPT);
    group.getChildren().add(1, field);
    onKeys(field, () -> {
      group.getChildren().remove(field);
      onRelease.run();
    }, content -> {
      field.clear();
      TodoStore.Todo todo = store.newTodo(category, content);
      // 다 끝내 칭찬받은 뒤에 적은 건 끝낼 때마다 칭찬한다 (milestone)
      todo.late = day.allPraised;
      day.todos.add(todo);
      group.getChildren().add(row(todo, color));
      saveDay();
    });
    field.focusedProperty().addListener((obs, was, now) -> {
      if (!now && field.getText().isBlank()) {
        group.getChildren().remove(field);
        onRelease.run();
      }
    });
    Platform.runLater(field::requestFocus);
  }

  /** 맨 밑의 "＋ 카테고리". 누르면 그 자리에 이름 칸이 열린다 */
  /*
   * private HBox categoryAdder() {
   * Button add = new Button("+ 카테고리");
   * add.getStyleClass().add("todo-goal-add");
   * HBox box = new HBox(add);
   * box.setAlignment(Pos.CENTER_LEFT);
   *
   * add.setOnAction(e -> {
   * TextField field = inlineField("카테고리 이름 (Enter)");
   * box.getChildren().setAll(field);
   * Runnable close = () -> box.getChildren().setAll(add);
   * onKeys(field, close, title -> {
   * categories.add(store.newCategory(title));
   * saveCategories();
   * render(null);
   * });
   * field.focusedProperty().addListener((obs, was, now) -> {
   * if (!now)
   * close.run();
   * });
   * Platform.runLater(field::requestFocus);
   * });
   * return box;
   * }
   */

  /** 이름표 자리에 이름 칸을 띄워 고친다. Enter 로 바꾸고 Esc / 딴 데 누르면 그대로 */
  private void openRename(VBox group, HBox chip, TodoStore.Category category) {
    TextField field = inlineField("카테고리 이름 (Enter)");
    field.setText(category.title);
    int at = group.getChildren().indexOf(chip);
    group.getChildren().set(at, field);
    Runnable close = () -> {
      int i = group.getChildren().indexOf(field);
      if (i >= 0)
        group.getChildren().set(i, chip);
    };
    onKeys(field, () -> {
      close.run();
      onRelease.run();
    }, title -> {
      category.title = title;
      saveCategories();
      render(null);
      onRelease.run();
    });
    field.focusedProperty().addListener((obs, was, now) -> {
      if (!now) {
        close.run();
        onRelease.run();
      }
    });
    Platform.runLater(() -> {
      field.requestFocus();
      field.selectAll();
    });
  }

  /** 카테고리만 지운다. 오늘 거기 있던 할 일은 '기타' 로 남고, 지난 기록은 그대로다 */
  private void removeCategory(TodoStore.Category category) {
    categories.remove(category);
    saveCategories();
    render(null);
  }

  private TextField inlineField(String prompt) {
    TextField field = new TextField();
    // 스킨을 미리 달아 둔다. 스킨 없이 포커스를 받으면 씬이 IME 를 안 붙여서
    // 한글이 따로 뜬 입력기 창에서 조합되고 한 글자씩 끊겨 들어온다
    field.setSkin(new TextFieldSkin(field));
    field.getStyleClass().add("todo-input");
    field.setPromptText(prompt);
    return field;
  }

  /** Esc 면 close, Enter 면 앞뒤 공백 뗀 글자로 submit (비었으면 무시) */
  private void onKeys(TextField field, Runnable close, Consumer<String> submit) {
    field.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        // 위젯까지 내려가지 않게 여기서 끊는다
        e.consume();
        close.run();
      } else if (e.getCode() == KeyCode.ENTER) {
        e.consume();
        String text = field.getText().trim();
        if (!text.isEmpty())
          submit.accept(text);
      }
    });
  }

  /**
   * 목표 맵에서 칸을 끝냈을 때 (GoalPane 이 Main 을 거쳐 부른다) — 그 칸에서 내려온 오늘 할 일을
   * 손으로 체크한 것과 똑같이 끝낸다 (밀려 다닌 사본 · 칭찬까지). 오늘로 다시 읽어서 넘겨받을 것부터 받는다.
   * 지난 날에만 남은 건 TodoStore.markGoalPast 가 취소선으로. done 이 false 면 오늘 끝낸 것만 푼다
   */
  public void finishGoal(String goalId, List<String> nodeIds, boolean done) {
    refresh();
    if (day == null)
      return;
    String on = day.date;
    Set<String> today = new HashSet<>();
    Milestone milestone = null;
    TodoStore.Todo praised = null;
    boolean changed = false;
    try {
      for (TodoStore.Todo todo : day.shown()) {
        if (!TodoStore.fromGoal(todo, goalId, nodeIds))
          continue;
        today.add(todo.id);
        if (done ? todo.doneOn != null : !on.equals(todo.doneOn))
          continue;
        store.markDone(day, todo, done ? LocalDate.parse(on) : null);
        changed = true;
        Milestone m = todo.done ? milestone(todo) : null;
        if (m != null) {
          milestone = m;
          praised = todo;
        }
      }
      if (changed)
        saveDay();
      if (store.markGoalPast(goalId, nodeIds, done, today))
        changed = true;
    } catch (IOException e) {
      showWarning("다른 날 기록을 못 고쳤어: " + e.getMessage());
    }
    if (!changed)
      return;
    render(null);
    onChange.run();
    if (milestone != null)
      onDone.accept(praised, milestone);
  }

  private void saveDay() {
    try {
      // 카테고리 이름이 바뀌었으면 오늘 기록에도 새 이름을 적는다
      Map<String, String> titles = new LinkedHashMap<>();
      for (TodoStore.Category category : categories)
        titles.put(category.id, category.title);
      for (TodoStore.Todo todo : day.todos)
        todo.category = titles.getOrDefault(todo.categoryId, todo.category);
      store.saveDay(day);
      onChange.run();
    } catch (IOException e) {
      showWarning("할 일을 못 적었어: " + e.getMessage());
    }
  }

  private void saveCategories() {
    try {
      store.saveCategories(categories);
    } catch (IOException e) {
      showWarning("카테고리를 못 적었어: " + e.getMessage());
      return;
    }
    // 오늘 할 일에 적힌 카테고리 이름도 맞춰둔다
    if (day != null && !day.todos.isEmpty())
      saveDay();
  }

  private void showWarning(String text) {
    status.setText("⚠ " + text);
    if (!body.getChildren().contains(status))
      body.getChildren().add(0, status);
  }

  /** 투두메이트 체크칸 — 동그라미 넷을 겹친 네잎. 한 건 카테고리 색에 흰 체크 */
  static final class Clover extends StackPane {
    private final Color color;
    private final List<Circle> leaves = new ArrayList<>();
    private final SVGPath check = new SVGPath();
    private boolean done;

    /** 할 일 창 주간 줄의 날짜 칸 — 체크 대신 남은 개수를 흰 글씨로 얹는다 (count 가 null 이면 체크) */
    Clover(Color color, boolean filled, String count) {
      this(color, filled);
      if (count == null)
        return;
      check.setVisible(false);
      Label number = new Label(count);
      number.getStyleClass().add("todo-week-count");
      getChildren().add(number);
      setCursor(Cursor.DEFAULT);
    }

    Clover(Color color, boolean done) {
      this.color = color;
      Group shape = new Group();
      for (double[] c : new double[][] { { 7, 7 }, { 15, 7 }, { 7, 15 }, { 15, 15 } }) {
        Circle leaf = new Circle(c[0], c[1], 7);
        leaves.add(leaf);
        shape.getChildren().add(leaf);
      }
      check.setContent("M6.5 11.5 L9.8 14.6 L15.6 8.2");
      check.setFill(null);
      check.setStroke(Color.WHITE);
      check.setStrokeWidth(2.2);
      check.setStrokeLineCap(StrokeLineCap.ROUND);
      check.setStrokeLineJoin(StrokeLineJoin.ROUND);
      getChildren().addAll(shape, check);
      setMinSize(22, 22);
      setMaxSize(22, 22);
      setCursor(Cursor.HAND);
      set(done);
    }

    void set(boolean done) {
      this.done = done;
      for (Circle leaf : leaves)
        leaf.setFill(done ? color : UNDONE);
      check.setVisible(done);
    }
  }
}
