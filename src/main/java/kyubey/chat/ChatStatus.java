package kyubey.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 수다 상대의 상태 메시지. 멤버 탭(ChatSide)과 프로필 팝업(ProfileCard)에 뜬다.
 *
 * 상대가 직접 쓴다 (ChatPane.maybeRewriteStatus). 마지막으로 쓴 지 GAP 이 지나기 전엔 안 바꾸고,
 * 지난 뒤로는 하루에 한 번 CHANCE 확률로 굴려서 — 정해진 날마다 바뀌면 기계 같아서 — 새로 쓴다.
 * 아직 한 번도 안 썼으면 굴리지 않고 바로 쓴다. 실패해도 RETRY 가 지나야 다시 부른다.
 *
 * 상대마다 json/partners/{id}/status.json 에 남는다.
 */
public class ChatStatus {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    public String text = "";
    /** 지금 글을 쓴 때 (epoch millis) */
    public long at;
    /** 마지막으로 다시 쓸지 굴려본 때 (epoch millis) */
    public long rolledAt;
  }

  private static final long DAY = 24L * 60 * 60 * 1000;
  /** 한 번 쓰면 이만큼은 그대로 둔다 */
  private static final long GAP = 3 * DAY;
  /** 그 뒤 다시 쓸지 굴리는 간격 */
  private static final long ROLL_EVERY = DAY;
  /** 한 번 굴릴 때 다시 쓸 확률 */
  private static final double CHANCE = 0.3;
  /** 아직 글이 없는데 실패했을 때 다시 부르기까지 */
  private static final long RETRY = 60L * 60 * 1000;

  private final File file;
  private final ObjectMapper mapper = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final Snapshot current;

  public ChatStatus(String path) {
    file = new File(path);
    current = load();
  }

  private Snapshot load() {
    if (!file.isFile())
      return new Snapshot();
    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      if (loaded == null)
        return new Snapshot();
      if (loaded.text == null)
        loaded.text = "";
      return loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  private void save() {
    try {
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, current);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public String text() {
    return current.text;
  }

  /** 지금 다시 쓸 차례인지 굴려본다. 굴린 때를 남겨서 같은 날 또 안 굴린다 */
  public boolean roll(long now) {
    boolean first = current.text.isBlank();
    if (first ? now - current.rolledAt < RETRY : now - current.at < GAP || now - current.rolledAt < ROLL_EVERY)
      return false;
    current.rolledAt = now;
    save();
    return first || ThreadLocalRandom.current().nextDouble() < CHANCE;
  }

  /** 새로 쓴 글을 남긴다 */
  public void write(String text, long now) {
    current.text = text;
    current.at = now;
    save();
  }
}
