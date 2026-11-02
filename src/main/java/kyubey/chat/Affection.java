package kyubey.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import kyubey.todo.TodoStore;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 마도카가 나한테 품은 호감도. 0 에서 시작해서 오간 말에 따라 오르기도 내리기도 한다.
 *
 * 마도카가 답할 때마다 끝에 [[호감도:+1]] 같은 표시를 몰래 달아 보내면 ChatPane 이 그걸 떼어내
 * 여기 더한다 (ChatPane.takeAffection). 지금 값은 대화에 "호감도" 라는 말이 나온 티키타카에서만
 * 마도카한테 알려준다 (ChatPane.withAffection).
 *
 * 오르내릴 때마다 마도카가 같이 적어 보낸 한마디 이유도 남긴다 ([[호감도:+1|헤헤 고마워!]]).
 * 머리말 호감도를 두 번 누르면 AffectionDialog 가 이걸 펼쳐 보여준다.
 *
 * 껐다 켜도 이어져야 해서 파일에 남긴다. 값은 상대한테 묶여 있어서 "채팅 삭제" 를 해도 그대로 남고,
 * 오르내린 기록만 그 대화에 묶여 같이 비워진다 — 보관하고 지우면 그 기록(과 그때 값)이 그 대화 옆에
 * 같이 보관된다 (ChatArchive). 값을 0 으로 되돌리려면 기본 화면에 "호감도 초기화" 를 친다.
 *
 * 파트너면 이유가 바로바로 다 보인다. 파트너가 아니면 오늘(할 일이 바뀌는 아침 TodoStore.DAY_STARTS_AT
 * 시부터) 오르내린 이유는 잠겨 있다가 다음 날 아침에 열린다 — 바뀐 만큼은 하나하나 바로 보인다. 파트너가
 * 끊긴 날은 openUntil 까지 (다음 날 아침까지) 전부 보인다 (revealed, PartnerBond).
 */
public class Affection {

  /** 이유를 몇 개까지 남길지. 넘치면 오래된 것부터 버린다 */
  private static final int HISTORY_MAX = 300;

  /** 호감도가 한 번 오르내린 기록 */
  public static class Change {
    /**
     * 바뀐 때 (epoch ms). 답에 달려 온 표시로 바뀌었으면 그 답의 첫 말풍선 시각과 똑같다 — 기록을 두 번
     * 누르면 이걸로 그 말풍선을 찾아간다 (ChatPane.lineOf). 파트너가 바뀌어 오르내린 건 그냥 그때다
     */
    public long at;
    /** 바뀐 만큼 (+2, -1 …) */
    public int delta;
    /** 바뀐 뒤의 값 */
    public int value;
    /** 마도카 속마음 한마디. 안 적어 왔으면 빈 문자열 */
    public String reason = "";

    /** Jackson 이 쓰는 빈 생성자 */
    public Change() {
    }
  }

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 지금 호감도 */
    public int value;

    /** 오르내린 기록. 오래된 것부터 */
    public List<Change> history = new ArrayList<>();

    /** 이때까지 (epoch ms) 는 오늘 이유도 다 보인다 — 파트너가 되거나 끊긴 날 다음 날 아침까지. 0 이면 없다 */
    public long openUntil;

