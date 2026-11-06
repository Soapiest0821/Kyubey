package kyubey.goal;

import kyubey.core.AppPaths;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 생각 정리하기 (← 화면, GoalPane) 가 적어두는 곳. 목표 여러 개를 goals.json 한 파일에 담는다.
 *
 * 목표 하나 = 트리 하나 + 그 트리를 두고 마도카랑 주고받은 짧은 말들.
 * 관계도(GRAPH)는 트리 대신 중심 칸(root) + 평평한 칸들(nodes) + 연결(links) 로 담는다 — 자리도 손으로 잡아서 x · y 를 남긴다.
 * 수다 화면(ChatPane)과 달리 지우지 않고도 목표를 갈아타며 쓸 수 있어서, 목표마다 따로 들고 있다.
 * 마도카한테는 이 말들 말고 트리만 보여준다 (GoalPane.ask 참고) — 말은 내가 다시 보려고 남기는 것뿐이다.
 */
public final class GoalStore {

  public static final String PATH = AppPaths.JSON + "goals.json";

  /** 목표 쪼개기 — 맨 위 칸에서 아래로 쪼개 나가는 트리. kind 가 없으면 이거 (전에 적은 목표들) */
  public static final String TREE = "tree";
  /** 관계도 — 중심 칸 + 아무 칸끼리나 잇는 그래프. 파트너는 안 쓴다 */
  public static final String GRAPH = "graph";

  /** 트리의 칸 하나. id 는 목표 안에서만 겹치지 않으면 된다 ("n0", "n1"…) — 마도카가 짚어 말하기 쉽게 짧게 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Node {
    public String id;
    public String title;
    /** 끝낸 칸 — 트리에 취소선으로 보인다 */
    public boolean done;
    /** 오늘 할 일에 넣은 적 있는지 — 트리에 📌 로 보인다 */
    public boolean todo;
    /** 말단으로 정한 칸 — 더 쪼개지 않고 바로 손댈 칸. 트리에 흰 테두리로 보이고, 파트너한테 물어 할 일로 내릴 수 있다 (GoalPane.askLeaf) */
    public boolean leaf;
    /** 접어둔 칸인지. 다시 열어도 접은 그대로 보이게 남긴다 */
    public boolean folded;
    /** 칸 메모. 이 칸을 쪼갤 때 마도카가 한 말이 쌓이고, 더블클릭해서 다시 보거나 손으로 고친다. 없으면 null */
    public String note;
    public List<Node> children = new ArrayList<>();
    /** 관계도에서 칸 가운데 자리 (중심 칸이 0, 0). 트리 칸은 자리를 맵이 잡아서 비워 둔다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Double x;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Double y;

    public Node() {
    }

    Node(String id, String title) {
      this.id = id;
      this.title = title;
    }
  }

  /** 목표 화면에서 오간 말 한 줄. mine 이 true 면 내 말 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Line {
    public boolean mine;
    public String text;

    public Line() {
    }

    Line(boolean mine, String text) {
      this.mine = mine;
      this.text = text;
    }
  }

  /** 관계도의 연결 하나. 두 칸 사이엔 하나만 둔다 — 방향은 dir 이 정하고, 방향 없는 관계도면 순서도 dir 도 뜻이 없다 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Link {
    /** from → to 한 방향 */
    static final String ONE = "one";
    /** 양방향 */
    static final String BOTH = "both";
    /** 아직 방향을 안 정했다 — 방향 없는 관계도에서 이은 채로 방향을 달았을 때. Ctrl+끌기로 정한다 */
    static final String NONE = "none";

    public String from;
    public String to;
    /** ONE · BOTH · NONE. 방향 있는 관계도에서만 뜻이 있다 */
    public String dir;

    public Link() {
    }

    Link(String from, String to, String dir) {
      this.from = from;
      this.to = to;
      this.dir = dir;
    }

    boolean touches(String nodeId) {
      return nodeId.equals(from) || nodeId.equals(to);
    }

    boolean one() {
      return ONE.equals(dir);
    }

    boolean both() {
      return BOTH.equals(dir);
    }

