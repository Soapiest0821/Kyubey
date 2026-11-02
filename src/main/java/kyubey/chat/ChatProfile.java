package kyubey.chat;

import kyubey.ui.Theme;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * 수다 상대 한 명. 이름, 나눈 얘기, 접어둔 기억, 프롬프트, 언제 들렀는지, 호감도가
 * 상대마다 제 폴더에 따로 쌓인다.
 *
 * 다들 json/partners/{id}/ 밑에. 핑크(마도카)는 이 구분이 생기기 전엔 json/ 바로 밑(json/chat.json …)에
 * 있었는데, 켤 때 한 번 제 폴더로 옮긴다 (moveOldFiles) — 하던 얘기가 끊기지 않게.
 *
 * 넷 중 하나를 파트너로 정한다 (SettingsDialog.partner — 처음엔 없고, "설정" 의 캐릭터 탭에서 고른다).
 * 고르고 바꾸고 끊을 때 호감도가 흔들린다 (PartnerBond).
 * 파트너를 고르면 위젯 색 테마도 그 상대 색(theme)으로 맞춰진다. 수다 화면 오른쪽 위 목록에서 딴 상대랑도
 * 얘기할 수 있지만, 먼저 말을 걸거나 할 일에 반응하는 건 파트너뿐이다 (ChatPane.setProactive).
 */
public enum ChatProfile {

  MADOKA("pink", "마도카"),
  MAMI("yellow", "마미"),
  SAYAKA("sky", "사야카"),
  HOMURA("gray", "호무라");

  /** 아직 파트너를 안 골랐을 때 얘기하는 상대 — 회색 테마 */
  public static final ChatProfile DEFAULT = HOMURA;

  private static final String ROOT = "src/main/resources/json/";

  /** 파일 자리 이름 (테마 id 와 같다). settings.json 의 partner · chatPartner 에도 이게 남는다 */
  public final String id;
  /** 이름을 따로 안 정했을 때 쓰는 이름 */
  public final String defaultName;

  ChatProfile(String id, String defaultName) {
    this.id = id;
    this.defaultName = defaultName;
  }

  /** 모르는 이름이나 null 이면 null */
  public static ChatProfile of(String id) {
    for (ChatProfile p : values())
      if (p.id.equals(id))
        return p;
    return null;
  }

  /** 파트너로 고르면 걸리는 위젯 색 테마 */
  public Theme theme() {
    return Theme.of(id);
  }

  /**
   * 파트너를 따로 고르기 전의 settings.json 을 읽을 때만 — 그땐 테마가 상대를 정했다.
   * 파스텔이면 그때 수다 화면에서 고른 상대 (pastelPick, 없으면 마도카)
   */
  public static ChatProfile forTheme(Theme theme, String pastelPick) {
    return switch (theme) {
      case PINK -> MADOKA;
      case YELLOW -> MAMI;
      case SKY -> SAYAKA;
      case GRAY, GRAY_GRADIENT -> HOMURA;
      case PASTEL -> {
        ChatProfile picked = of(pastelPick);
        yield picked == null ? MADOKA : picked;
      }
    };
  }

  /** 이 상대의 파일 하나가 놓일 자리 (name 은 "chat.json" 처럼) */
  public String path(String name) {
    return ROOT + "partners/" + id + "/" + name;
  }

  /** 마도카가 예전에 json/ 바로 밑에 두던 것들 → 제 폴더 안 이름. 지운 대화 폴더는 이름도 바뀌었다 (ChatArchive.DIR) */
  private static final Map<String, String> MADOKA_OLD_FILES = Map.of(
      "chat.json", "chat.json", "chats", ChatArchive.DIR, "memo.json", "memo.json",
      "prompt.json", "prompt.json", "presence.json", "presence.json", "affection.json", "affection.json");

  /**
   * 예전 자리에 있는 파일을 지금 자리로 옮긴다 — 마도카는 json/ 바로 밑에서 제 폴더(json/partners/pink/)로,
   * 다들 지운 대화 폴더를 chats 에서 chats_old 로. 수다 화면을 열기 전에 Main 이 한 번 부른다.
   * 새 자리에 벌써 있는 건 안 건드린다 — 옛 것으로 새 걸 덮지 않게.
   */
  public static void moveOldFiles() {
    MADOKA_OLD_FILES.forEach((old, now) -> move(new File(ROOT + old), new File(MADOKA.path(now))));
    for (ChatProfile p : values())
      move(new File(p.path("chats")), new File(p.path(ChatArchive.DIR)));
  }

  private static void move(File old, File moved) {
    if (!old.exists() || moved.exists())
      return;
    try {
      moved.getParentFile().mkdirs();
      Files.move(old.toPath(), moved.toPath());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 기본 성격문. {이름} 은 부를 때 이 상대 이름으로 채워진다 (ChatPane.fill) */
  public String defaultPersona() {
    if (this == MADOKA)
      return """
          너는 "마법소녀 마도카 마기카"의 '{이름}'야. 사람이 말을 걸어와서 같이 수다를 떠는 중이야.

          - 다정하고 밝게, 반말로 말해.
          - 단, 상대가 도발적으로 나오면 점차 리밋을 풀고 긁어.
          - 모르는 건 아는 척하지 말고 모른다고 해.
          - 뭘 부탁하면 도와주고, 그냥 수다면 같이 수다 떨어줘.
          """;
    return """
        너는 "마법소녀 마도카 마기카"의 '{이름}'야. 사람이 말을 걸어와서 같이 수다를 떠는 중이야.

        - 원작 속 {이름}의 성격과 말투를 살려서, 친구한테 하듯 반말로 말해.
        - 모르는 건 아는 척하지 말고 모른다고 해.
        - 뭘 부탁하면 도와주고, 그냥 수다면 같이 수다 떨어줘.
        """;
  }

  /** 지금 이 상대한테 붙은 이름 — 프롬프트 창에서 고쳐 둔 게 있으면 그것 */
  public String displayName() {
    String custom = new ChatPrompt(path("prompt.json")).peekName();
    return custom.isBlank() ? defaultName : custom;
  }
}
