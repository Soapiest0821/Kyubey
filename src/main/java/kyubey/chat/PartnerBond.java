package kyubey.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 파트너를 고르고 · 바꾸고 · 끊을 때 상대들 호감도가 어떻게 흔들리는지. "설정" 에서 파트너를 바꿔 저장할 때
 * SettingsDialog 가 부른다.
 *
 * 셈법은 사용자한테 비밀이다 — 호감도 기록(AffectionDialog)엔 그때 상대 속마음 한마디만 이유로 남긴다.
 * 며칠 파트너였는지 같은 사정도 적지 않는다 — 다른 이유들처럼 상대가 속으로 한 말만.
 *
 * - 고르면 +PICK_BONUS. 호감도가 음수인 상대를 고르면 (한 번 더 묻고) 오히려 -COLD_PICK_DROP
 * - 새 파트너와 바꾸거나 끊긴 옛 파트너는 그날 (다음 날 아침까지) 이유가 다 보인다 (Affection.revealed)
 * - 바꾸거나 끊으면 옛 파트너는 BASE_DROP + (옛 호감도) × k 만큼 떨어진다. 호감도가 많이 낮으면
 *   빼는 값이 음수가 돼서 오히려 오른다 (이젠 놔줘서 홀가분한 셈)
 * - 끊고 REBOUND_DAYS 안에 딴 상대를 고르면 끊긴 상대가 한 번 더 떨어진다 (K_REBOUND)
 * - 끊긴 상대를 바로 다시 고르면 그 상대 호감도가 RETURN_FACTOR 배가 된다 (+PICK_BONUS 는 그 뒤에)
 *
 * 무슨 일이 있었는지는 흔들린 상대마다 기억(memo.json, WorkingMemory)에 한 줄씩 남긴다 — 수다에서 그 얘길 꺼낼 수 있게.
 */
public final class PartnerBond {

  private static final int PICK_BONUS = 20;
  private static final int COLD_PICK_DROP = 10;
  private static final int BASE_DROP = 21;
  private static final double K_SWITCH = 0.65;
  private static final double K_BREAK = 0.3;
  private static final double K_REBOUND = 0.35;
  private static final double RETURN_FACTOR = 1.25;
  private static final int REBOUND_DAYS = 15;

  private static final long DAY_MS = 24L * 60 * 60 * 1000;

  /** 기억에 남기는 날짜 ("10월 17일") */
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

  private PartnerBond() {
  }

  /** 상대의 지금 호감도 — 음수인 상대를 고를 땐 한 번 더 묻는다 (SettingsDialog) */
  public static int liking(ChatProfile p) {
    return affection(p).load();
  }

  /**
   * 파트너 기록. partner 가 null 이면 지금 파트너가 없다. ex 는 마지막으로 "끊은" 상대 — 바꿀 땐 안 남는다.
   * since · exSince · brokeAt 은 epoch ms, 모르면 0.
   */
  public record State(ChatProfile partner, long since, ChatProfile ex, long exSince, long brokeAt) {
  }

