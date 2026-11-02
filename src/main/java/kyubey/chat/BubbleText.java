package kyubey.chat;

import kyubey.ui.Emoji;
import kyubey.ui.MarkdownView;
import javafx.scene.Cursor;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 말풍선 안의 글자. 수다 화면과 지난 대화 창이 같이 쓴다.
 *
 * Label 로는 글자 일부만 누를 수 있게 못 만들어서 TextFlow 에 Text 조각을 이어 붙인다.
 * http:// · https:// 로 시작하는 주소는 밑줄 친 조각으로 따로 떼고, 누르면 브라우저로 연다.
 * 이모지는 컬러 그림으로 갈아 끼운다 (Emoji).
 */
final class BubbleText {

  /**
   * 주소로 칠 글자. 한글은 뺀다 — "https://x.com에서" 처럼 띄어쓰기 없이 조사가 붙어도
   * 주소는 영문에서 끊기게. 한글 주소는 브라우저가 %xx 로 바꿔 쓰는 쪽이라 놓치는 일이 드물다.
   */
  private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

  /** 주소 끝에 붙어 있어도 문장부호일 가능성이 큰 글자들 */
  private static final String TRAILING = ".,;:!?'\")]";

  private static final double EMOJI_SIZE = 18;
  private static final double EMOJI_DROP = 3;

  private BubbleText() {
  }

  static TextFlow of(String text) {
    TextFlow flow = new TextFlow() {
      /*
       * 말풍선 최대 폭(창 폭 × 0.7)은 화면 픽셀 칸에 안 맞는 값(예: 222.88)이 나온다.
       * 실제로 그릴 땐 픽셀 칸으로 올려 잡히는데(223.2), 높이는 그 전 값으로 재서
       * 한 줄에 딱 차는 말이 두 줄로 넘어간 셈이 된다 — 글자는 한 줄인데 말풍선만
       * 두 줄 높이로 커져서 아래 말풍선을 덮는다. 높이도 그릴 때와 같은 폭으로 잰다.
       */
      @Override
      protected double computePrefHeight(double width) {
        return super.computePrefHeight(width < 0 ? width : snapSizeX(width));
      }

      @Override
      protected double computeMinHeight(double width) {
        return super.computeMinHeight(width < 0 ? width : snapSizeX(width));
      }
    };
    Matcher m = URL.matcher(text);
    int last = 0;

    while (m.find()) {
      String url = trimTail(m.group());
      if (url.length() <= "https://".length())
        continue;
      add(flow, text.substring(last, m.start()));
      addLink(flow, url);
      last = m.start() + url.length();
    }
    add(flow, text.substring(last));
    return flow;
  }

  /**
   * "(https://a.com)" 이나 "https://a.com." 에서 닫는 괄호·마침표를 주소에서 떼어낸다.
   * 괄호는 주소 안에 여는 괄호가 없을 때만 — 위키 주소처럼 괄호가 짝으로 들어간 건 남긴다.
   */
  private static String trimTail(String url) {
    int end = url.length();
    while (end > 0 && TRAILING.indexOf(url.charAt(end - 1)) >= 0) {
      char c = url.charAt(end - 1);
      if (c == ')' && count(url, '(', end) > count(url, ')', end) - 1)
        break;
      if (c == ']' && count(url, '[', end) > count(url, ']', end) - 1)
        break;
      end--;
    }
    return url.substring(0, end);
  }

  private static int count(String s, char c, int end) {
    int n = 0;
    for (int i = 0; i < end; i++) {
      if (s.charAt(i) == c)
        n++;
    }
    return n;
  }

  /** 이모지는 그림으로 (Emoji) — 글자 크기(14px)보다 조금 크게, 줄 아래로 살짝 내려 글자와 높이를 맞춘다 */
  private static void add(TextFlow flow, String content) {
    if (content.isEmpty())
      return;
    for (Emoji.Piece piece : Emoji.split(content)) {
      if (piece.image() != null) {
        ImageView view = Emoji.view(piece.image(), EMOJI_SIZE);
        view.setTranslateY(EMOJI_DROP);
        flow.getChildren().add(view);
        continue;
      }
      Text node = new Text(piece.text());
      node.getStyleClass().add("chat-text");
      flow.getChildren().add(node);
    }
  }

  private static void addLink(TextFlow flow, String url) {
    Text node = new Text(url);
    node.getStyleClass().addAll("chat-text", "chat-link");
    node.setCursor(Cursor.HAND);
    node.setOnMouseClicked(e -> MarkdownView.openInBrowser(url));
    flow.getChildren().add(node);
  }
}
