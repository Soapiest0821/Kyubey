package kyubey.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import kyubey.core.SettingsDialog;
import kyubey.ui.WindowDrag;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 수다 화면 오른쪽, 캐릭터 그림 위 빈칸의 탭 — 멤버 · 톡서랍 · 지난 대화.
 *
 * 수다 화면에 들어올 때마다 멤버 탭으로 선다 (ChatPane.enter 가 되돌린다).
 * 고른 탭을 또 누르면 접힌다. 내용은 칸 높이에 맞춰 잘리고 그 안에서 스크롤한다 — 자리는 ChatPane.layoutChildren 이 잡는다.
 * 말풍선처럼 포커스를 안 받아서, 탭을 눌러도 입력칸에서 계속 칠 수 있다.
 */
final class ChatSide extends VBox {

  private enum Tab {
    MEMBERS("멤버"), DRAWER("톡서랍"), PAST("지난 대화");

    final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  /** 멤버 줄의 프로필 사진 지름 */
  private static final double AVATAR = 30;
  /** 톡서랍 사진 한 변 */
  private static final double THUMB = 62;
  /** 톡서랍 사진 모서리 둥글기 */
  private static final double THUMB_CORNER = 12;
  /** 프사를 못 받았을 때 세워둔 그림에서 쓰는 비율 — 얼굴이 차게 (ProfileCard.avatar) */
  private static final double MASCOT_ZOOM = 0.82;

  /** 말 속 링크 — 한글이 바로 붙어도 거기서 끊기게 아스키 글자까지만 */
  private static final Pattern LINK = Pattern.compile("https?://[\\x21-\\x7E&&[^<>\"')\\]]]+");

  private final ChatPane chat;
  private final Map<Tab, ToggleButton> tabs = new EnumMap<>(Tab.class);
  private final VBox content = new VBox(4);
  private final ScrollPane body = new ScrollPane(content);
  /** 지금 고른 탭. 접혀 있으면 없다 */
  private Tab shown;

  ChatSide(ChatPane chat) {
    this.chat = chat;
    setSpacing(6);
    // 빈 곳은 뒤로 흘려서 창 끌기가 그대로 되게
    setPickOnBounds(false);
    getProperties().put(WindowDrag.NO_DRAG, true);

    HBox bar = new HBox(4);
    bar.setAlignment(Pos.CENTER);
    bar.getStyleClass().add("graph-mode");
    for (Tab tab : Tab.values()) {
      ToggleButton button = new ToggleButton(tab.label);
      // 목표 쪼개기의 모드 단추와 같은 동글동글한 모양
      button.getStyleClass().add("graph-mode-button");
      button.setFocusTraversable(false);
      button.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(button, Priority.ALWAYS);
      button.setOnAction(e -> show(tab == shown ? null : tab));
      tabs.put(tab, button);
      bar.getChildren().add(button);
    }

    content.setPadding(new Insets(6));
    content.setFillWidth(true);
    body.getStyleClass().addAll("chat-scroll", "chat-side-body");
    body.setFitToWidth(true);
    body.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    body.setFocusTraversable(false);
    body.setPrefHeight(0);
    VBox.setVgrow(body, Priority.ALWAYS);
    body.managedProperty().bind(body.visibleProperty());
    body.setVisible(false);

    getChildren().addAll(bar, body);
  }

  /** 탭을 고른다. null 이면 접는다 */
  private void show(Tab tab) {
    shown = tab;
    tabs.forEach((t, button) -> button.setSelected(t == tab));
    body.setVisible(tab != null);
    refresh();
  }

  /** 멤버 탭이 열린 처음 모습으로 */
  void reset() {
    show(Tab.MEMBERS);
  }

  /** 멤버 탭이 떠 있으면 다시 그린다 — 상태 메시지나 프사가 새로 왔을 때 */
  void refreshMembers() {
    if (shown == Tab.MEMBERS)
      refresh();
  }

