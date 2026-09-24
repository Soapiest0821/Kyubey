package widget.ui;

import widget.core.MacroManager;
import widget.index.AppMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "new" 치면 뜨는 등록 창 — 단축 키워드 하나에 파일/사이트/명령어 중 하나를 묶어준다.
 * "edit 키워드" 로 들어오면 같은 창이 이미 등록해둔 내용으로 채워진 채 뜬다.
 * 아직 등록 안 했지만 컴퓨터에 깔려 있는 앱(installed.json)도 "edit Chatgpt" 로 데려올 수 있다 —
 * 경로가 채워진 채 뜨니까 짧은 키워드랑 별칭만 달아주면 그대로 등록된다.
 */
public class AppRegisterDialog {

  private static final String APPS_JSON_PATH = "src/main/resources/json/apps.json";
  private static final String SITES_JSON_PATH = "src/main/resources/json/sites.json";
  private static final String BOOKMARKS_JSON_PATH = "src/main/resources/json/bookmarks.json";
  private static final String CMDS_JSON_PATH = "src/main/resources/json/cmds.json";
  private static final String ALIASES_JSON_PATH = "src/main/resources/json/aliases.json";
  private static final String INSTALLED_JSON_PATH = "src/main/resources/json/installed.json";

  // aliases.json 의 type 값 — Resolver 가 이 문자열로 별칭을 갈라본다
  private static final String TYPE_APP = "app";
  private static final String TYPE_SITE = "site";
  private static final String TYPE_CMD = "cmd";

  // 파일을 창 위로 끌고 왔을 때 잠깐 입는 테두리
  private static final String DROP_ACTIVE = "dialog-root-drop";

  /**
   * 수정 모드로 창을 열 때, 원래 등록돼 있던 내용 한 벌.
   * search 는 사이트 검색 주소 — 창에서는 안 건드리지만 이름을 바꿔도 따라가야 해서 들고 다닌다.
   * installed 는 "등록한 적은 없고 그냥 깔려 있는 앱(installed.json)에서 데려왔다"는 표시 —
   * 고치는 게 아니라 이 자리에서 처음 등록되는 거라 창 문구와 뒷정리가 조금 달라진다.
   */
  private record Existing(String keyword, String type, String value, String dir, String search,
      List<String> aliases, boolean installed) {
  }

  public static void show(Stage owner, MacroManager macro) {
    open(owner, macro, null, null);
  }

  /**
   * 메인 입력창에 친 글자를 단축 키워드 칸에 미리 채운 채로 여는 길.
   * 아무것도 못 찾아서 "새로 등록하기" 로 내려온 참이라, 찾던 그 이름이 곧 붙여줄 키워드다.
   */
  public static void show(Stage owner, MacroManager macro, String keyword) {
    open(owner, macro, null, keyword);
  }

  /**
   * "edit 키워드" — 등록해둔 것뿐 아니라 그냥 깔려 있는 앱(installed.json)도 이 길로 잡힌다.
   * 어느 쪽에도 없는 이름이면 창을 띄우지 않고 알려만 준다.
   */
  public static void edit(Stage owner, MacroManager macro, String keyword) {
    Existing existing;
    try {
      existing = findExisting(keyword);
    } catch (IOException e) {
      e.printStackTrace();
      showAlert(owner, "등록해둔 걸 읽다가 오류가 났어 ㅠㅠ: " + e.getMessage());
      return;
    }
    if (existing == null) {
      showAlert(owner, keyword + " 는 등록된 적도 없고, 깔려 있지도 않은 것 같아!\n\nnew 로 새로 만들 수 있어.");
      return;
    }
    open(owner, macro, existing, null);
  }

