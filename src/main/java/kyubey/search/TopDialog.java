package kyubey.search;

import kyubey.core.SettingsDialog;
import kyubey.ui.Emoji;
import kyubey.ui.WindowDrag;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
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

import java.util.List;

/**
 * /top 으로 여는 많이 쓴 명령어 랭킹 창. 1~3 등은 메달(컬러 그림, Emoji), 그 밑은 숫자로 순위를 단다.
 * 쓴 횟수가 같으면 공동 순위 — 1, 1, 3 처럼 다음 순위는 그만큼 건너뛴다. 공동 3등까진 다 메달.
 * 줄을 두 번 누르거나 Enter 면 그 명령어를 돌려준다 — 입력창에 올려두는 건 부르는 쪽 몫.
 * 닫는 건 Esc / 버튼 (Notice 와 같은 옷). 위젯이 전체화면이면 창도 글씨도 크게 띄운다.
 */
public final class TopDialog {

  private static final double WIDTH = 420;
  private static final double HEIGHT = 460;
  private static final double FULL_WIDTH = 600;
  private static final double FULL_HEIGHT = 680;
  private static final double MEDAL_SIZE = 20;
  private static final double FULL_MEDAL_SIZE = 30;
  private static final String[] MEDALS = { "🥇", "🥈", "🥉" };

  private TopDialog() {
  }

  /** 고른 명령어, 그냥 닫았으면 null */
  public static String show(Window owner, List<HistoryManager.Entry> entries, boolean full) {
    Stage popup = new Stage();
    if (owner != null)
      popup.initOwner(owner);
    popup.initModality(Modality.APPLICATION_MODAL);
    popup.initStyle(StageStyle.TRANSPARENT);
    String[] picked = new String[1];
    int[] ranks = ranks(entries);
    double medalSize = full ? FULL_MEDAL_SIZE : MEDAL_SIZE;

    Label title = new Label("🔥 많이 쓴 명령어");
    title.getStyleClass().add("notice-title");

    ListView<HistoryManager.Entry> list = new ListView<>();
    list.getItems().setAll(entries);
    VBox.setVgrow(list, Priority.ALWAYS);
    list.setCellFactory(lv -> {
      ListCell<HistoryManager.Entry> cell = new ListCell<>() {
        @Override
        protected void updateItem(HistoryManager.Entry item, boolean empty) {
          super.updateItem(item, empty);
          setText(null);
          if (empty || item == null) {
            setGraphic(null);
            return;
          }
          int rank = ranks[getIndex()];
          Label rankLabel = new Label();
          ImageView medal = rank <= MEDALS.length ? Emoji.view(MEDALS[rank - 1], medalSize) : null;
          if (medal != null)
            rankLabel.setGraphic(medal);
          else
            rankLabel.setText(rank <= MEDALS.length ? MEDALS[rank - 1] : rank + ".");
          rankLabel.setMinWidth(medalSize + 16);
          Label cmd = new Label(item.cmd);
          Region gap = new Region();
          HBox.setHgrow(gap, Priority.ALWAYS);
          Label count = new Label(item.count + "번");
          HBox row = new HBox(8, rankLabel, cmd, gap, count);
          row.setAlignment(Pos.CENTER_LEFT);
          setGraphic(row);
        }
      };
      cell.setOnMouseClicked(e -> {
        if (cell.isEmpty() || e.getButton() != MouseButton.PRIMARY || e.getClickCount() < 2)
          return;
        picked[0] = cell.getItem().cmd;
        popup.close();
      });
      return cell;
    });
    list.setOnKeyPressed(e -> {
      HistoryManager.Entry entry = list.getSelectionModel().getSelectedItem();
      if (e.getCode() == KeyCode.ENTER && entry != null) {
        picked[0] = entry.cmd;
        popup.close();
      }
    });

    Button okBtn = new Button("닫기");
    okBtn.getStyleClass().add("dialog-button-primary");
    okBtn.setOnAction(e -> popup.close());

    HBox buttonBox = new HBox(okBtn);
    buttonBox.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(14, title, list, buttonBox);
    root.getStyleClass().addAll("dialog-root", "notice-root");
    root.setPadding(new Insets(22));
    root.setPrefSize(full ? FULL_WIDTH : WIDTH, full ? FULL_HEIGHT : HEIGHT);
    if (full)
      root.setStyle("-fx-font-size: 17px;");

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE)
        popup.close();
    });

    popup.setScene(scene);
    WindowDrag.makeDraggable(popup, root);
    popup.setOnShown(e -> {
      if (owner != null && owner.getWidth() > 0 && !Double.isNaN(owner.getX())) {
        popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
        popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
      } else {
        popup.centerOnScreen();
      }
      list.requestFocus();
      list.getSelectionModel().select(0);
    });
    popup.showAndWait();
    return picked[0];
  }

  /** 줄마다 순위 (1부터). 쓴 횟수가 앞 줄과 같으면 앞 줄 순위를 그대로 — entries 는 많이 쓴 순이라 이웃만 보면 된다 */
  private static int[] ranks(List<HistoryManager.Entry> entries) {
    int[] ranks = new int[entries.size()];
    for (int i = 0; i < ranks.length; i++)
      ranks[i] = i > 0 && entries.get(i).count == entries.get(i - 1).count ? ranks[i - 1] : i + 1;
    return ranks;
  }
}
