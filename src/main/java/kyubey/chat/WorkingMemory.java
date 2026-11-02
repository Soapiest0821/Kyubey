package kyubey.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마도카가 오래 들고 가는 기억 — 지난 대화에서 추려둔 메모 몇 줄.
 *
 * chat.json 은 오간 말을 그대로 남기는 곳이라, 그걸 매번 통째로 다시 보내면
 * 대화가 길어질수록 값이 계속 붙는다. 그래서 모델한테는 최근 몇 줄만 원문으로 주고
 * (ChatPane.WINDOW_LINES), 그보다 앞의 얘기는 여기 적힌 메모로 대신 들려준다 —
 * "9월 21일 발표 있음", "고양이 두 마리 키움" 같은, 오래 알아둘 것들만.
 *
 * foldedUpTo 는 어디까지 메모에 접어 넣었는지 표시하는 자리다.
 * 줄 번호가 아니라 시각(epoch millis)으로 세는데, chat.json 이 300줄에서 앞을 잘라내서
 * 번호는 자꾸 밀리기 때문이다. 껐다 켠 뒤에 같은 대화를 또 요약해서 호출을 낭비하지
 * 않으려면 줄 자체에 붙어 있는 시각을 보는 편이 맞다.
 *
 * 메모는 통째로 덮어쓴다. 새 대화를 접어 넣는 건 기존 메모에 한 줄 보태는 게 아니라
 * 모델이 메모 전문을 다시 써주는 일이라서 (오래돼서 틀린 게 된 줄을 고치려면 그래야 한다).
 *
 * 줄마다 언제까지 기억할지 꼬리표가 붙는다 — "- [~2026-10-25] 시험 기간임", "- [평생] 이름: 정성호".
 * "~을 완료함" 같은 그날뿐인 얘기가 자리를 차지하고 눌러앉지 않게, 날짜가 지난 줄은 읽을 때 지운다.
 * 꼬리표가 없는 줄(예전 메모)은 안 지우고 두고, 다음에 접을 때 모델이 붙이게 한다.
 */
public class WorkingMemory {

  /** 모델이 꼬리표를 빼먹은 줄은 이만큼만 들고 간다 */
  public static final int DEFAULT_DAYS = 7;

  /** "- [평생] …" / "- [~2026-10-25] …" / "- [~10-25] …" (해를 빼먹으면 올해로 친다) */
  private static final Pattern TAG = Pattern.compile(
      "^-?\\s*\\[\\s*(평생|~\\s*(?:(\\d{4})-)?(\\d{1,2})-(\\d{1,2}))\\s*]\\s*(.*)$");

  /**
   * 읽어온 기억. 파일에는 memo 가 줄마다 배열 한 칸씩 들어가고, 여기선 "\n" 으로 이은 글 하나로 다룬다.
   * 예전 파일처럼 memo 가 글 하나로 들어 있어도 그대로 읽는다.
   */
  public static class Snapshot {
    /** 기억해 둔 것. 한 줄에 하나씩 */
    public String memo = "";
    /** 이 시각까지 오간 말은 memo 에 들어갔다 (epoch millis) */
    public long foldedUpTo;
  }

  /** 메모 한 줄을 뜯어본 것. until 이 null 이면 평생, tagged 가 false 면 꼬리표가 없던 줄 */
  private record Entry(String text, LocalDate until, boolean tagged) {
  }

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();

  public WorkingMemory(String path) {
    this.path = path;
  }

