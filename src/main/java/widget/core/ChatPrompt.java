package widget.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * 마도카한테 입혀주는 성격문을 손으로 고쳐 쓴 것. 기본 화면에 "채팅 프롬프트" 를 치면 고친다.
 *
 * 비어 있으면 고친 적이 없다는 뜻이라 ChatPane 에 박혀 있는 기본 성격문이 그대로 간다.
 * 기본값을 파일에 미리 써두지 않는 건, 그러면 코드에서 기본 성격문을 다듬어도
 * 한 번도 안 고친 사람까지 옛날 문구에 묶여버려서다.
 */
public class ChatPrompt {

  /** 파일에 담기는 모양. Jackson 이 그대로 읽고 쓰게 필드를 열어둔다 */
  public static class Snapshot {
    /** 고쳐 쓴 성격문. 비었으면 기본값을 쓴다 */
    public String persona = "";

    /** Jackson 이 쓰는 빈 생성자 */
    public Snapshot() {
    }
  }

  private final String path;
  private final ObjectMapper mapper = new ObjectMapper();

  public ChatPrompt(String path) {
    this.path = path;
  }

  /** 고쳐 둔 성격문. 파일이 없거나 깨졌으면 빈 문자열 — 기본값으로 돌아가면 그만이라서 */
  public String load() {
    File file = new File(path);
    if (!file.exists())
      return "";

    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      return loaded == null || loaded.persona == null ? "" : loaded.persona;
    } catch (IOException e) {
      e.printStackTrace();
      return "";
    }
  }

  /** 통째로 덮어쓴다. 빈 문자열을 넘기면 기본값으로 돌아간 것으로 친다 */
  public void save(String persona) {
    Snapshot snapshot = new Snapshot();
    snapshot.persona = persona == null ? "" : persona;

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
