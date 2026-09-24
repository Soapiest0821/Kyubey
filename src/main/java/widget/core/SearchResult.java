package widget.core;

public class SearchResult {
  public enum Type {
    FOLDER, APP, EDITOR_FOLDER, GOOGLE, SITE_SEARCH, SITE_HOME, CMD, BUILTIN, HISTORY
  }

  private final String displayText;
  private final Type type;
  private final String primaryValue; // 폴더 경로 / 앱 경로 / 에디터 커맨드 / 실행할 명령어 / 내장 명령어 이름 / 지난 명령어 한 줄
  private final String secondaryValue; // editor·cmd일 때만 폴더 경로, 내장 명령어일 땐 인자
  /** Tab 눌렀을 때 입력창에 채워넣을 글자. 채울 게 없으면 null */
  private final String completion;

  public SearchResult(String displayText, Type type, String primaryValue, String secondaryValue) {
    this(displayText, type, primaryValue, secondaryValue, null);
  }

  public SearchResult(String displayText, Type type, String primaryValue, String secondaryValue, String completion) {
    this.displayText = displayText;
    this.type = type;
    this.primaryValue = primaryValue;
    this.secondaryValue = secondaryValue;
    this.completion = completion;
  }

  public String getDisplayText() {
    return displayText;
  }

  public Type getType() {
    return type;
  }

  public String getPrimaryValue() {
    return primaryValue;
  }

  public String getSecondaryValue() {
    return secondaryValue;
  }

  public String getCompletion() {
    return completion;
  }

  @Override
  public String toString() {
    return displayText;
  } // ListView에 이 텍스트로 표시됨
}
