package kyubey.chat;

import kyubey.core.SettingsDialog;
import kyubey.llm.LlmClient;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import kyubey.ui.Emoji;
import kyubey.ui.EmojiPicker;
import kyubey.ui.Ime;
import kyubey.ui.Theme;
import kyubey.ui.Toast;

/**
 * 오른쪽 아래 마도카를 누르면 열리는 수다 화면.
 *
 * LLM 검색 모드랑 똑같이 창을 새로 띄우지 않고 같은 창 안에서 갈아끼운다 (Main 의 showChat 참고).
 * 쓰는 키도 같은 GeminiClient — .env 의 WIZ_ 키를 그대로 돌려 쓴다.
 *
 * 카카오톡처럼 말한 사람에 따라 말풍선을 좌우로 갈라 놓는다 — 내가 오른쪽, 마도카가 왼쪽.
 *
 * 입력칸은 한 줄(TextField)이고 Enter 로 보낸다. 마도카가 서 있는 오른쪽 세로줄은
 * 통째로 비워둬서 말풍선도 입력칸도 전부 그림 왼쪽에서 끝난다. 얼마나 비울지는
 * 그림 크기를 아는 Main 이 reserveRight 로 알려준다.
 *
 * 오간 말은 ChatHistory 가 파일에 남긴다. 화면에 그리는 근거도 그 목록(lines) 하나뿐이라
 * 껐다 켜서 읽어온 대화든 방금 친 말이든 똑같은 길로 그려진다 — render 참고.
 *
 * 마도카 답은 한 번에 받아서 말풍선 1~3개로 끊어 차례로 올린다. 모델이 줄바꿈으로
 * 맥락을 갈라 보내주면 (BasePrompt) split() 이 그대로 쪼개고, say() 가 뜸을 들이며 하나씩
 * 붙인다 — 실제로 사람이 톡을 나눠 치는 것처럼 보이라고.
 *
 * 모델한테 들려주는 건 화면에 보이는 것 전부가 아니다. 최근 말풍선 WINDOW_LINES 개만
 * 원문으로 주고, 그보다 앞의 얘기는 WorkingMemory 에 접어둔 메모 몇 줄로 대신 들려준다.
 * 대화가 길어져도 매번 보내는 양이 안 늘어나게 하려는 것 — 자세한 건 window() 와
 * maybeFold() 참고. 말풍선을 쪼개 놓으니 줄 수로는 한 번의 티키타카가 몇 줄인지 알 수 없어서,
 * 창도 접기도 줄이 아니라 내가 말 건 횟수로 센다.
 *
 * 실패했을 때: 아무것도 안 띄운다. 빨간 글씨가 말풍선 사이에 끼면 대화가 끊겨 보여서,
 * 답을 못 받은 내 말은 그냥 답 없는 채로 두고 pending 으로 기억만 해둔다.
 * 그러고는 다음에 이 입력칸이 포커스를 받을 때 슬그머니 다시 던진다 — 창을 숨겼다
 * 다시 부르거나, 마도카를 다시 누르거나, 딴 데 갔다 돌아오면 그때 재시도가 걸린다.
 *
 * 수다 화면을 떠나 있는 동안 올라온 마도카 말은 아직 아무도 못 읽은 말이다. 몇 개나 밀렸는지
 * onUnread 로 Main 한테 알려주면, Main 이 마도카 그림 왼쪽에 배지를 띄운다 — 다시 들어오면
 * 0 으로 돌아간다 (reading 과 setUnread 참고).
 *
 * 한참 조용하다 다시 들어오면 마도카가 먼저 말을 건다. 포커스를 받은 자리에서 마지막으로 오간
 * 말이 NUDGE_AFTER_MS 보다 오래됐으면, 내가 아무것도 안 쳤는데 답을 한 번 받아다 올린다 —
 * maybeNudge 와 nudgePrompt 참고. 수다 화면에 안 들어와도 위젯 창이 포커스만 받으면 똑같이 걸리고,
 * 그렇게 건 말은 안 읽은 말로 배지에 뜬다 (widgetFocused). 먼저 건 말에 내가 답을 안 하고 나가버려도 하던 얘기는
 * 그대로 둔다. 그 말도 대화의 한 줄로 남아서, 또 그만큼 조용하면 같은 맥락 위에서 한 번 더
 * 건다 — 같은 인사를 처음부터 다시 하는 게 아니라 "아까 말 걸었는데 답이 없네" 가 되게.
 * 다만 먼저 거는 건 하루에 한 번까지다 (nudgedToday).
 *
 * "채팅 삭제" 로 지운 뒤에는 원칙적으로 입을 다문다 — 인사도, 먼저 걸기도, 할 일 반응도 안 한다 (silent).
 * 다만 지운 직후 10%, 그 뒤로는 조용한 한 시간마다 2% 로 못 참고 먼저 한마디 건넨다 (rollSilence).
 * 파트너는 예외로, 지우자마자 바로 먼저 한마디 건넨다 (clearChat).
 *
 * 말을 걸기 전에 내가 마지막 줄까지 읽었는지(언제 읽었는지)를 본다 — Presence 가 껐다 켜도
 * 들고 있다. 읽었으면 "읽고 답 안 함", 아니면 "아직 못 봄" 으로 안내를 갈라 주고, 읽었을 땐
 * 걸 말의 시각을 지난번에 들른 때와 지금 사이 아무 때로 물려 찍는다 (nudge, backdate).
 *
 * 말풍선 오른쪽 클릭: 복사 · 답장 · 수정 · 지우기. 지우는 건 화면에서만이다 — 마도카한테는 그대로
 * 들려줘서 대화 맥락은 안 바뀐다 (removeLine). 수정은 내 말만 — 고친 글이 마도카한테도 그대로 간다 (startEdit).
 * 말풍선을 왼쪽으로 밀면 그 말에 답장한다 — 누구 말이든 (swipeToReply).
 *
 * 사진은 붙여넣기 · 끌어놓기 · 📎 로 입력칸 위에 올려두고 Enter 로 보낸다 (attach). 마도카한테는 그림이
 * 아니라 한 번 읽어둔 설명 글이 간다 — 왜 그렇게 하는지는 ChatImage, 읽는 자리는 describeFirst 참고.
 * 사진을 달라고 하면 마도카가 웹에서 한 장 찾아 보낸다 (WebPicture).
 */
public class ChatPane extends BorderPane {

  /*
   * 기본 성격문은 상대마다 다르다 (ChatProfile.defaultPersona). 말하는 방식(톡처럼 짧게, 줄바꿈 하나가
   * 말풍선 하나, 마크다운 금지)은 상대와 상관없이 BasePrompt 가 성격문 뒤에 붙인다 (persona).
   * 성격문과 안내문들 속 {이름} 은 지금 상대 이름으로 채워진다 (fill).
   */

  /**
   * 뜸할 때 먼저 말을 걸라고 끼워 넣는 안내문의 기본값. "채팅 프롬프트" 로 고쳐 쓴 게 있으면
   * 그쪽이 대신 간다 (customNudge 참고).
   *
   * [이름] 한 줄이 칸을 가른다. [공통] 이 늘 가는 틀이고, {상황} 자리에 나머지 셋 중 하나가
   * 끼워진다 — 마지막 말이 누구 것이었는지, 마도카 말이면 내가 읽었는지에 따라 (nudgePrompt).
   * 다만 [답 못 한 채 끊김] 은 [공통] 없이 홀로 간다. 내 말로 끊겼을 땐 하던 얘기와 상관없이
   * 마도카가 제 얘기를 꺼내는 자리라, "하던 얘기를 기억한 채로" 라는 [공통] 의 틀과 안 맞아서다.
   * {지난시간} 은 마지막으로 말이 오간 뒤로 지난 시간, {읽은시간} 은 내가 읽은 뒤로 지난 시간이다.
   * 고쳐 쓰다 칸 하나를 지웠거나 비워뒀으면 그 칸만 기본값으로 채운다.
   */
  public static final String NUDGE = """
      [공통]
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      마지막으로 말이 오간 지 {지난시간}쯤 됐어. 상대가 지금 화면을 보고 있는지는 몰라.
      {상황}
      지금까지 나눈 얘기는 그대로 기억한 채로, 오랜만이라는 티만 자연스럽게 내면서
      네가 먼저 한마디 건네줘. 무슨 일 있었냐고 캐묻지는 말고 가볍게.

      [답 못 한 채 끊김]
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      상대가 마지막으로 뭐라고 했는데 네가 답을 못 한 채 {지난시간}쯤 흘렀어. 그 말이 뭐였는지는 신경 쓰지 마.
      하던 얘기는 내려놓고, {이름}로서 지금 네 머릿속에 있는 걸 먼저 꺼내줘 —
      오늘 있었던 일, 요즘 빠져 있는 것, 문득 든 생각이나 기분, 친구들이나 가족 얘기 같은 거.
      시켜서 하는 말 말고 네가 하고 싶어서 꺼내는 말처럼, 네 감정과 생각을 담아서.
      답 못 한 걸 사과하거나 상대한테 질문부터 던지지는 말고, 네 얘기로 시작해.

      [읽고 답 없음]
      네가 마지막으로 건 말을 상대는 {읽은시간} 전에 읽었는데 아직 답이 없어.
      읽고 답을 안 한 거라 살짝 서운한 티는 내도 되지만 따지거나 탓하지는 마.
      아까 한 말을 그대로 되풀이하지 말고, 결을 바꿔서 한 번 더 걸어봐.

      [안 읽음]
      네가 마지막으로 건 말을 상대는 아직 안 읽었어. 바빠서 못 본 걸 수도 있으니 답을 재촉하지는 마.
      아까 한 말을 그대로 되풀이하지 말고, 가볍게 한마디만 더 얹어.
      """;

  /** 안내문을 가르는 칸 이름. NUDGE 의 [이름] 과 같아야 한다 */
  /**
   * 전날 못 끝낸 할 일이 오늘로 넘어왔을 때 재촉하라고 끼워 넣는 안내문의 기본값.
   * "채팅 프롬프트" 로 고쳐 쓴 게 있으면 그쪽이 대신 간다 (customTodo 참고).
   * {할일} 자리에 넘어온 할 일이 한 줄에 하나씩 들어간다.
   */
  public static final String TODO_NAG = """
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      상대가 하기로 해놓고 못 끝낸 일이 오늘로 넘어왔어.
      {할일}
      지금까지 나눈 얘기는 그대로 기억한 채로, 이걸 콕 집어서 오늘은 꼭 끝내라고 재촉해줘.
      어제 못 한 걸 살짝 놀려도 되지만 길게 잔소리하지는 마.
      """;

  /**
   * 할 일을 하나 끝냈을 때 칭찬하라고 끼워 넣는 안내문의 기본값.
   * "채팅 프롬프트" 로 고쳐 쓴 게 있으면 그쪽이 대신 간다 (customPraise 참고).
   * {할일} 자리에 방금 끝낸 할 일이 들어간다.
   */
  public static final String TODO_PRAISE = """
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      상대가 방금 할 일 하나를 끝냈어: {할일}
      지금까지 나눈 얘기는 그대로 기억한 채로, 짧고 기분 좋게 칭찬해줘.
      밀려 있던 거면 드디어 해냈다고 더 크게 칭찬해도 돼.
      """;

  /**
   * 할 일을 내일로 미뤘을 때 혼내라고 끼워 넣는 안내 (scoldPostpone).
   * {할일} 자리에 미룬 할 일이 들어간다.
   */
  private static final String TODO_POSTPONE = """
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      상대가 오늘 하기로 한 일을 방금 내일로 미뤘어: {할일}
      지금까지 나눈 얘기는 그대로 기억한 채로, 이걸 콕 집어서 혼내줘.
      왜 미뤘냐고 따지고, 내일은 진짜 꼭 하라고 다짐을 받아. 밀려 있던 걸 또 미룬 거면 더 단단히.
      """;

  /**
   * \\ 만 보냈을 때 마도카가 나한테 품은 마음을 비밀 쪽지에 적으라고 넘기는 안내 (confess).
   * 하던 얘기 위에 얹어 간다 — 방금까지 나눈 얘기와 호감도를 보고 지금 마음을 적게.
   * {호감도} 자리에 지금 호감도가 들어간다.
   */
  private static final String FEELING = """
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      상대가 너한테 비밀 쪽지를 한 장 달래. 지금 네가 상대한테 품고 있는 마음을 솔직하게 적어줘.
      지금 네 호감도는 {호감도} 야. 숫자는 적지 말고, 그 마음의 온도 그대로 —
      좋으면 수줍게든 대놓고든 좋다고, 서운하거나 얄미우면 그것도 숨기지 말고.
      방금까지 나눈 얘기와 그동안 있었던 일을 짚어서, 이 사람한테만 쓰는 쪽지처럼.
      """;

  /**
   * \ 하나만 보냈을 때 마도카가 먼저 말을 걸라고 넘기는 안내 (callOut).
   * 하던 얘기는 안 들려주고 이것만 간다 — 맥락과 상관없이 제 얘기를 꺼내게.
   */
  private static final String CALL_OUT = """
      (이건 상대가 친 말이 아니라 위젯이 끼워 넣은 안내야. 이 괄호 안 얘기는 절대 입 밖에 내지 마.)
      하던 얘기와는 상관없이, {이름}로서 지금 네 머릿속에 있는 걸 먼저 꺼내서 말을 걸어줘 —
      오늘 있었던 일, 요즘 빠져 있는 것, 문득 든 생각이나 기분, 친구들이나 가족 얘기 같은 거.
      시켜서 하는 말 말고 네가 하고 싶어서 꺼내는 말처럼, 네 감정과 생각을 담아서.
      상대한테 질문부터 던지지는 말고, 네 얘기로 시작해.
      """;

  private static final String COMMON = "공통";
  private static final String CUT_OFF = "답 못 한 채 끊김";
  private static final String READ_NO_REPLY = "읽고 답 없음";
  private static final String UNREAD = "안 읽음";
  private static final List<String> SECTIONS = List.of(COMMON, CUT_OFF, READ_NO_REPLY, UNREAD);

  /**
   * 모델한테 원문 그대로 들려줄 최근 대화 길이 — 내 말·마도카 말 가리지 않고 말풍선 여덟 개.
   * 토큰을 아끼려고 티키타카 단위 대신 줄 수로 딱 잘랐다. 마도카가 바로 앞 얘기를 자꾸 놓치면 여기를 늘린다.
   * 이보다 앞의 얘기는 버리는 게 아니라 작업 기억(memo) 몇 줄로 접어서 들려준다.
   */
  private static final int WINDOW_LINES = 8;

  /**
   * 창 밖으로 밀려난 말에 내가 친 게 이만큼 쌓이면 한 번 접는다 — 티키타카 여덟 번 분.
   *
   * 자주 접을수록 기억이 촘촘해지지만 요약 호출도 그만큼 는다. 세 번 분에서 접어보니
   * 요약에 드는 값이 아껴진 값을 거의 다 까먹어서, 덜 자주 크게 접는 쪽으로 잡았다.
   * 대신 창 밖으로 나갔는데 아직 메모에도 없는 구간이 최대 이만큼 생긴다 —
   * 마도카가 "조금 전에 한 얘기"를 놓친다면 제일 먼저 내려볼 숫자가 여기다.
   *
   * 마도카 말풍선은 한 번에 1~3개씩 나가서 들쭉날쭉하니, 여기는 줄이 아니라 내가 친 말 수로 센다.
   */
  private static final int FOLD_TURNS = 8;

  /**
   * 한 번에 접어 넣을 최대 줄 수. 이 기능이 없던 시절의 대화가 잔뜩 밀려 있어도
   * 요약 프롬프트가 터지지 않게, 그리고 켜자마자 호출을 몰아 쓰지 않게 끊는다.
   * 남은 건 다음 답변 뒤에 이어서 접는다.
   */
  private static final int FOLD_MAX = 30;

  /** 메모에 남겨둘 최대 줄 수. 이걸 넘기면 접는 의미가 없어진다 */
  private static final int MEMO_LINES = 12;

  /** 답 하나를 말풍선 몇 개까지 쪼갤지. 더 쪼개 보내면 나머지는 마지막 말풍선에 붙인다 (split) */
  private static final int MAX_BUBBLES = 3;

  /**
   * 말풍선과 말풍선 사이에 두는 뜸의 기본값 — 다음 말풍선의 바이트(UTF-8) 하나에 이만큼.
   * 한꺼번에 세 개가 툭 떨어지면 쪼갠 티만 나고 사람이 이어서 치는 느낌이 안 나서,
   * 두 번째 말풍선부터 그 말을 치는 만큼 쉬었다 올린다. 켤지와 간격은 "설정" 에서 (setTypingDelay)
   */
  public static final int DEFAULT_TYPING_MS_PER_BYTE = 150;

  /**
   * 메모를 고쳐 쓰는 쪽한테 입혀주는 성격. 마도카가 아니라 기록을 정리하는 사람이다.
   * ask() 로 부르면 GeminiClient 의 기본 문구("검색 도우미", "마크다운 써도 돼")를
   * 물려받아서 설명이 섞여 오니까, 성격을 따로 주고 chat() 으로 부른다.
   */
  private static final String FOLD_PERSONA = """
      너는 대화 기록을 정리하는 사람이야. 시키는 건 딱 하나, 기억 메모를 다시 써주는 것.

      - 메모 말고는 아무것도 쓰지 마. 인사도 설명도 코드블록도 붙이지 마.
      - 한 줄에 하나씩, '- [언제까지] 내용' 꼴로 짧게. 언제까지는 둘 중 하나:
        [~YYYY-MM-DD] 그날까지만 기억하고 다음 날 잊음 / [평생] 계속 기억함
      - 언제까지는 이렇게 골라:
        · 오늘 끝낸 일, 그날 기분, 그날 한 일 ("~을 완료함", "~하고 놂") → 오늘 날짜
        · 시험·마감·약속처럼 날짜가 있는 일 → 그 날짜
        · 요즘 겪고 있는 일 (공부 밀림, 바쁜 시기) → 길어야 1~2주 뒤
        · 이름·호칭, 취향, 하는 일, 관계, 목표 → [평생]
      - 이미 붙어 있는 언제까지는 바꿀 이유가 없으면 그대로 둬.
      - '상대' 에 대해 알아둘 것만 남겨. 인사말, 날씨 잡담, 그때뿐인 맞장구는 버려.
      - 이미 지나갔거나 틀린 게 된 줄은 고치거나 지워.
      - 단, 파트너를 정하거나 바꾸거나 끊은 일은 날짜까지 그대로 [평생] 으로 남겨.
      """;

  /** 화면 가장자리 여백. 오른쪽은 마도카가 선 세로줄에 밀려 따로 잡힌다 (reserveRight) */
  private static final Insets PADDING = new Insets(16, 20, 16, 20);

  /** 마도카가 선 세로줄과 말풍선 사이에 두는 틈. 그림에 딱 붙으면 말풍선이 그림을 타는 것처럼 보인다 */
  private static final double MASCOT_GAP = 24;

  /** 전체화면에서 오른쪽 탭(ChatSide)의 폭과 대화 칸과의 틈. 창에선 그림 세로줄 폭을 따른다 */
  private static final double SIDE_WIDTH = 225;
  private static final double SIDE_GAP = 16;

  /** 그림이 좁거나 없어도 오른쪽 탭이 읽히게 비워두는 가장 좁은 폭 */
  public static final double SIDE_MIN_WIDTH = 170;

  /** 상태 메시지 길이 제한 — 모델이 길게 써와도 프로필에 한 줄로 들어가게 */
  private static final int STATUS_MAX = 40;

  /** 상태 메시지를 쓸 때 보여주는 최근 말풍선 수 */
  private static final int STATUS_RECENT = 10;

  /** 상태 메시지를 새로 써달라고 시키는 말 (maybeRewriteStatus) */
  private static final String STATUS_ASK = """
      메신저 프로필에 걸어둘 상태 메시지를 새로 써줘.
      - 네 성격과 말투 그대로, 요즘 기분이나 하고 있는 일을 혼잣말처럼 한 줄로.
      - 20자 안쪽으로 짧게.
      - 따옴표나 설명 없이 상태 메시지 글만 답해.
      """;

