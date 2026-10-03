package eu.wohlben.qits.system.startup;

import eu.wohlben.qits.system.docker.DockerArgv;
import eu.wohlben.qits.system.docker.DockerCli;
import eu.wohlben.qits.system.docker.DockerProcess;
import eu.wohlben.qits.system.terminal.TerminalSessions;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What this service does on the way up: write its own docker credential, prove the daemon is there,
 * reap the terminals a previous life left behind, and fetch the glances image so the first session
 * does not sit through a pull.
 *
 * <p><b>NOTHING HERE IS FATAL.</b> A boot that failed because docker was unreachable would be a
 * service that cannot start on the one machine it exists to look at — and the honest answer to an
 * unreachable daemon is a 503 on a read, not a container that will not come up. Every step below
 * logs and continues, and readiness stays independent of all three.
 */
@ApplicationScoped
public class TerminalBootSweep {

  private static final Logger LOG = Logger.getLogger(TerminalBootSweep.class);

  @Inject DockerCli docker;

  @Inject TerminalSessions sessions;

  /**
   * Called first in {@link #onStart}, which is the whole ordering guarantee: the glances pull below
   * reads the config.json this writes. Not a second observer with a {@code @Priority} — see the
   * writer's own comment.
   */
  @Inject DockerCredentialWriter credentials;

  @ConfigProperty(name = "qits.system.terminals.owner")
  String owner;

  @ConfigProperty(name = "qits.system.glances.pull-at-startup")
  boolean pullAtStartup;

  void onStart(@Observes StartupEvent event) {
    // BEFORE anything spawns docker: the pull in prefetchGlances needs this service's own
    // credential, and DOCKER_CONFIG only points at it once the file exists.
    credentials.write();
    if (!probeDaemon()) {
      // Every later step needs the daemon, and each would log the same failure again.
      return;
    }
    sweepLeftovers();
    prefetchGlances();
  }

  /**
   * Is there a daemon, and can this process reach it?
   *
   * <p>WARN-ONLY, and worth the call anyway: the two failures a deployment actually makes — the
   * socket not mounted and the docker group not added — are invisible until somebody opens the UI
   * and gets a 503. One line in the boot log, naming docker's own words, turns a support question
   * into a fix.
   */
  private boolean probeDaemon() {
    List<String> argv = DockerArgv.version(docker.binary());
    DockerProcess.Result result = docker.attempt(argv);
    if (result.ok()) {
      LOG.infof("Docker daemon reachable, server version %s", result.output().trim());
      return true;
    }
    LOG.warnf(
        "Docker is NOT reachable, so every read will answer 503 and no terminal will start."
            + " Docker said: %s."
            + " Check that /var/run/docker.sock is mounted and that this container is in the"
            + " socket's group.",
        result.output() == null ? "(nothing)" : result.output().trim());
    return false;
  }

  /**
   * Remove the containers a previous life of this service left running.
   *
   * <p>A glances container OUTLIVES the CLI that started it: {@code --rm} fires when the container
   * exits, and killing the client does not exit it. So a service that was killed rather than
   * stopped (an OOM, a `docker kill`, a host reboot mid-session) leaves one holding the host's pid
   * namespace and a socket, with nothing pointing at it. This is the only thing that would ever
   * clean it up.
   *
   * <p>THE FILTER IS THE OWNER LABEL, whose value is this application's name. Two platforms can
   * share one docker daemon, and a sweep that reaped every {@code qits.system.*} container would
   * kill the other platform's live terminals on every restart of this one.
   */
  private void sweepLeftovers() {
    List<String> psArgv = DockerArgv.psByOwner(docker.binary(), owner);
    DockerProcess.Result found = docker.attempt(psArgv);
    if (!found.ok()) {
      LOG.warnf("Could not list leftover terminal containers: %s", found.output());
      return;
    }
    List<String> ids =
        Arrays.stream(found.output().split("\\s+")).filter(id -> !id.isBlank()).toList();
    if (ids.isEmpty()) {
      return;
    }
    LOG.infof("Removing %d terminal container(s) left by a previous run", ids.size());
    docker.bestEffort("removing leftover terminal containers", DockerArgv.rmForce(docker.binary(), ids));
  }

  /**
   * Fetch the glances image ahead of the first session.
   *
   * <p>It runs under the same call timeout as every other docker command, which is deliberately
   * SHORTER than a cold pull of a few hundred megabytes takes. That is not a bug: the point is to
   * warm a mirror that already has the layers, not to hold the boot open while one is fetched from
   * upstream. A pull that does not finish in time is logged and the first terminal shows docker's
   * own pull progress instead — which is the correct place for a person to watch it.
   */
  private void prefetchGlances() {
    if (!pullAtStartup) {
      return;
    }
    String image = sessions.glancesImage();
    DockerProcess.Result result = docker.attempt(DockerArgv.pull(docker.binary(), image));
    if (result.ok()) {
      LOG.infof("Glances image %s is present", image);
    } else {
      LOG.warnf(
          "Could not pre-pull %s (%s). The first host terminal will pull it inline."
              + " If this says 401, this service's docker config.json (its own idp client, or an"
              + " older spec's mounted volume) is missing the mirror credential.",
          image, result.output() == null ? "no output" : result.output().trim());
    }
  }
}
