package kyubey.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * 수다 상대(ChatProfile) 하나한테 붙인 이름과, 입혀주는 성격문과, 뜸할 때 먼저 말을 걸라고 끼워 넣는 안내문,
 * 전날 못 끝낸 할 일을 재촉하라고, 할 일을 끝냈을 때 칭찬하라고 끼워 넣는 안내문을 손으로 고쳐 쓴 것.
 * 기본 화면에 "채팅 프롬프트" 를 치면 고친다.
 *
 * 상대마다 파일이 따로다 (ChatProfile.path). 말하는 방식을 일러두는 기본 프롬프트는 상대와 상관없이
 * 하나라서 여기 말고 BasePrompt 에 있다.
 *
 * 비어 있으면 고친 적이 없다는 뜻이라 기본값(ChatProfile.defaultName / defaultPersona, ChatPane.NUDGE …)이 그대로 간다.
 * 다섯은 따로 센다 — 하나만 고쳐도 나머지는 기본값을 따라간다.
 * 기본값을 파일에 미리 써두지 않는 건, 그러면 코드에서 기본 문구를 다듬어도
 * 한 번도 안 고친 사람까지 옛날 문구에 묶여버려서다.
 *
 * prompt.json 은 저장소에 안 올라가서 (.gitignore) 새로 받은 사람한테는 없다.
 * 처음 켤 때 load 가 빈 성격문으로 파일을 만들어 둔다 — 비어 있으니 기본값을 따라가는 건 그대로다.
 */
public class ChatPrompt {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 고쳐 쓴 상대 이름. 비었으면 기본 이름 (ChatProfile.defaultName) */
    public String name = "";
    /** 고쳐 쓴 성격문. 비었으면 기본값을 쓴다 */
    public String persona = "";
    /** 고쳐 쓴 안내문. 비었으면 기본값을 쓴다 */
    public String nudge = "";
    /** 고쳐 쓴 할 일 재촉 안내문. 비었으면 기본값을 쓴다 */
    public String todo = "";
    /** 고쳐 쓴 할 일 칭찬 안내문. 비었으면 기본값을 쓴다 */
    public String praise = "";
    /** 고쳐 쓴 시작 멘트. 비었으면 기본값을 쓴다 */
    public String greeting = "";

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
      save(new Snapshot());
      return new Snapshot();
    }

    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      if (loaded == null)
        return new Snapshot();
      if (loaded.name == null)
        loaded.name = "";
      if (loaded.persona == null)
        loaded.persona = "";
      if (loaded.nudge == null)
        loaded.nudge = "";
      if (loaded.todo == null)
        loaded.todo = "";
      if (loaded.praise == null)
        loaded.praise = "";
      if (loaded.greeting == null)
        loaded.greeting = "";
      return loaded;
    } catch (IOException e) {
      e.printStackTrace();
      return new Snapshot();
    }
  }

  /** 고쳐 둔 이름만 슬쩍 본다. 파일이 없어도 만들지 않는다 (파스텔 상대 목록이 넷을 다 훑어서) */
  public String peekName() {
    File file = new File(path);
    if (!file.isFile())
      return "";
    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      return loaded == null || loaded.name == null ? "" : loaded.name.strip();
    } catch (IOException e) {
      return "";
    }
  }

  /** 통째로 덮어쓴다. 빈 문자열인 칸은 기본값으로 돌아간 것으로 친다 */
  public void save(Snapshot snapshot) {
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