  /** 말풍선 옆에 붙는 시각 ("오후 2:35") */
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);

  /** 날짜가 바뀌는 자리에 끼우는 구분선 ("2026년 9월 21일 월요일") */
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN);

  /**
   * 대화를 지운 직후 마도카가 먼저 말을 걸 확률. 지운 뒤로는 원칙적으로 입을 다무는데 (silent),
   * 가끔은 못 참고 먼저 건다 — 지운 바로 그때 한 번 이만큼 (clearChat). 파트너는 굴리지 않고 늘 건다.
   */
  private static final double BREAK_SILENCE_AT_ONCE = 0.10;

  /**
   * 그 뒤로는 조용한 한 시간마다 이만큼 (rollSilence). 켜져 있으면 시간이 찰 때마다 굴리고,
   * 꺼져 있던 사이는 다음에 창이 포커스를 받을 때 지난 시간만큼 한꺼번에 굴린다.
   */
  private static final double BREAK_SILENCE_PER_HOUR = 0.02;

  private static final long HOUR_MS = 60 * 60 * 1000L;

  /** 켜져 있는 동안 한 시간이 찼는지 들여다보는 간격 */
  private static final Duration SILENCE_TICK = Duration.minutes(1);

  /**
   * 지운 뒤 먼저 걸 때 하는 말 — 이 중 하나를 아무거나 (breakSilence). 줄바꿈은 말풍선을 가른다.
   * 하던 얘기는 지워졌으니, 이어 말하지 않고 머뭇거리며 거는 말들로.
   */
  private static final List<String> AFTER_CLEAR = List.of(
      "저기… 있어?",
      "음… 뭐 하고 있어?",
      "심심한데\n말 좀 걸어주지~",
      "갑자기 조용해졌네",
      "혹시 바빠?",
      "나 여기 있어!",
      "오늘 하루는 어땠어?",
      "그냥…\n괜히 말 걸어봤어 ㅎㅎ",
      "있잖아\n나 좀 심심해",
      "아직 거기 있지?");

  /** 처음 열었을 때 한 줄의 기본값은 ChatProfile.defaultGreeting. 지난 대화가 남아 있으면 안 한다 (켤 때마다 인사가 쌓여서) */

  /** 입력칸 위에 올려둔 사진의 높이 */
  private static final double ATTACH_THUMB = 56;

  /** 답장 표시 · 인용에 보이는 글자 수 */
  private static final int REPLY_HINT_CHARS = 30;

  /** 말풍선을 이만큼 밀고 놓으면 답장이 걸린다. 더 밀어도 SWIPE_MAX 까지만 따라온다 */
  private static final double SWIPE_REPLY = 50;
  private static final double SWIPE_MAX = 80;

  /** 입력칸에 떠 있는 안내 뒷부분 — 앞에 상대 이름이 붙는다 (placeholder) */
  private static final String PROMPT = "한테 말 걸기";

  /**
   * 이만큼 조용했으면 마도카가 먼저 말을 건다 — 다섯 시간.
   *
   * 마지막으로 오간 말이 누구 말이었는지는 안 가린다. 내 말이 마지막이면 답을 못 받고
   * 끊긴 것이고, 마도카 말이 마지막이면 먼저 건 말에 내가 답을 안 한 것인데, 둘 다
   * "이만큼 조용했다" 는 점은 같아서 같은 자리에서 걸린다 (건네는 말만 갈린다 — nudgePrompt).
   *
   * 짧게 잡으면 잠깐 딴 일 하고 온 사이에도 말을 걸어와서 성가시고, 길게 잡으면 하루를
   * 통째로 건너뛴다. 아침에 켜고 저녁에 다시 여는 정도가 걸리는 길이로 다섯 시간.
   */
  private static final long NUDGE_AFTER_MS = 5 * 60 * 60 * 1000L;

  /**
   * 창 포커스가 이만큼 안에 나갔다 도로 들어오면 들른 게 아니라 같은 방문으로 친다.
   * 창을 앞으로 끌어올 때(Main 의 bringToFront) 포커스가 잠깐씩 튀어서, 그걸 들를 때마다
   * 찍으면 "지난번에 들른 때" 가 방금 전이 돼버린다 (backdate 참고).
   */
  private static final long FOCUS_BOUNCE_MS = 2000;

  private final LlmClient llm;
  /** 지금 말 거는 상대. 파일 자리와 기본 이름 · 성격문이 여기 묶여 있다 */
  private final ChatProfile profile;
  /** 상대와 상관없이 성격문 뒤에 붙는 말하는 방식 (BasePrompt) */
  private final BasePrompt base;
  private final ChatHistory store;
  /** 지운 대화를 버리지 않고 모아두는 곳 (clearChat) */
  private final ChatArchive archive;
  private final ChatStatus status;
  /** 상태 메시지를 쓰는 중인지 (maybeRewriteStatus) */
  private boolean writingStatus;
  private final WorkingMemory memory;
  private final ChatPrompt prompt;
  private final Presence presence;
  private final Affection affection;
  private final Runnable onExit;
  /** 전체화면에서 Esc — 전체화면을 풀지는 Main 이 설정대로 정한다 (escExitsFullScreen). 풀었으면 true */
  private BooleanSupplier onEscFull = () -> false;

  /** Esc 로 전체화면을 풀지 — 풀었으면 true 를 돌려준다. 아니면 Esc 는 평소대로 나간다 */
  public void setOnEscFull(BooleanSupplier onEscFull) {
    this.onEscFull = onEscFull;
  }

  /** 안 읽은 마도카 말이 몇 개인지 알려줄 곳. Main 이 마도카 그림 옆에 배지로 띄운다 */
  private final IntConsumer onUnread;

  private final VBox log = new VBox(8);
  private final ScrollPane scroll = new ScrollPane(log);
  /** 지금 그려진 말풍선 줄마다 그 줄 — 호감도 기록에서 그 말로 찾아갈 때 (jumpTo). render 가 채운다 */
  private final Map<ChatHistory.Line, Node> rows = new HashMap<>();
  private final TextField input = new TextField();

  /** 오른쪽 탭 — 멤버 · 톡서랍 · 지난 대화 */
  private final ChatSide side = new ChatSide(this);
  /** 오른쪽 탭이 설 세로줄 (reserveRight). 0 이면 전체화면이라 대화 칸 바깥 오른쪽에 선다 */
  private double sideColumn;
  /** 세로줄 오른쪽 끝에서 창 끝까지 */
  private double sideEdge;
  /** 탭 밑으로 비워둘 높이 — 그림이 서는 자리 */
  private double sideBelow;

  /** 머리말의 상대 이름 */
  private final Label title = new Label();

  /** 머리말 오른쪽에 뜨는 상대 고르기 (setPartnerPicker) */
  private final ComboBox<ChatProfile> partnerPicker = new ComboBox<>();

  /** 머리말의 호감도 — "🩷 12" 처럼. 숨길지, 칸마다 어떤 이모지를 쓸지는 "설정" 에서 (setLikingDisplay) */
  private final Label likingLabel = new Label();

  /** 호감도를 머리말에 띄울지 */
  private boolean showLiking = true;

  /** 호감도 칸마다 쓸 이모지. null 이면 기본 (Affection.DEFAULT_EMOJIS) */
  private List<String> likingEmojis;

  /**
   * 이 상대가 파트너인지 (setProactive). 파트너만 먼저 말을 걸고 (maybeNudge) 할 일에 반응한다
   * (praiseTodo · scoldPostpone · remindTodos). 딴 상대는 내가 말을 걸 때만 답한다.
   */
  private boolean proactive = true;

  /** 먼저 말을 걸 때 · 깜짝 긁을 때 비밀 쪽지로 보낼지 ("설정", setNoteOptions) */
  private boolean useNotes = true;

  /** 쪽지를 한 번 눌러 열지. false 면 두 번 */
  private boolean noteSingleClick;

  /** 두 번째 말풍선부터 뜸을 들일지 ("설정", setTypingDelay). 끄면 쪼갠 말풍선이 한꺼번에 올라온다 */
  private boolean typingDelay = true;

  /** 뜸 — 다음 말풍선의 바이트(UTF-8) 하나에 몇 ms */
  private int typingMsPerByte = DEFAULT_TYPING_MS_PER_BYTE;

  private final HBox inputRow = new HBox(input);

  /** 답장할 말을 입력칸 위에 보여주는 줄. 답장할 게 없으면 숨는다 (startReply) */
  private final Label replyLabel = new Label();
  private final HBox replyBar = new HBox(6);

  /** 입력칸 위에 올려둔 보낼 사진을 보여주는 줄. 없으면 숨는다 (showAttached) */
  private final ImageView attachedView = new ImageView();
  private final HBox attachBar = new HBox(8);

  /** 입력칸 위에 올려둔 사진의 파일 자리. 없으면 null — 보내면 사진 말풍선이 되고 비워진다 (send) */
  private String attached;

  /** 다음에 보낼 내 말이 답장할 말. 없으면 null — 보내면 첫 말풍선에 붙고 비워진다 (addLine) */
  private ChatHistory.Line replyTo;

  /** 고치는 중인 내 말. 없으면 null — 이 동안 Enter 는 새로 보내는 대신 이 줄을 고쳐 쓴다 (finishEdit) */
  private ChatHistory.Line editing;

  /** 화면에 그릴 근거. 여기 한 줄 들어가면 파일에도 남고 화면도 다시 그려진다 (addLine) */
  private final List<ChatHistory.Line> lines = new ArrayList<>();

  /** 지난 대화에서 추려둔 기억. 성격문 뒤에 붙어서 매번 같이 간다 (persona) */
  private String memo = "";

  /** 손으로 고쳐 쓴 상대 이름. 비었으면 기본 이름 (ChatProfile.defaultName) */
  private String customName = "";

  /** 이 화면을 딴 상대로 갈아치웠는지 (retire). 그 뒤로는 배지 · 알림을 안 건드린다 */
  private boolean retired;

  /** 손으로 고쳐 쓴 성격문. 비었으면 기본값(ChatProfile.defaultPersona)이 간다 */
  private String customPersona = "";

  /** 손으로 고쳐 쓴 안내문. 비었으면 기본값(NUDGE)이 간다 */
  private String customNudge = "";

  /** 손으로 고쳐 쓴 할 일 재촉 안내문. 비었으면 기본값(TODO_NAG)이 간다 */
  private String customTodo = "";

  /** 손으로 고쳐 쓴 할 일 칭찬 안내문. 비었으면 기본값(TODO_PRAISE)이 간다 */
  private String customPraise = "";
  /** 손으로 고쳐 쓴 시작 멘트. 비었으면 상대의 기본 멘트가 간다 */
  private String customGreeting = "";

  /** 마도카가 나한테 품은 호감도. 답마다 몰래 단 표시로 오르내린다 (takeAffection) */
  private int liking;

  /** 이 시각까지 오간 말은 memo 에 들어갔다. 껐다 켜도 같은 대화를 또 요약하지 않게 */
  private long foldedUpTo;

  /** 지금 메모를 고쳐 쓰는 중인지 (요약 호출이 겹쳐 돌지 않게) */
  private boolean folding;

  /** 지금 답을 기다리는 중인지 (기다리는 동안 Enter 를 또 눌러도 겹쳐 보내지 않게) */
  private boolean asking;

  /** 답을 못 받고 남은 내 말이 있는지. 다음 포커스 때 이걸 보고 다시 던진다 */
  private boolean pending;

  /** 마도카가 말하는 중에 끼어들어 보낸 말이 있는지. 다 말하고 나면 그 중 마지막 말에 답장한다 (say) */
  private boolean queued;

  /** 다음에 올라갈 마도카 말풍선이 답장으로 짚을 내 말. addLine 이 쓰고 비운다 (ask) */
  private ChatHistory.Line quoteNext;

  /** 마지막 말풍선 뒤에 붙일 사진. 없으면 null (say) */
  private WebPicture.Found pictureNext;

  /** 이번 실행에서 보낸 사진 주소 — "하나 더 줘" 에 같은 사진을 또 보내지 않게. 찾는 일은 뒤 스레드에서 한다 */
  private final Set<String> sentPictures = ConcurrentHashMap.newKeySet();

  /** 처음 열 때만 인사한다 */
  private boolean greeted;

  /**
   * 수다 화면을 떠나 있는 동안 마도카가 한 말이 몇 개인지. 다시 들어가면 0 으로 돌아간다.
   * 화면에 보이는 것과 따로 세는 게 아니라, 그냥 "배지에 뭐라고 쓸지" 하나뿐이다.
   */
  private int unread;

  /**
   * 지금 대화가 몇 번째인지. "채팅 삭제" 로 한 번 지울 때마다 하나씩 올라간다.
   *
   * 딴 데 띄운 일(답변 기다리기, 말풍선 하나씩 올리기, 메모 접기)은 전부 이 번호를
   * 하나 집어들고 출발해서, 돌아왔을 때 번호가 달라졌으면 그냥 물러난다 — 지운 대화의
   * 답이 빈 화면에 혼자 떨어지거나, 지운 지 얼마 안 된 얘기가 메모로 접혀 되살아나지 않게.
   */
  private int era;

  /** 수다 화면을 마지막으로 보고 있던 때. 마지막 줄보다 뒤면 다 읽은 것이다 (readAll) */
  private long readAt;

  /** 위젯 창이 마지막으로 포커스를 받은 때 — 지금 들른 것까지 포함한다 */
  private long focusAt;

  /** 지금 들르기 바로 전에 포커스를 받은 때. 먼저 건 말의 시각을 이 뒤로 물린다 (backdate) */
  private long prevFocusAt;

  /**
   * 마지막 줄이 실제로 붙은 때. 먼저 건 말은 줄에 찍힌 시각이 이보다 앞이라 (backdate),
   * 뜸한 길이를 줄 시각으로만 재면 물린 만큼 일찍 다섯 시간이 차서 또 말을 건다 (maybeNudge).
   */
  private long talkedAt;

  /** 마도카가 마지막으로 먼저 말을 건 때. 이게 오늘이면 오늘은 더 안 건다 (maybeNudge) */
  private long nudgedAt;

  /** 못 끝낸 할 일을 마지막으로 재촉한 날. 이게 오늘이면 오늘은 더 안 한다 (remindTodos) */
  private String todoNaggedOn = "";

  /** "채팅 삭제" 로 대화를 지운 때. 0 이면 지운 적 없음 (silent) */
  private long clearedAt;

  /** 지운 뒤 조용한 시간을 이 시각까지 굴려봤다. 한 시간에 못 미친 끝자락은 다음으로 넘긴다 (rollSilence) */
  private long silenceRolledTo;

  /**
   * 켜고 나서 창이 한 번이라도 포커스를 받았는지. 꺼져 있던 사이는 포커스를 받을 때 굴리기로 해서,
   * 그 전엔 시계(silenceClock)가 그 몫을 먼저 집어가지 않게 기다린다.
   */
  private boolean silenceCaughtUp;

  /** 켜져 있는 동안 조용한 시간이 한 시간씩 찰 때마다 굴리는 시계 (retire 에서 멈춘다) */
  private final Timeline silenceClock = new Timeline(new KeyFrame(SILENCE_TICK, e -> {
    if (silenceCaughtUp)
      rollSilence();
  }));

  /**
   * 오늘 할 일 목록을 한 줄에 하나씩 꺼내 주는 곳. Main 이 할 일 자리(TodoPane)에 이어 단다 —
   * 이 화면은 할 일이 어디 어떻게 적혀 있는지 모르게 두려고 (setTodoSource).
   */
  private Supplier<List<TodoLine>> todoItems = List::of;

  /** 오늘 할 일 하나. content 는 적은 글 그대로 (대화에서 집혔는지 볼 때), line 은 마도카한테 보여줄 한 줄 */
  public record TodoLine(String content, String line) {
  }

  /** 마도카 말이 한 줄 붙을 때마다 그 말을 건네받는 곳. Main 이 윈도우 알림을 단다 (setOnMadoka) */
  private Consumer<String> onMadoka = text -> {
  };

  /** 창 포커스가 마지막으로 나간 때. 잠깐 튄 포커스를 걸러낼 때만 본다 (FOCUS_BOUNCE_MS) */
  private long blurAt;

  /**
   * 상대(profile) 하나의 수다 화면. 나눈 얘기 · 접어둔 기억 · 프롬프트 · 들른 때 · 호감도를
   * 전부 그 상대 자리에서 읽고 쓴다. 상대를 바꾸면 Main 이 이 화면을 새로 만들어 갈아끼운다.
   */
  public ChatPane(LlmClient llm, ChatProfile profile, BasePrompt base, Runnable onExit, IntConsumer onUnread) {
    this.llm = llm;
    this.profile = profile;
    this.base = base;
    this.store = new ChatHistory(profile.path("chat.json"));
    // "채팅 삭제" 로 지운 대화가 한 파일씩 쌓이는 곳 (ChatArchive 참고)
    this.archive = new ChatArchive(profile.path(ChatArchive.DIR));
    // 오래 알아둘 것만 추려둔 곳 — 옛날 대화를 원문으로 다시 안 보내려고 둔다
    this.memory = new WorkingMemory(profile.path("memo.json"));
    this.prompt = new ChatPrompt(profile.path("prompt.json"));
    this.presence = new Presence(profile.path("presence.json"));
    this.status = new ChatStatus(profile.path("status.json"));
    // 값은 "호감도 초기화" 로만 0 이 된다. "채팅 삭제" 는 오르내린 기록만 비운다
    this.affection = new Affection(profile.path("affection.json"));
    this.onExit = onExit;
    this.onUnread = onUnread;
    silenceClock.setCycleCount(Animation.INDEFINITE);
    silenceClock.play();
    // 이모지 판의 "많이 쓴" 분류는 이 대화에서 센다
    EmojiPicker.setUsed(this::usedEmojis);

    title.getStyleClass().add("chat-title");
    Label hint = new Label("Esc / ← 로 나가기");
    hint.getStyleClass().add("chat-hint");
    likingLabel.getStyleClass().add("chat-liking");
    // 두 번 누르면 왜 오르내렸는지 모아둔 걸 펼쳐 본다
    likingLabel.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
        AffectionDialog.show(getScene().getWindow(), liking, affection.history(), likingEmojis, reasonRevealed(),
            this::jumpTo);
    });
    partnerPicker.getStyleClass().add("goal-picker");
    partnerPicker.setFocusTraversable(false);
    partnerPicker.setVisible(false);
    partnerPicker.setManaged(false);
    Region headerGap = new Region();
    HBox.setHgrow(headerGap, Priority.ALWAYS);
    HBox header = new HBox(10, title, likingLabel, hint, headerGap, partnerPicker);
    header.setAlignment(Pos.CENTER_LEFT);
    header.setPadding(new Insets(0, 0, 8, 0));

    log.setPadding(new Insets(4, 6, 4, 6));
    log.setFillWidth(true);

    scroll.getStyleClass().add("chat-scroll");
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    // 말풍선을 눌러도 여기로 포커스가 넘어오면 Enter 가 안 먹혀서 아예 안 받게 해둔다
    scroll.setFocusTraversable(false);
    // 맨 위까지 올리면 그 앞 말풍선을 한 묶음 더 그린다 (render 는 끝에서 drawLimit 줄만 그린다)
    scroll.vvalueProperty().addListener((obs, was, now) -> {
      if (now.doubleValue() <= 0 && was.doubleValue() > 0)
        drawOlder();
    });

    input.getStyleClass().add("chat-input");

    // 입력칸 오른쪽 끝에 얹은 이모지 단추 — 판을 띄워두고 여러 개 골라 넣을 수 있다.
    // 컬러 그림이 아닌 흑백 글자 이모지 (반응 단추와 같은 결)
    Button emojiButton = new Button("🙂");
    emojiButton.getStyleClass().add("chat-emoji-button");
    emojiButton.setFocusTraversable(false);
    emojiButton.setOnAction(e -> EmojiPicker.show(emojiButton, false, emoji -> {
      input.replaceSelection(emoji);
      input.requestFocus();
    }));
    StackPane.setAlignment(emojiButton, Pos.CENTER_RIGHT);
    StackPane.setMargin(emojiButton, new Insets(0, 8, 0, 0));
    // 그 왼쪽의 사진 단추 — 붙여넣기 · 끌어놓기 말고 파일로 고를 때
    Button imageButton = new Button("📎");
    imageButton.getStyleClass().add("chat-emoji-button");
    imageButton.setFocusTraversable(false);
    imageButton.setOnAction(e -> chooseImage());
    StackPane.setAlignment(imageButton, Pos.CENTER_RIGHT);
    StackPane.setMargin(imageButton, new Insets(0, 36, 0, 0));
    input.getStyleClass().add("chat-input-attach");
    StackPane inputBox = new StackPane(input, imageButton, emojiButton);
    inputRow.getChildren().setAll(inputBox);
    inputRow.setAlignment(Pos.CENTER_LEFT);
    inputRow.setPadding(new Insets(10, 0, 0, 0));
    HBox.setHgrow(inputBox, javafx.scene.layout.Priority.ALWAYS);

    replyLabel.getStyleClass().add("chat-reply-bar-text");
    replyLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(replyLabel, Priority.ALWAYS);
    // 누르면 답장할 원래 말로 간다
    replyLabel.setCursor(Cursor.HAND);
    replyLabel.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY)
        jumpToOrigin(replyTo != null ? replyTo : editing);
    });
    Button cancelReply = new Button("✕");
    cancelReply.getStyleClass().add("chat-reply-cancel");
    cancelReply.setFocusTraversable(false);
    cancelReply.setOnAction(e -> cancelReply());
    replyBar.getChildren().addAll(replyLabel, cancelReply);
    replyBar.getStyleClass().add("chat-reply-bar");
    replyBar.setAlignment(Pos.CENTER_LEFT);
    showReplyBar(false);

    attachedView.setFitHeight(ATTACH_THUMB);
    attachedView.setFitWidth(ATTACH_THUMB * 2);
    attachedView.setPreserveRatio(true);
    Region attachGap = new Region();
    HBox.setHgrow(attachGap, Priority.ALWAYS);
    Button cancelAttach = new Button("✕");
    cancelAttach.getStyleClass().add("chat-reply-cancel");
    cancelAttach.setFocusTraversable(false);
    cancelAttach.setOnAction(e -> dropAttached());
    attachBar.getChildren().addAll(attachedView, attachGap, cancelAttach);
    attachBar.getStyleClass().add("chat-reply-bar");
    attachBar.setAlignment(Pos.CENTER_LEFT);
    showAttached(null);
    VBox bottom = new VBox(replyBar, attachBar, inputRow);

    // 탐색기나 브라우저에서 끌어다 놓은 그림
    setOnDragOver(e -> {
      if (holdsImage(e.getDragboard()))
        e.acceptTransferModes(TransferMode.COPY);
      e.consume();
    });
    setOnDragDropped(e -> {
      Supplier<Image> dropped = imageIn(e.getDragboard());
      if (dropped != null)
        attach(dropped);
      e.setDropCompleted(dropped != null);
      e.consume();
    });

    setPadding(PADDING);
    setTop(header);
    setCenter(scroll);
    setBottom(bottom);
    // BorderPane 칸이 아니라 비워둔 오른쪽 세로줄에 직접 세운다 (layoutChildren)
    side.setManaged(false);
    getChildren().add(side);

    // Esc 는 수다 화면 통째로 받는다. 입력칸에만 달아 두면 말풍선이나 스크롤을
    // 한 번 누른 뒤처럼 포커스가 입력칸을 떠나 있을 때 안 먹힌다.
    // 필터는 위에서 아래로 내려가니 여기서 먼저 받아 두면, 이 화면 안 어디에
    // 포커스가 있든 똑같이 나가진다.
    addEventFilter(KeyEvent.KEY_PRESSED, this::handleExit);
    // Enter 는 입력칸에서만 받는다 — 딴 데서 친 Enter 까지 보내기로 먹으면 곤란해서.
    input.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
    // 끝에 \ 를 치면 Enter 없이 그 자리에서 말풍선을 끊어 올린다 — 마도카는 안 부른다 (send 주석).
    // 키 대신 글자 바뀜을 보는 건, 한글을 조합하다 \ 를 치면 조합이 먼저 들어간 뒤에 \ 가 붙어서다.
    // 맨 끝에 한 글자 붙었을 때만 — 가운데 끼워 친 \ 나 붙여넣은 글은 Enter 때 예전대로 나눈다.
    input.textProperty().addListener((obs, was, now) -> {
      if (editing == null && now.equals(was + "\\") && breaksNow(now))
        Platform.runLater(this::send);
    });
    // 창을 다시 부르거나 마도카를 눌러 들어오면 여기로 걸린다 (Ime.focus 가 포커스를 뗐다 붙인다).
    // 실패한 말을 다시 던지는 것도, 뜸해졌을 때 먼저 말을 거는 것도 이 신호 하나에서 갈린다.
    // 입력칸에 포커스가 있는 동안이 곧 이 화면을 보고 있는 동안이라, 나갈 때 읽은 때로 찍는다 —
    // 나가는 순간까지 눈앞에 있던 말은 다 읽은 셈이니까. 들어올 때 안 찍는 건, 들어오는 자리에서
    // 마도카가 먼저 말을 걸지 고를 때 "들어오기 전에 다 읽었었나" 를 봐야 해서다 (nudge).
    input.focusedProperty().addListener((obs, was, is) -> {
      if (is)
        onFocus();
      else if (leftScreen())
        markRead();
    });

    restore();
  }

  /** 지난 대화와 접어둔 기억을 읽어와 화면에 올린다 */
  private void restore() {
    lines.addAll(store.load());

    WorkingMemory.Snapshot saved = memory.load();
    memo = saved.memo;
    foldedUpTo = saved.foldedUpTo;
    ChatPrompt.Snapshot written = prompt.load();
    customName = written.name.strip();
    customPersona = written.persona;
    customNudge = written.nudge;
    customTodo = written.todo;
    customPraise = written.praise;
    customGreeting = written.greeting;

    Presence.Snapshot seen = presence.load();
    readAt = seen.readAt;
    focusAt = seen.focusAt;
    prevFocusAt = focusAt;
    talkedAt = seen.talkedAt;
    nudgedAt = seen.nudgedAt;
    todoNaggedOn = seen.todoNaggedOn == null ? "" : seen.todoNaggedOn;
    clearedAt = seen.clearedAt;
    silenceRolledTo = seen.silenceRolledTo;
    liking = affection.load();
    showLiking();
    showName();

    // 하던 얘기가 있으면 인사는 건너뛴다. 이어 말하는 자리에 "왔구나!" 가 끼면 처음 보는 사이 같아서.
    // 지워서 비었으면 인사도 안 한다 — 지운 뒤로는 입을 다문다 (silent).
    greeted = !lines.isEmpty() || clearedAt > 0;
    render();
  }

  /**
   * 마도카가 서 있는 오른쪽 세로줄을 통째로 비워둔다. 창 오른쪽 끝에서 그림 왼쪽 끝까지가
   * 얼마인지(그림 너비 + 그림이 오른쪽에서 띄워 선 거리)를 Main 이 재서 부른다.
   *
   * 입력칸만 비켜주면 오른쪽에 붙는 내 말풍선이 그림 위로 올라타서,
   * 화면 전체를 그만큼 왼쪽으로 물린다 — 머리말도 말풍선도 입력칸도 전부 그림 왼쪽에서 끝나게.
   * 거기에 MASCOT_GAP 만큼 더 띄워서 그림과 말풍선 사이에 여백을 남긴다.
   *
   * 비운 세로줄의 그림 위쪽에는 오른쪽 탭(ChatSide)이 선다. edge 는 세로줄 오른쪽 끝에서 창 끝까지,
   * below 는 창 아래에서 탭이 멈출 높이다. 그래서 탭 내용이 그림과 안 겹치고 그 위에서 잘린다.
   */
  public void reserveRight(double column, double edge, double below) {
    setPadding(new Insets(PADDING.getTop(), Math.max(0, column) + MASCOT_GAP,
        PADDING.getBottom(), PADDING.getLeft()));
    sideColumn = Math.max(0, column);
    sideEdge = edge;
    sideBelow = below;
    requestLayout();
  }

  /** 오른쪽 탭을 세운다. 전체화면에선 대화 칸이 가운데 서고 그림은 떠 있으니 대화 칸 바로 오른쪽에 */
  @Override
  protected void layoutChildren() {
    super.layoutChildren();
    double top = PADDING.getTop();
    if (sideColumn > 0)
      side.resizeRelocate(getWidth() - sideColumn, top, sideColumn - sideEdge, Math.max(0, getHeight() - top - sideBelow));
    else
      side.resizeRelocate(getWidth() + SIDE_GAP, top, SIDE_WIDTH, Math.max(0, getHeight() - top - PADDING.getBottom()));
  }

  /** 포커스를 줄 곳. 창을 다시 띄울 때 Main 이 여기로 돌려준다 */
  public TextField input() {
    return input;
  }

  /**
   * 위젯 창 포커스가 들어오고 나갈 때마다 Main 이 부른다 — 수다 화면에 안 들어와 있어도.
   *
   * 들어올 때는 두 가지를 한다. 먼저 "언제 들렀나" 를 한 칸 밀어둔다 — 방금 전 들른 때가
   * prevFocusAt 으로 내려가서, 마도카가 먼저 건 말의 시각을 그 뒤로 물릴 때 쓴다 (backdate).
   * 그다음 뜸해졌는지 본다. 기본 화면만 열었다 닫는 날에도 마도카가 먼저 말을 걸게 하려는 것이고,
   * 화면 밖에서 올라온 말이라 그대로 안 읽은 말이 되어 그림 옆에 배지가 뜬다 (addLine 참고).
   *
   * 수다 화면을 보고 있을 땐 입력칸이 포커스를 받으면서 onFocus 가 알아서 하니까 뜸한지는 안 본다.
   * 나갔다 FOCUS_BOUNCE_MS 안에 도로 들어온 건 창을 끌어오다 튄 것이라 들른 걸로 안 친다.
   */
  public void widgetFocused(boolean focused) {
    long now = System.currentTimeMillis();
    if (!focused) {
      blurAt = now;
      return;
    }
    if (blurAt > 0 && now - blurAt < FOCUS_BOUNCE_MS)
      return;

    prevFocusAt = focusAt;
    focusAt = now;
    savePresence();

    // 꺼져 있던 사이(켜져 있던 사이는 시계가 이미 굴렸다)를 지난 시간만큼 굴린다
    silenceCaughtUp = true;
    rollSilence();

    if (!reading())
      maybeNudge();
  }

  /** 지금 이 화면을 보고 있다고 찍어둔다. 여기까지 붙은 말은 다 읽은 것이 된다 */
  private void markRead() {
    readAt = System.currentTimeMillis();
    savePresence();
  }

  /**
   * 입력칸이 포커스를 잃은 게 정말로 이 화면을 떠난 건지. 창이 포커스를 잃었거나 화면에서 떨어져
   * 나갔으면 떠난 것이다. 창은 그대로인데 잃은 건 Ime.focus 가 포커스를 잠깐 뗐다 붙인 것이라,
   * 그걸 읽은 때로 찍으면 창을 다시 부를 때마다 "방금 다 읽음" 이 돼버린다.
   */
  private boolean leftScreen() {
    return getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isFocused();
  }

  /** 마지막 줄까지 다 읽었는지 — 수다 화면을 마지막으로 본 때가 마지막 줄보다 뒤인지 */
  private boolean readAll() {
    return lines.isEmpty() || readAt >= lines.get(lines.size() - 1).at;
  }

  /** 지운 대화들이 모인 곳. 오른쪽 탭의 지난 대화가 이걸 펼친다 */
  ChatArchive archive() {
    return archive;
  }

  /** 지금 대화 줄들 — 오른쪽 탭의 톡서랍이 읽는다 */
  List<ChatHistory.Line> lines() {
    return lines;
  }

  /** 프사가 새로 왔을 때 — 멤버 탭이 떠 있으면 다시 그린다 */
  public void refreshMembers() {
    side.refreshMembers();
  }

  /** 상대가 써둔 상태 메시지. 아직 없으면 빈 글 */
  String statusText() {
    return status.text();
  }

  /** 입력칸으로 포커스를 돌려준다 — 오른쪽 탭에서 띄운 창이 닫힐 때 */
  void focusInput() {
    Platform.runLater(() -> Ime.focus(input));
  }

  /** 지난 대화 창을 entry 가 펼쳐진 채로 띄운다 */
  void openArchive(List<ChatArchive.Entry> entries, ChatArchive.Entry entry) {
    ChatArchiveDialog.show((javafx.stage.Stage) getScene().getWindow(), entries, entry, name(), noteSingleClick(), likingEmojis(),
        reasonRevealed());
    focusInput();
  }

  /**
   * 상태 메시지를 새로 쓸 차례면 (ChatStatus.roll) 상대한테 한 줄 받아둔다. 수다 화면에 들어올 때 부른다.
   * 메모 접기처럼 화면에 표시 없이 조용히 하고, 실패하면 있던 글을 그대로 둔다.
   */
  private void maybeRewriteStatus() {
    long now = System.currentTimeMillis();
    if (writingStatus || !llm.isReady() || !status.roll(now))
      return;
    String persona = statusPersona();
    String ask = statusAsk();
    writingStatus = true;

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.chat(persona, List.of(new LlmClient.Message(true, ask)), LlmClient.Mode.FAST);
      }
    };
    task.setOnSucceeded(e -> {
      writingStatus = false;
      String written = statusLine(task.getValue());
      if (written.isBlank())
        return;
      status.write(written, System.currentTimeMillis());
      side.refreshMembers();
    });
    task.setOnFailed(e -> writingStatus = false);

    Thread worker = new Thread(task, "madoka-status");
    worker.setDaemon(true);
    worker.start();
  }

  /** 상태 메시지를 쓸 때 입히는 성격 — 말풍선 쪼개기 같은 말하는 방식(BasePrompt)은 필요 없어서 뺀다 */
  private String statusPersona() {
    String known = WorkingMemory.plain(memo, LocalDate.now());
    String persona = fill(prompt()).strip();
    return known.isBlank() ? persona : persona + "\n\n[기억해 둔 것]\n" + known;
  }

  /** 지금 상태 메시지와 최근 말풍선을 붙인 부탁 */
  private String statusAsk() {
    StringBuilder sb = new StringBuilder(STATUS_ASK);
    if (!status.text().isBlank())
      sb.append("\n지금 상태 메시지 (이것과는 다르게 써): ").append(status.text()).append('\n');
    List<String> recent = new ArrayList<>();
    for (int i = lines.size() - 1; i >= 0 && recent.size() < STATUS_RECENT; i--) {
      ChatHistory.Line line = lines.get(i);
      if (!line.hidden && !line.note)
        recent.add(0, (line.mine ? "상대" : name()) + ": " + line.text);
    }
    if (!recent.isEmpty())
      sb.append("\n[최근 대화]\n").append(String.join("\n", recent)).append('\n');
    return sb.toString();
  }

  /** 모델 답에서 첫 줄만, 감싼 따옴표를 벗겨 길이 제한 안으로 */
  static String statusLine(String raw) {
    if (raw == null)
      return "";
    for (String line : raw.strip().split("\n")) {
      String text = line.strip().replaceAll("^[\"'“”‘’「『]+|[\"'“”‘’」』]+$", "").strip();
      if (text.isEmpty() || text.startsWith("```"))
        continue;
      return text.length() <= STATUS_MAX ? text : text.substring(0, STATUS_MAX);
    }
    return "";
  }

  /** 마도카를 눌러서 이 화면으로 들어올 때 */
  public void enter() {
    // 여기까지 들어왔으면 밀려 있던 말은 다 눈앞에 있다 — 그림 옆 배지를 거둔다
    setUnread(0);
    // 들어올 때 오른쪽 탭은 멤버로
    side.reset();
    maybeRewriteStatus();
    ChatAvatar.refresh(profile, side::refreshMembers);
    if (!greeted) {
      greeted = true;
      addLine(fill(greeting()), false);
    }
    // 나가 있는 사이에 붙은 말은 화면 밖에서 그려졌다. 그때 내려둔 자리는 씬이 없어서
    // 안 먹혔을 수 있으니, 들어오는 김에 한 번 더 내려 마지막 말이 눈앞에 오게 한다.
    scrollToBottom();
    // 방금 화면에 붙은 입력칸이라 그냥 포커스를 주면 한글 조합이 안 보인다 (Ime 주석 참고)
    Platform.runLater(() -> Ime.focus(input));
  }

  /**
   * 마도카랑 나눈 얘기를 통째로 지운다. 기본 화면에 "채팅 삭제" 를 치면 Main 이 여기로 부른다.
   *
   * 화면에 그려둔 말풍선과 파일에 남은 대화(chat.json)를 비운다. 접어둔 기억(memo.json)은 남긴다 —
   * 오간 말 원문은 지워도, 마도카가 그동안 알아둔 것까지 잊으면 처음 보는 사이가 돼서.
   * foldedUpTo 도 그대로 둔다. 새로 붙는 줄은 다 그 뒤 시각이라 이어서 접으면 된다.
   *
   * 다만 지운 대화는 ChatArchive 에 한 파일로 따로 남긴다. 마도카한테 다시 들려주진 않고,
   * 오른쪽 탭의 지난 대화에서 고르면 뜨는 창(ChatArchiveDialog)에서 보기만 한다.
   *
   * 답을 기다리는 중에 지울 수도 있다 (답이 늦어서 Esc 로 나왔다가 지우는 경우). era 를 한 칸
   * 올려두면 뒤늦게 온 답도, 돌던 요약도 제 발로 물러난다 — era 주석 참고.
   *
   * 호감도 값도 그대로 둔다. 오르내린 기록만 이 대화에 묶여 있어서 같이 비운다 — 보관하면
   * 그 기록(과 지울 때 값)이 그 대화 옆에 같이 남고, 아니면 버린다.
   *
   * 보관할지는 부르는 쪽(Main)이 먼저 물어보고 keep 으로 넘긴다. false 면 보관 없이 그냥 버린다.
   */
  public void clearChat(boolean keep) {
    era++;

    if (keep)
      archive.save(lines, affection.snapshot(liking));
    else
      lines.forEach(line -> ChatImage.delete(line.image));
    lines.clear();
    store.save(lines);

    affection.clearHistory(liking);

    asking = false;
    folding = false;
    pending = false;
    queued = false;
    cancelReply();
    side.refresh();
    // 지워버린 말이 "안 읽은 말" 로 남아 배지가 안 꺼지는 일이 없게
    setUnread(0);
    // 지운 뒤로는 인사도 안 한다 — 원칙적으로 입을 다물고, 가끔만 먼저 건다 (silent)
    greeted = true;
    clearedAt = System.currentTimeMillis();
    silenceRolledTo = clearedAt;
    savePresence();
    render();

    // 파트너는 지우자마자 바로 먼저 건다
    if (proactive) {
      breakSilence();
      return;
    }
    // 딴 상대는 지운 바로 그때 한 번 굴린다. 지우자마자 툭 튀어나오면 기계 같아서 한 박자 쉬고 건다
    if (ThreadLocalRandom.current().nextDouble() < BREAK_SILENCE_AT_ONCE) {
      int startedIn = era;
      PauseTransition beat = new PauseTransition(Duration.seconds(2));
      beat.setOnFinished(e -> {
        if (startedIn == era)
          breakSilence();
      });
      beat.play();
    }
  }

  /** 대화를 지운 뒤 아직 한 마디도 안 오갔는지. 그동안 상대는 먼저 말을 안 건다 (breakSilence 만 빼고) */
  private boolean silent() {
    return clearedAt > 0 && lines.isEmpty();
  }

  /**
   * 지운 뒤 조용한 시간을 한 시간에 BREAK_SILENCE_PER_HOUR 씩 굴린다. 지난번에 굴린 뒤로 찬 시간만큼
   * 한꺼번에 굴리고 (n 시간이면 1 - 0.98^n), 한 시간에 못 미친 끝자락은 다음 번으로 넘긴다.
   * 켜져 있으면 silenceClock 이, 꺼져 있던 사이는 창이 포커스를 받을 때 widgetFocused 가 부른다.
   */
  private void rollSilence() {
    if (!silent())
      return;
    long hours = (System.currentTimeMillis() - silenceRolledTo) / HOUR_MS;
    if (hours <= 0)
      return;
    silenceRolledTo += hours * HOUR_MS;
    savePresence();

    double chance = 1 - Math.pow(1 - BREAK_SILENCE_PER_HOUR, hours);
    if (ThreadLocalRandom.current().nextDouble() < chance)
      breakSilence();
  }

  /**
   * 지운 뒤의 침묵을 깨고 먼저 한마디 — AFTER_CLEAR 중 아무거나. 모델은 안 부른다.
   * 한 줄이라도 붙으면 lines 가 차서 silent 가 풀리고, 그 뒤로는 평소처럼 오간다.
   */
  private void breakSilence() {
    if (asking || !silent())
      return;
    String pick = AFTER_CLEAR.get(ThreadLocalRandom.current().nextInt(AFTER_CLEAR.size()));
    asking = true;
    say(split(pick), 0, era, 0, false, System.currentTimeMillis());
  }

  private void savePresence() {
    presence.save(readAt, focusAt, talkedAt, nudgedAt, todoNaggedOn, clearedAt, silenceRolledTo);
  }

  /** 지금 호감도 */
  public int liking() {
    return liking;
  }

  /** 호감도를 0 으로 돌린다. 기본 화면에 "호감도 초기화" 를 치면 Main 이 부른다. 대화는 안 건드린다 */
  public void resetLiking() {
    liking = 0;
    affection.reset();
    showLiking();
  }

  /** 머리말에 호감도를 띄울지, 칸마다 어떤 이모지를 쓸지. 켤 때와 "설정" 을 저장할 때 Main 이 부른다 */
  public void setLikingDisplay(boolean show, List<String> emojis) {
    showLiking = show;
    likingEmojis = emojis;
    showLiking();
  }

  /** 비밀 쪽지를 쓸지, 한 번 눌러 열지. 켤 때와 "설정" 을 저장할 때 Main 이 부른다 */
  public void setNoteOptions(boolean use, boolean singleClick) {
    useNotes = use;
    noteSingleClick = singleClick;
    render();
  }

  /** 두 번째 말풍선부터 뜸을 들일지, 바이트 하나에 몇 ms 쉴지. 켤 때와 "설정" 을 저장할 때 Main 이 부른다 */
  public void setTypingDelay(boolean on, int msPerByte) {
    typingDelay = on;
    typingMsPerByte = Math.max(0, msPerByte);
  }

  /** 호감도 칸마다 쓸 이모지. 지난 대화 창도 같은 이모지로 그리게 Main 이 건네준다 */
  public List<String> likingEmojis() {
    return likingEmojis;
  }

  /** 쪽지를 한 번 눌러 여는지. 지난 대화 창도 같은 규칙으로 열게 Main 이 건네준다 */
  public boolean noteSingleClick() {
    return noteSingleClick;
  }

  /** 지금 이유를 볼 수 있는 호감도 기록 — 파트너면 다, 아니면 오늘 오르내린 건 다음 날 아침에 열린다 (Affection.revealed) */
  public Predicate<Affection.Change> reasonRevealed() {
    return affection.revealed(proactive);
  }

  /**
   * 호감도와 기억을 파일에서 다시 읽는다 — 파트너를 바꿔 저장하면 이 화면 밖에서 고쳐 쓰니까 (PartnerBond).
   * 기억을 안 다시 읽으면 다음에 접을 때 들고 있던 옛 메모로 덮어써서 새로 남긴 줄이 날아간다
   */
  public void reloadLiking() {
    memo = memory.load().memo;
    liking = affection.load();
    showLiking();
  }

  /** 머리말 호감도를 지금 값으로 고쳐 쓴다. 숨겨둔 거면 자리도 안 차지하게 뺀다 */
  private void showLiking() {
    // 이모지는 그림으로 바꾸지 않고 글자 그대로 — 머리말에선 흑백 윤곽으로 나오게
    likingLabel.setText(Affection.emoji(liking, likingEmojis) + " " + liking);
    likingLabel.setVisible(showLiking);
    likingLabel.setManaged(showLiking);
  }

  /** 지금 상대 */
  public ChatProfile profile() {
    return profile;
  }

  /** 지금 상대 이름 — 고쳐 둔 게 없으면 기본 이름 */
  public String name() {
    return customName.isBlank() ? profile.defaultName : customName;
  }

  /** 상대 이름을 바꾼다. 비우거나 기본 이름 그대로면 고친 게 없는 것으로 친다 */
  public void setName(String text) {
    String trimmed = text == null ? "" : text.strip();
    customName = trimmed.equals(profile.defaultName) ? "" : trimmed;
    savePrompt();
    showName();
  }

  /** 머리말 이름과 입력칸 안내를 지금 이름으로 */
  private void showName() {
    title.setText(name());
    input.setPromptText(placeholder());
  }

  private String placeholder() {
    return name() + PROMPT;
  }

  /** 글 속 {이름} 을 지금 상대 이름으로 */
  private String fill(String text) {
    return text.replace("{이름}", name());
  }

  /** 이 상대의 기본 성격문 ({이름} 은 그대로). 프롬프트 창의 "기본값" 이 이걸 채운다 */
  public String defaultPersona() {
    return profile.defaultPersona();
  }

  /** 지금 입혀둔 성격문 — 고쳐 쓴 게 없으면 기본값. "채팅 프롬프트" 창이 이걸 채워서 연다 */
  public String prompt() {
    return customPersona.isBlank() ? defaultPersona() : customPersona;
  }

  /**
   * 성격문을 갈아끼운다. 비우거나 기본값과 똑같이 두면 고친 게 없는 것으로 친다 —
   * 나중에 코드에서 기본 성격문을 다듬었을 때 그대로 따라가게.
   *
   * 다음 답변부터 바로 먹는다. 대화나 접어둔 기억은 안 건드린다.
   */
  public void setPrompt(String text) {
    customPersona = customized(text, defaultPersona());
    savePrompt();
  }

  /** 말하는 방식 — 상대와 상관없이 하나. 프롬프트 창의 숨은 "기본 프롬프트" 가 이걸 고친다 */
  public String basePrompt() {
    return base.text();
  }

  public void setBasePrompt(String text) {
    base.set(text);
  }

  private void savePrompt() {
    ChatPrompt.Snapshot snapshot = new ChatPrompt.Snapshot();
    snapshot.name = customName;
    snapshot.persona = customPersona;
    snapshot.nudge = customNudge;
    snapshot.todo = customTodo;
    snapshot.praise = customPraise;
    snapshot.greeting = customGreeting;
    prompt.save(snapshot);
  }

  /** 파트너인지 정한다 — 아니면 먼저 말을 걸지도, 할 일에 반응하지도 않는다 (proactive) */
  public void setProactive(boolean proactive) {
    this.proactive = proactive;
  }

  /**
   * 머리말 오른쪽에 상대 고르기 목록을 띄운다. 파트너(main)는 이름 옆에 ♥ 를 단다. 고르면 onPick 이 불리고,
   * 그 뒤는 Main 이 이 화면을 고른 상대 것으로 갈아끼운다. onPick 이 null 이면 목록을 숨긴다.
   */
  public void setPartnerPicker(ChatProfile main, Consumer<ChatProfile> onPick) {
    boolean show = onPick != null;
    partnerPicker.setVisible(show);
    partnerPicker.setManaged(show);
    partnerPicker.setOnAction(null);
    if (!show)
      return;
    // 이름은 상대마다 고쳐 둔 것일 수 있어서 각자 프롬프트 파일에서 읽는다
    partnerPicker.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
      @Override
      protected void updateItem(ChatProfile item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null
            : (item == profile ? name() : item.displayName()) + (item == main ? " ♥" : ""));
      }
    });
    partnerPicker.setButtonCell(partnerPicker.getCellFactory().call(null));
    partnerPicker.getItems().setAll(ChatProfile.values());
    partnerPicker.getSelectionModel().select(profile);
    partnerPicker.setOnAction(e -> {
      ChatProfile picked = partnerPicker.getValue();
      if (picked != null && picked != profile)
        onPick.accept(picked);
    });
  }

  /**
   * 딴 상대로 갈아끼워질 때 Main 이 부른다. 기다리던 답 · 말풍선은 버리고 (era),
   * 배지와 알림은 이제 새 화면 몫이라 안 건드린다.
   */
  public void retire() {
    // 보던 중에 딴 상대로 갈아타면 여기까지는 읽은 것 — 상대 목록 (PartnerFloat) 에 안 읽은 말로 남지 않게
    if (reading())
      markRead();
    retired = true;
    era++;
    silenceClock.stop();
    asking = false;
    folding = false;
    queued = false;
  }

  /** 오늘 할 일을 꺼내 줄 곳을 단다. 대화에 할 일 얘기가 나오거나 할 일 하나가 집히면 그때 읽는다 (withTodos) */
  public void setTodoSource(Supplier<List<TodoLine>> source) {
    this.todoItems = source == null ? List::of : source;
  }

  /** 지금 쓰는 안내문 — 고쳐 쓴 게 없으면 기본값. "채팅 프롬프트" 창이 이걸 채워서 연다 */
  /** 마도카 말이 붙을 때마다 불린다. 띄울지 말지(창을 보고 있는지)는 받는 쪽이 고른다 */
  public void setOnMadoka(Consumer<String> listener) {
    onMadoka = listener;
  }

  /** 지금 쓰는 시작 멘트 ({이름} 은 그대로) — 고쳐 쓴 게 없으면 이 상대의 기본 멘트 */
  public String greeting() {
    return customGreeting.isBlank() ? defaultGreeting() : customGreeting;
  }

  public String defaultGreeting() {
    return profile.defaultGreeting();
  }

  /** 시작 멘트를 갈아끼운다. 대화가 빈 상대를 처음 열 때부터 먹는다 */
  public void setGreeting(String text) {
    customGreeting = customized(text, defaultGreeting());
    savePrompt();
  }

  public String nudgeGuide() {
    return customNudge.isBlank() ? NUDGE : customNudge;
  }

  /** 안내문을 갈아끼운다. 다음에 먼저 말을 걸 때부터 먹는다 (setPrompt 와 같은 규칙) */
  public void setNudgeGuide(String text) {
    customNudge = customized(text, NUDGE);
    savePrompt();
  }

  /** 지금 쓰는 할 일 재촉 안내문 — 고쳐 쓴 게 없으면 기본값. "채팅 프롬프트" 창이 이걸 채워서 연다 */
  public String todoGuide() {
    return customTodo.isBlank() ? TODO_NAG : customTodo;
  }

  /** 할 일 재촉 안내문을 갈아끼운다. 다음에 재촉할 때부터 먹는다 (setPrompt 와 같은 규칙) */
  public void setTodoGuide(String text) {
    customTodo = customized(text, TODO_NAG);
    savePrompt();
  }

  /** 지금 쓰는 할 일 칭찬 안내문 — 고쳐 쓴 게 없으면 기본값. "채팅 프롬프트" 창이 이걸 채워서 연다 */
  public String praiseGuide() {
    return customPraise.isBlank() ? TODO_PRAISE : customPraise;
  }

  /** 할 일 칭찬 안내문을 갈아끼운다. 다음에 칭찬할 때부터 먹는다 (setPrompt 와 같은 규칙) */
  public void setPraiseGuide(String text) {
    customPraise = customized(text, TODO_PRAISE);
    savePrompt();
  }

  /** 고쳐 쓴 글에서 남겨둘 몫. 비었거나 기본값과 같으면 "" — 고친 게 없는 것으로 친다 */
  private static String customized(String text, String fallback) {
    String trimmed = text == null ? "" : text.strip();
    return trimmed.equals(fallback.strip()) ? "" : trimmed;
  }

  /**
   * 수다 화면을 접고 기본 화면으로. 기본 화면이 포커스를 먼저 가져가면 창은 그대로라
   * leftScreen 에 안 걸린다 — 나가는 자리에서 직접 읽은 때로 찍는다.
   * 입력칸 밖에서 ← 를 눌러 나갈 때 Main 도 여기로 나간다.
   */
  public void exit() {
    markRead();
    onExit.run();
  }

  /** Esc: 수다 화면을 접고 기본 화면으로. 포커스가 이 화면 안 어디에 있든 같다 */
  private void handleExit(KeyEvent e) {
    if (e.getCode() == KeyCode.ESCAPE) {
      e.consume();
      // 답장을 걸어둔 채거나 고치는 중이면 Esc 는 그것부터 취소
      if (replyTo != null || editing != null) {
        cancelReply();
        return;
      }
      if (attached != null) {
        dropAttached();
        return;
      }
      if (!onEscFull.getAsBoolean())
        exit();
    }
  }

  private void handleKey(KeyEvent e) {
    if (e.getCode() == KeyCode.ENTER) {
      e.consume();
      send();
      return;
    }
    // 복사해둔 게 그림이면 입력칸에 글로 넣는 대신 사진으로 올린다
    if (e.isShortcutDown() && e.getCode() == KeyCode.V && editing == null) {
      Supplier<Image> copied = imageIn(Clipboard.getSystemClipboard());
      if (copied != null) {
        e.consume();
        attach(copied);
      }
      return;
    }
    // 빈 입력칸에서 맨손 ← : 기본 화면으로 나간다. 수다 화면은 기본 화면 오른쪽에 있어서
    // 들어올 때 누른 → 를 거꾸로 누르면 그대로 되돌아 나가는 셈이다.
    // 친 게 있을 땐 ← 가 평소대로 캐럿 옮기기 — 그땐 Alt 를 같이 눌러야 나간다 (Main 과 같은 규칙).
    if (e.getCode() == KeyCode.LEFT && (e.isAltDown() || input.getText().isEmpty())) {
      e.consume();
      exit();
    }
  }

  /**
   * 친 걸 말풍선으로 올리고 마도카한테 넘긴다.
   * \ 는 말풍선 끊기 — "응\그래" 는 "응", "그래" 두 개로 올라간다. 빈 조각은 버린다.
   * \ 글자 자체는 \\ 로 친다.
   * 맨 끝이 \ 로 끝나면 말풍선만 올리고 마도카는 안 부른다 — 이어서 더 칠 게 있을 때.
   * 끝에 \ 를 치는 순간 Enter 없이 여기로 온다 (breaksNow).
   * 다음에 \ 없이 보내면 그때 쌓인 말까지 한꺼번에 넘어간다.
   * 모델한테 갈 때는 연달아 한 말이 한 덩어리로 합쳐지니 (GeminiClient.merge) 대화는 그대로다.
   * \ 하나만 보내면 말풍선은 안 올리고 마도카가 먼저 말을 건다 (callOut).
   * \\ 만 보내면 마도카가 나한테 품은 마음을 비밀 쪽지로 보낸다 (confess).
   * 맨 앞이 \ 로 시작하면 ("\ㅗ ^^") 내 말이 비밀 쪽지 한 장으로 접혀 올라간다 (쪽지를 켠 설정일 때).
   * 마도카가 말하는 중에도 보낼 수 있다 — 말풍선은 바로 올라가고, 답은 마도카가 다 말한 뒤에 한다 (queued).
   * 먼저 말 걸기·고백은 그 사이엔 안 받는다.
   * 사진을 올려뒀으면 사진 말풍선이 먼저 올라가고 친 글이 뒤따른다. 글 없이 사진만 보내도 된다 —
   * 이때 \ 는 끊기로만 읽는다 (사진만 올리고 마도카는 안 부르기).
   */
  private void send() {
    if (editing != null) {
      finishEdit();
      return;
    }
    String raw = input.getText().stripTrailing();
    boolean picture = attached != null;
    if (!picture && asking && (raw.strip().equals("\\") || raw.strip().equals("\\\\")))
      return;
    if (!picture && raw.strip().equals("\\")) {
      input.clear();
      callOut();
      return;
    }
    if (!picture && raw.strip().equals("\\\\")) {
      input.clear();
      confess();
      return;
    }
    boolean note = useNotes && startsWithNote(raw);
    List<String> pieces = splitMine(raw);
    if (pieces.isEmpty() && !picture)
      return;

    input.clear();
    if (picture)
      addImageLine();
    if (pieces.isEmpty()) {
      // 사진뿐이라 올릴 글이 없다
    } else if (note) {
      // 쪽지는 \ 로 끊어 쳤어도 한 장으로 접는다
      addLine(String.join("\n", pieces), true, System.currentTimeMillis(), true);
    } else {
      for (String piece : pieces)
        addLine(piece, true);
    }
    if (endsWithBreak(raw))
      return;
    if (asking) {
      queued = true;
      return;
    }
    pending = true;
    ask();
  }

  /** 올려둔 사진을 말풍선으로 올린다 */
  private void addImageLine() {
    String path = attached;
    showAttached(null);
    addLine(ChatImage.TEXT, true, System.currentTimeMillis(), false, path, null);
  }

  /**
   * 건네받은 것(클립보드 · 끌어온 것)에 그림이 들었는지. 끄는 동안 계속 불리니 내용은 안 꺼내 본다.
   * 글이 같이 든 건 안 집는다 — 엑셀 칸이나 워드 글을 복사하면 글과 함께 그림도 들어오는데,
   * 그때 원하는 건 글이라서 (Capture.clipboardImage 와 같은 결).
   */
  private static boolean holdsImage(Clipboard board) {
    if (board.hasFiles() && ChatImage.firstImage(board.getFiles()) != null)
      return true;
    return !board.hasString() && board.hasImage();
  }

  /** 건네받은 것에 든 그림. 없으면 null. 건네받은 건 이 자리를 벗어나면 비니 내용을 지금 읽어 들고 간다 */
  private static Supplier<Image> imageIn(Clipboard board) {
    if (!holdsImage(board))
      return null;
    File file = board.hasFiles() ? ChatImage.firstImage(board.getFiles()) : null;
    if (file != null)
      return () -> ChatImage.load(file);
    Image image = board.getImage();
    return image == null ? null : () -> image;
  }

  /** 📎 — 파일에서 사진을 고른다 */
  private void chooseImage() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("보낼 사진 고르기");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("그림", ChatImage.FILTER));
    File file = chooser.showOpenDialog(getScene().getWindow());
    if (file != null)
      attach(() -> ChatImage.load(file));
    Platform.runLater(() -> Ime.focus(input));
  }

  /** 사진을 줄여 저장하고 입력칸 위에 올려둔다. 이미 올려둔 게 있으면 새 사진으로 갈아끼운다 */
  private void attach(Supplier<Image> source) {
    String dir = profile.path(ChatImage.DIR);
    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return ChatImage.save(source.get(), dir);
      }
    };
    task.setOnSucceeded(e -> {
      ChatImage.delete(attached);
      showAttached(task.getValue());
    });
    task.setOnFailed(e -> Toast.show("이 그림은 못 읽겠어"));
    Thread worker = new Thread(task, "chat-image");
    worker.setDaemon(true);
    worker.start();
  }

  /** 올려둔 사진을 내린다 */
  private void dropAttached() {
    ChatImage.delete(attached);
    showAttached(null);
  }

  /** 입력칸 위 사진 줄을 path 로 맞춘다. null 이면 숨긴다 */
  private void showAttached(String path) {
    attached = path;
    attachedView.setImage(path == null ? null : new Image(new File(path).toURI().toString(), 0, ATTACH_THUMB * 2, true, true));
    attachBar.setVisible(path != null);
    attachBar.setManaged(path != null);
  }

  /**
   * 방금 끝에 친 \ 로 바로 끊어 올릴지. 짝 없는 \ 로 끝나야 하고, \ 하나·\\ 하나뿐이면 안 끊는다 —
   * 빈 칸에서 친 \ 는 먼저 말 걸기 (callOut)·비밀 쪽지 (맨 앞 \)·고백 (\\) 의 시작이라 Enter 를 기다린다.
   */
  private static boolean breaksNow(String text) {
    String s = text.strip();
    return endsWithBreak(text) && !s.equals("\\") && !s.equals("\\\\");
  }

  /** 맨 앞이 짝 없는 \ 인지 — 내 말을 비밀 쪽지로 보내는 표시다. \\ 로 시작하면 \ 글자일 뿐이다 */
  private static boolean startsWithNote(String raw) {
    String s = raw.stripLeading();
    return s.startsWith("\\") && !s.startsWith("\\\\");
  }

  /** 끝에 붙은 \ 가 홀수 개면 마지막 하나는 \\ 짝이 없는 끊기다 */
  private static boolean endsWithBreak(String raw) {
    int count = 0;
    for (int i = raw.length() - 1; i >= 0 && raw.charAt(i) == '\\'; i--)
      count++;
    return count % 2 == 1;
  }

  /** 내가 친 걸 말풍선 단위로 쪼갠다. \ 하나는 끊기, \\ 는 \ 글자 하나 */
  private static List<String> splitMine(String raw) {
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c != '\\') {
        current.append(c);
      } else if (i + 1 < raw.length() && raw.charAt(i + 1) == '\\') {
        current.append('\\');
        i++;
      } else {
        addPiece(pieces, current);
      }
    }
    addPiece(pieces, current);
    return pieces;
  }

  private static void addPiece(List<String> pieces, StringBuilder current) {
    String trimmed = current.toString().strip();
    if (!trimmed.isEmpty())
      pieces.add(trimmed);
    current.setLength(0);
  }

  /**
   * 이 입력칸이 포커스를 받을 때마다 — 창을 다시 부르거나, 마도카를 눌러 들어오거나,
   * 딴 데 갔다 돌아왔을 때. 할 일이 없으면 아무 일도 안 일어난다.
   *
   * 못 보낸 내 말이 먼저다. 그걸 놔두고 마도카가 딴 말을 먼저 꺼내면 내가 친 말은 영영
   * 답 없이 묻히고, 답이 오면 그 줄에 시각이 새로 찍혀서 뜸해진 것도 어차피 풀린다.
   */
  private void onFocus() {
    if (pending) {
      retryPending();
      return;
    }
    // 창이 포커스를 받는 자리에선 입력칸이 창보다 먼저 불릴 때가 있다. 한 박자 미뤄서
    // widgetFocused 가 "지난번에 들른 때" 를 먼저 밀어두게 한다 (backdate 가 그걸 본다).
    Platform.runLater(this::maybeNudge);
  }

  /** 답을 못 받고 남은 말이 있으면 다시 던진다. 없으면 아무 일도 안 일어난다 */
  private void retryPending() {
    if (pending && !asking)
      ask();
  }

  /**
   * 최근 몇 줄과 접어둔 기억을 넘기고 답을 기다린다.
   * 실패는 화면에 안 남긴다 — pending 을 켜둔 채 물러나면 다음 포커스 때 여기로 다시 온다.
   *
   * 내 마지막 말 뒤에 마도카 말풍선이 붙어 있으면 (말하는 중에 끼어든 말) 답이 무슨 말에 대한 건지
   * 헷갈리니, 첫 말풍선을 그 말에 대한 답장으로 올린다.
   */
  private void ask() {
    if (lines.isEmpty() || describeFirst())
      return;
    ChatHistory.Line target = null;
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).mine) {
        if (i < lines.size() - 1)
          target = lines.get(i);
        break;
      }
    }
    request(window(), 0, false, null, target, WebPicture.asked(unanswered(), recentTalk()), false);
  }

  /** 사진 얘기 중인지 볼 줄 수. 조르는 동안 말이 몇 번 오가도 끊기지 않을 만큼 */
  private static final int PICTURE_TALK_LINES = 16;

  /** 최근 말들. 사진 줄은 글이 ChatImage.TEXT 라서 그대로 사진 얘기로 잡힌다 */
  private String recentTalk() {
    StringBuilder out = new StringBuilder();
    for (ChatHistory.Line line : lines.subList(Math.max(0, lines.size() - PICTURE_TALK_LINES), lines.size()))
      out.append(line.text).append('\n');
    return out.toString();
  }

  /** 아직 답을 못 받은 내 말을 한 줄로 잇는다. 말풍선을 끊어 친 말도 이어서 봐야 사진 달라는 말이 보인다 */
  private String unanswered() {
    StringBuilder out = new StringBuilder();
    for (int i = lines.size() - 1; i >= 0 && lines.get(i).mine; i--) {
      if (lines.get(i).image == null)
        out.insert(0, lines.get(i).text + " ");
    }
    return out.toString();
  }

  /**
   * 아직 안 읽은 사진이 있으면 그것부터 읽히고 true 를 돌려준다. 다 읽으면 ask 로 돌아와 답을 받는다.
   *
   * 답을 기다리는 것과 똑같이 친다 — asking 을 켜둬서 그 사이 보낸 말은 queued 로 미뤄지고,
   * 실패하면 pending 을 켜둔 채 물러나 다음 포커스 때 여기서부터 다시 한다. 읽은 사진은 파일에
   * 남으니 다시 해도 또 읽히지 않는다. 파일이 없어진 사진은 빈 설명으로 넘어간다.
   */
  private boolean describeFirst() {
    ChatHistory.Line target = null;
    for (ChatHistory.Line line : lines) {
      if (line.image != null && line.caption == null) {
        target = line;
        break;
      }
    }
    if (target == null || asking || !llm.isReady())
      return false;

    ChatHistory.Line line = target;
    int startedIn = era;
    asking = true;
    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        try {
          return llm.describeImage(Files.readAllBytes(Path.of(line.image)));
        } catch (NoSuchFileException gone) {
          return "";
        }
      }
    };
    task.setOnSucceeded(e -> {
      if (startedIn != era)
        return;
      line.caption = task.getValue().strip();
      store.save(lines);
      asking = false;
      queued = false;
      ask();
    });
    task.setOnFailed(e -> {
      System.err.println("[chat] 사진 읽기 실패: " + task.getException());
      if (startedIn == era)
        settleQueued();
    });
    Thread worker = new Thread(task, "madoka-image");
    worker.setDaemon(true);
    worker.start();
    return true;
  }

  /**
   * 마도카한테 넘기고 답을 말풍선으로 올리는 자리. 내 말에 답하는 것(ask)도, 뜸해져서
   * 먼저 말을 거는 것(nudge)도 결국 여기로 모인다 — 건네는 목록만 다르고 나머지는 같다.
   *
   * earlierMs 만큼 말풍선에 찍히는 시각을 앞당긴다. 먼저 건 말을 내가 없던 사이에 보낸
   * 것처럼 보이게 할 때만 쓰고 (backdate), 보통 답은 0 이다.
   *
   * onAnswered 는 답이 실제로 왔을 때만 부른다 — 먼저 건 말이면 그 시각을 nudgedAt 으로,
   * 할 일 재촉이면 그날을 todoNaggedOn 으로 남긴다. 실패하거나 빈 답이면 안 건 셈이라
   * 안 부르고, 다음 포커스 때 다시 걸 수 있다. 내 말에 답하는 건 null.
   *
   * asNote 면 답 전체를 비밀 쪽지 한 장으로 접어 올린다 — 마도카가 먼저 말을 거는 자리 (뜸할 때,
   * 할 일 재촉·칭찬). 아니어도 마도카가 [[쪽지:...]] 로 접어 보낸 게 있으면 그 쪽지 하나만 올린다
   * (깜짝 긁기, withNotes). 쪽지를 끈 설정이면 둘 다 보통 말풍선으로 풀어 올린다.
   */
  private void request(List<LlmClient.Message> snapshot, long earlierMs, boolean asNote, Runnable onAnswered) {
    request(snapshot, earlierMs, asNote, onAnswered, null, false, true);
  }

  /**
   * quote 가 있으면 첫 말풍선을 그 말에 대한 답장으로 올린다 (ask).
   * picture 면 이번 턴에만 사진 보내는 법을 일러주고, 답에 표시가 오면 찾아서 말풍선 뒤에 붙인다 (WebPicture)
   * cued 면 끝에 붙은 건 내가 친 말이 아니라 위젯이 끼워 넣은 안내다. 안내 글자를 내 말로 잘못 읽으니
   * 할 일 목록을 찾지 않는다 (withTodos). 할 일 얘기를 시키는 안내엔 그 할 일이 이미 적혀 있다.
   */
  private void request(List<LlmClient.Message> snapshot, long earlierMs, boolean asNote, Runnable onAnswered,
      ChatHistory.Line quote, boolean picture, boolean cued) {
    if (asking || snapshot.isEmpty())
      return;

    if (!llm.isReady()) {
      // 키가 없는 것도 실패로 친다. 여기서 안내를 띄우면 대화창이 설명서가 돼버려서,
      // 길게 물어보는 LLM 모드(←)에 맡기고 여기선 조용히 접는다.
      return;
    }

    // 시각을 물려 찍는 말이면 [지금 시각] 도 그 시각으로 — 마도카는 그때 보낸 말을 쓰는 거라서
    long saidAt = System.currentTimeMillis() - earlierMs;
    String known = cued ? persona() : withStatus(withTodos(persona(), snapshot), snapshot);
    String persona = WebPicture.withGuide(
        withNotes(withNow(withAffection(known, snapshot), saidAt), asNote), picture, profile);
    String pictureDir = profile.path(ChatImage.DIR);
    int startedIn = era;
    asking = true;

    Task<WebPicture.Reply> task = new Task<>() {
      @Override
      protected WebPicture.Reply call() throws Exception {
        String raw = llm.chat(persona, snapshot, LlmClient.Mode.FAST, true);
        boolean wanted = picture || WebPicture.faked(raw);
        return WebPicture.resolve(raw, wanted && !affectionDrops(raw), pictureDir, sentPictures, profile);
      }
    };

    task.setOnSucceeded(e -> {
      WebPicture.Reply answer = task.getValue();
      // 기다리는 사이에 대화를 지웠으면 이 답은 갈 데가 없다 (정리는 clearChat 이 이미 해뒀다)
      if (startedIn != era) {
        if (answer.picture() != null)
          ChatImage.delete(answer.picture().path());
        return;
      }
      // 첫 말풍선이 찍힐 시각 — 호감도 기록도 같은 시각으로 남겨서, 기록을 두 번 누르면 이 말풍선으로 간다
      long at = System.currentTimeMillis() - earlierMs;
      String reply = takeAffection(answer.text(), at);
      // 쪽지를 접어 보냈으면 쪽지 하나만 — 같이 붙여 보낸 말은 버린다
      String marked = useNotes ? noteIn(reply) : null;
      List<String> bubbles = split(marked != null ? marked : unwrapNotes(reply));
      // 사진 표시만 달고 말이 없었어도 사진은 보낸다 — 찾으면서 적은 설명을 말 대신 올린다
      if (bubbles.isEmpty() && answer.picture() != null)
        bubbles.add(answer.picture().caption());
      if (answer.missed())
        bubbles.add(WebPicture.MISSED);
      if (bubbles.isEmpty()) {
        // 내용 없이 빈 답이 온 경우. 빈 말풍선을 올려봐야 대화만 끊겨 보이니
        // 실패했을 때와 똑같이 pending 을 켜둔 채 물러난다 (다음 포커스 때 다시 던진다).
        settleQueued();
        return;
      }
      pending = false;
      if (onAnswered != null)
        onAnswered.run();
      // 답이 왔으면 그 앞에 내가 보낸 쪽지는 마도카가 읽은 셈이다 (저장·다시 그리기는 곧 붙을 addLine 이 한다)
      for (ChatHistory.Line line : lines)
        if (line.mine && line.note)
          line.opened = true;
      // 쪽지는 여러 줄이어도 한 장으로 접는다
      boolean note = useNotes && (asNote || marked != null);
      if (note)
        bubbles = List.of(String.join("\n", bubbles));
      // asking 은 마지막 말풍선이 올라갈 때까지 켜둔다 — 마도카가 아직 말하는 중에
      // 내 말이 끼어들면 주고받은 순서가 엉켜서. 그 사이 보낸 말은 queued 로 미뤄둔다.
      if (quote != null && lines.contains(quote))
        quoteNext = quote;
      pictureNext = answer.picture();
      say(bubbles, 0, startedIn, earlierMs, note, at);
    });

    task.setOnFailed(e -> {
      // 말없이 물러난다. pending 은 켜둔 채라 다음 포커스 때 또 던진다.
      if (startedIn != era)
        return;
      settleQueued();
    });

    Thread worker = new Thread(task, "madoka-chat");
    worker.setDaemon(true); // 답 기다리는 중에 위젯 꺼도 프로세스가 안 남게
    worker.start();
  }

  /**
   * 답 없이 물러날 때 (실패·빈 답). 그 사이 끼어든 말은 답 못 받은 말로 남긴다 —
   * 지금 바로 다시 던지면 같은 실패를 되풀이할 테니, 다음 포커스 때 pending 으로 다시 온다.
   */
  private void settleQueued() {
    asking = false;
    if (queued)
      pending = true;
    queued = false;
  }

  /**
   * 마지막으로 오간 말이 NUDGE_AFTER_MS 보다 오래됐으면, 내가 아무것도 안 쳤는데
   * 마도카가 먼저 한마디 건네게 한다.
   *
   * 뜸해진 길이는 화면에 그려져 있는 마지막 줄에 붙은 시각으로 잰다. 그 시각은 chat.json 에
   * 같이 남으니 껐다 켠 뒤에도 그대로고, 그래서 "위젯을 얼마나 안 켰나" 가 아니라 정말로
   * "말이 얼마나 안 오갔나" 로 걸린다.
   *
   * 한 번 걸리고 나면 방금 건 말이 맨 아래 줄이 되면서 뜸한 시간이 0 으로 돌아간다 —
   * 포커스를 몇 번 더 받아도 다시 그만큼 조용해지기 전엔 또 말을 걸지 않는다.
   * 답을 못 받고 물러난 경우(실패·빈 답)에는 줄이 안 늘어서 다음 포커스 때 다시 걸린다.
   *
   * 먼저 건 말은 시각을 물려 찍으니 (backdate) 줄 시각만 보면 물린 만큼 일찍 다섯 시간이
   * 찬다 — 그래서 실제로 붙은 때(talkedAt)와 둘 중 늦은 쪽부터 잰다.
   *
   * 아직 한 줄도 없으면 여기 올 일이 없다 — 그건 처음 열었을 때의 인사(GREETING) 몫이다.
   *
   * 먼저 거는 건 하루(달력 날짜로) 한 번뿐이다. 오늘 이미 걸었으면 아무리 조용해도 내일까지 참는다.
   */
  private void maybeNudge() {
    if (!proactive || asking || lines.isEmpty() || nudgedToday())
      return;

    ChatHistory.Line last = lines.get(lines.size() - 1);
    long idle = System.currentTimeMillis() - Math.max(last.at, talkedAt);
    if (idle < NUDGE_AFTER_MS)
      return;

    nudge(last);
  }

  /**
   * 먼저 말을 걸어달라고 한 번 부른다.
   *
   * 평소 답변과 다른 건 맨 뒤에 붙는 안내 한 덩어리뿐이다. 앞에 오는 건 늘 쓰던 그 창
   * (window) 과 접어둔 기억(persona) 이라, 하던 얘기를 하나도 안 지운 채로 그 위에서
   * 말이 이어진다 — 오래 조용했다고 대화를 처음부터 다시 시작하지는 않는다.
   *
   * 안내는 chat.json 에 안 남긴다. 화면에 안 보이는 말이 기록에 끼면 다음번 window() 에
   * 딸려 들어가서, 조용하지도 않은데 "오랜만이야" 를 또 하게 된다.
   *
   * 여기서 내가 마지막 줄까지 읽었는지 본다. 읽었으면 걸 말의 시각을 내가 없던 사이로
   * 물리고 (backdate), 안내에도 읽었는지·언제 읽었는지를 적어 넘긴다 (nudgePrompt).
   */
  private void nudge(ChatHistory.Line last) {
    long now = System.currentTimeMillis();
    boolean read = readAll();
    long sentAt = read ? backdate(last, now) : now;

    // 내 말로 끊긴 자리는 하던 얘기를 안 들려준다. 창을 같이 보내면 아무리 일러도 내 마지막 말에
    // 답부터 하게 돼서, 맥락 없이 제 얘기를 꺼내게 하려면 안내 한 덩어리만 보내는 수밖에 없다.
    // 오래 알아둘 것(memo)은 성격문에 그대로 붙어 가니 상대가 누군지는 안다.
    List<LlmClient.Message> snapshot = last.mine ? new ArrayList<>() : window();
    snapshot.add(new LlmClient.Message(true,
        nudgePrompt(sentAt - last.at, last.mine, read ? sentAt - readAt : -1)));
    request(snapshot, now - sentAt, useNotes, () -> {
      nudgedAt = System.currentTimeMillis();
      savePresence();
    });
  }

  /**
   * \ 하나만 보냈을 때 — 뜸했는지, 오늘 이미 걸었는지 안 보고 마도카가 바로 먼저 말을 건다.
   * 하던 얘기(window)는 안 들려주고 안내 한 덩어리만 보낸다. 창을 같이 보내면 내 마지막 말에
   * 답부터 하게 돼서다 (nudge 의 내 말로 끊긴 자리와 같은 까닭). 오래 알아둘 것은 성격문에 붙어 간다.
   * 내가 부른 거라 하루 한 번 세는 nudgedAt 은 안 건드린다.
   */
  private void callOut() {
    List<LlmClient.Message> snapshot = new ArrayList<>();
    snapshot.add(new LlmClient.Message(true, fill(CALL_OUT.strip())));
    request(snapshot, 0, false, null);
  }

  /**
   * \\ 만 보냈을 때 — 마도카가 지금 나한테 품은 마음을 비밀 쪽지 한 장에 적어 보낸다.
   * callOut 과 달리 하던 얘기(window)를 같이 들려준다 — 방금까지의 맥락 위에서 적는 쪽지라서.
   * 쪽지를 끈 설정이면 보통 말풍선으로 온다. 내가 부른 거라 nudgedAt 은 안 건드린다.
   */
  private void confess() {
    List<LlmClient.Message> snapshot = window();
    snapshot.add(new LlmClient.Message(true, FEELING.strip().replace("{호감도}", String.valueOf(liking))));
    request(snapshot, 0, useNotes, null);
  }

  /**
   * 전날 못 끝내서 오늘로 넘어온 할 일이 있으면 마도카가 재촉한다. 위젯 창이 포커스를 받을 때
   * Main 이 할 일을 새로 읽고 부른다 — 아침 여섯 시가 지나 넘어온 게 생기면 그날 처음 들를 때 걸린다.
   *
   * 하루(할 일의 하루, day) 한 번뿐이다. 답을 못 받았으면 안 한 셈이라 다음 포커스 때 다시 건다.
   * 다른 답을 기다리는 중이어도 다음으로 미룬다.
   *
   * 뜸할 때 거는 말(nudge)과 같은 틀이다 — 하던 얘기 위에 안내 한 덩어리만 얹고,
   * 안내는 화면에도 기록에도 안 남는다. 화면 밖에서 붙은 말이면 배지로 뜬다.
   */
  /**
   * 할 일을 끝내 칭찬할 때가 되면 마도카가 칭찬한다. 체크칸을 누르는 자리에서 Main 을 거쳐 불린다 —
   * 언제 칭찬할지 (절반 · 다 끝냄 · 그 뒤 더 적은 것) 는 할 일 쪽이 정하고 기억한다 (TodoPane.milestone).
   *
   * 재촉(remindTodos)과 같은 틀이다 — 하던 얘기 위에 안내 한 덩어리만 얹고, 안내는 안 남는다.
   * 수다 화면 밖에서 붙은 말이라 배지로 뜬다. 다른 답을 기다리는 중이면 그 칭찬은 건너뛴다 —
   * 여러 개를 연달아 체크할 때 말풍선이 줄줄이 쏟아지지 않게.
   */
  public void praiseTodo(String todo) {
    if (!proactive || asking || silent())
      return;
    List<LlmClient.Message> snapshot = window();
    snapshot.add(new LlmClient.Message(true, fill(praiseGuide().strip()).replace("{할일}", todo)));
    request(snapshot, 0, useNotes, null);
  }

  /**
   * 할 일을 내일로 미뤘을 때 마도카가 비밀 쪽지로 혼낸다. 할 일 줄을 오른쪽 클릭해서 미루는 자리에서
   * Main 을 거쳐 불린다. 칭찬(praiseTodo)과 같은 틀이고, 쪽지를 끈 설정이면 보통 말풍선으로 온다.
   */
  public void scoldPostpone(String todo) {
    if (!proactive || asking || silent())
      return;
    List<LlmClient.Message> snapshot = window();
    snapshot.add(new LlmClient.Message(true, TODO_POSTPONE.strip().replace("{할일}", todo)));
    request(snapshot, 0, true, null);
  }

  public void remindTodos(LocalDate day, List<String> todos) {
    if (!proactive || asking || silent() || day == null || todos.isEmpty() || day.toString().equals(todoNaggedOn))
      return;

    StringBuilder list = new StringBuilder();
    for (String todo : todos)
      list.append("- ").append(todo).append("\n");

    List<LlmClient.Message> snapshot = window();
    snapshot.add(new LlmClient.Message(true, fill(todoGuide().strip()).replace("{할일}", list.toString().strip())));
    request(snapshot, 0, useNotes, () -> {
      todoNaggedOn = day.toString();
      savePresence();
    });
  }

  /** 오늘 이미 먼저 말을 걸었는지 — 마지막으로 건 날짜가 오늘인지 */
  private boolean nudgedToday() {
    if (nudgedAt <= 0)
      return false;
    LocalDate day = Instant.ofEpochMilli(nudgedAt).atZone(ZoneId.systemDefault()).toLocalDate();
    return day.equals(LocalDate.now());
  }

  /**
   * 먼저 건 말에 찍을 시각 — 내가 지난번에 들른 때와 지금 사이 아무 때나.
   *
   * 다 읽고 나간 뒤에 온 말이 "지금" 으로 찍히면 창을 부르자마자 말이 오는 꼴이라 지켜보고
   * 있다가 튀어나온 것처럼 보인다. 내가 없던 사이 어느 때 보낸 걸로 찍어두면, 들어와 보니
   * 그새 톡이 와 있는 모양이 된다.
   *
   * 아래 끝은 지난번에 들른 때(prevFocusAt)인데, 마지막 줄이나 마지막으로 읽은 때보다
   * 앞으로는 안 간다 — 그보다 앞에 찍히면 순서가 꼬이거나, 읽었을 때 이미 있어야 했던 말이 된다.
   * 물릴 틈이 없으면 그냥 지금이다.
   */
  private long backdate(ChatHistory.Line last, long now) {
    long from = Math.max(last.at, Math.max(readAt, prevFocusAt)) + 1;
    if (from >= now)
      return now;
    return ThreadLocalRandom.current().nextLong(from, now);
  }

  /**
   * 먼저 말을 걸라고 끼워 넣는 안내. 내가 친 말인 척 user 쪽에 붙지만 화면에도 기록에도 안 남는다.
   *
   * 마지막 말이 누구 것이었냐에 따라 할 말이 갈린다. 내 말이 마지막이면 답을 못 받고 끊긴
   * 자리라 그 말은 접어두고 마도카가 제 얘기를 먼저 꺼낸다 ([공통] 없이 그 칸만 간다), 마도카 말이 마지막이면 먼저 걸었는데 내가 답을 안 한
   * 자리라 같은 말을 되풀이하면 안 된다 — 후자를 안 갈라주면 아까 한 인사를 글자 그대로
   * 다시 해서, 말을 건 게 아니라 고장 난 것처럼 보인다.
   *
   * 마도카 말이 마지막일 땐 그걸 내가 읽었는지도 알려준다 (readAgoMs, 안 읽었으면 음수).
   * 읽고도 답이 없는 것과 아직 못 본 것은 건넬 말이 다르다 — 못 본 사람한테 "왜 답이 없어"
   * 하면 억울하고, 읽은 사람한테 아무 일 없던 척 하면 눈치가 없어 보인다.
   *
   * 상대가 지금 화면을 보고 있다고는 안 적는다. 시각을 물려 찍은 말은 상대가 없던 사이에
   * 보낸 말이라, "방금 들어왔네" 같은 말이 나오면 앞뒤가 안 맞는다.
   *
   * 문구는 안내문(NUDGE, 또는 고쳐 쓴 것)에서 칸별로 꺼내 빈자리만 채운다.
   */
  private String nudgePrompt(long idleMs, boolean lastWasMine, long readAgoMs) {
    Map<String, String> guide = sections(fill(nudgeGuide()));
    if (lastWasMine)
      return guide.get(CUT_OFF).replace("{지난시간}", ago(idleMs));
    String situation = guide.get(lastWasMine ? CUT_OFF : readAgoMs >= 0 ? READ_NO_REPLY : UNREAD);
    String readAgo = readAgoMs >= 0 ? ago(readAgoMs) : "";

    return guide.get(COMMON)
        .replace("{상황}", situation)
        .replace("{지난시간}", ago(idleMs))
        .replace("{읽은시간}", readAgo);
  }

  /**
   * 안내문을 [이름] 줄로 갈라 칸별로 담는다. 첫 [이름] 앞에 쓴 건 [공통] 에 붙는다 —
   * 칸 표시를 다 지우고 한 덩어리로만 써도 그게 틀로 가게.
   * 모르는 [이름] 은 칸을 가르지 않고 그냥 글로 친다. 비어 있는 칸은 기본값 칸으로 채운다.
   */
  private Map<String, String> sections(String text) {
    Map<String, String> out = parse(text);
    Map<String, String> fallback = parse(fill(NUDGE));
    for (String name : SECTIONS) {
      if (out.get(name).isEmpty())
        out.put(name, fallback.get(name));
    }
    return out;
  }

  /** [이름] 줄로 가르기만 한다. 없는 칸은 빈 문자열 */
  private static Map<String, String> parse(String text) {
    Map<String, StringBuilder> found = new LinkedHashMap<>();
    String current = COMMON;
    for (String line : text.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        String name = trimmed.substring(1, trimmed.length() - 1).strip();
        if (SECTIONS.contains(name)) {
          current = name;
          continue;
        }
      }
      found.computeIfAbsent(current, k -> new StringBuilder()).append(line).append("\n");
    }

    Map<String, String> out = new LinkedHashMap<>();
    for (String name : SECTIONS) {
      StringBuilder body = found.get(name);
      out.put(name, body == null ? "" : body.toString().strip());
    }
    return out;
  }

  /** 안내에 적을 지난 시간. 한 시간이 안 되면 분으로 — "0시간" 이라고 적히지 않게 */
  private static String ago(long ms) {
    long minutes = Math.max(1, ms / (60 * 1000L));
    if (minutes < 60)
      return minutes + "분";
    return (minutes / 60) + "시간";
  }

  /**
   * 쪼개진 답을 한 덩어리씩 차례로 올린다 (index 번째부터, 다음 것은 뜸 들였다 제 발로 이어진다).
   *
   * 다 올리고 나서야 asking 을 내리고, 그때 한 번만 접기를 돌아본다.
   *
   * 뜿 들이는 사이에 대화를 지울 수도 있어서, 출발할 때의 era 를 끝까지 들고 다닌다 —
   * 달라졌으면 남은 말풍선은 그냥 삼킨다.
   *
   * earlierMs 는 시각을 물려 찍을 만큼이다 (request 참고). 말풍선마다 같은 만큼 물려서
   * 사이사이 뜸은 그대로 남는다. firstAt 은 첫 말풍선 시각 — 호감도 기록에 미리 적어둔 시각과 맞춘다.
   */
  private void say(List<String> bubbles, int index, int startedIn, long earlierMs, boolean note, long firstAt) {
    if (startedIn != era)
      return;

    addLine(bubbles.get(index), false, index == 0 ? firstAt : System.currentTimeMillis() - earlierMs, note);

    if (index + 1 >= bubbles.size()) {
      if (pictureNext != null) {
        WebPicture.Found picture = pictureNext;
        pictureNext = null;
        addLine(ChatImage.TEXT, false, System.currentTimeMillis() - earlierMs, false, picture.path(), picture.caption());
        lines.get(lines.size() - 1).pictureSearch = picture.search();
        store.save(lines);
      }
      asking = false;
      // 말하는 사이 끼어든 말이 있으면 바로 그 중 마지막 말에 답장한다 (ask 가 짚는다)
      if (queued) {
        queued = false;
        pending = true;
        ask();
        return;
      }
      // 답이 붙어서 창 밖으로 밀려난 말이 생겼을 수 있다. 수다가 끊긴 이 틈에 접어둔다.
      maybeFold();
      return;
    }

    // 뜸을 끄면 남은 말풍선을 쉬지 않고 바로 이어 올린다
    if (!typingDelay || typingMsPerByte <= 0) {
      say(bubbles, index + 1, startedIn, earlierMs, note, firstAt);
      return;
    }
    String next = bubbles.get(index + 1);
    long bytes = next.getBytes(StandardCharsets.UTF_8).length;
    PauseTransition gap = new PauseTransition(Duration.millis(bytes * typingMsPerByte));
    gap.setOnFinished(e -> say(bubbles, index + 1, startedIn, earlierMs, note, firstAt));
    gap.play();
  }

  /**
   * 한 번에 받아온 답을 말풍선 단위로 쪼갠다. 줄바꿈 하나가 곧 새 말풍선이다 (성격문에서 그렇게 시켜뒀다).
   *
   * 시킨 것보다 잘게 쪼개 보내는 날도 있어서 MAX_BUBBLES 개까지만 나누고 나머지는 마지막에 붙인다 —
   * 말풍선이 다섯 개씩 쏟아지느니 마지막 하나가 길어지는 편이 낫다. 어차피 모델한테 되돌려 보낼 때는
   * 연달아 한 말이 한 덩어리로 합쳐져서 (GeminiClient.merge), 몇 개로 갈랐든 대화는 그대로 이어진다.
   */
  private static List<String> split(String raw) {
    List<String> bubbles = new ArrayList<>();
    if (raw == null)
      return bubbles;

    for (String piece : raw.strip().split("\n")) {
      String trimmed = piece.strip();
      if (trimmed.isEmpty())
        continue;
      if (bubbles.size() < MAX_BUBBLES)
        bubbles.add(trimmed);
      else
        bubbles.set(bubbles.size() - 1, bubbles.get(bubbles.size() - 1) + "\n" + trimmed);
    }
    return bubbles;
  }

  /** 오간 말 한 줄. 화면에 올리고 파일에 남긴다 */
  private void addLine(String text, boolean mine) {
    addLine(text, mine, System.currentTimeMillis(), false);
  }

  /**
   * 시각을 정해서 남기는 한 줄. 먼저 건 말을 물려 찍을 때만 지금이 아닌 시각이 온다 (backdate).
   * note 면 비밀 쪽지 — 말풍선엔 접힌 쪽지만 보이고, 알림에도 내용 대신 쪽지가 왔다고만 띄운다.
   */
  private void addLine(String text, boolean mine, long at, boolean note) {
    addLine(text, mine, at, note, null, null);
  }

  /** image 가 있으면 사진 한 장을 올리는 줄이다. caption 이 null 이면 아직 안 읽은 사진이다 (describeFirst) */
  private void addLine(String text, boolean mine, long at, boolean note, String image, String caption) {
    ChatHistory.Line line = new ChatHistory.Line(mine, text, at);
    line.note = note;
    line.image = image;
    line.caption = caption;
    line.cutIn = mine && asking;
    // 끼어든 말에 하는 답은 첫 말풍선이 그 말을 짚는다 (ask)
    if (!mine && quoteNext != null) {
      line.replyAt = quoteNext.at;
      line.replyMine = true;
      line.replyNote = quoteNext.note;
      line.replyText = quoteNext.text;
      quoteNext = null;
    }
    // 걸어둔 답장은 내가 보낸 첫 말풍선에 붙는다
    if (mine && replyTo != null) {
      line.replyAt = replyTo.at;
      line.replyMine = replyTo.mine;
      line.replyNote = replyTo.note;
      line.replyText = replyTo.text;
      cancelReply();
    }
    lines.add(line);
    store.save(lines);
    talkedAt = System.currentTimeMillis();
    savePresence();
    append(line);
    if (image != null || !ChatSide.linksIn(text).isEmpty())
      side.refreshDrawer();
    // 눈앞에서 붙은 말은 붙자마자 읽은 셈이다
    if (input.isFocused())
      markRead();
    // 이 화면 밖에서 올라온 마도카 말은 아직 아무도 못 봤다 — 그림 옆에 표시를 띄워둔다
    if (!mine && !reading())
      setUnread(unread + 1);
    if (!mine && !retired)
      onMadoka.accept(note ? "💌 비밀 쪽지가 왔어" : text);
  }

  /**
   * 지금 이 화면을 보고 있는지. 기본 화면으로 나가면 root 에서 떨어져 나가 씬이 없어져서
   * (Main 의 showHome 참고) 씬이 붙어 있는지만 보면 된다.
   *
   * 나가 있는 사이에도 말은 올라온다 — 답이 늦게 와서 말풍선이 하나씩 붙는 중에 Esc 로
   * 나가버린 경우다. 그렇게 뒤늦게 붙은 말이 곧 '안 읽은 말' 이 된다.
   *
   * 창을 숨긴 건 안 가린다. 수다 화면을 켜 둔 채 숨겼다 다시 부르면 포커스가 이 입력칸으로
   * 돌아오면서 화면도 그대로 눈앞에 뜨니까 (Main 의 bringToFront), 배지를 거칠 일이 없다.
   */
  private boolean reading() {
    return getScene() != null;
  }

  /** 이 화면 밖에서 올라와 아직 안 읽은 상대 말 수 */
  public int unread() {
    return unread;
  }

  /**
   * 화면이 안 떠 있는 상대의 안 읽은 말 수 — 파일에서 센다. 마지막으로 그 수다를 본 때 (Presence.readAt)
   * 뒤에 붙은 상대 말 (화면에서 지운 줄 빼고)
   */
  public static int unreadOf(ChatProfile profile) {
    long readAt = new Presence(profile.path("presence.json")).load().readAt;
    int count = 0;
    for (ChatHistory.Line line : new ChatHistory(profile.path("chat.json")).load()) {
      if (!line.mine && !line.hidden && line.at > readAt)
        count++;
    }
    return count;
  }

  /** 안 읽은 수를 고쳐 쓰고 Main 한테 알린다. 달라진 게 없으면 조용히 넘어간다 */
  private void setUnread(int count) {
    if (retired || unread == count)
      return;
    unread = count;
    onUnread.accept(unread);
  }

  // ── 기억 ───────────────────────────────────────────────────────────────────
  // 모델한테 건네는 건 두 덩어리다: 성격문 뒤에 붙는 메모(접어둔 옛날 얘기)와
  // 원문 그대로 가는 최근 말풍선 WINDOW_LINES 개. 대화가 아무리 길어져도 보내는 양이 여기서 멈춘다.

  /** 성격 + 말하는 방식(BasePrompt) + 지금까지 접어둔 기억. 메모가 비었으면 기억은 안 붙는다 */
  private String persona() {
    String base = fill(prompt()).stripTrailing() + "\n\n" + fill(this.base.text()).strip() + "\n";
    // 켜둔 채로 날이 바뀌면 지난 줄이 아직 들고 있는 memo 에 남아 있어서 여기서도 한 번 거른다
    String known = WorkingMemory.plain(memo, LocalDate.now());
    if (known.isBlank())
      return base;
    // 굳이 먼저 꺼내 말하라고 하면 "너 고양이 키우잖아!" 를 인사처럼 하게 돼서 일러둔다
    return base.stripTrailing() + "\n" + """

        [기억해 둔 것]
        예전에 나눈 얘기에서 추려둔 거야. 알고만 있고, 묻지도 않았는데 먼저 꺼내진 마.
        """ + known;
  }

  /**
   * 건네는 말 중에 "할 일" / "할일" 이 나오거나, 내가 오늘 할 일 하나를 콕 집어 말하면
   * ("영어 숙제 하기 싫다") 오늘 할 일 목록을 성격문 뒤에 붙여준다. 콕 집은 게 있으면 그게
   * 어느 할 일인지도 같이 일러서, 마도카가 "그거 오늘 할 일에 있던 거지?" 하고 알아채게 한다.
   * 둘 다 아니면 그대로 — 묻지도 않았는데 할 일 얘기를 꺼내지 않게, 그리고 매번 목록을 싣지 않게.
   * 목록은 붙일 때마다 새로 읽어서, 방금 체크한 것까지 맞게 보인다.
   */
  private String withTodos(String base, List<LlmClient.Message> snapshot) {
    boolean asked = mentionsTodo(snapshot);
    List<String> picked = asked ? List.of() : pickedTodos(snapshot);
    if (!asked && picked.isEmpty())
      return base;
    List<String> todos = new ArrayList<>();
    for (TodoLine todo : todoItems.get())
      todos.add(todo.line());
    String list = todos.isEmpty() ? "(오늘은 적어둔 할 일이 없어.)" : String.join("\n", todos);
    String out = base.stripTrailing() + "\n" + """

        [오늘 할 일]
        상대가 위젯에 적어둔 오늘 할 일이야. 할 일 얘기가 나오면 이걸 보고 답해.
        [x] 는 끝낸 것, [ ] 는 아직 안 한 것이야.
        """ + list;
    if (picked.isEmpty())
      return out;
    return out.stripTrailing() + "\n" + """

        [방금 꺼낸 할 일]
        상대가 방금 한 말이 오늘 할 일 중 이것 얘기 같아:
        """ + String.join("\n", picked) + """

        오늘 할 일에 적어둔 거라는 걸 네가 알아챈 티를 자연스럽게 내줘 —
        아직 안 했으면 응원하거나 살짝 챙기고, 이미 끝냈으면 그걸 알아봐줘.
        목록을 줄줄 읊거나 다른 할 일까지 끌어오지는 마. 엉뚱한 얘기 같으면 모른 척 넘어가.
        """;
  }

  /**
   * 건네는 말 중에 상태 메시지 얘기가 나오면 양쪽 상태 메시지를 성격문 뒤에 붙여준다. 평소엔 안 실어서
   * 토큰을 아끼고, 묻지도 않았는데 상태 메시지 얘기를 꺼내지 않게 한다. 글이 없으면 없다고 일러서
   * 있는 척 지어내지 않게 한다.
   */
  private String withStatus(String base, List<LlmClient.Message> snapshot) {
    if (!mentionsStatus(snapshot))
      return base;
    return base.stripTrailing() + "\n" + """

        [상태 메시지]
        메신저 프로필에 걸려 있는 상태 메시지야. 상태 메시지 얘기가 나오면 이걸 보고 답해.
        """ + "네 상태 메시지: " + statusOrNone(status.text()) + "\n"
        + "상대 상태 메시지: " + statusOrNone(SettingsDialog.load().myStatus) + "\n";
  }

  private static String statusOrNone(String text) {
    return text == null || text.isBlank() ? "(아직 없어)" : text;
  }

  /**
   * 마지막 티키타카에서 내가 친 말에 오늘 할 일이 콕 집혀 나왔는지 — 집힌 할 일의 목록 줄들.
   * 할 일 글을 낱말로 쪼개 어간만 남겨서 (stems) 하나라도 내 말에 들어 있으면 집힌 걸로 친다.
   * "운동하기" 를 적어두고 "운동 갔다 왔어" 라고 해도 걸리게.
   */
  private List<String> pickedTodos(List<LlmClient.Message> snapshot) {
    String said = lastSaid(snapshot);
    if (said.isBlank())
      return List.of();
    List<String> picked = new ArrayList<>();
    for (TodoLine todo : todoItems.get()) {
      for (String stem : stems(todo.content())) {
        if (said.contains(stem)) {
          picked.add(todo.line());
          break;
        }
      }
    }
    return picked;
  }

  /** 마지막 티키타카에서 내가 친 말만 이어 붙인 것 (공백 없이 — 띄어쓰기가 달라도 걸리게) */
  private static String lastSaid(List<LlmClient.Message> snapshot) {
    StringBuilder said = new StringBuilder();
    for (int i = snapshot.size() - 1; i >= 0; i--) {
      LlmClient.Message message = snapshot.get(i);
      if (!message.mine())
        break;
      if (message.text() != null)
        said.insert(0, message.text().replaceAll("\\s+", ""));
    }
    return said.toString();
  }

  /** 붙여도 뜻이 안 남는 끝말. 떼고 남은 게 어간이다 (stems) */
  private static final List<String> ENDINGS = List.of("하기", "하러", "가기", "보기", "기", "하");

  /** 이것만 겹쳐서는 그 할 일 얘기라고 못 보는 흔한 낱말 */
  private static final List<String> COMMON_WORDS = List.of("오늘", "내일", "하기", "끝내기", "준비", "정리", "확인", "다시");

  /**
   * 할 일 글에서 대화에 나오면 그 할 일 얘기라고 볼 만한 조각들. 낱말마다 끝말(ENDINGS)을 떼고,
   * 두 글자가 안 되거나 흔한 낱말(COMMON_WORDS)이면 버린다 — "책 읽기" 의 "책" 처럼 한 글자는
   * 아무 말에나 걸려서. 남은 게 하나도 없으면 띄어쓰기를 지운 할 일 글 전체를 쓴다.
   */
  private static List<String> stems(String content) {
    List<String> out = new ArrayList<>();
    if (content == null)
      return out;
    for (String word : content.split("[\\s\\p{Punct}·]+")) {
      String stem = word;
      for (String ending : ENDINGS) {
        if (stem.endsWith(ending) && stem.length() - ending.length() >= 2) {
          stem = stem.substring(0, stem.length() - ending.length());
          break;
        }
      }
      if (stem.length() >= 2 && !COMMON_WORDS.contains(stem))
        out.add(stem);
    }
    if (out.isEmpty()) {
      String whole = content.replaceAll("\\s+", "");
      if (whole.length() >= 2)
        out.add(whole);
    }
    return out;
  }

  /**
   * 답 끝에 몰래 다는 호감도 표시. 마도카가 이번 말로 나를 얼마나 더 (덜) 좋아하게 됐는지다.
   * 줄 끝에 붙여 오는 날도 있어서 줄 하나가 아니라 어디 있든 찾아서 뗀다.
   * | 뒤는 왜 바뀌었는지 마도카 속마음 한마디 ([[호감도:+1|헤헤 고마워!]]). 빠져 있어도 받아준다.
   */
  private static final Pattern AFFECTION_MARK =
      Pattern.compile("\\[\\[\\s*호감도\\s*[:：]?\\s*([+-]?)\\s*(\\d{1,9})\\s*(?:[|｜]([^\\]]*))?\\]\\]");

  /** 이유 한마디 최대 길이. 길게 늘어놓으면 목록에서 한눈에 안 들어와서 자른다 */
  private static final int AFFECTION_REASON_MAX = 40;

  /**
   * 답에서 호감도 표시를 떼어내 호감도에 더하고, 뗀 나머지를 돌려준다. 표시가 없으면 그대로다.
   * 표시는 화면에도 기록에도 안 남는다 — 말풍선에 "[[호감도:+1]]" 이 찍히면 몰래 단 의미가 없어서.
   * at 은 이 답의 첫 말풍선이 찍힐 시각. 오르내린 기록에 그대로 남긴다 (Affection.Change.at)
   */
  private String takeAffection(String raw, long at) {
    if (raw == null)
      return null;
    Matcher m = AFFECTION_MARK.matcher(raw);
    int delta = 0;
    boolean found = false;
    String reason = "";
    while (m.find()) {
      found = true;
      int step = Integer.parseInt(m.group(2));
      delta += "-".equals(m.group(1)) ? -step : step;
      // 여러 개 달려 오면 마지막 한마디로
      if (m.group(3) != null && !m.group(3).isBlank())
        reason = m.group(3).strip();
    }
    if (!found)
      return raw;
    int before = liking;
    liking += delta;
    // [[호감도:0]] 이라 안 바뀌었으면 남길 것도 없다
    if (liking != before) {
      if (reason.length() > AFFECTION_REASON_MAX)
        reason = reason.substring(0, AFFECTION_REASON_MAX) + "…";
      affection.change(liking, liking - before, reason, at);
      showLiking();
      if (liking - before >= HEART_STEP)
        heartLastMine();
    }
    return m.replaceAll("").strip();
  }

  /** 이 답으로 호감도가 줄어드는지. 줄어드는 답엔 사진을 안 붙인다 — 화나서 깎으면서 사진을 주면 앞뒤가 안 맞는다 */
  static boolean affectionDrops(String raw) {
    if (raw == null)
      return false;
    Matcher m = AFFECTION_MARK.matcher(raw);
    int delta = 0;
    while (m.find())
      delta += ("-".equals(m.group(1)) ? -1 : 1) * Integer.parseInt(m.group(2));
    return delta < 0;
  }

  /** 한 번에 이만큼 넘게 오르면 마도카가 그 말에 하트를 단다 */
  private static final int HEART_STEP = 3;

  /**
   * 방금 마도카를 기쁘게 한 내 말 — 화면에 보이는 맨 아래 줄 — 에 하트를 단다.
   * 맨 아래가 마도카 말이면 (먼저 말을 건 자리) 하트를 달 내 말이 없는 거라 그냥 둔다
   */
  private void heartLastMine() {
    for (int i = lines.size() - 1; i >= 0; i--) {
      ChatHistory.Line line = lines.get(i);
      if (line.hidden)
        continue;
      if (line.mine && !line.heart) {
        line.heart = true;
        store.save(lines);
        render();
      }
      return;
    }
  }

  /**
   * 호감도를 어떻게 매기는지는 늘 붙인다 — 답마다 표시를 달아야 오르내리니까.
   * 지금 값은 마지막 티키타카에 "호감도" · "사랑" · "좋아" 라는 말이 나왔을 때만 알려준다. 매번 알려주면
   * 묻지도 않았는데 숫자를 의식한 말투가 돼서, 할 일 목록(withTodos)과 같은 결로 뒀다.
   * 다만 음수면 늘 알려준다 — 마음이 상해 있는 걸 잊고 살갑게 굴면 안 되니까.
   */
  private String withAffection(String base, List<LlmClient.Message> snapshot) {
    String out = base.stripTrailing() + "\n" + """

        [호감도]
        답 맨 끝 줄에 이번 대화로 바뀐 만큼을 [[호감도:+2|속마음 한마디]] 꼴로 적어 (그대로면 [[호감도:0]]).
        다정하면 올리고 무례하면 내려. 소소하면 1~2, 크게 감동하거나 상처받으면 망설이지 말고 크게. 상대한텐 안 보여.
        """;
    boolean mentioned = lastTurnMentions(snapshot, "호감도", "사랑", "좋아");
    if (!mentioned && liking >= 0)
      return out;
    String why = mentioned
        ? "좋아하는 마음 얘기가 나왔으니 이 숫자를 보고 답해."
        : "아직 마음이 상해 있으니 그걸 잊지 말고 답해.";
    String mood = liking >= LOVE_AT ? "\n지금은 상대를 아주 많이 좋아하고 있는 거야."
        : liking < 0 ? "\n음수라는 건 상대한테 서운하거나 화가 나 있다는 뜻이야." : "";
    return out.stripTrailing() + "\n" + """

        지금 상대한테 품은 호감도는 %d 야. %d 이상이면 상대를 아주 많이 좋아하는 거야. %s%s
        """.formatted(liking, LOVE_AT, why, mood);
  }

  /** 이 이상이면 상대를 아주 많이 좋아한다 — Affection 머리말 이모지의 제일 높은 칸과 같은 선 */
  private static final int LOVE_AT = 300;

  /**
   * 마도카가 접어 보낸 비밀 쪽지 표시 — [[쪽지:ㅗ ^^]]. 안의 글이 곧 쪽지 내용이다.
   * 호감도 표시처럼 어디 있든 찾는다. 여러 줄이어도 받는다.
   */
  private static final Pattern NOTE_MARK =
      Pattern.compile("\\[\\[\\s*(?:비밀\\s*)?쪽지\\s*[:：]\\s*(.*?)\\s*\\]\\]", Pattern.DOTALL);

  /** 답에 접어 보낸 쪽지가 있으면 그 내용 (여럿이면 첫 장). 없거나 빈 쪽지면 null */
  private static String noteIn(String raw) {
    if (raw == null)
      return null;
    Matcher m = NOTE_MARK.matcher(raw);
    while (m.find()) {
      if (!m.group(1).isBlank())
        return m.group(1).strip();
    }
    return null;
  }

  /** 쪽지 표시를 벗겨 안의 글만 남긴다 — 쪽지를 끈 설정일 때 보통 말로 올리려고 */
  private static String unwrapNotes(String raw) {
    if (raw == null)
      return null;
    return NOTE_MARK.matcher(raw).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
  }

  /**
   * 비밀 쪽지를 어떻게 쓰는지 일러둔다. 쪽지를 끈 설정이면 아무것도 안 붙인다.
   * asNote 면 이번 말이 통째로 쪽지 한 장에 담긴다고 알려준다 — 먼저 말을 거는 자리.
   * 아니면 깜짝 긁을 때만 골라 쓰라고 한다. 상대도 쪽지를 보낼 수 있다는 것도 같이 일러둔다.
   */
  private String withNotes(String base, boolean asNote) {
    if (!useNotes)
      return base;
    if (asNote)
      return base.stripTrailing() + "\n" + """

          [비밀 쪽지]
          이번 말은 네가 먼저 건네는 비밀 쪽지 한 장에 담겨 전해져. 상대는 쪽지를 눌러야 내용을 봐.
          쪽지에 적을 말만 써줘. [[쪽지:]] 표시는 안 붙여도 돼.
          """;
    return base.stripTrailing() + "\n" + """

        [비밀 쪽지]
        가끔 의외의 순간에 장난이나 짧은 욕을 [[쪽지:ㅗ ^^]] 로 접어 보낼 수 있어. 그땐 쪽지 하나만 보내 (호감도 표시는 달아).
        상대가 보낸 [[쪽지:...]] 는 몰래 건넨 쪽지로 알고 답해.
        """;
  }

  /**
   * 지금 몇 시인지. 모르면 새벽 세 시에 "좋은 아침!" 을 하게 돼서 부를 때마다 새로 적어준다.
   * 먼저 건 말을 물려 찍을 땐 (backdate) 실제 지금이 아니라 그 말이 찍힐 시각을 적는다 —
   * 아니면 새벽에 보낸 걸로 찍힌 말이 "점심 먹었어?" 를 하게 된다.
   */
  private static final DateTimeFormatter NOW_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE a h시 m분", Locale.KOREAN);

  private static String withNow(String base, long at) {
    return base.stripTrailing() + "\n" + """

        [지금 시각]
        """ + LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).format(NOW_FORMAT) + """

        시간대에 맞게 말해. 묻지도 않았는데 시각을 굳이 읊지는 마.
        """;
  }

  /**
   * 마지막 티키타카 한 번(내가 친 마지막 말과 그 뒤) 안에서만 찾는다. 창 전체를 보면
   * 한 번 꺼낸 할 일 얘기가 창 밖으로 밀려날 때까지 몇 번이고 목록이 따라붙어서.
   */
  private static boolean mentionsTodo(List<LlmClient.Message> snapshot) {
    return lastTurnMentions(snapshot, "할 일", "할일");
  }

  /** mentionsTodo 처럼 마지막 티키타카 안에서만 찾는다 */
  private static boolean mentionsStatus(List<LlmClient.Message> snapshot) {
    return lastTurnMentions(snapshot, "상태 메시지", "상태메시지", "상태 메세지", "상태메세지", "상메");
  }

  /** 마지막 티키타카 한 번(내가 친 마지막 말과 그 뒤) 안에 words 중 하나라도 나왔는지 */
  private static boolean lastTurnMentions(List<LlmClient.Message> snapshot, String... words) {
    int start = 0;
    for (int i = snapshot.size() - 1; i >= 0; i--) {
      if (snapshot.get(i).mine()) {
        start = i;
        break;
      }
    }
    for (LlmClient.Message message : snapshot.subList(start, snapshot.size())) {
      String text = message.text();
      if (text == null)
        continue;
      for (String word : words) {
        if (text.contains(word))
          return true;
      }
    }
    return false;
  }

  /** 원문 그대로 들려줄 몫 — 최근 말풍선 WINDOW_LINES 개. 그 앞은 메모가 대신한다 */
  private List<LlmClient.Message> window() {
    List<LlmClient.Message> out = new ArrayList<>();
    int start = windowStart();
    // 끼어든 말(cutIn)은 마도카가 하던 말을 다 마친 뒤로 물린다 — 그 답을 만들 땐 못 들은 말이라서.
    // 다음 내 말이나, 그 말에 답장으로 온 마도카 말 바로 앞에서 풀어놓는다.
    List<ChatHistory.Line> order = new ArrayList<>();
    List<ChatHistory.Line> held = new ArrayList<>();
    for (ChatHistory.Line line : lines.subList(start, lines.size())) {
      if (line.mine && line.cutIn) {
        held.add(line);
        continue;
      }
      if (line.mine || line.replyText != null) {
        order.addAll(held);
        held.clear();
      }
      order.add(line);
    }
    order.addAll(held);
    // 아직 답을 못 받은 내 말들의 첫 자리 — 맨 끝에서 마도카 말이 나오기 전까지
    int fresh = order.size();
    while (fresh > 0 && order.get(fresh - 1).mine)
      fresh--;
    for (int i = 0; i < order.size(); i++) {
      ChatHistory.Line line = order.get(i);
      // 쪽지는 보낸 모양 그대로 들려준다 — 쪽지로 보냈다는 걸 마도카도 알게
      String text = line.note ? "[[쪽지:" + line.text + "]]" : ChatImage.spoken(line);
      // 마도카가 단 답장 표시는 안 들려준다 — 바로 앞에 그 말이 오도록 물려놨고, 따라 쓰면 곤란해서
      if (line.mine && line.replyText != null)
        text = replyPrefix(line, start, i >= fresh) + text;
      out.add(new LlmClient.Message(line.mine, text));
    }
    return out;
  }

  /**
   * 답장한 말 앞에 붙이는 표시. 답장한 말이 창 안에 있으면 짧게 짚기만 한다.
   * 창 밖(먼 옛날)이면 마도카가 모르는 말이라 원문을 통째로 붙이는데, 그건 그 말에 처음 답할 때
   * 한 번뿐이다 (fresh) — 그 뒤엔 마도카 답이 그 얘기를 이어받았으니 매번 다시 실어 보내지 않는다.
   */
  private String replyPrefix(ChatHistory.Line line, int windowStart, boolean fresh) {
    String who = line.replyMine ? "내가" : "네가";
    boolean inWindow = indexOfReply(line) >= windowStart;
    if (inWindow || !fresh)
      return "[↪ " + who + " 한 말 \"" + shorten(line.replyText, REPLY_HINT_CHARS) + "\" 에 답장] ";
    return "[↪ 예전에 " + who + " 한 말에 답장: \"" + line.replyText + "\"] ";
  }

  /** 답장한 말이 lines 몇 번째인지. 오래돼서 잘려 나갔으면 -1 */
  private int indexOfReply(ChatHistory.Line line) {
    for (int i = lines.size() - 1; i >= 0; i--) {
      ChatHistory.Line at = lines.get(i);
      if (at.at == line.replyAt && at.mine == line.replyMine && at.text.equals(line.replyText))
        return i;
    }
    return -1;
  }

  /** 답장 줄 · 인용에 보일 글. 비밀 쪽지는 열어봤어도 내용을 안 비친다 */
  private static String preview(String text, boolean note) {
    return note ? "💌 비밀 쪽지" : shorten(text, REPLY_HINT_CHARS);
  }

  /** 한 줄로 펴서 max 글자까지 */
  static String shorten(String text, int max) {
    String flat = text.replace('\n', ' ').strip();
    return flat.length() <= max ? flat : flat.substring(0, max) + "…";
  }

  /** 원문으로 들려줄 첫 줄 자리 — 뒤에서부터 말풍선 WINDOW_LINES 개. 그만큼 안 쌓였으면 0 (통째로) */
  private int windowStart() {
    return Math.max(0, lines.size() - WINDOW_LINES);
  }

  /** 이 줄들 중 내가 친 게 몇 개인지 — 쌓인 양을 티키타카 횟수로 셀 때 쓴다 */
  private static int turnsIn(List<ChatHistory.Line> pile) {
    int mine = 0;
    for (ChatHistory.Line line : pile) {
      if (line.mine)
        mine++;
    }
    return mine;
  }

  /**
   * 창 밖으로 밀려났는데 아직 메모에 못 들어간 말들. 오래된 것부터.
   *
   * 줄 번호가 아니라 시각으로 가르는 건 chat.json 이 300줄에서 앞을 잘라내기 때문 —
   * 껐다 켜면 번호는 밀려도 줄에 붙은 시각은 그대로라 같은 걸 또 요약하지 않는다.
   */
  private List<ChatHistory.Line> unfolded() {
    List<ChatHistory.Line> out = new ArrayList<>();
    int edge = windowStart();
    for (int i = 0; i < edge; i++) {
      if (lines.get(i).at > foldedUpTo)
        out.add(lines.get(i));
    }
    return out;
  }

  /**
   * 밀려난 말이 티키타카 FOLD_TURNS 번 분만큼 쌓였으면 메모를 다시 써달라고 한 번 부른다.
   *
   * 답변과 같은 FAST 모드로 부르지만 사람을 기다리게 하는 호출이 아니라서 화면에는
   * 아무 표시도 안 한다. 실패하면 foldedUpTo 를 안 옮기고 물러나니까, 다음 답변 뒤에
   * 같은 자리에서 다시 시도한다 — 실패한 내 말을 pending 으로 들고 있는 것과 같은 결.
   */
  private void maybeFold() {
    if (folding || !llm.isReady())
      return;

    List<ChatHistory.Line> pile = unfolded();
    if (turnsIn(pile) < FOLD_TURNS)
      return;
    if (pile.size() > FOLD_MAX)
      pile = pile.subList(0, FOLD_MAX);

    // 접기에 성공했을 때 여기까지 들어갔다고 찍어둘 시각
    long mark = pile.get(pile.size() - 1).at;
    LocalDate today = LocalDate.now();
    String prompt = foldPrompt(WorkingMemory.prune(memo, today), pile, name(), today);
    int startedIn = era;
    folding = true;

    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return llm.chat(FOLD_PERSONA, List.of(new LlmClient.Message(true, prompt)), LlmClient.Mode.FAST);
      }
    };

    task.setOnSucceeded(e -> {
      // 요약하는 사이에 대화를 지웠으면, 지운 얘기를 메모로 되살리는 꼴이라 버린다
      if (startedIn != era)
        return;
      folding = false;
      String folded = WorkingMemory.stamp(clean(task.getValue()), today);
      // 빈 답이 오면 있던 기억까지 날아가니까 그때는 그냥 하던 걸 들고 간다
      if (folded.isBlank())
        return;
      memo = folded;
      foldedUpTo = mark;
      memory.save(memo, foldedUpTo);
    });

    // 말없이 물러난다. foldedUpTo 를 안 옮겼으니 다음 답변 뒤에 또 접어본다.
    task.setOnFailed(e -> {
      if (startedIn == era)
        folding = false;
    });

    Thread worker = new Thread(task, "madoka-memo");
    worker.setDaemon(true);
    worker.start();
  }

  /** 메모를 다시 써달라고 시키면서 건네는 말 — 지금 메모와, 새로 접어 넣을 대화 */
  private static String foldPrompt(String memo, List<ChatHistory.Line> pile, String name, LocalDate today) {
    StringBuilder sb = new StringBuilder();
    sb.append("""
        오늘은 %s (%s) 이야.
        [지금 메모] 에 [새로 오간 말] 에서 알게 된 걸 접어 넣고, 갱신된 메모 전문을 돌려줘.
        다 합쳐 %d줄을 넘기지 마. 넘치면 곧 잊을 것부터 버려.

        [지금 메모]
        """.formatted(today, today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN), MEMO_LINES));
    sb.append(memo.isBlank() ? "(아직 없음)" : memo);
    sb.append("\n\n[새로 오간 말]\n");
    for (ChatHistory.Line line : pile)
      sb.append((line.mine ? "상대" : name) + (line.note ? "(비밀 쪽지): " : ": ")).append(ChatImage.spoken(line)).append("\n");
    return sb.toString();
  }

  /**
   * 시킨 대로 메모만 오면 좋겠지만 "알겠어!" 나 코드블록을 얹어 보낼 때가 있다.
   * 쓸 만한 줄만 남기고 줄 수도 여기서 한 번 더 자른다 — 프롬프트로만 막으면 언젠가 샌다.
   */
  private static String clean(String raw) {
    if (raw == null)
      return "";
    List<String> kept = new ArrayList<>();
    for (String line : raw.strip().split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("```"))
        continue;
      kept.add(trimmed);
      if (kept.size() >= MEMO_LINES)
        break;
    }
    return String.join("\n", kept);
  }

  /**
   * 말풍선을 처음부터 다시 그린다. 한 줄 늘 때마다 통째로 그리는 게 낭비 같아 보여도,
   * 날짜 구분선이며 "이 줄에 시각을 붙일지" 가 앞뒤 줄을 봐야 정해지는 것들이라
   * 한 줄만 끼워 넣는 길을 따로 두면 금세 어긋난다. 길어야 수백 줄이라 값도 싸다.
   */
  private void render() {
    render(true);
  }

  /**
   * 한 번에 그리는 말풍선 수. 수백 줄을 통째로 그리면 (말풍선 하나에 노드 열 몇 개 + 글자 배치)
   * 수다 화면을 열거나 상대를 갈아탈 때 한참 멈춘다. 끝에서 이만큼만 그리고, 맨 위로 올리면 더 그린다 (drawOlder)
   */
  private static final int DRAW_PAGE = 40;
  /** 끝에서 몇 줄(화면에 보이는 줄 기준)까지 그릴지. 이어 붙인 줄(append)만큼 같이 는다 */
  private int drawLimit = DRAW_PAGE;
  /** 지금 그려진 것보다 앞에 안 그린 줄이 남아 있는지 */
  private boolean olderLeft;

  private void render(boolean toBottom) {
    log.getChildren().clear();
    rows.clear();

    // 지운 줄은 마도카 맥락에만 남고 화면엔 안 그린다 (removeLine)
    List<ChatHistory.Line> shown = new ArrayList<>();
    for (ChatHistory.Line line : lines)
      if (!line.hidden)
        shown.add(line);

    int from = Math.max(0, shown.size() - drawLimit);
    olderLeft = from > 0;
    drawnDay = null;
    // 파스텔 무지개 차례가 그리는 범위에 따라 바뀌지 않게, 안 그린 앞쪽 상대 말도 센다
    theirsDrawn = 0;
    for (int i = 0; i < from; i++)
      if (!shown.get(i).mine)
        theirsDrawn++;
    lastDrawn = null;
    for (int i = from; i < shown.size(); i++) {
      ChatHistory.Line line = shown.get(i);
      LocalDateTime when = when(line);

      if (!when.toLocalDate().equals(drawnDay)) {
        drawnDay = when.toLocalDate();
        log.getChildren().add(dateMark(drawnDay));
      }
      HBox row = bubble(line, endsGroup(shown, i) ? when : null, theirsDrawn);
      rows.put(line, row);
      log.getChildren().add(row);
      if (!line.mine)
        theirsDrawn++;
      lastDrawn = line;
    }

    if (toBottom)
      scrollToBottom();
  }

  /** 맨 위까지 올렸을 때 — 앞 말풍선을 DRAW_PAGE 줄 더 그리고, 보던 줄이 제자리에 남게 스크롤을 맞춘다 */
  private void drawOlder() {
    if (!olderLeft)
      return;
    double before = log.getBoundsInLocal().getHeight();
    drawLimit += DRAW_PAGE;
    render(false);
    Platform.runLater(() -> {
      scroll.applyCss();
      scroll.layout();
      double content = log.getBoundsInLocal().getHeight();
      double view = scroll.getViewportBounds().getHeight();
      if (content > view)
        scroll.setVvalue(Math.max(0, Math.min(1, (content - before) / (content - view))));
    });
  }

  /** line 이 아직 안 그려졌으면 (오래돼서 앞쪽에 있으면) 그 줄까지 넓혀 다시 그린다. 지운 줄이면 그대로 */
  private void ensureDrawn(ChatHistory.Line line) {
    if (line == null || line.hidden || rows.containsKey(line))
      return;
    int fromEnd = 0;
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).hidden)
        continue;
      fromEnd++;
      if (lines.get(i) == line)
        break;
    }
    drawLimit = Math.max(drawLimit, fromEnd);
    render(false);
  }

  /** 맨 끝까지 그린 날짜 · 그린 줄 — 이어 그릴 때(append) 이어받는다. render 가 채운다 */
  private LocalDate drawnDay;
  private ChatHistory.Line lastDrawn;
  /** 상대 말풍선을 몇 개 그렸는지 — 파스텔 테마는 이 차례로 무지개 12색을 돌려 칠한다 (Theme.chatCycleClass) */
  private int theirsDrawn;

  /**
   * 맨 끝에 막 붙은 한 줄만 이어 그린다 (addLine). 말풍선 하나 붙을 때마다 render 로 통째로 그리면
   * 대화가 길수록 수백 줄 노드를 새로 만드느라 답이 붙는 게 굼떠진다.
   *
   * 앞뒤를 봐야 하는 건 둘뿐이다 — 날짜가 바뀌었으면 구분선을 넣고, 바로 앞 줄과 같은 사람이
   * 같은 분에 이어 한 말이면 앞 줄에 붙어 있던 시각을 뗀다 (endsGroup). 앞 줄은 그 자리에서 새로 그려 갈아끼운다.
   * 그려진 게 lines 와 어긋나 있으면 (앞 줄이 화면에 없는 등) 그냥 통째로 다시 그린다.
   * 지우기 · 고치기 · 반응 같은 나머지 손질은 지금처럼 render 몫이다.
   */
  private void append(ChatHistory.Line line) {
    if (line.hidden)
      return;
    ChatHistory.Line prev = null;
    for (int i = lines.size() - 2; i >= 0 && prev == null; i--)
      if (!lines.get(i).hidden)
        prev = lines.get(i);
    int prevAt = prev == null ? -1 : log.getChildren().indexOf(rows.get(prev));
    if (prev != lastDrawn || (prev != null && prevAt < 0)) {
      render();
      return;
    }

    LocalDateTime when = when(line);
    if (prev != null && !endsGroup(List.of(prev, line), 0)) {
      HBox again = bubble(prev, null, prev.mine ? theirsDrawn : theirsDrawn - 1);
      rows.put(prev, again);
      log.getChildren().set(prevAt, again);
    }
    if (!when.toLocalDate().equals(drawnDay)) {
      drawnDay = when.toLocalDate();
      log.getChildren().add(dateMark(drawnDay));
    }
    HBox row = bubble(line, when, theirsDrawn);
    rows.put(line, row);
    log.getChildren().add(row);
    if (!line.mine)
      theirsDrawn++;
    lastDrawn = line;
    // 다시 그릴 때 (render) 이어 붙인 줄 때문에 앞쪽이 잘려 나가지 않게
    drawLimit++;

    scrollToBottom();
  }

  /**
   * 시각을 맞추기 전에 남은 호감도 기록은 답을 받은 때라, 첫 말풍선이 이만큼 안에 뒤따라 붙었을 거다 (lineOf).
   * 뜸 들이는 건 말풍선 사이에만 있어서 첫 말풍선은 거의 바로 붙는다
   */
  private static final long LEGACY_MATCH_MS = 60_000;

  /** 호감도 기록을 두 번 누르면 — 그 기록을 부른 말풍선으로 가서 깜빡인다. 못 찾으면 (지웠거나 접혔으면) false */
  private boolean jumpTo(Affection.Change change) {
    ChatHistory.Line line = lineOf(lines, change);
    ensureDrawn(line);
    Node row = line == null ? null : rows.get(line);
    if (row == null)
      return false;
    scrollTo(scroll, row);
    return true;
  }

  /**
   * 호감도 기록 change 를 부른 상대 말 — 시각(at)이 똑같은 줄. 없으면 시각을 맞추기 전 기록이라 치고
   * 그 뒤 LEGACY_MATCH_MS 안의 첫 상대 말. 그 말을 지웠으면 (hidden) null. 지난 대화 창도 같이 쓴다
   */
  static ChatHistory.Line lineOf(List<ChatHistory.Line> lines, Affection.Change change) {
    ChatHistory.Line near = null;
    for (ChatHistory.Line line : lines) {
      if (line.mine)
        continue;
      if (line.at == change.at)
        return line.hidden ? null : line;
      if (near == null && !line.hidden && line.at > change.at && line.at - change.at <= LEGACY_MATCH_MS)
        near = line;
    }
    return near;
  }

  /** scroll 안의 row 가 가운데쯤 보이게 굴리고 몇 번 깜빡여 어느 말인지 알려준다. 지난 대화 창도 같이 쓴다 */
  static void scrollTo(ScrollPane scroll, Node row) {
    // 막 그린 줄은 아직 자리가 안 잡혀 있어서 배치를 먼저 돌린다 (scrollToBottom 과 같은 이치)
    Platform.runLater(() -> {
      scroll.applyCss();
      scroll.layout();
      double content = scroll.getContent().getBoundsInLocal().getHeight();
      double view = scroll.getViewportBounds().getHeight();
      double top = row.getBoundsInParent().getMinY() - (view - row.getBoundsInParent().getHeight()) / 2;
      scroll.setVvalue(content <= view ? 0 : Math.max(0, Math.min(1, top / (content - view))));

      FadeTransition blink = new FadeTransition(Duration.millis(220), row);
      blink.setFromValue(1);
      blink.setToValue(0.25);
      blink.setCycleCount(4);
      blink.setAutoReverse(true);
      blink.play();
    });
  }

  /**
   * 이 줄에 시각을 붙일지. 같은 사람이 같은 분에 잇따라 한 말은 맨 끝 줄에만 붙인다 —
   * 카카오톡이 하는 대로, 줄마다 시각이 붙어 어지러워지지 않게.
   */
  private boolean endsGroup(List<ChatHistory.Line> shown, int index) {
    if (index == shown.size() - 1)
      return true;

    ChatHistory.Line line = shown.get(index);
    ChatHistory.Line next = shown.get(index + 1);
    if (line.mine != next.mine)
      return true;
    return !when(line).truncatedTo(ChronoUnit.MINUTES)
        .equals(when(next).truncatedTo(ChronoUnit.MINUTES));
  }

  private LocalDateTime when(ChatHistory.Line line) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(line.at), ZoneId.systemDefault());
  }

  /**
   * 말풍선 한 줄. 내 말이면 오른쪽, 마도카 말이면 왼쪽에 붙인다.
   * 시각(at)은 말풍선 안쪽 — 내 말은 왼쪽, 마도카 말은 오른쪽 — 에 아래를 맞춰 작게 붙는다.
   * null 이면 시각 없이 말풍선만 (같은 분에 이어지는 줄, 그리고 기다림 표시).
   */
  private HBox bubble(ChatHistory.Line line, LocalDateTime at, int theirsIndex) {
    boolean mine = line.mine;
    Region picture = line.image == null ? null : ChatImage.bubble(line);
    Region label;
    if (picture != null) {
      label = picture;
    } else if (line.note) {
      // 접힌 쪽지 — 열면 열어봤다고 남기고 다시 그린다
      label = SecretNote.bubble(line, name(), noteSingleClick, () -> {
        store.save(lines);
        render();
      });
    } else {
      // 주소가 섞이면 그 부분만 눌러서 열 수 있게 (BubbleText)
      label = BubbleText.of(line.text);
      label.getStyleClass().addAll("chat-bubble", mine ? "chat-mine" : "chat-theirs");
      if (!mine)
        label.getStyleClass().add(Theme.chatCycleClass(theirsIndex));
    }
    // 창 폭이 변해도 말풍선이 한쪽을 다 먹지 않게
    label.maxWidthProperty().bind(scroll.widthProperty().multiply(0.7));
    Label chip = reactionChip(line);

    // 반응 단추 — 말풍선 끝(마지막 글자 아래쯤) 귀퉁이에 살짝 걸쳐 단다. 컬러 그림이 아닌 흑백 글자 이모지
    Button react = new Button("🙂");
    react.getStyleClass().add("chat-react-button");
    react.setFocusTraversable(false);
    // 반응 칩과 같은 쪽 — 상대 말은 오른쪽 귀퉁이, 내 말은 왼쪽 귀퉁이
    StackPane.setAlignment(react, mine ? Pos.BOTTOM_LEFT : Pos.BOTTOM_RIGHT);
    react.setTranslateX(mine ? -REACT_OVERLAP_X : REACT_OVERLAP_X);
    react.setTranslateY(REACT_OVERLAP_Y);
    // 내 말엔 반응을 못 단다 — 단추째 빼둔다
    StackPane bubbleBox = mine ? new StackPane(label) : new StackPane(label, react);
    bubbleBox.maxWidthProperty().bind(label.maxWidthProperty());
    Region body = withReaction(withQuote(bubbleBox, line, name(), () -> jumpToOrigin(replyOrigin(lines, line))), chip, heartChip(line), name(), mine);

    HBox row = new HBox(4);
    // 여러 줄짜리 말풍선 옆에서 시각이 가운데 뜨지 않게, 아래를 맞춘다
    row.setAlignment(mine ? Pos.BOTTOM_RIGHT : Pos.BOTTOM_LEFT);
    swipeToReply(label, row, line);

    // 말풍선(과 걸친 단추) 위에 마우스가 있을 때만, 반응을 아직 안 달았을 때만 보인다 — 단 뒤엔 반응 칩을 누르면 된다
    react.visibleProperty().bind(bubbleBox.hoverProperty().and(chip.visibleProperty().not()));
    // 단추가 말풍선 밑으로 삐져나와 아랫줄에 깔리지 않게, 올린 줄은 위로 끌어올려 그린다
    bubbleBox.hoverProperty().addListener((obs, was, is) -> row.setViewOrder(is ? -1 : 0));
    // 고르기 판은 늘 보이는 말풍선에 붙인다 — 단추에 붙이면 판이 뜨며 호버가 풀려 단추가 숨는 순간
    // 판도 주인을 잃고 같이 닫혀버린다. 자리만 누른 것(단추 · 칩) 바로 오른쪽으로
    Consumer<Node> pickReaction = beside -> EmojiPicker.quick(label, beside, line.reaction, emoji -> react(line, emoji, chip));
    react.setOnAction(e -> pickReaction.accept(react));
    // 단 반응을 누르면 바꾸거나 뗄 수 있게 다시 고르기
    chip.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY && !mine)
        pickReaction.accept(chip);
    });
    label.setOnContextMenuRequested(e -> {
      bubbleMenu(line, label, chip).show(label, e.getScreenX(), e.getScreenY());
      e.consume();
    });

    Label time = null;
    // 고친 말엔 시각 앞에 (시각이 안 붙는 줄이면 홀로) "수정됨" 을 붙인다
    if (at != null || line.edited) {
      String stamp = at == null ? "" : TIME.format(at);
      time = new Label(line.edited ? ("수정됨 " + stamp).strip() : stamp);
      time.getStyleClass().add("chat-time");
    }
    if (mine) {
      if (time != null)
        row.getChildren().add(time);
      row.getChildren().add(body);
    } else {
      row.getChildren().add(body);
      if (time != null)
        row.getChildren().add(time);
    }
    return row;
  }

  /** 반응 단추가 말풍선 아래 귀퉁이(내 말은 왼쪽, 상대 말은 오른쪽)에서 얼마나 비켜 앉는지 — 안쪽으로 조금, 아래로 반쯤 걸치게 */
  private static final double REACT_OVERLAP_X = -4;
  private static final double REACT_OVERLAP_Y = 12;

  /** 오른쪽 클릭 메뉴 맨 위에 늘어놓을 반응 개수 */
  private static final int MENU_REACTIONS = 4;

  /** 내가 가장 자주 보낸 · 단 이모지 MENU_REACTIONS 개. 모자라면 EmojiPicker.QUICK 차례로 채운다 */
  private List<String> favoriteReactions() {
    List<String> out = new ArrayList<>();
    for (String emoji : usedEmojis()) {
      if (out.size() == MENU_REACTIONS)
        break;
      out.add(emoji);
    }
    for (String emoji : EmojiPicker.QUICK) {
      if (out.size() == MENU_REACTIONS)
        break;
      if (!out.contains(reactionKey(emoji)))
        out.add(reactionKey(emoji));
    }
    return out;
  }

  /**
   * 지금까지 쓴 이모지 전부, 많이 쓴 차례로. 보낸 말 속 이모지와 단 반응을 같이 센다.
   * 오른쪽 클릭 메뉴와 이모지 판의 "많이 쓴" 분류가 같이 쓴다
   */
  private List<String> usedEmojis() {
    Map<String, Integer> counts = new HashMap<>();
    for (ChatHistory.Line line : lines) {
      if (line.reaction != null)
        counts.merge(reactionKey(line.reaction), 1, Integer::sum);
      if (!line.mine || line.note || line.text == null)
        continue;
      for (Emoji.Piece piece : Emoji.split(line.text))
        if (piece.image() != null)
          counts.merge(reactionKey(piece.text()), 1, Integer::sum);
    }
    List<String> top = new ArrayList<>(counts.keySet());
    top.sort((a, b) -> counts.get(b) - counts.get(a));
    return top;
  }

  /** ❤ 와 ❤️ 를 같은 걸로 세게 — 이모지로 보여라 표시(FE0F)를 떼고 센다. 보일 땐 EmojiPicker 가 다시 붙인다 */
  private static String reactionKey(String emoji) {
    return emoji.replace("️", "");
  }

  /** 말에 반응을 단다. 같은 걸 또 고르면 뗀다 */
  private void react(ChatHistory.Line line, String emoji, Label chip) {
    line.reaction = emoji.equals(line.reaction) ? null : emoji;
    store.save(lines);
    paintReaction(chip, line.reaction);
  }

  /** 말풍선 아래 귀퉁이에 붙는 반응 이모지. 반응이 없으면 숨어 있다. 지난 대화 창도 같이 쓴다 */
  static Label reactionChip(ChatHistory.Line line) {
    Label chip = new Label();
    chip.getStyleClass().add("chat-reaction");
    paintReaction(chip, line.reaction);
    return chip;
  }

  private static void paintReaction(Label chip, String reaction) {
    ImageView icon = reaction == null ? null : Emoji.view(reaction, 14);
    chip.setGraphic(icon);
    chip.setText(icon == null ? reaction : null);
    chip.setVisible(reaction != null);
    chip.setManaged(reaction != null);
  }

  /** 마도카가 이 말에 단 하트. 안 달았으면 숨어 있다. 지난 대화 창도 같이 쓴다 */
  static Label heartChip(ChatHistory.Line line) {
    Label chip = new Label();
    chip.getStyleClass().addAll("chat-reaction", "chat-heart");
    paintReaction(chip, line.heart ? "❤️" : null);
    return chip;
  }

  /**
   * 말풍선 밑에 반응을 살짝 겹쳐 단다 — 상대 말은 오른쪽 귀퉁이, 내 말은 왼쪽 귀퉁이. 지난 대화 창도 같이 쓴다.
   * heart 는 마도카가 단 하트 (heartChip) — 내 반응 옆에 나란히 붙는다. 칩 위에 1초 머물면 누가 달았는지 뜬다
   */
  static Region withReaction(Region body, Label chip, Label heart, String name, boolean mine) {
    whoReacted(chip, "내 반응");
    whoReacted(heart, name + "의 반응");
    HBox chips = new HBox(2, heart, chip);
    // 둘 다 없으면 칸째 빠져야 말풍선 아래가 겹침(-6)만큼 잘리지 않는다
    chips.visibleProperty().bind(chip.visibleProperty().or(heart.visibleProperty()));
    chips.managedProperty().bind(chips.visibleProperty());
    // VBox 가 칸을 폭 가득 늘리면 칩이 칸 안에서 왼쪽에 붙어버린다 — 제 폭만 차지해야 box 정렬을 따른다
    chips.setMaxWidth(Region.USE_PREF_SIZE);
    VBox box = new VBox(-6, body, chips);
    box.setAlignment(mine ? Pos.BOTTOM_LEFT : Pos.BOTTOM_RIGHT);
    VBox.setMargin(chips, mine ? new Insets(0, 0, 0, 8) : new Insets(0, 8, 0, 0));
    return box;
  }

  /** 반응 칩에 마우스를 1초 대고 있으면 단 사람 이름을 띄운다 */
  private static void whoReacted(Label chip, String text) {
    // 말풍선마다 칩이 둘씩이라 Tooltip 을 미리 다 만들면 그리기가 굼떠진다 — 처음 마우스가 올라올 때 단다
    chip.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
      if (chip.getTooltip() != null)
        return;
      Tooltip tip = new Tooltip(text);
      tip.setShowDelay(Duration.seconds(1));
      chip.setTooltip(tip);
    });
  }

  /**
   * 답장한 말이면 말풍선 위에 무슨 말에 답했는지 작게 얹는다. 그 인용을 누르면 jump — 원래 말로 찾아간다.
   * 지난 대화 창도 같이 쓴다
   */
  static Region withQuote(Region bubble, ChatHistory.Line line, String name, Runnable jump) {
    if (line.replyText == null)
      return bubble;
    Label quote = new Label("↪ " + (line.replyMine ? "나" : name) + ": " + preview(line.replyText, line.replyNote));
    quote.getStyleClass().add("chat-reply-quote");
    quote.maxWidthProperty().bind(bubble.maxWidthProperty());
    quote.setCursor(Cursor.HAND);
    quote.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY)
        return;
      jump.run();
      e.consume();
    });
    VBox box = new VBox(2, quote, bubble);
    box.setAlignment(line.mine ? Pos.BOTTOM_RIGHT : Pos.BOTTOM_LEFT);
    return box;
  }

  // ── 말풍선 손질 (복사 · 답장 · 지우기) ──

  /** 말풍선 오른쪽 클릭 메뉴. 맨 위엔 자주 쓰는 반응 줄 (컬러) — 끝의 ⋯ 로 다른 이모지를 고른다 */
  private ContextMenu bubbleMenu(ChatHistory.Line line, Region bubble, Label chip) {
    ContextMenu menu = new ContextMenu();
    menu.getStyleClass().add("chat-menu");
    HBox reactions = EmojiPicker.row(favoriteReactions(), line.reaction, 16, emoji -> {
      menu.hide();
      react(line, emoji, chip);
    }, () -> {
      menu.hide();
      EmojiPicker.show(bubble, true, emoji -> react(line, emoji, chip));
    });
    CustomMenuItem react = new CustomMenuItem(reactions, false);
    react.getStyleClass().add("chat-react-menu");

    MenuItem copy = new MenuItem("복사");
    copy.setOnAction(e -> {
      ClipboardContent content = new ClipboardContent();
      if (line.image != null && new File(line.image).exists())
        content.putImage(new Image(new File(line.image).toURI().toString()));
      else
        content.putString(line.text);
      Clipboard.getSystemClipboard().setContent(content);
    });
    MenuItem reply = new MenuItem("답장");
    reply.setOnAction(e -> startReply(line));
    MenuItem edit = new MenuItem("수정");
    edit.setOnAction(e -> startEdit(line));
    MenuItem remove = new MenuItem("지우기");
    remove.setOnAction(e -> removeLine(line));
    MenuItem refetch = new MenuItem("사진 다시 가져오기");
    refetch.setOnAction(e -> refetchPicture(line));
    // 반응 줄은 상대 말에만 — 내 말엔 반응을 못 단다
    if (!line.mine)
      menu.getItems().addAll(react, new SeparatorMenuItem());
    menu.getItems().addAll(copy, reply);
    // 파일이 없어진 사진은 글 말풍선으로 그려지니 열 것도 없다
    if (line.image != null && new File(line.image).exists())
      menu.getItems().add(ChatImage.originalItem(new File(line.image)));
    // 고칠 수 있는 건 내가 한 보통 말뿐이다 — 쪽지는 접힌 채라 고칠 글이 안 보이고, 사진엔 고칠 글이 없다
    if (line.mine && !line.note && line.image == null)
      menu.getItems().add(edit);
    // 상대가 찾아 보낸 사진만 — 내가 올린 사진은 다시 가져올 데가 없다
    if (!line.mine && line.image != null)
      menu.getItems().add(refetch);
    menu.getItems().add(remove);
    return menu;
  }

  /** 다시 가져오는 중인지. 메뉴를 연달아 눌러도 한 번만 찾게 */
  private boolean refetching;

  /**
   * 상대가 보낸 사진을 같은 조건으로 찾은 다른 사진으로 갈아끼운다. LLM 을 안 불러서 토큰이 안 들고, 설명 글은 그대로 둔다.
   * 찾는 사이에 대화를 지웠거나 그 줄이 없어졌으면 받은 사진을 버린다.
   */
  private void refetchPicture(ChatHistory.Line line) {
    if (refetching)
      return;
    refetching = true;
    String dir = profile.path(ChatImage.DIR);
    int startedIn = era;
    Task<String> task = new Task<>() {
      @Override
      protected String call() throws Exception {
        return WebPicture.refetch(line.pictureSearch, profile, dir, sentPictures);
      }
    };
    task.setOnSucceeded(e -> {
      refetching = false;
      String path = task.getValue();
      if (path == null) {
        Toast.show("다른 사진을 못 찾았어");
        return;
      }
      if (startedIn != era || !lines.contains(line)) {
        ChatImage.delete(path);
        return;
      }
      String old = line.image;
      line.image = path;
      store.save(lines);
      ChatImage.delete(old);
      render();
    });
    task.setOnFailed(e -> {
      refetching = false;
      System.err.println("[chat] 사진 다시 가져오기 실패: " + task.getException());
      Toast.show("다른 사진을 못 찾았어");
    });
    Thread worker = new Thread(task, "chat-image-refetch");
    worker.setDaemon(true);
    worker.start();
  }

  /**
   * 내 말을 고치기 시작한다. 글을 입력칸에 옮겨두고 입력칸 위에 무슨 말을 고치는지 띄운다.
   * 걸어둔 답장은 접는다 — 고치는 동안 Enter 는 보내기가 아니라 고쳐 쓰기라서.
   */
  private void startEdit(ChatHistory.Line line) {
    replyTo = null;
    editing = line;
    replyLabel.setText("✎ 수정: " + preview(line.text, false));
    showReplyBar(true);
    input.setText(line.text);
    input.positionCaret(line.text.length());
    input.requestFocus();
  }

  /**
   * 입력칸 글로 고치는 중인 말을 고쳐 쓴다. 보낼 때와 달리 \ 로 끊지 않고 친 그대로 한 말풍선이다.
   * 마도카를 다시 부르진 않는다 — 고친 글은 다음 대화부터 맥락에 실린다.
   * 이 말에 단 답장들의 인용도 같이 고친다 — 그래야 인용을 눌렀을 때 원래 말을 계속 찾아간다 (replyOrigin).
   */
  private void finishEdit() {
    ChatHistory.Line line = editing;
    String text = input.getText().strip();
    if (text.isEmpty())
      return;
    if (!text.equals(line.text)) {
      for (ChatHistory.Line other : lines)
        if (other.replyText != null && other.replyMine && other.replyAt == line.at && other.replyText.equals(line.text))
          other.replyText = text;
      line.text = text;
      line.edited = true;
      store.save(lines);
      render();
    }
    cancelReply();
  }

  /**
   * 화면에서만 지운다. 줄은 lines 에 숨긴 채로 남아서 마도카한테는 그대로 들려주고 (window),
   * 메모 접기 · 뜸한 시간 재기도 그대로 그 줄을 본다 — 지운다고 대화 맥락이 바뀌지 않게.
   */
  private void removeLine(ChatHistory.Line line) {
    line.hidden = true;
    if (replyTo == line || editing == line)
      cancelReply();
    store.save(lines);
    render();
    side.refreshDrawer();
  }

  /**
   * 말풍선을 왼쪽으로 밀어 답장 걸기 — 마도카 말이든 내 말이든 같은 쪽으로.
   * SWIPE_REPLY 만큼 밀고 놓으면 답장이 걸리고, 말풍선은 제자리로 튕겨 돌아간다.
   * 민 다음의 클릭은 삼킨다 — 쪽지나 주소가 덩달아 열리지 않게.
   */
  private void swipeToReply(Region bubble, Node row, ChatHistory.Line line) {
    double[] from = new double[1];
    boolean[] moved = new boolean[1];
    bubble.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
      from[0] = e.getSceneX();
      moved[0] = false;
    });
    bubble.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
      if (!e.isPrimaryButtonDown())
        return;
      double dx = e.getSceneX() - from[0];
      double pull = Math.max(0, Math.min(SWIPE_MAX, -dx));
      if (pull > 4)
        moved[0] = true;
      row.setTranslateX(-pull);
    });
    bubble.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
      double pulled = Math.abs(row.getTranslateX());
      if (pulled == 0)
        return;
      TranslateTransition back = new TranslateTransition(Duration.millis(150), row);
      back.setToX(0);
      back.play();
      if (pulled >= SWIPE_REPLY)
        startReply(line);
    });
    bubble.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
      if (moved[0])
        e.consume();
    });
  }

  /** 다음에 보낼 말을 이 말에 대한 답장으로 건다. 입력칸 위에 무슨 말인지 띄워둔다 */
  private void startReply(ChatHistory.Line line) {
    if (editing != null)
      cancelReply();
    replyTo = line;
    replyLabel.setText("↪ " + (line.mine ? "나" : name()) + "한테 답장: " + preview(line.text, line.note));
    showReplyBar(true);
    input.requestFocus();
  }

  /** 답장한 말 · 걸어둔 답장의 원래 말로 가서 깜빡인다. 지웠거나 오래돼서 잘려 나갔으면 가만히 있는다 */
  private void jumpToOrigin(ChatHistory.Line line) {
    ensureDrawn(line);
    Node row = line == null ? null : rows.get(line);
    if (row != null)
      scrollTo(scroll, row);
  }

  /** 답장 줄 line 이 답한 원래 말. 지웠거나 (hidden) 잘려 나갔으면 null. 지난 대화 창도 같이 쓴다 */
  static ChatHistory.Line replyOrigin(List<ChatHistory.Line> lines, ChatHistory.Line line) {
    for (int i = lines.size() - 1; i >= 0; i--) {
      ChatHistory.Line at = lines.get(i);
      if (at.at == line.replyAt && at.mine == line.replyMine && at.text.equals(line.replyText))
        return at.hidden ? null : at;
    }
    return null;
  }

  /** 걸어둔 답장 · 고치던 말을 접는다. 고치던 중이면 입력칸에 옮겨둔 글도 비운다 */
  private void cancelReply() {
    replyTo = null;
    if (editing != null) {
      editing = null;
      input.clear();
    }
    showReplyBar(false);
  }

  private void showReplyBar(boolean show) {
    replyBar.setVisible(show);
    replyBar.setManaged(show);
  }

  /** 날짜가 바뀌는 자리에 끼우는 구분선 */
  private HBox dateMark(LocalDate day) {
    Label label = new Label(DATE.format(day));
    label.getStyleClass().add("chat-date");
    HBox row = new HBox(label);
    row.setAlignment(Pos.CENTER);
    row.setPadding(new Insets(6, 0, 2, 0));
    return row;
  }

  /**
   * 맨 아래로 내린다. 마도카 말이 한 줄 붙을 때마다 여기로 온다.
   *
   * 방금 넣은 말풍선은 아직 제 높이가 안 잡혀 있다. 그 자리에서 vvalue 만 올려두면
   * 늘어나기 전 높이를 기준으로 끝까지 간 셈이 돼서, 배치가 돌고 나면 새로 붙은
   * 말풍선만큼이 아래에 남는다 — 긴 답이 올수록 더 많이 남아서, 정작 읽어야 할
   * 마지막 말이 화면 밖으로 밀린다.
   *
   * 그래서 한 박자 재운 뒤(runLater) 그 자리에서 배치를 강제로 돌려(applyCss/layout)
   * 늘어난 높이를 먼저 확정하고, 그다음에 내린다.
   */
  private void scrollToBottom() {
    Platform.runLater(() -> {
      scroll.applyCss();
      scroll.layout();
      scroll.setVvalue(1.0);
    });
  }
}
