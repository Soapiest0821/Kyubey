package kyubey.chat;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/**
 * 수다에 보내는 사진.
 *
 * 토큰을 아끼는 길은 그림을 대화에 싣지 않는 것이다. 수다는 최근 말풍선 여덟 개를 매번 다시 보내서,
 * 그림을 대화에 그대로 실으면 그 줄이 창 밖으로 밀려날 때까지 답할 때마다 값을 또 치른다.
 * 그래서 보낼 때 딱 한 번 낮은 해상도로 읽혀 짧은 설명(Line.caption)을 받아두고 (LlmClient.describeImage),
 * 마도카한테는 그 설명 글만 들려준다 (spoken). 읽히는 값은 한 장에 한 번뿐이다.
 *
 * 화면에 그릴 그림은 상대 폴더의 images/ 에 JPEG 로 줄여서 남긴다.
 * 지운 대화 창에서도 보여야 하니 Line.image 에는 파일 자리를 그대로 적는다.
 */
final class ChatImage {

  /** 사진 줄의 글. 답장 인용 · 빈 줄 거르기가 글을 보니 비워두지 않는다 (ChatHistory.load) */
  static final String TEXT = "[사진]";

  /** 상대 폴더 안에서 사진이 쌓이는 곳 */
  static final String DIR = "images";

  /** 고를 수 있는 그림 — JavaFX 가 읽을 수 있는 것들 */
  static final List<String> FILTER = List.of("*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp");

  private static final Set<String> EXTS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

  /** 저장 · 전송할 긴 변 길이. 토큰은 해상도 설정이 정하니, 이건 업로드 양과 디스크를 아끼는 값이다 */
  private static final int MAX_EDGE = 1024;
  private static final float QUALITY = 0.85f;

  /** 말풍선에 그릴 긴 변 길이 */
  private static final double BUBBLE_EDGE = 240;
  private static final double CORNER = 28;

  private ChatImage() {
  }

  /**
   * 마도카한테 들려줄 글. 아직 못 읽었거나 읽어도 설명이 안 나온 사진은 사진이 왔다는 것만 들려준다.
   * 마도카가 보낸 사진(WebPicture)은 찾을 때 적은 설명이 그대로 들어 있다
   */
  static String spoken(ChatHistory.Line line) {
    if (line.image == null)
      return line.text;
    String who = line.mine ? "상대가" : "내가";
    if (line.caption == null || line.caption.isBlank())
      return "[" + who + " 사진을 보냈는데 내용은 안 보임]";
    return "[" + who + " 사진을 보냄: " + line.caption + "]";
  }

  /** 목록에서 첫 그림 파일. 없으면 null */
  static File firstImage(List<File> files) {
    for (File file : files) {
      String name = file.getName().toLowerCase(Locale.ROOT);
      int dot = name.lastIndexOf('.');
      if (dot >= 0 && EXTS.contains(name.substring(dot + 1)))
        return file;
    }
    return null;
  }

  /** 파일에서 읽되 MAX_EDGE 로 줄여서 — 큰 사진을 원본 크기로 펼치지 않게 */
  static Image load(File file) {
    return new Image(file.toURI().toString(), MAX_EDGE, MAX_EDGE, true, true);
  }

  /** 받아온 바이트에서 읽되 MAX_EDGE 로 줄여서 */
  static Image load(byte[] bytes) {
    return new Image(new ByteArrayInputStream(bytes), MAX_EDGE, MAX_EDGE, true, true);
  }

  /** 그림을 줄여 JPEG 로 dir 에 남기고 파일 자리를 돌려준다. 시간이 걸리니 JavaFX 스레드에서 부르지 말 것 */
  static String save(Image image, String dir) throws IOException {
    if (image == null || image.isError())
      throw new IOException("그림을 못 읽었어");
    BufferedImage shrunk = shrink(SwingFXUtils.fromFXImage(image, null));

    File folder = new File(dir);
    folder.mkdirs();
    File file = new File(folder, System.currentTimeMillis() + ".jpg");
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    try (FileImageOutputStream out = new FileImageOutputStream(file)) {
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(QUALITY);
      writer.setOutput(out);
      writer.write(null, new IIOImage(shrunk, null, null), param);
    } finally {
      writer.dispose();
    }
    return dir + "/" + file.getName();
  }

  /** 긴 변을 MAX_EDGE 로 줄이고 투명한 데는 흰색으로 깐다 — JPEG 엔 알파가 없어서 그냥 구우면 검게 나온다 */
  private static BufferedImage shrink(BufferedImage source) throws IOException {
    if (source == null)
      throw new IOException("그림을 옮겨 담지 못했어");
    int w = source.getWidth();
    int h = source.getHeight();
    double scale = Math.min(1, (double) MAX_EDGE / Math.max(w, h));
    int width = Math.max(1, (int) Math.round(w * scale));
    int height = Math.max(1, (int) Math.round(h * scale));

    BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.drawImage(source, 0, 0, width, height, Color.WHITE, null);
    g.dispose();
    return out;
  }

  /** 말풍선 자리에 놓을 그림. 파일이 없어졌으면 null — 그러면 부르는 쪽이 글 말풍선으로 그린다 */
  static Region bubble(ChatHistory.Line line) {
    File file = new File(line.image);
    if (!file.exists())
      return null;
    // 고해상도 화면에서도 또렷하게 두 배로 읽어둔다
    Image image = new Image(file.toURI().toString(), BUBBLE_EDGE * 2, BUBBLE_EDGE * 2, true, true);
    if (image.isError() || image.getWidth() <= 0)
      return null;

    double scale = Math.min(1, BUBBLE_EDGE / Math.max(image.getWidth(), image.getHeight()));
    double width = image.getWidth() * scale;
    double height = image.getHeight() * scale;
    ImageView view = new ImageView(image);
    view.setFitWidth(width);
    view.setFitHeight(height);
    view.setSmooth(true);
    Rectangle corners = new Rectangle(width, height);
    corners.setArcWidth(CORNER);
    corners.setArcHeight(CORNER);
    view.setClip(corners);

    StackPane box = new StackPane(view);
    box.setAlignment(Pos.CENTER);
    box.setCursor(Cursor.HAND);
    // 누르면 윈도우의 사진 뷰어로 크게 본다
    box.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        open(file);
    });
    return box;
  }

  private static void open(File file) {
    Thread thread = new Thread(() -> {
      try {
        if (Desktop.isDesktopSupported())
          Desktop.getDesktop().open(file);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }, "chat-image-open");
    thread.setDaemon(true);
    thread.start();
  }

  /** 사진 파일을 지운다. 보내기 전에 뺐거나, 보관하지 않고 대화를 지웠을 때 */
  static void delete(String path) {
    if (path == null)
      return;
    try {
      Files.deleteIfExists(new File(path).toPath());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