  /** 톡서랍이 떠 있으면 다시 그린다 — 사진이나 링크가 붙거나 빠졌을 때 */
  void refreshDrawer() {
    if (shown == Tab.DRAWER)
      refresh();
  }

  /** 고른 탭을 새로 그린다 */
  void refresh() {
    content.getChildren().clear();
    if (shown == null)
      return;
    switch (shown) {
      case MEMBERS -> drawMembers();
      case DRAWER -> drawDrawer();
      case PAST -> drawPast();
    }
    body.setVvalue(0);
  }

  // ── 멤버 ───────────────────────────────────────────────────────────────────

  private void drawMembers() {
    SettingsDialog settings = SettingsDialog.load();
    ChatProfile profile = chat.profile();
    // 받아둔 프사가 없으면 (아직 못 받았으면) 세워둔 그림에서 얼굴 쪽을 잘라 쓴다
    Image avatar = ChatAvatar.image(profile);
    Image image = avatar != null ? avatar : SettingsDialog.mascotImage(profile, settings.mascotImages.get(profile.id));
    double zoom = avatar != null ? 1 : MASCOT_ZOOM;
    String name = chat.name() + (profile == settings.mainPartner() ? " ♥" : "");
    String status = chat.statusText();
    content.getChildren().add(memberRow(image, name, status, zoom,
        row -> ProfileCard.show(row, profile, image, name, status, zoom, chat::focusInput)));

    String myName = ProfileCard.displayName(settings.myName);
    content.getChildren().add(memberRow(ProfileCard.picture(settings.myImage), myName, settings.myStatus, 1,
        row -> ProfileCard.showMine(row, () -> {
          refreshMembers();
          chat.focusInput();
        })));
  }

  private interface RowAction {
    void run(Node row);
  }

