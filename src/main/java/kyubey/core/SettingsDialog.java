package kyubey.core;

import kyubey.chat.Affection;
import kyubey.chat.ChatPane;
import kyubey.chat.ChatProfile;
import kyubey.chat.PartnerBond;
import kyubey.llm.GeminiClient;
import kyubey.ui.Fonts;
import kyubey.ui.Notice;
import kyubey.todo.TodoPane;
import kyubey.ui.Theme;
import kyubey.ui.WindowDrag;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사람마다 다른 설정 — 폴더를 훑을 곳, 상대마다 세울 그림, 할 일 색, 위젯 색 테마와 투명도 — 과
 * 그걸 고치는 "settings" / "설정" 창 ("setup" 으로도 부른다).
 *
 * 예전엔 코드에 내 폴더가 박혀 있었는데, 저장소에 올리면서 처음 켤 때 고르게 바꿨다.
 * settings.json 이 아예 없으면 아직 설정을 안 한 것이고, 그때 이 창이 뜬다.
 * "나중에" 로 넘겨도 빈 설정을 저장해서 다음부턴 안 뜬다.
 *
 * 그림은 아무 그림 파일이나 골라 세울 수 있다. 할 일 색은 기본 / 테마(위젯 색 테마에 맞춘 묶음) /
 * 커스텀(칸마다 직접) 중에 고른다 — 카테고리에 차례로 돌려 쓴다. 색 테마와 투명도는 고르는 동안 위젯에 바로 비춰본다.
 * 폴더 말고는 칸마다 "기본으로" 가 있어서 그 설정만 처음 값으로 되돌릴 수 있다.
 *
 * 창은 위젯 UI(색 테마 · 투명도 · 할 일 색) / 캐릭터(디스플레이 · 호감도 · 비밀 쪽지) / 훑을 폴더
 * 세 섹션으로 나뉘고, 위쪽 알약으로 오간다.
 */
