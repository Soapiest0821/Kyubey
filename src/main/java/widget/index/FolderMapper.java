package widget.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class FolderMapper {

  /** 저장할 JSON 파일 경로 */
  public static final String OUTPUT_JSON_PATH = "src/main/resources/json/dirs.json";

  /**
   * 고른 뿌리 폴더들 밑의 폴더를 전부 훑어서 "폴더 이름 → 경로들" 로 dirs.json 에 적는다.
   * 뿌리는 처음 설정(SetupDialog)에서 고른 것 — Settings.scanRoots.
   *
   * @return 찾은 폴더 이름 수
   */
  public static int Run(List<String> roots) {
    Map<String, List<String>> folderMap = new HashMap<>();

    for (String root : roots) {
      Path start = Paths.get(root);
      if (!Files.isDirectory(start))
        continue;
      try {
        // Files.walk 는 못 여는 폴더(권한 없음) 하나에 걸리면 통째로 멈춰서, 그런 곳은 건너뛰며 돈다
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
            String fullPath = path.toAbsolutePath().toString();
            // 뿌리 밑으로만 본다 — 뿌리 자체가 C:\GitHub 같은 이름이면 통째로 걸러져서
            if (start.relativize(path).toString().matches(".*(node_modules|git|godot).*"))
              return FileVisitResult.SKIP_SUBTREE;
            Path name = path.getFileName();
            // 드라이브 통째(C:\)를 골랐으면 이름이 없다
            if (name != null)
              folderMap.computeIfAbsent(name.toString(), k -> new ArrayList<>()).add(fullPath);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    try {
      ObjectMapper mapper = new ObjectMapper();
      // 예쁘게 출력(Pretty Print)하기 위한 설정
      mapper.enable(SerializationFeature.INDENT_OUTPUT);
      File out = new File(OUTPUT_JSON_PATH);
      out.getParentFile().mkdirs();
      mapper.writeValue(out, folderMap);
      System.out.println("JSON 저장 완료: " + out.getAbsolutePath());
    } catch (IOException e) {
      e.printStackTrace();
    }
    return folderMap.size();
  }
}
