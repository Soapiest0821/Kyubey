package kyubey.todo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 할 일을 이 컴퓨터에만 적어둔다. 투두메이트 서버 없이.
 *
 * 카테고리는 날짜와 상관없이 한 벌이라 categories.json 하나에,
 * 할 일은 하루에 한 파일씩 (2026-10-06.json) — 지난 날을 나중에 들춰보려고 지우지 않고 쌓아둔다.
 * 하루 파일의 할 일마다 카테고리 이름도 같이 적는다. 나중에 카테고리를 고치거나 지워도
 * 그날 무슨 이름 밑에 있었는지 남게.
 *
 * 하루는 자정이 아니라 아침 DAY_STARTS_AT 시에 바뀐다 (today). 새벽까지 깨어 있는 날에도
 * 그날 할 일이 그대로 보이게. 날이 바뀌면 전날 못 끝낸 일을 오늘로 넘긴다 (carryOver).
 */
public final class TodoStore {

  public static final String DIR = "src/main/resources/json/todo";

  /** 하루가 바뀌는 시각 — 아침 여섯 시. 그 전까지는 아직 어제다 */
  public static final int DAY_STARTS_AT = 6;

  /**
   * 목표 맵의 말단 칸을 파트너한테 물어서 내린 할 일이 모이는 카테고리 id (addGoal).
   * id 로 찾아서, 이름표를 오른쪽 클릭해 이름을 바꿔도 계속 여기로 모인다
   */
  public static final String GOAL_CATEGORY = "goal";
  /** GOAL_CATEGORY 를 처음 만들 때 이름 */
  public static final String GOAL_CATEGORY_TITLE = "목표";

