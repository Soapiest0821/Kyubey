package kyubey.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * "채팅 삭제" 로 지운 대화를 버리지 않고 한 폴더에 한 파일씩 모아둔다.
 *
 * 지우는 건 마도카가 그 얘기를 잊게 하려는 것이지, 나까지 못 보게 하려는 게 아니라서.
 * 여기 들어간 대화는 다시 마도카한테 들려주지 않는다 — 보기만 한다 (ChatArchiveDialog).
 *
 * 파일 이름은 지운 시각 (20260928-153012.json) 이라 이름 순이 곧 시간 순이다.
 * 한 파일의 모양은 chat.json 과 같아서 읽는 건 ChatHistory 를 그대로 빌려 쓴다.
 */
public class ChatArchive {

  /** 지난 대화 하나 — 어느 파일인지와 그 안의 말들 */
  public static class Entry {
    public final File file;
    public final List<ChatHistory.Line> lines;

    Entry(File file, List<ChatHistory.Line> lines) {
      this.file = file;
      this.lines = lines;
    }
  }

  private final File dir;
  private final ObjectMapper mapper = new ObjectMapper();

  public ChatArchive(String dir) {
    this.dir = new File(dir);
  }

  /**
   * 지우기 직전의 대화를 새 파일로 남긴다. 빈 대화는 남길 게 없어 건너뛴다.
   * chat.json 과 달리 줄 수를 자르지 않는다 — 한 번 쓰고 다시 안 고치는 파일이라서.
   */
  public void save(List<ChatHistory.Line> lines) {
    if (lines.isEmpty())
      return;
    try {
      dir.mkdirs();
      String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
      File file = new File(dir, stamp + ".json");
      // 같은 초에 두 번 지우는 일은 드물지만, 그래도 덮어써서 날리진 않게
      for (int n = 2; file.exists(); n++)
        file = new File(dir, stamp + "-" + n + ".json");
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(lines));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 남겨둔 대화들, 최근에 지운 것부터. 비었거나 못 읽은 파일은 빼고 준다 */
  public List<Entry> list() {
    List<Entry> out = new ArrayList<>();
    File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
    if (files == null)
      return out;

    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
    for (File file : files) {
      List<ChatHistory.Line> lines = new ChatHistory(file.getPath()).load();
      if (!lines.isEmpty())
        out.add(new Entry(file, lines));
    }
    return out;
  }
}