// 예전 settings.json 에 남은 칸(투두메이트 시절 todoAppColors 등)은 읽을 때 그냥 넘긴다
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SettingsDialog {

  private static final String PATH = "src/main/resources/json/settings.json";
  private static final String STYLE = "/style/style.css";

  /** 아무것도 안 골랐을 때 서는 그림 */
  private static final String DEFAULT_MASCOT = "/img/마도카.png";

  /** 그림을 세우는 키 */
  public static final double MASCOT_HEIGHT = 170;

  /** 기본 마도카 그림의 가로:세로 (485×367) */
  private static final double MASCOT_RATIO = 485.0 / 367;

  /** 기본 마도카 그림을 MASCOT_HEIGHT 로 세웠을 때의 너비 — "가로에 맞추기" 가 이 너비에 맞춘다 */
  public static final double MASCOT_WIDTH = Math.round(MASCOT_HEIGHT * MASCOT_RATIO);

  /**
   * 위젯 투명도 — style.css 의 반투명 색(rgba) 알파를 바꾸는 값.
   * 1 이 원래 모습. 1 아래는 알파에 그대로 곱해서 20% 까지 더 비치게(0.8) 하고,
   * 1 위는 알파를 1 쪽으로 끌어올려서 MAX_OPACITY 에서 완전 불투명이 된다.
   * 창째로 흐리게 하면 글씨와 마도카까지 흐려져서 바탕만 건드린다.
   */
  public static final double DEFAULT_OPACITY = 1.0;
  /** 전체화면 설정 창 크기 — 화면이 작으면 화면에 맞춰 줄인다 */
  private static final double FULL_WIDTH = 1400;
  private static final double FULL_HEIGHT = 820;
  public static final double MIN_OPACITY = 0.8;
  public static final double MAX_OPACITY = 2.0;

  /**
   * 할 일 색의 "테마" — 위젯 색 테마마다 맞춰 짜둔 묶음. 칸 수는 기본 색과 맞춘다.
   * 따로 고르지 않고 지금 걸린 위젯 색 테마를 따라간다.
   */
  private static final Map<Theme, List<String>> TODO_THEMES = new java.util.EnumMap<>(Theme.class);
  static {
    List<String> pink = List.of("#ff69a0", "#ff9cc2", "#e05a8a", "#c86bd6", "#ffb36b", "#f27a8f");
    List<String> gray = List.of("#4b505a", "#7a808c", "#5f6b7d", "#9399a4", "#3a3f47", "#6e7480");
    TODO_THEMES.put(Theme.PINK, pink);
    TODO_THEMES.put(Theme.YELLOW, List.of("#f2b51e", "#ffcf5c", "#e08a1e", "#c9a227", "#ffb347", "#d98e04"));
    TODO_THEMES.put(Theme.SKY, List.of("#4a90d9", "#7cc0f0", "#2f6fb5", "#8fa8f0", "#4fb8cf", "#6a7fd0"));
    TODO_THEMES.put(Theme.PASTEL, List.of("#9fd88a", "#ffd36e", "#ffa8b8", "#9cc9f0", "#c6aef0", "#ffbf94"));
    TODO_THEMES.put(Theme.GRAY, gray);
    TODO_THEMES.put(Theme.GRAY_GRADIENT, gray);
  }

  /** 이 위젯 색 테마에 맞춘 할 일 색 */
  private static List<String> todoThemePalette(Theme theme) {
    return TODO_THEMES.getOrDefault(theme, TODO_THEMES.get(Theme.DEFAULT));
  }

  /** 할 일 색을 어떻게 정하나 */
  private enum TodoMode {
    DEFAULT("기본"), THEME("테마"), CUSTOM("커스텀");

    final String label;

    TodoMode(String label) {
      this.label = label;
    }
  }

  private static final Pattern RGBA = Pattern
      .compile("rgba\\(\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+),\\s*([0-9.]+)\\s*\\)");

  private static final ObjectMapper mapper = new ObjectMapper();

  /** 투명도를 바꿀 때마다 다시 읽지 않게 한 번 읽어둔 style.css */
  private static String styleSource;

  /** 지금 걸려 있는 색 테마 — 다른 창들이 styleUrl() 로 따라 입는다. 처음 부를 때 settings.json 에서 읽는다 */
  private static Theme currentTheme;

  /** 지금 Pretendard 를 쓰는지 — 처음 부를 때 settings.json 에서 읽는다 */
  private static Boolean currentPretendard;

  /** 테마마다 한 번만 만들어두는 다른 창용 style.css 주소. Pretendard 를 켜고 끄면 비운다 */
  private static final Map<Theme, String> themedUrls = new java.util.EnumMap<>(Theme.class);

  /** 폴더 이름으로 찾아 열 수 있게 훑어둘 뿌리 폴더들 */
  public List<String> scanRoots = new ArrayList<>();

  /**
   * 오른쪽 아래에 세울 그림 파일 — 상대(ChatProfile.id)마다 하나. 지금 말 거는 상대 그림이 선다.
   * 마도카는 없으면 기본 그림(/img/마도카.png), 나머지는 없으면 안 띄운다.
   */
  public Map<String, String> mascotImages = new LinkedHashMap<>();

  /**
   * 기본 마도카보다 옆으로 넓은 그림을 마도카 너비에 맞춰 줄일 상대들 (ChatProfile.id).
   * 없으면 마도카 키 그대로 세우고, 넘치는 만큼 왼쪽으로 넓어진다 (MascotFitDialog)
   */
  public Set<String> mascotFitWidth = new LinkedHashSet<>();

  /** 마도카를 치워뒀는지. 위젯에서 마도카를 오른쪽 클릭하면 켜지고, 끄는 건 여기 창에서 */
  public boolean mascotHidden;

  /** 지금 상대 그림이 없어도 오른쪽 아래에 그림 자리를 비워둘지 */
  public boolean reserveMascotSpace = true;

  /** 할 일 카테고리에 돌려 쓰는 색들 (커스텀). null 이면 기본 색 (TodoPane.DEFAULT_PALETTE) */
  public List<String> todoPalette;

  /** 할 일 색을 위젯 색 테마에 맞출지. 켜져 있으면 todoPalette 는 안 본다 */
  public boolean todoFollowTheme;

  /** 위젯 바탕의 불투명도 (MIN_OPACITY ~ MAX_OPACITY, 1 이 원래대로, MAX 가 완전 불투명) */
  public double widgetOpacity = DEFAULT_OPACITY;

  /** 글꼴을 Pretendard 로 쓸지. 끄면 맑은 고딕으로 내려간다 */
  public boolean usePretendard = true;

  /** 숨겼던 창을 다시 열 때마다 전체화면으로 띄울지. 떠 있는 동안엔 F11 로 오간다 (예전 이름은 focusFullScreen · startFullScreen) */
  @JsonAlias({ "focusFullScreen", "startFullScreen" })
  public boolean openFullScreen;

  /** Win+Alt+Space 로 열 화면 — 기본 위젯 (WIDGET) 이나 오로라 (AURORA: 화면 가운데 빛 속에 입력창만 뜨는 것) */
  public static final String HOTKEY_WIDGET = "widget";
  public static final String HOTKEY_AURORA = "aurora";
  public String hotkeyOpens = HOTKEY_WIDGET;

  /** 오로라에 할 일을 띄울지 — 안 한 건 입력창 밑 빛에서, 끝낸 건 위 빛에서 떠다닌다 (AuroraTodos) */
  public boolean auroraTodos = true;

  /** 단축키로 오로라를 여는지 */
  public boolean hotkeyAurora() {
    return HOTKEY_AURORA.equals(hotkeyOpens);
  }

  /** 전체화면에서 Esc 가 먼저 전체화면을 풀지. 끄면 Esc 는 전체화면 그대로 평소 일 (나가기 · 숨기기) 만 한다 */
  public boolean escExitsFullScreen = true;

  /** 위젯 색 테마 (Theme.id). 처음엔 기본 파트너(호무라)의 회색, 예전 파일에 null 로 남은 건 핑크 */
  public String widgetTheme = ChatProfile.DEFAULT.theme().id;

  /**
   * 파트너 (ChatProfile.id) — 먼저 말을 걸고 할 일에 반응하는 단 한 명. null 이면 아직 없다 (처음엔 없다).
   * "설정" 의 캐릭터 탭에서 고르고, 고를 때 위젯 색 테마도 그 상대 색으로 맞춰진다. 고르고 바꾸고 끊을 때
   * 호감도가 흔들린다 (PartnerBond). 이 칸이 아예 없던 예전 파일은 읽을 때 테마로 정한다 (load)
   */
  public String partner;

  /** 지금 파트너를 고른 때 (epoch ms). 모르면 0 */
  public long partnerSince;

  /** 마지막으로 끊은 상대 (ChatProfile.id) — 다시 돌아오거나 딴 상대로 갈아탈 때 본다 (PartnerBond) */
  public String exPartner;

  /** 끊은 상대를 파트너로 골랐던 때 (epoch ms). 모르면 0 */
  public long exPartnerSince;

  /** 끊은 때 (epoch ms) */
  public long brokeAt;

  /** 파일에 partner 칸이 있었는지 — 없던 예전 파일만 테마로 파트너를 정한다 */
  private boolean partnerKnown;

  /** 지금 수다 화면에서 얘기하는 상대 (ChatProfile.id). null 이면 파트너. 수다 화면 오른쪽 위 목록에서 고른다 */
  public String chatPartner;

  /** 파트너가 없을 때 수다 화면 밖에 세울 캐릭터 (ChatProfile.id). null 이면 기본 상대. "설정" 의 캐릭터 탭에서 고른다 */
  public String displayCharacter;

  /** 예전 파일의 파스텔 상대 — 파트너 칸이 없을 때 그걸 정하는 데만 쓴다 */
  private String legacyPastelPartner;

  /** 수다 화면 머리말에 호감도를 띄울지 */
  public boolean showLiking = true;

  /** 호감도 칸마다 쓸 이모지 (높은 칸부터). null 이면 기본 이모지 (Affection.DEFAULT_EMOJIS) */
  public List<String> likingEmojis;

  /** 마도카가 먼저 말을 걸 때 · 깜짝 긁을 때 비밀 쪽지로 보낼지 */
  public boolean secretNotes = true;

  /** 비밀 쪽지를 한 번 눌러 열지. false 면 두 번 눌러 연다 */
  public boolean noteSingleClick;

  /** 상대 답이 여러 말풍선으로 쪼개졌을 때 두 번째부터 뜸을 들여 올릴지 */
  public boolean typingDelay = true;

  /** 그 뜸 — 다음 말풍선의 바이트(UTF-8) 하나에 몇 ms */
  public int typingMsPerByte = ChatPane.DEFAULT_TYPING_MS_PER_BYTE;

  public SettingsDialog() {
  }

  /** 예전 settings.json 의 그림 하나(mascotImage)는 마도카 그림으로 옮겨 읽는다 */
  @JsonProperty("mascotImage")
  private void setLegacyMascot(String path) {
    if (path != null)
      mascotImages.putIfAbsent(ChatProfile.MADOKA.id, path);
  }

  @JsonProperty("partner")
  private void setPartner(String id) {
    partner = id;
    partnerKnown = true;
  }

  @JsonProperty("pastelPartner")
  private void setLegacyPastelPartner(String id) {
    legacyPastelPartner = id;
  }

  // ── 읽고 쓰기 ──

  /** 설정을 한 번이라도 저장했나. 안 했으면 처음 켠 것 */
  public static boolean exists() {
    return new File(PATH).isFile();
  }

  public static SettingsDialog load() {
    File file = new File(PATH);
    if (file.isFile()) {
      try {
        SettingsDialog s = mapper.readValue(file, SettingsDialog.class);
        if (s.scanRoots == null)
          s.scanRoots = new ArrayList<>();
        if (s.mascotImages == null)
          s.mascotImages = new LinkedHashMap<>();
        if (s.mascotFitWidth == null)
          s.mascotFitWidth = new LinkedHashSet<>();
        s.widgetOpacity = clampOpacity(s.widgetOpacity);
        if (s.typingMsPerByte < 0)
          s.typingMsPerByte = ChatPane.DEFAULT_TYPING_MS_PER_BYTE;
        // 예전엔 테마 묶음을 색 그대로 남겼다 — 그런 색이면 이제 위젯 테마를 따라가게 넘긴다
        if (!s.todoFollowTheme && s.todoPalette != null && TODO_THEMES.containsValue(s.todoPalette)) {
          s.todoFollowTheme = true;
          s.todoPalette = null;
        }
        // 파트너를 따로 고르기 전엔 테마가 상대를 정했다 — 그때 말 걸던 상대를 파트너로 이어받는다
        if (!s.partnerKnown)
          s.partner = ChatProfile.forTheme(Theme.of(s.widgetTheme), s.legacyPastelPartner).id;
        else if (ChatProfile.of(s.partner) == null)
          s.partner = null;
        return s;
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    // 처음엔 파트너가 없다
    return new SettingsDialog();
  }

  /** 할 일에 실제로 칠할 색 — 테마를 따르면 위젯 색 테마의 묶음, 아니면 고른 색 (null 이면 기본) */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public List<String> todoColors() {
    return todoFollowTheme ? todoThemePalette(Theme.of(widgetTheme)) : todoPalette;
  }

  public void save() throws IOException {
    File file = new File(PATH);
    file.getParentFile().mkdirs();
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, this);
    currentTheme = Theme.of(widgetTheme);
    if (currentPretendard == null || currentPretendard != usePretendard) {
      currentPretendard = usePretendard;
      themedUrls.clear();
      // 꺼둔 채로 켰으면 아직 안 올라와 있다
      if (usePretendard && !Fonts.isPretendardAvailable())
        Fonts.loadPretendard();
    }
  }

  /** 수다 상대만 바꿔 남긴다 — 수다 화면 목록에서 고를 때마다 설정 창을 거치지 않고. 파트너를 고르면 null 로 */
  public static void saveChatPartner(String id) {
    SettingsDialog s = load();
    s.chatPartner = id == null || id.equals(s.partner) ? null : id;
    try {
      s.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** 손으로 고친 settings.json 에 엉뚱한 값이 있어도 정해둔 폭 안으로 */
  private static double clampOpacity(double v) {
    if (Double.isNaN(v))
      return DEFAULT_OPACITY;
    return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, v));
  }

  /** 파트너 — 먼저 말을 걸고 할 일에 반응하는 상대. 아직 없으면 null */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public ChatProfile mainPartner() {
    return ChatProfile.of(partner);
  }

  /** 지금 수다 화면에서 얘기하는 상대 — 목록에서 딴 상대를 골랐으면 그 상대, 아니면 파트너 (없으면 기본 상대) */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public ChatProfile talkingTo() {
    ChatProfile p = ChatProfile.of(chatPartner);
    if (p != null)
      return p;
    ChatProfile main = mainPartner();
    return main == null ? ChatProfile.DEFAULT : main;
  }

  /** 수다 화면 밖에 서는 캐릭터 — 파트너가 있으면 파트너, 없으면 설정에서 고른 캐릭터 (없으면 기본 상대) */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public ChatProfile displayed() {
    ChatProfile main = mainPartner();
    if (main != null)
      return main;
    ChatProfile picked = ChatProfile.of(displayCharacter);
    return picked == null ? ChatProfile.DEFAULT : picked;
  }

  /** 그림으로 세울 상대 — 수다 화면에선 지금 얘기하는 상대, 밖에선 displayed() */
  private ChatProfile shown(boolean inChat) {
    return inChat ? talkingTo() : displayed();
  }

  /** 파트너 기록 — PartnerBond 가 읽고 고친다 */
  private PartnerBond.State bond() {
    return new PartnerBond.State(mainPartner(), partnerSince, ChatProfile.of(exPartner), exPartnerSince, brokeAt);
  }

  private void applyBond(PartnerBond.State b) {
    partner = b.partner() == null ? null : b.partner().id;
    partnerSince = b.since();
    exPartner = b.ex() == null ? null : b.ex().id;
    exPartnerSince = b.exSince();
    brokeAt = b.brokeAt();
  }

  /** 세울 상대(shown)의 그림. 없으면 null — 그땐 안 띄운다 */
  public Image mascotImage(boolean inChat) {
    ChatProfile p = shown(inChat);
    return mascotImage(p, mascotImages.get(p.id));
  }

  /** 세울 상대(shown)의 그림을 마도카 너비에 맞춰 줄일지 */
  public boolean mascotFitsWidth(boolean inChat) {
    return mascotFitWidth.contains(shown(inChat).id);
  }

  /** 기본 마도카보다 옆으로 넓은 그림인지 — 그래야 가로에 맞출지 고를 거리가 생긴다 */
  public static boolean widerThanDefault(Image image) {
    return image != null && image.getHeight() > 0
        && image.getWidth() / image.getHeight() > MASCOT_RATIO + 1e-3;
  }

  /**
   * 그림 크기를 건다. 늘 마도카 키로 세우되, 가로에 맞추면 마도카 너비도 넘지 않게 한다
   * (비율은 지키니 넓은 그림은 키가 줄고, 좁은 그림은 그대로)
   */
  public static void fitMascot(ImageView view, boolean fitWidth) {
    view.setPreserveRatio(true);
    view.setFitHeight(MASCOT_HEIGHT);
    view.setFitWidth(fitWidth ? MASCOT_WIDTH : 0);
  }

  /**
   * 이 상대에게 세울 그림. 골라둔 파일을 못 읽거나 안 골랐으면 마도카는 기본 그림으로,
   * 나머지는 null (안 띄움). 그림 하나 없다고 위젯까지 못 뜨면 곤란해서 조용히 넘어간다.
   */
  public static Image mascotImage(ChatProfile profile, String path) {
    if (path != null) {
      File file = new File(path);
      if (file.isFile()) {
        Image image = cachedMascot(file);
        if (image != null)
          return image;
      }
      System.err.println("[mascot] " + path + " 를 못 읽었어");
    }
    if (profile != ChatProfile.MADOKA)
      return null;
    if (defaultMascot == null) {
      var stream = SettingsDialog.class.getResourceAsStream(DEFAULT_MASCOT);
      if (stream == null) {
        System.err.println("[mascot] " + DEFAULT_MASCOT + " 를 못 찾았어");
        return null;
      }
      defaultMascot = new Image(stream);
    }
    return defaultMascot;
  }

  /**
   * 그림은 아무리 커도 MASCOT_LOAD_HEIGHT 키로 줄여서 읽는다 — 화면엔 MASCOT_HEIGHT 로만 서는데
   * 큰 사진을 원본 그대로 풀면 그림 하나에 수십 MB 가 든다. 두 배는 배율 높은 화면(200%) 몫.
   */
  private static final double MASCOT_LOAD_HEIGHT = MASCOT_HEIGHT * 2;

  /** 기본 마도카 그림 (jar 안에 있어서 바뀔 일이 없다) */
  private static Image defaultMascot;

  /** 읽어둔 그림. 수다 화면을 드나들 때마다 다시 거는데, 그때마다 파일을 다시 풀지 않으려고 */
  private static final Map<String, CachedMascot> mascotCache = new java.util.HashMap<>();

  /** 그 파일의 수정 시각 · 크기가 그대로일 때만 그림을 다시 쓴다 — 같은 경로에 그림만 바꿔 넣었을 수도 있어서 */
  private record CachedMascot(long modified, long size, Image image) {
  }

  /** 파일 그림을 줄여 읽는다. 못 읽으면 null */
  private static Image cachedMascot(File file) {
    String key = file.getAbsolutePath();
    long modified = file.lastModified();
    long size = file.length();
    CachedMascot hit = mascotCache.get(key);
    if (hit != null && hit.modified == modified && hit.size == size)
      return hit.image;
    Image image = new Image(file.toURI().toString(), 0, MASCOT_LOAD_HEIGHT, true, true);
    if (image.isError()) {
      mascotCache.remove(key);
      return null;
    }
    mascotCache.put(key, new CachedMascot(modified, size, image));
    return image;
  }

  /**
   * 위젯 씬에 style.css 를 건다. 테마가 기본(핑크)이 아니면 색을 바꿔 끼우고, 배율이 1 이 아니면
   * rgba 의 알파만 곱한 사본을 data: 로 건다 (JavaFX CSS 엔 계산식이 없어서 변수 하나로 못 돌린다).
   * 다른 창들은 styleUrl() — 테마만 따르고 투명도는 원래대로.
   */
  public static void applyStyle(Scene scene, double opacity, String theme) {
    Theme t = Theme.of(theme);
    double factor = clampOpacity(opacity);
    String url = SettingsDialog.class.getResource(STYLE).toExternalForm();
    if (t != Theme.DEFAULT || Math.abs(factor - 1.0) > 1e-6 || !pretendard()) {
      String css = scaleAlpha(withFont(t.apply(styleSource())), factor);
      if (css != null)
        url = dataUrl(css);
    }
    scene.getStylesheets().setAll(url);
  }

  /** 위젯 말고 다른 창들이 거는 style.css — 고른 색 테마만 입힌다 */
  public static String styleUrl() {
    return styleUrl(theme());
  }

  /** 지금 걸려 있는 색 테마 — style.css 밖에 박힌 색도 따라가게 (Theme.swap) */
  public static Theme theme() {
    if (currentTheme == null)
      currentTheme = Theme.of(load().widgetTheme);
    return currentTheme;
  }

  /** Pretendard 를 쓰는지 — 끄면 style.css 에서 'Pretendard' 를 걷어낸 사본이 걸린다 */
  public static boolean pretendard() {
    if (currentPretendard == null)
      currentPretendard = load().usePretendard;
    return currentPretendard;
  }

  private static String styleUrl(Theme theme) {
    String plain = SettingsDialog.class.getResource(STYLE).toExternalForm();
    if (theme == Theme.DEFAULT && pretendard())
      return plain;
    return themedUrls.computeIfAbsent(theme, t -> {
      String css = withFont(t.apply(styleSource()));
      return css == null ? plain : dataUrl(css);
    });
  }

  /** Pretendard 를 껐으면 글꼴 목록에서 빼서 뒤의 맑은 고딕(Segoe UI)으로 내려가게 */
  private static String withFont(String css) {
    if (css == null || pretendard())
      return css;
    return css.replace("'Pretendard', ", "");
  }

  private static String dataUrl(String css) {
    return "data:text/css;charset=utf-8;base64,"
        + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
  }

  private static String styleSource() {
    if (styleSource == null) {
      try (InputStream in = SettingsDialog.class.getResourceAsStream(STYLE)) {
        if (in != null)
          styleSource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return styleSource;
  }

  private static String scaleAlpha(String css, double factor) {
    if (css == null || Math.abs(factor - 1.0) <= 1e-6)
      return css;
    Matcher m = RGBA.matcher(css);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      double alpha = Double.parseDouble(m.group(4));
      alpha = factor < 1.0
          ? alpha * factor
          : alpha + (1.0 - alpha) * (factor - 1.0) / (MAX_OPACITY - 1.0);
      alpha = Math.min(1.0, alpha);
      m.appendReplacement(out, String.format(Locale.ROOT, "rgba(%s, %s, %s, %.3f)",
          m.group(1).trim(), m.group(2).trim(), m.group(3).trim(), alpha));
    }
    m.appendTail(out);
    return out.toString();
  }

  // ── 설정 창 ──

  /**
   * 창을 띄우고 닫힐 때까지 기다린다. "저장" 으로 닫았으면 그 설정을, 아니면 null.
   * 색 테마와 투명도는 고르는 동안 owner 에 바로 비춰보고, 저장 안 하고 닫으면 원래대로 돌려놓는다.
   * 처음 켠 거면(settings.json 이 없으면) "취소" 가 "나중에" 가 되고, 빈 설정이라도 남긴다.
   * 위젯이 전체화면이면(full) 탭 대신 세 섹션을 나란히 세 칸으로 펴서 한눈에 본다.
   */
  public static SettingsDialog show(Stage owner, boolean full) {
    boolean firstRun = !exists();
    SettingsDialog settings = load();

    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label(firstRun ? "🌸 처음 설정" : "⚙ 설정");
    heading.getStyleClass().add("notice-title");

    // ── 훑을 폴더 ── (처음 값이란 게 없어서 "기본으로" 는 없다)
    Label foldersTitle = new Label("훑을 폴더");
    foldersTitle.getStyleClass().add("dialog-label");

    Label foldersSub = new Label("자주 여는 폴더들이 모여 있는 곳을 골라줘. 그 밑의 폴더는 이름만 쳐도 열 수 있게 돼. "
        + "앱은 시작 메뉴에서 알아서 찾고, 빠진 건 \"new\" 로 넣으면 돼. 바꾸고 저장하면 새로 훑어.");
    foldersSub.getStyleClass().add("dialog-path-label");
    foldersSub.setWrapText(true);

    ObservableList<String> roots = FXCollections.observableArrayList(settings.scanRoots);
    ListView<String> list = new ListView<>(roots);
    list.getStyleClass().add("dialog-alias-list");
    list.setPrefHeight(110);
    list.setPlaceholder(new Label("아직 고른 폴더가 없어"));

    Button addFolder = new Button("＋ 폴더 추가");
    addFolder.getStyleClass().add("dialog-button");
    addFolder.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle("훑을 폴더 고르기");
      File dir = chooser.showDialog(dialog);
      if (dir != null && !roots.contains(dir.getAbsolutePath()))
        roots.add(dir.getAbsolutePath());
    });

    Button removeFolder = new Button("빼기");
    removeFolder.getStyleClass().add("dialog-button");
    removeFolder.setOnAction(e -> {
      String picked = list.getSelectionModel().getSelectedItem();
      if (picked != null)
        roots.remove(picked);
    });
    list.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.DELETE) {
        e.consume();
        removeFolder.fire();
      }
    });

    HBox folderButtons = new HBox(8, addFolder, removeFolder);

    // ── 캐릭터 디스플레이 ── 상대마다 그림 하나. 지금 말 거는 상대 그림만 선다
    Map<ChatProfile, String> mascotPaths = new EnumMap<>(ChatProfile.class);
    for (ChatProfile p : ChatProfile.values())
      if (settings.mascotImages.get(p.id) != null)
        mascotPaths.put(p, settings.mascotImages.get(p.id));
    Set<ChatProfile> fitWidth = EnumSet.noneOf(ChatProfile.class);
    for (ChatProfile p : ChatProfile.values())
      if (settings.mascotFitWidth.contains(p.id))
        fitWidth.add(p);

    Label mascotHint = new Label("수다 화면 밖에선 파트너(없으면 위에서 고른 캐릭터), 수다 화면에선 지금 얘기하는 상대의 그림이 서. "
        + "그림을 안 고른 상대는 안 띄우고, 마도카만 기본 그림이 있어.");
    mascotHint.getStyleClass().add("dialog-path-label");
    mascotHint.setWrapText(true);

    List<Runnable> showMascots = new ArrayList<>();
    VBox mascotRows = new VBox(10);
    for (ChatProfile p : ChatProfile.values()) {
      ImageView preview = new ImageView();
      preview.setPreserveRatio(true);
      preview.setFitHeight(60);
      preview.setFitWidth(60);
      StackPane frame = new StackPane(preview);
      frame.setMinSize(60, 60);
      frame.setPrefSize(60, 60);

      Label name = new Label(p.displayName());
      name.getStyleClass().add("dialog-label");
      Label path = new Label();
      path.getStyleClass().add("dialog-path-label");
      path.setWrapText(true);

      Button pick = new Button("고르기…");
      pick.getStyleClass().add("dialog-button");
      Button clear = new Button("빼기");
      clear.getStyleClass().add("dialog-button");
      // 옆으로 넓은 그림일 때만 — 가로에 맞출지, 왼쪽으로 넓게 세울지 다시 고른다
      Button fit = new Button("크기…");
      fit.getStyleClass().add("dialog-button");

      Runnable show = () -> {
        String file = mascotPaths.get(p);
        Image image = mascotImage(p, file);
        boolean wide = widerThanDefault(image);
        preview.setImage(image);
        String where = file != null ? file : p == ChatProfile.MADOKA ? "기본 그림" : "없음 — 안 띄워";
        path.setText(wide ? where + "\n" + (fitWidth.contains(p) ? "가로에 맞춤" : "왼쪽으로 넓게") : where);
        clear.setDisable(file == null);
        fit.setDisable(!wide);
      };
      showMascots.add(show);
      show.run();

      pick.setOnAction(e -> {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(p.displayName() + " 그림 고르기");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("그림", "*.png", "*.gif", "*.jpg", "*.jpeg", "*.bmp"));
        String current = mascotPaths.get(p);
        File dir = current == null ? null : new File(current).getParentFile();
        if (dir != null && dir.isDirectory())
          chooser.setInitialDirectory(dir);
        File file = chooser.showOpenDialog(dialog);
        if (file != null) {
          mascotPaths.put(p, file.getAbsolutePath());
          // 새 그림은 마도카 키 그대로가 기본. 옆으로 넓으면 바로 물어본다
          fitWidth.remove(p);
          Image image = mascotImage(p, file.getAbsolutePath());
          if (widerThanDefault(image) && Boolean.TRUE.equals(
              MascotFitDialog.ask(dialog, image, p.displayName(), false)))
            fitWidth.add(p);
          show.run();
        }
      });
      fit.setOnAction(e -> {
        Boolean picked = MascotFitDialog.ask(dialog, preview.getImage(), p.displayName(), fitWidth.contains(p));
        if (picked == null)
          return;
        if (picked)
          fitWidth.add(p);
        else
          fitWidth.remove(p);
        show.run();
      });
      clear.setOnAction(e -> {
        mascotPaths.remove(p);
        fitWidth.remove(p);
        show.run();
      });

      VBox info = new VBox(4, name, path);
      HBox.setHgrow(info, Priority.ALWAYS);
      HBox row = new HBox(12, frame, info, pick, fit, clear);
      row.setAlignment(Pos.CENTER_LEFT);
      mascotRows.getChildren().add(row);
    }

    CheckBox hideMascot = new CheckBox("그림 숨기기 (위젯에서 그림을 오른쪽 클릭해도 숨어)");
    hideMascot.getStyleClass().add("dialog-path-label");
    hideMascot.setSelected(settings.mascotHidden);

    CheckBox reserveSpace = new CheckBox("그림이 없어도 오른쪽 아래 자리 비워두기");
    reserveSpace.getStyleClass().add("dialog-path-label");
    reserveSpace.setSelected(settings.reserveMascotSpace);

    HBox mascotTitle = section("캐릭터 디스플레이", () -> {
      mascotPaths.clear();
      fitWidth.clear();
      showMascots.forEach(Runnable::run);
      hideMascot.setSelected(false);
      reserveSpace.setSelected(true);
    });

    VBox mascotRow = new VBox(10, mascotHint, mascotRows, hideMascot, reserveSpace);

    // ── 위젯 색 테마 ──
    String originalTheme = settings.widgetTheme;
    ToggleGroup themeGroup = new ToggleGroup();
    FlowPane themeRow = new FlowPane(6, 6);
    for (Theme t : Theme.values())
      themeRow.getChildren().add(toggle(themeGroup, t.label, t, t == Theme.of(settings.widgetTheme)));

    // ── 파트너 ── 캐릭터 탭 맨 위. 처음엔 없음이고, 고르면 위젯 색 테마도 그 상대 색으로 바로 바뀐다.
    // 고르고 바꾸고 끊는 건 저장할 때 한 번 더 묻는다 — 호감도가 크게 흔들려서 (PartnerBond)
    ChatProfile originalPartner = settings.mainPartner();
    ToggleGroup partnerGroup = new ToggleGroup();
    FlowPane partnerToggles = new FlowPane(6, 6);
    partnerToggles.getChildren().add(toggle(partnerGroup, "없음", NO_PARTNER, originalPartner == null));
    for (ChatProfile p : ChatProfile.values())
      partnerToggles.getChildren().add(toggle(partnerGroup, p.displayName(), p, p == originalPartner));
    partnerGroup.selectedToggleProperty().addListener((obs, was, is) -> {
      if (is != null && is.getUserData() instanceof ChatProfile p)
        selectToggle(themeGroup, p.theme());
    });
    // 파트너가 없을 때만 — 수다 화면 밖에 세울 캐릭터를 고른다. 파트너가 있으면 파트너가 서니 잠가둔다
    ChatProfile originalDisplay = ChatProfile.of(settings.displayCharacter);
    if (originalDisplay == null)
      originalDisplay = ChatProfile.DEFAULT;
    ToggleGroup displayGroup = new ToggleGroup();
    FlowPane displayToggles = new FlowPane(6, 6);
    for (ChatProfile p : ChatProfile.values())
      displayToggles.getChildren().add(toggle(displayGroup, p.displayName(), p, p == originalDisplay));
    Label displayTitle = new Label("세울 캐릭터");
    displayTitle.getStyleClass().add("dialog-label");
    Label displayHint = new Label("파트너가 없을 때 수다 화면 밖에 설 캐릭터야. 파트너가 있으면 파트너가 서.");
    displayHint.getStyleClass().add("dialog-path-label");
    displayHint.setWrapText(true);
    Runnable lockDisplay = () -> displayToggles.setDisable(pickedPartner(partnerGroup) != null);
    partnerGroup.selectedToggleProperty().addListener((obs, was, is) -> lockDisplay.run());
    lockDisplay.run();

    Label partnerTitle = new Label("파트너");
    partnerTitle.getStyleClass().add("dialog-label");
    Label partnerHint = new Label("먼저 말을 걸고 할 일에 반응하는 건 파트너뿐이야. 딴 상대랑은 수다 화면 위 목록에서 얘기할 수 있어.\n"
        + "⚠ 신중히 골라줘. 한번 정한 파트너를 바꾸거나 끊으면 그 상대 마음이 크게 상할 수 있어.");
    partnerHint.getStyleClass().add("dialog-path-label");
    partnerHint.setWrapText(true);

    // 기본 테마는 파트너 색 (없으면 기본 상대 색)
    HBox themeTitle = section("위젯 색 테마", () -> {
      ChatProfile p = pickedPartner(partnerGroup);
      selectToggle(themeGroup, (p == null ? ChatProfile.DEFAULT : p).theme());
    });

    // ── 할 일 색 ── 기본 / 테마 / 커스텀. 기본·테마일 땐 칸은 보여주기만 하고, 커스텀에서만 고친다.
    // 테마는 따로 고르지 않고 위에서 고른 위젯 색 테마를 따른다
    List<String> start = settings.todoFollowTheme ? todoThemePalette(Theme.of(settings.widgetTheme))
        : settings.todoPalette == null || settings.todoPalette.isEmpty() ? TodoPane.DEFAULT_PALETTE
        : settings.todoPalette;
    List<ColorPicker> pickers = new ArrayList<>();
    HBox pickerRow = new HBox(6);
    for (int i = 0; i < TodoPane.DEFAULT_PALETTE.size(); i++) {
      String hex = i < start.size() ? start.get(i) : TodoPane.DEFAULT_PALETTE.get(i);
      ColorPicker picker = new ColorPicker(Color.web(hex));
      picker.setPrefWidth(56);
      pickers.add(picker);
      pickerRow.getChildren().add(picker);
    }

    TodoMode startMode = settings.todoFollowTheme ? TodoMode.THEME
        : start.equals(TodoPane.DEFAULT_PALETTE) ? TodoMode.DEFAULT
        : TodoMode.CUSTOM;

    ToggleGroup todoModeGroup = new ToggleGroup();
    HBox todoModeRow = new HBox(6);
    for (TodoMode mode : TodoMode.values())
      todoModeRow.getChildren().add(toggle(todoModeGroup, mode.label, mode, mode == startMode));

    // 고른 대로 칸을 채운다. 커스텀으로 넘어갈 땐 보고 있던 색에서 이어 고친다
    Runnable showTodo = () -> {
      TodoMode mode = (TodoMode) todoModeGroup.getSelectedToggle().getUserData();
      if (mode == TodoMode.DEFAULT)
        applyPalette(pickers, TodoPane.DEFAULT_PALETTE);
      else if (mode == TodoMode.THEME)
        applyPalette(pickers, todoThemePalette(pickedTheme(themeGroup)));
      // 끄면(setDisable) 흐려져서 색이 제대로 안 보이니, 눌러도 안 열리게만 한다
      for (ColorPicker picker : pickers) {
        picker.setMouseTransparent(mode != TodoMode.CUSTOM);
        picker.setFocusTraversable(mode == TodoMode.CUSTOM);
      }
    };
    showTodo.run();
    todoModeGroup.selectedToggleProperty().addListener((obs, was, is) -> {
      if (is != null)
        showTodo.run();
    });
    // 위젯 색 테마를 바꾸면 테마 색도 따라 바뀐다
    themeGroup.selectedToggleProperty().addListener((obs, was, is) -> {
      if (is != null)
        showTodo.run();
    });

    HBox todoTitle = section("할 일 색", () -> selectToggle(todoModeGroup, TodoMode.DEFAULT));

    // ── 위젯 투명도 ──
    double originalOpacity = settings.widgetOpacity;
    Slider opacity = new Slider(MIN_OPACITY, MAX_OPACITY, settings.widgetOpacity);
    opacity.setMajorTickUnit(0.1);
    opacity.setMinorTickCount(1);
    opacity.setSnapToTicks(true);
    opacity.setShowTickMarks(true);
    opacity.setBlockIncrement(0.05);
    HBox.setHgrow(opacity, Priority.ALWAYS);
    Label opacityValue = new Label();
    opacityValue.getStyleClass().add("dialog-path-label");
    opacityValue.setMinWidth(90);
    Runnable showOpacity = () -> {
      opacityValue.setText(opacityText(opacity.getValue()));
      if (owner != null && owner.getScene() != null)
        applyStyle(owner.getScene(), opacity.getValue(), pickedTheme(themeGroup).id);
    };
    themeGroup.selectedToggleProperty().addListener((obs, was, is) -> {
      if (is != null)
        showOpacity.run();
    });
    // 끌 때마다 스타일을 다시 거는 일이라, 눈금에 걸려 값이 바뀔 때만 돈다
    opacity.valueProperty().addListener((obs, was, is) -> {
      if (Math.abs(was.doubleValue() - is.doubleValue()) > 1e-6)
        showOpacity.run();
    });
    opacityValue.setText(opacityText(opacity.getValue()));

    HBox opacityTitle = section("위젯 투명도", () -> opacity.setValue(DEFAULT_OPACITY));
    Label opacityHint = new Label("왼쪽이 더 비치고, 오른쪽 끝은 완전 불투명이야. 글씨와 그림은 그대로야.");
    opacityHint.getStyleClass().add("dialog-path-label");
    HBox opacityRow = new HBox(10, opacity, opacityValue);
    opacityRow.setAlignment(Pos.CENTER_LEFT);

    // ── 글꼴 ── 저장하면 걸린다
    CheckBox usePretendard = new CheckBox("Pretendard 쓰기 (끄면 맑은 고딕)");
    usePretendard.getStyleClass().add("dialog-path-label");
    usePretendard.setSelected(settings.usePretendard);

    HBox fontTitle = section("글꼴", () -> usePretendard.setSelected(true));

    // ── 단축키로 여는 화면 ── 다음에 창을 열 때부터
    ToggleGroup openGroup = new ToggleGroup();
    RadioButton openWidget = new RadioButton("기본 위젯");
    RadioButton openAurora = new RadioButton("오로라 (화면 가운데 빛 속 입력창 — ← → ↓ 로 기본 위젯)");
    for (RadioButton rb : List.of(openWidget, openAurora)) {
      rb.setToggleGroup(openGroup);
      rb.getStyleClass().add("dialog-path-label");
    }
    (settings.hotkeyAurora() ? openAurora : openWidget).setSelected(true);
    VBox openRow = new VBox(6, openWidget, openAurora);

    CheckBox auroraTodos = new CheckBox("오로라에 할 일 띄우기 (안 한 건 밑에서, 끝낸 건 위에서 떠다녀)");
    auroraTodos.getStyleClass().add("dialog-path-label");
    auroraTodos.setSelected(settings.auroraTodos);

    HBox openTitle = section("단축키로 여는 화면 (Win+Alt+Space)", () -> {
      openWidget.setSelected(true);
      auroraTodos.setSelected(true);
    });

    // ── 전체화면 ── 다음에 창을 열 때부터
    CheckBox startFull = new CheckBox("기본 위젯을 열 때 전체화면으로 띄우기 (떠 있는 동안엔 F11 로 오가)");
    startFull.getStyleClass().add("dialog-path-label");
    startFull.setSelected(settings.openFullScreen);

    CheckBox escFull = new CheckBox("전체화면에서 Esc 로 전체화면 풀기 (끄면 Esc 는 나가기 · 숨기기만)");
    escFull.getStyleClass().add("dialog-path-label");
    escFull.setSelected(settings.escExitsFullScreen);

    HBox fullTitle = section("전체화면", () -> {
      startFull.setSelected(false);
      escFull.setSelected(true);
    });

    // ── 호감도 표시 ──
    CheckBox showLiking = new CheckBox("수다 화면 위에 호감도 띄우기");
    showLiking.getStyleClass().add("dialog-path-label");
    showLiking.setSelected(settings.showLiking);

    String[] likingRanges = { "300 이상", "0 ~ 299", "-100 ~ -1", "-100 미만" };
    List<TextField> emojiFields = new ArrayList<>();
    FlowPane emojiRow = new FlowPane(12, 6);
    for (int i = 0; i < likingRanges.length; i++) {
      TextField field = new TextField(Affection.emojiAt(i, settings.likingEmojis));
      field.getStyleClass().add("dialog-textfield");
      field.setPrefColumnCount(3);
      field.setPromptText(Affection.DEFAULT_EMOJIS.get(i));
      emojiFields.add(field);
      Label range = new Label(likingRanges[i]);
      range.getStyleClass().add("dialog-path-label");
      HBox cell = new HBox(6, range, field);
      cell.setAlignment(Pos.CENTER_LEFT);
      emojiRow.getChildren().add(cell);
    }

    HBox likingTitle = section("호감도 표시", () -> {
      showLiking.setSelected(true);
      for (int i = 0; i < emojiFields.size(); i++)
        emojiFields.get(i).setText(Affection.DEFAULT_EMOJIS.get(i));
    });

    // ── 비밀 쪽지 ──
    CheckBox useNotes = new CheckBox("비밀 쪽지 보내기");
    useNotes.getStyleClass().add("dialog-path-label");
    useNotes.setSelected(settings.secretNotes);

    ToggleGroup clickGroup = new ToggleGroup();
    RadioButton doubleClick = new RadioButton("두 번 눌러 열기");
    RadioButton singleClick = new RadioButton("한 번 눌러 열기");
    for (RadioButton rb : List.of(doubleClick, singleClick)) {
      rb.setToggleGroup(clickGroup);
      rb.getStyleClass().add("dialog-path-label");
      // 쪽지를 끄면 여는 법은 고를 일이 없다
      rb.disableProperty().bind(useNotes.selectedProperty().not());
    }
    (settings.noteSingleClick ? singleClick : doubleClick).setSelected(true);
    HBox clickRow = new HBox(16, doubleClick, singleClick);
    clickRow.setAlignment(Pos.CENTER_LEFT);

    HBox noteTitle = section("비밀 쪽지", () -> {
      useNotes.setSelected(true);
      doubleClick.setSelected(true);
    });

    // ── 말풍선 뜸 ──
    CheckBox useTyping = new CheckBox("두 번째 말풍선부터 치는 만큼 쉬었다 올리기");
    useTyping.getStyleClass().add("dialog-path-label");
    useTyping.setSelected(settings.typingDelay);

    TextField typingMs = new TextField(String.valueOf(settings.typingMsPerByte));
    typingMs.getStyleClass().add("dialog-textfield");
    typingMs.setPrefColumnCount(5);
    typingMs.setPromptText(String.valueOf(ChatPane.DEFAULT_TYPING_MS_PER_BYTE));
    // 숫자만
    typingMs.textProperty().addListener((obs, was, is) -> {
      if (!is.matches("\\d{0,5}"))
        typingMs.setText(was);
    });
    typingMs.disableProperty().bind(useTyping.selectedProperty().not());
    Label typingUnit = new Label("ms / 바이트 (한글 한 글자는 3바이트)");
    typingUnit.getStyleClass().add("dialog-path-label");
    HBox typingRow = new HBox(8, typingMs, typingUnit);
    typingRow.setAlignment(Pos.CENTER_LEFT);

    HBox typingTitle = section("말풍선 뜸", () -> {
      useTyping.setSelected(true);
      typingMs.setText(String.valueOf(ChatPane.DEFAULT_TYPING_MS_PER_BYTE));
    });

    // ── 저장 / 취소 ──
    Label error = new Label();
    error.getStyleClass().add("dialog-path-label");
    error.setManaged(false);
    error.setVisible(false);

    SettingsDialog[] saved = { null };
    Runnable save = () -> {
      ChatProfile partner = pickedPartner(partnerGroup);
      if (partner != originalPartner && !confirmPartner(dialog, originalPartner, partner))
        return;
      List<String> palette = new ArrayList<>();
      for (ColorPicker picker : pickers)
        palette.add(hex(picker.getValue()));
      TodoMode todoMode = (TodoMode) todoModeGroup.getSelectedToggle().getUserData();
      settings.scanRoots = new ArrayList<>(roots);
      settings.mascotImages = new LinkedHashMap<>();
      mascotPaths.forEach((p, file) -> settings.mascotImages.put(p.id, file));
      settings.mascotFitWidth = new LinkedHashSet<>();
      fitWidth.forEach(p -> settings.mascotFitWidth.add(p.id));
      settings.mascotHidden = hideMascot.isSelected();
      var display = displayGroup.getSelectedToggle();
      settings.displayCharacter = display == null ? null : ((ChatProfile) display.getUserData()).id;
      settings.reserveMascotSpace = reserveSpace.isSelected();
      // 테마를 따르거나 기본 색 그대로면 null 로 둔다 — 나중에 기본 색을 고치면 따라가게
      settings.todoFollowTheme = todoMode == TodoMode.THEME;
      settings.todoPalette = todoMode != TodoMode.CUSTOM || palette.equals(TodoPane.DEFAULT_PALETTE) ? null : palette;
      settings.widgetOpacity = clampOpacity(opacity.getValue());
      settings.widgetTheme = pickedTheme(themeGroup).id;
      settings.usePretendard = usePretendard.isSelected();
      settings.hotkeyOpens = openAurora.isSelected() ? HOTKEY_AURORA : HOTKEY_WIDGET;
      settings.auroraTodos = auroraTodos.isSelected();
      settings.openFullScreen = startFull.isSelected();
      settings.escExitsFullScreen = escFull.isSelected();
      // 파트너를 바꾸면 호감도가 흔들리고, 수다 화면도 새 파트너랑 얘기하던 자리로 돌아간다
      if (partner != originalPartner) {
        settings.applyBond(PartnerBond.change(settings.bond(), partner, System.currentTimeMillis()));
        settings.chatPartner = null;
      }
      settings.showLiking = showLiking.isSelected();
      settings.secretNotes = useNotes.isSelected();
      settings.noteSingleClick = singleClick.isSelected();
      settings.typingDelay = useTyping.isSelected();
      // 비우면 기본 간격으로
      settings.typingMsPerByte = typingMs.getText().isBlank() ? ChatPane.DEFAULT_TYPING_MS_PER_BYTE
          : Integer.parseInt(typingMs.getText());
      // 비운 칸은 기본 이모지로. 전부 기본 그대로면 null — 할 일 색과 같은 이치
      List<String> emojis = new ArrayList<>();
      for (int i = 0; i < emojiFields.size(); i++) {
        String text = emojiFields.get(i).getText().strip();
        emojis.add(text.isEmpty() ? Affection.DEFAULT_EMOJIS.get(i) : text);
      }
      settings.likingEmojis = emojis.equals(Affection.DEFAULT_EMOJIS) ? null : emojis;
      try {
        settings.save();
        saved[0] = settings;
        dialog.close();
      } catch (IOException ex) {
        error.setText("설정을 못 썼어: " + ex.getMessage());
        error.setManaged(true);
        error.setVisible(true);
      }
    };

    Button cancel = new Button(firstRun ? "나중에" : "취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setOnAction(e -> dialog.close());

    Button ok = new Button("저장");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setOnAction(e -> save.run());

    // 지금 칸들에 들어 있는 값 — 처음 값과 달라졌으면 Esc 로 닫을 때 저장할지 묻는다
    java.util.function.Supplier<List<Object>> form = () -> {
      List<Object> colors = new ArrayList<>();
      for (ColorPicker picker : pickers)
        colors.add(picker.getValue());
      List<Object> emojis = new ArrayList<>();
      for (TextField field : emojiFields)
        emojis.add(field.getText());
      return Arrays.asList(new ArrayList<>(roots), new LinkedHashMap<>(mascotPaths), new LinkedHashSet<>(fitWidth),
          hideMascot.isSelected(), reserveSpace.isSelected(),
          themeGroup.getSelectedToggle(), partnerGroup.getSelectedToggle(), displayGroup.getSelectedToggle(), todoModeGroup.getSelectedToggle(),
          colors, opacity.getValue(), usePretendard.isSelected(), openAurora.isSelected(), auroraTodos.isSelected(), startFull.isSelected(), escFull.isSelected(), showLiking.isSelected(), emojis,
          useNotes.isSelected(), singleClick.isSelected(), useTyping.isSelected(), typingMs.getText());
    };
    List<Object> originalForm = form.get();

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    // ── 섹션 ── 위젯 UI / 캐릭터 / 훑을 폴더. 위쪽 알약으로 하나씩 갈아 끼운다.
    // 칸이 넘칠 수 있어 섹션만 굴리고 제목·단추는 붙박아둔다 (PromptDialog 와 같은 이치)
    VBox widgetPage = page(
        themeTitle, themeRow,
        opacityTitle, opacityRow, opacityHint,
        fontTitle, usePretendard,
        openTitle, openRow, auroraTodos,
        fullTitle, startFull, escFull,
        todoTitle, todoModeRow, pickerRow);
    VBox characterPage = page(
        partnerTitle, partnerToggles, partnerHint,
        displayTitle, displayToggles, displayHint,
        mascotTitle, mascotRow,
        likingTitle, showLiking, emojiRow,
        noteTitle, useNotes, clickRow,
        typingTitle, useTyping, typingRow);
    VBox folderPage = page(foldersTitle, foldersSub, list, folderButtons);

    ScrollPane scroll = new ScrollPane();
    scroll.getStyleClass().add("keys-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setPrefViewportHeight(340);

    // 처음 켠 거면 폴더부터 — 그게 제일 먼저 골라야 할 것
    VBox startPage = firstRun ? folderPage : widgetPage;
    ToggleGroup pageGroup = new ToggleGroup();
    ToggleButton characterTab = toggle(pageGroup, "🌸 캐릭터", characterPage, startPage == characterPage);
    HBox pageRow = new HBox(6,
        toggle(pageGroup, "🎨 위젯 UI", widgetPage, startPage == widgetPage),
        characterTab,
        toggle(pageGroup, "📁 훑을 폴더", folderPage, startPage == folderPage));
    // 캐릭터들은 Gemini 로 말하니까, 키가 없으면 캐릭터 탭은 잠가둔다
    boolean hasKey = !GeminiClient.readKeys().isEmpty();
    characterTab.setDisable(!hasKey);
    Label lockedHint = new Label("🔒 캐릭터 탭은 Gemini 키를 등록해야 열려. 기본 화면에 \"keys\" 를 쳐서 등록해줘.");
    lockedHint.getStyleClass().add("dialog-path-label");
    lockedHint.setWrapText(true);
    lockedHint.setVisible(!hasKey);
    lockedHint.setManaged(!hasKey);
    // 전체화면이면 섹션들이 세 칸에 따로 들어간다 (column) — 한 노드는 한 자리에만
    if (!full)
      scroll.setContent(startPage);
    pageGroup.selectedToggleProperty().addListener((obs, was, is) -> {
      if (is != null) {
        scroll.setContent((VBox) is.getUserData());
        scroll.setVvalue(0);
      }
    });

    VBox root;
    if (full) {
      // 세 칸 — 칸마다 제목을 붙박고 그 밑만 따로 굴린다. 키가 없으면 캐릭터 칸은 잠근다 (탭과 같은 이치)
      VBox characterColumn = column("🌸 캐릭터", characterPage);
      characterColumn.setDisable(!hasKey);
      HBox columns = new HBox(18, column("🎨 위젯 UI", widgetPage), characterColumn, column("📁 훑을 폴더", folderPage));
      VBox.setVgrow(columns, Priority.ALWAYS);
      root = new VBox(12, heading, lockedHint, columns, error, buttons);
      Rectangle2D screen = Screen.getPrimary().getVisualBounds();
      root.setPrefSize(Math.min(FULL_WIDTH, screen.getWidth() * 0.94), Math.min(FULL_HEIGHT, screen.getHeight() * 0.92));
    } else {
      root = new VBox(12, heading, pageRow, lockedHint, scroll, error, buttons);
      root.setPrefWidth(520);
    }
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        if (form.get().equals(originalForm)) {
          dialog.close();
          return;
        }
        // 기본은 "저장" — 고친 걸 엔터 한 번에 날리지 않게. Esc 로 닫으면 그냥 계속 고친다
        int picked = Notice.ask(dialog, "바뀐 설정이 있어. 저장할까?", "저장", "저장 안 함", "계속 고칠래");
        if (picked == 0)
          save.run();
        else if (picked == 1)
          dialog.close();
      } else if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
        e.consume();
        save.run();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      (firstRun ? addFolder : ok).requestFocus();
    });
    dialog.setOnHidden(e -> {
      if (saved[0] != null)
        return;
      // 처음 뜬 거면 "나중에" 여도 테마만은 남긴다 — 나머진 빈 채로 (파트너도 없이), 다음에 또 안 뜨게
      if (!exists()) {
        SettingsDialog fresh = new SettingsDialog();
        fresh.widgetTheme = pickedTheme(themeGroup).id;
        try {
          fresh.save();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        saved[0] = fresh;
        return;
      }
      // 저장 안 하고 닫았으면 비춰보던 테마와 투명도를 되돌린다
      if (owner != null && owner.getScene() != null)
        applyStyle(owner.getScene(), originalOpacity, originalTheme);
    });
    dialog.showAndWait();
    return saved[0];
  }

  /** 1 → "기본", 0.9 → "10% 더 비침", 1.5 → "50% 불투명", MAX → "완전 불투명" */
  private static String opacityText(double v) {
    if (v >= MAX_OPACITY - 1e-6)
      return "완전 불투명";
    if (v > 1.0)
      return Math.round((v - 1.0) / (MAX_OPACITY - 1.0) * 100) + "% 불투명";
    long percent = Math.round((1.0 - v) * 100);
    if (percent == 0)
      return "기본";
    return percent + "% 더 비침";
  }

  /** 칸 제목 + 오른쪽 끝의 "기본으로" — 그 칸만 처음 값으로 되돌린다 */
  private static HBox section(String title, Runnable reset) {
    Label label = new Label(title);
    label.getStyleClass().add("dialog-label");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    Button button = new Button("기본으로");
    button.getStyleClass().add("dialog-button");
    button.setOnAction(e -> reset.run());
    HBox row = new HBox(8, label, spacer, button);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  /** 전체화면 설정의 한 칸 — 섹션 제목 밑에 그 섹션을 따로 굴린다. 세 칸이 너비를 똑같이 나눈다 */
  private static VBox column(String title, VBox page) {
    Label label = new Label(title);
    label.getStyleClass().add("notice-title");
    ScrollPane scroll = new ScrollPane(page);
    scroll.getStyleClass().add("keys-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    VBox column = new VBox(10, label, scroll);
    column.setPrefWidth(0);
    HBox.setHgrow(column, Priority.ALWAYS);
    return column;
  }

  /** 섹션 하나 — 칸들을 세로로 쌓는다. 오른쪽은 스크롤 막대 자리 */
  private static VBox page(javafx.scene.Node... nodes) {
    VBox page = new VBox(12, nodes);
    page.setPadding(new Insets(0, 6, 0, 0));
    return page;
  }

  private static Theme pickedTheme(ToggleGroup group) {
    var picked = group.getSelectedToggle();
    return picked == null ? Theme.DEFAULT : (Theme) picked.getUserData();
  }

  /** 파트너 "없음" 알약에 다는 표 */
  private static final Object NO_PARTNER = new Object();

  /** 고른 파트너. "없음" 이면 null */
  private static ChatProfile pickedPartner(ToggleGroup group) {
    var picked = group.getSelectedToggle();
    return picked != null && picked.getUserData() instanceof ChatProfile p ? p : null;
  }

  /** 파트너를 고르고 · 바꾸고 · 끊기 전에 한 번 더 묻는다. 그래도 하겠다면 true */
  private static boolean confirmPartner(Window owner, ChatProfile before, ChatProfile after) {
    String message;
    if (before == null)
      message = after.displayName() + " 을(를) 파트너로 정할까?\n\n"
          + "파트너는 신중히 골라줘. 나중에 바꾸거나 끊으면 " + after.displayName() + " 마음이 크게 상할 수 있어.";
    else if (after == null)
      message = before.displayName() + " 이(가) 이제 파트너가 아니게 돼. 정말 끊을까?\n\n"
          + before.displayName() + " 마음이 크게 상할 수 있어. 신중히 생각해줘.";
    else
      message = "파트너를 " + before.displayName() + " 에서 " + after.displayName() + " (으)로 바꿀까?\n\n"
          + before.displayName() + " 마음이 크게 상할 수 있어. 신중히 골라줘.";
    // 엔터 한 번에 넘어가지 않게 기본은 "다시 생각할래"
    if (Notice.ask(owner, message, "다시 생각할래", "그래도 할래") != 1)
      return false;
    // 호감도가 음수인 상대는 골라줘도 안 기뻐한다 — 오히려 떨어지니 한 번 더 묻는다 (PartnerBond)
    if (after == null || PartnerBond.liking(after) >= 0)
      return true;
    return Notice.ask(owner, after.displayName() + " 의 호감도가 " + PartnerBond.liking(after) + " 이야.\n\n"
        + "지금 사이로는 파트너가 돼도 반기지 않아. 호감도가 오르기는커녕 더 떨어질 거야. 정말 파트너로 정할까?",
        "다시 생각할래", "그래도 정할래") == 1;
  }

  private static void selectToggle(ToggleGroup group, Object data) {
    for (var toggle : group.getToggles())
      if (toggle.getUserData() == data)
        toggle.setSelected(true);
  }

  /**
   * 하나만 고르는 알약 단추 (LLM 검색의 모드 스위치 생김새). 고른 걸 다시 눌러도 꺼지지 않는다 —
   * 무리마다 늘 하나는 골라져 있게.
   */
  private static ToggleButton toggle(ToggleGroup group, String label, Object data, boolean selected) {
    ToggleButton b = new ToggleButton(label);
    b.getStyleClass().add("llm-mode");
    b.setUserData(data);
    b.setToggleGroup(group);
    b.setSelected(selected);
    b.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
      if (b.isSelected())
        e.consume();
    });
    return b;
  }

  /** 색 묶음을 칸에 채운다. 묶음이 짧으면 처음부터 돌려 쓴다 */
  private static void applyPalette(List<ColorPicker> pickers, List<String> palette) {
    for (int i = 0; i < pickers.size(); i++)
      pickers.get(i).setValue(Color.web(palette.get(i % palette.size())));
  }

  /** #rrggbb — 투두메이트 색과 같은 꼴로 남긴다 */
  private static String hex(Color c) {
    return String.format("#%02x%02x%02x",
        Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
  }

  /** 띄운 쪽 창 한가운데로 (Notice 와 같은 이치) */
  private static void centerOn(Stage popup, Window owner) {
    if (owner == null || owner.getWidth() <= 0 || Double.isNaN(owner.getX())) {
      popup.centerOnScreen();
      return;
    }
    popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
    popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
  }
}
