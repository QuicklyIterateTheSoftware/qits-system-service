package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.SwarmArgv;
import eu.wohlben.qits.system.docker.CallerValues;
import eu.wohlben.qits.system.docker.DockerCli;
import eu.wohlben.qits.system.error.ConflictException;
import eu.wohlben.qits.system.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The node-scoped half: what is actually on THIS machine — containers, images, volumes, networks,
 * and the logs of a container.
 *
 * <p><b>ONE NODE, AND THE OTHERS ARE A 409.</b> These reads go to the local docker daemon, which
 * knows only its own containers; a swarm manager can list every SERVICE in the cluster but not
 * another machine's containers. Reaching a second node would need an agent on it, which v1 does not
 * have. So a node-scoped read for any other node answers 409 {@code NODE_REMOTE} rather than an
 * empty list — "there is nothing here" and "I cannot see over there" are different facts, and only
 * one of them means the operator should go and look somewhere else.
 *
 * <p>{@value #LOCAL_ALIAS} is accepted as a name for this node in every path, so a client can ask
 * without knowing the id and a daemon that is not in a swarm (which has no node id at all) is still
 * readable.
 */
@ApplicationScoped
public class NodeReads {

  /** The name any client may use for "the node this service runs on". */
  public static final String LOCAL_ALIAS = "local";

  /** The code a client branches on when it asked about a node this service cannot reach. */
  public static final String NODE_REMOTE = "NODE_REMOTE";

  @Inject DockerCli docker;

  @Inject HostReads hosts;

  /** The largest log tail a caller may ask for. Beyond this it is a log store's job, not a panel's. */
  @ConfigProperty(name = "qits.system.logs.max-tail")
  int maxTail;

  /** How much of a log read is kept. The tail, because that is the end an operator asked for. */
  @ConfigProperty(name = "qits.system.logs.max-chars")
  int maxLogChars;

  /**
   * The three facts the exec path needs before it opens a terminal, in one call: the canonical id,
   * whether the container is running, and its name. A format string rather than a full inspect
   * because this runs on the request path of every terminal open.
   */
  static final String PROBE_FORMAT = "{{.Id}}|{{.State.Running}}|{{.Name}}";

  /** What the exec path learns before it opens a terminal. */
  public record ContainerTarget(String id, String name, boolean running) {}

  /**
   * Refuse a read aimed at another machine.
   *
   * @throws ConflictException with code {@code NODE_REMOTE} when {@code nodeId} is not this node
   */
  public void requireLocal(String nodeId) {
    if (LOCAL_ALIAS.equalsIgnoreCase(nodeId)) {
      return;
    }
    String local = hosts.localNodeId();
    if (local != null && local.equals(nodeId)) {
      return;
    }
    throw new ConflictException(NODE_REMOTE, "only the local node is reachable in this version");
  }

  public List<ContainerSummary> containers(String nodeId, boolean all) {
    requireLocal(nodeId);
    // --no-trunc so an id is the id; `all` adds the stopped ones, which is what an
    // operator wants after something died.
    return docker.jsonLines(DockerArgv.ps(docker.binary(), all, true, DockerCli.JSON_LINES)).stream()
        .map(ContainerSummary::from)
        .toList();
  }

  public ContainerDetail container(String nodeId, String ref) {
    requireLocal(nodeId);
    CallerValues.requireContainerRef(ref);
    List<String> argv = DockerArgv.containerInspect(docker.binary(), ref, null);
    ContainerProcess.Result result = docker.attempt(argv);
    if (!DockerCli.ok(result)) {
      if (saysNoSuchContainer(result.output())) {
        throw new NotFoundException("no such container: " + ref);
      }
      throw docker.unavailable(argv, result);
    }
    List<JsonNode> rows = docker.parseArray(argv, result.output());
    if (rows.isEmpty()) {
      throw new NotFoundException("no such container: " + ref);
    }
    return ContainerDetail.from(rows.get(0));
  }

  /** A container's recent output. Timestamps off: the lines carry the workload's own format. */
  public LogChunk logs(String nodeId, String ref, int tail) {
    requireLocal(nodeId);
    CallerValues.requireContainerRef(ref);
    int bounded = CallerValues.requireTail(tail, maxTail);
    List<String> argv = DockerArgv.logsTailByReference(docker.binary(), ref, bounded);
    ContainerProcess.Result result = docker.attempt(argv, maxLogChars);
    if (!DockerCli.ok(result)) {
      if (saysNoSuchContainer(result.output())) {
        throw new NotFoundException("no such container: " + ref);
      }
      throw docker.unavailable(argv, result);
    }
    return new LogChunk(result.output(), result.truncated());
  }

  public List<ImageSummary> images(String nodeId) {
    requireLocal(nodeId);
    return docker.jsonLines(DockerArgv.imageLs(docker.binary(), DockerCli.JSON_LINES)).stream()
        .map(ImageSummary::from)
        .toList();
  }

  public List<VolumeSummary> volumes(String nodeId) {
    requireLocal(nodeId);
    return docker.jsonLines(DockerArgv.volumeLs(docker.binary(), DockerCli.JSON_LINES)).stream()
        .map(VolumeSummary::from)
        .toList();
  }

  public List<NetworkSummary> networks(String nodeId) {
    requireLocal(nodeId);
    return docker
        .jsonLines(SwarmArgv.networkLs(docker.binary(), List.of(), DockerCli.JSON_LINES))
        .stream()
        .map(NetworkSummary::from)
        .toList();
  }

  /**
   * Resolve a container reference for the exec path, and refuse it unless a shell could actually
   * run in it.
   *
   * <p>THIS IS WHAT KEEPS THE CALLER'S STRING OUT OF THE TERMINAL ARGV. The reference is validated
   * as a reference, handed to `docker container inspect --format '{{.Id}}|{{.State.Running}}|{{.Name}}'`,
   * and what comes back is the canonical id — which is the only thing the exec command line ever
   * carries.
   *
   * @throws NotFoundException when the daemon knows no such container
   * @throws ConflictException when it exists but is not running (`docker exec` would fail with a
   *     message the browser could not act on)
   */
  public ContainerTarget requireRunningContainer(String ref) {
    CallerValues.requireContainerRef(ref);
    List<String> argv = DockerArgv.containerInspect(docker.binary(), ref, PROBE_FORMAT);
    ContainerProcess.Result result = docker.attempt(argv);
    if (!DockerCli.ok(result)) {
      if (saysNoSuchContainer(result.output())) {
        throw new NotFoundException("no such container: " + ref);
      }
      throw docker.unavailable(argv, result);
    }
    String[] parts = result.output().trim().split("\\|", -1);
    if (parts.length < 3) {
      throw new NotFoundException("no such container: " + ref);
    }
    String id = CallerValues.requireFullId(parts[0].trim());
    boolean running = Boolean.parseBoolean(parts[1].trim());
    String name = parts[2].trim();
    if (name.startsWith("/")) {
      name = name.substring(1);
    }
    if (!running) {
      throw new ConflictException("container " + name + " is not running");
    }
    return new ContainerTarget(id, name, true);
  }

  /**
   * Docker's own wording for a reference it does not know. Matched rather than assumed from the
   * exit code, because every failure of `inspect` exits 1 — including the socket being missing,
   * which must stay a 503.
   */
  private static boolean saysNoSuchContainer(String output) {
    if (output == null) {
      return false;
    }
    String said = output.toLowerCase(Locale.ROOT);
    return said.contains("no such container") || said.contains("no such object");
  }
}
