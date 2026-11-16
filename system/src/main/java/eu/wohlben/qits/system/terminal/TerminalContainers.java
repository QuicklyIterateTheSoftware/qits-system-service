package eu.wohlben.qits.system.terminal;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.InteractiveArgv;
import eu.wohlben.qits.containers.driver.spec.InteractiveRunSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;

/**
 * This service's own vocabulary for the containers its terminals run in: the names, the labels, the
 * environment and the sandbox it asks for. The argv is qits-containers-driver's ({@link
 * InteractiveArgv}, qits-2211), whose flag order is the one this service used to spell itself; what
 * goes INTO it is decided here.
 *
 * <p><b>The spec IS the sandbox.</b> A flag lost here is invisible until it is invisible in
 * production; the suite's fake docker matches the whole argv, so a dropped one fails it.
 */
public final class TerminalContainers {

  /** The label namespace this service marks its own containers with. */
  public static final String LABEL_SESSION = "qits.system.session";

  public static final String LABEL_KIND = "qits.system.kind";

  /**
   * The label the boot sweep filters on. Its VALUE is the application name, so two platforms on one
   * daemon can each reap their own leftovers and neither reaps the other's.
   */
  public static final String LABEL_OWNER = "qits.system.owner";

  /** What every terminal child is told it is running under, so curses programs draw in colour. */
  public static final String TERM = "xterm-256color";

  /** The variable a shell's environment carries so a {@code ps} can trace it to its session. */
  public static final String SESSION_ENV = "QITS_SYSTEM_SESSION";

  private TerminalContainers() {}

  /** The container name a glances session runs under — derived, so the teardown can find it. */
  public static String glancesContainerName(UUID sessionId) {
    return "qits-system-glances-" + sessionId;
  }

  /** The labels a glances container carries, in the order they are rendered. */
  public static SequencedMap<String, String> glancesLabels(UUID sessionId, String owner) {
    SequencedMap<String, String> labels = new LinkedHashMap<>();
    labels.put(LABEL_SESSION, sessionId.toString());
    labels.put(LABEL_KIND, "glances");
    labels.put(LABEL_OWNER, owner);
    return labels;
  }

  /**
   * The host monitor, as {@code docker run --rm -it}.
   *
   * <p>Every option, and why:
   *
   * <ul>
   *   <li>{@code --rm} (always, {@link InteractiveArgv#run}) — a monitor is not a record; when it
   *       exits there is nothing to read back. It only fires on CONTAINER exit, which is why an
   *       explicit teardown still does {@code rm -f} by the name above.
   *   <li>the name + three labels — so the container can be found by a teardown that has only the
   *       session id, and by a boot sweep that has only the owner.
   *   <li>{@code --pid host} — glances reports PROCESSES. In its own pid namespace it would show
   *       one, itself, which is a monitor of nothing.
   *   <li>{@code --network host} — upstream's own recipe. Glances reads network counters from its
   *       netns, so in its own it reports the container's veth rather than the host's interfaces.
   *       Nothing is published: in terminal mode glances runs no listener.
   *   <li>the docker socket, READ-ONLY — the {@code -full} image variant carries the docker plugin,
   *       which is what makes the container list panel work. Read-only because a monitor never
   *       needs to write to it, and this is a container we hand a socket to.
   *   <li>{@code --cap-drop ALL} and {@code --security-opt no-new-privileges} — it reads {@code
   *       /proc} and a socket; it needs no capability at all.
   *   <li>{@code --memory}/{@code --memory-swap} equal, {@code --pids-limit} — a monitor that leaks
   *       must not take the host with it, and equal memory and memory-swap is what turns off swap
   *       rather than granting unlimited swap.
   *   <li>{@code --oom-score-adj 500} — if the host runs out, the kernel takes this before it takes
   *       a platform service. The same doctrine as the spawned-workload scores elsewhere on the
   *       platform (ci 1000, agents 800, workspaces 600); a monitor sits above all of them.
   * </ul>
   *
   * @param extraArgs glances' own flags from config, appended after the image — empty by default
   */
  public static InteractiveRunSpec glances(
      String image, List<String> extraArgs, UUID sessionId, String owner) {
    InteractiveRunSpec.Builder spec =
        InteractiveRunSpec.builder(glancesContainerName(sessionId), image);
    glancesLabels(sessionId, owner).forEach(spec::label);
    return spec.hostPid(true)
        .network("host")
        .bind(DockerArgv.DOCKER_SOCKET + ":" + DockerArgv.DOCKER_SOCKET + ":ro")
        .capDropAll(true)
        .noNewPrivileges(true)
        .memory("512m")
        .memorySwap("512m")
        .pidsLimit(256L)
        .oomScoreAdj(500)
        .env("TERM", TERM)
        .args(extraArgs)
        .build();
  }

  /** The glances run's argv. */
  public static List<String> runGlances(
      String binary, String image, List<String> extraArgs, UUID sessionId, String owner) {
    return InteractiveArgv.run(binary, glances(image, extraArgs, sessionId, owner));
  }

  /**
   * A shell inside one of this node's containers, as {@code docker exec -it}.
   *
   * <p>{@code containerId} is the CANONICAL 64-hex id the daemon printed when the reference was
   * resolved — never the string the caller sent; the library's {@code requireFullId} is the belt on
   * that. {@value #SESSION_ENV} is in the child's environment so that a shell somebody finds later
   * in a {@code ps} can be traced back to the session that opened it.
   */
  public static List<String> execShell(
      String binary, String containerId, ExecShell shell, UUID sessionId) {
    SequencedMap<String, String> env = new LinkedHashMap<>();
    env.put("TERM", TERM);
    env.put(SESSION_ENV, sessionId.toString());
    return InteractiveArgv.exec(binary, containerId, env, List.of(shell.binary()));
  }

  /** The boot sweep's filter: every container this owner labelled, running or not. */
  public static Map<String, String> ownerFilter(String owner) {
    return Map.of(LABEL_OWNER, owner);
  }
}
