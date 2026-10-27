package eu.wohlben.qits.system.targets.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.control.TargetEvents;
import eu.wohlben.qits.system.targets.control.TargetPresence;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.system.targets.control.WorkVolumes;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.HealthChecked;
import eu.wohlben.qits.systemrunner.protocol.Inventory;
import eu.wohlben.qits.systemrunner.protocol.LeaseReport;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.ProbeLogin;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SlotImages;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerAck;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCapabilities;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * Every target runner holding a socket to this process: one {@link Session} per connection, what
 * each was told, and what each reported. {@link TargetRunnerSocket} owns the WebSocket lifecycle and
 * forwards frames here — the {@code CiRunnerSocket}/{@code CiRunnerRegistry} split every runner host
 * on the estate keeps, with qits-projects-service's {@code DeskRunnerRegistry} as the shape: <b>the
 * server pushes an {@code estate}, the runner answers with an {@code inventory}</b>. There are no
 * pull frames ({@code reserve}, {@code backlog}): {@link TargetAllocation} decides, and the estate
 * carries the decision.
 *
 * <p><b>A session is one connection; a target has at most one per version.</b> A second connection
 * that says {@code hello} in the same version replaces the first, closed 1008 {@link
 * #ALREADY_CONNECTED}. A runner not at the pin ({@link TargetPins}) is sent {@link Upgrade} and
 * {@code ack{slots: 0}} and drains; once its successor says {@code hello} at the pin every other
 * connection of the target is sent {@link Retire}. Lease containers outlive the runner process, so
 * the successor's {@code inventory} reports the same leases.
 *
 * <p><b>What a greeted connection is told, in order</b>: {@code ack} with the slot images, the probe
 * image and the registry mirrors — {@code slots} 0 while quarantined, followed by {@code
 * quarantined} — then {@code estate}; and {@code healthCheck} when the target awaits its first one
 * or has just come back ({@link TargetHealth}). The estate is sent again on every change ({@link
 * #estateChanged}) and every {@code estate-interval}, always from a successful read: the runner
 * removes whatever is not on it.
 *
 * <p><b>A dropped runner is still present for {@code qits.system.targets.reconnect-grace}</b>: its
 * leases are left exactly as they are (they run on the node whatever this socket does), and a
 * socket inside the grace is not a comeback. One after it is, and is quarantined until a health
 * check passes. A disconnected target is granted nothing.
 *
 * <p><b>Sign-in.</b> {@code loginState} is written to the row ({@link Targets#recordLogin}) and
 * announced when it changed; the login commands are rendered only once one has arrived on the
 * current connection ({@link #loginStateSeen}), the proof the probe image is on the node. A
 * harness sign-in runs on the serving connection and reports {@code signInProgress} ({@link
 * TargetSignIns}); a connection that closes fails whatever it was signing in.
 */
@ApplicationScoped
public class TargetRegistry implements TargetPresence {

  private static final Logger LOG = Logger.getLogger(TargetRegistry.class);

  public static final int CLOSE_POLICY = RunnerWire.CLOSE_POLICY_VIOLATION;

  public static final String ALREADY_CONNECTED = "ALREADY_CONNECTED";

  /**
   * The close reason of a connection whose client was replaced by a re-registration (qits-1579).
   * Deliberately not {@link RunnerWire.CloseReason#RUNNER_DELETED}: the target lives on, and the
   * runner must not remove its namespace from the node, which the new runner takes over.
   */
  public static final String REREGISTERED = "REREGISTERED";

  static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

  static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  static final Duration SEEN_INTERVAL = Duration.ofMinutes(1);

  @Inject TargetFrames frames;

  @Inject Targets targets;

  @Inject Leases leases;

  @Inject WorkVolumes workVolumes;

  @Inject TargetAllocation allocation;

  @Inject TargetEvents events;

  @Inject TargetPins pins;

  @Inject TargetAddresses addresses;

  @Inject TargetsConfig config;

  @Inject TargetHealth health;

  @Inject ProbeImages probeImages;

  @Inject TargetSignIns signIns;

  @Inject TargetDocker docker;

  @Inject ObjectMapper objectMapper;

  private final ConcurrentHashMap<UUID, List<Session>> sessions = new ConcurrentHashMap<>();

  private final AtomicLong admitted = new AtomicLong();

  private final ConcurrentHashMap<UUID, Instant> connectedSince = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<UUID, Instant> droppedAt = new ConcurrentHashMap<>();

  private final ScheduledExecutorService pushes =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "system-targets-push");
            t.setDaemon(true);
            return t;
          });

  /** One runner's connection. */
  public static final class Session {

    private final UUID targetId;
    private final String targetName;
    /** The client the connection's bearer named: the target's client when it was admitted. */
    private final String clientId;
    private final WebSocketConnection connection;
    private final long order;
    private volatile boolean open = true;
    private volatile boolean greeted;
    private volatile boolean draining;
    private volatile boolean loginSeen;
    private volatile String runnerVersion;
    private volatile Instant seenWrittenAt;
    /** The probe image this connection's newest {@code ack} named; null for none. */
    private volatile String probeImage;

    Session(Target target, WebSocketConnection connection, long order) {
      this.targetId = target.id;
      this.targetName = target.name;
      this.clientId = target.clientId;
      this.connection = connection;
      this.order = order;
    }

    public UUID targetId() {
      return targetId;
    }

    public String targetName() {
      return targetName;
    }

    public String clientId() {
      return clientId;
    }

    public boolean greeted() {
      return greeted;
    }

    public boolean draining() {
      return draining;
    }

    public String runnerVersion() {
      return runnerVersion;
    }

    public boolean isOpen() {
      return open;
    }
  }

  /** How a {@code hello} was received. */
  public enum Greeting {
    GREETED,
    VERSION_MISMATCH,
    TARGET_GONE
  }

  // --- the socket side --------------------------------------------------------------------------

  /** Binds a connection to its target, beside any session it has; its {@code hello} settles which stays. */
  public Session admit(Target target, WebSocketConnection connection) {
    Session fresh = new Session(target, connection, admitted.incrementAndGet());
    boolean[] first = {false};
    sessions.compute(
        target.id,
        (id, present) -> {
          List<Session> next = new ArrayList<>();
          if (present != null) {
            present.stream().filter(Session::isOpen).forEach(next::add);
          }
          first[0] = next.isEmpty();
          next.add(fresh);
          return List.copyOf(next);
        });
    if (first[0]) {
      Instant now = Instant.now();
      connectedSince.put(target.id, now);
      Instant dropped = droppedAt.remove(target.id);
      if (dropped != null && !dropped.plus(config.reconnectGrace()).isAfter(now)) {
        health.cameBack(target.id, target.name, Duration.between(dropped, now));
      }
    }
    LOG.infof("Target %s (%s) connected (connection %s)", target.name, target.id, connection.id());
    return fresh;
  }

  /**
   * The runner said who it is: version against the pin, then the capability, then what it said is
   * recorded (its slots are the target's total), the same-version rule settled, and it is answered.
   */
  public Greeting onHello(Session session, Hello hello) {
    Optional<String> pin = pins.version();
    boolean current = pin.isEmpty() || pin.get().equals(hello.runnerVersion());
    boolean speaks = hello.capabilityVersion() == SystemRunnerProtocol.CAPABILITY_VERSION;
    if (current && !speaks) {
      LOG.warnf(
          "Target %s announced capability version %d and this host speaks %d; refusing it",
          session.targetName, hello.capabilityVersion(), SystemRunnerProtocol.CAPABILITY_VERSION);
      return Greeting.VERSION_MISMATCH;
    }
    RunnerCapabilities machine = hello.capabilities();
    String volume =
        machine == null || !speaks ? null : SystemRunnerCapabilities.of(machine).credentialVolume();
    Target row =
        targets.recordHello(
            session.targetId,
            hello.slots(),
            hello.runnerVersion(),
            capabilities(hello, speaks),
            volume);
    if (row == null) {
      return Greeting.TARGET_GONE;
    }
    session.seenWrittenAt = Instant.now();
    session.runnerVersion = hello.runnerVersion();
    session.draining = !current;
    List<Session> retiring = settle(session);
    if (!current) {
      String image;
      try {
        image = addresses.runnerImage(pin.orElseThrow());
      } catch (TargetRefusal unconfigured) {
        LOG.errorf(
            "Target %s said hello as %s and the pin is %s, and it cannot be upgraded: %s",
            row.name, hello.runnerVersion(), pin.orElseThrow(), unconfigured.getMessage());
        if (speaks) {
          send(session, ack(session, 0));
        }
        return Greeting.GREETED;
      }
      LOG.infof(
          "Target %s said hello as %s and the pin is %s; upgrading it to %s",
          row.name, hello.runnerVersion(), pin.orElseThrow(), image);
      send(session, new Upgrade(pin.orElseThrow(), image, null));
      if (speaks) {
        send(session, ack(session, 0));
      }
      return Greeting.GREETED;
    }
    int slots = slots(row);
    LOG.infof(
        "Target %s said hello: %s, %d slot(s)%s",
        row.name,
        hello.runnerVersion(),
        row.slotsTotal,
        row.quarantined() ? "; quarantined: " + row.quarantineReason : "");
    send(session, ack(session, slots));
    if (row.quarantined()) {
      send(session, quarantinedFrame(row.quarantineReason, row.quarantinedAt));
    }
    session.greeted = true;
    sendEstate(session);
    health.onGreeted(row);
    for (Session old : retiring) {
      LOG.infof(
          "Target %s's %s connection %s is superseded by %s; retiring it",
          row.name, old.runnerVersion, old.connection.id(), hello.runnerVersion());
      send(old, new Retire("superseded by " + hello.runnerVersion()));
    }
    allocation.requestPass();
    return Greeting.GREETED;
  }

  /** What a {@code hello} says about the machine, as the capabilities column keeps it. */
  private String capabilities(Hello hello, boolean speaks) {
    ObjectNode said = objectMapper.createObjectNode();
    said.put("runnerVersion", hello.runnerVersion());
    RunnerCapabilities machine = speaks ? hello.capabilities() : null;
    if (machine != null) {
      said.put(RunnerWire.Field.DOCKER, machine.docker());
      said.put(RunnerWire.Field.ARCH, machine.arch());
      said.put(RunnerWire.Field.OS, machine.os());
      if (!machine.labels().isEmpty()) {
        said.set(RunnerWire.Field.LABELS, objectMapper.valueToTree(machine.labels()));
      }
      machine.extensions().forEach((key, value) -> said.set(key, objectMapper.valueToTree(value)));
    }
    return LeaseSpecs.text(said);
  }

  private List<Session> settle(Session session) {
    List<Session> replaced = new ArrayList<>();
    List<Session> retiring = new ArrayList<>();
    sessions.computeIfPresent(
        session.targetId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>();
          for (Session other : present) {
            if (other == session || other.runnerVersion == null) {
              kept.add(other);
            } else if (other.runnerVersion.equals(session.runnerVersion)) {
              replaced.add(other);
            } else {
              kept.add(other);
              if (!session.draining) {
                retiring.add(other);
              }
            }
          }
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    for (Session previous : replaced) {
      LOG.warnf(
          "Target %s said hello again as %s; closing its previous connection %s %s",
          session.targetName, session.runnerVersion, previous.connection.id(), ALREADY_CONNECTED);
      previous.open = false;
      closeBounded(
          previous.connection,
          new CloseReason(CLOSE_POLICY, ALREADY_CONNECTED),
          "the replaced connection of target " + session.targetName);
    }
    return retiring;
  }

  public void onHeartbeat(Session session) {
    Instant now = Instant.now();
    Instant written = session.seenWrittenAt;
    if (written != null && written.plus(SEEN_INTERVAL).isAfter(now)) {
      return;
    }
    session.seenWrittenAt = now;
    try {
      targets.touchSeen(session.targetId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not stamp target %s as seen: %s", session.targetName, e.getMessage());
    }
  }

  /** What the runner holds: its leases move, and its purged volumes' rows go. */
  public void onInventory(Session session, Inventory inventory) {
    List<Leases.Held> held = new ArrayList<>();
    for (LeaseReport lease : inventory.leases()) {
      UUID id = uuid(lease.leaseId());
      if (id != null) {
        held.add(
            new Leases.Held(
                id, lease.state().name(), lease.specHash(), lease.exitCode(), lease.detail()));
      }
    }
    try {
      leases.applyInventory(session.targetId, held);
    } catch (RuntimeException e) {
      LOG.warnf("Target %s's inventory was not applied: %s", session.targetName, e.toString());
    }
    try {
      if (workVolumes.onVolumes(session.targetId, inventory.volumes()) > 0) {
        estateChanged(session.targetId);
      }
    } catch (RuntimeException e) {
      LOG.warnf("Target %s's volumes were not reconciled: %s", session.targetName, e.toString());
    }
  }

  /** The node's sign-in, as last probed: written to the row, announced when it changed. */
  public void onLoginState(Session session, LoginState login) {
    session.loginSeen = true;
    try {
      Targets.LoginRecorded recorded =
          targets.recordLogin(
              session.targetId,
              LoginValue.parse(login.claude().name()),
              LoginValue.parse(login.kimi().name()),
              instant(login.checkedAt()),
              login.volume());
      if (recorded != null && recorded.changed()) {
        LOG.infof(
            "Target %s's sign-in is claude %s, kimi %s",
            session.targetName, recorded.target().loginClaude, recorded.target().loginKimi);
        events.loginChanged(recorded.target(), login.checkedAt());
        allocation.requestPass();
      }
    } catch (RuntimeException e) {
      LOG.warnf("Target %s's login state was not recorded: %s", session.targetName, e.toString());
    }
  }

  public void onHealthChecked(Session session, HealthChecked checked) {
    health.onHealthChecked(session.targetId, checked.requestId(), checked.report());
  }

  /** A docker request's answer ({@link TargetDocker}). */
  public void onDockerResult(Session session, DockerResult result) {
    docker.onResult(session.targetId, result);
  }

  /** A harness sign-in moved ({@link TargetSignIns}). */
  public void onSignInProgress(Session session, SignInProgress progress) {
    signIns.onProgress(session, progress);
  }

  /** A connection went away; when it was the target's last, the reconnect grace starts. */
  public void onClose(Session session) {
    session.open = false;
    signIns.onClose(session);
    boolean[] last = {false};
    sessions.computeIfPresent(
        session.targetId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>(present);
          kept.remove(session);
          kept.removeIf(other -> !other.isOpen());
          last[0] = kept.isEmpty();
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    if (!last[0] || sessions.containsKey(session.targetId)) {
      return;
    }
    LOG.infof(
        "Target %s (%s) disconnected (connection %s); it is waited for %s",
        session.targetName, session.targetId, session.connection.id(), config.reconnectGrace());
    connectedSince.remove(session.targetId);
    droppedAt.put(session.targetId, Instant.now());
  }

  // --- presence ---------------------------------------------------------------------------------

  @Override
  public boolean connected(UUID targetId) {
    return serving(targetId) != null;
  }

  /** Since when it has held a socket without a break; null while it holds none. */
  public Instant connectedSince(UUID targetId) {
    return targetId == null || open(targetId).isEmpty() ? null : connectedSince.get(targetId);
  }

  /** Connected, or dropped less than the reconnect grace ago. */
  public boolean presence(UUID targetId) {
    if (!open(targetId).isEmpty()) {
      return true;
    }
    Instant dropped = targetId == null ? null : droppedAt.get(targetId);
    return dropped != null && dropped.plus(config.reconnectGrace()).isAfter(Instant.now());
  }

  /** Whether a {@code loginState} arrived on the target's serving connection. */
  public boolean loginStateSeen(UUID targetId) {
    Session session = serving(targetId);
    return session != null && session.loginSeen;
  }

  /** The probe image the target's serving connection was last acked with, or null. */
  public String ackedProbeImage(UUID targetId) {
    Session session = serving(targetId);
    return session == null ? null : session.probeImage;
  }

  /** The runner version the target's serving connection said, or null. */
  public String connectedVersion(UUID targetId) {
    Session session = serving(targetId);
    return session == null ? null : session.runnerVersion;
  }

  Session current(UUID targetId) {
    List<Session> open = open(targetId);
    return open.stream()
        .filter(s -> s.greeted && !s.draining)
        .findFirst()
        .orElseGet(() -> open.isEmpty() ? null : open.get(open.size() - 1));
  }

  /** The session a target is served on — greeted, at the pin, open — or null. */
  Session serving(UUID targetId) {
    Session session = current(targetId);
    return session != null && session.greeted && !session.draining && session.isOpen()
        ? session
        : null;
  }

  List<Session> open(UUID targetId) {
    List<Session> present = targetId == null ? null : sessions.get(targetId);
    if (present == null) {
      return List.of();
    }
    return present.stream()
        .filter(Session::isOpen)
        .sorted(Comparator.comparingLong(s -> s.order))
        .toList();
  }

  // --- the signals ------------------------------------------------------------------------------

  @Override
  public void estateChanged(UUID targetId) {
    if (targetId == null) {
      return;
    }
    async(
        () -> {
          Session session = serving(targetId);
          if (session != null) {
            sendEstate(session);
          }
        },
        "the estate of target " + targetId);
  }

  /**
   * The probe image is chosen from the leases ({@link ProbeImages}); every serving connection whose
   * {@code ack} named another one is acked again. The runner takes the probe image from every
   * {@code ack} and probes at once, so a fresh target re-probes as soon as the first WORKSPACE (or
   * DESK) lease is asked for.
   */
  @Override
  public void probeImageMayHaveChanged() {
    async(
        () -> {
          String image = probeImages.current();
          for (UUID targetId : List.copyOf(sessions.keySet())) {
            Session session = serving(targetId);
            if (session != null && !java.util.Objects.equals(session.probeImage, image)) {
              LOG.infof(
                  "Target %s's login probe image is now %s; re-sending its ack",
                  session.targetName, image);
              reAck(session);
            }
          }
        },
        "the login probe image");
  }

  /** {@code probeLogin} to the target's serving connection; false when none took it. */
  public boolean probeLogin(UUID targetId) {
    Session session = serving(targetId);
    return session != null && send(session, new ProbeLogin());
  }

  public void reinstated(UUID targetId, String by) {
    Session session = serving(targetId);
    if (session != null) {
      send(session, new Reinstated(by));
      reAck(session);
    }
    allocation.requestPass();
  }

  public void quarantined(UUID targetId, String reason, Instant since) {
    Session session = serving(targetId);
    if (session != null) {
      send(session, quarantinedFrame(reason, since));
      reAck(session);
    }
  }

  /**
   * The target's row was deleted: every connection is sent {@code retire{DELETED}}, on which the
   * runner removes its own namespace from the node, and is closed 1008 {@code RUNNER_DELETED}.
   */
  public void deleted(UUID targetId) {
    for (Session session : open(targetId)) {
      LOG.infof(
          "Target %s (%s) was deleted; retiring its connection %s for good",
          session.targetName, session.targetId, session.connection.id());
      send(session, Retire.deleted("the target was deleted"));
      session.open = false;
      closeBounded(
          session.connection,
          new CloseReason(CLOSE_POLICY, RunnerWire.CloseReason.RUNNER_DELETED),
          "the connection of deleted target " + session.targetName);
    }
    sessions.remove(targetId);
    droppedAt.remove(targetId);
    connectedSince.remove(targetId);
    health.forget(targetId);
    signIns.forget(targetId);
  }

  /**
   * The target registered again (qits-1579): every connection admitted on {@code previousClientId}
   * is closed 1008 {@link #REREGISTERED} — no {@code retire{DELETED}}, since the target, its leases
   * and its work volumes stay, and the runner installed with the new token takes them over. A
   * connection the new client opens is untouched; a later dial with the old client finds no row and
   * is refused at the socket.
   */
  public void reregistered(UUID targetId, String previousClientId) {
    if (targetId == null || previousClientId == null) {
      return;
    }
    boolean closed = false;
    for (Session session : open(targetId)) {
      if (!previousClientId.equals(session.clientId)) {
        continue;
      }
      closed = true;
      LOG.infof(
          "Target %s (%s) registered again; closing its connection %s of replaced client %s",
          session.targetName, session.targetId, session.connection.id(), previousClientId);
      session.open = false;
      closeBounded(
          session.connection,
          new CloseReason(CLOSE_POLICY, REREGISTERED),
          "the replaced connection of target " + session.targetName);
    }
    if (closed && open(targetId).isEmpty()) {
      connectedSince.remove(targetId);
      droppedAt.put(targetId, Instant.now());
    }
  }

  // --- frames -----------------------------------------------------------------------------------

  /** {@code estate}, from a successful read only: a read that failed sends nothing. */
  void sendEstate(Session session) {
    String estate;
    try {
      estate = frames.encodeEstate(allocation.estateOf(session.targetId));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read target %s's leases; no estate sent: %s", session.targetName, e.toString());
      return;
    }
    sendText(session, Estate.class.getSimpleName(), estate);
  }

  private void reAck(Session session) {
    Target row = targets.find(session.targetId);
    if (row != null) {
      send(session, ack(session, slots(row)));
    }
  }

  private int slots(Target row) {
    return row.quarantined() ? 0 : Math.max(0, row.slotsTotal);
  }

  /** {@code ack} with the system runner's extension: slot images, probe image, registry mirrors. */
  Ack ack(Session session, int slots) {
    String probe = probeImages.current();
    session.probeImage = probe;
    Map<String, String> images = config.slotImages();
    SystemRunnerAck extension =
        new SystemRunnerAck(
            new SlotImages(
                images.get(SlotType.CI.key()),
                images.get(SlotType.WORKSPACE.key()),
                images.get(SlotType.DESK.key())),
            probe,
            addresses.registryMirrors());
    return new Ack(SystemRunnerProtocol.CAPABILITY_VERSION, slots, null, extension.extensions());
  }

  private static Quarantined quarantinedFrame(String reason, Instant since) {
    return new Quarantined(
        reason == null ? "" : reason, since == null ? Instant.now().toString() : since.toString());
  }

  /** Sends one frame on the target's serving connection, bounded. */
  public boolean send(UUID targetId, RunnerMessage message) {
    Session session = serving(targetId);
    return session != null && send(session, message);
  }

  /** Sends one frame on a session, bounded. False when it could not leave. */
  public boolean send(Session session, RunnerMessage message) {
    return sendText(session, message.getClass().getSimpleName(), () -> frames.encode(message));
  }

  /** {@link #send} for a frame already encoded as text (the estate, see {@code encodeEstate}). */
  boolean sendText(Session session, String frame, String text) {
    return sendText(session, frame, () -> text);
  }

  private boolean sendText(
      Session session, String frame, java.util.function.Supplier<String> text) {
    WebSocketConnection connection = session.connection;
    if (!session.isOpen() || !connection.isOpen()) {
      LOG.debugf("No live socket for target %s; dropped %s", session.targetName, frame);
      return false;
    }
    try {
      connection.sendText(text.get()).await().atMost(SEND_TIMEOUT);
      return true;
    } catch (RuntimeException e) {
      LOG.warnf("Could not send %s to target %s: %s", frame, session.targetName, e.getMessage());
      return false;
    }
  }

  /** Every target with an open connection, in memory. */
  public Set<UUID> connectedTargetIds() {
    return Set.copyOf(sessions.keySet());
  }

  private void async(Runnable work, String what) {
    try {
      pushes.execute(
          () -> {
            try {
              work.run();
            } catch (RuntimeException e) {
              LOG.debugf("%s failed: %s", what, e.getMessage());
            }
          });
    } catch (RuntimeException rejected) {
      LOG.debugf("%s was not scheduled: %s", what, rejected.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    pushes.shutdownNow();
  }

  static void closeBounded(WebSocketConnection connection, CloseReason reason, String what) {
    try {
      connection.close(reason).await().atMost(CLOSE_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.debugf("Closing the socket of %s did not complete: %s", what, e.getMessage());
    }
  }

  private static UUID uuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  private static Instant instant(String text) {
    if (text == null || text.isBlank()) {
      return Instant.now();
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException unparseable) {
      return Instant.now();
    }
  }
}
