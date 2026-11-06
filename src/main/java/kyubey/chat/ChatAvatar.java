package kyubey.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 수다 상대의 프로필 사진. 세워둔 캐릭터 그림은 몸까지 나와서 프사로 쓰기 어려워, 얼굴이 잘 나온 그림을
 * 수다에서 그림을 찾던 길(WebPicture)로 따로 받아둔다. LLM 은 안 타니 토큰이 안 든다.
 *
 * 아직 프사가 없으면 바로 받는다. 있으면 받은 지 GAP 이 지난 뒤로 하루 한 번 CHANCE 확률로 바꾼다.
 * 전에 썼던 그림은 다시 안 쓴다 (Snapshot.used). 못 받으면 있던 프사를 그대로 두고, RETRY 뒤에 다시 해본다.
 *
 * 상대마다 json/partners/{id}/avatar.json 에 기록이, avatar/ 에 그림 한 장이 남는다.
 * 켤 때 Main 이 넷 다 살피고, 수다 화면에 들어올 때 지금 상대를 또 살핀다 — 위젯은 며칠씩 켜두는 일이 많아서.
 */
public final class ChatAvatar {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 지금 프사 파일 자리. 없으면 빈 글 */
    public String path = "";
    /** 지금 프사를 받은 때 (epoch millis) */
    public long at;
    /** 마지막으로 바꿀지 굴려본 때 (epoch millis) */
    public long rolledAt;
    /** 지금까지 쓴 그림 주소 */
    public Set<String> used = new HashSet<>();
  }

  private static final long DAY = 24L * 60 * 60 * 1000;
  /** 한 번 받으면 이만큼은 그대로 둔다 — 보름 */
  private static final long GAP = 15 * DAY;
  /** 그 뒤 바꿀지 굴리는 간격 */
  private static final long ROLL_EVERY = DAY;
  /** 한 번 굴릴 때 바꿀 확률 */
  private static final double CHANCE = 0.1;
  /** 아직 프사가 없는데 못 받았을 때 다시 해보기까지 */
  private static final long RETRY = 60L * 60 * 1000;
  /** 읽어들일 높이 — 카드 지름의 몇 배면 충분하다 */
  private static final double LOAD_HEIGHT = 256;

  /** 지금 받고 있는 상대 — 켤 때 살피는 것과 들어올 때 살피는 게 겹치지 않게 */
  private static final Set<ChatProfile> busy = ConcurrentHashMap.newKeySet();
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private ChatAvatar() {
  }

  /** 이 상대의 프사. 아직 없으면 null */
  static Image image(ChatProfile profile) {
    return ProfileCard.picture(load(profile).path);
  }

  /** 넷 다 살핀다. 프사가 바뀔 때마다 onChanged 를 JavaFX 스레드에서 부른다 */
  public static void refreshAll(Runnable onChanged) {
    start(onChanged, ChatProfile.values());
  }

  /** 이 상대만 살핀다 */
  static void refresh(ChatProfile profile, Runnable onChanged) {
    start(onChanged, profile);
  }

  private static void start(Runnable onChanged, ChatProfile... profiles) {
    Thread worker = new Thread(() -> {
      for (ChatProfile profile : profiles) {
        if (!busy.add(profile))
          continue;
        try {
          if (update(profile))
            Platform.runLater(onChanged);
        } finally {
          busy.remove(profile);
        }
      }
    }, "chat-avatar");
    worker.setDaemon(true);
    worker.start();
  }

  /** 받을 차례면 받는다. 프사가 바뀌었으면 true */
  private static boolean update(ChatProfile profile) {
    Snapshot snapshot = load(profile);
    long now = System.currentTimeMillis();
    if (!roll(snapshot, now))
      return false;
    save(profile, snapshot);

    Set<String> sent = new HashSet<>(snapshot.used);
    String path;
    try {
      path = WebPicture.avatar(profile, profile.path("avatar"), sent);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException | RuntimeException e) {
      System.err.println("[chat] 프사 받기 실패: " + e);
      return false;
    }
    if (path == null)
      return false;

    String old = snapshot.path;
    snapshot.path = path;
    snapshot.at = now;
    snapshot.used = sent;
    save(profile, snapshot);
    if (!old.isBlank())
      ChatImage.delete(old);
    return true;
  }

  /** 지금 받을 차례인지 굴려본다. 굴린 때를 적어둬서 같은 날 또 안 굴린다 */
  private static boolean roll(Snapshot snapshot, long now) {
    boolean first = snapshot.path.isBlank() || !new File(snapshot.path).isFile();
    if (first ? now - snapshot.rolledAt < RETRY : now - snapshot.at < GAP || now - snapshot.rolledAt < ROLL_EVERY)
      return false;
    snapshot.rolledAt = now;
    return first || ThreadLocalRandom.current().nextDouble() < CHANCE;
  }

  private static Snapshot load(ChatProfile profile) {
    File file = new File(profile.path("avatar.json"));
    if (!file.isFile())
      return new Snapshot();
    try {
      Snapshot loaded = MAPPER.readValue(file, Snapshot.class);
      if (loaded == null)
        return new Snapshot();
      if (loaded.path == null)
        loaded.path = "";
      if (loaded.used == null)
        loaded.used = new HashSet<>();
      return loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  private static void save(ChatProfile profile, Snapshot snapshot) {
    try {
      File file = new File(profile.path("avatar.json"));
      file.getParentFile().mkdirs();
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
