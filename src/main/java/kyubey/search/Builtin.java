package kyubey.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 위젯 자체에 붙어 있는 명령어들 (new / edit / scan / settings / 캡쳐 / restart / exit / 채팅 삭제
 * / 채팅 프롬프트 / 호감도 초기화 / keys / todo).
 *
 * 예전엔 Main 에서 "친 글자가 정확히 restart 면" 하고 가로챘는데,
 * 이제는 검색 결과 목록에 같이 올라온다 — 앞글자만 쳐도 보이고, 엔터로 고른다.
 * 실제로 뭘 하는지는 Main 이 맡는다 (창을 띄우거나 JVM 을 끄는 일이라 여기선 이름만 들고 있는다).
 */
public enum Builtin {
  NEW("new", "새로 등록하기"),
  EDIT("edit", "등록해둔 거 고치기"),
  // 한글 자판 그대로 "순회" 라고 쳐도 알아듣게.
  SCAN("scan", "설치된 앱 순회", "순회"),
  // 훑을 폴더, 마도카 그림, 할 일 색, 위젯 투명도. 처음 켰을 때 뜨는 그 창이다.
  // 한글로 "설정" 이라 쳐도, 예전 이름 "setup" 으로 쳐도 여기로 온다
  SETTINGS("settings", "설정 (폴더 · 마케몬 그림 · 할 일 색 · 투명도)", "설정", "setup"),
  // 단축키(Win+Alt+Z)를 못 걸었거나 손이 이미 위젯에 가 있을 때 부르는 길.
  // 맞춤법은 "캡처" 지만 치는 사람은 "캡쳐" 라고도 쳐서 둘 다 알아듣게 해뒀다.
  CAPTURE("캡쳐", "화면 잘라서 복사/검색 (Win+Alt+Z)", "캡처", "capture"),
  RESTART("restart", "다시 켜기", "ㄱㄷ"),
  EXIT("exit", "위젯 끄기"),
  // 같은 와이파이에 있는 폰이랑 주고받기. 이름이 "파일" 로 같아서 앞글자만 쳐도 둘이 나란히 뜬다.
  SEND_FILE("파일 송신", "폰으로 파일 보내기 (QR)"),
  RECEIVE_FILE("파일 수신", "폰에서 파일 받기 (QR → 다운로드 폴더)"),
  // 지우는 건 되돌릴 수 없는 일이라 앞글자만 치고 엔터를 눌렀다가 날아가면 곤란해서,
  // 이름을 통째로 쳐야 목록에 오른다 (Resolver.matchBuiltins 가 이것만 앞글자 매칭에서 뺀다).
  // "대화 삭제" 로도 불린다 — 머릿속에서 채팅이라 부르든 대화라 부르든 걸리게.
  CLEAR_CHAT("채팅 삭제", "채팅 상대랑 나눈 얘기 지우기", "대화 삭제"),
  // 마도카 성격문 고치기. 창이 한 번 뜨고 취소도 되니 앞글자만 쳐도 목록에 오른다 —
  // "채팅" 까지 치면 "채팅 삭제" 는 안 뜨고 이것만 뜬다. 이것도 "대화 프롬프트" 로 불린다.
  CHAT_PROMPT("채팅 프롬프트", "채팅 상대 이름 · 성격(프롬프트) 고치기", "대화 프롬프트"),
  // 마도카가 나한테 품은 호감도를 0 으로. 누르면 한 번 묻고 나서 지우니 앞글자만 쳐도 목록에 오른다
  RESET_LIKING("호감도 초기화", "채팅 상대 호감도 0 으로 되돌리기"),
  // .env 의 WIZ_ 키를 창에서 넣고 빼기. 저장하면 껐다 켜지 않아도 다음 질문부터 먹는다.
  KEYS("keys", "Gemini 키 관리 (.env)", "key", "키"),
  // 투두메이트처럼 한 주 줄과 그날 할 일을 띄운다. 지난 날을 들춰보거나 앞날 할 일을 미리 적을 때
  TODO("todo", "할 일 (한 주 보기)", "투두", "할일", "새애"),
  // 많이 쓴 검색어 랭킹. 슬래시로만 부른다 — 그냥 "to" 만 쳐도 todo 옆에 끼어들면 성가셔서
  TOP("top", "많이 쓴 검색어 랭킹"),
  // 루트의 PATCHNOTES.md 를 창에 띄운다. 이것도 슬래시로만
  PATCH("patch", "패치 노트");

  /** "/" 로 시작하게 쳤을 때 목록에 오르는 명령어들 (Resolver.matchSlash) */
  public static final List<Builtin> SLASH = List.of(TOP, PATCH, TODO, KEYS, SETTINGS, RESTART);

  private final String keyword;
  private final String label;

  /**
   * 이 명령어를 부르는 이름 전부 — 맨 앞이 대표 이름(keyword)이고 뒤는 딴 이름들.
   * 딴 이름은 쳤을 때 알아듣기만 하고 목록엔 안 뜬다. 같은 일을 하는 줄이 두 개
   * 나란히 뜨면 뭐가 다른가 싶어서, 보이는 건 늘 대표 이름 하나다.
   */
  private final List<String> keywords;

  Builtin(String keyword, String label, String... aliases) {
    this.keyword = keyword;
    this.label = label;
    List<String> all = new ArrayList<>(aliases.length + 1);
    all.add(keyword);
    all.addAll(Arrays.asList(aliases));
    this.keywords = List.copyOf(all);
  }

  /** 목록에 뜨는 대표 이름 (Tab 자동완성도 이 값으로 채운다) */
  public String keyword() {
    return keyword;
  }

  /** 쳐서 이 명령어를 부를 수 있는 이름들 — 대표 이름 + 딴 이름 */
  public List<String> keywords() {
    return keywords;
  }

  /** 친 게 이 명령어의 이름 중 하나와 딱 맞나 */
  public boolean answersTo(String typed) {
    return keywords.contains(typed);
  }

  /** 친 게 이 명령어의 이름 중 하나의 앞부분인가 (앞글자만 치고 고르는 길) */
  public boolean startsWith(String typed) {
    for (String name : keywords) {
      if (name.startsWith(typed))
        return true;
    }
    return false;
  }

  /** 결과 목록에 뜨는 줄 */
  public String label() {
    return label;
  }

  /** SearchResult 에 실어 보내는 값 — Main 이 이걸로 어느 명령어인지 되찾는다 */
  public static Builtin of(String keyword) {
    for (Builtin b : values()) {
      if (b.answersTo(keyword))
        return b;
    }
    return null;
  }

  /** 슬래시로만 부르는 명령어 — 그냥 쳐선 목록에 안 오른다 */
  public boolean slashOnly() {
    return this == TOP || this == PATCH;
  }

  /** 슬래시 목록에 올릴 항목 하나. Tab 이면 "/top" 처럼 슬래시째 채운다 */
  public SearchResult asSlashResult() {
    return new SearchResult("/" + keyword + "  " + label, SearchResult.Type.BUILTIN, keyword, null, "/" + keyword);
  }

  /** 결과 목록에 올릴 항목 하나 */
  public SearchResult asResult() {
    return new SearchResult(label, SearchResult.Type.BUILTIN, keyword, null, keyword);
  }
}
