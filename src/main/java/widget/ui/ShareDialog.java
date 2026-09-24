package widget.ui;

import widget.share.Downloads;
import widget.share.LanShare;
import widget.share.Qr;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * "파일 송신" / "파일 수신" 으로 뜨는 창 — 같은 와이파이에 있는 폰이랑 파일을 주고받는다.
 *
 * 창에 뜨는 QR 을 폰 카메라로 찍으면 브라우저가 열린다. 앱을 깔 것도, 케이블을 꽂을 것도,
 * 클라우드를 거칠 것도 없다. 실제로 주고받는 일은 widget.share.LanShare 가 맡고
 * 여긴 QR 을 띄우고 진행 상황을 보여주는 데까지다.
 *
 * 창을 닫으면 서버도 같이 내려간다 — 열어둔 문을 잊고 켜둘 일이 없게.
 */
public final class ShareDialog {

  /** QR 그림 한 변 (px). 폰 카메라가 한 걸음 떨어져서도 잡는 크기 */
  private static final int QR_SIZE = 240;

  /** 오간 파일 기록을 몇 줄까지 남겨둘지. 넘치면 오래된 줄부터 지운다 */
  private static final int LOG_LIMIT = 6;

  private ShareDialog() {
  }

  // ── 보내기 ──

  /** 컴퓨터에 있는 파일을 골라 폰이 받아가게 내건다 */
  public static void send(Stage owner) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("폰으로 보낼 파일 고르기");
    chooser.setInitialDirectory(new File(System.getProperty("user.home")));
    List<File> picked = chooser.showOpenMultipleDialog(owner);
    if (picked == null || picked.isEmpty())
      return;

    Log log = new Log();
    LanShare share;
    try {
      share = LanShare.send(picked, log.listener());
    } catch (IOException e) {
      Notice.warn(owner, "파일을 내걸지 못했어 ㅠㅠ\n\n" + e.getMessage());
      return;
    }

