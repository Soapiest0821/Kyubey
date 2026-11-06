package kyubey.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kyubey.core.LateWriter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 마도카랑 나눈 수다를 파일에 남겨둔다. 위젯을 껐다 켜도 하던 얘기가 그대로 이어지게.
 *
 * history.json 과 달리 같은 말을 한 줄로 합치지 않는다 — 대화는 순서가 전부라서
 * 친 순서 그대로, 언제 했는지까지 같이 들고 있는다 (말풍선 옆 시각과 날짜 구분선이 이걸 본다).
 *
 * 한 줄 늘 때마다 파일을 통째로 덮어쓴다. 수다는 길어야 수백 줄이고 사람이 치는 속도로만
 * 늘어나서, 이어 붙이는 것보다 이게 훨씬 단순하다.
 */
public class ChatHistory {

  /** 오간 말 한 줄. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 (HistoryManager.Entry 와 같은 결) */
  public static class Line {
    /** 내가 한 말이면 true, 마도카가 한 말이면 false */
    public boolean mine;
    public String text;
    /** 언제 한 말인지 (epoch millis) */
    public long at;
    /** 비밀 쪽지로 온 말인지. 말풍선엔 접힌 쪽지만 보이고 눌러야 열린다 (SecretNote). 보통 줄엔 안 적는다 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean note;
    /** 쪽지를 한 번이라도 열어봤는지 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean opened;
    /** 화면에서 지운 줄. 말풍선만 안 그리고 마도카한테는 그대로 들려준다 (ChatPane.removeLine) */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean hidden;
    /** 밀어서 답장한 말의 시각 · 누가 한 말인지 · 글. 답장이 아니면 replyText 가 null (ChatPane.startReply) */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public long replyAt;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean replyMine;
    /** 답장한 말이 비밀 쪽지였는지 — 그러면 인용에 내용 대신 쪽지라고만 보인다 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean replyNote;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public String replyText;
    /** 내가 이 말에 단 반응 이모지. 없으면 null (ChatPane.react) */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public String reaction;
    /** 마도카가 이 말에 하트를 달았는지 — 이 말로 호감도가 크게 오르면 단다 (ChatPane.HEART_STEP) */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean heart;
    /** 보낸 뒤에 고친 말인지 — 말풍선 옆에 "수정됨" 이 붙는다 (ChatPane.startEdit) */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean edited;
    /**
     * 마도카가 아직 말하는 중에 끼어든 내 말인지. 그 답을 만들 땐 못 들은 말이라, 마도카한테 들려줄 땐
     * 그 답 뒤로 물려서 넘긴다 (ChatPane.window). 화면엔 친 자리 그대로 보인다
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean cutIn;
    /** 사진을 보낸 줄이면 그 파일 자리. text 는 ChatImage.TEXT 로 채워둔다 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public String image;
    /**
     * 사진을 읽은 설명. 마도카한테는 사진 대신 이 글이 간다 (ChatImage.spoken).
     * null 이면 아직 안 읽음, 빈 글이면 읽었는데 설명을 못 얻음 — NON_NULL 이어야 빈 글이 파일에 남는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String caption;
    /** 상대가 웹에서 찾아 보낸 사진이면 찾을 때 쓴 조건 — "사진 다시 가져오기" 가 쓴다 (WebPicture.refetch) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String pictureSearch;

    /** Jackson 이 쓰는 빈 생성자 */
    public Line() {
    }

    public Line(boolean mine, String text, long at) {
      this.mine = mine;
      this.text = text;
      this.at = at;
    }
  }

  /** 파일에 남겨둘 최대 줄 수. 넘치면 오래된 것부터 버린다 */
  private static final int MAX_LINES = 300;

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();

  public ChatHistory(String path) {
    this.path = path;
  }

  /**
   * 남아 있는 대화를 친 순서대로 읽어온다.
   * 파일이 없거나 깨졌으면 빈 목록을 준다 — 지난 대화 하나 못 읽었다고 수다까지 못 열면 곤란해서.
   */
  public List<Line> load() {
    List<Line> lines = new ArrayList<>();
    LateWriter.settle(path);
    File file = new File(path);
    if (!file.exists())
      return lines;

    try {
      List<Line> loaded = mapper.readValue(file, new TypeReference<List<Line>>() {
      });
      for (Line line : loaded) {
        if (line == null || line.text == null || line.text.isBlank())
          continue;
        // 시각이 없는 줄(손으로 고쳤거나 옛날 파일)은 1970년으로 읽혀서 날짜 구분선이 엉킨다.
        // 언제였는지는 이제 알 길이 없으니 방금 읽은 때로 쳐준다.
        if (line.at <= 0)
          line.at = System.currentTimeMillis();
        lines.add(line);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return lines;
  }

  /** 지금까지 오간 말을 통째로 덮어쓴다. 내용은 지금 굽고, 파일엔 뒤에서 쓴다 (LateWriter) */
  public void save(List<Line> lines) {
    try {
      List<Line> keep = lines.size() <= MAX_LINES ? lines
          : lines.subList(lines.size() - MAX_LINES, lines.size());
      LateWriter.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new ArrayList<>(keep)));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