    /** Jackson 이 쓰는 빈 생성자 */
    public Snapshot() {
    }
  }

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  /** 마지막으로 읽거나 쓴 기록 (load 가 채운다) */
  private final List<Change> history = new ArrayList<>();

  /** Snapshot.openUntil (load 가 채운다) */
  private long openUntil;

  public Affection(String path) {
    this.path = path;
  }

  /** 파일이 없거나 깨졌으면 0 — 처음 만난 사이로 친다 */
  public int load() {
    File file = new File(path);
    if (!file.exists())
      return 0;

    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      history.clear();
      if (loaded != null && loaded.history != null)
        history.addAll(loaded.history);
      openUntil = loaded == null ? 0 : loaded.openUntil;
      return loaded == null ? 0 : loaded.value;
    } catch (IOException e) {
      e.printStackTrace();
      return 0;
    }
  }

  /** 호감도가 delta 만큼 바뀌어 value 가 됐다고 이유와 함께 남긴다 */
  public void change(int value, int delta, String reason) {
    change(value, delta, reason, System.currentTimeMillis());
  }

  /** at 에 바뀐 걸로 남긴다 — 답에 달려 온 표시면 그 답 말풍선의 시각 */
  public void change(int value, int delta, String reason, long at) {
    Change c = new Change();
    c.at = at;
    c.delta = delta;
    c.value = value;
    c.reason = reason == null ? "" : reason.strip();
    history.add(c);
    while (history.size() > HISTORY_MAX)
      history.remove(0);
    save(value);
  }

  /** 0 으로 되돌린다. 지난 이유들도 같이 버린다 — 남겨두면 합이 지금 값과 안 맞아서 */
  public void reset() {
    history.clear();
    save(0);
  }

  /**
   * 값은 두고 오르내린 기록만 버린다. "채팅 삭제" 때 — 기록은 그 대화에 묶여 있어서.
   * 그 뒤로 기록의 합은 지금 값과 안 맞는다. 기록은 이 대화에서 오르내린 것만이라서다.
   */
  public void clearHistory(int value) {
    history.clear();
    save(value);
  }

  /** 지금 값이 value 일 때, until 까지는 오늘 이유도 다 보여준다 (파트너가 되거나 끊길 때) */
  public void openUntil(long until, int value) {
    openUntil = until;
    save(value);
  }

  /**
   * 지금 이유를 볼 수 있는 기록만 참이다. 파트너거나 파트너가 끊긴 날(openUntil 전)이면 전부,
   * 아니면 오늘 아침 TodoStore.DAY_STARTS_AT 시 전에 오르내린 것만
   */
  public Predicate<Change> revealed(boolean partner) {
    if (partner || System.currentTimeMillis() < openUntil)
      return c -> true;
    long dayStart = dayStart(0);
    return c -> c.at < dayStart;
  }

  /** 다음 날 아침 TodoStore.DAY_STARTS_AT 시 (epoch ms) — 오늘 이유가 열리는 때 */
  public static long nextDayStart() {
    return dayStart(1);
  }

  /** 오늘에서 days 날 뒤 아침 TodoStore.DAY_STARTS_AT 시 (epoch ms) */
  private static long dayStart(int days) {
    return TodoStore.today().plusDays(days).atTime(TodoStore.DAY_STARTS_AT, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
  }

  /** 오르내린 기록. 오래된 것부터 */
  public List<Change> history() {
    return List.copyOf(history);
  }

  /** 지금 값이 value 일 때 파일에 담기는 모양 그대로. 대화를 보관하며 지울 때 같이 남기려고 */
  public Snapshot snapshot(int value) {
    Snapshot snapshot = new Snapshot();
    snapshot.value = value;
    snapshot.history = new ArrayList<>(history);
    snapshot.openUntil = openUntil;
    return snapshot;
  }

  private void save(int value) {
    Snapshot snapshot = snapshot(value);

    try {
      File file = new File(path);
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * 수다 화면 머리말에 숫자 앞에 붙는 이모지. 높은 쪽부터 TIERS 칸마다 하나씩 —
   * 300 이상 / 0 ~ 299 / -100 ~ -1 / -100 미만. "설정" 에서 바꿀 수 있다.
   */
  public static final List<String> DEFAULT_EMOJIS = List.of("💕", "🩷", "💔", "💢");

  /** 칸을 가르는 아랫끝. 값이 이 이상이면 그 칸이고, 다 못 넘으면 마지막 칸 */
  private static final int[] TIERS = { 300, 0, -100 };

  /** 지금 값에 맞는 이모지 */
  public static String emoji(int value, List<String> emojis) {
    return emojiAt(tier(value), emojis);
  }

  /** 값이 몇 번째 칸인지 (0 이 제일 높은 칸) */
  public static int tier(int value) {
    for (int i = 0; i < TIERS.length; i++)
      if (value >= TIERS[i])
        return i;
    return TIERS.length;
  }

  /** tier 번째 칸의 이모지. emojis 가 비었거나 그 칸이 비었으면 기본 이모지로 */
  public static String emojiAt(int tier, List<String> emojis) {
    if (emojis != null && tier < emojis.size() && emojis.get(tier) != null && !emojis.get(tier).isBlank())
      return emojis.get(tier).strip();
    return DEFAULT_EMOJIS.get(tier);
  }
}
