package kyubey.index;

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
   * 뿌리는 설정 창(SettingsDialog)에서 고른 것 — SettingsDialog.scanRoots.
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
            Path name = path.getFileName();
            // 뿌리 밑으로만 본다 — 뿌리 자체가 C:\GitHub 같은 이름이면 통째로 걸러져서.
            // 폴더 이름만 본다 — 경로 어디에든 "git" 이 들면 걸러내던 땐 digital · legit 같은 폴더까지 빠졌다
            if (!path.equals(start) && name != null && skipped(name.toString()))
              return FileVisitResult.SKIP_SUBTREE;
            // 드라이브 통째(C:\)를 골랐으면 이름이 없다
            if (name != null)
              folderMap.computeIfAbsent(name.toString(), k -> new ArrayList<>()).add(fullPath);
            // src 가 있는 폴더는 프로젝트로 보고 그 폴더만 적고 안쪽은 안 훑는다 (뿌리 자체는 예외)
            if (!path.equals(start) && Files.isDirectory(path.resolve("src")))
              return FileVisitResult.SKIP_SUBTREE;
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

  /** 안 훑고 넘어갈 폴더 — node_modules, .git · .github 같은 git 폴더, godot 이 든 폴더 */
  private static boolean skipped(String name) {
    return name.contains("node_modules") || name.startsWith(".git") || name.contains("godot");
  }
}
