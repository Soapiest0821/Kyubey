package kyubey.goal;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import kyubey.core.SettingsDialog;
import kyubey.ui.Ime;
import kyubey.ui.WindowDrag;

/**
 * ＋ 로 새로 만들 때 뜨는 창 — 이름과 어떤 식으로 정리할지 고른다 (GoalPane.startNewDialog).
 *  - 목표 쪼개기: 맨 위 칸에서 아래로 쪼개는 트리. 파트너랑 같이 쪼갤지 고른다
 *  - 관계도: 중심 칸 + 아무 칸끼리나 잇는 그래프. 연결에 방향이 있을지 고른다. 파트너는 안 쓴다
 * Enter 면 만들고, Esc · 취소면 null. 만든 뒤엔 GoalPane 이 고른 방식의 길잡이 (GoalGuideDialog) 를 띄운다.
 */
final class GoalKindDialog {

  /** 고른 것 — kind 는 GoalStore.TREE · GRAPH */
  record Choice(String title, String kind, boolean llm, boolean directed) {
  }

  private GoalKindDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. llmLabel 은 "마도카랑 쪼개기" 같은 체크 글. llmReady 가 false 면 꺼진 채로 잠근다 */
  static Choice show(Window owner, String initial, boolean llmReady, String llmLabel) {
    Stage dialog = new Stage();
    if (owner != null)
      dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label title = new Label("🌱 새로 정리하기");
    title.getStyleClass().add("notice-title");

    TextField field = new TextField(initial == null ? "" : initial);
    field.getStyleClass().add("dialog-textfield");
    field.setPromptText("이름 — 목표나 중심 생각");

    ToggleGroup group = new ToggleGroup();
    ToggleButton tree = kind(group, "✂ 목표 쪼개기", GoalStore.TREE);
    ToggleButton graph = kind(group, "🕸 관계도", GoalStore.GRAPH);
    tree.setSelected(true);
    HBox kinds = new HBox(6, tree, graph);

    Label hint = new Label();
    hint.getStyleClass().add("dialog-path-label");
    hint.setWrapText(true);

    CheckBox llm = new CheckBox(llmLabel + " (LLM 쓰기)");
    llm.getStyleClass().add("dialog-path-label");
    llm.setSelected(llmReady);
    llm.setDisable(!llmReady);
    CheckBox directed = new CheckBox("방향 있는 연결 (화살표)");
    directed.getStyleClass().add("dialog-path-label");

    Runnable sync = () -> {
      boolean isGraph = graph.isSelected();
      llm.setVisible(!isGraph);
      llm.setManaged(!isGraph);
      directed.setVisible(isGraph);
      directed.setManaged(isGraph);
      hint.setText(isGraph
          ? "중심 칸에서 시작해 아무 칸끼리나 잇는 그림이야. 고른 칸에서 N 은 이어진 칸, 아무것도 안 고르고 N 은 따로 선 칸. Ctrl+끌기를 다른 칸에 놓으면 잇기, 빈 데 놓으면 이은 새 칸."
          : "맨 위 칸을 작은 칸들로 아래로 쪼개 나가는 트리야." + (llmReady ? "" : " (Gemini 키가 없어서 손으로만)"));
    };
    group.selectedToggleProperty().addListener((obs, was, is) -> sync.run());
    sync.run();

    Choice[] result = { null };
    Button ok = new Button("만들기");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setFocusTraversable(false);
    ok.disableProperty().bind(field.textProperty().map(String::isBlank));
    ok.setOnAction(e -> {
      boolean isGraph = graph.isSelected();
      result[0] = new Choice(field.getText().strip(), isGraph ? GoalStore.GRAPH : GoalStore.TREE,
          !isGraph && llm.isSelected(), isGraph && directed.isSelected());
      dialog.close();
    });
    Button cancel = new Button("취소");
    cancel.getStyleClass().add("dialog-button");
    cancel.setFocusTraversable(false);
    cancel.setOnAction(e -> dialog.close());

    HBox buttons = new HBox(8, cancel, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, title, field, kinds, hint, llm, directed, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(360);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SettingsDialog.styleUrl());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        dialog.close();
      } else if (e.getCode() == KeyCode.ENTER) {
        e.consume();
        if (!ok.isDisabled())
          ok.fire();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      if (owner == null || owner.getWidth() <= 0) {
        dialog.centerOnScreen();
      } else {
        dialog.setX(owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2);
        dialog.setY(owner.getY() + (owner.getHeight() - dialog.getHeight()) / 2);
      }
      // 막 뜬 창의 입력칸이라 한 박자 미뤄 Ime 로 줘야 한글 조합이 보인다 (Ime 주석 참고)
      Platform.runLater(() -> {
        Ime.focus(field);
        field.selectAll();
      });
    });
    dialog.showAndWait();
    return result[0];
  }

  /** 하나만 고르는 알약 단추 (SettingsDialog.toggle 과 같은 생김새) — 고른 걸 다시 눌러도 안 꺼진다 */
  private static ToggleButton kind(ToggleGroup group, String label, String kind) {
    ToggleButton b = new ToggleButton(label);
    b.getStyleClass().add("llm-mode");
    b.setUserData(kind);
    b.setToggleGroup(group);
    b.setFocusTraversable(false);
    b.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      if (b.isSelected())
        e.consume();
    });
    return b;
  }
}
