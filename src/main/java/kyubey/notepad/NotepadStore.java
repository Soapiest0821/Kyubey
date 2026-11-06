package kyubey.notepad;

import kyubey.core.AppPaths;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 메모장 (↓ 화면, NotepadPane) 이 적어두는 곳. 메모 여러 장을 notepad.json 한 파일에 담는다.
 * 수다 쪽 기억(memo.json)과 헷갈리지 않게 이름을 따로 뒀다.
 */
public final class NotepadStore {

  public static final String PATH = AppPaths.JSON + "notepad.json";

  /** 메모 한 장. 첫 줄이 곧 목록에 뜨는 이름이다 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Note {
    public String id;
    public String text = "";
    /** 마지막으로 고친 때 (epoch ms) */
    public long updated;

    public Note() {
    }

    /** 목록에 뜨는 이름 — 비어 있지 않은 첫 줄 */
    public String title() {
      for (String line : text.split("\n")) {
        if (!line.isBlank())
          return line.strip();
      }
      return "(빈 메모)";
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Book {
    /** 마지막으로 보던 메모 id — 다시 들어오면 그 메모가 열린다 */
    public String current;
    /** 새 메모가 위로 온다 */
    public List<Note> notes = new ArrayList<>();

    public Book() {
    }
  }

  private static final ObjectMapper mapper = new ObjectMapper();

  private final File file;

  public NotepadStore(String path) {
    this.file = new File(path);
  }

  public Book load() {
    if (!file.isFile())
      return new Book();
    try {
      Book book = mapper.readValue(file, Book.class);
      if (book.notes == null)
        book.notes = new ArrayList<>();
      return book;
    } catch (IOException e) {
      e.printStackTrace();
      return new Book();
    }
  }

  public void save(Book book) throws IOException {
    file.getParentFile().mkdirs();
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, book);
  }

  public static Note newNote() {
    Note note = new Note();
    note.id = UUID.randomUUID().toString().substring(0, 8);
    note.updated = System.currentTimeMillis();
    return note;
  }
}
