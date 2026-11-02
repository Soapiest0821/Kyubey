package kyubey.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 파일 쓰기를 화면 스레드 밖으로 미뤄 두는 곳. 말풍선 하나 붙을 때마다 chat.json · presence.json 을
 * 통째로 덮어쓰는데, 그걸 화면 스레드에서 하면 디스크(와 백신 검사)가 굼뜬 만큼 화면이 멎는다.
 *
 * 내용은 부르는 쪽에서 바이트로 다 구워서 넘긴다 — 쓰는 사이 화면 쪽에서 줄을 고쳐도 엉키지 않게.
 * 같은 파일에 쓸 게 밀려 있으면 마지막 것만 쓴다. 꺼질 때(System.exit 포함) 밀린 건 다 쓰고 끈다.
 * 밀린 파일을 읽기 전엔 settle 로 먼저 써둔다 — 안 그러면 방금 남긴 게 빠진 옛 파일을 읽는다.
 */
public final class LateWriter {

  /** 아직 못 쓴 파일 → 마지막으로 넘겨받은 내용 */
  private static final Map<String, byte[]> pending = new HashMap<>();

  /** 쓰기는 한 번에 하나씩 — 같은 파일에 옛 내용이 새 내용을 덮지 않게 */
  private static final Object WRITE = new Object();

  private static final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "late-writer");
    thread.setDaemon(true);
    return thread;
  });

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(LateWriter::settleAll, "late-writer-flush"));
  }

  private LateWriter() {
  }

  /** path 에 bytes 를 곧 쓴다. 그 전에 또 오면 앞의 것은 버리고 이것만 */
  public static void write(String path, byte[] bytes) {
    boolean fresh;
    synchronized (pending) {
      fresh = pending.put(path, bytes) == null;
    }
    if (fresh)
      worker.execute(() -> settle(path));
  }

  /** path 에 밀린 게 있으면 지금 이 자리에서 쓴다 */
  public static void settle(String path) {
    synchronized (WRITE) {
      byte[] bytes;
      synchronized (pending) {
        bytes = pending.remove(path);
      }
      if (bytes != null)
        writeNow(path, bytes);
    }
  }

  private static void settleAll() {
    List<String> paths;
    synchronized (pending) {
      paths = new ArrayList<>(pending.keySet());
    }
    for (String path : paths)
      settle(path);
  }

  private static void writeNow(String path, byte[] bytes) {
    try {
      Path file = Path.of(path);
      if (file.getParent() != null)
        Files.createDirectories(file.getParent());
      Files.write(file, bytes);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