    String what = picked.size() == 1
        ? picked.get(0).getName()
        : picked.size() + "개 파일";
    open(owner, share, log, "📤 파일 송신", what + " — 폰으로 QR 을 찍으면 받아져", null);
  }

  // ── 받기 ──

  /** 폰에 올리기 화면을 띄워주고, 올라온 파일을 다운로드 폴더에 떨군다 */
  public static void receive(Stage owner) {
    Path folder;
    try {
      folder = Downloads.dir();
    } catch (IOException e) {
      Notice.warn(owner, "다운로드 폴더를 못 찾았어 ㅠㅠ\n\n" + e.getMessage());
      return;
    }

    Log log = new Log();
    LanShare share;
    try {
      share = LanShare.receive(folder, log.listener());
    } catch (IOException e) {
      Notice.warn(owner, "받을 준비를 못 했어 ㅠㅠ\n\n" + e.getMessage());
      return;
    }

    open(owner, share, log, "📥 파일 수신", "폰에서 QR 을 찍고 파일을 고르면 여기로 온다", folder);
  }

  // ── 창 ──

  /**
   * QR 창을 띄우고 닫힐 때까지 기다린다. folder 가 있으면(받기) 폴더 여는 단추도 같이 단다.
   */
  private static void open(Stage owner, LanShare share, Log log,
      String title, String subtitle, Path folder) {
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label(title);
    heading.getStyleClass().add("notice-title");

    Label sub = new Label(subtitle);
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);
    sub.setMaxWidth(300);

    VBox box = new VBox(12, heading, sub, qr(share.url()), address(share.url()));
    if (folder != null)
      box.getChildren().add(note("📂 " + folder));
    box.getChildren().addAll(log.view(), buttons(dialog, folder));
    box.getStyleClass().addAll("dialog-root", "notice-root");
    box.setPadding(new Insets(22));
    box.setAlignment(Pos.CENTER);
    box.setMaxWidth(340);

    Scene scene = new Scene(box);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(ShareDialog.class.getResource("/style/style.css").toExternalForm());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE)
        dialog.close();
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, box);
    // 파일이 하나 오갈 때마다 아래로 한 줄씩 늘어난다 — 창도 같이 키워줘야 글자가 잘리지 않는다.
    // (진행률 글자는 자주 바뀌지만 자리를 미리 잡아둬서 창 높이는 안 건드린다 — Log 참고)
    log.lines.getChildren().addListener((ListChangeListener<Node>) change -> dialog.sizeToScene());
    // 창을 닫는 게 곧 서버를 내리는 것. 단추로 닫든 Esc 로 닫든 여기로 모인다.
    dialog.setOnHidden(e -> share.close());
    dialog.setOnShown(e -> centerOn(dialog, owner));
    dialog.showAndWait();
  }

  /** QR 그림. 못 그렸으면 아래 주소만 보고 손으로 치면 되니 자리만 비운다 */
  private static Region qr(String url) {
    Image image = Qr.of(url, QR_SIZE);
    if (image == null)
      return note("QR 을 못 그렸어 — 아래 주소를 폰 브라우저에 쳐줘");

    ImageView view = new ImageView(image);
    view.setFitWidth(QR_SIZE);
    view.setFitHeight(QR_SIZE);
    // QR 은 칸이 딱 떨어져야 잘 잡힌다. 부드럽게 늘리면 경계가 번져서 흐려진다.
    view.setSmooth(false);
    view.setPreserveRatio(true);

    VBox frame = new VBox(view);
    frame.getStyleClass().add("share-qr");
    frame.setAlignment(Pos.CENTER);
    frame.setPadding(new Insets(10));
    return frame;
  }

  /** QR 이 안 잡힐 때 손으로 칠 주소 */
  private static Label address(String url) {
    Label label = new Label(url);
    label.getStyleClass().add("share-url");
    return label;
  }

  private static Label note(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("dialog-path-label");
    label.setWrapText(true);
    label.setMaxWidth(300);
    return label;
  }

  private static HBox buttons(Stage dialog, Path folder) {
    Button close = new Button("닫기");
    close.getStyleClass().add("dialog-button-primary");
    close.setDefaultButton(true);
    close.setCancelButton(true);
    close.setOnAction(e -> dialog.close());

    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_RIGHT);
    if (folder != null) {
      Button openFolder = new Button("폴더 열기");
      openFolder.getStyleClass().add("dialog-button");
      openFolder.setOnAction(e -> explore(folder));
      row.getChildren().add(openFolder);
    }
    row.getChildren().add(close);
    return row;
  }

  private static void explore(Path folder) {
    try {
      Runtime.getRuntime().exec(new String[] { "explorer.exe", folder.toString() });
    } catch (IOException e) {
      e.printStackTrace();
    }
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

  // ── 진행 상황 ──

  /**
   * 창 아래쪽에 쌓이는 소식들. LanShare 는 자기 HTTP 스레드에서 알려주기 때문에
   * 화면에 손대기 전에 전부 Platform.runLater 로 FX 스레드에 넘긴다.
   */
  private static final class Log {
    private final Label now = new Label("폰이 찍기를 기다리는 중…");
    private final VBox lines = new VBox(3);

    Log() {
      now.getStyleClass().add("share-status");
      now.setWrapText(true);
      now.setMaxWidth(300);
      // 진행률은 150ms 마다 갈리는데 그때마다 창이 들썩이면 눈이 아프다.
      // 두 줄 자리를 미리 비워두고, 이름이 길면 줄여서(shorten) 거기 안에 들어가게 한다.
      now.setMinHeight(34);
      lines.setAlignment(Pos.CENTER_LEFT);
    }

    VBox view() {
      VBox box = new VBox(6, now, lines);
      box.setAlignment(Pos.CENTER_LEFT);
      box.setMinWidth(300);
      return box;
    }

    LanShare.Listener listener() {
      return new LanShare.Listener() {
        @Override
        public void onProgress(String name, long done, long total) {
          String head = shorten(name);
          Platform.runLater(() -> now.setText(total > 0
              ? head + "  " + LanShare.human(done) + " / " + LanShare.human(total)
                  + "  (" + (done * 100 / total) + "%)"
              : head + "  " + LanShare.human(done)));
        }

        @Override
        public void onDone(String name, Path saved) {
          Platform.runLater(() -> {
            now.setText("다 됐어! 더 주고받으려면 QR 을 또 찍어");
            add("✅ " + saved.getFileName(), false);
          });
        }

        @Override
        public void onFailed(String name, String message) {
          Platform.runLater(() -> {
            now.setText("폰이 찍기를 기다리는 중…");
            add("⚠ " + name + " — " + message, true);
          });
        }
      };
    }

    /** 긴 이름은 앞부분만. 진행률 줄이 두 줄을 넘겨 창을 밀어 올리지 않게 */
    private static String shorten(String name) {
      return name.length() <= 24 ? name : name.substring(0, 23) + "…";
    }

    private void add(String text, boolean failed) {
      Label line = new Label(text);
      line.getStyleClass().add(failed ? "share-line-fail" : "share-line");
      line.setWrapText(true);
      line.setMaxWidth(300);
      lines.getChildren().add(line);
      // 오래 열어두고 여러 개 주고받으면 창이 한없이 길어져서, 최근 것만 남긴다
      while (lines.getChildren().size() > LOG_LIMIT)
        lines.getChildren().remove(0);
    }
  }
}
