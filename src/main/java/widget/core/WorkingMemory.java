package widget.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * 마도카가 오래 들고 가는 기억 — 지난 대화에서 추려둔 메모 몇 줄.
 *
 * chat.json 은 오간 말을 그대로 남기는 곳이라, 그걸 매번 통째로 다시 보내면
 * 대화가 길어질수록 값이 계속 붙는다. 그래서 모델한테는 최근 몇 줄만 원문으로 주고
 * (ChatPane.WINDOW_TURNS), 그보다 앞의 얘기는 여기 적힌 메모로 대신 들려준다 —
 * "9월 21일 발표 있음", "고양이 두 마리 키움" 같은, 오래 알아둘 것들만.
 *
 * foldedUpTo 는 어디까지 메모에 접어 넣었는지 표시하는 자리다.
 * 줄 번호가 아니라 시각(epoch millis)으로 세는데, chat.json 이 300줄에서 앞을 잘라내서
 * 번호는 자꾸 밀리기 때문이다. 껐다 켠 뒤에 같은 대화를 또 요약해서 호출을 낭비하지
 * 않으려면 줄 자체에 붙어 있는 시각을 보는 편이 맞다.
 *
 * 메모는 통째로 덮어쓴다. 새 대화를 접어 넣는 건 기존 메모에 한 줄 보태는 게 아니라
 * 모델이 메모 전문을 다시 써주는 일이라서 (오래돼서 틀린 게 된 줄을 고치려면 그래야 한다).
 */
public class WorkingMemory {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 기억해 둔 것. 한 줄에 하나씩 */
    public String memo = "";
    /** 이 시각까지 오간 말은 memo 에 들어갔다 (epoch millis) */
    public long foldedUpTo;

    /** Jackson 이 쓰는 빈 생성자 */
    public Snapshot() {
    }
  }

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();

  public WorkingMemory(String path) {
    this.path = path;
  }

  /**
   * 적어둔 메모를 읽어온다.
   * 파일이 없거나 깨졌으면 빈 메모를 준다 — 기억 하나 못 읽었다고 수다까지 못 열면 곤란해서.
   * 빈 메모로 시작하면 foldedUpTo 도 0 이라 지난 대화를 처음부터 다시 접어 넣게 된다.
   */
  public Snapshot load() {
    File file = new File(path);
    if (!file.exists())
      return new Snapshot();

    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      if (loaded == null)
        return new Snapshot();
      if (loaded.memo == null)
        loaded.memo = "";
      return loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  /** 새로 쓴 메모와 거기까지 접어 넣은 시각을 통째로 덮어쓴다 */
  public void save(String memo, long foldedUpTo) {
    Snapshot snapshot = new Snapshot();
    snapshot.memo = memo == null ? "" : memo;
    snapshot.foldedUpTo = foldedUpTo;

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