  /**
   * 적어둔 메모를 읽어온다. 날짜가 지난 줄은 여기서 지우고 파일에도 지운 채로 다시 써둔다.
   * 파일이 없거나 깨졌으면 빈 메모를 준다 — 기억 하나 못 읽었다고 수다까지 못 열면 곤란해서.
   * 빈 메모로 시작하면 foldedUpTo 도 0 이라 지난 대화를 처음부터 다시 접어 넣게 된다.
   */
  public Snapshot load() {
    File file = new File(path);
    if (!file.exists())
      return new Snapshot();

    try {
      JsonNode root = mapper.readTree(file);
      if (root == null || !root.isObject())
        return new Snapshot();
      Snapshot loaded = new Snapshot();
      JsonNode memo = root.path("memo");
      if (memo.isArray()) {
        List<String> lines = new ArrayList<>();
        for (JsonNode line : memo)
          lines.add(line.asText());
        loaded.memo = String.join("\n", lines);
      } else if (memo.isTextual()) {
        loaded.memo = memo.asText();
      }
      loaded.foldedUpTo = root.path("foldedUpTo").asLong();
      String kept = prune(loaded.memo, LocalDate.now());
      if (!kept.equals(loaded.memo)) {
        loaded.memo = kept;
        save(kept, loaded.foldedUpTo);
      }
      return loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  /**
   * 메모 끝에 한 줄 보탠다. 접어 넣은 시각은 그대로 — 대화 밖에서 생긴 일이라서 (파트너가 바뀔 때, PartnerBond).
   * 다음에 접을 때 모델이 메모 전문을 다시 쓰면서 이 줄도 같이 들고 간다. until 이 null 이면 평생.
   */
  public void append(String line, LocalDate until) {
    Snapshot saved = load();
    String memo = saved.memo.strip();
    save((memo.isEmpty() ? "" : memo + "\n") + format(new Entry(line, until, true)), saved.foldedUpTo);
  }

  /** 새로 쓴 메모와 거기까지 접어 넣은 시각을 통째로 덮어쓴다 */
  public void save(String memo, long foldedUpTo) {
    ObjectNode snapshot = mapper.createObjectNode();
    ArrayNode lines = snapshot.putArray("memo");
    for (String line : lines(memo))
      lines.add(line);
    snapshot.put("foldedUpTo", foldedUpTo);

    try {
      File file = new File(path);
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** today 보다 앞선 날짜가 붙은 줄을 뺀다. "[~10-21]" 은 10월 21일까지 남고 22일에 지워진다 */
  public static String prune(String memo, LocalDate today) {
    List<String> kept = new ArrayList<>();
    for (String line : lines(memo)) {
      Entry entry = parse(line, today);
      if (entry.until == null || !entry.until.isBefore(today))
        kept.add(line);
    }
    return String.join("\n", kept);
  }

  /**
   * 모델이 새로 써준 메모를 저장할 모양으로 다듬는다.
   * 꼬리표가 없는 줄엔 DEFAULT_DAYS 뒤까지로 붙이고, 이미 지난 날짜를 붙여 온 줄은 뺀다.
   */
  public static String stamp(String memo, LocalDate today) {
    List<String> out = new ArrayList<>();
    for (String line : lines(memo)) {
      Entry entry = parse(line, today);
      if (!entry.tagged)
        entry = new Entry(entry.text, today.plusDays(DEFAULT_DAYS), true);
      if (entry.until != null && entry.until.isBefore(today))
        continue;
      out.add(format(entry));
    }
    return String.join("\n", out);
  }

  /** 마도카 성격문에 붙여줄 모양 — 지난 줄은 빼고 꼬리표는 떼서 "- 시험 기간임" 처럼 */
  public static String plain(String memo, LocalDate today) {
    List<String> out = new ArrayList<>();
    for (String line : lines(prune(memo, today)))
      out.add("- " + parse(line, today).text);
    return String.join("\n", out);
  }

  private static List<String> lines(String memo) {
    List<String> out = new ArrayList<>();
    if (memo == null)
      return out;
    for (String line : memo.split("\n")) {
      String trimmed = line.strip();
      if (!trimmed.isEmpty())
        out.add(trimmed);
    }
    return out;
  }

  private static Entry parse(String line, LocalDate today) {
    Matcher m = TAG.matcher(line);
    if (m.matches()) {
      if (m.group(1).equals("평생"))
        return new Entry(m.group(5).strip(), null, true);
      try {
        int year = m.group(2) == null ? today.getYear() : Integer.parseInt(m.group(2));
        LocalDate until = LocalDate.of(year, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        return new Entry(m.group(5).strip(), until, true);
      } catch (DateTimeException ignored) {
        // 13월 같은 날짜는 꼬리표가 없던 셈 친다
      }
    }
    String text = line.startsWith("-") ? line.substring(1).strip() : line;
    return new Entry(text, null, false);
  }

  private static String format(Entry entry) {
    if (!entry.tagged)
      return "- " + entry.text;
    return "- [" + (entry.until == null ? "평생" : "~" + entry.until) + "] " + entry.text;
  }
}
