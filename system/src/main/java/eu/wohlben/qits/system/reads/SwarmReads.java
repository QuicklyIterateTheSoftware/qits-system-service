package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.docker.SwarmArgv;
import eu.wohlben.qits.system.docker.CallerValues;
import eu.wohlben.qits.system.docker.DockerCli;
import eu.wohlben.qits.system.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The swarm: nodes, services, configs and secrets, read from the manager this service runs on.
 *
 * <p>These are the CLUSTER-WIDE reads, and unlike the node-scoped half they are not limited to one
 * machine — a manager knows the whole swarm, so a three-node cluster lists three nodes here even
 * though only one of them can be shelled into.
 *
 * <p><b>A read on a worker fails, and it fails honestly.</b> `docker node ls` on a non-manager
 * answers "This node is not a swarm manager", which {@code DockerCli} turns into a 503 carrying
 * that sentence. There is no fallback to an empty list: an empty swarm and an unreadable one are
 * different facts and must not look the same.
 *
 * <p><b>An empty inspect is a 404.</b> Docker exits non-zero for an unknown reference, so a missing
 * object arrives as a failed call — but the message says "no such", not "cannot connect". The
 * distinction is made here rather than in {@code DockerCli} because only the caller knows which
 * reference it asked about.
 */
@ApplicationScoped
public class SwarmReads {

  /** What a refusal and a 404 name: the object a reference was meant to point at. */
  private static final String NODE = "node";

  private static final String SERVICE = "service";

  private static final String CONFIG = "config";

  /**
   * What the secrets read asks of each service: its name and its secret references ({@code
   * SecretID}, {@code SecretName}, {@code File}), one JSON object per line — and NOTHING ELSE of the
   * spec. A plain {@code service inspect} carries the environment, which is where a deployment's
   * injected password lives; this template keeps every env value out of the process output, not
   * only out of the answer. A service mounting no secret prints {@code "secrets":null}.
   */
  static final String SECRET_USAGE =
      "{\"name\":{{json .Spec.Name}},\"secrets\":{{json .Spec.TaskTemplate.ContainerSpec.Secrets}}}";

  /** What a list read asks {@code secret ls} and {@code config ls} for: the ids alone, one a line. */
  static final String ID_ONLY = "{{.ID}}";

  /**
   * What the secrets read asks {@code secret inspect} for: each secret's list row, one JSON object
   * per line — id, name, driver, the two dates and the labels (qits-2487). {@code ls} cannot give
   * the dates: its formatter humanises them ({@code "2 months ago"}) whatever the template, and only
   * an inspect carries the instants. A secret's document holds no value to print, so the template
   * keeps the output to the row rather than keeping anything back.
   */
  static final String SECRET_ROW =
      "{\"ID\":{{json .ID}},\"Name\":{{json .Spec.Name}},"
          + "\"Driver\":{{if .Spec.Driver}}{{json .Spec.Driver.Name}}{{else}}\"\"{{end}},"
          + "\"CreatedAt\":{{json .CreatedAt}},\"UpdatedAt\":{{json .UpdatedAt}},"
          + "\"Labels\":{{json .Spec.Labels}}}";

  /**
   * What the configs read asks {@code config inspect} for: each config's list row, one JSON object
   * per line (qits-2487). Never {@code .Spec.Data}: a list shows no content, so no config's content
   * reaches the process output of one — the detail door is where a config is read.
   */
  static final String CONFIG_ROW =
      "{\"ID\":{{json .ID}},\"Name\":{{json .Spec.Name}},"
          + "\"CreatedAt\":{{json .CreatedAt}},\"UpdatedAt\":{{json .UpdatedAt}},"
          + "\"Labels\":{{json .Spec.Labels}}}";

  /** Docker's own list order, which an inspect over the ids need not keep: by name. */
  private static final Comparator<JsonNode> BY_NAME =
      Comparator.comparing(
          row -> Json.text(row, "Name"), Comparator.nullsLast(Comparator.naturalOrder()));

  @Inject DockerCli docker;

  @Inject HostReads hosts;

  public List<NodeSummary> nodes() {
    return docker.jsonLines(SwarmArgv.nodeLs(docker.binary(), DockerCli.JSON_LINES)).stream()
        .map(NodeSummary::from)
        .toList();
  }

