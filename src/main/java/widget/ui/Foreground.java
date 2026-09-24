package widget.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * 우리 창을 진짜로 앞으로 끌어와서 키보드 포커스까지 쥐여준다.
 *
 * 윈도우는 "방금 입력을 받은 프로세스"만 남의 창을 밀어내고 앞으로 나오게 해준다(포그라운드 잠금).
 * Win+Alt+Space 를 받은 순간엔 우리가 그 자격이 있는데, Platform.runLater 를 거쳐 숨긴 창을
 * 다시 만드는 사이에 사용자가 키를 떼면 그 키 떼기가 원래 쓰던 창으로 가서 자격이 날아간다.
 * 그러면 JavaFX 의 toFront()/requestFocus() 는 창만 위에 그려 놓고 포커스는 못 가져온다
 * (작업 표시줄 아이콘만 깜빡이고, 치는 글자는 원래 창으로 간다).
 *
 * 그래서 지금 앞에 있는 창의 스레드에 잠깐 입력 큐를 붙여서(AttachThreadInput) 그 스레드
 * 행세를 하고 SetForegroundWindow 를 부른다. 그래도 막히면 Alt 를 한 번 눌렀다 떼서
 * "우리가 마지막 입력을 받았다" 로 만들어 놓고 한 번 더 시도한다.
 *
 * JavaFX 창의 스레드(= FX 스레드)에서 불러야 한다.
 */
public final class Foreground {

  private static final int VK_MENU = 0x12;
  private static final int KEYEVENTF_KEYUP = 0x0002;

  /** jna-platform 의 User32 는 버전 따라 들쭉날쭉해서 필요한 것만 직접 맵핑한다 */
  private interface User32 extends StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

    HWND GetForegroundWindow();

    boolean SetForegroundWindow(HWND hWnd);

    boolean BringWindowToTop(HWND hWnd);

    int GetWindowThreadProcessId(HWND hWnd, IntByReference pid);

    boolean AttachThreadInput(int idAttach, int idAttachTo, boolean attach);

    boolean EnumThreadWindows(int threadId, WinUser.WNDENUMPROC fn, Pointer data);

    int GetWindowText(HWND hWnd, char[] text, int max);

    boolean IsWindowVisible(HWND hWnd);

    void keybd_event(byte vk, byte scan, int flags, Pointer extra);
  }

  private interface Kernel32 extends StdCallLibrary {
    Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

    int GetCurrentThreadId();
  }

  private Foreground() {
  }

  /**
   * FX 스레드가 가진 창 중 제목이 title 인 보이는 창을 앞으로 끌어온다.
   * 제목으로 찾는 건 우리 스레드 안에서만이라, 같은 이름의 탐색기 폴더 창 같은 건 안 걸린다.
   * JNA 가 안 올라온 환경이면 아무것도 안 한다 (JavaFX 쪽 toFront 만 믿는다).
   */
  public static void force(String title) {
    try {
      int me = Kernel32.INSTANCE.GetCurrentThreadId();
      HWND hwnd = find(me, title);
      if (hwnd == null)
        return;
      if (take(me, hwnd))
        return;
      // 자격이 없어서 막혔다 — Alt 를 한 번 쳐서 마지막 입력을 우리 걸로 만들고 다시
      User32.INSTANCE.keybd_event((byte) VK_MENU, (byte) 0, 0, null);
      User32.INSTANCE.keybd_event((byte) VK_MENU, (byte) 0, KEYEVENTF_KEYUP, null);
      take(me, hwnd);
    } catch (Throwable t) {
      System.err.println("[focus] 창을 앞으로 못 끌어왔어: " + t);
    }
  }

  private static HWND find(int thread, String title) {
    HWND[] found = new HWND[1];
    User32.INSTANCE.EnumThreadWindows(thread, (hWnd, data) -> {
      if (!User32.INSTANCE.IsWindowVisible(hWnd))
        return true;
      char[] buf = new char[256];
      int len = User32.INSTANCE.GetWindowText(hWnd, buf, buf.length);
      if (title.equals(new String(buf, 0, len))) {
        found[0] = hWnd;
        return false;
      }
      return true;
    }, null);
    return found[0];
  }

  /** @return 우리 창이 앞에 나왔으면 true */
  private static boolean take(int me, HWND hwnd) {
    HWND fg = User32.INSTANCE.GetForegroundWindow();
    if (hwnd.equals(fg))
      return true;
    int them = fg == null ? 0 : User32.INSTANCE.GetWindowThreadProcessId(fg, null);
    boolean attached = them != 0 && them != me && User32.INSTANCE.AttachThreadInput(me, them, true);
    try {
      User32.INSTANCE.BringWindowToTop(hwnd);
      User32.INSTANCE.SetForegroundWindow(hwnd);
    } finally {
      if (attached)
        User32.INSTANCE.AttachThreadInput(me, them, false);
    }
    return hwnd.equals(User32.INSTANCE.GetForegroundWindow());
  }
}
