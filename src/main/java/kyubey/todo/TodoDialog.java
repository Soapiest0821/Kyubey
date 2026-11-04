package kyubey.todo;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.geometry.Rectangle2D;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.core.SettingsDialog;
import kyubey.ui.WindowDrag;

/**
 * 할 일 창. 투두메이트 앱처럼 위에 한 주 줄이 있고, 날마다 네잎에 남은 할 일 개수를 띄운다.
 * 날짜를 누르면 밑에 그날 할 일이 뜨고, 거기서 적고 지우고 체크하는 건 입력창 밑 할 일(TodoPane)과 같다.
 *
 * 위젯이 전체화면이면 한 주 줄 대신 한 달 달력을 크게 펴고, 날마다 칸 안에 그날 할 일을 적어 보인다.
 * 고른 날 할 일은 달력 오른쪽에 뜬다. ‹ › 는 한 주 대신 한 달씩.
 * 위젯이 전체화면이 아니어도 F11 이나 오른쪽 아래 📅 버튼이면 같은 한 달 달력으로 바꿔 띄운다 (F11 로 되돌린다).
 *
 * ← → 는 하루씩, ↑ ↓ 는 한 주씩 고른 날을 옮긴다.
 * 네잎은 할 일이 없으면 빈 것, 그날 다 끝냈으면 초록 체크, 끝은 났는데 밀려서 나중에 끝낸 게 (취소선) 있으면 노란 세모,
 * 남았으면 검정에 남은 개수, 일주일이 지났는데도 남았으면 빨간 X.
 */
