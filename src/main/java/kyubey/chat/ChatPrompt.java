package kyubey.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * 마도카한테 입혀주는 성격문과, 뜸할 때 먼저 말을 걸라고 끼워 넣는 안내문을 손으로 고쳐 쓴 것.
 * 기본 화면에 "채팅 프롬프트" 를 치면 고친다.
 *
 * 비어 있으면 고친 적이 없다는 뜻이라 ChatPane 에 박혀 있는 기본값(PERSONA, NUDGE)이 그대로 간다.
 * 둘은 따로 센다 — 하나만 고쳐도 나머지는 기본값을 따라간다.
 * 기본값을 파일에 미리 써두지 않는 건, 그러면 코드에서 기본 문구를 다듬어도
 * 한 번도 안 고친 사람까지 옛날 문구에 묶여버려서다.
 *
 * prompt.json 은 저장소에 안 올라가서 (.gitignore) 새로 받은 사람한테는 없다.
 * 처음 켤 때 load 가 빈 성격문으로 파일을 만들어 둔다 — 비어 있으니 기본값을 따라가는 건 그대로다.
 */
public class ChatPrompt {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 고쳐 쓴 성격문. 비었으면 기본값을 쓴다 */
    public String persona = "";
    /** 고쳐 쓴 안내문. 비었으면 기본값을 쓴다 */
    public String nudge = "";

    /** Jackson 이 쓰는 빈 생성자 */
    public Snapshot() {
    }
  }

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();

  public ChatPrompt(String path) {
    this.path = path;
  }

  /**
   * 고쳐 둔 성격문과 안내문. 파일이 깨졌으면 둘 다 빈 문자열 — 기본값으로 돌아가면 그만이라서.
   * 파일이 아예 없으면 (처음 켠 것) 빈 채로 만들어 둔다. 안내문이 생기기 전 파일엔 nudge 가
   * 없는데, 그땐 빈 문자열로 읽혀서 기본 안내문을 따라간다.
   */
  public Snapshot load() {
    File file = new File(path);
    if (!file.exists()) {
      save("", "");
      return new Snapshot();
    }

    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      if (loaded == null)
        return new Snapshot();
      if (loaded.persona == null)
        loaded.persona = "";
      if (loaded.nudge == null)
        loaded.nudge = "";
      return loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  /** 통째로 덮어쓴다. 빈 문자열을 넘기면 그쪽은 기본값으로 돌아간 것으로 친다 */
  public void save(String persona, String nudge) {
    Snapshot snapshot = new Snapshot();
    snapshot.persona = persona == null ? "" : persona;
    snapshot.nudge = nudge == null ? "" : nudge;

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