    /** 방향 있는 관계도에서 a 에서 b 로 건너갈 수 있는지 — 방향을 안 정한 연결은 양쪽 다 */
    boolean goes(String a, String b) {
      if (one())
        return a.equals(from) && b.equals(to);
      return touches(a) && touches(b);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Goal {
    public String id;
    /** 맨 위 칸이 곧 목표 이름이다. 늘 "n0". 관계도에선 중심 칸 */
    public Node root;
    public List<Line> log = new ArrayList<>();
    /** 마지막으로 내준 칸 번호 — 지운 칸 번호를 다시 쓰면 마도카가 헷갈려서 계속 올리기만 한다 */
    public int seq;
    /** TREE · GRAPH. 없으면 TREE */
    public String kind;
    /** 트리를 파트너랑 같이 쪼갤지. 끄면 칸은 손으로만 붙인다 */
    public boolean llm = true;
    /** 관계도 연결에 방향이 있는지 (화살표) */
    public boolean directed;
    /** 관계도를 루트부터 세로줄로 늘어놓아 보는지. 아니면 손으로 잡은 자리대로 (자유 그래프) */
    public boolean layered;
    /** 관계도의 중심 칸 말고 칸들 — 관계도는 children 대신 여기 평평하게 둔다 */
    public List<Node> nodes = new ArrayList<>();
    public List<Link> links = new ArrayList<>();

    public Goal() {
    }

    /** 관계도인지 */
    boolean graph() {
      return GRAPH.equals(kind);
    }

    /** 파트너한테 물어가며 쓰는 목표인지 — 관계도는 늘 손으로만 */
    boolean usesLlm() {
      return !graph() && llm;
    }

    /** 관계도 칸 전부 — 중심 칸 먼저 */
    List<Node> graphNodes() {
      List<Node> out = new ArrayList<>();
      out.add(root);
      out.addAll(nodes);
      return out;
    }

    /** a 와 b 를 잇는 연결 — 방향은 안 따진다. 없으면 null */
    Link link(Node a, Node b) {
      for (Link link : links) {
        if (link.touches(a.id) && link.touches(b.id))
          return link;
      }
      return null;
    }

    /** a 와 b 를 잇는다 — 방향 있는 관계도면 a → b, 없으면 방향 안 정한 채로. 자기 자신이거나 이미 이어져 있으면 null */
    Link connect(Node a, Node b) {
      if (a == b || link(a, b) != null)
        return null;
      Link link = new Link(a.id, b.id, directed ? Link.ONE : Link.NONE);
      links.add(link);
      return link;
    }

    /**
     * 옛 파일 맞추기 — dir 이 없던 연결은 방향 있는 관계도면 한 방향, 없으면 안 정한 걸로.
     * 예전엔 양방향을 반대로 가는 연결 둘로 담았다 — 하나로 합쳐 양방향으로
     */
    void normalizeLinks() {
      List<Link> kept = new ArrayList<>();
      for (Link link : links) {
        if (link.dir == null)
          link.dir = directed ? Link.ONE : Link.NONE;
        Link twin = null;
        for (Link k : kept) {
          if (k.touches(link.from) && k.touches(link.to))
            twin = k;
        }
        if (twin == null)
          kept.add(link);
        else if (twin.one() && link.one() && twin.from.equals(link.to))
          twin.dir = Link.BOTH;
      }
      links.clear();
      links.addAll(kept);
    }

    /** 이 목표에서 새 칸 하나 */
    Node node(String title) {
      return new Node("n" + (++seq), title);
    }

    /** 이 id 의 칸. 없으면 null */
    Node find(String nodeId) {
      Node found = find(root, nodeId);
      if (found != null || nodeId == null)
        return found;
      for (Node node : nodes) {
        if (nodeId.equals(node.id))
          return node;
      }
      return null;
    }

    /** 이 칸을 품은 칸. 맨 위 칸이거나 없으면 null */
    Node parentOf(Node child) {
      return parentOf(root, child);
    }

    private static Node find(Node at, String nodeId) {
      if (at == null || nodeId == null)
        return null;
      if (nodeId.equals(at.id))
        return at;
      for (Node child : at.children) {
        Node found = find(child, nodeId);
        if (found != null)
          return found;
      }
      return null;
    }

    private static Node parentOf(Node at, Node child) {
      for (Node c : at.children) {
        if (c == child)
          return at;
        Node found = parentOf(c, child);
        if (found != null)
          return found;
      }
      return null;
    }
  }

  /** 파일 하나에 담기는 모양 — 목표들과, 마지막으로 보던 목표 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Book {
    public String current;
    public List<Goal> goals = new ArrayList<>();
    /** 새로 만들 때 뜨는 길잡이 (GoalGuideDialog) 를 "다음부터 안 보기" 한 방식들 — TREE · GRAPH */
    public List<String> guideOff = new ArrayList<>();

    public Book() {
    }
  }

  private static final ObjectMapper mapper = new ObjectMapper();

  private final File file;

  public GoalStore(String path) {
    this.file = new File(path);
  }

  /** 파일이 없거나 깨졌으면 빈 책 — 목표는 새로 세우면 그만이라서 */
  public Book load() {
    if (!file.isFile())
      return new Book();
    try {
      Book book = mapper.readValue(file, Book.class);
      if (book.goals == null)
        book.goals = new ArrayList<>();
      if (book.guideOff == null)
        book.guideOff = new ArrayList<>();
      for (Goal goal : book.goals) {
        if (goal.links == null)
          goal.links = new ArrayList<>();
        goal.normalizeLinks();
      }
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

  /** 새 목표. 맨 위 칸 이름은 일단 친 말 그대로 두고, 마도카가 짧게 다듬는다 */
  public static Goal newGoal(String title) {
    return newGoal(title, TREE, true, false);
  }

  /** 새 목표 — kind 는 TREE · GRAPH, llm 은 트리를 파트너랑 쪼갤지, directed 는 관계도 연결에 방향이 있는지 */
  public static Goal newGoal(String title, String kind, boolean llm, boolean directed) {
    Goal goal = new Goal();
    goal.id = UUID.randomUUID().toString().substring(0, 8);
    goal.root = new Node("n0", title);
    goal.kind = kind;
    goal.llm = GRAPH.equals(kind) ? false : llm;
    goal.directed = directed;
    if (GRAPH.equals(kind)) {
      goal.root.x = 0.0;
      goal.root.y = 0.0;
    }
    return goal;
  }

  static Line line(boolean mine, String text) {
    return new Line(mine, text);
  }
}
