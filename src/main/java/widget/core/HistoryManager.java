package widget.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 입력창에 쳐서 실제로 실행한 명령어를 남겨둔다.
 * 빈 입력창에서 ↑(최근) / ↓(최빈) 로 꺼내 쓰는 게 전부라,
 * 같은 명령어는 한 줄로 합쳐 두고 쓴 횟수와 마지막 시각만 갱신한다.
 *
 * top.json 은 "어떤 키워드로 어느 경로를 골랐나"를 세는 곳이라 결이 다르다.
 * 여긴 친 줄 자체("yt 고양이")를 통째로 기억한다.
 */
public class HistoryManager {

  public static class Entry {
    public String cmd;
    public int count;
    /** 마지막으로 쓴 시각 (epoch millis) */
    public long last;
  }

  /** 파일에 남겨둘 최대 줄 수. 넘치면 오래된 것부터 버린다 */
  private static final int MAX_ENTRIES = 200;

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();
  /** 명령어 → 기록. 중복 없이 한 줄씩만 들고 있는다 */
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  public HistoryManager(String path) {
    this.path = path;
    load();
  }

  private void load() {
    File file = new File(path);
    if (!file.exists())
      return;
    try {
      List<Entry> loaded = mapper.readValue(file, new TypeReference<List<Entry>>() {
      });
      for (Entry entry : loaded) {
        if (entry.cmd != null && !entry.cmd.isBlank())
          entries.put(entry.cmd, entry);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void save() {
    try {
      File file = new File(path);
      file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(entries.values()));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 명령어 하나를 썼다고 적어둔다. 이미 있던 줄이면 횟수만 올린다 */
  public void record(String rawCmd) {
    if (rawCmd == null)
      return;
    String cmd = rawCmd.trim();
    if (cmd.isEmpty())
      return;

    Entry entry = entries.get(cmd);
    if (entry == null) {
      entry = new Entry();
      entry.cmd = cmd;
      entries.put(cmd, entry);
    }
    entry.count++;
    entry.last = System.currentTimeMillis();

    prune();
    save();
  }

  /**
   * 기록 파일이 아직 없을 때, 예전부터 세어 둔 top.json 을 밑천으로 깐다.
   * 처음 눌렀는데 빈손이면 고장 난 줄 알게 되니까.
   * 언제 썼는지는 top.json 에 없어서 last 는 0 으로 두고, 최근 목록에선 맨 아래에 둔다.
   */
  public void seedFrom(Map<String, Map<String, Integer>> freqData) {
    if (!entries.isEmpty() || freqData == null || freqData.isEmpty())
      return;

    for (Map.Entry<String, Map<String, Integer>> row : freqData.entrySet()) {
      String cmd = row.getKey() == null ? "" : row.getKey().trim();
      if (cmd.isEmpty() || row.getValue() == null)
        continue;

      int count = row.getValue().values().stream()
          .filter(Objects::nonNull)
          .mapToInt(Integer::intValue)
          .sum();
      if (count <= 0)
        continue;

      Entry entry = new Entry();
      entry.cmd = cmd;
      entry.count = count;
      entries.put(cmd, entry);
    }

    if (!entries.isEmpty()) {
      prune();
      save();
    }
  }

  /** 최근에 쓴 순서. 밑천으로 깔린 줄(시각 모름)끼리는 많이 쓴 게 위로 */
  public List<Entry> recent(int limit) {
    return sorted(Comparator.comparingLong((Entry entry) -> -entry.last)
        .thenComparingInt(entry -> -entry.count), limit);
  }

  /** 많이 쓴 순서. 횟수가 같으면 최근 것을 위로 */
  public List<Entry> frequent(int limit) {
    return sorted(Comparator.comparingInt((Entry entry) -> -entry.count)
        .thenComparingLong(entry -> -entry.last), limit);
  }

  private List<Entry> sorted(Comparator<Entry> order, int limit) {
    return entries.values().stream()
        .sorted(order)
        .limit(limit)
        .collect(Collectors.toList());
  }

  private void prune() {
    if (entries.size() <= MAX_ENTRIES)
      return;

    List<Entry> oldestFirst = new ArrayList<>(entries.values());
    oldestFirst.sort(Comparator.comparingLong(entry -> entry.last));
    int over = oldestFirst.size() - MAX_ENTRIES;
    for (int i = 0; i < over; i++)
      entries.remove(oldestFirst.get(i).cmd);
  }
}
