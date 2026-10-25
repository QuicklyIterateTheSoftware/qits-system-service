package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInCancel;
import eu.wohlben.qits.systemrunner.protocol.SignInCode;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>A target's harness sign-in, run by its runner and relayed through here</b> (qits-1593): the
 * {@code /targets/{id}/sign-ins} doors start one, read it, hand it the code a person pasted and
 * cancel it; the runner runs the CLI's own login in its credential volume and reports every move as
 * {@code signInProgress}.
 *
 * <p><b>Held in memory, in this process only.</b> qits-system runs as one process, and the runner's
 * socket is held by that process ({@link TargetRegistry}'s sessions are in memory too), so there is
 * no other instance a sign-in or its session could live on. A restart forgets every sign-in, and
 * drops the socket that would have finished it.
 *
 * <p><b>A sign-in is bound to the session that started it.</b> The code and the cancel go to that
 * connection and no other, and when it closes a sign-in still running is {@link SignInState#FAILED}
 * {@value #RUNNER_DISCONNECTED}: the login ran in the runner process that went away. A runner's
 * report moves only the sign-in it names on its own target, and never its target or its harness.
 *
 * <p><b>Ten minutes, then fifteen.</b> A sign-in nobody finished in {@link #EXPIRY} is {@link
 * SignInState#EXPIRED} here and sent {@code signInCancel}; a terminal one stays readable for {@link
 * #KEPT} after it settled, so a poll that comes late still reads how it ended, and is then dropped.
 *
 * <p><b>The code is never logged</b>, nor kept: it is relayed and forgotten.
 */
@ApplicationScoped
public class TargetSignIns {

  private static final Logger LOG = Logger.getLogger(TargetSignIns.class);

  /** The harnesses a runner signs in. */
  public static final Set<String> HARNESSES = Set.of("claude", "kimi");

  static final Duration EXPIRY = Duration.ofMinutes(10);

  static final Duration KEPT = Duration.ofMinutes(15);

  public static final String RUNNER_DISCONNECTED = "the runner disconnected";

  static final String NOT_FINISHED = "nobody finished it within " + EXPIRY.toMinutes() + " minutes";

  @Inject TargetRegistry registry;

  /** One sign-in as the doors answer it. */
  public record View(
      UUID signInId,
      UUID targetId,
      String harness,
      SignInState state,
      String url,
      String userCode,
      String detail,
      Instant startedAt,
      Instant updatedAt) {

    View moved(SignInState to, String url, String userCode, String detail, Instant at) {
      return new View(
          signInId,
          targetId,
          harness,
          to,
          url == null ? this.url : url,
          userCode == null ? this.userCode : userCode,
          detail,
          startedAt,
          at);
    }
  }

  private record Held(View view, TargetRegistry.Session session) {}

  private final ConcurrentHashMap<UUID, Held> signIns = new ConcurrentHashMap<>();

  // --- the doors --------------------------------------------------------------------------------

  /**
   * Starts {@code harness}'s sign-in on the target's serving connection: 400 for another harness,
   * 409 {@code TARGET_UNAVAILABLE} when none is serving, 409 {@code SIGN_IN_RUNNING} while one for
   * the same harness has not settled.
   */
  public synchronized View start(UUID targetId, String targetName, String harness) {
    if (harness == null || !HARNESSES.contains(harness)) {
      throw TargetRefusal.badRequest(
          "A sign-in's harness is one of " + List.copyOf(HARNESSES) + ", not " + harness);
    }
    sweep(Instant.now());
    TargetRegistry.Session session = registry.serving(targetId);
    if (session == null) {
      throw unavailable(targetName);
    }
    for (Held held : signIns.values()) {
      View running = held.view();
      if (running.targetId().equals(targetId)
          && running.harness().equals(harness)
          && !running.state().terminal()) {
        throw TargetRefusal.conflict(
            TargetRefusal.SIGN_IN_RUNNING,
            "Target "
                + targetName
                + " is already signing in to "
                + harness
                + ": sign-in "
                + running.signInId()
                + " is "
                + running.state());
      }
    }
    Instant now = Instant.now();
    View started =
        new View(
            UUID.randomUUID(), targetId, harness, SignInState.STARTING, null, null, null, now, now);
    signIns.put(started.signInId(), new Held(started, session));
    if (!registry.send(session, new SignIn(started.signInId().toString(), harness))) {
      signIns.remove(started.signInId());
      throw unavailable(targetName);
    }
    LOG.infof("Target %s: %s sign-in %s started", targetName, harness, started.signInId());
    return started;
  }

  /** The sign-in, while it is kept; 404 {@code SIGN_IN_NOT_FOUND} otherwise. */
  public View get(UUID targetId, String signInId) {
    return held(targetId, signInId).view();
  }

  /** Relays the code to the runner: 409 {@code SIGN_IN_NOT_AWAITING_CODE} unless it awaits one. */
  public View code(UUID targetId, String targetName, String signInId, String code) {
    Held held = held(targetId, signInId);
    View view = held.view();
    if (view.state() != SignInState.AWAITING_CODE) {
      throw TargetRefusal.conflict(
          TargetRefusal.SIGN_IN_NOT_AWAITING_CODE,
          "Sign-in " + view.signInId() + " is " + view.state() + ", not awaiting a code");
    }
    if (code == null || code.isBlank()) {
      throw TargetRefusal.badRequest("A sign-in code is not blank");
    }
    if (!registry.send(held.session(), new SignInCode(view.signInId().toString(), code.strip()))) {
      throw unavailable(targetName);
    }
    LOG.infof("Target %s: relayed the code of %s sign-in %s", targetName, view.harness(), view.signInId());
    return view;
  }

  /**
   * Stops the sign-in: {@code signInCancel} to its runner, and CANCELLED at once — the runner's own
   * CANCELLED then finds it settled. A settled one is answered as it is.
   */
  public View cancel(UUID targetId, String targetName, String signInId) {
    Held held = held(targetId, signInId);
    View cancelled = settle(held.view().signInId(), SignInState.CANCELLED, null);
    if (cancelled == null) {
      return held(targetId, signInId).view();
    }
    registry.send(held.session(), new SignInCancel(cancelled.signInId().toString()));
    LOG.infof("Target %s: %s sign-in %s cancelled", targetName, cancelled.harness(), cancelled.signInId());
    return cancelled;
  }

  // --- the runner -------------------------------------------------------------------------------

  /** A {@code signInProgress} from {@code session}: moves the sign-in it names, on its own target only. */
  void onProgress(TargetRegistry.Session session, SignInProgress progress) {
    UUID id = uuid(progress.signInId());
    Held held = id == null ? null : signIns.get(id);
    if (held == null || !held.view().targetId().equals(session.targetId()) || progress.state() == null) {
      LOG.debugf(
          "Target %s reported sign-in %s, which it does not hold here; ignored",
          session.targetName(), progress.signInId());
      return;
    }
    View[] moved = {null};
    signIns.computeIfPresent(
        id,
        (key, current) -> {
          if (current.view().state().terminal()) {
            return current;
          }
          moved[0] =
              current
                  .view()
                  .moved(
                      progress.state(),
                      progress.url(),
                      progress.userCode(),
                      progress.detail(),
                      Instant.now());
          return new Held(moved[0], current.session());
        });
    if (moved[0] != null) {
      LOG.infof(
          "Target %s: %s sign-in %s is %s%s",
          session.targetName(),
          moved[0].harness(),
          id,
          moved[0].state(),
          moved[0].detail() == null ? "" : ": " + moved[0].detail());
    }
  }

  /** A connection closed: what it was signing in can no longer finish. */
  void onClose(TargetRegistry.Session session) {
    for (Held held : List.copyOf(signIns.values())) {
      if (held.session() == session
          && settle(held.view().signInId(), SignInState.FAILED, RUNNER_DISCONNECTED) != null) {
        LOG.infof(
            "Target %s: %s sign-in %s failed, %s",
            session.targetName(), held.view().harness(), held.view().signInId(), RUNNER_DISCONNECTED);
      }
    }
  }

  /** The target is gone, and its sign-ins with it. */
  public void forget(UUID targetId) {
    signIns.values().removeIf(held -> held.view().targetId().equals(targetId));
  }

  // --- the clock --------------------------------------------------------------------------------

  @Scheduled(every = "30s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepTick() {
    sweep(Instant.now());
  }

  /** Expires what ran past {@link #EXPIRY} and drops what settled more than {@link #KEPT} ago. */
  public void sweep(Instant now) {
    for (Map.Entry<UUID, Held> entry : List.copyOf(signIns.entrySet())) {
      View view = entry.getValue().view();
      if (view.state().terminal()) {
        if (!view.updatedAt().plus(KEPT).isAfter(now)) {
          signIns.remove(entry.getKey(), entry.getValue());
        }
      } else if (!view.startedAt().plus(EXPIRY).isAfter(now)
          && settle(view.signInId(), SignInState.EXPIRED, NOT_FINISHED) != null) {
        LOG.infof("%s sign-in %s expired: %s", view.harness(), view.signInId(), NOT_FINISHED);
        registry.send(entry.getValue().session(), new SignInCancel(view.signInId().toString()));
      }
    }
  }

  // --- internals --------------------------------------------------------------------------------

  /** Moves a sign-in that has not settled to the terminal {@code to}; null when it had settled. */
  private View settle(UUID id, SignInState to, String detail) {
    View[] settled = {null};
    signIns.computeIfPresent(
        id,
        (key, current) -> {
          if (current.view().state().terminal()) {
            return current;
          }
          settled[0] = current.view().moved(to, null, null, detail, Instant.now());
          return new Held(settled[0], current.session());
        });
    return settled[0];
  }

  private Held held(UUID targetId, String signInId) {
    UUID id = uuid(signInId);
    Held held = id == null ? null : signIns.get(id);
    if (held == null || !held.view().targetId().equals(targetId)) {
      throw new TargetRefusal(
          404, TargetRefusal.SIGN_IN_NOT_FOUND, "No sign-in " + signInId + " on this target");
    }
    return held;
  }

  private static TargetRefusal unavailable(String targetName) {
    return TargetRefusal.conflict(
        TargetRefusal.TARGET_UNAVAILABLE,
        "Target " + targetName + " is not connected, so it cannot sign in now");
  }

  private static UUID uuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
