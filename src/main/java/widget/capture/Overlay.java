package widget.capture;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.ImageCursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.Consumer;

/**
 * 화면을 찍어놓고 그 위에 덮는 자르기 판.
 *
 * 진짜 화면 위에 투명한 창을 씌우는 대신, 누른 순간의 화면을 한 장 찍어서 그대로 깔고
 * 그 위에 어둡게 덮는다. 그래야 고르는 동안 뒤에서 뭐가 움직여도(동영상, 알림 같은 것)
 * 내가 본 그 화면이 그대로 잘린다.
 *
 * 화면에 보이는 좌표(논리 좌표)와 찍힌 그림의 픽셀 수는 윈도우 배율(125% 같은 것) 때문에
 * 서로 다르다. 그래서 그림은 화면 크기에 맞춰 늘려 깔고, 자를 땐 그 비율만큼 되돌려서
 * 원본 픽셀 그대로 잘라낸다 — 보이는 대로 잘리면서 화질은 안 깎이게.
 *
 * (모니터마다 배율이 다르면 두 번째 모니터 쪽이 조금 밀릴 수 있다. 창 하나가 여러
 * 모니터에 걸쳐 있으면 배율은 하나만 쓸 수 있어서 어쩔 수 없는 부분.)
 */
final class Overlay {

  /** 이보다 작게 끌면 자른 게 아니라 그냥 클릭한 걸로 본다 */
  private static final double MIN_DRAG = 6;

  /** 안내판을 마우스에서 이만큼 떼어 오른쪽 아래에 세운다 (커서에 안 겹치게) */
  private static final double HINT_GAP = 20;

  /** 고른 자리를 두르는 핑크 테두리 */
  private static final Color CROSS = Color.web("#ff69a0");

  /** 십자 커서 색 — 연한 핑크 */
  private static final Color POINTER = Color.web("#ffb6c1");

  /** 십자 커서 선 굵기 (픽셀) — 한 줄은 너무 가늘어서 두 줄로 긋는다 */
  private static final int POINTER_WIDTH = 2;

  /** 무엇을 가져갈지 — X 로 토글 */
  enum Grab {
    /** 기본값. 자른 그림 그대로 */
    IMAGE("📷 이미지"),
    /** 그림 속 글자만 읽어서 (Gemini OCR) */
    TEXT("🔤 텍스트");

    final String label;

    Grab(String label) {
      this.label = label;
    }
  }

  /** 가져간 다음에 뭘 할지 — C 로 토글. 어느 쪽이든 복사는 한다 */
  enum Then {
    /** 복사하고 검색까지 */
    SEARCH("🔍 복사 + 검색"),
    /** 기본값. 복사만 하고 조용히 — 대개는 어디 붙여넣으려고 자르는 거라서 */
    COPY("📋 복사만");

    final String label;

    Then(String label) {
      this.label = label;
    }
  }

  /** 다 고르고 나온 결과 — 잘린 그림과 그때 켜져 있던 토글 */
  record Shot(Image image, Grab grab, Then then) {
  }

  private Overlay() {
  }

  /**
   * 자르기 판을 띄운다. JavaFX 스레드에서 부를 것.
   * 다 고르면 onDone, Esc 나 오른쪽 클릭으로 그만두면 onCancel 이 불린다 (둘 중 하나만).
   *
   * @throws Exception 화면을 못 찍었을 때 (부르는 쪽이 사람한테 알린다)
   */
  static void open(Consumer<Shot> onDone, Runnable onCancel) throws Exception {
    Rectangle2D area = virtualBounds();
    Robot robot = new Robot();
    WritableImage shot = robot.getScreenCapture(null,
        area.getMinX(), area.getMinY(), area.getWidth(), area.getHeight(), false);
    if (shot == null || shot.getWidth() <= 0)
      throw new IllegalStateException("화면을 못 찍었어");

    // 판이 뜨기 전엔 마우스 이벤트가 안 오니까, 지금 어디 있는지는 로봇한테 물어본다
    new Overlay().show(area, shot, robot.getMouseX(), robot.getMouseY(), onDone, onCancel);
  }

  /** 지금 켜져 있는 토글. 판을 새로 열 때마다 기본값에서 시작한다 */
  private Grab grab = Grab.IMAGE;
  private Then then = Then.COPY;

