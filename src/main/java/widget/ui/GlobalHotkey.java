package widget.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 윈도우 전역 단축키 (Win+Alt+Space 로 위젯 부르기, Win+Alt+Z 로 화면 자르기).
 *
 * 창을 숨겨두면 키보드 이벤트가 JavaFX 까지 안 오니까 OS 한테 직접 부탁한다.
 * RegisterHotKey 는 "이 조합이 눌리면 알려줘" 만 등록하는 거라, 모든 키를 엿보는
 * 전역 후킹(키로거처럼 보이는 그것)과 달리 이 조합 말고는 아무것도 받지 않는다.
 *
 * RegisterHotKey 로 등록한 스레드의 메시지 큐로만 WM_HOTKEY 가 오기 때문에,
 * 등록과 메시지 루프를 같은 데몬 스레드에서 돌린다. 그래서 단축키는 한 번에
 * 몰아서 등록한다 — 나중에 다른 스레드에서 하나 더 걸면 그 키는 영영 안 온다.
 */
public final class GlobalHotkey {

  private static final int MOD_ALT = 0x0001;
  private static final int MOD_WIN = 0x0008;
  /** 꾹 누르고 있을 때 따발총처럼 반복되는 것 막기 */
  private static final int MOD_NOREPEAT = 0x4000;

  /** 위젯 부르기 (Win+Alt+Space) */
  public static final int VK_SPACE = 0x20;
  /** 화면 자르기 (Win+Alt+Z) */
  public static final int VK_Z = 0x5A;

  private static final int WM_HOTKEY = 0x0312;

  private GlobalHotkey() {
  }

  /**
   * 걸어둘 단축키 하나. vk 는 이 클래스의 VK_* 상수, name 은 못 걸렸을 때
   * 누구 얘기인지 알아보라고 붙이는 이름("Win+Alt+Z")이다.
   */
  public record Hotkey(int vk, String name, Runnable onPress) {
  }

  /** jna-platform 의 User32 는 버전 따라 들쭉날쭉해서 필요한 것만 직접 맵핑한다 */
  private interface User32 extends StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

    boolean RegisterHotKey(Pointer hWnd, int id, int fsModifiers, int vk);

    boolean UnregisterHotKey(Pointer hWnd, int id);

    int GetMessage(WinUser.MSG lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);
  }

  /**
   * 넘긴 단축키들을 Win+Alt 조합으로 한꺼번에 등록하고, 눌릴 때마다 각자의 onPress 를 부른다.
   * onPress 는 메시지 루프 스레드에서 불리니까 UI 를 만지려면 Platform.runLater 로 넘겨야 한다.
   *
   * 하나가 막혀도 나머지는 그대로 건다 — 남의 프로그램이 선점한 건 그 조합 하나뿐이라서.
   *
   * @return 못 건 단축키 이름들. 전부 걸렸으면 빈 목록.
   */
  public static List<String> register(Hotkey... hotkeys) {
    final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
    final List<String> missed = new ArrayList<>();
    final Map<Integer, Runnable> handlers = new HashMap<>();

    Thread pump = new Thread(() -> {
      try {
        for (int i = 0; i < hotkeys.length; i++) {
          Hotkey hotkey = hotkeys[i];
          int id = i + 1; // 등록 id 는 이 스레드 안에서만 유일하면 된다
          boolean ok = User32.INSTANCE.RegisterHotKey(null, id,
              MOD_WIN | MOD_ALT | MOD_NOREPEAT, hotkey.vk());
          if (ok)
            handlers.put(id, hotkey.onPress());
          else
            missed.add(hotkey.name());
        }
      } catch (Throwable t) {
        // JNA 자체가 안 올라온 경우 (네이티브 라이브러리 없음 등) — 전부 못 건 셈
        System.err.println("[hotkey] 전역 단축키를 못 걸었어: " + t);
        for (Hotkey hotkey : hotkeys) {
          if (!missed.contains(hotkey.name()))
            missed.add(hotkey.name());
        }
        handlers.clear();
      } finally {
        ready.countDown();
      }
      if (handlers.isEmpty())
        return;

      WinUser.MSG msg = new WinUser.MSG();
      while (true) {
        int r = User32.INSTANCE.GetMessage(msg, null, 0, 0);
        if (r <= 0) // 0 = WM_QUIT, -1 = 에러
          break;
        if (msg.message != WM_HOTKEY)
          continue;
        Runnable onPress = handlers.get(msg.wParam.intValue());
        if (onPress == null)
          continue;
        try {
          onPress.run();
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }
      for (Integer id : handlers.keySet())
        User32.INSTANCE.UnregisterHotKey(null, id);
    }, "global-hotkey");

    pump.setDaemon(true);
    pump.start();

    try {
      ready.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return missed;
  }
}
