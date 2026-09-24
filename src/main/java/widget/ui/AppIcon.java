package widget.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 작업 표시줄과 Alt+Tab 에 뜨는 아이콘을 /img/images.ico 에서 읽어 온다.
 *
 * JavaFX 의 Image 는 PNG·JPEG·GIF·BMP 만 읽고 .ico 는 못 본다 (ImageIO 도 마찬가지라
 * 갈아탈 데가 없다). 그래서 여기서 ico 를 직접 뜯는다 — 안에 든 그림 중 제일 큰 걸 골라서,
 * 비스타 이후 형식처럼 PNG 통짜면 그대로 쓰고 아니면 32bpp BGRA 비트맵을 손으로 편다.
 *
 * 못 읽어도 예외는 안 던진다 — 아이콘만 자바 기본 커피잔으로 남고 위젯은 그대로 뜬다.
 */
public final class AppIcon {

  private static final String RESOURCE = "/img/images.ico";

  /**
   * 윈도우가 쓰는 아이콘 크기들. 원본(256px) 한 장만 건네면 줄이는 건 윈도우 몫인데
   * 그 그림이 지저분해서, 크기별로 미리 곱게 줄여 놓고 골라 쓰게 한다.
   */
  private static final int[] SIZES = { 16, 24, 32, 48, 64, 128, 256 };

  private AppIcon() {
  }

  /** stage.show() 전에 한 번만 불러주면 된다. */
  public static void apply(Stage stage) {
    List<Image> icons = load();
    if (!icons.isEmpty())
      stage.getIcons().setAll(icons);
  }

  private static List<Image> load() {
    byte[] ico;
    try (InputStream in = AppIcon.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        System.err.println("[icon] " + RESOURCE + " 를 못 찾았어");
        return List.of();
      }
      ico = in.readAllBytes();
    } catch (IOException e) {
      System.err.println("[icon] " + RESOURCE + " 를 못 읽었어: " + e.getMessage());
      return List.of();
    }

    byte[] png = toPng(ico);
    if (png == null)
      return List.of();

    List<Image> icons = new ArrayList<>();
    for (int size : SIZES)
      icons.add(new Image(new ByteArrayInputStream(png), size, size, true, true));
    return icons;
  }

  /** ico 에서 제일 큰 그림을 꺼내 PNG 바이트로 돌려준다. 못 알아보면 null. */
  private static byte[] toPng(byte[] ico) {
    if (ico.length < 22) {
      System.err.println("[icon] ico 가 너무 짧아");
      return null;
    }

    ByteBuffer buf = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
    if (buf.getShort(0) != 0 || buf.getShort(2) != 1) {
      System.err.println("[icon] ico 헤더가 아니야");
      return null;
    }

    int count = buf.getShort(4) & 0xffff;
    int best = -1;
    int bestSide = -1;
    for (int i = 0; i < count; i++) {
      int entry = 6 + i * 16;
      if (entry + 16 > ico.length)
        break;
      int side = ico[entry] & 0xff; // 0 은 256 을 뜻한다 (한 바이트에 안 들어가서)
      if (side == 0)
        side = 256;
      if (side > bestSide) {
        bestSide = side;
        best = entry;
      }
    }
    if (best < 0) {
      System.err.println("[icon] ico 안에 그림이 없어");
      return null;
    }

    int length = buf.getInt(best + 8);
    int offset = buf.getInt(best + 12);
    if (offset < 0 || length < 0 || offset + length > ico.length) {
      System.err.println("[icon] ico 안의 그림 위치가 파일 밖을 가리켜");
      return null;
    }

    if (isPng(ico, offset))
      return Arrays.copyOfRange(ico, offset, offset + length);

    BufferedImage bitmap = readDib(ico, offset);
    if (bitmap == null)
      return null;

    // JavaFX 가 읽을 수 있는 형식으로 한 번 구워서 넘긴다 (크기 조절도 JavaFX 에 맡기려고)
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(bitmap, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      System.err.println("[icon] PNG 로 굽다 실패했어: " + e.getMessage());
      return null;
    }
  }

  private static boolean isPng(byte[] bytes, int offset) {
    return offset + 8 <= bytes.length
        && (bytes[offset] & 0xff) == 0x89
        && bytes[offset + 1] == 'P' && bytes[offset + 2] == 'N' && bytes[offset + 3] == 'G';
  }

  /** ico 안의 BITMAPINFOHEADER 그림(32bpp BGRA)을 편다. */
  private static BufferedImage readDib(byte[] ico, int offset) {
    ByteBuffer buf = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
    int headerSize = buf.getInt(offset);
    int width = buf.getInt(offset + 4);
    // ico 에 적힌 높이는 그림 + 투명 마스크를 합친 값이라 두 배다
    int height = buf.getInt(offset + 8) / 2;
    int bits = buf.getShort(offset + 14) & 0xffff;
    int compression = buf.getInt(offset + 16);

    if (bits != 32 || compression != 0) {
      System.err.println("[icon] 압축 안 한 32bpp ico 만 읽을 수 있어 "
          + "(이건 " + bits + "bpp" + (compression == 0 ? "" : ", 압축됨") + "). "
          + "PNG 로 저장한 ico 로 바꾸면 그대로 읽힌다.");
      return null;
    }
    if (width <= 0 || height <= 0) {
      System.err.println("[icon] ico 크기가 이상해: " + width + "x" + height);
      return null;
    }

    int pixels = offset + headerSize;
    if (pixels + width * height * 4 > ico.length) {
      System.err.println("[icon] ico 그림 데이터가 잘려 있어");
      return null;
    }

    int[] argb = new int[width * height];
    boolean anyAlpha = false;
    for (int y = 0; y < height; y++) {
      int row = pixels + (height - 1 - y) * width * 4; // DIB 는 아래 줄부터 쌓여 있다
      for (int x = 0; x < width; x++) {
        int p = row + x * 4;
        int b = ico[p] & 0xff;
        int g = ico[p + 1] & 0xff;
        int r = ico[p + 2] & 0xff;
        int a = ico[p + 3] & 0xff;
        anyAlpha |= a != 0;
        argb[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
      }
    }

    // 알파 칸을 아예 안 채운 옛날 ico 는 통째로 투명해져 버린다. 그럴 때만
    // 그림 뒤에 붙은 AND 마스크를 본다 — 비트가 켜진 자리가 "여긴 뚫린 곳" 이다.
    int mask = pixels + width * height * 4;
    int stride = ((width + 31) / 32) * 4;
    if (!anyAlpha && mask + height * stride <= ico.length) {
      for (int y = 0; y < height; y++) {
        int row = mask + (height - 1 - y) * stride;
        for (int x = 0; x < width; x++) {
          boolean hole = ((ico[row + x / 8] >> (7 - (x % 8))) & 1) != 0;
          if (!hole)
            argb[y * width + x] |= 0xff000000;
        }
      }
    } else if (!anyAlpha) {
      for (int i = 0; i < argb.length; i++)
        argb[i] |= 0xff000000; // 마스크도 없으면 그냥 다 불투명으로
    }

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, width, height, argb, 0, width);
    return image;
  }
}
