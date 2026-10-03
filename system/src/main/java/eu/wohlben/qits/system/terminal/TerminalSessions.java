package eu.wohlben.qits.system.terminal;

import eu.wohlben.qits.system.docker.DockerArgv;
import eu.wohlben.qits.system.docker.DockerCli;
import eu.wohlben.qits.system.docker.DockerClientConfig;
import eu.wohlben.qits.system.docker.TerminalArgv;
import eu.wohlben.qits.system.error.ConflictException;
import eu.wohlben.qits.system.error.DockerUnavailableException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-memory registry of live terminals — the service's only state, and it is meant to be lost
 * on a restart (the browser reconnects, is told the session is gone, and opens a new one).
 *
 * <p>Adapted from qits-projects' {@code RemoteLoginSessions}. Three things differ, and each is
 * deliberate:
 *
 * <ul>
 *   <li><b>Create and attach are separate.</b> There a session was spawned by the WebSocket's first
 *       attach; here a terminal is a REST resource — POST creates it and answers an id, the socket
 *       attaches to that id. That is what lets the client list what is running, show it in the UI
 *       before any socket exists, and reconnect to it by address.
 *   <li><b>The linger window is armed AT CREATION</b>, not only on the last detach. A POST whose
 *       browser never connects — a lost tab, a failed upgrade — would otherwise leave a container
 *       running forever with nothing pointing at it.
 *   <li><b>GLANCES is find-or-create.</b> There is one host, so a second request for a host monitor
 *       is the same monitor: the caller gets the live one back (and the controller answers 200
 *       rather than 201). EXEC is not — two shells in one container is an ordinary thing to want.
 *   <li><b>GLANCES lingers for seconds, EXEC for a minute.</b> A host monitor has nothing in it to
 *       come back to, so the last viewer leaving the page must stop it — see {@code glances-linger}.
 *       A shell has a working directory, a history and a half-typed command, so it keeps the full
 *       window and survives a reload.
 * </ul>
 *
 * <p><b>Routing.</b> {@link #attach} returns a {@link Handle} bound to the exact session, and the
 * socket drives input/resize/detach through it — so a stale connection whose close is still in
 * flight can never deliver keystrokes into a session created after it.
 */
@ApplicationScoped
public class TerminalSessions {

  private static final Logger LOG = Logger.getLogger(TerminalSessions.class);

  /** The terminal's size until the browser's first resize frame reports the real one. */
  private static final int INITIAL_COLUMNS = 80;

  private static final int INITIAL_ROWS = 24;

  @ConfigProperty(name = "qits.system.terminals.linger")
  Duration linger;

  /** The last-detach window for GLANCES: short, so leaving the Overview stops the host monitor. */
  @ConfigProperty(name = "qits.system.terminals.glances-linger")
  Duration glancesLinger;

  @ConfigProperty(name = "qits.system.terminals.max-sessions")
  int maxSessions;

  @ConfigProperty(name = "qits.system.terminals.scrollback-bytes")
  int scrollbackBytes;

  /**
   * The label value that marks this service's own containers. Two platforms can share one docker
   * daemon, and each must reap only its own leftovers — see {@code TerminalBootSweep}.
   */
  @ConfigProperty(name = "qits.system.terminals.owner")
  String owner;

  @ConfigProperty(name = "qits.system.glances.image-repo")
  String glancesRepo;

  @ConfigProperty(name = "qits.system.glances.image-version")
  String glancesVersion;

  /** Extra flags for glances itself, appended after the image. Empty unless a deployment sets it. */
  @ConfigProperty(name = "qits.system.glances.args")
  Optional<List<String>> glancesArgs;

  @Inject DockerCli docker;

  /** Live sessions, oldest first, keyed by their id. */
  private final Map<UUID, TerminalSession> sessions = new LinkedHashMap<>();

  /** The current linger schedule per session: an identity token plus its future, for cancelling. */
  private final Map<UUID, Object> lingerTokens = new LinkedHashMap<>();

  private final Map<UUID, ScheduledFuture<?>> lingerFutures = new LinkedHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "terminal-linger");
            thread.setDaemon(true);
            return thread;
          });

  /** What a create did: the session, and whether it is new (201) or the live glances one (200). */
  public record Created(TerminalSession session, boolean fresh) {}

  /**
   * A per-connection routing handle: everything the socket does after attach goes through the exact
   * session it attached to, never an id re-lookup that could resolve to a newer one.
   */
  public final class Handle {
    private final TerminalSession session;
    private final TerminalOutputSink sink;

    private Handle(TerminalSession session, TerminalOutputSink sink) {
      this.session = session;
      this.sink = sink;
    }

    public UUID sessionId() {
      return session.id();
    }

    public void input(byte[] data) {
      session.input(data);
    }

    public void resize(int cols, int rows) {
      session.resize(cols, rows);
    }

    /** Detach this connection; re-arms the linger backstop if it was the session's last client. */
    public void detach() {
      detachClient(session, sink);
    }
  }

  /**
   * Start a terminal.
   *
   * @throws ConflictException when the session limit is reached
   * @throws DockerUnavailableException when the PTY or the child could not be started
   */
  public Created create(TerminalLaunch launch, String principal) {
    TerminalSession session;
    synchronized (this) {
      if (launch.kind() == TerminalKind.GLANCES) {
        Optional<TerminalSession> live =
            sessions.values().stream()
                .filter(s -> s.kind() == TerminalKind.GLANCES && s.isAlive())
                .findFirst();
        if (live.isPresent()) {
          return new Created(live.get(), false);
        }
      }
      if (sessions.size() >= maxSessions) {
        throw new ConflictException(
            "the terminal limit of "
                + maxSessions
                + " is reached; close one before opening another");
      }
      session = spawn(launch, principal);
      sessions.put(session.id(), session);
      // Armed at creation: a POST whose browser never connects must not leave a container running.
      // The FULL window even for glances — nobody has attached yet, so there is no "left the page"
      // to react to, only a browser that may still be finishing its upgrade.
      armLinger(session.id(), linger);
    }
    session.startReader();
    LOG.infof("Opened %s terminal %s for %s", launch.kind(), session.id(), principal);
    return new Created(session, true);
  }

  /**
   * Attach a sink to a live session, replaying its scrollback first.
   *
   * @return the handle, or empty when no session has that id (the socket says so and closes 1000)
   */
  public Optional<Handle> attach(UUID id, TerminalOutputSink sink, IntConsumer onSessionEnd) {
    TerminalSession session;
    synchronized (this) {
      session = sessions.get(id);
      if (session == null) {
        return Optional.empty();
      }
      cancelLinger(id);
    }
    // Replay (a blocking write of up to the ring size) happens OUTSIDE the registry monitor so one
    // slow client cannot stall every other session's open, detach and end.
    session.attach(sink, onSessionEnd);
    // The connection may have closed during the blocking attach (the socket's onClose would then
    // have found no handle to detach): reconcile now so the linger backstop still arms.
    if (!sink.isOpen()) {
      detachClient(session, sink);
    }
    return Optional.of(new Handle(session, sink));
  }

  /** Every live session, oldest first. */
  public synchronized List<TerminalSession> list() {
    return new ArrayList<>(sessions.values())
        .stream().sorted(Comparator.comparing(TerminalSession::createdAt)).toList();
  }

  public synchronized Optional<TerminalSession> get(UUID id) {
    return Optional.ofNullable(sessions.get(id));
  }

  /**
   * End a session now.
   *
   * @return false when no session has that id
   */
  public boolean terminate(UUID id) {
    TerminalSession session;
    synchronized (this) {
      session = sessions.remove(id);
      cancelLinger(id);
    }
    if (session == null) {
      return false;
    }
    endSession(session, "asked to");
    return true;
  }

  // --- spawning ---------------------------------------------------------------------------------

  private TerminalSession spawn(TerminalLaunch launch, String principal) {
    UUID id = UUID.randomUUID();
    List<String> argv =
        switch (launch) {
          case TerminalLaunch.Glances ignored ->
              TerminalArgv.runGlances(
                  docker.binary(), glancesImage(), glancesArgs.orElse(List.of()), id, owner);
          case TerminalLaunch.Exec exec ->
              TerminalArgv.execShell(docker.binary(), exec.containerId(), exec.shell(), id);
        };
    ForeignPty pty;
    try {
      pty = ForeignPty.open(INITIAL_COLUMNS, INITIAL_ROWS);
    } catch (IOException e) {
      throw new DockerUnavailableException("could not allocate a terminal: " + e.getMessage());
    }
    try {
      ProcessBuilder builder = TerminalProcesses.terminalProcess(pty.slavePath(), argv);
      // TERM is what makes the far side draw in colour. DOCKER_CONFIG points at the config.json
      // this service wrote from its own idp client (qits-879) — the mirror credential the glances
      // pull needs — but ONLY when that file exists. Otherwise it is inherited from this process,
      // where an older deployment spec points it at the mounted config volume; uid 1001 has no HOME
      // for the CLI to fall back to either way.
      builder.environment().put("TERM", TerminalArgv.TERM);
      DockerClientConfig.applyTo(builder.environment(), docker.configDir());
      Process process = builder.start();
      return new TerminalSession(
          id,
          launch,
          principal,
          process,
          pty,
          scrollbackBytes,
          exitCode -> onSessionFinished(id, exitCode));
    } catch (IOException e) {
      // The terminal was allocated before the child; a failed spawn must not leak its master fd.
      pty.close();
      throw new DockerUnavailableException("could not start the terminal: " + e.getMessage());
    }
  }

  /** The image a glances terminal runs, as a repo + version pair the deployment can move apart. */
  public String glancesImage() {
    return glancesRepo + ":" + glancesVersion;
  }

  // --- lifecycle --------------------------------------------------------------------------------

  /** The child exited on its own: drop the session. Called from the session's reader thread. */
  private void onSessionFinished(UUID id, int exitCode) {
    synchronized (this) {
      sessions.remove(id);
      cancelLinger(id);
    }
    LOG.infof("Terminal %s ended (code %d)", id, exitCode);
  }

  /** Detach a client; once none remains, the linger timer arms and ends the session when it fires. */
  private synchronized void detachClient(TerminalSession session, TerminalOutputSink sink) {
    int remaining = session.detach(sink);
    if (remaining == 0 && session.isAlive() && sessions.containsKey(session.id())) {
      armLinger(session.id(), detachLinger(session.kind()));
    }
  }

  /**
   * How long an unattended session waits after its LAST client left.
   *
   * <p>Glances gets the short window: the operator navigated away from the Overview, and a host
   * monitor holds nothing worth coming back to, so it must stop by itself. A shell holds a working
   * directory, a history and a half-typed command, so it keeps the full window and outlives a
   * reload.
   */
  private Duration detachLinger(TerminalKind kind) {
    return kind == TerminalKind.GLANCES ? glancesLinger : linger;
  }

  /** Arm (or re-arm) the linger backstop over {@code window}. The caller holds the monitor. */
  private void armLinger(UUID id, Duration window) {
    cancelLinger(id);
    Object token = new Object();
    lingerTokens.put(id, token);
    lingerFutures.put(
        id,
        scheduler.schedule(
            () -> terminateIfUnattended(id, token, window),
            window.toMillis(),
            TimeUnit.MILLISECONDS));
  }

  /**
   * The linger window elapsed — end the session only if it is still the same, still unattended one.
   * The token guards a re-attach that raced this firing: {@link #cancelLinger} clears it under the
   * monitor, so a timer whose {@code cancel(false)} came too late still sees a stale token and
   * bails. Removal happens under the monitor (so a racing attach cannot join a dying session); the
   * blocking teardown runs outside it.
   */
  private void terminateIfUnattended(UUID id, Object lingerToken, Duration window) {
    TerminalSession toEnd = null;
    synchronized (this) {
      if (lingerTokens.get(id) != lingerToken) {
        return; // superseded by a re-attach or a later detach
      }
      TerminalSession session = sessions.get(id);
      if (session != null && !session.hasClients() && session.isAlive()) {
        sessions.remove(id);
        cancelLinger(id);
        toEnd = session;
      }
    }
    if (toEnd != null) {
      endSession(toEnd, "unattended for " + window);
    }
  }

  private void cancelLinger(UUID id) {
    lingerTokens.remove(id);
    ScheduledFuture<?> timer = lingerFutures.remove(id);
    if (timer != null) {
      timer.cancel(false);
    }
  }

  /**
   * End a session and, for glances, make sure the CONTAINER is gone too.
   *
   * <p>{@code --rm} only fires when a container exits. Killing the CLI that is attached to it does
   * not exit it — the container keeps running with nobody watching, holding the host's pid
   * namespace and the socket. So a glances teardown is two acts, and the second one is by the name
   * the argv builder derived from the session id.
   */
  private void endSession(TerminalSession session, String why) {
    LOG.infof("Ending %s terminal %s (%s)", session.kind(), session.id(), why);
    session.terminate();
    if (session.kind() == TerminalKind.GLANCES) {
      docker.bestEffort(
          "removing the glances container",
          DockerArgv.rmForce(
              docker.binary(), List.of(TerminalArgv.glancesContainerName(session.id()))));
    }
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
    List<TerminalSession> live;
    synchronized (this) {
      live = new ArrayList<>(sessions.values());
      sessions.clear();
    }
    // A restart takes every terminal with it, by design. Ending them here rather than letting the
    // process die is what stops a glances container outliving the service that started it.
    live.forEach(session -> endSession(session, "the service is stopping"));
  }
}
