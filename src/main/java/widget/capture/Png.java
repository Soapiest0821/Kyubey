package widget.capture;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * 잘라낸 그림을 PNG 바이트로 굽는다.
 *
 * 클립보드는 JavaFX Image 를 그대로 받지만, 구글 렌즈에 올리거나 Gemini 한테
 * 글자를 읽어달라고 할 땐 파일 모양의 바이트가 있어야 해서 여기서 한 번 굽는다.
 *
 * 알파 채널은 떼고 굽는다 — 화면을 그대로 찍은 거라 투명한 데가 없는데,
 * 굳이 들고 가면 용량만 커지고 받는 쪽에서 검게 깔리는 일이 생긴다.
 */
final class Png {

  private Png() {
  }

  static byte[] encode(Image image) throws IOException {
    BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
    if (buffered == null)
      throw new IOException("그림을 옮겨 담지 못했어");

    BufferedImage opaque = new BufferedImage(
        buffered.getWidth(), buffered.getHeight(), BufferedImage.TYPE_INT_RGB);
    var g = opaque.createGraphics();
    g.drawImage(buffered, 0, 0, null);
    g.dispose();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(opaque, "png", out))
      throw new IOException("PNG 로 굽지 못했어");
    return out.toByteArray();
  }
}