  private final Rectangle selection = new Rectangle();
  private final Label size = new Label();
  /** 안내판에 나란히 적어두고, 지금 켜진 쪽만 밝게 칠한다 */
  private final Label grabImage = option(Grab.IMAGE.label);
  private final Label grabText = option(Grab.TEXT.label);
  private final Label thenSearch = option(Then.SEARCH.label);
  private final Label thenCopy = option(Then.COPY.label);

  /** 안내판. 마우스를 따라다녀야 해서 들고 있는다 */
  private VBox hintBox;
  /** 판 안에서의 마우스 자리 — 안내판은 늘 여기 오른쪽 아래에 선다 */
  private double pointerX;
  private double pointerY;

  private double anchorX;
  private double anchorY;
  private boolean dragging;
  /** 끝났다고 두 번 알리지 않게 — 닫는 길이 여럿이라 (Esc, 오른쪽 클릭, 드래그 끝) */
  private boolean done;

  private void show(Rectangle2D area, WritableImage shot, double mouseX, double mouseY,
      Consumer<Shot> onDone, Runnable onCancel) {
    double width = area.getWidth();
    double height = area.getHeight();

    // 판이 뜨자마자 안내판이 제자리에 있게, 지금 마우스가 있는 데서 시작한다
    pointerX = mouseX - area.getMinX();
    pointerY = mouseY - area.getMinY();

    ImageView view = new ImageView(shot);
    view.setFitWidth(width);
    view.setFitHeight(height);

    Rectangle dim = new Rectangle(width, height, Color.rgb(0, 0, 0, 0.45));

    selection.setFill(Color.TRANSPARENT);
    selection.setStroke(CROSS);
    selection.setStrokeWidth(1.5);
    selection.setVisible(false);

    size.getStyleClass().add("capture-size");
    size.setVisible(false);

    Pane root = new Pane(view, dim, selection, size, hints(width, height));
    root.setPrefSize(width, height);

    Stage stage = new Stage(StageStyle.UNDECORATED);
    Scene scene = new Scene(root, width, height);
    scene.setCursor(crosshair());
    scene.setFill(Color.BLACK);
    scene.getStylesheets().add(Overlay.class.getResource("/style/style.css").toExternalForm());

    // 끝내는 길은 전부 여기로 모은다 — 창 닫고 딱 한 번만 알린다
    Runnable cancel = () -> {
      if (done)
        return;
      done = true;
      stage.close();
      onCancel.run();
    };
    Runnable finish = () -> {
      if (done || selection.getWidth() < MIN_DRAG || selection.getHeight() < MIN_DRAG)
        return;
      done = true;
      stage.close();
      onDone.accept(new Shot(crop(shot, width), grab, then));
    };

    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      switch (e.getCode()) {
        case ESCAPE -> {
          e.consume();
          cancel.run();
        }
        case X -> {
          e.consume();
          grab = grab == Grab.IMAGE ? Grab.TEXT : Grab.IMAGE;
          refreshHints();
        }
        case C -> {
          e.consume();
          then = then == Then.SEARCH ? Then.COPY : Then.SEARCH;
          refreshHints();
        }
        default -> {
        }
      }
    });

    root.setOnMouseMoved(e -> follow(e.getX(), e.getY(), width, height));
    root.setOnMousePressed(e -> {
      follow(e.getX(), e.getY(), width, height);
      if (e.getButton() == MouseButton.SECONDARY) {
        cancel.run();
        return;
      }
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      anchorX = e.getX();
      anchorY = e.getY();
      dragging = true;
      track(e.getX(), e.getY(), dim, width, height);
    });
    root.setOnMouseDragged(e -> {
      // 끄는 동안엔 MOUSE_MOVED 가 안 와서, 안내판은 여기서 따라 옮긴다
      follow(e.getX(), e.getY(), width, height);
      if (dragging)
        track(e.getX(), e.getY(), dim, width, height);
    });
    root.setOnMouseReleased(e -> {
      if (!dragging || e.getButton() != MouseButton.PRIMARY)
        return;
      dragging = false;
      // 끌지 않고 톡 누른 건 자른 게 아니다 — 판은 그대로 두고 다시 고르게 한다
      if (selection.getWidth() < MIN_DRAG || selection.getHeight() < MIN_DRAG) {
        selection.setVisible(false);
        size.setVisible(false);
        dim.setClip(null);
        return;
      }
      finish.run();
    });

    refreshHints();

    stage.setScene(scene);
    stage.setAlwaysOnTop(true);
    stage.setResizable(false);
    stage.setX(area.getMinX());
    stage.setY(area.getMinY());
    stage.setWidth(width);
    stage.setHeight(height);
    stage.show();
    stage.toFront();
    stage.requestFocus();
    root.requestFocus(); // 키(X·C·Esc)를 판이 직접 받게
  }

  /** 끌고 있는 사각형을 갱신하고, 그만큼 어둠에 구멍을 뚫는다 */
  private void track(double x, double y, Rectangle dim, double width, double height) {
    double minX = Math.max(0, Math.min(anchorX, x));
    double minY = Math.max(0, Math.min(anchorY, y));
    double maxX = Math.min(width, Math.max(anchorX, x));
    double maxY = Math.min(height, Math.max(anchorY, y));

    selection.setX(minX);
    selection.setY(minY);
    selection.setWidth(maxX - minX);
    selection.setHeight(maxY - minY);
    selection.setVisible(true);

    // 고른 자리만 원래 밝기로 보이게 — 어두운 판에서 그 자리를 오려낸다
    dim.setClip(Shape.subtract(new Rectangle(width, height),
        new Rectangle(minX, minY, maxX - minX, maxY - minY)));

    size.setText(Math.round(maxX - minX) + " × " + Math.round(maxY - minY));
    size.setVisible(true);
    // 글자는 사각형 위에 얹는데, 위가 막혔으면(화면 맨 위) 안쪽으로 내린다
    size.setLayoutX(minX);
    size.setLayoutY(minY > 26 ? minY - 26 : minY + 4);
  }

  /** 화면에 보이는 사각형을 원본 픽셀 좌표로 되돌려 잘라낸다 */
  private Image crop(WritableImage shot, double width) {
    double scale = shot.getWidth() / width;
    int x = (int) Math.round(selection.getX() * scale);
    int y = (int) Math.round(selection.getY() * scale);
    int w = (int) Math.round(selection.getWidth() * scale);
    int h = (int) Math.round(selection.getHeight() * scale);

    // 반올림하다 그림 밖으로 한 픽셀 넘어가면 예외가 나서, 테두리에서 한 번 접어준다
    x = Math.max(0, Math.min(x, (int) shot.getWidth() - 1));
    y = Math.max(0, Math.min(y, (int) shot.getHeight() - 1));
    w = Math.max(1, Math.min(w, (int) shot.getWidth() - x));
    h = Math.max(1, Math.min(h, (int) shot.getHeight() - y));

    return new WritableImage(shot.getPixelReader(), x, y, w, h);
  }

  /** 마우스를 따라다니는 안내판 — 지금 켜진 토글이 여기서 밝게 빛난다 */
  private VBox hints(double width, double height) {
    Label title = new Label("드래그해서 자를 곳 고르기 · Esc / 오른쪽 클릭 으로 그만두기");
    title.getStyleClass().add("capture-hint-title");

    HBox grabRow = new HBox(8, key("X"), grabImage, grabText);
    HBox thenRow = new HBox(8, key("C"), thenSearch, thenCopy);
    grabRow.setAlignment(Pos.CENTER_LEFT);
    thenRow.setAlignment(Pos.CENTER_LEFT);

    VBox box = new VBox(8, title, grabRow, thenRow);
    box.getStyleClass().add("capture-hint");
    box.setPadding(new Insets(14, 18, 14, 18));
    box.setAlignment(Pos.CENTER_LEFT);

    hintBox = box;
    // 글자가 바뀌면(렌즈 검색 ↔ 구글 검색) 폭도 바뀌니까 그때마다 자리를 다시 잡는다.
    // 처음 자리도 이 listener 가 잡아준다 — 크기는 한 번 그려봐야 나와서.
    box.layoutBoundsProperty().addListener((obs, was, is) -> place(width, height));
    return box;
  }

  /** 마우스가 옮겨간 자리를 적어두고 안내판을 따라 세운다 */
  private void follow(double x, double y, double width, double height) {
    pointerX = x;
    pointerY = y;
    place(width, height);
  }

  /**
   * 안내판을 마우스 오른쪽 아래에 세운다.
   * 오른쪽이나 아래가 모자라면 반대쪽으로 넘긴다 — 화면 밖으로 밀려나면 못 읽어서.
   */
  private void place(double width, double height) {
    if (hintBox == null)
      return;
    double w = hintBox.getLayoutBounds().getWidth();
    double h = hintBox.getLayoutBounds().getHeight();

    double x = pointerX + HINT_GAP;
    double y = pointerY + HINT_GAP;
    if (x + w > width)
      x = pointerX - HINT_GAP - w;
    if (y + h > height)
      y = pointerY - HINT_GAP - h;

    hintBox.setLayoutX(Math.max(0, Math.min(x, width - w)));
    hintBox.setLayoutY(Math.max(0, Math.min(y, height - h)));
  }

  /** 안내판에서 누르는 키를 나타내는 네모칸 (X, C) */
  private static Label key(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("capture-key");
    return label;
  }

  /** 토글의 한쪽 — 켜지면 capture-on 이 붙어서 밝아진다 */
  private static Label option(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("capture-option");
    return label;
  }

  private void refreshHints() {
    // 글자 모드에서 '검색' 은 렌즈가 아니라 읽어낸 말을 구글에 치는 것
    thenSearch.setText(grab == Grab.IMAGE ? "🔍 복사 + 렌즈 검색" : "🔍 복사 + 구글 검색");

    light(grabImage, grab == Grab.IMAGE);
    light(grabText, grab == Grab.TEXT);
    light(thenSearch, then == Then.SEARCH);
    light(thenCopy, then == Then.COPY);
  }

  private static void light(Label label, boolean on) {
    label.getStyleClass().remove("capture-on");
    if (on)
      label.getStyleClass().add("capture-on");
  }

  /**
   * 모니터를 전부 덮는 사각형. 왼쪽/위쪽에 붙은 모니터는 좌표가 음수라
   * 폭·높이만 더하면 안 되고 네 모서리를 따로 재야 한다.
   */
  private static Rectangle2D virtualBounds() {
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Screen screen : Screen.getScreens()) {
      Rectangle2D b = screen.getBounds();
      minX = Math.min(minX, b.getMinX());
      minY = Math.min(minY, b.getMinY());
      maxX = Math.max(maxX, b.getMaxX());
      maxY = Math.max(maxY, b.getMaxY());
    }
    if (minX > maxX || minY > maxY) // 화면 목록이 비어 있는 이상한 경우
      return Screen.getPrimary().getBounds();
    return new Rectangle2D(minX, minY, maxX - minX, maxY - minY);
  }

  /**
   * 연한 핑크 십자 커서를 직접 그린다.
   *
   * 윈도우가 주는 기본 십자(Cursor.CROSSHAIR)는 검은색이라, 어둡게 덮인 판 위에서는
   * 어디를 가리키는지 잘 안 보인다. 그래서 연한 핑크로 그려 쓴다.
   * 한가운데는 비워둔다 — 찍으려는 자리를 커서가 덮으면 안 되니까.
   */
  private static ImageCursor crosshair() {
    int size = 32;
    int mid = size / 2;

    WritableImage image = new WritableImage(size, size); // 처음엔 전부 투명
    PixelWriter pixels = image.getPixelWriter();
    for (int i = 0; i < size; i++) {
      if (Math.abs(i - mid) < 3) // 한가운데 빈 자리
        continue;
      for (int t = 0; t < POINTER_WIDTH; t++) {
        pixels.setColor(i, mid + t, POINTER);
        pixels.setColor(mid + t, i, POINTER);
      }
    }
    // 정확히 여기라고 찍어주는 한 점. 선과 같은 굵기라야 가운데가 야위어 보이지 않는다
    for (int x = 0; x < POINTER_WIDTH; x++)
      for (int y = 0; y < POINTER_WIDTH; y++)
        pixels.setColor(mid + x, mid + y, POINTER);

    return new ImageCursor(image, mid, mid);
  }
}