  /** prefill 은 새로 등록할 때 단축 키워드 칸에 미리 채워둘 글자 (없으면 null) */
  private static void open(Stage owner, MacroManager macro, Existing existing, String prefill) {
    boolean editing = existing != null;

    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    // 깔려 있기만 한 앱을 데려온 거면 "고치기" 가 아니라 이 자리에서 처음 등록하는 셈이다
    boolean adopting = editing && existing.installed();

    Label heading = label(!editing ? "새로 등록"
        : existing.keyword() + (adopting ? " 등록하기" : " 고치기"));

    Label adoptHint = new Label("깔려만 있던 앱이야 — 별칭을 달아두면 짧은 이름으로도 부를 수 있어!");
    adoptHint.getStyleClass().add("dialog-path-label");
    adoptHint.setWrapText(true);
    adoptHint.setMaxWidth(330);
    adoptHint.setVisible(adopting);
    adoptHint.setManaged(adopting);

    // ── 단축 키워드 ──
    TextField keywordField = new TextField();
    keywordField.setPromptText("예: 한글 / yt / dev서버");
    keywordField.getStyleClass().add("dialog-textfield");
    keywordField.setPrefWidth(330);
    if (editing)
      keywordField.setText(existing.keyword());
    else if (prefill != null && !prefill.isBlank())
      keywordField.setText(prefill.trim());

    // ── 별칭 (입력칸 + 추가 버튼 + 담아두는 목록) ──
    ObservableList<String> aliasItems = FXCollections.observableArrayList();

    TextField aliasField = new TextField();
    aliasField.setPromptText("같은 걸 부르는 다른 이름 (예: yt)");
    aliasField.getStyleClass().add("dialog-textfield");
    HBox.setHgrow(aliasField, Priority.ALWAYS);

    Button addAliasBtn = new Button("추가");
    addAliasBtn.getStyleClass().add("dialog-button");

    ListView<String> aliasList = new ListView<>(aliasItems);
    aliasList.getStyleClass().add("dialog-alias-list");
    aliasList.setMaxWidth(330);

    Runnable addAlias = () -> {
      String alias = aliasField.getText().trim();
      if (alias.isEmpty())
        return;
      // 키워드랑 같은 규칙 — 위젯이 첫 칸만 키워드로 보기 때문에 띄어쓰기가 있으면 영영 안 걸린다
      if (alias.contains(" ")) {
        showAlert(dialog, "별칭에도 띄어쓰기를 넣지 말아줘!");
        return;
      }
      if (alias.equalsIgnoreCase(keywordField.getText().trim())) {
        showAlert(dialog, "별칭이 단축 키워드랑 똑같아!");
        return;
      }
      if (aliasItems.stream().noneMatch(alias::equalsIgnoreCase))
        aliasItems.add(alias);
      aliasField.clear();
    };
    addAliasBtn.setOnAction(e -> addAlias.run());
    aliasField.setOnAction(e -> addAlias.run()); // 엔터로도 추가

    // 지우기 — 더블클릭 / Delete 둘 다
    aliasList.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2)
        removeSelectedAlias(aliasList);
    });
    aliasList.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE)
        removeSelectedAlias(aliasList);
    });

    Label aliasHint = new Label("지울 별칭은 더블클릭!");
    aliasHint.getStyleClass().add("dialog-path-label");

    // 별칭이 없을 땐 목록 자리를 통째로 빼서 창이 쓸데없이 길어지지 않게 한다
    aliasItems.addListener((ListChangeListener<String>) c -> {
      boolean any = !aliasItems.isEmpty();
      aliasList.setVisible(any);
      aliasList.setManaged(any);
      aliasHint.setVisible(any);
      aliasHint.setManaged(any);
      aliasList.setPrefHeight(Math.min(aliasItems.size(), 4) * 30 + 4);
      if (dialog.getScene() != null)
        dialog.sizeToScene();
    });
    aliasList.setVisible(false);
    aliasList.setManaged(false);
    aliasHint.setVisible(false);
    aliasHint.setManaged(false);

    HBox aliasRow = new HBox(8, aliasField, addAliasBtn);
    aliasRow.setAlignment(Pos.CENTER_LEFT);
    aliasRow.setPrefWidth(330);

    // ── 종류 고르기 ──
    Label typeLabel = label("종류");

    ToggleGroup typeGroup = new ToggleGroup();
    RadioButton fileType = radio("파일 / 폴더", typeGroup);
    RadioButton siteType = radio("사이트", typeGroup);
    RadioButton cmdType = radio("명령어", typeGroup);
    fileType.setSelected(true);
    HBox typeBox = new HBox(14, fileType, siteType, cmdType);
    typeBox.setAlignment(Pos.CENTER_LEFT);

    // ── 파일: 버튼으로 고르기 ──
    Label pathLabel = new Label("실행 파일이나 폴더를 골라줘~");
    pathLabel.getStyleClass().add("dialog-path-label");
    pathLabel.setWrapText(true);
    pathLabel.setMaxWidth(130);

    // 스토어 앱 경로(shell:AppsFolder\...)는 File 로 쥐기 애매해서 문자열 그대로 들고 있는다
    final String[] selectedPath = new String[1];
    Button chooseFileBtn = new Button("파일 선택");
    chooseFileBtn.getStyleClass().add("dialog-button");
    chooseFileBtn.setOnAction(e -> {
      FileChooser chooser = new FileChooser();
      chooser.setTitle("실행 파일을 선택하세요");
      chooser.getExtensionFilters().add(
          new FileChooser.ExtensionFilter("실행 파일 (*.exe, *.lnk)", "*.exe", "*.lnk"));
      File file = chooser.showOpenDialog(dialog);
      if (file != null) {
        selectedPath[0] = file.getAbsolutePath();
        pathLabel.setText(file.getName());
        suggestKeyword(keywordField, baseName(file.getName()));
      }
    });
    // 폴더를 고르면 부를 때 탐색기로 바로 열린다
    Button chooseFolderBtn = new Button("폴더 선택");
    chooseFolderBtn.getStyleClass().add("dialog-button");
    chooseFolderBtn.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle("열 폴더를 선택하세요");
      if (selectedPath[0] != null && new File(selectedPath[0]).isDirectory())
        chooser.setInitialDirectory(new File(selectedPath[0]));
      File dir = chooser.showDialog(dialog);
      if (dir != null) {
        selectedPath[0] = dir.getAbsolutePath();
        pathLabel.setText("📁 " + folderName(dir));
        suggestKeyword(keywordField, folderName(dir));
      }
    });
    HBox fileRow = new HBox(8, chooseFileBtn, chooseFolderBtn, pathLabel);
    fileRow.setAlignment(Pos.CENTER_LEFT);
    VBox fileBox = new VBox(8, fileRow);

    // ── 사이트: 주소 직접 입력 ──
    TextField urlField = new TextField();
    urlField.setPromptText("예: https://www.youtube.com");
    urlField.getStyleClass().add("dialog-textfield");
    urlField.setPrefWidth(330);
    VBox siteBox = new VBox(8, label("주소"), urlField);

    // ── 명령어: 작업 폴더(입력 + 버튼) + 명령어 ──
    TextField dirField = new TextField();
    dirField.setPromptText("비워두면 기본 위치에서 실행");
    dirField.getStyleClass().add("dialog-textfield");
    HBox.setHgrow(dirField, Priority.ALWAYS);

    Button chooseDirBtn = new Button("폴더 선택");
    chooseDirBtn.getStyleClass().add("dialog-button");
    chooseDirBtn.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle("작업 폴더를 선택하세요");
      File current = new File(dirField.getText().trim());
      if (current.isDirectory())
        chooser.setInitialDirectory(current);
      File dir = chooser.showDialog(dialog);
      if (dir != null)
        dirField.setText(dir.getAbsolutePath());
    });
    HBox dirRow = new HBox(8, dirField, chooseDirBtn);
    dirRow.setAlignment(Pos.CENTER_LEFT);
    dirRow.setPrefWidth(330);

    TextField cmdField = new TextField();
    cmdField.setPromptText("예: npm run dev");
    cmdField.getStyleClass().add("dialog-textfield");
    cmdField.setPrefWidth(330);

    VBox cmdBox = new VBox(8, label("작업 폴더"), dirRow, label("명령어"), cmdField);

    bindVisible(fileBox, fileType.selectedProperty());
    bindVisible(siteBox, siteType.selectedProperty());
    bindVisible(cmdBox, cmdType.selectedProperty());

    // 끌어다 놓기 안내 — 파일 고를 때만 보이면 폴더/링크도 된다는 걸 모르니 항상 띄워둔다
    Label dropHint = new Label("창 아무 데나 파일·폴더·링크를 끌어다 놔도 돼!");
    dropHint.getStyleClass().add("dialog-path-label");
    dropHint.setWrapText(true);
    dropHint.setMaxWidth(330);

    // ── 수정 모드면 원래 값으로 채워둔다 ──
    if (editing) {
      aliasItems.addAll(existing.aliases());
      switch (existing.type()) {
        case TYPE_SITE -> {
          siteType.setSelected(true);
          urlField.setText(existing.value());
        }
        case TYPE_CMD -> {
          cmdType.setSelected(true);
          cmdField.setText(existing.value());
          dirField.setText(existing.dir() == null ? "" : existing.dir());
        }
        default -> {
          fileType.setSelected(true);
          selectedPath[0] = existing.value();
          pathLabel.setText(fileDisplayName(existing.value()));
        }
      }
    }

    // ── 등록 / 취소 ──
    Button registerBtn = new Button(editing && !adopting ? "고치기!" : "등록!");
    registerBtn.getStyleClass().add("dialog-button-primary");
    Button cancelBtn = new Button("취소");
    cancelBtn.getStyleClass().add("dialog-button");
    cancelBtn.setOnAction(e -> dialog.close());

    registerBtn.setOnAction(e -> {
      String keyword = keywordField.getText().trim();
      if (keyword.isEmpty()) {
        showAlert(dialog, "단축 키워드를 입력해줘!");
        return;
      }
      // 위젯은 첫 칸을 키워드로, 나머지를 인자로 쪼개서 본다 — 키워드에 공백이 있으면 영영 안 걸린다.
      // 깔려 있던 앱을 이름 그대로 데려오는 것만 봐준다 ("Android Studio" / "한글 2024" 같은 이름).
      // 이런 건 설치된 앱 목록에서 이름으로 이미 찾아지고, 별칭을 달면 짧게도 부를 수 있어서 안 잃어버린다.
      if (keyword.contains(" ") && !adopting) {
        showAlert(dialog, "단축 키워드에는 띄어쓰기를 넣지 말아줘!");
        return;
      }
      // 별칭 칸에 쳐놓고 "추가" 를 안 누른 것도 주워 담는다 (그냥 날아가면 헷갈린다)
      if (!aliasField.getText().trim().isEmpty()) {
        addAlias.run();
        if (!aliasField.getText().trim().isEmpty())
          return; // 별칭이 규칙에 걸렸으면 경고만 띄우고 멈춘다
      }

      String type = fileType.isSelected() ? TYPE_APP : siteType.isSelected() ? TYPE_SITE : TYPE_CMD;

      try {
        if (fileType.isSelected()) {
          if (selectedPath[0] == null) {
            showAlert(dialog, "실행 파일이나 폴더를 선택해줘!");
            return;
          }
          registerFile(keyword, selectedPath[0]);

        } else if (siteType.isSelected()) {
          String url = normalizeUrl(urlField.getText().trim());
          if (url == null) {
            showAlert(dialog, "사이트 주소를 입력해줘!");
            return;
          }
          // 사이트끼리 이름만 바꾼 거면 검색 주소도 새 이름 쪽으로 옮겨준다
          String keepSearch = (editing && TYPE_SITE.equals(existing.type())) ? existing.search() : null;
          registerSite(keyword, url, keepSearch);

        } else {
          String command = cmdField.getText().trim();
          if (command.isEmpty()) {
            showAlert(dialog, "실행할 명령어를 입력해줘!");
            return;
          }
          String dir = dirField.getText().trim();
          if (!dir.isEmpty() && !new File(dir).isDirectory()) {
            showAlert(dialog, "그런 폴더는 없는 것 같아: " + dir);
            return;
          }
          registerCmd(keyword, dir, command);
        }

        writeAliases(keyword, type, aliasItems, existing);

        // 키워드 이름을 바꿨거나 종류를 갈아탔으면 원래 자리는 치워준다.
        // 깔려 있기만 하던 앱은 등록된 자리가 애초에 없어서 치울 것도 없다 —
        // installed.json 은 scan 이 훑어 만드는 목록이라 여기서 손대지 않는다.
        if (editing && !adopting
            && !(existing.keyword().equals(keyword) && existing.type().equals(type))) {
          removeEntry(existing.keyword(), existing.type());
        }

        macro.reloadRegistry(); // ✨ 재시작 없이 바로 반영!
        dialog.close();
      } catch (IOException ex) {
        ex.printStackTrace();
        showAlert(dialog, "저장하다가 오류가 났어 ㅠㅠ: " + ex.getMessage());
      }
    });

    HBox buttonBox = new HBox(10, registerBtn, cancelBtn);
    buttonBox.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12,
        heading,
        adoptHint,
        label("단축 키워드"), keywordField,
        label("별칭 (없어도 돼)"), aliasRow, aliasList, aliasHint,
        typeLabel, typeBox,
        fileBox, siteBox, cmdBox,
        dropHint,
        buttonBox);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(24));
    root.setAlignment(Pos.CENTER_LEFT);
    root.setPrefWidth(380);

    // 종류마다 칸 수가 달라서, 바뀔 때마다 창 높이를 다시 맞춘다
    typeGroup.selectedToggleProperty().addListener((obs, o, n) -> dialog.sizeToScene());

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);

    // Esc 로 닫기 — 취소 버튼과 같은 길 (다른 창들도 Esc 로 닫힌다: ShareDialog·Notice)
    // 입력칸이나 별칭 목록이 먼저 삼킬 수 있어서 필터로 가로챈다.
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        dialog.close();
        e.consume();
      }
    });

    // ── 끌어다 놓기 ──
    // 창 어디에 떨궈도 받는다. 입력칸(TextField)이 먼저 삼켜버리니 필터로 가로채되,
    // 우리가 다룰 수 있는 것(파일 / 링크)일 때만 consume 해서 글자 끌어 넣기는 그대로 둔다.
    scene.addEventFilter(DragEvent.DRAG_OVER, e -> {
      if (!droppable(e.getDragboard()))
        return;
      e.acceptTransferModes(TransferMode.COPY);
      if (!root.getStyleClass().contains(DROP_ACTIVE))
        root.getStyleClass().add(DROP_ACTIVE);
      e.consume();
    });
    // 창 밖으로 완전히 빠져나갔을 때만 테두리를 끈다 (칸 사이를 지나갈 때마다 깜빡이면 정신없다)
    scene.addEventFilter(DragEvent.DRAG_EXITED, e -> {
      if (e.getTarget() == root)
        root.getStyleClass().remove(DROP_ACTIVE);
    });
    scene.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
      Dragboard board = e.getDragboard();
      if (!droppable(board))
        return;
      root.getStyleClass().remove(DROP_ACTIVE);

      if (board.hasFiles()) {
        File dropped = board.getFiles().get(0);
        // 명령어를 고른 채로 폴더를 떨구면 "이걸 작업 폴더로" 라는 뜻이다
        if (dropped.isDirectory() && cmdType.isSelected()) {
          dirField.setText(dropped.getAbsolutePath());
        } else {
          fileType.setSelected(true);
          selectedPath[0] = dropped.getAbsolutePath();
          pathLabel.setText(dropped.isDirectory() ? "📁 " + folderName(dropped) : dropped.getName());
          suggestKeyword(keywordField, dropped.isDirectory() ? folderName(dropped) : baseName(dropped.getName()));
        }
      } else {
        // 브라우저 주소창이나 링크를 끌어온 경우
        siteType.setSelected(true);
        urlField.setText(board.hasUrl() ? board.getUrl() : board.getString().trim());
      }

      dialog.sizeToScene();
      e.setDropCompleted(true);
      e.consume();
    });

    scene.getStylesheets().add(AppRegisterDialog.class.getResource("/style/style.css").toExternalForm());
    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      Ime.focus(keywordField);
      // 고치러 온 거면 통째로 갈아끼우기 쉽게 잡아두고,
      // 친 글자를 물려받아 채워둔 거면 이어서 칠 수 있게 끝에 세운다.
      if (editing)
        keywordField.selectAll();
      else
        keywordField.positionCaret(keywordField.getLength());
    });
    dialog.showAndWait();
  }

  /** 끌고 온 게 파일이거나 주소일 때만 창이 받아준다 (그냥 글자는 입력칸이 알아서 받게 둔다) */
  private static boolean droppable(Dragboard board) {
    if (board.hasFiles() && !board.getFiles().isEmpty())
      return true;
    if (board.hasUrl())
      return true;
    String text = board.hasString() ? board.getString().trim() : "";
    return text.startsWith("http://") || text.startsWith("https://");
  }

  /** 키워드 칸이 비어 있으면 파일 이름으로 채워준다 — 띄어쓰기가 있는 이름은 키워드로 못 쓰니 건너뛴다 */
  private static void suggestKeyword(TextField keywordField, String name) {
    if (!keywordField.getText().trim().isEmpty() || name.isEmpty() || name.contains(" "))
      return;
    keywordField.setText(name);
  }

  /** "Notion.exe" → "Notion" (확장자만 떼고, 숨김 파일처럼 점으로 시작하는 건 그대로 둔다) */
  private static String baseName(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  /** 드라이브 통째(D:\)는 getName() 이 비어서 경로를 그대로 쓴다 */
  private static String folderName(File dir) {
    return dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
  }

  private static void removeSelectedAlias(ListView<String> list) {
    String picked = list.getSelectionModel().getSelectedItem();
    if (picked != null)
      list.getItems().remove(picked);
  }

  /** 스토어 앱(shell:AppsFolder\...)은 파일이 아니라서 경로를 그대로 보여준다 */
  private static String fileDisplayName(String path) {
    if (path == null)
      return "";
    if (path.startsWith(AppMapper.UWP_PREFIX))
      return path;
    File file = new File(path);
    return file.isDirectory() ? "📁 " + folderName(file) : file.getName();
  }

  /**
   * 키워드로 등록해둔 걸 찾는다. 별칭을 쳐도(edit yt) 본체(유튜브)를 찾아준다.
   * 같은 이름이 여러 종류에 걸쳐 있으면 앱 > 사이트 > 명령어 순으로 하나만 집는다.
   *
   * 등록해둔 게 하나도 없으면 마지막으로 installed.json(그냥 깔려 있는 앱)까지 본다 —
   * "edit Chatgpt" 처럼 아직 등록 안 한 앱도 경로가 채워진 채 창이 떠서, 별칭만 달면 등록이 끝난다.
   */
  private static Existing findExisting(String raw) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    String keyword = raw.trim();
    if (keyword.isEmpty())
      return null;

    Map<String, String> apps = loadMap(mapper, APPS_JSON_PATH, new TypeReference<Map<String, String>>() {
    });
    Map<String, MacroManager.SiteEntry> sites = loadSites(mapper);
    Map<String, MacroManager.CmdEntry> cmds = loadMap(mapper, CMDS_JSON_PATH,
        new TypeReference<Map<String, MacroManager.CmdEntry>>() {
        });
    Map<String, MacroManager.AliasEntry> aliases = loadMap(mapper, ALIASES_JSON_PATH,
        new TypeReference<Map<String, MacroManager.AliasEntry>>() {
        });

    // 대소문자는 안 가린다 ("edit YT" 도 yt 를 찾는다).
    // 다만 창에 채우는 건 파일에 적힌 원래 이름이어야 저장할 때 "YT" 가 따로 하나 더 생기지 않는다.
    String target = keyword;
    String aliasType = null;
    String aliasKey = keyIn(aliases, keyword);
    MacroManager.AliasEntry aliased = aliasKey == null ? null : aliases.get(aliasKey);
    boolean registered = keyIn(apps, keyword) != null || keyIn(sites, keyword) != null
        || keyIn(cmds, keyword) != null;
    if (!registered && aliased != null && aliased.target != null) {
      target = aliased.target;
      aliasType = aliased.type;
    }

    String appKey = keyIn(apps, target);
    if (appKey != null && (aliasType == null || TYPE_APP.equals(aliasType)))
      return new Existing(appKey, TYPE_APP, apps.get(appKey), null, null, aliasesOf(aliases, appKey, TYPE_APP),
          false);

    String siteKey = keyIn(sites, target);
    if (siteKey != null && (aliasType == null || TYPE_SITE.equals(aliasType))) {
      MacroManager.SiteEntry site = sites.get(siteKey);
      return new Existing(siteKey, TYPE_SITE, site.home, null, site.search, aliasesOf(aliases, siteKey, TYPE_SITE),
          false);
    }

    String cmdKey = keyIn(cmds, target);
    if (cmdKey != null && (aliasType == null || TYPE_CMD.equals(aliasType))) {
      MacroManager.CmdEntry entry = cmds.get(cmdKey);
      return new Existing(cmdKey, TYPE_CMD, entry.cmd, entry.dir, null, aliasesOf(aliases, cmdKey, TYPE_CMD), false);
    }

    // 등록된 게 없으면 깔려 있는 앱 중에서 찾는다 (앱 별칭을 쳐서 들어온 거면 여기까지 와도 앱이 맞다)
    if (aliasType == null || TYPE_APP.equals(aliasType)) {
      Map.Entry<String, String> found = findInstalled(mapper, target);
      if (found != null)
        return new Existing(found.getKey(), TYPE_APP, found.getValue(), null, null,
            aliasesOf(aliases, found.getKey(), TYPE_APP), true);
    }

    return null;
  }

  /** 맵에 적힌 원래 이름 — 친 대로 있으면 그것, 없으면 대소문자만 다른 것 (없으면 null) */
  private static String keyIn(Map<String, ?> map, String name) {
    if (map.containsKey(name))
      return name;
    for (String key : map.keySet()) {
      if (key.equalsIgnoreCase(name))
        return key;
    }
    return null;
  }

  /** installed.json 은 앱 이름이 그대로 키라서, 친 대로 없으면 대소문자만 다른 것까지 봐준다 */
  private static Map.Entry<String, String> findInstalled(ObjectMapper mapper, String name) throws IOException {
    Map<String, String> installed = loadMap(mapper, INSTALLED_JSON_PATH,
        new TypeReference<Map<String, String>>() {
        });
    String exact = installed.get(name);
    if (exact != null)
      return Map.entry(name, exact);
    for (Map.Entry<String, String> entry : installed.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name))
        return entry;
    }
    return null;
  }

  private static List<String> aliasesOf(Map<String, MacroManager.AliasEntry> aliases, String keyword, String type) {
    List<String> found = new ArrayList<>();
    for (Map.Entry<String, MacroManager.AliasEntry> e : aliases.entrySet()) {
      if (pointsAt(e.getValue(), keyword, type))
        found.add(e.getKey());
    }
    return found;
  }

  private static boolean pointsAt(MacroManager.AliasEntry entry, String keyword, String type) {
    return entry != null && keyword.equalsIgnoreCase(entry.target) && type.equals(entry.type);
  }

  private static String normalizeUrl(String raw) {
    if (raw.isEmpty())
      return null;
    if (raw.startsWith("http://") || raw.startsWith("https://"))
      return raw;
    return "https://" + raw;
  }

  private static RadioButton radio(String text, ToggleGroup group) {
    RadioButton rb = new RadioButton(text);
    rb.setToggleGroup(group);
    rb.getStyleClass().add("dialog-label");
    return rb;
  }

  private static Label label(String text) {
    Label l = new Label(text);
    l.getStyleClass().add("dialog-label");
    return l;
  }

  /** 안 고른 종류의 칸은 자리까지 통째로 빼버린다 */
  private static void bindVisible(Node node, ObservableValue<Boolean> when) {
    BooleanBinding shown = Bindings.createBooleanBinding(when::getValue, when);
    node.visibleProperty().bind(shown);
    node.managedProperty().bind(shown);
  }

  private static void registerFile(String keyword, String path) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, String> apps = loadMap(mapper, APPS_JSON_PATH, new TypeReference<Map<String, String>>() {
    });
    apps.put(keyword, path);
    saveMap(mapper, APPS_JSON_PATH, apps);
  }

  /**
   * 검색 주소(search)는 창에 칸이 없는 값이라 손으로 채워 넣은 걸 날리면 안 된다.
   * 같은 이름이면 있던 걸 그대로 두고, 이름을 바꿔서 왔으면 keepSearch 로 들고 온 걸 옮겨 심는다.
   */
  private static void registerSite(String keyword, String url, String keepSearch) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, MacroManager.SiteEntry> sites = loadSites(mapper);
    MacroManager.SiteEntry entry = sites.get(keyword);
    if (entry == null) {
      entry = new MacroManager.SiteEntry();
      entry.search = keepSearch; // 없으면 sites.json 에 직접 채워 넣어도 "키워드 검색어" 가 된다
    }
    entry.home = url;
    sites.put(keyword, entry);
    saveSites(mapper, sites);
  }

  private static void registerCmd(String keyword, String dir, String command) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, MacroManager.CmdEntry> cmds = loadMap(mapper, CMDS_JSON_PATH,
        new TypeReference<Map<String, MacroManager.CmdEntry>>() {
        });
    MacroManager.CmdEntry entry = new MacroManager.CmdEntry();
    entry.dir = dir.isEmpty() ? null : dir;
    entry.cmd = command;
    cmds.put(keyword, entry);
    saveMap(mapper, CMDS_JSON_PATH, cmds);
  }

  /**
   * 이 키워드를 가리키던 별칭을 싹 지우고 지금 목록으로 다시 깐다.
   * 수정 모드면 예전 키워드/종류를 가리키던 것까지 같이 치워야 이름을 바꿨을 때 유령이 안 남는다.
   */
  private static void writeAliases(String keyword, String type, List<String> current, Existing old)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, MacroManager.AliasEntry> aliases = loadMap(mapper, ALIASES_JSON_PATH,
        new TypeReference<Map<String, MacroManager.AliasEntry>>() {
        });

    aliases.entrySet().removeIf(e -> pointsAt(e.getValue(), keyword, type)
        || (old != null && pointsAt(e.getValue(), old.keyword(), old.type())));

    for (String alias : current) {
      MacroManager.AliasEntry entry = new MacroManager.AliasEntry();
      entry.target = keyword;
      entry.type = type;
      aliases.put(alias, entry);
    }
    saveMap(mapper, ALIASES_JSON_PATH, aliases);
  }

  /** 이름을 바꾸거나 종류를 갈아탔을 때, 원래 자리에 남아 있는 항목을 지운다 */
  private static void removeEntry(String keyword, String type) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    switch (type) {
      case TYPE_APP -> {
        Map<String, String> apps = loadMap(mapper, APPS_JSON_PATH, new TypeReference<Map<String, String>>() {
        });
        if (apps.remove(keyword) != null)
          saveMap(mapper, APPS_JSON_PATH, apps);
      }
      case TYPE_SITE -> {
        Map<String, MacroManager.SiteEntry> sites = loadSites(mapper);
        if (sites.remove(keyword) != null)
          saveSites(mapper, sites);
      }
      case TYPE_CMD -> {
        Map<String, MacroManager.CmdEntry> cmds = loadMap(mapper, CMDS_JSON_PATH,
            new TypeReference<Map<String, MacroManager.CmdEntry>>() {
            });
        if (cmds.remove(keyword) != null)
          saveMap(mapper, CMDS_JSON_PATH, cmds);
      }
      default -> {
      }
    }
  }

  private static <T> Map<String, T> loadMap(ObjectMapper mapper, String path,
      TypeReference<Map<String, T>> typeRef) throws IOException {
    File file = new File(path);
    if (!file.exists())
      return new HashMap<>();
    return mapper.readValue(file, typeRef);
  }

  /** 검색 되는 사이트(sites.json)랑 열기만 하는 사이트(bookmarks.json)를 합쳐서 읽는다 */
  private static Map<String, MacroManager.SiteEntry> loadSites(ObjectMapper mapper) throws IOException {
    TypeReference<Map<String, MacroManager.SiteEntry>> typeRef = new TypeReference<>() {
    };
    Map<String, MacroManager.SiteEntry> sites = loadMap(mapper, SITES_JSON_PATH, typeRef);
    sites.putAll(loadMap(mapper, BOOKMARKS_JSON_PATH, typeRef));
    return sites;
  }

  /** search 가 있으면 sites.json, 없으면 bookmarks.json (저장소에 안 올라가는 쪽) 으로 나눠 쓴다 */
  private static void saveSites(ObjectMapper mapper, Map<String, MacroManager.SiteEntry> sites) throws IOException {
    Map<String, MacroManager.SiteEntry> searchable = new LinkedHashMap<>();
    Map<String, MacroManager.SiteEntry> bookmarks = new LinkedHashMap<>();
    sites.forEach((name, site) -> (site.search == null ? bookmarks : searchable).put(name, site));
    saveMap(mapper, SITES_JSON_PATH, searchable);
    saveMap(mapper, BOOKMARKS_JSON_PATH, bookmarks);
  }

  private static <T> void saveMap(ObjectMapper mapper, String path, Map<String, T> map) throws IOException {
    File file = new File(path);
    File parent = file.getParentFile();
    if (parent != null)
      parent.mkdirs();
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, map);
  }

  /** 경고는 전부 위젯이랑 같은 옷을 입은 창으로 (기본 Alert 은 혼자 하얘서 안 쓴다) */
  private static void showAlert(Window owner, String message) {
    Notice.warn(owner, message);
  }
}
