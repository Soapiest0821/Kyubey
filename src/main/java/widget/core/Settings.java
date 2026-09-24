package widget.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 사람마다 다른 설정 — 지금은 "폴더를 훑을 곳" 하나뿐이다.
 *
 * 예전엔 코드에 내 폴더가 박혀 있었는데, 저장소에 올리면서 처음 켤 때 고르게 바꿨다
 * (SetupDialog). 파일이 아예 없으면 아직 설정을 안 한 것이고, 그때 설정 창이 뜬다.
 * "나중에" 로 넘겨도 빈 목록으로 저장해서 다음부턴 안 뜬다 — 다시 부르는 건 "setup".
 */
public final class Settings {

  private static final String PATH = "src/main/resources/json/settings.json";

  /** 폴더 이름으로 찾아 열 수 있게 훑어둘 뿌리 폴더들 */
  public List<String> scanRoots = new ArrayList<>();

  private static final ObjectMapper mapper = new ObjectMapper();

  /** 설정을 한 번이라도 저장했나. 안 했으면 처음 켠 것 */
  public static boolean exists() {
    return new File(PATH).isFile();
  }

  public static Settings load() {
    File file = new File(PATH);
    if (file.isFile()) {
      try {
        Settings s = mapper.readValue(file, Settings.class);
        if (s.scanRoots == null)
          s.scanRoots = new ArrayList<>();
        return s;
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return new Settings();
  }

  public void save() throws IOException {
    File file = new File(PATH);
    file.getParentFile().mkdirs();
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, this);
  }
}
