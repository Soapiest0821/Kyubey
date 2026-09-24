package widget.ui;

import widget.core.Settings;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * 처음 켰을 때 (settings.json 이 없을 때) 뜨는 창, 나중엔 "setup" 으로 다시 부른다.
 *
 * 폴더 이름만 쳐서 열 수 있게 훑어둘 뿌리 폴더를 고른다. 예전엔 코드에 내 폴더가
 * 박혀 있었는데, 남의 컴퓨터엔 그런 폴더가 없어서 여기서 고르게 했다.
 * 앱 쪽은 따로 안 묻는다 — 시작 메뉴는 알아서 훑고(scan), 모자란 건 "new" 로 넣으면 된다.
 *
 * "나중에" 로 닫아도 빈 설정을 저장한다. 안 그러면 켤 때마다 이 창이 또 뜬다.
 */
public final class SetupDialog {

  private SetupDialog() {
  }

  /** 창을 띄우고 닫힐 때까지 기다린다. "저장" 으로 닫았으면 그 설정을, 아니면 null */
  public static Settings show(Stage owner) {
    Settings settings = Settings.load();

    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initStyle(StageStyle.TRANSPARENT);

    Label heading = new Label("🌸 처음 설정");
    heading.getStyleClass().add("notice-title");

    Label sub = new Label("자주 여는 폴더들이 모여 있는 곳을 골라줘. 그 밑의 폴더는 이름만 쳐도 열 수 있게 돼.\n"
        + "앱은 시작 메뉴에서 알아서 찾고, 빠진 건 \"new\" 로 넣으면 돼. 나중에 \"setup\" 으로 다시 고칠 수 있어.");
    sub.getStyleClass().add("dialog-path-label");
    sub.setWrapText(true);

    ObservableList<String> roots = FXCollections.observableArrayList(settings.scanRoots);
    ListView<String> list = new ListView<>(roots);
    list.getStyleClass().add("dialog-alias-list");
    list.setPrefHeight(160);
    list.setPlaceholder(new Label("아직 고른 폴더가 없어"));

    Button add = new Button("＋ 폴더 추가");
    add.getStyleClass().add("dialog-button");
    add.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle("훑을 폴더 고르기");
      File dir = chooser.showDialog(dialog);
      if (dir != null && !roots.contains(dir.getAbsolutePath()))
        roots.add(dir.getAbsolutePath());
    });

    Button remove = new Button("빼기");
    remove.getStyleClass().add("dialog-button");
    remove.setOnAction(e -> {
      String picked = list.getSelectionModel().getSelectedItem();
      if (picked != null)
        roots.remove(picked);
    });

    HBox listButtons = new HBox(8, add, remove);

    Label error = new Label();
    error.getStyleClass().add("dialog-path-label");
    error.setManaged(false);
    error.setVisible(false);

    Settings[] saved = { null };
    Runnable save = () -> {
      settings.scanRoots = new ArrayList<>(roots);
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

    Button later = new Button("나중에");
    later.getStyleClass().add("dialog-button");
    later.setOnAction(e -> {
      // 처음 뜬 거면 빈 채로라도 저장해서 다음에 또 안 뜨게. 이미 있던 설정은 건드리지 않는다
      // 고른 게 없으니 훑지도 않는다 — 그래서 null 을 돌려준다
      if (!Settings.exists()) {
        try {
          new Settings().save();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
      dialog.close();
    });

    Button ok = new Button("저장");
    ok.getStyleClass().add("dialog-button-primary");
    ok.setOnAction(e -> save.run());

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, later, ok);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox root = new VBox(12, heading, sub, list, listButtons, error, buttons);
    root.getStyleClass().add("dialog-root");
    root.setPadding(new Insets(22));
    root.setPrefWidth(520);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(SetupDialog.class.getResource("/style/style.css").toExternalForm());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.getCode() == KeyCode.ESCAPE) {
        e.consume();
        later.fire();
      } else if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
        e.consume();
        save.run();
      } else if (e.getCode() == KeyCode.DELETE) {
        e.consume();
        remove.fire();
      }
    });

    dialog.setScene(scene);
    WindowDrag.makeDraggable(dialog, root);
    dialog.setOnShown(e -> {
      centerOn(dialog, owner);
      add.requestFocus();
    });
    dialog.showAndWait();
    return saved[0];
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
