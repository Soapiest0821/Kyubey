package widget.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 답을 마크다운으로 그려주는 뷰.
 *
 * WebView 를 쓰면 javafx.web 모듈을 run.bat 에 더 붙여야 하고 덩치도 커져서,
 * 위젯에 필요한 만큼만 직접 파싱해서 TextFlow 로 그린다.
 *
 * 지원: #~### 제목, - / 1. 목록(들여쓰기 포함), > 인용, --- 구분선,
 *       ``` 코드블록, `인라인코드`, **굵게**, *기울임*, ~~취소선~~, [글자](링크)
 * 미지원: 표, 이미지, 굵게+기울임 중첩. 그냥 원문 그대로 나온다.
 */
public class MarkdownView extends ScrollPane {

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
  private static final Pattern HORIZONTAL_RULE = Pattern.compile("^(?:-{3,}|\\*{3,}|_{3,})$");
  private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
  private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
  private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");

  /** 인라인 서식. 굵게를 기울임보다 먼저 둬야 **x** 가 *x* 로 안 잘린다. */
  private static final Pattern INLINE = Pattern.compile(
      "`([^`]+)`"
          + "|\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*"
          + "|__(?=\\S)(.+?)(?<=\\S)__"
          + "|\\*(?=\\S)(.+?)(?<=\\S)\\*"
          + "|(?<!\\w)_(?=\\S)(.+?)(?<=\\S)_(?!\\w)"
          + "|~~(.+?)~~"
          + "|\\[(.+?)\\]\\((\\S+?)\\)");

  private static final int INDENT_STEP = 16;

  private final VBox blocks = new VBox(6);

  /** 복사할 때 쓰려고 들고 있는 원문 */
  private String source = "";

  public MarkdownView() {
    getStyleClass().add("md-view");
    blocks.getStyleClass().add("md-blocks");
    blocks.setFillWidth(true);

    setContent(blocks);
    setFitToWidth(true);
    setHbarPolicy(ScrollBarPolicy.NEVER);
    setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
    setMinHeight(0);
  }

  /** 화면에 뿌릴 마크다운. 그냥 평문을 넣어도 문제없다. */
  public void setMarkdown(String markdown) {
    source = markdown == null ? "" : markdown;
    blocks.getChildren().clear();
    render(source);
    setVvalue(0); // 새 답은 맨 위부터 보여준다
  }

  /** Ctrl+Shift+C 로 복사할 원문 (그려진 게 아니라 받은 그대로) */
  public String getSource() {
    return source;
  }

  public void clear() {
    setMarkdown("");
  }

  private void render(String markdown) {
    String[] lines = markdown.split("\\R", -1);
    List<String> paragraph = new ArrayList<>();

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      String trimmed = line.strip();

      // ``` 코드블록: 닫는 ``` 나 입력 끝까지 통째로 먹는다
      if (trimmed.startsWith("```")) {
        flush(paragraph);
        StringBuilder code = new StringBuilder();
        i++;
        while (i < lines.length && !lines[i].strip().startsWith("```")) {
          code.append(lines[i]).append('\n');
          i++;
        }
        addCodeBlock(code.toString());
        continue;
      }

      if (trimmed.isEmpty()) {
        flush(paragraph);
        continue;
      }

      if (HORIZONTAL_RULE.matcher(trimmed).matches()) {
        flush(paragraph);
        addRule();
        continue;
      }

      Matcher heading = HEADING.matcher(trimmed);
      if (heading.matches()) {
        flush(paragraph);
        int level = Math.min(heading.group(1).length(), 3); // #### 이하는 ### 취급
        addFlow(heading.group(2), "md-h" + level, "md-t-h" + level);
        continue;
      }

      Matcher quote = QUOTE.matcher(trimmed);
      if (quote.matches()) {
        flush(paragraph);
        addFlow(quote.group(1), "md-quote", "md-t-quote");
        continue;
      }

      Matcher bullet = BULLET.matcher(line);
      if (bullet.matches()) {
        flush(paragraph);
        addListItem(depthOf(bullet.group(1)), "•", bullet.group(2));
        continue;
      }

      Matcher ordered = ORDERED.matcher(line);
      if (ordered.matches()) {
        flush(paragraph);
        addListItem(depthOf(ordered.group(1)), ordered.group(2) + ".", ordered.group(3));
        continue;
      }

      paragraph.add(trimmed);
    }
    flush(paragraph);
  }

  /** 빈 줄 나오기 전까지 모인 줄들을 한 문단으로 붙인다 */
  private void flush(List<String> paragraph) {
    if (paragraph.isEmpty())
      return;
    addFlow(String.join(" ", paragraph), "md-p", "md-t-p");
    paragraph.clear();
  }

  private static int depthOf(String indent) {
    return indent.replace("\t", "  ").length() / 2;
  }

  /**
   * @param blockClass TextFlow(=문단 상자)에 붙는 클래스 — 여백/테두리용
   * @param textClass  안에 들어가는 Text 하나하나에 붙는 클래스 — 글자 크기/색용
   *
   *                   JavaFX 에서는 `.md-p .text` 같은 자손 선택자가 TextFlow 안의
   *                   Text 에 안 걸려서, 글자 속성은 Text 에 클래스를 직접 붙여야 한다.
   */
  private TextFlow addFlow(String text, String blockClass, String textClass) {
    TextFlow flow = new TextFlow();
    flow.getStyleClass().add(blockClass);
    appendInline(flow, text, textClass);
    blocks.getChildren().add(flow);
    return flow;
  }

  private void addListItem(int depth, String marker, String text) {
    Label bullet = new Label(marker);
    bullet.getStyleClass().add("md-li-marker");
    bullet.setMinWidth(Region.USE_PREF_SIZE);

    TextFlow body = new TextFlow();
    body.getStyleClass().add("md-li");
    appendInline(body, text, "md-t-li");
    HBox.setHgrow(body, Priority.ALWAYS);

    HBox row = new HBox(6, bullet, body);
    row.setAlignment(Pos.TOP_LEFT); // 마커가 첫 줄에 붙게 (기본값이면 세로 가운데로 내려간다)
    row.setPadding(new Insets(0, 0, 0, depth * INDENT_STEP));
    blocks.getChildren().add(row);
  }

  private void addCodeBlock(String code) {
    Label block = new Label(code.stripTrailing());
    block.getStyleClass().add("md-code-block");
    block.setWrapText(true);
    block.setMaxWidth(Double.MAX_VALUE);
    blocks.getChildren().add(block);
  }

  private void addRule() {
    Region rule = new Region();
    rule.getStyleClass().add("md-hr");
    rule.setMaxWidth(Double.MAX_VALUE);
    blocks.getChildren().add(rule);
  }

  private static void appendInline(TextFlow flow, String text, String textClass) {
    Matcher m = INLINE.matcher(text);
    int last = 0;

    while (m.find()) {
      addText(flow, text.substring(last, m.start()), textClass, null);

      if (m.group(1) != null)
        addText(flow, m.group(1), textClass, "md-code");
      else if (m.group(2) != null)
        addText(flow, m.group(2), textClass, "md-bold");
      else if (m.group(3) != null)
        addText(flow, m.group(3), textClass, "md-bold");
      else if (m.group(4) != null)
        addText(flow, m.group(4), textClass, "md-italic");
      else if (m.group(5) != null)
        addText(flow, m.group(5), textClass, "md-italic");
      else if (m.group(6) != null)
        addText(flow, m.group(6), textClass, "md-strike");
      else
        addLink(flow, m.group(7), m.group(8), textClass);

      last = m.end();
    }
    addText(flow, text.substring(last), textClass, null);
  }

  private static void addText(TextFlow flow, String content, String textClass, String inlineClass) {
    if (content.isEmpty())
      return;
    Text node = new Text(content);
    node.getStyleClass().add(textClass);
    if (inlineClass != null)
      node.getStyleClass().add(inlineClass); // 크기는 textClass, 서식은 inlineClass 가 준다
    flow.getChildren().add(node);
  }

  private static void addLink(TextFlow flow, String label, String url, String textClass) {
    Text node = new Text(label);
    node.getStyleClass().addAll(textClass, "md-link");
    node.setCursor(Cursor.HAND);
    node.setOnMouseClicked(e -> openInBrowser(url));
    flow.getChildren().add(node);
  }

  static void openInBrowser(String url) {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        Desktop.getDesktop().browse(new URI(url));
    } catch (Exception ignored) {
      // 링크 하나 못 연다고 위젯이 죽을 이유는 없다
    }
  }
}