  /** 디스코드 멤버 목록처럼 — 프로필 사진, 그 옆에 닉네임과 상태 메시지 */
  private HBox memberRow(Image image, String name, String status, double zoom, RowAction onClick) {
    Label nameLabel = new Label(name);
    nameLabel.getStyleClass().add("partner-name");
    VBox text = new VBox(nameLabel);
    text.setAlignment(Pos.CENTER_LEFT);
    if (!status.isBlank()) {
      Label statusLabel = new Label(status);
      statusLabel.getStyleClass().add("chat-side-sub");
      text.getChildren().add(statusLabel);
    }
    // 긴 글이 칸을 늘리지 않고 말줄임으로 잘리게
    text.setMinWidth(0);
    text.setPrefWidth(0);
    HBox.setHgrow(text, Priority.ALWAYS);

    HBox row = new HBox(8, ProfileCard.avatar(image, name, AVATAR, zoom), text);
    row.setAlignment(Pos.CENTER_LEFT);
    row.getStyleClass().add("chat-side-row");
    row.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        onClick.run(row);
    });
    return row;
  }

  // ── 톡서랍 ─────────────────────────────────────────────────────────────────

  /** 이 대화에 오간 사진과 링크, 최근 것부터. 지운 말은 빼고, 비밀 쪽지 속 링크도 안 꺼낸다 */
  private void drawDrawer() {
    List<File> pictures = new ArrayList<>();
    Set<String> links = new LinkedHashSet<>();
    List<ChatHistory.Line> lines = chat.lines();
    for (int i = lines.size() - 1; i >= 0; i--) {
      ChatHistory.Line line = lines.get(i);
      if (line.hidden)
        continue;
      if (line.image != null) {
        File file = new File(line.image);
        if (file.exists())
          pictures.add(file);
      } else if (!line.note) {
        links.addAll(linksIn(line.text));
      }
    }

    if (pictures.isEmpty() && links.isEmpty()) {
      content.getChildren().add(empty("아직 사진도 링크도 없어"));
      return;
    }
    if (!pictures.isEmpty()) {
      content.getChildren().add(heading("사진 " + pictures.size()));
      TilePane tiles = new TilePane(4, 4);
      tiles.setPrefTileWidth(THUMB);
      tiles.setPrefTileHeight(THUMB);
      pictures.forEach(file -> tiles.getChildren().add(thumb(file)));
      content.getChildren().add(tiles);
    }
    if (!links.isEmpty()) {
      content.getChildren().add(heading("링크 " + links.size()));
      links.forEach(url -> content.getChildren().add(linkRow(url)));
    }
  }

  /** 글 속 링크들. 문장 끝 문장부호는 링크에서 뗀다 */
  static List<String> linksIn(String text) {
    List<String> found = new ArrayList<>();
    if (text == null)
      return found;
    Matcher matcher = LINK.matcher(text);
    while (matcher.find())
      found.add(matcher.group().replaceAll("[.,!?;:]+$", ""));
    return found;
  }

  /** 가운데를 정사각형으로 자른 사진. 누르면 수다 화면 말풍선을 누른 것처럼 크게 뜬다 */
  private Node thumb(File file) {
    // 사진이 많아도 탭이 멈칫하지 않게 뒤에서 읽는다
    Image image = new Image(file.toURI().toString(), THUMB * 2, THUMB * 2, true, true, true);
    ImageView view = new ImageView(image);
    view.setFitWidth(THUMB);
    view.setFitHeight(THUMB);
    view.setSmooth(true);
    Rectangle corners = new Rectangle(THUMB, THUMB);
    corners.setArcWidth(THUMB_CORNER);
    corners.setArcHeight(THUMB_CORNER);
    view.setClip(corners);
    Runnable crop = () -> {
      double side = Math.min(image.getWidth(), image.getHeight());
      if (side > 0)
        view.setViewport(new Rectangle2D((image.getWidth() - side) / 2, (image.getHeight() - side) / 2, side, side));
    };
    if (image.getProgress() >= 1)
      crop.run();
    else
      image.progressProperty().addListener((obs, was, is) -> {
        if (is.doubleValue() >= 1)
          crop.run();
      });

    StackPane box = new StackPane(view);
    box.setCursor(Cursor.HAND);
    box.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      ChatImage.popup(getScene().getWindow(), file);
      chat.focusInput();
    });
    return box;
  }

  private Node linkRow(String url) {
    Label label = new Label(url.replaceFirst("^https?://", ""));
    label.getStyleClass().add("chat-side-link");
    label.setTextOverrun(OverrunStyle.ELLIPSIS);
    label.setTooltip(new Tooltip(url));
    label.setMaxWidth(Double.MAX_VALUE);
    label.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        browse(url);
    });
    return label;
  }

  private static void browse(String url) {
    Thread thread = new Thread(() -> {
      try {
        if (Desktop.isDesktopSupported())
          Desktop.getDesktop().browse(new URI(url));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }, "chat-link-open");
    thread.setDaemon(true);
    thread.start();
  }

  // ── 지난 대화 ──────────────────────────────────────────────────────────────

  /** 채팅 삭제로 보관한 대화들, 최근 것부터. 누르면 그 대화가 펼쳐진 지난 대화 창이 뜬다 */
  private void drawPast() {
    List<ChatArchive.Entry> entries = chat.archive().list();
    if (entries.isEmpty()) {
      content.getChildren().add(empty("아직 지운 대화가 없어"));
      return;
    }
    for (ChatArchive.Entry entry : entries) {
      Label label = new Label(ChatArchiveDialog.summary(entry));
      label.getStyleClass().add("partner-name");
      HBox row = new HBox(label);
      row.setAlignment(Pos.CENTER_LEFT);
      row.getStyleClass().add("chat-side-row");
      row.setOnMouseClicked(e -> {
        if (e.getButton() == MouseButton.PRIMARY)
          chat.openArchive(entries, entry);
      });
      content.getChildren().add(row);
    }
  }

  private static Label heading(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("chat-side-sub");
    return label;
  }

  private static Label empty(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("chat-side-sub");
    label.setWrapText(true);
    return label;
  }
}
