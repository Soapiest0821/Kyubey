package kyubey;

/**
 * jpackage 실행 파일의 시작점.
 *
 * JavaFX 가 클래스패스에 있을 때 Application 을 상속한 Main 을 바로 시작점으로 잡으면
 * "JavaFX runtime components are missing" 으로 멈춘다. 상속이 없는 이 클래스를 거쳐 띄운다.
 */
public final class Launcher {

  private Launcher() {
  }

  public static void main(String[] args) {
    Main.main(args);
  }
}