  /**
   * A node's document, with {@code self} decided the way {@code NodeReads.requireLocal} decides it:
   * by the id the local daemon's {@code info} reports, which is the only source that knows.
   */
  public NodeDetail node(String ref) {
    CallerValues.requireSwarmRef(NODE, ref);
    JsonNode node = inspectOne(SwarmArgv.nodeInspect(docker.binary(), ref), NODE, ref);
    String local = hosts.localNodeId();
    return NodeDetail.from(node, local != null && local.equals(Json.text(node, "ID")));
  }

  /**
   * What swarm has scheduled on one node, newest first per slot as docker prints it, shut-down
   * history included. {@code --no-trunc} for the same reason as a service's tasks: the error
   * column is the point. One call: an unknown node is docker's own "no such node", mapped like an
   * inspect's, rather than an inspect forked first to ask.
   */
  public List<TaskSummary> nodeTasks(String ref) {
    CallerValues.requireSwarmRef(NODE, ref);
    List<String> argv = SwarmArgv.nodePs(docker.binary(), ref, true, DockerCli.JSON_LINES);
    return docker.parseLines(argv, answered(argv, NODE, ref)).stream()
        .map(TaskSummary::from)
        .toList();
  }

  public List<ServiceSummary> services() {
    return docker.jsonLines(SwarmArgv.serviceLs(docker.binary(), List.of(), DockerCli.JSON_LINES)).stream()
        .map(ServiceSummary::from)
        .toList();
  }

  /**
   * A service with its tasks — two calls, because `inspect` does not carry them. The task list is
   * {@code --no-trunc} because the default truncates the error column, and a failed task's error is
   * the entire reason anybody opens this view. {@code SwarmArgv.servicePs} does not belt its
   * reference, so the check above it is the one that holds for both calls.
   */
  public ServiceDetail service(String ref) {
    CallerValues.requireSwarmRef(SERVICE, ref);
    JsonNode node = inspectOne(SwarmArgv.serviceInspect(docker.binary(), ref), SERVICE, ref);
    List<TaskSummary> tasks =
        docker
            .jsonLines(
                SwarmArgv.servicePs(docker.binary(), ref, List.of(), true, DockerCli.JSON_LINES))
            .stream()
            .map(TaskSummary::from)
            .toList();
    return ServiceDetail.from(node, tasks);
  }

  /**
   * Every config, by name — {@code config ls} for the ids, then, only when it listed any, ONE {@code
   * config inspect} over all of them with {@link #CONFIG_ROW}, for the instants {@code ls} only
   * prints as an age (qits-2487). A config removed in between is the secrets' race, retried the
   * same way ({@link #rows}).
   */
  public List<ConfigSummary> configs() {
    return rows(
            SwarmArgv.configLs(docker.binary(), ID_ONLY),
            ids -> SwarmArgv.configInspect(docker.binary(), ids, CONFIG_ROW))
        .stream()
        .map(ConfigSummary::from)
        .toList();
  }

  public ConfigDetail config(String ref) {
    CallerValues.requireSwarmRef(CONFIG, ref);
    return ConfigDetail.from(
        inspectOne(SwarmArgv.configInspect(docker.binary(), ref), CONFIG, ref));
  }

  /**
   * Every secret by name, with the services that use it (qits-2350) — at most four calls: {@code
   * secret ls} for the ids, then, only when it listed any, ONE {@code secret inspect} over all of
   * them with {@link #SECRET_ROW} for the instants {@code ls} only prints as an age (qits-2487),
   * then the usage pair — {@code service ls -q} and, only when that listed any, ONE {@code service
   * inspect} over all of them with {@link #SECRET_USAGE}. A secret holds no list of its users; the
   * service specs are where usage lives. {@code secret inspect} prints no value: swarm returns a
   * secret's data to nobody, so its document has none to print.
   */
  public List<SecretSummary> secrets() {
    List<JsonNode> secrets =
        rows(
            SwarmArgv.secretLs(docker.binary(), ID_ONLY),
            ids -> SwarmArgv.secretInspect(docker.binary(), ids, SECRET_ROW));
    if (secrets.isEmpty()) {
      return List.of();
    }
    // A service removed between the `ls` and the `inspect` makes docker exit non-zero for the
    // whole inspect. That race is worth one more pair; a second failure is the 503 of any other.
    List<JsonNode> usage = secretUsage(false);
    if (usage == null) {
      usage = secretUsage(true);
    }
    List<JsonNode> services = usage;
    return secrets.stream().map(secret -> SecretSummary.from(secret, services)).toList();
  }

