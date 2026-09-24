package widget.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

  /** 지금까지 오간 말을 통째로 덮어쓴다 */
  public void save(List<Line> lines) {
    try {
      File file = new File(path);
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      List<Line> keep = lines.size() <= MAX_LINES ? lines
          : lines.subList(lines.size() - MAX_LINES, lines.size());
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(keep));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