  /** 목표 맵에서 내려온 할 일이 모이는 카테고리인지 — 말단 칸 (GOAL_CATEGORY) 이나 목표마다 하나 ("goal-" + 목표 id) */
  public static boolean isGoalCategory(String categoryId) {
    return categoryId != null && (categoryId.equals(GOAL_CATEGORY) || categoryId.startsWith(GOAL_CATEGORY + "-"));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Category {
    public String id;
    public String title;

    public Category() {
    }

    Category(String title) {
      this.id = newId();
      this.title = title;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Todo {
    public String id;
    public String categoryId;
    /** 적을 때의 카테고리 이름 — 기록을 볼 때 쓰려고. 화면은 categoryId 로 묶는다 */
    public String category;
    public String content;
    public boolean done;
    /** 못 끝내서 넘어온 날짜 (처음 적은 날). 비었으면 그날 적은 것 — 화면에서 진한 핑크로 칠한다 */
    public String overdueSince;
    /** 이 할 일을 다음 날로 넘겼는지. 넘긴 건 기록에 그대로 두고, 또 넘기지 않는다 */
    public boolean carried;
    /**
     * 끝낸 날 (체크를 누른 날). 넘겨받은 사본들(같은 id)에 다 똑같이 적는다 — 그날은 체크,
     * 그 전 날들은 취소선, 그 뒤 날들은 없던 것으로 (struck / gone). 비었으면 아직 안 끝냈다
     */
    public String doneOn;
    /** 그날 할 일을 다 끝내 칭찬받은 뒤에 적은 것 — 이건 끝낼 때마다 칭찬한다 (TodoPane.milestone) */
    public boolean late;
    /** late 인 걸 끝내서 칭찬받았는지. 체크를 풀었다 다시 해서 또 받지 않게 */
    public boolean praised;
    /** 목표 맵에서 내려온 할 일이면 그 목표 id 와 칸 id — 오른쪽 클릭 "목표로 이동하기" 로 그 칸에 간다. 아니면 null */
    public String goalId;
    public String goalNode;

    public Todo() {
    }

    public boolean overdue() {
      return overdueSince != null && !overdueSince.isEmpty();
    }

    /** date 날 기록에서 취소선 — 밀려서 나중 날에 끝낸 것. 남은 개수에 안 친다 */
    public boolean struck(String date) {
      return doneOn != null && date.compareTo(doneOn) < 0;
    }

    /** date 날 기록에서 없던 것 — 그 전 날에 이미 끝낸 것. 보이지도 세지도 않는다 */
    public boolean gone(String date) {
      return doneOn != null && date.compareTo(doneOn) > 0;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Day {
    public String date;
    public List<Todo> todos = new ArrayList<>();
    /** 그날 할 일을 절반 끝내 칭찬받았는지 (할 일이 두 개 이상일 때만, 하루 한 번) */
    public boolean halfPraised;
    /** 그날 할 일을 다 끝내 칭찬받았는지 (하루 한 번) */
    public boolean allPraised;

    public Day() {
    }

    /** 그날 보이는 할 일 — 그 전 날에 끝내서 없던 게 된 건 뺀다 */
    public List<Todo> shown() {
      List<Todo> out = new ArrayList<>();
      for (Todo todo : todos) {
        if (!todo.gone(date))
          out.add(todo);
      }
      return out;
    }

    /** 그날 남은 할 일 개수 — 체크한 것 · 취소선 · 없던 것은 빼고 */
    public int left() {
      int left = 0;
      for (Todo todo : todos) {
        if (!todo.done && todo.doneOn == null)
          left++;
      }
      return left;
    }

    /** 그날 끝낸 할 일 개수 — 체크한 것만 */
    public int finished() {
      int finished = 0;
      for (Todo todo : todos) {
        if (todo.done)
          finished++;
      }
      return finished;
    }
  }

  private static final ObjectMapper mapper = new ObjectMapper();

  private final File dir = new File(DIR);
  private final File categoriesFile = new File(dir, "categories.json");

  public List<Category> categories() throws IOException {
    if (!categoriesFile.isFile())
      return new ArrayList<>();
    Category[] read = mapper.readValue(categoriesFile, Category[].class);
    return new ArrayList<>(List.of(read));
  }

  public void saveCategories(List<Category> categories) throws IOException {
    dir.mkdirs();
    mapper.writerWithDefaultPrettyPrinter().writeValue(categoriesFile, categories);
  }

  public Day day(LocalDate date) throws IOException {
    File file = dayFile(date);
    Day day = file.isFile() ? mapper.readValue(file, Day.class) : new Day();
    day.date = date.toString();
    if (day.todos == null)
      day.todos = new ArrayList<>();
    return day;
  }

  public void saveDay(Day day) throws IOException {
    dir.mkdirs();
    File file = dayFile(LocalDate.parse(day.date));
    // 아무것도 안 적은 날은 빈 파일을 남기지 않는다 (다 지웠으면 파일도 치운다)
    if (day.todos.isEmpty()) {
      file.delete();
      return;
    }
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, day);
  }

  /** 지금이 어느 날 몫인지 — 아침 DAY_STARTS_AT 시 전이면 아직 어제 */
  public static LocalDate today() {
    return LocalDateTime.now().minusHours(DAY_STARTS_AT).toLocalDate();
  }

  /**
   * 오늘(today) 전에 마지막으로 적은 날에서 못 끝낸 일을 오늘로 넘긴다.
   * 며칠 비웠으면 그 사이를 건너뛰고 마지막으로 적은 날에서 가져온다 — 거기 남은 게 곧 못 끝낸 일이라.
   *
   * 넘긴 건 그날 기록에서 지우지 않고 carried 만 찍어둔다 — 지난 날을 들춰볼 때 못 한 채로 남게,
   * 그리고 오늘 그걸 지워도 다시 넘어오지 않게. 오늘로 온 건 같은 id 에 overdueSince 를 단다.
   *
   * 넘긴 게 있으면 저장까지 하고 오늘 하루를 돌려준다. 없으면 null.
   */
  public Day carryOver(Day today) throws IOException {
    LocalDate date = LocalDate.parse(today.date);
    LocalDate previous = lastDayBefore(date);
    if (previous == null)
      return null;

    Day from = day(previous);
    List<Todo> moved = new ArrayList<>();
    for (Todo todo : from.todos) {
      // 다른 날에 끝낸 것 (doneOn) 도 넘기지 않는다
      if (todo.done || todo.carried || todo.doneOn != null)
        continue;
      todo.carried = true;
      Todo copy = new Todo();
      copy.id = todo.id;
      copy.categoryId = todo.categoryId;
      copy.category = todo.category;
      copy.content = todo.content;
      copy.goalId = todo.goalId;
      copy.goalNode = todo.goalNode;
      copy.overdueSince = todo.overdue() ? todo.overdueSince : from.date;
      moved.add(copy);
    }
    if (moved.isEmpty())
      return null;

    // 넘어온 게 오늘 적은 것보다 위에 오게
    today.todos.addAll(0, moved);
    saveDay(today);
    saveDay(from);
    return today;
  }

  /**
   * current 날의 todo 를 끝냈다고 (doneOn = 그날) 또는 안 끝냈다고 (doneOn = null) 적는다.
   * 밀려 다니며 생긴 사본들(같은 id, 다른 날 파일)에도 끝낸 날을 똑같이 적어서
   * 끝낸 날은 체크, 그 전 날들은 취소선, 그 뒤 날들은 없던 것이 되게 한다. 되돌리면 다 원래대로.
   *
   * 다른 날 파일은 여기서 저장한다. current 는 고치기만 하고 저장은 부른 쪽이 한다 (화면이 쥔 하루라서).
   */
  public void markDone(Day current, Todo todo, LocalDate doneOn) throws IOException {
    String on = doneOn == null ? null : doneOn.toString();
    // 사본은 처음 적은 날부터 생긴다
    LocalDate origin = LocalDate.parse(todo.overdue() ? todo.overdueSince : current.date);
    for (Todo t : current.todos) {
      if (t.id.equals(todo.id))
        stamp(t, current.date, on);
    }
    for (LocalDate date : days()) {
      if (date.isBefore(origin) || date.toString().equals(current.date))
        continue;
      Day day = day(date);
      boolean changed = false;
      for (Todo t : day.todos) {
        if (t.id.equals(todo.id)) {
          stamp(t, day.date, on);
          changed = true;
        }
      }
      if (changed)
        saveDay(day);
    }
  }

  /**
   * current 날의 todo 를 다음 날로 미룬다. current 에서는 빼고 다음 날 맨 위에 같은 id 로 얹는다 —
   * 못 끝내서 넘어온 것(carryOver)과 똑같이 overdueSince 를 달아서 다음 날엔 진한 핑크로 뜬다.
   *
   * 다음 날 파일은 여기서 저장한다. current 는 고치기만 하고 저장은 부른 쪽이 한다 (markDone 과 같은 이치).
   */
  public void postpone(Day current, Todo todo) throws IOException {
    Day next = day(LocalDate.parse(current.date).plusDays(1));
    current.todos.removeIf(t -> t.id.equals(todo.id));
    for (Todo t : next.todos) {
      // 이미 다음 날에 같은 게 있으면 (미뤘다 또 미룬 것) 겹쳐 얹지 않는다
      if (t.id.equals(todo.id))
        return;
    }
    Todo copy = new Todo();
    copy.id = todo.id;
    copy.categoryId = todo.categoryId;
    copy.category = todo.category;
    copy.content = todo.content;
    copy.goalId = todo.goalId;
    copy.goalNode = todo.goalNode;
    copy.overdueSince = todo.overdue() ? todo.overdueSince : current.date;
    next.todos.add(0, copy);
    saveDay(next);
  }

  private static void stamp(Todo todo, String date, String doneOn) {
    todo.doneOn = doneOn;
    todo.done = date.equals(doneOn);
  }

  /** date 전에 할 일을 적어둔 마지막 날. 없으면 null */
  private LocalDate lastDayBefore(LocalDate date) {
    LocalDate latest = null;
    for (LocalDate day : days()) {
      if (day.isBefore(date) && (latest == null || day.isAfter(latest)))
        latest = day;
    }
    return latest;
  }

  /** 할 일을 적어둔 날들 (파일 이름에서) */
  private List<LocalDate> days() {
    File[] files = dir.listFiles((d, name) -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.json"));
    List<LocalDate> out = new ArrayList<>();
    if (files != null) {
      for (File file : files)
        out.add(LocalDate.parse(file.getName().substring(0, 10)));
    }
    return out;
  }

  /** 목표 맵의 칸 하나를 할 일로 내릴 때 — 칸 id 와 적을 내용 (addGoal) */
  public record GoalItem(String nodeId, String content) {
  }

  /**
   * 목표 맵(GoalPane)의 칸들을 오늘 할 일로 적는다. 카테고리도 할 일도 이름 말고 id 로 기억한다 —
   * 카테고리는 categoryId 로 찾고 (없으면 categoryTitle 로 맨 끝에 새로 만든다), 할 일마다 목표 id · 칸 id 를 달아둔다.
   * 그래서 카테고리 이름표나 목표 · 칸 이름을 바꿔도 같은 카테고리로 모이고, "목표로 이동하기" 도 그 칸을 찾아간다.
   * 오늘 이미 그 칸에서 내려온 게 있으면 건너뛴다.
   *
   * 파일에만 적으니, 화면(TodoPane)은 부른 쪽이 새로 읽혀야 한다. 넣은 개수를 돌려준다.
   */
  public int addGoal(String categoryId, String categoryTitle, String goalId, List<GoalItem> items) throws IOException {
    List<Category> categories = categories();
    Category found = null;
    for (Category c : categories) {
      if (categoryId.equals(c.id))
        found = c;
    }
    if (found == null) {
      found = new Category(categoryTitle);
      found.id = categoryId;
      categories.add(found);
      saveCategories(categories);
    }

    Category category = found;
    Day day = day(today());
    int added = 0;
    for (GoalItem item : items) {
      boolean exists = day.shown().stream()
          .anyMatch(t -> goalId.equals(t.goalId) && item.nodeId().equals(t.goalNode));
      if (exists)
        continue;
      Todo todo = newTodo(category, item.content());
      todo.goalId = goalId;
      todo.goalNode = item.nodeId();
      // 다 끝내 칭찬받은 뒤에 적은 것 — 손으로 적을 때와 똑같이 (TodoPane.openAdder)
      todo.late = day.allPraised;
      day.todos.add(todo);
      added++;
    }
    if (added > 0)
      saveDay(day);
    return added;
  }

  /**
   * 목표 맵에서 칸을 끝냈다고 (또는 안 끝냈다고) 할 때, 그 칸에서 내려와 지난 날에만 남은 할 일
   * (못 끝내고 오늘로 안 넘어온 것) 을 오늘 끝낸 걸로 적는다 — 오늘 끝낸 할 일의 지난 사본처럼 그날엔 취소선이 된다.
   * 오늘 있는 건 TodoPane.finishGoal 이 손으로 체크한 것과 똑같이 처리하고, 그 id 들을 skip 으로 넘긴다.
   * 되돌릴 땐 오늘 끝낸 걸로 적힌 것만 푼다 — 다른 날 끝낸 건 이 칸 때문에 끝낸 게 아니라서.
   * 고친 게 있으면 true
   */
  public boolean markGoalPast(String goalId, List<String> nodeIds, boolean done, Set<String> skip) throws IOException {
    LocalDate today = today();
    String on = today.toString();
    boolean changed = false;
    for (LocalDate date : days()) {
      if (!date.isBefore(today))
        continue;
      Day day = day(date);
      boolean dirty = false;
      for (Todo todo : day.todos) {
        if (!fromGoal(todo, goalId, nodeIds) || skip.contains(todo.id))
          continue;
        if (done ? todo.doneOn != null : !on.equals(todo.doneOn))
          continue;
        stamp(todo, day.date, done ? on : null);
        dirty = true;
      }
      if (dirty) {
        saveDay(day);
        changed = true;
      }
    }
    return changed;
  }

  /**
   * 목표 맵에서 "할 일에서 빼기" 했을 때 — 그 칸에서 내려온 할 일을 오늘과 그 뒤 날(미룬 것)에서 지운다.
   * 지난 날 기록은 그대로 둔다. 파일에만 적으니 화면은 부른 쪽이 새로 읽혀야 한다. 지운 개수를 돌려준다
   */
  public int removeGoal(String goalId, List<String> nodeIds) throws IOException {
    LocalDate today = today();
    int removed = 0;
    for (LocalDate date : days()) {
      if (date.isBefore(today))
        continue;
      Day day = day(date);
      int before = day.todos.size();
      day.todos.removeIf(t -> fromGoal(t, goalId, nodeIds));
      if (day.todos.size() != before) {
        removed += before - day.todos.size();
        saveDay(day);
      }
    }
    return removed;
  }

  /**
   * 목표 맵에서 칸을 합쳤을 때 — 없어진 칸에서 내려온 할 일을 합쳐진 칸으로 다시 잇는다 (relink: 옛 칸 id → 새 칸 id).
   * 지난 날 기록까지 다 고쳐서 "목표로 이동하기" 가 어느 날에서든 칸을 찾아가게. 고친 개수를 돌려준다
   */
  public int relinkGoal(String goalId, Map<String, String> relink) throws IOException {
    if (relink.isEmpty())
      return 0;
    int changed = 0;
    for (LocalDate date : days()) {
      Day day = day(date);
      boolean dirty = false;
      for (Todo todo : day.todos) {
        String to = goalId.equals(todo.goalId) ? relink.get(todo.goalNode) : null;
        if (to == null)
          continue;
        todo.goalNode = to;
        dirty = true;
        changed++;
      }
      if (dirty)
        saveDay(day);
    }
    return changed;
  }

  /** 목표 nodeIds 칸에서 내려온 할 일인지 */
  public static boolean fromGoal(Todo todo, String goalId, List<String> nodeIds) {
    return goalId.equals(todo.goalId) && nodeIds.contains(todo.goalNode);
  }

  public Category newCategory(String title) {
    return new Category(title);
  }

  public Todo newTodo(Category category, String content) {
    Todo todo = new Todo();
    todo.id = newId();
    todo.categoryId = category.id;
    todo.category = category.title;
    todo.content = content;
    return todo;
  }

  public File dir() {
    return dir;
  }

  private File dayFile(LocalDate date) {
    return new File(dir, date + ".json");
  }

  private static String newId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
