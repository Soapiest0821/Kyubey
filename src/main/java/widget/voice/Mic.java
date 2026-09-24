package widget.voice;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 스페이스를 누르고 있는 동안만 열어두는 마이크.
 *
 * 켜고(open) → 떼면 끄고(close) wav 파일 한 개를 내놓는 게 전부다. 소리는 딴 스레드가
 * 퍼 담는다 — 마이크에서 읽는 일은 버퍼가 찰 때까지 기다리는 일이라, JavaFX 스레드에서
 * 하면 누르고 있는 내내 화면이 굳는다.
 *
 * 받는 모양은 16kHz·16비트·모노를 제일 먼저 물어본다. Whisper 가 결국 그 모양으로 바꿔
 * 듣기 때문에 처음부터 그렇게 받으면 옮길 일이 없다. 웹캠 마이크처럼 그 모양을 거절하는
 * 장치도 있어서 44.1kHz·48kHz 와 스테레오까지 차례로 물어보고, 그렇게 받은 건
 * whisper 가 읽으면서 알아서 16kHz 모노로 내린다.
 *
 * 여는 것도 공짜가 아니다. 여기서 재보니 그 판에서 처음 열 땐 400ms 쯤, 그 뒤엔 80ms 쯤
 * 걸리고, 열고 나서도 소리가 실제로 흘러들기까지 0.2초쯤 더 빈다. 그래서 open() 은
 * 딴 스레드에서 부르고(Dictation.press 참고), 그 사이에 손을 떼면 close() 가 먼저 와도
 * 되게 해뒀다 — 그 경우 여는 쪽이 열자마자 도로 닫는다 (wanted).
 *
 * 녹음한 건 임시 wav 로 굽는다. whisper-cli 가 파일만 받기도 하고, 몇 초짜리라 크지도 않다.
 * 다 쓴 파일은 Whisper 쪽에서 지운다.
 */
final class Mic {

  /** 물어볼 소리 모양 — 앞엣것부터, 장치가 받아주는 첫 번째로 연다 */
  private static final AudioFormat[] SHAPES = {
      pcm(16_000, 1),
      pcm(16_000, 2),
      pcm(44_100, 1),
      pcm(44_100, 2),
      pcm(48_000, 1),
      pcm(48_000, 2),
  };

  /** 한 번에 퍼 담는 덩어리. 작을수록 늦게 끊기지만 너무 작으면 헛돈다 */
  private static final int CHUNK = 4096;

  private TargetDataLine line;
  private AudioFormat shape;
  private final ByteArrayOutputStream sound = new ByteArrayOutputStream();
  private Thread pump;
  private volatile boolean recording;

  /** 아직 이 녹음이 쓸모가 있나. 여는 중에 손을 떼면 false 가 되고, 열자마자 도로 닫는다 */
  private volatile boolean wanted = true;

  private static AudioFormat pcm(float rate, int channels) {
    return new AudioFormat(rate, 16, channels, true, false);
  }

  /**
   * 마이크를 연다 (딴 스레드에서 부를 것 — 클래스 주석 참고).
   * 여는 데 실패하면(장치가 없거나 딴 프로그램이 잡고 있으면) 던진다 —
   * 부르는 쪽이 "마이크를 못 열었어" 라고 말해줄 수 있게.
   */
  void open() throws LineUnavailableException {
    TargetDataLine opened = null;
    AudioFormat openedShape = null;

    for (AudioFormat candidate : SHAPES) {
      DataLine.Info info = new DataLine.Info(TargetDataLine.class, candidate);
      if (!AudioSystem.isLineSupported(info))
        continue;

      opened = (TargetDataLine) AudioSystem.getLine(info);
      opened.open(candidate);
      openedShape = candidate;
      break;
    }

    if (opened == null)
      throw new LineUnavailableException("쓸 수 있는 마이크 입력을 못 찾았어");

    synchronized (this) {
      if (!wanted) {
        // 여는 사이에 손을 뗐다 — 담을 것도 없으니 그냥 도로 닫는다
        opened.close();
        return;
      }
      line = opened;
      shape = openedShape;
      recording = true;
      opened.start();

      pump = new Thread(this::drain, "voice-mic");
      pump.setDaemon(true); // 녹음 중에 위젯을 꺼도 프로세스가 안 남게
      pump.start();
    }
  }

  /** 마이크가 흘려주는 걸 계속 퍼 담는다 (딴 스레드) */
  private void drain() {
    byte[] chunk = new byte[CHUNK];
    while (recording) {
      int read = line.read(chunk, 0, chunk.length);
      if (read <= 0)
        break;
      synchronized (sound) {
        sound.write(chunk, 0, read);
      }
    }
  }

  /**
   * 마이크를 닫고 담아둔 소리를 wav 로 굽는다.
   * 열지도 못했거나 아무 소리도 안 담겼으면 null — 부르는 쪽에서 "너무 짧다" 로 본다.
   */
  Path close() throws IOException {
    byte[] pcm = stop();
    if (pcm == null || pcm.length == 0)
      return null;

    Path wav = Files.createTempFile("widget-voice-", ".wav");
    try (AudioInputStream stream = new AudioInputStream(
        new ByteArrayInputStream(pcm), shape, pcm.length / shape.getFrameSize())) {
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile());
    }
    return wav;
  }

  /** 담아둔 소리를 버리고 닫는다 (스페이스를 누른 채로 화면을 떠났을 때) */
  void cancel() {
    stop();
  }

  /** 담긴 소리가 몇 초어치인지. 너무 짧으면 Whisper 한테 보낼 것도 없다 */
  double seconds() {
    if (shape == null)
      return 0;
    return bytes() / (double) shape.getFrameSize() / shape.getFrameRate();
  }

  private int bytes() {
    synchronized (sound) {
      return sound.size();
    }
  }

  private byte[] stop() {
    Thread closing;
    synchronized (this) {
      wanted = false; // 아직 여는 중이면 열자마자 도로 닫으라고 (open 참고)
      recording = false;
      if (line != null) {
        line.stop();
        line.close();
        line = null;
      }
      closing = pump;
      pump = null;
    }

    // 퍼 담던 스레드가 마지막 덩어리를 쓰고 나갈 때까지만 잠깐 기다린다
    if (closing != null) {
      try {
        closing.join(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    synchronized (sound) {
      return sound.toByteArray();
    }
  }
}
