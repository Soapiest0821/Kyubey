package kyubey.chat;

import kyubey.core.SettingsDialog;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import kyubey.todo.TodoStore;
import kyubey.ui.WindowDrag;

/**
 * 수다 화면 머리말의 호감도를 두 번 누르면 뜨는 창 — 호감도가 왜 오르내렸는지 모아 본다.
 *
 * 한 줄에 바뀐 만큼, 마도카 속마음 한마디(길면 여러 줄), 그때 시각. 최근 것부터. 보기만 한다.
 * 아직 못 보는 이유(Affection.revealed)는 자물쇠로 가린다 — 오르내린 만큼과 시각은 보인다.
 * 한 줄을 두 번 누르면 창을 닫고 그때 그 말풍선으로 간다 (jump) — 시각은 원래 보이니 잠긴 줄도 간다.
 */
public final class AffectionDialog {

  /** 올해 안이면 "10월 12일 오후 3:04" */
  private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN);
  /** 올해가 아니면 연도까지 짧게 */
  private static final DateTimeFormatter YEAR_WHEN = DateTimeFormatter.ofPattern("yyyy.M.d. a h:mm", Locale.KOREAN);

  private AffectionDialog() {
  }

  /**
   * 창을 띄우고 닫힐 때까지 기다린다. revealed 가 거짓인 기록은 이유를 가린다.
   * jump 는 두 번 누른 기록의 말풍선으로 가 보고, 갔으면 참 — 그때 창을 닫는다. 못 갔으면 창은 그대로
   */
  public static void show(Window owner, int liking, List<Affection.Change> history, List<String> emojis,
      Predicate<Affection.Change> revealed, Predicate<Affection.Change> jump) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label(Affection.emoji(liking, emojis) + " 호감도 " + liking);
    heading.getStyleClass().add("notice-title");

    boolean locked = history.stream().anyMatch(revealed.negate());
    Label sub = new Label(locked
        ? "오늘 오르내린 이유는 잠겨 있어 · 내일 아침 " + TodoStore.DAY_STARTS_AT + "시에 열려 · 두 번 누르면 그 말로 가 · Esc 닫기"
        : "호감도가 오르내린 이유야 · 두 번 누르면 그 말로 가 · Esc 닫기");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    List<Affection.Change> newestFirst = new ArrayList<>(history);
    Collections.reverse(newestFirst);

    ListView<Affection.Change> list = new ListView<>();
    list.getStyleClass().add("dialog-alias-list");
    list.getItems().setAll(newestFirst);
    list.setPlaceholder(new Label("아직 오르내린 적이 없어"));
    list.setCellFactory(v -> new ListCell<>() {
      {
        // 칸이 제 글자 폭을 주장하지 않게 — 안 그러면 목록이 좌우 스크롤을 단다
        setPrefWidth(0);
        setOnMouseClicked(e -> {
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null
              && jump.test(getItem()))
            dialog.close();
        });
      }

      @Override
      protected void updateItem(Affection.Change change, boolean empty) {
        super.updateItem(change, empty);
        setText(null);
        setGraphic(empty || change == null ? null : row(change, revealed.test(change), this));
      }
    });
    VBox.setVgrow(list, Priority.ALWAYS);

    Button close = new Button("닫기");
    close.getStyleClass().add("dialog-button");
    close.setOnAction(e -> dialog.close());
    HBox buttons = new HBox(close);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, heading, sub, list, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(16));
    // 지난 대화 창(ChatArchiveDialog)과 같은 크기 — 뒤 창을 통째로 가리지 않게
    root.setPrefSize(480, 320);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        dialog.close();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      list.requestFocus();
    });
    dialog.showAndWait();
  }

  /** 목록 한 줄: "+2  헤헤 고민 들어줘서 고마워!        10월 12일 오후 3:04" — 이유가 길면 여러 줄로 */
  private static HBox row(Affection.Change change, boolean revealed, ListCell<?> cell) {
    Label delta = new Label((change.delta > 0 ? "+" : "") + change.delta);
    delta.getStyleClass().add(change.delta > 0 ? "liking-up" : "liking-down");
    delta.setMinWidth(28);

    String text = !revealed ? "🔒 아직 잠겨 있어"
        : change.reason == null || change.reason.isBlank() ? "(말 없이)" : change.reason;
    Label reason = new Label(text);
    reason.getStyleClass().add(revealed ? "liking-reason" : "liking-locked");
    reason.setMinWidth(0);
    reason.setWrapText(true);
    reason.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(reason, Priority.ALWAYS);

    Label when = new Label(format(change.at));
    when.getStyleClass().add("chat-time");
    when.setMinWidth(Region.USE_PREF_SIZE);

    // 칸 폭에서 앞뒤를 뺀 만큼을 이유 폭으로 못박는다 — 그래야 칸 높이가 줄 수만큼 늘어난다
    reason.prefWidthProperty().bind(Bindings.createDoubleBinding(
        () -> Math.max(0, cell.getWidth() - cell.getInsets().getLeft() - cell.getInsets().getRight()
            - delta.getMinWidth() - when.prefWidth(-1) - 8 * 2 - 2),
        cell.widthProperty(), cell.insetsProperty()));

    HBox row = new HBox(8, delta, reason, when);
    row.setAlignment(Pos.BASELINE_LEFT);
    return row;
  }

  private static String format(long at) {
    LocalDateTime when = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault());
    return (when.getYear() == LocalDateTime.now().getYear() ? WHEN : YEAR_WHEN).format(when);
  }

  /** 띄운 쪽 창 한가운데로 (Notice 와 같은 이치) */
  private static void centerOn(Stage popup, Window owner) {
    if (owner == null || owner.getWidth() <= 0 || Double.isNaN(owner.getX())) {
      popup.centerOnScreen();
      return;
    }
    popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
    popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
  }
}
