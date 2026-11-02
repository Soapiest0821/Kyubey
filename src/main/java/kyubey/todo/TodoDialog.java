package kyubey.todo;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.core.SettingsDialog;
import kyubey.ui.WindowDrag;

/**
 * 할 일 창. 투두메이트 앱처럼 위에 한 주 줄이 있고, 날마다 네잎에 남은 할 일 개수를 띄운다.
 * 날짜를 누르면 밑에 그날 할 일이 뜨고, 거기서 적고 지우고 체크하는 건 입력창 밑 할 일(TodoPane)과 같다.
 */
public final class TodoDialog {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN);
  private static final String[] WEEKDAYS = { "일", "월", "화", "수", "목", "금", "토" };
  /** 남은 할 일이 있는 날의 네잎 색 — 앱처럼 거의 검정 */
  private static final Color PENDING = Color.web("#222222");
  private static final Color EMPTY = Color.web("#d9dde2");

  private final TodoStore store = new TodoStore();
  private final TodoPane pane = new TodoPane();
  private final HBox week = new HBox();
  private final Label month = new Label();

  /** 띄운 창 — 목표로 이동할 때 닫으려고 */
  private Stage dialog;

  /** 지금 보이는 주의 일요일 */
  private LocalDate weekStart;
  private LocalDate selected;

  private TodoDialog(TodoPane docked) {
    pane.setColors(docked.colors());
    pane.setOnDone(docked.onDone());
    pane.setOnPostpone(docked.onPostpone());
    pane.setOnGoalDone(docked.onGoalDone());
    pane.setOnGoalRemove(docked.onGoalRemove());
    pane.setOnChange(this::drawWeek);
    // 목표 쪼개기 화면은 메인 창에 있어서, 이 창을 닫고 나서 간다
    pane.setOnGoal((goalId, nodeId) -> {
      dialog.close();
      Platform.runLater(() -> docked.onGoal().accept(goalId, nodeId));
    });
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. docked 는 입력창 밑 할 일 — 색과 칭찬을 빌려 쓴다 */
  public static void show(Stage owner, TodoPane docked) {
    new TodoDialog(docked).open(owner);
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

    Label hint = new Label("날짜를 누르면 그날 할 일 · ← → 하루씩 · Esc 닫기");
    hint.getStyleClass().add("dialog-path-label");

    VBox root = new VBox(12, header, week, pane, hint);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(16));
    root.setPrefSize(340, 440);

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
    // ← → 로 하루씩. 버튼이 화살표를 포커스 이동으로 먹기 전에 필터로 잡되, 글 적는 칸에선 커서 이동으로 둔다
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() != KeyCode.LEFT && e.getCode() != KeyCode.RIGHT)
        return;
      if (scene.getFocusOwner() instanceof TextInputControl)
        return;
      e.consume();
      select(selected.plusDays(e.getCode() == KeyCode.LEFT ? -1 : 1));
    });

    select(TodoStore.today());

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      root.requestFocus();
    });
    dialog.showAndWait();
  }

  private Button arrow(String text, int weeks) {
    Button button = new Button(text);
    button.getStyleClass().add("todo-week-arrow");
    button.setOnAction(e -> {
      weekStart = weekStart.plusWeeks(weeks);
      drawWeek();
    });
    return button;
  }

  /** 그 날을 고른다 — 그 날이 든 주로 옮기고 밑에 그날 할 일을 띄운다 */
  private void select(LocalDate date) {
    selected = date;
    weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    pane.show(date);
    drawWeek();
  }

  /** 한 주 줄을 다시 그린다. 날마다 요일 · 네잎 · 날짜 — 할 일이 없으면 빈 네잎, 다 끝냈으면 체크, 남았으면 남은 개수 */
  private void drawWeek() {
    // 주 가운데(수요일)가 든 달을 머리에 단다
    month.setText(MONTH.format(weekStart.plusDays(3)));
    week.getChildren().clear();
    LocalDate today = TodoStore.today();
    Color doneColor = Color.web(pane.colors().get(0));
    for (int i = 0; i < 7; i++) {
      LocalDate date = weekStart.plusDays(i);
      int total = 0;
      int left = 0;
      try {
        TodoStore.Day day = store.day(date);
        total = day.shown().size();
        left = day.left();
      } catch (IOException ignored) {
      }

      TodoPane.Clover clover;
      if (total == 0)
        clover = new TodoPane.Clover(EMPTY, false, "");
      else if (left == 0)
        clover = new TodoPane.Clover(doneColor, true);
      else
        clover = new TodoPane.Clover(PENDING, true, String.valueOf(left));
      clover.setMouseTransparent(true);

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
    popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
    popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
  }
}
