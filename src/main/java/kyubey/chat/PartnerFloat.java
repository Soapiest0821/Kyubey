package kyubey.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import kyubey.core.SettingsDialog;
import kyubey.ui.FloatingPanel;

import java.util.function.Consumer;

/**
 * 수다를 전체화면으로 펴면 오른쪽 위에 뜨는 둥근 판 — 수다 상대 목록과 상대마다 안 읽은 말이 있는지 · 몇 개인지.
 *  - 이름을 누르면 그 상대로 갈아탄다 (onPick — Main 이 수다 화면을 갈아끼운다). 지금 상대는 칠해 두고, 파트너는 ♥
 *  - 안 읽은 말이 있으면 오른쪽에 "💬 3" 배지. 지금 상대는 화면이 들고 있는 수 (ChatPane.unread),
 *    딴 상대는 파일에서 센 수 (ChatPane.unreadOf)
 * 손잡이로 끌어 옮긴다 (FloatingPanel).
 */
public final class PartnerFloat extends FloatingPanel {

  private static final double WIDTH = 190;

  private final VBox rows = new VBox(2);
  private final Consumer<ChatProfile> onPick;

  public PartnerFloat(Consumer<ChatProfile> onPick) {
    super(WIDTH, new Insets(16, 16, 16, 0), true);
    this.onPick = onPick;
    Label title = new Label("수다 상대");
    title.getStyleClass().add("float-title");
    setContent(new VBox(4, title, rows));
  }

  /** 지금 상대 (current — 그 화면) · 파트너 · 안 읽은 말을 새로 그린다 */
  public void refresh(ChatPane current) {
    ChatProfile main = SettingsDialog.load().mainPartner();
    rows.getChildren().clear();
    for (ChatProfile profile : ChatProfile.values()) {
      boolean now = profile == current.profile();
      // 지금 상대 이름은 화면이 들고 있는 걸로 — 방금 고쳤을 수 있다
      String name = now ? current.name() : profile.displayName();
      Label label = new Label(name + (profile == main ? " ♥" : ""));
      label.getStyleClass().add("partner-name");
      label.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(label, Priority.ALWAYS);

      HBox row = new HBox(4, label);
      int unread = now ? current.unread() : ChatPane.unreadOf(profile);
      if (unread > 0) {
        Label badge = new Label("💬 " + unread);
        badge.getStyleClass().add("partner-unread");
        row.getChildren().add(badge);
      }
      row.setAlignment(Pos.CENTER_LEFT);
      row.getStyleClass().add("partner-row");
      if (now)
        row.getStyleClass().add("partner-row-current");
      row.setOnMouseClicked(e -> {
        if (e.getButton() == MouseButton.PRIMARY && !now)
          onPick.accept(profile);
      });
      rows.getChildren().add(row);
    }
  }
}
