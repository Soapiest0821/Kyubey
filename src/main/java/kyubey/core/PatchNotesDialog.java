package kyubey.core;

import kyubey.ui.MarkdownView;
import kyubey.ui.WindowDrag;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /patch 로 여는 패치 노트 창. 루트의 PATCHNOTES.md 를 그대로 읽어 MarkdownView 로 그린다 —
 * 노트를 고치면 다시 빌드 안 해도 다음에 열 때 바로 보인다.
 * 닫는 건 Enter / Esc / 버튼 아무거나 (Notice 와 같은 옷).
 */
public final class PatchNotesDialog {

  public static final String PATH = "PATCHNOTES.md";

  private static final double WIDTH = 520;
  private static final double HEIGHT = 560;

  private PatchNotesDialog() {
  }

  public static void show(Window owner) {
    Stage popup = new Stage();
    if (owner != null)
      popup.initOwner(owner);
    popup.initModality(Modality.APPLICATION_MODAL);
    popup.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label("📜 패치 노트");
    title.getStyleClass().add("notice-title");

    MarkdownView notes = new MarkdownView();
    notes.setMarkdown(read());
    VBox.setVgrow(notes, Priority.ALWAYS);

    Button okBtn = new Button("닫기");
    okBtn.getStyleClass().add("dialog-button-primary");
    okBtn.setDefaultButton(true);
    okBtn.setOnAction(e -> popup.close());

    HBox buttonBox = new HBox(okBtn);
    buttonBox.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(14, title, notes, buttonBox);
    root.getStyleClass().addAll("dialog-root", "notice-root");
    root.setPadding(new Insets(22));
    root.setPrefSize(WIDTH, HEIGHT);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.ESCAPE)
        popup.close();
    });

    popup.setScene(scene);
    // 타이틀바가 없으니 아무 데나 잡고 끈다 (스크롤은 글 칸이 먼저 받는다)
    WindowDrag.makeDraggable(popup, root);
    popup.setOnShown(e -> {
      if (owner != null && owner.getWidth() > 0 && !Double.isNaN(owner.getX())) {
        popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
        popup.setY(owner.getY() + (owner.getHeight() - popup.getHeight()) / 2);
      } else {
        popup.centerOnScreen();
      }
      okBtn.requestFocus();
    });
    popup.showAndWait();
  }

  /** 파일이 없거나 못 읽으면 창은 그대로 띄우고 왜 비었는지만 적는다 */
  private static String read() {
    try {
      return Files.readString(Path.of(PATH), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "패치 노트를 못 읽었어 (" + PATH + ")";
    }
  }
}
