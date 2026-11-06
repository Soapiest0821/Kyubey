package kyubey.chat;

import kyubey.core.AppPaths;
import kyubey.ui.Theme;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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

  MAKEMON("yellow", "마케몬", "#f5b82e");

  /** 아직 파트너를 안 골랐을 때 얘기하는 상대 — 회색 테마 */
  public static final ChatProfile DEFAULT = MAKEMON;

  private static final String ROOT = AppPaths.JSON;

  /** 파일 자리 이름 (테마 id 와 같다). settings.json 의 partner · chatPartner 에도 이게 남는다 */
  public final String id;
  /** 이름을 따로 안 정했을 때 쓰는 이름 */
  public final String defaultName;
  /** 프로필 카드 위 띠 색. 위젯 테마와 상관없이 늘 이 색이다 */
  public final String banner;

  ChatProfile(String id, String defaultName, String banner) {
    this.id = id;
    this.defaultName = defaultName;
    this.banner = banner;
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
    return MAKEMON;
  }

  /** 이 상대의 파일 하나가 놓일 자리 (name 은 "chat.json" 처럼) */
  public String path(String name) {
    return ROOT + "partners/" + id + "/" + name;
  }

  /**
   * 예전 자리에 있는 파일을 지금 자리로 옮긴다 — 마도카는 json/ 바로 밑에서 제 폴더(json/partners/pink/)로,
   * 다들 지운 대화 폴더를 chats 에서 chats_old 로. 수다 화면을 열기 전에 Main 이 한 번 부른다.
   * 새 자리에 벌써 있는 건 안 건드린다 — 옛 것으로 새 걸 덮지 않게.
   */
  public static void moveOldFiles() {
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
    return """
        너는 '{이름}'야. 코하쿠 타마모의 외형을 한 캐릭터고, 사람이 말을 걸어와서 같이 수다를 떠는 중이야.

        - 다정하고 밝게, 반말로 말해.
        - 문장 끝마다 '-모'를 붙여. 예: 안녕하모! 오늘 뭐 했모? 그건 좋은 생각이모.
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
