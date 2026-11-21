package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.docker.SwarmArgv;
import eu.wohlben.qits.system.docker.CallerValues;
import eu.wohlben.qits.system.docker.DockerCli;
import eu.wohlben.qits.system.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

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

  @Inject DockerCli docker;

  public List<NodeSummary> nodes() {
    return docker.jsonLines(SwarmArgv.nodeLs(docker.binary(), DockerCli.JSON_LINES)).stream()
        .map(NodeSummary::from)
        .toList();
  }

  public NodeDetail node(String ref) {
    CallerValues.requireSwarmRef(NODE, ref);
    return NodeDetail.from(inspectOne(SwarmArgv.nodeInspect(docker.binary(), ref), NODE, ref));
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

  public List<ConfigSummary> configs() {
    return docker.jsonLines(SwarmArgv.configLs(docker.binary(), DockerCli.JSON_LINES)).stream()
        .map(ConfigSummary::from)
        .toList();
  }

  public ConfigDetail config(String ref) {
    CallerValues.requireSwarmRef(CONFIG, ref);
    return ConfigDetail.from(
        inspectOne(SwarmArgv.configInspect(docker.binary(), ref), CONFIG, ref));
  }

  /**
   * Every secret, with the services that use it (qits-2350) — at most three calls, and none of them
   * a {@code secret inspect}: {@code secret ls}, then, only when it listed any, {@code service ls
   * -q} and, only when that listed any, ONE {@code service inspect} over all of them with {@link
   * #SECRET_USAGE}. A secret holds no list of its users; the service specs are where usage lives.
   */
  public List<SecretSummary> secrets() {
    List<JsonNode> secrets =
        docker.jsonLines(SwarmArgv.secretLs(docker.binary(), DockerCli.JSON_LINES));
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
    List<String> ids =
        listed.output() == null
            ? List.of()
            : listed.output().lines().map(String::trim).filter(id -> !id.isEmpty()).toList();
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
    var result = docker.attempt(argv);
    if (!DockerCli.ok(result)) {
      String said = result.output() == null ? "" : result.output().toLowerCase(java.util.Locale.ROOT);
      // Docker's wording across objects: "Error: no such node: x", "service x not found".
      if (said.contains("no such") || said.contains("not found")) {
        throw new NotFoundException("no such " + what + ": " + ref);
      }
      throw docker.unavailable(argv, result);
    }
    // Parse what that call already printed. Calling jsonArray here would fork docker a second time
    // for the same document — and the two answers could differ.
    List<JsonNode> rows = docker.parseArray(argv, result.output());
    if (rows.isEmpty()) {
      throw new NotFoundException("no such " + what + ": " + ref);
    }
    return rows.get(0);
  }
}
