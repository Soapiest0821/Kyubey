package widget.capture;

import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * 클립보드에 그림/글자를 얹는다. JavaFX 스레드에서만 부를 것.
 *
 * 캡쳐는 뭘 하든 일단 복사부터 한다 — 렌즈로 검색을 시키든 안 시키든,
 * 잘라낸 게 클립보드에 있어야 바로 다른 데 붙여넣을 수 있어서.
 */
final class Clip {

  private Clip() {
  }

  static void put(Image image) {
    ClipboardContent content = new ClipboardContent();
    content.putImage(image);
    Clipboard.getSystemClipboard().setContent(content);
  }

  static void put(String text) {
    ClipboardContent content = new ClipboardContent();
    content.putString(text);
    Clipboard.getSystemClipboard().setContent(content);
  }
}
