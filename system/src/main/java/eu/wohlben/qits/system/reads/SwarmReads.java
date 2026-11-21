package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
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

  public List<SecretSummary> secrets() {
    return docker.jsonLines(SwarmArgv.secretLs(docker.binary(), DockerCli.JSON_LINES)).stream()
        .map(SecretSummary::from)
        .toList();
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
