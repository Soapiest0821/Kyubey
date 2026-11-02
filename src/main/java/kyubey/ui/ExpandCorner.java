package kyubey.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import kyubey.core.SettingsDialog;

/**
 * 판 오른쪽 위 모서리에 붙는 직각삼각형 단추 — 누르면 안쪽 판(목표 맵, 메모 글)을 전체화면으로 펴고,
 * 다시 누르면 접는다. 직각이 판 모서리 쪽에 오게 깎아서, 접힌 종이 귀퉁이처럼 보인다 (style.css 의 .expand-corner).
 * F11 과 오른쪽 클릭 메뉴 (menuItem) 도 같은 일을 한다.
 *
 * 판과 같은 StackPane 에 얹는다. 판 테두리가 둥글어서 모서리에서 조금 안쪽으로 들인다.
 */
public final class ExpandCorner extends Button {

  /** 판 둥근 테두리 안쪽으로 들이는 거리 */
  private static final double INSET = 4;

  private final Runnable onToggle;
  private final BooleanProperty expanded = new SimpleBooleanProperty();
  private final Tooltip tooltip = new Tooltip();

  public ExpandCorner(Runnable onToggle) {
    this.onToggle = onToggle;
    getStyleClass().add("expand-corner");
    setFocusTraversable(false);
    setOnAction(e -> onToggle.run());
    // 도움말은 뜰 때마다 새로 쓴다 — 펴 둔 채 "설정" 에서 Esc 로 풀기를 바꿔도 맞게
    tooltip.setOnShowing(e -> tooltip.setText(tooltipText()));
    setTooltip(tooltip);
    setExpanded(false);
    StackPane.setAlignment(this, Pos.TOP_RIGHT);
    StackPane.setMargin(this, new Insets(INSET, INSET, 0, 0));
  }

  /** 펴진 채면 귀퉁이를 진하게 칠하고 말풍선 도움말 · 메뉴 이름을 바꾼다 */
  public void setExpanded(boolean on) {
    expanded.set(on);
    pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("expanded"), on);
    tooltip.setText(tooltipText());
  }

  /** Esc 로 전체화면을 푸는 건 설정 (escExitsFullScreen) 이 켜져 있을 때만 */
  private String tooltipText() {
    if (!expanded.get())
      return "전체화면 (F11)";
    return SettingsDialog.load().escExitsFullScreen ? "전체화면 끄기 (F11 · Esc)" : "전체화면 끄기 (F11)";
  }

  /** 오른쪽 클릭 메뉴에 넣을 전체화면 켜기/끄기. 이름은 지금 펴졌는지를 따라간다 */
  public MenuItem menuItem() {
    MenuItem item = new MenuItem();
    item.textProperty().bind(Bindings.when(expanded).then("전체화면 끄기").otherwise("전체화면 (F11)"));
    item.setOnAction(e -> onToggle.run());
    return item;
  }
}
