package widget.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 키마다 마지막으로 던져본 결과 — keys 창에서 🟢🟡🔴 로 보여준다.
 *
 * WIZ_ 번호가 아니라 키 값에 붙여서 기억한다. keys 창에서 중간 키를 지우면 번호가
 * 당겨지는데, 번호에 붙여두면 남의 상태가 딴 키 옆에 뜨게 된다.
 * 파일엔 키 값 그대로 말고 해시 앞자리만 적는다 — 키가 .env 말고 딴 데 또 적히지 않게.
 *
 * 키 탓이 아닌 실패(모델 이름 틀림, 요청 형식, 인터넷 끊김)는 적지 않는다.
 * 그건 키를 바꿔 끼워도 똑같아서, 키 옆에 빨간불을 켜면 괜히 멀쩡한 키를 지우게 된다.
 */
public final class KeyHealth {

  private static final String PATH = "src/main/resources/json/keys.json";

  public enum State {
    /** 마지막에 답을 받았다 */
    OK("🟢"),
    /** 한도가 찼거나 구글 쪽이 잠깐 아팠다 — 기다리면 돌아온다 */
    BUSY("🟡"),
    /** 키가 틀렸거나 막혔다 — 기다려도 안 돌아온다 */
    DEAD("🔴");

    private final String light;

    State(String light) {
      this.light = light;
    }

    public String light() {
      return light;
    }
  }

  /** 한 키의 마지막 결과. reason 은 GeminiClient 가 쓰는 한글 키워드 ("한도 초과") */
  public record Status(State state, String reason, long at) {
  }

  private static final ObjectMapper mapper = new ObjectMapper();
  private static Map<String, Status> byHash;

  private KeyHealth() {
  }

  /** 이 키로 답을 받았다 */
  static void ok(String key) {
    put(key, new Status(State.OK, "성공", System.currentTimeMillis()));
  }

  /** 이 키가 이 이유로 실패했다. 키 탓이 아닌 이유면 적지 않고 넘어간다 */
  static void failed(String key, String reason) {
    State state = switch (reason) {
      case "한도 초과", "서버 오류", "응답 없음" -> State.BUSY;
      case "키 무효", "인증 실패", "권한 없음" -> State.DEAD;
      default -> null;
    };
    if (state != null)
      put(key, new Status(state, reason, System.currentTimeMillis()));
  }

  /** 마지막 결과. 이 키로 아직 한 번도 안 던져봤으면 null */
  public static synchronized Status of(String key) {
    return load().get(hash(key));
  }

  private static synchronized void put(String key, Status status) {
    load().put(hash(key), status);
    try {
      File file = new File(PATH);
      if (file.getParentFile() != null)
        file.getParentFile().mkdirs();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, byHash);
    } catch (IOException e) {
      // 불 색깔 하나 못 남긴다고 답까지 버릴 순 없다
      e.printStackTrace();
    }
  }

  private static Map<String, Status> load() {
    if (byHash != null)
      return byHash;
    byHash = new HashMap<>();
    File file = new File(PATH);
    if (file.exists()) {
      try {
        byHash.putAll(mapper.readValue(file, new TypeReference<Map<String, Status>>() {
        }));
      } catch (IOException e) {
        // 깨졌으면 다들 "안 써봄" 으로 시작한다
        e.printStackTrace();
      }
    }
    return byHash;
  }

  private static String hash(String key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
