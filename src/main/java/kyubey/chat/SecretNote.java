package kyubey.chat;

import kyubey.core.SettingsDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import kyubey.ui.WindowDrag;

/**
 * 비밀 쪽지 — 마도카가 먼저 말을 걸 때(뜸할 때, 할 일 재촉·칭찬, \\ 로 마음을 물을 때)나 깜짝 놀래키며 긁을 때 보내는 접힌 말.
 * 나도 \ 로 시작해서 보내면 쪽지가 된다 (ChatPane.send).
 *
 * 말풍선 자리엔 "💌 비밀 쪽지" 만 보이고 내용은 안 보인다. 눌러야 (두 번 또는 한 번 — "설정")
 * 작은 창으로 펼쳐진다. 한 번 열어본 쪽지는 "열어봄" 으로 바뀌지만 계속 다시 열 수 있다.
 * 수다 화면과 지난 대화 창이 같은 모양을 쓴다.
 */
public final class SecretNote {

  /** 쪽지 창 아래 시각 ("10월 12일 오후 3:04") */
  private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN);

  private SecretNote() {
  }

  /**
   * 접힌 쪽지 말풍선. singleClick 이면 한 번, 아니면 두 번 눌러 연다.
   * 마도카 쪽지를 열면 line.opened 를 켜고 onOpened 를 부른다 — 부르는 쪽이 파일에 남기고 다시 그린다 (null 이면 안 남긴다).
   * 내가 보낸 쪽지의 opened 는 마도카가 읽었는지다 — 답이 오면 ChatPane 이 켠다. 내가 열어봐도 안 바뀐다.
   */
  static Label bubble(ChatHistory.Line line, String name, boolean singleClick, Runnable onOpened) {
    String how = singleClick ? "눌러서 열기" : "두 번 눌러 열기";
    String state = line.mine
        ? (line.opened ? "읽음" : "아직 안 읽음")
        : (line.opened ? "열어봄" : how);
    Label label = new Label("💌 비밀 쪽지 · " + state);
    label.getStyleClass().addAll("chat-bubble", line.mine ? "chat-mine" : "chat-theirs", "chat-note");
    if (line.opened)
      label.getStyleClass().add("chat-note-opened");
    label.setCursor(Cursor.HAND);
    label.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != (singleClick ? 1 : 2))
        return;
      e.consume();
      Window owner = label.getScene() == null ? null : label.getScene().getWindow();
      show(owner, line, name);
      if (line.mine)
        return;
      boolean first = !line.opened;
      line.opened = true;
      if (first && onOpened != null)
        onOpened.run();
    });
    return label;
  }

  /** 쪽지를 펼쳐 보이고 닫힐 때까지 기다린다. Esc / Enter 로 닫는다. name 은 쪽지를 보낸 캐릭터 이름 */
  static void show(Window owner, ChatHistory.Line line, String name) {
    Stage dialog = new Stage();
    if (owner != null)
      dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label(line.mine ? "💌 내가 보낸 비밀 쪽지" : "💌 " + name + "의 비밀 쪽지");
    heading.getStyleClass().add("notice-title");

    TextFlow body = BubbleText.of(line.text);
    body.getStyleClass().add("note-body");
    body.setMaxWidth(340);

    Label when = new Label(WHEN.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(line.at), ZoneId.systemDefault())));
    when.getStyleClass().add("chat-time");

    Button close = new Button("닫기");
    close.getStyleClass().add("dialog-button-primary");
    close.setOnAction(e -> dialog.close());
    HBox buttons = new HBox(close);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, heading, body, when, buttons);
    root.getStyleClass().addAll("dialog-root", "notice-root");
    root.setPadding(new Insets(18));
    root.setMinWidth(260);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.ENTER) {
        e.consume();
        dialog.close();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      close.requestFocus();
    });
    dialog.showAndWait();
  }

  /** 띄운 쪽 창 한가운데로 (Notice 와 같은 이치) */
  static void centerOn(Stage popup, Window owner) {
    if (owner == null || owner.getWidth() <= 0 || Double.isNaN(owner.getX())) {
      popup.centerOnScreen();
      return;
    }
    popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
    popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
  }
}
