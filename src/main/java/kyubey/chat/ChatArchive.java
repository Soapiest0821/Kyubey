package kyubey.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
 *
 * 호감도는 대화에 묶여 있어서, 지울 때의 호감도(값과 오르내린 기록)도 같은 이름 옆에
 * .affection.json 으로 같이 남긴다 (20260928-153012.affection.json — 모양은 affection.json 과 같다).
 * 호감도를 묶기 전에 보관한 대화엔 이 파일이 없다.
 */
public class ChatArchive {

  /** 지난 대화 하나 — 어느 파일인지와 그 안의 말들 */
  public static class Entry {
    public final File file;
    public final List<ChatHistory.Line> lines;
    /** 지울 때의 호감도. 호감도를 묶기 전에 보관했거나 못 읽었으면 null */
    public final Affection.Snapshot affection;

    Entry(File file, List<ChatHistory.Line> lines, Affection.Snapshot affection) {
      this.file = file;
      this.lines = lines;
      this.affection = affection;
    }
  }

  /** 대화 파일 옆에 붙는 호감도 파일의 꼬리 */
  private static final String AFFECTION_SUFFIX = ".affection.json";

  /** 상대 폴더 안에서 지운 대화가 쌓이는 곳 (json/partners/{id}/chats_old/). 예전 이름은 chats */
  public static final String DIR = "chats_old";

  private final File dir;
  private final ObjectMapper mapper = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public ChatArchive(String dir) {
    this.dir = new File(dir);
  }

  /**
   * 지우기 직전의 대화를 새 파일로 남긴다. 빈 대화는 남길 게 없어 건너뛴다.
   * chat.json 과 달리 줄 수를 자르지 않는다 — 한 번 쓰고 다시 안 고치는 파일이라서.
   * affection 은 지우기 직전의 호감도. 같은 이름의 .affection.json 으로 옆에 남긴다.
   */
  public void save(List<ChatHistory.Line> lines, Affection.Snapshot affection) {
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
      if (affection != null)
        mapper.writerWithDefaultPrettyPrinter().writeValue(affectionFile(file), affection);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 남겨둔 대화들, 최근에 지운 것부터. 비었거나 못 읽은 파일은 빼고 준다 */
  public List<Entry> list() {
    List<Entry> out = new ArrayList<>();
    File[] files = dir.listFiles((d, name) -> name.endsWith(".json") && !name.endsWith(AFFECTION_SUFFIX));
    if (files == null)
      return out;

    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
    for (File file : files) {
      List<ChatHistory.Line> lines = new ChatHistory(file.getPath()).load();
      if (!lines.isEmpty())
        out.add(new Entry(file, lines, loadAffection(file)));
    }
    return out;
  }

  /** 대화 파일 옆의 호감도. 없거나 깨졌으면 null — 대화는 그래도 볼 수 있게 */
  private Affection.Snapshot loadAffection(File chat) {
    File file = affectionFile(chat);
    if (!file.exists())
      return null;
    try {
      return mapper.readValue(file, Affection.Snapshot.class);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  /** 20260928-153012.json → 20260928-153012.affection.json */
  private static File affectionFile(File chat) {
    String name = chat.getName();
    return new File(chat.getParentFile(), name.substring(0, name.length() - ".json".length()) + AFFECTION_SUFFIX);
  }
}
