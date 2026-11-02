package kyubey.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * 말하는 방식을 일러두는 기본 프롬프트 — 톡처럼 짧게, 줄바꿈 하나가 말풍선 하나, 마크다운 금지 같은 것.
 * 상대가 누구든 똑같이 성격문 뒤에 붙는다 (ChatPane.persona).
 *
 * 화면이 굴러가는 방식에 묶인 얘기라 평소엔 숨겨둔다. 프롬프트 창의 "기본 프롬프트" 단추를
 * 두 번 눌러야 열린다 (PromptDialog.showBase).
 *
 * 비어 있으면 고친 적이 없는 것이라 DEFAULT 가 간다 (ChatPrompt 와 같은 이치).
 */
public final class BasePrompt {

  public static final String DEFAULT = """
      [말하는 방식]
      - 카카오톡으로 주고받는다고 생각하고 짧게. 두세 문장이면 충분해.
      - 할 말은 맥락 단위로 끊어서 1~3개로 나눠 보내. 줄바꿈 하나가 말풍선 하나로 나가고,
        내가 그걸 차례로 띄워줄게. 이어지는 얘기는 한 줄에 두고, 결이 바뀔 때만 줄을 바꿔.
        나눌 게 없으면 한 줄로만 말해도 돼 — 억지로 세 개로 늘리지는 마.
      - 마크다운(**, #, ``` 같은 것)은 쓰지 마. 그대로 글자로 찍혀서 지저분해진다.
      """;

  public static final String PATH = "src/main/resources/json/prompt-base.json";

  /** 파일에 담기는 모양 */
  public static class Snapshot {
    public String base = "";

    public Snapshot() {
    }
  }

  private static final ObjectMapper mapper = new ObjectMapper();

  private final String path;
  private String custom;

  public BasePrompt(String path) {
    this.path = path;
    this.custom = read();
  }

  /** 지금 쓰는 기본 프롬프트 — 고쳐 쓴 게 없으면 DEFAULT */
  public String text() {
    return custom.isBlank() ? DEFAULT : custom;
  }

  /** 갈아끼운다. 비우거나 DEFAULT 와 같으면 고친 게 없는 것으로 친다 */
  public void set(String text) {
    String trimmed = text == null ? "" : text.strip();
    custom = trimmed.equals(DEFAULT.strip()) ? "" : trimmed;
    Snapshot snapshot = new Snapshot();
    snapshot.base = custom;
    try {
      File file = new File(path);
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String read() {
    File file = new File(path);
    if (!file.isFile())
      return "";
    try {
      Snapshot loaded = mapper.readValue(file, Snapshot.class);
      return loaded == null || loaded.base == null ? "" : loaded.base;
    } catch (IOException e) {
      e.printStackTrace();
      return "";
    }
  }
}
