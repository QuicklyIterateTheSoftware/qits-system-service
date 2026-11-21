package eu.wohlben.qits.system.stories.support;

import eu.wohlben.qits.system.testdocker.FakeDocker;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>The recording docker the story catalogue runs against</b>, and the tap that draws what the
 * launched process asked the daemon for.
 *
 * <h2>Why the docker hop is observed here and not declared</h2>
 *
 * <p>This service never opens {@code /var/run/docker.sock} itself: {@code docker/DockerCli}, through
 * qits-containers-driver's {@code ContainerProcess},
 * <b>spawns the docker CLI</b> and reads its pipes, and which binary that is arrives as one runtime
 * key, {@code qits.system.docker.binary}. So the honest stand-in for its one outbound dependency is
 * not a stubbed endpoint — it is an executable, and this repository has shipped one since long
 * before it had stories: {@code src/test/resources/fake-docker/docker}, which answers in docker's
 * exact output shapes, refuses in docker's own words, and turns {@code run -it} / {@code exec -it}
 * into a real echo loop on a real pseudo-terminal.
 *
 * <p>That is why the docker hop is an <b>observed</b> {@code process} edge rather than a claim. The
 * argv the stand-in receives is the argv qits-containers-driver's builders produced, a
 * real {@code ProcessBuilder} forked a real child to deliver it, and the script records it with the
 * exit code it answered. What no tap out here can see is the hop <i>behind</i> that CLI — the unix
 * socket the deployment mounts into this container — and that one edge stays {@link
 * eu.wohlben.qits.userflows.Network#declare declared}. Two hops, and each drawn as what it is.
 *
 * <h2>The catalogue gets its own copy of the binary</h2>
 *
 * <p>{@link #install()} <b>stages</b> the shipped script into {@code target/story-docker/} and
 * answers that path; the story profile points the launched artifact at it. The copy is not
 * fastidiousness: {@code target/test-classes/fake-docker/} is written by the whole surefire suite,
 * which runs to completion before failsafe starts, so a recording read from there would open with
 * several hundred calls belonging to unit tests. The staged directory is wiped once and then
 * written by exactly one process — the launched artifact — from its first boot call onwards.
 *
 * <p>Which is also why the recording has <b>no floor</b>. The calls this service makes at boot —
 * the daemon probe and the sweep for terminal containers a previous life left behind — are the
 * subject of the first story, the same way the startup JWKS fetch is. The source is registered at
 * zero and the framework's per-source cursor attributes those lines to whichever story drains
 * first, which the class ordering makes the story about them. Run a later class on its own and its
 * first story inherits the boot calls and fails its edge count — loudly, which is the right way for
 * that assumption to break.
 *
 * <p>Everything shared is a file or a system property, because nothing here shares a heap: {@link
 * #install()} is called from a {@code QuarkusTestProfile}, which Quarkus instantiates in more than
 * one classloader, and the stand-in itself is a child process of a <i>second</i> JVM.
 *
 * <h2>What a line becomes</h2>
 *
 * <p>The script records {@code <exit><TAB><arg><TAB><arg>…}, and {@link #summarize} reduces the
 * argv to the sentence a reader of a dependency map needs — {@code info}, {@code system df}, {@code
 * exec -it {digest} sh}. Never the whole command line, for two reasons that are both about the
 * {@code networkHash}: a terminal argv carries {@code QITS_SYSTEM_SESSION=<uuid>} and a glances
 * argv a {@code --name} derived from the same uuid, which are generated per run; and a Go {@code
 * --format} template is full of braces that mermaid reads as syntax. The exit code follows as
 * {@code -> 0}, in the shape an HTTP label's status has, because it is the same half of the
 * evidence: that the call was <i>answered</i>, not merely made.
 *
 * <p>The argv is still assertable in full — {@link #argvOf} reads it out of the same recording. The
 * diagram carries summaries; a claim about a <b>flag</b> (the sandbox flags on a glances run, the
 * canonical id on an exec) reads the argv instead. Same recording, finer question.
 */
public final class StoryDocker {

  /**
   * How a diagram names the spawned CLI. Deliberately the program, not the daemon: this service
   * forks {@code docker} and talks to it over pipes, which is a real property of the design — the
   * argv <i>is</i> the sandbox, assertable element for element with no daemon anywhere — and not an
   * accident of the stand-in.
   */
  public static final String DOCKER = "docker";

  /** The kind those edges carry: a spawned process talked to over its pipes. */
  public static final String KIND = NetworkEdge.PROCESS;

  /**
   * The hop behind the CLI, and the one thing in this catalogue no tap can reach: the host's docker
   * socket, mounted into this container by a deliberate, recorded grant. It is the whole reason
   * this service exists and the whole reason that edge is a declaration.
   */
  public static final String DAEMON = "the host's docker daemon";

  /** What the declared edge says. A literal, and it has to survive {@code Labels.scrub} unchanged. */
  public static final String SOCKET_LABEL = "the mounted /var/run/docker.sock";

  /** The marker a still-running child carries where a completed call carries its exit code. */
  public static final String RUNNING = "running";

  /** Where the staged directory is parked, for the profile's other classloader to find. */
  private static final String DIR_PROPERTY = "qits.test.story-docker.dir";

  private static final String SOURCE_ID = "story-docker";

  private static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final Pattern LONG_HEX = Pattern.compile("[0-9a-f]{32,}");

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** How many recorded lines are already edges. The framework's own cursor slices what it returns. */
  private static int harvested;

  /** Everything ever emitted, in order. Only grows, so the framework's cursor cannot re-slice it. */
  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryDocker() {}

  // --- the binary ---------------------------------------------------------------------------------

  /**
   * Stage the shipped stand-in into this catalogue's own directory and answer the path to hand
   * {@code qits.system.docker.binary}.
   *
   * <p>Idempotent per JVM through the parked property: the profile is instantiated more than once
   * and only the first copy has any business wiping the recording — by the time the second asks,
   * the launched process may already be booting against it.
   *
   * <p>The mode has to be set here. Maven's resource copy does not carry the committed {@code 755}
   * into {@code target/test-classes}, and a {@code ProcessBuilder} on a non-executable file fails
   * with "Permission denied", which reads like a sandbox problem and is not one.
   */
  public static synchronized String install() {
    String parked = System.getProperty(DIR_PROPERTY);
    if (parked != null) {
      return Path.of(parked).resolve("docker").toString();
    }
    Path dir = Path.of(System.getProperty("user.dir"), "target", "story-docker").toAbsolutePath();
    try {
      deleteRecursively(dir);
      Files.createDirectories(dir);
      Path binary = dir.resolve("docker");
      Files.copy(Path.of(FakeDocker.binaryPath()), binary);
      Files.setPosixFilePermissions(
          binary,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_EXECUTE));
      System.setProperty(DIR_PROPERTY, dir.toString());
      return binary.toString();
    } catch (IOException e) {
      throw new UncheckedIOException("could not stage the docker stand-in under " + dir, e);
    }
  }

  /** Where the answered calls are recorded. Resolved through the parked directory, never guessed. */
  public static Path resultLog() {
    String parked = System.getProperty(DIR_PROPERTY);
    Path dir =
        parked != null
            ? Path.of(parked)
            : Path.of(System.getProperty("user.dir"), "target", "story-docker").toAbsolutePath();
    return dir.resolve("results.log");
  }

  // --- what a story class calls ---------------------------------------------------------------

  /**
   * Register the recording as a cumulative {@link NetworkCapture} source, once per JVM.
   *
   * <p>At zero rather than at a floor — see the class javadoc. Called from every story class's
   * {@code @BeforeAll} so each class is self-contained; whichever runs first does the work, and the
   * {@code registered} gate is what stops a second install drawing every call twice.
   */
  public static void installSource() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      EDGES.clear();
      NetworkCapture.source(SOURCE_ID, StoryDocker::edges);
      registered = true;
    }
  }

  /**
   * How many calls the stand-in has answered so far — a story's own <b>starting line</b>.
   *
   * <p>The recording is cumulative and one launched process serves the whole catalogue, so a bare
   * "the log contains no {@code exec}" would be a claim about the RUN rather than about the story,
   * and it is wrong in exactly the case worth asserting: the refusal story runs beside a story that
   * legitimately opened a shell. Take a mark before acting and read {@link #callsSince(int)}
   * afterwards, and the question becomes "what did THIS story ask docker for".
   */
  public static int mark() {
    return recorded().size();
  }

  /** Every call answered since {@code mark}, as the labels the diagram carries. */
  public static List<String> callsSince(int mark) {
    List<String> summarized = new ArrayList<>();
    List<List<String>> lines = recorded();
    for (List<String> fields : lines.subList(Math.min(mark, lines.size()), lines.size())) {
      summarized.add(label(summarize(argv(fields)), fields.getFirst()));
    }
    return List.copyOf(summarized);
  }

  /**
   * The whole argv of the last call whose summary matches, or an empty list.
   *
   * <p>The diagram carries summaries, so an assertion about a <b>flag</b> — the read-only socket
   * mount on a glances run, the canonical 64-hex id on an exec — reads the argv instead.
   */
  public static List<String> argvOf(String summary) {
    List<String> found = List.of();
    for (List<String> fields : recorded()) {
      List<String> argv = argv(fields);
      if (summarize(argv).equals(summary)) {
        found = argv;
      }
    }
    return found;
  }

  /** The label one answered call renders as — what an assertion has to spell. */
  public static String label(String summary, String exitCode) {
    return summary + " -> " + exitCode;
  }

  /** The container name {@code TerminalContainers} derives for a glances session, template-shaped. */
  public static String glancesContainer() {
    return "qits-system-glances-" + StoryTarget.ID;
  }

  // --- the source -------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      List<List<String>> lines = recorded();
      if (harvested > lines.size()) {
        // The file was truncated under us. Start over rather than mis-slice a prefix.
        harvested = 0;
        EDGES.clear();
      }
      for (List<String> fields : lines.subList(harvested, lines.size())) {
        EDGES.add(
            new NetworkEdge(
                KIND, StoryTarget.SERVICE, DOCKER, label(summarize(argv(fields)), fields.getFirst())));
      }
      harvested = lines.size();
      return List.copyOf(EDGES);
    }
  }

  // --- reading the recording ----------------------------------------------------------------------

  /**
   * The recording's complete lines, each split into {@code [exit, arg, arg, …]}.
   *
   * <p>A missing file is an empty recording rather than a failure, and an <b>unterminated tail is
   * dropped</b>: the stand-in appends while this reads, and half a line would shape half an edge.
   * The next read sees it whole.
   */
  private static List<List<String>> recorded() {
    Path log = resultLog();
    if (!Files.isRegularFile(log)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(log, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    List<List<String>> lines = new ArrayList<>();
    for (String line : text.substring(0, lastComplete).split("\n")) {
      List<String> fields = List.of(line.split("\t", -1));
      if (fields.size() >= 2) {
        lines.add(fields);
      }
    }
    return List.copyOf(lines);
  }

  private static List<String> argv(List<String> fields) {
    return fields.subList(1, fields.size());
  }

  /**
   * One argv as the label a reader wants: the docker verb, and the one name it is addressed to.
   *
   * <p>The vocabulary is CLOSED — the library builders {@code reads/}, {@code startup/} and {@code
   * terminal/} call are the complete list of command lines this service will ever run — so this is a table rather than a heuristic, with
   * a fallback that still shows a call nobody expected rather than swallowing it.
   */
  static String summarize(List<String> argv) {
    if (argv.isEmpty()) {
      return DOCKER;
    }
    String command = argv.getFirst();
    return switch (command) {
      case "version", "info" -> command;
      // `system df`, `node ls`, `node inspect X`, `service ps X`, … — the object, the verb, and the
      // reference where the verb takes one. In every one of these the reference is the argument
      // straight after the verb, because that is how the library's builders spell them; `--format`,
      // `--no-trunc` and their values are dropped.
      case "system", "node", "service", "config", "secret", "image", "volume", "network" ->
          command + " " + second(argv) + swarmReference(argv);
      // `container inspect [--format …] <ref>` and `logs --tail N <ref>`: the reference is LAST.
      case "container" -> "container inspect " + template(argv.getLast());
      case "logs" -> "logs " + template(argv.getLast());
      case "ps" -> ps(argv);
      // The boot sweep's and the glances teardown's removal, by the names the argv builder derived.
      case "rm" -> "rm -f" + templatedTail(argv, 2);
      case "pull" -> "pull glances";
      // The two interactive verbs. The shell is authored (`sh` or `bash`) and stays; the container
      // id is the canonical 64-hex one the daemon printed and becomes {digest}.
      case "exec" -> "exec -it " + template(argv.get(argv.size() - 2)) + " " + argv.getLast();
      case "run" -> "run -it --rm --name " + template(nameFlag(argv)) + " glances";
      default -> command;
    };
  }

  /**
   * The reference an object verb takes, or nothing: only {@code inspect} and {@code ps} take one.
   * Two shapes say more (qits-2350, the secrets read's usage pair): {@code service ls -q} keeps its
   * {@code -q}, which is what tells it from the service list, and an {@code inspect --format F a b}
   * names every reference after the template — never the template, whose braces are mermaid
   * syntax.
   */
  private static String swarmReference(List<String> argv) {
    String verb = second(argv);
    if ("ls".equals(verb) && argv.contains("-q")) {
      return " -q";
    }
    if (!("inspect".equals(verb) || "ps".equals(verb)) || argv.size() < 3) {
      return "";
    }
    if ("inspect".equals(verb) && "--format".equals(argv.get(2))) {
      return templatedTail(argv, 4);
    }
    return " " + template(argv.get(2));
  }

  /** Every argument from {@code from} on, templated — {@code rm -f} takes a list of ids. */
  private static String templatedTail(List<String> argv, int from) {
    StringBuilder tail = new StringBuilder();
    for (int index = from; index < argv.size(); index++) {
      tail.append(' ').append(template(argv.get(index)));
    }
    return tail.toString();
  }

  /** The value of {@code --name} — the only part of a glances run a diagram is about. */
  private static String nameFlag(List<String> argv) {
    for (int index = 0; index + 1 < argv.size(); index++) {
      if ("--name".equals(argv.get(index))) {
        return argv.get(index + 1);
      }
    }
    return "";
  }

  /**
   * The four {@code docker ps} shapes this service makes, told apart by intent rather than by their
   * flags — a label carrying {@code --format {{json .}}} would put mermaid syntax in a diagram.
   */
  private static String ps(List<String> argv) {
    for (String argument : argv) {
      if (argument.startsWith("label=")) {
        // The boot sweep, filtered on the owner label's VALUE — which is the whole point of it, so
        // it is the one filter that stays spelled out in full.
        return "ps -aq --filter " + argument;
      }
    }
    return argv.contains("-a") ? "ps -a" : "ps";
  }

  private static String second(List<String> argv) {
    return argv.size() > 1 ? argv.get(1) : "";
  }

  /**
   * A token as a label may carry it: a generated uuid becomes {@code {id}} and a 32+-character hex
   * run {@code {digest}}, wherever in the token they sit — {@code qits-system-glances-<uuid>} is one
   * token with one generated half.
   *
   * <p>The framework's own scrubber would not do it: it rewrites whole PATH segments, and none of
   * these strings has a slash in front of the value. Shaping the label here is what keeps the
   * story's {@code networkHash} from moving on every run.
   */
  private static String template(String token) {
    String templated = UUID.matcher(token).replaceAll(StoryTarget.ID);
    return LONG_HEX.matcher(templated).replaceAll(StoryTarget.DIGEST);
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