  /**
   * The list rows of one object kind, by name: an {@code ls} printing the ids, then ONE {@code
   * inspect} over all of them printing a row each — {@code List.of()} and no inspect when the
   * {@code ls} listed none. An object removed between the two makes docker exit non-zero for the
   * whole inspect; that race is worth one more pair, and a second failure is the 503 of any other
   * (a timeout is not retried).
   */
  private List<JsonNode> rows(List<String> ls, Function<List<String>, List<String>> inspect) {
    List<JsonNode> rows = rowsOnce(ls, inspect, false);
    if (rows == null) {
      rows = rowsOnce(ls, inspect, true);
    }
    return rows.stream().sorted(BY_NAME).toList();
  }

  /** One {@code ls} + {@code inspect} pair; null when it failed and {@code last} is false. */
  private List<JsonNode> rowsOnce(
      List<String> ls, Function<List<String>, List<String>> inspect, boolean last) {
    ContainerProcess.Result listed = docker.attempt(ls);
    if (!DockerCli.ok(listed)) {
      return failed(ls, listed, last);
    }
    List<String> ids = ids(listed);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<String> argv = inspect.apply(ids);
    ContainerProcess.Result inspected = docker.attempt(argv);
    if (!DockerCli.ok(inspected)) {
      return failed(argv, inspected, last);
    }
    return docker.parseLines(argv, inspected.output());
  }

  /** The ids an {@code ls} printed one a line, blanks dropped. */
  private static List<String> ids(ContainerProcess.Result listed) {
    return listed.output() == null
        ? List.of()
        : listed.output().lines().map(String::trim).filter(id -> !id.isEmpty()).toList();
  }

  /**
   * One {@code service ls -q} + {@code service inspect} pair: every service's name and secret
   * references, one JSON object per line. Null when the pair failed and {@code last} is false — the
   * caller's cue to try once more; a timeout is not retried, it would only cost the deadline again.
   */
  private List<JsonNode> secretUsage(boolean last) {
    List<String> ls = SwarmArgv.serviceLsQuiet(docker.binary(), List.of());
    ContainerProcess.Result listed = docker.attempt(ls);
    if (!DockerCli.ok(listed)) {
      return failed(ls, listed, last);
    }
    List<String> ids = ids(listed);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<String> inspect = SwarmArgv.serviceInspect(docker.binary(), ids, SECRET_USAGE);
    ContainerProcess.Result inspected = docker.attempt(inspect);
    if (!DockerCli.ok(inspected)) {
      return failed(inspect, inspected, last);
    }
    return docker.parseLines(inspect, inspected.output());
  }

  private List<JsonNode> failed(List<String> argv, ContainerProcess.Result result, boolean last) {
    if (last || result.timedOut()) {
      throw docker.unavailable(argv, result);
    }
    return null;
  }

  /**
   * Inspect one object, turning docker's own "no such X" into a 404 and every other failure into
   * the 503 {@code DockerCli} already builds.
   */
  private JsonNode inspectOne(List<String> argv, String what, String ref) {
    // Parse what that call already printed. Calling jsonArray here would fork docker a second time
    // for the same document — and the two answers could differ.
    List<JsonNode> rows = docker.parseArray(argv, answered(argv, what, ref));
    if (rows.isEmpty()) {
      throw new NotFoundException("no such " + what + ": " + ref);
    }
    return rows.get(0);
  }

  /**
   * Run one call on one reference and hand back what it printed, turning docker's own "no such X"
   * into a 404 and every other failure into the 503 {@code DockerCli} already builds.
   */
  private String answered(List<String> argv, String what, String ref) {
    var result = docker.attempt(argv);
    if (!DockerCli.ok(result)) {
      String said = result.output() == null ? "" : result.output().toLowerCase(java.util.Locale.ROOT);
      // Docker's wording across objects: "Error: no such node: x", "service x not found".
      if (said.contains("no such") || said.contains("not found")) {
        throw new NotFoundException("no such " + what + ": " + ref);
      }
      throw docker.unavailable(argv, result);
    }
    return result.output();
  }
}