public final class TodoDialog {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN);
  private static final String[] WEEKDAYS = { "일", "월", "화", "수", "목", "금", "토" };
  /** 남은 할 일이 있는 날의 네잎 색 — 앱처럼 거의 검정 */
  private static final Color PENDING = Color.web("#222222");
  private static final Color EMPTY = Color.web("#d9dde2");
  /** 끝은 났지만 밀려서 나중 날에 끝낸 게 (취소선) 섞인 날 */
  private static final Color STRUCK = Color.web("#f2c230");
  /** 일주일이 지났는데도 남은 게 있는 날 */
  private static final Color MISSED = Color.web("#e05555");
  private static final String TRIANGLE = "M11 6.6 L15.4 14.6 L6.6 14.6 Z";
  private static final String CROSS = "M7.6 7.6 L14.4 14.4 M14.4 7.6 L7.6 14.4";
  /** 한 달 달력 칸에 적어 보일 할 일 줄 수 — 넘치면 "+n" 으로 접는다 */
  private static final int MONTH_ITEMS = 3;
  private static final double FULL_WIDTH = 1100;
  private static final double FULL_HEIGHT = 720;
  private static final double FULL_PANE_WIDTH = 360;

  private final TodoStore store = new TodoStore();
  private final TodoPane pane = new TodoPane();
  private final HBox week = new HBox();
  private final GridPane monthGrid = new GridPane();
  private final Label month = new Label();
  /** 전체화면 — 한 달 달력으로 편다 */
  private final boolean full;

  /** 띄운 창 — 목표로 이동할 때 닫으려고 */
  private Stage dialog;

  /** 지금 보이는 주의 일요일 */
  private LocalDate weekStart;
  /** 지금 보이는 달 (전체화면) */
  private YearMonth viewMonth;
  private LocalDate selected;
  /** 닫힐 때 반대 모양(한 주 줄 ↔ 한 달 달력)으로 다시 띄울지 — F11 · 📅 버튼 */
  private boolean toggle;

  private TodoDialog(TodoPane docked, boolean full, LocalDate start) {
    this.full = full;
    this.selected = start;
    pane.setColors(docked.colors());
    pane.setOnDone(docked.onDone());
    pane.setOnPostpone(docked.onPostpone());
    pane.setOnGoalDone(docked.onGoalDone());
    pane.setOnGoalRemove(docked.onGoalRemove());
    pane.setOnChange(this::draw);
    // 목표 쪼개기 화면은 메인 창에 있어서, 이 창을 닫고 나서 간다
    pane.setOnGoal((goalId, nodeId) -> {
      dialog.close();
      Platform.runLater(() -> docked.onGoal().accept(goalId, nodeId));
    });
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. docked 는 입력창 밑 할 일 — 색과 칭찬을 빌려 쓴다. full 이면 한 달 달력 */
  public static void show(Stage owner, TodoPane docked, boolean full) {
    LocalDate date = TodoStore.today();
    while (true) {
      TodoDialog todo = new TodoDialog(docked, full, date);
      todo.open(owner);
      if (!todo.toggle)
        return;
      full = !full;
      date = todo.selected;
    }
  }

  /** 반대 모양으로 바꿔 띄우려고 이 창을 닫는다 — show 가 이어서 띄운다 */
  private void switchView() {
    toggle = true;
    dialog.close();
  }

  private void open(Stage owner) {
    dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    month.getStyleClass().add("notice-title");
    Button prev = arrow("‹", -1);
    Button next = arrow("›", 1);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox header = new HBox(4, month, spacer, prev, next);
    header.setAlignment(Pos.CENTER_LEFT);

    week.setAlignment(Pos.CENTER);
    week.getStyleClass().add("todo-week");

    VBox.setVgrow(pane, Priority.ALWAYS);

    Label hint = new Label(full
        ? "날짜를 누르면 그날 할 일 · ← → 하루씩 · ↑ ↓ 한 주씩 · F11 작게 · Esc 닫기"
        : "← → 하루씩 · ↑ ↓ 한 주씩 · Esc 닫기");
    hint.getStyleClass().add("dialog-path-label");
    hint.setMinWidth(0);

    Region root;
    if (full) {
      buildMonthGrid();
      VBox.setVgrow(monthGrid, Priority.ALWAYS);
      VBox calendar = new VBox(12, header, monthGrid, hint);
      HBox.setHgrow(calendar, Priority.ALWAYS);
      pane.setPrefWidth(FULL_PANE_WIDTH);
      pane.setMinWidth(FULL_PANE_WIDTH);
      root = new HBox(16, calendar, pane);
      root.setPrefSize(FULL_WIDTH, FULL_HEIGHT);
      root.setStyle("-fx-font-size: 15px;");
    } else {
      Button expand = new Button("📅");
      expand.getStyleClass().add("todo-week-arrow");
      expand.setTooltip(new Tooltip("크게 보기 (F11)"));
      expand.setFocusTraversable(false);
      expand.setOnAction(e -> switchView());
      Region gap = new Region();
      HBox.setHgrow(gap, Priority.ALWAYS);
      HBox footer = new HBox(6, hint, gap, expand);
      footer.setAlignment(Pos.CENTER_LEFT);
      root = new VBox(12, header, week, pane, footer);
      root.setPrefSize(340, 440);
    }
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(16));

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    // 할 일 칸에서 Esc 는 그 칸만 닫는다 (TodoPane.onKeys 가 먼저 먹는다). 그 밖에서 Esc 면 창을 닫는다
    scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        dialog.close();
      }
    });
    // F11 — 한 주 줄 ↔ 한 달 달력
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.F11) {
        e.consume();
        switchView();
      }
    });
    // ← → 로 하루씩, ↑ ↓ 로 한 주씩. 버튼이 화살표를 포커스 이동으로 먹기 전에 필터로 잡되, 글 적는 칸에선 커서 이동으로 둔다
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      int days = switch (e.getCode()) {
        case LEFT -> -1;
        case RIGHT -> 1;
        case UP -> -7;
        case DOWN -> 7;
        default -> 0;
      };
      if (days == 0 || scene.getFocusOwner() instanceof TextInputControl)
        return;
      e.consume();
      select(selected.plusDays(days));
    });

    select(selected);

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      root.requestFocus();
    });
    dialog.showAndWait();
  }

  /** ‹ › — 평소엔 한 주씩, 전체화면 달력에선 한 달씩 */
  private Button arrow(String text, int step) {
    Button button = new Button(text);
    button.getStyleClass().add("todo-week-arrow");
    button.setOnAction(e -> {
      if (full)
        viewMonth = viewMonth.plusMonths(step);
      else
        weekStart = weekStart.plusWeeks(step);
      draw();
    });
    return button;
  }

  /** 그 날을 고른다 — 그 날이 든 주(달)로 옮기고 그날 할 일을 띄운다 */
  private void select(LocalDate date) {
    selected = date;
    weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    viewMonth = YearMonth.from(date);
    pane.show(date);
    draw();
  }

  private void draw() {
    if (full)
      drawMonth();
    else
      drawWeek();
  }

  /**
   * 할 일이 없으면 빈 네잎, 그날 다 끝냈으면 체크, 끝은 났는데 취소선(밀려서 나중에 끝낸 것)이 있으면 노란 세모,
   * 남았으면 남은 개수, 일주일이 지났는데도 남았으면 빨간 X
   */
  private TodoPane.Clover clover(TodoStore.Day day, LocalDate date) {
    List<TodoStore.Todo> shown = day == null ? List.of() : day.shown();
    int left = day == null ? 0 : day.left();
    TodoPane.Clover clover;
    if (shown.isEmpty())
      clover = new TodoPane.Clover(EMPTY, false, "");
    else if (left == 0) {
      boolean struck = shown.stream().anyMatch(t -> !t.done && t.struck(day.date));
      clover = struck
          ? new TodoPane.Clover(STRUCK, TRIANGLE)
          : new TodoPane.Clover(Color.web(pane.colors().get(0)), true);
    } else if (!date.isAfter(TodoStore.today().minusDays(7)))
      clover = new TodoPane.Clover(MISSED, CROSS);
    else
      clover = new TodoPane.Clover(PENDING, true, String.valueOf(left));
    clover.setMouseTransparent(true);
    return clover;
  }

  private TodoStore.Day dayOrNull(LocalDate date) {
    try {
      return store.day(date);
    } catch (IOException e) {
      return null;
    }
  }

  /** 달력 틀 — 맨 위 요일 줄 하나, 그 밑 여섯 주. 칸은 판 크기에 맞춰 고르게 나눈다 */
  private void buildMonthGrid() {
    monthGrid.getStyleClass().add("todo-month");
    monthGrid.setHgap(4);
    monthGrid.setVgap(4);
    for (int i = 0; i < 7; i++) {
      ColumnConstraints column = new ColumnConstraints();
      column.setPercentWidth(100.0 / 7);
      monthGrid.getColumnConstraints().add(column);
    }
    monthGrid.getRowConstraints().add(new RowConstraints());
    for (int i = 0; i < 6; i++) {
      RowConstraints row = new RowConstraints();
      row.setVgrow(Priority.ALWAYS);
      row.setFillHeight(true);
      monthGrid.getRowConstraints().add(row);
    }
  }

  /** 한 달 달력을 다시 그린다. 칸마다 날짜 · 네잎 · 그날 할 일 몇 줄 (끝낸 건 취소선). 딴 달 날은 흐리게 */
  private void drawMonth() {
    month.setText(MONTH.format(viewMonth.atDay(1)));
    monthGrid.getChildren().clear();
    for (int i = 0; i < 7; i++) {
      Label weekday = new Label(WEEKDAYS[i]);
      weekday.getStyleClass().add("todo-week-day");
      weekday.setMaxWidth(Double.MAX_VALUE);
      weekday.setAlignment(Pos.CENTER);
      monthGrid.add(weekday, i, 0);
    }
    LocalDate today = TodoStore.today();
    LocalDate start = viewMonth.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    for (int i = 0; i < 42; i++) {
      LocalDate date = start.plusDays(i);
      TodoStore.Day day = dayOrNull(date);

      Label number = new Label(String.valueOf(date.getDayOfMonth()));
      number.getStyleClass().add("todo-week-date");
      if (date.equals(today))
        number.getStyleClass().add("todo-week-today");
      if (date.equals(selected))
        number.getStyleClass().add("todo-week-selected");
      Region gap = new Region();
      HBox.setHgrow(gap, Priority.ALWAYS);
      HBox top = new HBox(number, gap, clover(day, date));
      top.setAlignment(Pos.CENTER_LEFT);

      VBox cell = new VBox(3, top);
      List<TodoStore.Todo> todos = day == null ? List.of() : day.shown();
      for (int t = 0; t < Math.min(todos.size(), MONTH_ITEMS); t++) {
        TodoStore.Todo todo = todos.get(t);
        Label item = new Label(todo.content);
        item.getStyleClass().add("todo-month-item");
        if (todo.done || todo.struck(day.date))
          item.getStyleClass().add("todo-month-item-done");
        item.setMaxWidth(Double.MAX_VALUE);
        cell.getChildren().add(item);
      }
      if (todos.size() > MONTH_ITEMS) {
        Label more = new Label("+" + (todos.size() - MONTH_ITEMS));
        more.getStyleClass().add("todo-month-more");
        cell.getChildren().add(more);
      }
      cell.getStyleClass().add("todo-month-cell");
      if (!YearMonth.from(date).equals(viewMonth))
        cell.getStyleClass().add("todo-month-cell-out");
      if (date.equals(selected))
        cell.getStyleClass().add("todo-month-cell-selected");
      cell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
      cell.setMinSize(0, 0);
      cell.setCursor(Cursor.HAND);
      cell.setOnMouseClicked(e -> select(date));
      monthGrid.add(cell, i % 7, i / 7 + 1);
    }
  }

  /** 한 주 줄을 다시 그린다. 날마다 요일 · 네잎 · 날짜 — 할 일이 없으면 빈 네잎, 다 끝냈으면 체크, 남았으면 남은 개수 */
  private void drawWeek() {
    // 주 가운데(수요일)가 든 달을 머리에 단다
    month.setText(MONTH.format(weekStart.plusDays(3)));
    week.getChildren().clear();
    LocalDate today = TodoStore.today();
    for (int i = 0; i < 7; i++) {
      LocalDate date = weekStart.plusDays(i);
      TodoPane.Clover clover = clover(dayOrNull(date), date);

      Label weekday = new Label(WEEKDAYS[i]);
      weekday.getStyleClass().add("todo-week-day");
      Label number = new Label(String.valueOf(date.getDayOfMonth()));
      number.getStyleClass().add("todo-week-date");
      if (date.equals(today))
        number.getStyleClass().add("todo-week-today");
      if (date.equals(selected))
        number.getStyleClass().add("todo-week-selected");

      VBox cell = new VBox(4, weekday, clover, number);
      cell.setAlignment(Pos.CENTER);
      cell.getStyleClass().add("todo-week-cell");
      cell.setCursor(Cursor.HAND);
      HBox.setHgrow(cell, Priority.ALWAYS);
      cell.setMaxWidth(Double.MAX_VALUE);
      cell.setOnMouseClicked(e -> select(date));
      week.getChildren().add(cell);
    }
  }

  /** 메인 창 가운데에 띄운다. 메인 창 자리를 아직 모르면 화면 가운데 */
  private static void centerOn(Stage popup, Window owner) {
    if (owner == null || owner.getWidth() <= 0 || Double.isNaN(owner.getX())) {
      popup.centerOnScreen();
      return;
    }
    double x = owner.getX() + (owner.getWidth() - popup.getWidth()) / 2;
    double y = owner.getY() + (owner.getHeight() - popup.getHeight()) / 2;
    // 작은 위젯 가운데에 큰 달력을 맞추면 화면 밖으로 삐져나갈 수 있어서, 메인 창이 있는 화면 안으로 당긴다
    List<Screen> screens = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
    Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
    x = Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - popup.getWidth()));
    y = Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - popup.getHeight()));
    popup.setX(x);
    popup.setY(y);
  }
}
