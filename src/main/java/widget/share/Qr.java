package widget.share;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 주소 한 줄을 QR 그림으로 바꾼다.
 *
 * zxing 의 core 만 쓴다 — javase 모듈은 BufferedImage(AWT) 로 그려주는데,
 * JavaFX 창에 올리려면 어차피 한 번 더 바꿔야 해서 여기서 직접 픽셀을 찍는다.
 */
public final class Qr {

  /**
   * QR 둘레에 두는 흰 여백 (칸 수). 표준은 4칸인데 화면에서 보고 찍는 거라
   * 그렇게까지 넉넉할 필요가 없어서 2칸으로 줄였다 — 그만큼 무늬가 커져서 잘 잡힌다.
   */
  private static final int MARGIN = 2;

  private Qr() {
  }

  /**
   * @param text 담을 글자 (여기선 http://192.168.x.x:포트/... 주소)
   * @param size 한 변 길이(px). 실제로는 칸 수의 배수로 떨어져서 이보다 조금 작게 나올 수 있다
   * @return 검은 무늬 / 흰 바탕 그림. 못 그리면 null (QR 이 없다고 위젯까지 멈출 일은 아니라서)
   */
  public static Image of(String text, int size) {
    BitMatrix matrix;
    try {
      matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, Map.of(
          EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
          // 화면에서 바로 찍는 거라 가려질 일이 없다. 낮게 잡을수록 칸이 적어 큼직하게 나온다.
          EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
          EncodeHintType.MARGIN, MARGIN));
    } catch (WriterException e) {
      e.printStackTrace();
      return null;
    }

    int w = matrix.getWidth();
    int h = matrix.getHeight();
    WritableImage image = new WritableImage(w, h);
    PixelWriter pixels = image.getPixelWriter();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        pixels.setArgb(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
      }
    }
    return image;
  }
}
