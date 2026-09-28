package kyubey.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * 내가 언제 들렀고 언제 수다를 읽었는지. 마도카가 먼저 말을 걸 때 이걸 보고 말을 고른다.
 *
 * 둘 다 껐다 켜도 이어져야 한다 — 먼저 말 거는 건 다섯 시간이 지나서야 걸려서, 그 사이에
 * 위젯을 한 번쯤 껐다 켜는 게 보통이라 메모리에만 들고 있으면 매번 "처음 온 사람" 이 된다.
 *
 * readAt 은 수다 화면을 마지막으로 보고 있던 때다. 마지막 줄보다 뒤면 다 읽은 것이고,
 * 앞이면 내가 없는 사이에 붙은 말이 남아 있다는 뜻이다 (ChatPane.readAll 참고).
 *
 * focusAt 은 위젯 창이 마지막으로 포커스를 받은 때다. 화면이 어디든 창만 불렀으면 찍힌다.
 */
public class Presence {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 수다 화면을 마지막으로 보고 있던 때 (epoch millis, 0 이면 모름) */
    public long readAt;
    /** 위젯 창이 마지막으로 포커스를 받은 때 (epoch millis, 0 이면 모름) */
    public long focusAt;

    /** Jackson 이 쓰는 빈 생성자 */
    public Snapshot() {
    }
  }

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();

  public Presence(String path) {
    this.path = path;
  }

  /** 파일이 없거나 깨졌으면 둘 다 0 — 모르는 것으로 친다 */
  public Snapshot load() {
    File file = new File(path);
    if (!file.exists())
      return new Snapshot();

    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      return loaded == null ? new Snapshot() : loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  public void save(long readAt, long focusAt) {
    Snapshot snapshot = new Snapshot();
    snapshot.readAt = readAt;
    snapshot.focusAt = focusAt;

    try {
      File file = new File(path);
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
