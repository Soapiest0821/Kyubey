package kyubey.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.net.URL;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 이모지를 그림(Noto Emoji PNG)으로 바꿔 그리는 곳.
 *
 * JavaFX 는 윈도우에서 컬러 이모지 폰트를 못 그린다 — Segoe UI Emoji 는 흑백 윤곽만 나오고,
 * Noto Color Emoji 를 폰트로 걸면 아예 빈칸이 된다 (JavaFX 21 ~ 25 다 그렇다).
 * 그래서 글을 글자 덩어리(grapheme)로 잘라서, 그림이 있는 이모지만 /emoji/emoji_uXXXX.png 로 갈아 끼운다.
 *
 * 그림 이름은 Noto 규칙을 따른다 — 코드포인트를 소문자 16진수로 _ 로 잇고, FE0F(이모지로 보여라 표시)는 뺀다.
 * 그림이 없는 이모지(국기 같은 것)는 글자 그대로 둔다.
 */
public final class Emoji {

  private static final String DIR = "/emoji/emoji_u";

  /** 이모지로 보여라 표시 (VS16) */
  private static final int VS16 = 0xFE0F;

  /** 키캡 (1️⃣ 의 테두리) */
  private static final int KEYCAP = 0x20E3;

  /** 한 번 읽은 그림. 없는 것도 없다고 기억해서 같은 자리를 또 안 뒤진다 */
  private static final Map<String, Optional<Image>> cache = new HashMap<>();

  private Emoji() {
  }

  /** 글 한 조각 — 이모지면 image 가 있고, 보통 글이면 null */
  public record Piece(String text, Image image) {
  }

  /** 글을 보통 글 · 이모지 조각으로 자른다. 보통 글은 이어 붙여 한 조각으로 */
  public static List<Piece> split(String text) {
    List<Piece> out = new ArrayList<>();
    StringBuilder plain = new StringBuilder();
    BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
    it.setText(text);
    int start = it.first();
    for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
      String g = text.substring(start, end);
      Image image = image(g);
      if (image == null) {
        plain.append(g);
        continue;
      }
      if (!plain.isEmpty()) {
        out.add(new Piece(plain.toString(), null));
        plain.setLength(0);
      }
      out.add(new Piece(g, image));
    }
    if (!plain.isEmpty())
      out.add(new Piece(plain.toString(), null));
    return out;
  }

  /** 이모지 하나(grapheme)의 그림. 이모지가 아니거나 그림이 없으면 null */
  public static Image image(String grapheme) {
    if (grapheme == null || grapheme.isEmpty() || !looksLikeEmoji(grapheme))
      return null;
    return cache.computeIfAbsent(key(grapheme), key -> {
      URL url = Emoji.class.getResource(DIR + key + ".png");
      return url == null ? Optional.empty() : Optional.of(new Image(url.toExternalForm()));
    }).orElse(null);
  }

  /** 이모지 하나를 size 크기 그림으로. 그림이 없으면 null — 그땐 글자로 쓰면 된다 */
  public static ImageView view(String grapheme, double size) {
    Image image = image(grapheme == null ? null : grapheme.strip());
    return image == null ? null : view(image, size);
  }

  public static ImageView view(Image image, double size) {
    ImageView view = new ImageView(image);
    view.setFitWidth(size);
    view.setFitHeight(size);
    view.setPreserveRatio(true);
    view.setSmooth(true);
    return view;
  }

  /**
   * 글자 칸에 넣을 모양으로 — 혼자 쓰면 글자로 나오는 이모지(❤ ✌ ☺ …)엔 FE0F 를 붙인다.
   * 고르기 판에서 넣은 이모지가 말풍선에서 글자로 내려앉지 않게.
   */
  public static String presented(String emoji) {
    if (emoji.codePointCount(0, emoji.length()) == 1 && !Character.isEmojiPresentation(emoji.codePointAt(0)))
      return emoji + Character.toString(VS16);
    return emoji;
  }

  /**
   * 그림으로 바꿀 만한 덩어리인지. 숫자 · # · © 처럼 이모지 그림이 있어도 평소엔 글자인 것들은
   * FE0F 나 키캡이 붙어 있을 때만 이모지로 친다.
   * 다만 ❤ ☺ ✌ ♥ ☀ 처럼 기호 · 딩뱃 칸(2600 ~ 27BF)의 이모지는 FE0F 없이 와도 이모지로 친다 —
   * 모델 답장이나 직접 친 글엔 FE0F 가 자주 빠져서, 이걸 글자로 두면 흑백 윤곽으로 섞여 나온다.
   */
  private static boolean looksLikeEmoji(String g) {
    return g.codePoints().anyMatch(cp -> cp == VS16 || cp == KEYCAP || Character.isEmojiPresentation(cp)
        || (cp >= 0x2600 && cp <= 0x27BF && Character.isEmoji(cp)));
  }

  private static String key(String g) {
    return g.codePoints()
        .filter(cp -> cp != VS16)
        .mapToObj(cp -> String.format("%04x", cp))
        .collect(Collectors.joining("_"));
  }
}
