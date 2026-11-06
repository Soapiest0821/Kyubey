package kyubey.chat;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.PopupWindow;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import kyubey.core.SettingsDialog;
import kyubey.ui.Ime;

import java.io.File;
import java.util.function.Consumer;

/**
 * 멤버를 누르면 그 옆에 뜨는 프로필 카드 — 위 띠에 프로필 사진이 걸치고, 그 밑에 닉네임과 상태 메시지.
 * 딴 데를 누르거나 Esc 를 누르면 닫힌다. 사진을 누르면 수다 사진처럼 크게 뜬다.
 *
 * 상대 카드는 보기만 한다 (사진은 ChatAvatar, 상태 메시지는 ChatStatus 가 알아서 바꾸고, 띠 색은
 * ChatProfile.banner 로 정해져 있다). 내 카드는 아래 "프로필 편집" 을 누르면 그 자리에서 칸으로 바뀌고,
 * 띠 위에 색 고르는 버튼이 뜬다. 저장을 눌러야 남고, 그 전에 닫으면 고치던 건 버린다.
 * 편집 중엔 사진을 누르면 파일을 고른다.
 */
final class ProfileCard {

  private static final double WIDTH = 240;
  private static final double BANNER = 56;
  private static final double AVATAR = 76;
  /** 띠 바깥으로 카드를 띄우는 틈 */
  private static final double GAP = 8;
  /** 사진을 읽어들일 높이 — 카드 지름의 몇 배면 충분하다 */
  private static final double IMAGE_HEIGHT = 256;
  /** 띠 기본색 — style.css 의 .profile-banner 와 같다. 테마가 바꿔 끼운다 */
  private static final String DEFAULT_BANNER = "#ff69a0";

  private final Node anchor;
  private final Runnable onClose;
  private final Stage card = new Stage();
  private final StackPane banner = new StackPane();
  private final StackPane ring = new StackPane();
  private final VBox details = new VBox(6);
  /** 편집 중에만 보이는 안내. 사진 오른쪽 아래, 띠 색 버튼 밑에 놓여 카드 높이를 안 늘린다 */
  private final Label hint = new Label("사진을 눌러서 바꿔");
  /** Enter 를 눌렀을 때 — 보통은 닫기, 편집 중이면 저장 */
  private Runnable onEnter = card::close;
  /** 파일 창을 띄운 동안 — 포커스를 잃어도 안 닫히게 */
  private boolean choosing;

  private ProfileCard(Node anchor, Runnable onClose) {
    this.anchor = anchor;
    this.onClose = onClose;
  }

  /** 상대 카드. zoom 은 avatar 와 같다 */
  static void show(Node anchor, ChatProfile profile, Image image, String name, String status, double zoom,
      Runnable onClose) {
    ProfileCard card = new ProfileCard(anchor, onClose);
    card.ring.getChildren().setAll(avatar(image, name, AVATAR, zoom));
    card.enlargeOnClick(image);
    card.paintBanner(profile.banner);
    card.showDetails(name, status, null);
    card.open();
  }

  /** 내 카드. onClose 전에 고친 걸 설정에 남긴다 */
  static void showMine(Node anchor, Runnable onClose) {
    ProfileCard card = new ProfileCard(anchor, onClose);
    card.viewMine();
    card.open();
  }

  /** 닉네임 · 상태 메시지를 보여준다. extra 가 있으면 맨 아래에 놓는다 */
  private void showDetails(String name, String status, Node extra) {
    Label nameLabel = new Label(name);
    nameLabel.getStyleClass().add("profile-name");
    Label caption = new Label("상태 메시지");
    caption.getStyleClass().add("profile-caption");
    Label statusLabel = new Label(status.isBlank() ? "아직 상태 메시지가 없어" : status);
    statusLabel.getStyleClass().add("profile-status");
    statusLabel.setWrapText(true);
    details.getChildren().setAll(nameLabel, caption, statusLabel);
    if (extra != null)
      details.getChildren().add(extra);
  }

  /** 내 카드 보기 — 상대 카드와 같고 "프로필 편집" 만 더 있다 */
  private void viewMine() {
    SettingsDialog settings = SettingsDialog.load();
    String name = displayName(settings.myName);
    Image image = picture(settings.myImage);
    ring.getChildren().setAll(avatar(image, name, AVATAR, 1));
    enlargeOnClick(image);
    paintBanner(settings.myBanner);
    banner.getChildren().clear();
    hint.setVisible(false);
    details.setMinHeight(Region.USE_COMPUTED_SIZE);

    Button edit = new Button("프로필 편집");
    edit.getStyleClass().add("dialog-button");
    edit.setFocusTraversable(false);
    edit.setMaxWidth(Double.MAX_VALUE);
    edit.setOnAction(e -> editMine(settings));
    VBox.setMargin(edit, new Insets(6, 0, 0, 0));
    showDetails(name, settings.myStatus, edit);
    onEnter = card::close;
    fit();
  }

