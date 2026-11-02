package kyubey.win;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import kyubey.ui.AppIcon;
import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 윈도우 알림 (화면 오른쪽 아래에 떴다가 알림 센터에 쌓이는 그것).
 *
 * 따로 라이브러리를 들이지 않고 AWT 의 TrayIcon.displayMessage 를 쓴다 — 윈도우 10/11 에선
 * 이게 그대로 토스트 알림으로 뜬다. 다만 알림을 띄우려면 트레이에 아이콘이 하나 서 있어야 해서,
 * 처음 알릴 때 위젯 아이콘을 트레이에 세우고 꺼질 때까지 둔다.
 *
 * 알림을 누르면 onClick 을 FX 스레드에서 불러준다 (Main 이 창을 불러 수다 화면을 연다).
 * 알림마다 누를 때 할 일을 따로 줄 수도 있다 — 목표 쪼개기 알림은 그 목표로 간다.
 *
 * 트레이를 못 쓰는 환경이면 조용히 아무것도 안 한다 — 알림은 덤이고, 배지는 그대로 뜬다.
 */
public final class WinNotify {

  private final String name;
  private final Runnable onClick;
  private TrayIcon icon;
  /** 마지막으로 띄운 알림을 누를 때 할 일. 트레이 아이콘은 하나라 알림 풍선도 마지막 것 하나만 누를 수 있다 */
  private volatile Runnable click;
  private boolean broken;

  /** 알림 발신자를 가르는 ID. 아무 문자열이나 되지만 한 번 정하면 안 바꾸는 게 좋다 (알림 센터 설정이 이걸로 묶인다) */
  private static final String APP_ID = "Kyubey.Madoka";

  /** jna-platform 의 Shell32 는 버전 따라 들쭉날쭉해서 필요한 것만 직접 맵핑한다 */
  private interface Shell32 extends StdCallLibrary {
    Shell32 INSTANCE = Native.load("shell32", Shell32.class, W32APIOptions.DEFAULT_OPTIONS);

    int SetCurrentProcessExplicitAppUserModelID(WString appId);
  }

  public WinNotify(String name, Runnable onClick) {
    this.name = name;
    this.onClick = onClick;
    this.click = onClick;
  }

  /**
   * 알림에 뜰 발신자 이름과 얼굴을 정한다. 창이나 트레이 아이콘을 띄우기 전에 (main 에서) 한 번 불러야 한다.
   *
   * 윈도우는 알림 머리에 프로세스의 AppUserModelID 에 등록된 이름을 쓰는데, 아무것도 안 정하면
   * java.exe 의 파일 설명("OpenJDK Platform Binary")이 뜬다. 그래서 ID 를 하나 정해 프로세스에 붙이고,
   * HKCU\Software\Classes\AppUserModelId\<ID> 에 이름(DisplayName)과 아이콘(IconUri)을 적어 둔다.
   * 관리자 권한은 필요 없다. 실패해도 알림 이름만 예전처럼 뜰 뿐이라 조용히 넘어간다.
   */
  public static void register(String displayName) {
    try {
      String key = "Software\\Classes\\AppUserModelId\\" + APP_ID;
      Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key);
      Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "DisplayName", displayName);
      Path icon = iconFile();
      if (icon != null)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "IconUri", icon.toString());

      int hr = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(APP_ID));
      if (hr != 0)
        System.err.println("[notify] 알림 이름을 못 붙였어 (HRESULT 0x" + Integer.toHexString(hr) + ")");
    } catch (Throwable e) {
      System.err.println("[notify] 알림 이름을 못 붙였어: " + e.getMessage());
    }
  }

  /** 레지스트리엔 파일 경로만 적을 수 있어서, jar 안의 아이콘을 %LOCALAPPDATA%\Kyubey 에 PNG 로 꺼내 둔다 */
  private static Path iconFile() {
    BufferedImage image = AppIcon.awt();
    String base = System.getenv("LOCALAPPDATA");
    if (image == null || base == null)
      return null;
    try {
      Path file = Path.of(base, "Kyubey", "notify.png");
      Files.createDirectories(file.getParent());
      ImageIO.write(image, "png", file.toFile());
      return file;
    } catch (IOException e) {
      System.err.println("[notify] 알림 아이콘을 못 꺼냈어: " + e.getMessage());
      return null;
    }
  }

  /** 아무 스레드에서 불러도 된다. AWT 일은 AWT 스레드로 넘긴다 */
  public void show(String title, String message) {
    show(title, message, onClick);
  }

  /** 누르면 onClick 대신 이 알림만의 click 을 부른다 (FX 스레드에서) */
  public void show(String title, String message, Runnable click) {
    this.click = click;
    EventQueue.invokeLater(() -> {
      TrayIcon tray = tray();
      if (tray != null)
        tray.displayMessage(title, message, TrayIcon.MessageType.NONE);
    });
  }

  /** 트레이 아이콘을 처음 한 번만 세운다. 못 세우면 다음부터는 시도도 안 한다 */
  private TrayIcon tray() {
    if (icon != null || broken)
      return icon;
    if (!SystemTray.isSupported()) {
      broken = true;
      System.err.println("[notify] 이 환경은 트레이를 못 써서 윈도우 알림은 건너뛸게");
      return null;
    }

    BufferedImage image = AppIcon.awt();
    if (image == null)
      image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    TrayIcon created = new TrayIcon(image, name);
    created.setImageAutoSize(true);
    // 알림 풍선을 누르거나 트레이 아이콘을 두 번 누르면 들어온다
    created.addActionListener(e -> Platform.runLater(click));
    try {
      SystemTray.getSystemTray().add(created);
    } catch (AWTException e) {
      broken = true;
      System.err.println("[notify] 트레이에 아이콘을 못 세웠어: " + e.getMessage());
      return null;
    }
    icon = created;
    return icon;
  }
}
