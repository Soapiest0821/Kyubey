package kyubey.ui;

import kyubey.core.ChatArchive;
import kyubey.core.ChatHistory;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 수다 화면에서 마도카를 누르면 뜨는 창 — "채팅 삭제" 로 지운 지난 대화들을 꺼내 본다.
 *
 * 왼쪽에 지운 대화 목록(최근 것부터), 고르면 오른쪽에 그때 말풍선이 그대로 그려진다.
 * 보기만 한다 — 이어서 말을 걸거나 되살리는 길은 없다. 지운 얘기를 마도카가 다시 알게 되면
 * 지운 보람이 없어서.
 *
 * 말풍선 모양은 수다 화면(ChatPane)과 같은 스타일을 빌려 쓴다. 다만 같은 분에 이어진 줄
 * 시각 합치기는 안 한다 — 읽기만 하는 곳이라 줄마다 시각이 붙어 있는 편이 찾아보기 쉽다.
 */
public final class ChatArchiveDialog {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN);
  /** 목록 한 칸에 적는 날짜 ("9월 21일") */
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);
  /** 올해가 아닌 날 ("2025.9.28") — 연도를 붙여도 목록 폭에 들어가게 짧게 */
  private static final DateTimeFormatter YEAR_DAY = DateTimeFormatter.ofPattern("yyyy.M.d.");

  /** 왼쪽 목록 폭. "12월 28일 · 300줄" 이 한 줄에 들어가는 만큼 */
  private static final double LIST_WIDTH = 140;

  /** 말풍선 최대 폭. 오른쪽 칸(창 480 - 여백 - 목록 ≈ 290)에서 시각 붙을 자리를 빼고 남는 만큼 */
  private static final double BUBBLE_WIDTH = 200;

  private ChatArchiveDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다 */
  public static void show(Stage owner, ChatArchive archive) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("📜 지난 대화");
    heading.getStyleClass().add("notice-title");

    Label sub = new Label("채팅 삭제로 지운 대화들이야. 보기만 할 수 있어 · Esc 닫기");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    VBox log = new VBox(8);
    log.setPadding(new Insets(4, 6, 4, 6));
    log.setFillWidth(true);
    ScrollPane scroll = new ScrollPane(log);
    scroll.getStyleClass().add("chat-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    // 말풍선 폭이 스크롤 폭을 따라가고, 스크롤 폭이 또 말풍선 폭을 따라가면 서로 밀고 당기며
    // 화면이 떨린다. 스크롤은 남는 자리만 받게 해서(pref 0 + 늘리기) 폭이 안에 든 것에 안 휘둘리게 한다.
    scroll.setPrefWidth(0);
    HBox.setHgrow(scroll, Priority.ALWAYS);

    List<ChatArchive.Entry> entries = archive.list();
    ListView<ChatArchive.Entry> list = new ListView<>();
    list.getStyleClass().add("dialog-alias-list");
    list.setMinWidth(LIST_WIDTH);
    list.setPrefWidth(LIST_WIDTH);
    list.setMaxWidth(LIST_WIDTH);
    list.getItems().setAll(entries);
    list.setPlaceholder(new Label("아직 지운 대화가 없어"));
    list.setCellFactory(v -> new ListCell<>() {
      {
        // 칸이 제 글자 폭을 주장하지 않게 — 안 그러면 목록이 좌우 스크롤을 단다
        setPrefWidth(0);
      }

      @Override
      protected void updateItem(ChatArchive.Entry entry, boolean empty) {
        super.updateItem(entry, empty);
        setText(empty || entry == null ? null : summary(entry));
      }
    });
    list.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
      render(log, is);
      // 긴 대화는 끝에서부터 읽는 게 보통이라 맨 아래로 (ChatPane.scrollToBottom 과 같은 이치)
      Platform.runLater(() -> {
        scroll.applyCss();
        scroll.layout();
        scroll.setVvalue(1.0);
      });
    });

    HBox body = new HBox(12, list, scroll);
    VBox.setVgrow(body, Priority.ALWAYS);

    Button close = new Button("닫기");
    close.getStyleClass().add("dialog-button");
    close.setOnAction(e -> dialog.close());
    HBox buttons = new HBox(close);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, heading, sub, body, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(16));
    // 위젯 창(600x400)보다 작게 — 위에 떠도 뒤 창을 통째로 가리지 않게
    root.setPrefSize(480, 320);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(ChatArchiveDialog.class.getResource("/style/style.css").toExternalForm());
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
      // 처음엔 아무것도 펼치지 않는다 — 목록에서 골라야 그 대화가 보인다
      list.requestFocus();
    });
    dialog.showAndWait();
  }

  /**
   * 목록 한 칸: 마지막으로 말이 오간 날과 줄 수 ("9월 28일 · 42줄").
   * 올해가 아니면 연도까지 넣되 목록 폭에 들어가게 짧게 ("2025.9.28 · 42줄").
   */
  private static String summary(ChatArchive.Entry entry) {
    LocalDate last = when(entry.lines.get(entry.lines.size() - 1)).toLocalDate();
    String day = last.getYear() == LocalDate.now().getYear() ? DAY.format(last) : YEAR_DAY.format(last);
    return day + " · " + entry.lines.size() + "줄";
  }

  private static void render(VBox log, ChatArchive.Entry entry) {
    log.getChildren().clear();
    if (entry == null)
      return;

    LocalDate drawnDay = null;
    for (ChatHistory.Line line : entry.lines) {
      LocalDateTime when = when(line);
      if (!when.toLocalDate().equals(drawnDay)) {
        drawnDay = when.toLocalDate();
        log.getChildren().add(dateMark(drawnDay));
      }
      log.getChildren().add(bubble(line.text, line.mine, when));
    }
  }

  /** 수다 화면과 같은 모양의 말풍선. 내 말은 왼쪽, 마도카 말은 오른쪽 */
  private static HBox bubble(String text, boolean mine, LocalDateTime at) {
    Label label = new Label(text);
    label.setWrapText(true);
    label.getStyleClass().addAll("chat-bubble", mine ? "chat-mine" : "chat-theirs");
    // 창 크기가 안 변하니 폭도 고정 — 스크롤 폭에 묶어두면 세로 막대가 들락날락하며 떨린다
    label.setMaxWidth(BUBBLE_WIDTH);

    Label time = new Label(TIME.format(at));
    time.getStyleClass().add("chat-time");

    HBox row = new HBox(4);
    row.setAlignment(mine ? Pos.BOTTOM_LEFT : Pos.BOTTOM_RIGHT);
    if (mine)
      row.getChildren().addAll(label, time);
    else
      row.getChildren().addAll(time, label);
    return row;
  }

  private static HBox dateMark(LocalDate day) {
    Label label = new Label(DATE.format(day));
    label.getStyleClass().add("chat-date");
    HBox row = new HBox(label);
    row.setAlignment(Pos.CENTER);
    row.setPadding(new Insets(6, 0, 2, 0));
    return row;
  }

  private static LocalDateTime when(ChatHistory.Line line) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(line.at), ZoneId.systemDefault());
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