  /** 사진을 누르면 크게 띄운다. 사진이 없으면 (이름 첫 글자면) 눌러도 아무 일 없다 */
  private void enlargeOnClick(Image image) {
    ring.setCursor(image == null ? Cursor.DEFAULT : Cursor.HAND);
    ring.setOnMouseClicked(image == null ? null : e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      choosing = true;
      ChatImage.popup(card, image);
      choosing = false;
      card.requestFocus();
    });
  }

  /** 띠를 이 색으로 칠한다. 빈 글이면 테마 기본색 */
  private void paintBanner(String hex) {
    banner.setStyle(hex.isBlank() ? "" : "-fx-background-color: " + hex + ";");
  }

  /** 띠 위에 놓는 색 버튼과 작은 x. 고를 때마다 onPick 을 부른다. x 는 빈 글을 넘긴다 (테마 기본색) */
  private Node bannerTools(String start, Consumer<String> onPick) {
    Color basic = Color.web(SettingsDialog.theme().swap(DEFAULT_BANNER));
    ColorPicker picker = new ColorPicker(basic);
    try {
      if (!start.isBlank())
        picker.setValue(Color.web(start));
    } catch (IllegalArgumentException e) {
      System.err.println("[profile] 띠 색을 못 읽었어: " + start);
    }
    picker.setPrefWidth(56);
    picker.setFocusTraversable(false);
    picker.valueProperty().addListener((obs, was, is) -> onPick.accept(SettingsDialog.hex(is)));

    Button reset = new Button("×");
    reset.getStyleClass().add("profile-banner-clear");
    reset.setFocusTraversable(false);
    reset.setOnAction(e -> {
      picker.setValue(basic);
      onPick.accept("");
    });

    HBox tools = new HBox(4, picker, reset);
    tools.setAlignment(Pos.CENTER_RIGHT);
    tools.setPadding(new Insets(0, 10, 0, 0));
    return tools;
  }

  /** 내 카드 고치기. 사진을 누르면 파일을 고른다 */
  private void editMine(SettingsDialog settings) {
    String[] image = { settings.myImage };
    String[] color = { settings.myBanner };
    TextField name = new TextField(settings.myName);
    name.setPromptText("나");
    name.getStyleClass().add("profile-input");
    TextField status = new TextField(settings.myStatus);
    status.setPromptText("상태 메시지를 적어봐");
    status.getStyleClass().add("profile-input");

    ring.setCursor(Cursor.HAND);
    ring.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      FileChooser chooser = new FileChooser();
      chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("그림", ChatImage.FILTER));
      choosing = true;
      File file = chooser.showOpenDialog(card);
      choosing = false;
      card.requestFocus();
      if (file == null)
        return;
      image[0] = file.getAbsolutePath();
      ring.getChildren().setAll(avatar(picture(image[0]), displayName(name.getText()), AVATAR, 1));
    });

    Runnable save = () -> {
      SettingsDialog.saveMyProfile(name.getText().strip(), image[0], status.getText().strip(), color[0]);
      viewMine();
    };
    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setFocusTraversable(false);
    cancel.setOnAction(e -> viewMine());
    Button saveButton = new Button("저장");
    saveButton.getStyleClass().add("dialog-button-primary");
    saveButton.setFocusTraversable(false);
    saveButton.setOnAction(e -> save.run());
    HBox buttons = new HBox(6, cancel, saveButton);
    buttons.setAlignment(Pos.CENTER_RIGHT);
    VBox.setMargin(buttons, new Insets(6, 0, 0, 0));

    banner.getChildren().setAll(bannerTools(color[0], hex -> {
      color[0] = hex;
      paintBanner(hex);
    }));
    // 칸이 보기 때 닉네임 · 상태 메시지 자리에 그대로 앉고, 남는 높이는 버튼 위로 미뤄서 카드 높이를 지킨다
    hint.setVisible(true);
    details.setMinHeight(details.getHeight());
    Region gap = new Region();
    VBox.setVgrow(gap, Priority.ALWAYS);
    details.getChildren().setAll(name, status, gap, buttons);
    onEnter = save;
    fit();
    Ime.focus(name);
  }

  /** 내용이 바뀌면 카드 크기와 자리를 다시 잡는다 */
  private void fit() {
    if (!card.isShowing())
      return;
    card.sizeToScene();
    place();
  }

  /** 비어 있으면 "나" */
  static String displayName(String name) {
    return name == null || name.isBlank() ? "나" : name.strip();
  }

  /** 프로필 사진 파일을 읽는다. 안 골랐거나 못 읽으면 null */
  static Image picture(String path) {
    if (path == null || path.isBlank() || !new File(path).isFile())
      return null;
    Image image = new Image(new File(path).toURI().toString(), 0, IMAGE_HEIGHT, true, true);
    return image.isError() ? null : image;
  }

  /**
   * 동그란 프로필 사진. 그림이 없으면 이름 첫 글자를 넣는다.
   * 사진은 위쪽 가운데를 정사각형으로 자른다 — 사람 그림은 얼굴이 위에 있다.
   * zoom 은 짧은 변 중 쓸 비율이다. 세워둔 캐릭터 그림은 몸까지 나와서 1 보다 작게 준다.
   */
  static StackPane avatar(Image image, String name, double size, double zoom) {
    Circle back = new Circle(size / 2);
    back.getStyleClass().add("profile-avatar-back");
    StackPane box = new StackPane(back);
    box.setMinSize(size, size);
    box.setPrefSize(size, size);
    box.setMaxSize(size, size);

    if (image == null || image.getWidth() <= 0) {
      Label letter = new Label(name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)));
      letter.getStyleClass().add("profile-avatar-letter");
      letter.setStyle("-fx-font-size: " + Math.round(size * 0.45) + "px;");
      box.getChildren().add(letter);
      return box;
    }

    double side = Math.min(image.getWidth(), image.getHeight()) * zoom;
    ImageView view = new ImageView(image);
    view.setViewport(new Rectangle2D((image.getWidth() - side) / 2, 0, side, side));
    view.setFitWidth(size);
    view.setFitHeight(size);
    view.setSmooth(true);
    view.setClip(new Circle(size / 2, size / 2, size / 2));
    box.getChildren().add(view);
    return box;
  }

  private void open() {
    card.initOwner(anchor.getScene().getWindow());
    card.initStyle(StageStyle.TRANSPARENT);

    ring.getStyleClass().add("profile-ring");
    ring.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    banner.getStyleClass().add("profile-banner");
    banner.setMinHeight(BANNER);
    details.setPadding(new Insets(0, 16, 16, 16));

    hint.getStyleClass().add("profile-caption");
    hint.setMouseTransparent(true);
    hint.setVisible(false);
    StackPane.setAlignment(ring, Pos.TOP_LEFT);
    StackPane.setAlignment(hint, Pos.BOTTOM_LEFT);
    StackPane.setMargin(hint, new Insets(0, 0, 6, AVATAR + 6 + 2));
    StackPane head = new StackPane(ring, hint);
    // 띠에 걸쳐 있어서, 빈 곳이 띠 위 색 버튼을 가리지 않게
    head.setPickOnBounds(false);

    VBox root = new VBox(8, banner, head, details);
    root.getStyleClass().addAll("dialog-root", "profile-card");
    root.setPrefWidth(WIDTH);
    root.setAlignment(Pos.TOP_LEFT);
    // 사진 반이 띠에 걸친다
    VBox.setMargin(head, new Insets(-AVATAR / 2 - 8 - 3, 0, 0, 14));

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        card.close();
      } else if (e.getCode() == KeyCode.ENTER) {
        e.consume();
        onEnter.run();
      }
    });
    card.setScene(scene);
    card.focusedProperty().addListener((obs, was, is) -> {
      if (is || choosing)
        return;
      Window child = ownedStage();
      if (child == null)
        card.close();
      else
        child.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> card.requestFocus());
    });
    card.setOnHidden(e -> onClose.run());
    card.setOnShown(e -> {
      place();
      root.requestFocus();
    });
    card.show();
  }

  /**
   * 이 카드에서 띄운 창. 없으면 null — 색 고르기의 "사용자 정의 색상" 창이 포커스를 가져가도 안 닫히게.
   * 그 창의 주인은 카드가 아니라 색 팝업이라 주인을 따라 올라간다. 팝업만 뜬 건 포커스를 안 가져가니 안 센다.
   */
  private Window ownedStage() {
    return Window.getWindows().stream().filter(w -> w instanceof Stage && w != card && ownerChainHasCard(w))
        .findFirst().orElse(null);
  }

  private boolean ownerChainHasCard(Window window) {
    for (Window at = window; at != null; at = at instanceof Stage stage ? stage.getOwner()
        : at instanceof PopupWindow popup ? popup.getOwnerWindow() : null)
      if (at == card)
        return true;
    return false;
  }

  /** 누른 줄 왼쪽에 세우고, 화면 밖으로 안 나가게 민다 */
  private void place() {
    Bounds at = anchor.localToScreen(anchor.getBoundsInLocal());
    if (at == null)
      return;
    Rectangle2D screen = Screen.getScreensForRectangle(at.getMinX(), at.getMinY(), 1, 1).stream().findFirst()
        .orElse(Screen.getPrimary()).getVisualBounds();
    card.setX(Math.max(screen.getMinX(), at.getMinX() - card.getWidth() - GAP));
    card.setY(Math.max(screen.getMinY(), Math.min(screen.getMaxY() - card.getHeight(), at.getMinY())));
  }
}