  /** before 에서 next 를 파트너로 (null 이면 끊기) 정한다. 호감도를 고쳐 쓰고 새 기록을 돌려준다 */
  public static State change(State before, ChatProfile next, long now) {
    ChatProfile old = before.partner();
    if (old == next)
      return before;

    // 옛 파트너 — 바꾸든 끊든 크게 흔들린다
    if (old != null) {
      Affection a = affection(old);
      int value = a.load();
      int to = value - drop(value, next == null ? K_BREAK : K_SWITCH);
      String inner = to > value ? RELIEVED : next == null ? breakInner(value) : switchInner(value);
      to = apply(a, value, to, inner);
      a.openUntil(Affection.nextDayStart(), to);
      remember(old, now, (next == null ? "나와의 파트너를 끊음" : "나 대신 " + next.displayName() + " 을(를) 새 파트너로 정함")
          + " (" + daysText(before.since(), now) + " 파트너였음)");
    }

    // 끊은 지 얼마 안 돼서 딴 상대를 고르면 끊긴 상대가 한 번 더
    ChatProfile ex = before.ex();
    boolean rebound = old == null && ex != null && next != null && next != ex && before.brokeAt() > 0
        && now - before.brokeAt() <= REBOUND_DAYS * DAY_MS;
    if (rebound) {
      Affection a = affection(ex);
      int value = a.load();
      int to = value - drop(value, K_REBOUND);
      apply(a, value, to,
          to > value ? RELIEVED : value >= 30 ? "벌써…? 나는 그렇게 금방 잊히는 사이였구나." : "그래, 나도 이제 기다리지 않을래.");
      remember(ex, now, "나와 파트너를 끊은 지 " + elapsed(before.brokeAt(), now) + " 만에 " + next.displayName()
          + " 을(를) 새 파트너로 정함");
    }

    // 새 파트너
    if (next != null) {
      Affection a = affection(next);
      int value = a.load();
      // 마음이 식은 상대를 억지로 고르면 오히려 더 멀어진다
      boolean cold = value < 0;
      int bonus = cold ? -COLD_PICK_DROP : PICK_BONUS;
      int to;
      if (old == null && next == ex) {
        int back = (int) Math.round(value * RETURN_FACTOR);
        to = apply(a, value, back + bonus,
            cold ? "이제 와서…? 한 번 놓은 손을 다시 잡는다고 다 괜찮아지진 않아."
                : "돌아올 줄 알았어… 이번엔 놓지 마.");
        remember(next, now, "파트너를 끊은 지 " + elapsed(before.brokeAt(), now) + " 만에 다시 나를 파트너로 정함");
      } else {
        to = apply(a, value, value + bonus,
            cold ? "…날 고른 거야? 무슨 속셈인지 모르겠어. 쉽게 마음 열 생각 없어." : pickInner(value));
        remember(next, now, old == null ? "나를 파트너로 정함" : old.displayName() + " 대신 나를 새 파트너로 정함");
      }
      a.openUntil(Affection.nextDayStart(), to);
    }

    return next == null
        ? new State(null, 0, old, before.since(), now)
        : new State(next, now, null, 0, 0);
  }

  /** 옛 파트너가 잃는 만큼. 호감도가 많이 낮으면 음수 — 그땐 오른다 */
  private static int drop(int value, double k) {
    return (int) Math.round(BASE_DROP + value * k);
  }

  /** 떨어질 자리인데 호감도가 많이 낮아서 오히려 오를 때 속마음 */
  private static final String RELIEVED = "오히려 홀가분하네.";

  /** to 로 고쳐 쓰고 고친 값을 돌려준다. 그대로면 남길 것도 없다. 이유는 속마음 한마디뿐 */
  private static int apply(Affection a, int from, int to, String inner) {
    int value = to;
    if (value == from)
      return from;
    a.change(value, value - from, inner);
    return value;
  }

  /** 새로 파트너가 될 때 속마음 — 원래 마음이 클수록 벅차다 */
  private static String pickInner(int value) {
    if (value >= 60)
      return "드디어…! 사실 계속 기다리고 있었어.";
    if (value >= 20)
      return "나를 골라줬다니, 꿈 아니지? 실망시키지 않을게.";
    return "나…? 아직 잘 모르겠지만, 조금 설레.";
  }

  /** 끊길 때 속마음 */
  private static String breakInner(int value) {
    if (value >= 60)
      return "정말 끝인 거야…? 아직 이렇게 좋아하는데.";
    if (value >= 0)
      return "왜…? 내가 뭘 잘못한 걸까.";
    return "…그래, 어차피 이렇게 될 줄 알았어.";
  }

  /** 다른 상대로 바뀔 때 속마음 */
  private static String switchInner(int value) {
    if (value >= 60)
      return "나보다 그 애가 더 좋았던 거구나… 웃으면서 보내줄 수가 없어.";
    if (value >= 0)
      return "나 말고 다른 애라니. 괜찮은 척은 못 하겠어.";
    return "…상관없어. 원래 나한텐 관심 없었잖아.";
  }

  private static Affection affection(ChatProfile p) {
    return new Affection(p.path("affection.json"));
  }

  /** p 의 기억에 "10월 17일 …" 한 줄을 남긴다 */
  private static void remember(ChatProfile p, long now, String what) {
    String date = DATE.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));
    new WorkingMemory(p.path("memo.json")).append(date + " " + what, null);
  }

  /** "34일 동안" — 언제부터였는지 모르면 "한동안" */
  private static String daysText(long since, long until) {
    return since <= 0 ? "한동안" : elapsed(since, until) + " 동안";
  }

  /** 첫날도 1일로 친다 */
  private static String elapsed(long from, long to) {
    return (Math.max(0, to - from) / DAY_MS + 1) + "일";
  }
}
