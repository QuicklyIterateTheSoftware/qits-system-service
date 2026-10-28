package eu.wohlben.qits.system.targets.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>A target's health check, and how it gates the target</b> — qits-projects-service's {@code
 * DeskRunnerHealth}, keyed {@code qits.system.targets.healthcheck.*}.
 *
 * <p><b>The checks.</b> The runner runs the toolkit's {@code HealthChecks} and its own ({@code
 * buildkit}) and answers {@code healthChecked{requestId, ok, detail, checks[]}}. This service adds
 * the two only it can judge, because only it knows the reservations ({@link #serverChecks}):
 *
 * <ul>
 *   <li>{@code login} <b>fails</b> when {@code reserved_workspace + reserved_desk > 0} and neither harness is
 *       PRESENT: a target reserved for agents that cannot run one;
 *   <li>{@code slots} fails when the reservations do not fit the total, naming the sum and the total.
 * </ul>
 *
 * <b>Both are reported and never quarantine</b> ({@link #REPORT_ONLY}): they fail the recorded
 * health and its detail, and are left out of the quarantine decision, so a signed-out target still
 * runs CI. And {@code buildkit} counts only when the target has {@code reserved_ci > 0} or runs a CI lease; a
 * failing one on any other target is kept in the report and does not fail it.
 *
 * <p><b>Quarantine.</b> A quarantined target's {@code ack} carries 0 slots and it is granted
 * nothing, while every lease it already runs is left as it is. A target is quarantined at its
 * creation ({@link Targets#AWAITING_FIRST_HEALTH_CHECK}), when it comes back after the reconnect
 * grace ({@link #RECONNECTED}), and when a check fails or goes unanswered within {@code
 * healthcheck.timeout}. A passing check reinstates it; so does a greenlight.
 *
 * <p><b>The schedule backs off from the quarantine</b>: {@code healthcheck.schedule} is a list of
 * offsets from {@code quarantined_at}, then one more every {@link #AFTER_SCHEDULE}.
 */
@ApplicationScoped
public class TargetHealth {

  private static final Logger LOG = Logger.getLogger(TargetHealth.class);

  static final Duration AFTER_SCHEDULE = Duration.ofHours(1);

  public static final String BY_HEALTH_CHECK = "health check";

  public static final String RECONNECTED = "reconnected after being offline";

  public static final String NO_ANSWER = "no answer";

  static final String FAILED = "health check failed";

  static final String LOGIN = "login";

  static final String SLOTS = "slots";

  static final String BUILDKIT = "buildkit";

  @Inject Targets targets;

  @Inject TargetRegistry registry;

  @Inject TargetsConfig config;

  @Inject ObjectMapper objectMapper;

  record Pending(String requestId, Instant sentAt, TargetRegistry.Session session) {}

  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<UUID, Instant> newestSent = new ConcurrentHashMap<>();

  private final Set<UUID> owedOnGreeting = ConcurrentHashMap.newKeySet();

  // --- the triggers -----------------------------------------------------------------------------

  /** Asks a connected target for a check now; its requestId, or null when none is serving. */
  public String request(UUID targetId) {
    return sendUnlessPending(targetId, Instant.now());
  }

  void cameBack(UUID targetId, String name, Duration away) {
    try {
      owedOnGreeting.add(targetId);
      Target quarantined = targets.quarantine(targetId, RECONNECTED);
      LOG.infof(
          "Target %s came back after %ss offline%s; it is granted nothing until a health check"
              + " passes",
          name, away.toSeconds(), quarantined == null ? " (already quarantined)" : ", quarantined");
    } catch (RuntimeException e) {
      LOG.warnf(e, "Target %s came back and could not be quarantined", name);
    }
  }

  void onGreeted(Target row) {
    boolean owed = owedOnGreeting.remove(row.id);
    if (!row.quarantined()
        || !(owed || Targets.AWAITING_FIRST_HEALTH_CHECK.equals(row.quarantineReason))) {
      return;
    }
    if (sendUnlessPending(row.id, Instant.now()) == null) {
      LOG.warnf("Target %s was greeted and its health check could not be sent", row.name);
    }
  }

  void forget(UUID targetId) {
    pending.remove(targetId);
    newestSent.remove(targetId);
    owedOnGreeting.remove(targetId);
  }

  // --- a settled check --------------------------------------------------------------------------

  void onHealthChecked(UUID targetId, String requestId, HealthReport report) {
    Pending settled = claim(targetId, requestId);
    String id = requestId == null && settled != null ? settled.requestId() : requestId;
    Instant now = Instant.now();
    Target row = targets.find(targetId);
    if (row == null) {
      return;
    }
    List<CheckResult> checks = new ArrayList<>(report == null ? List.of() : report.checks());
    checks.removeIf(c -> LOGIN.equals(c.name()) || SLOTS.equals(c.name()));
    boolean runnerNamedNone = checks.isEmpty();
    checks.addAll(serverChecks(row));
    boolean buildkitCounts = row.reservedCi > 0 || targets.runsCi(targetId);
    Verdict verdict = verdict(checks, buildkitCounts, report, runnerNamedNone);
    settle(
        targetId,
        verdict.ok(),
        verdict.admitted(),
        verdict.detail(),
        verdict.gateDetail(),
        report(now, verdict.ok(), verdict.detail(), id, checks),
        now);
  }

  /** The checks that are REPORTED and never quarantine a target: the gate decides on the rest. */
  static final java.util.Set<String> REPORT_ONLY = java.util.Set.of(LOGIN, SLOTS);

  /**
   * A settled check's two answers. {@code ok} is the reported health: every check that counts,
   * {@code login} and {@code slots} included. {@code admitted} is what quarantine and reinstatement
   * decide on, and it leaves {@link #REPORT_ONLY} out: a signed-out target must never stop CI (the
   * allocator's sign-in gate already holds its WORKSPACE and DESK leases), nor must reservations that do
   * not fit the total (the allocator already ignores them).
   */
  record Verdict(boolean ok, boolean admitted, String detail, String gateDetail) {}

  /** The verdict over {@code checks}; pure. */
  static Verdict verdict(
      List<CheckResult> checks, boolean buildkitCounts, HealthReport report, boolean runnerNamedNone) {
    List<String> failed = new ArrayList<>();
    List<String> gating = new ArrayList<>();
    for (CheckResult check : checks) {
      if (check.ok() || (!buildkitCounts && BUILDKIT.equals(check.name()))) {
        continue;
      }
      String line = check.name() + (check.detail() == null ? "" : ": " + check.detail());
      failed.add(line);
      if (!REPORT_ONLY.contains(check.name())) {
        gating.add(line);
      }
    }
    if (report != null && !report.ok() && runnerNamedNone) {
      // A runner that failed without naming a check still failed.
      String line = report.detail() == null ? FAILED : report.detail();
      failed.add(line);
      gating.add(line);
    }
    String passed = report == null || report.detail() == null ? "" : report.detail();
    return new Verdict(
        failed.isEmpty(),
        gating.isEmpty(),
        failed.isEmpty() ? passed : String.join("; ", failed),
        gating.isEmpty() ? passed : String.join("; ", gating));
  }

  /** The checks this service judges, from the row alone. Pure. */
  static List<CheckResult> serverChecks(Target row) {
    List<CheckResult> checks = new ArrayList<>();
    int agentReservations = row.reservedWorkspace + row.reservedDesk;
    boolean loginOk = agentReservations == 0 || row.signedIn();
    checks.add(
        new CheckResult(
            LOGIN,
            loginOk,
            loginOk
                ? null
                : "neither claude nor kimi is signed in, and "
                    + agentReservations
                    + " slot(s) are reserved for agents: qits targets sign-in --target "
                    + row.name,
            Map.of(
                "claude", String.valueOf(row.loginClaude),
                "kimi", String.valueOf(row.loginKimi))));
    int sum = row.reservationsSum();
    boolean slotsOk = sum <= row.slotsTotal;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reservations", sum);
    data.put("total", row.slotsTotal);
    checks.add(
        new CheckResult(
            SLOTS,
            slotsOk,
            slotsOk
                ? null
                : "the reservations sum to "
                    + sum
                    + ", above the "
                    + row.slotsTotal
                    + " slot(s) the runner reports; they are ignored",
            data));
    return checks;
  }

  private Pending claim(UUID targetId, String requestId) {
    if (requestId == null) {
      return pending.remove(targetId);
    }
    Pending[] claimed = {null};
    pending.computeIfPresent(
        targetId,
        (id, waiting) -> {
          if (requestId.equals(waiting.requestId())) {
            claimed[0] = waiting;
            return null;
          }
          return waiting;
        });
    return claimed[0];
  }

  /**
   * A check's verdict reached its row: the REPORTED health ({@code ok}, {@code detail}) is recorded
   * first, whatever follows; then the gate ({@code admitted}, {@code gateDetail}) lifts or begins a
   * quarantine. A target whose only failures are {@link #REPORT_ONLY} is recorded unhealthy and
   * neither quarantined nor kept in one. Never throws.
   */
  private void settle(
      UUID targetId,
      boolean ok,
      boolean admitted,
      String detail,
      String gateDetail,
      ObjectNode report,
      Instant at) {
    try {
      Target row = targets.recordHealthCheck(targetId, ok, at, LeaseSpecs.text(report));
      if (row == null) {
        return;
      }
      if (!ok) {
        LOG.warnf("Health check of target %s reports: %s", row.name, detail);
      }
      if (admitted) {
        LOG.infof("Health check of target %s admits it", row.name);
        if (row.quarantined()) {
          targets.greenlight(targetId);
          LOG.infof("Target %s passed its health check; it is granted leases now", row.name);
          registry.reinstated(targetId, BY_HEALTH_CHECK);
        }
        return;
      }
      String reason = truncate(gateDetail.isBlank() ? FAILED : FAILED + ": " + gateDetail);
      LOG.warnf("Health check of target %s failed: %s", row.name, gateDetail);
      Targets.Quarantine out = targets.quarantineFor(targetId, reason);
      if (out.target() != null && (out.began() || out.reasonChanged())) {
        registry.quarantined(targetId, out.target().quarantineReason, out.target().quarantinedAt);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Health check of target %s could not be recorded", targetId);
    }
  }

  private ObjectNode report(
      Instant at, boolean ok, String detail, String requestId, List<CheckResult> checks) {
    ObjectNode report = objectMapper.createObjectNode();
    report.put("at", at.toString());
    report.put("ok", ok);
    report.put("detail", detail);
    report.put("requestId", requestId);
    ArrayNode array = report.putArray("checks");
    for (CheckResult check : checks) {
      ObjectNode node = array.addObject();
      node.put("name", check.name());
      node.put("ok", check.ok());
      node.put("detail", check.detail());
      node.set("data", objectMapper.valueToTree(check.data()));
    }
    return report;
  }

  // --- the schedule -----------------------------------------------------------------------------

  @Scheduled(
      every = "{qits.system.targets.healthcheck.sweep-interval}",
      delayed = "{qits.system.targets.healthcheck.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepTick() {
    sweep(Instant.now());
  }

  void sweep(Instant now) {
    expireUnanswered(now);
    List<Target> quarantined;
    try {
      quarantined = targets.list().stream().filter(Target::quarantined).toList();
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the quarantined targets: %s", e.toString());
      return;
    }
    for (Target target : quarantined) {
      if (registry.serving(target.id) == null || livePending(target.id) != null || !due(target, now)) {
        continue;
      }
      if (sendUnlessPending(target.id, now) == null) {
        LOG.warnf("Target %s is due a health check and it could not be sent", target.name);
      }
    }
  }

  private boolean due(Target target, Instant now) {
    Instant since = target.quarantinedAt;
    if (since == null) {
      return false;
    }
    Instant newest = latestSince(since, newestSent.get(target.id), target.lastHealthCheckAt);
    return !nextSlot(since, newest).isAfter(now);
  }

  private static Instant latestSince(Instant since, Instant a, Instant b) {
    Instant newest = null;
    for (Instant at : new Instant[] {a, b}) {
      if (at != null && !at.isBefore(since) && (newest == null || at.isAfter(newest))) {
        newest = at;
      }
    }
    return newest;
  }

  Instant nextSlot(Instant since, Instant newest) {
    long k = 0;
    if (newest != null) {
      while (!since.plus(offset(k)).isAfter(newest)) {
        k++;
      }
    }
    return since.plus(offset(k));
  }

  Duration offset(long k) {
    List<Duration> offsets = config.healthcheck().schedule();
    if (k < offsets.size()) {
      return offsets.get((int) k);
    }
    Duration last = offsets.isEmpty() ? Duration.ZERO : offsets.get(offsets.size() - 1);
    return last.plus(AFTER_SCHEDULE.multipliedBy(k - offsets.size() + 1));
  }

  private void expireUnanswered(Instant now) {
    Duration timeout = config.healthcheck().timeout();
    for (Map.Entry<UUID, Pending> entry : List.copyOf(pending.entrySet())) {
      Pending waiting = entry.getValue();
      if (waiting.sentAt().plus(timeout).isAfter(now) || !pending.remove(entry.getKey(), waiting)) {
        continue;
      }
      LOG.warnf(
          "Health check %s of target %s was not answered within %s",
          waiting.requestId(), entry.getKey(), timeout);
      settle(
          entry.getKey(),
          false,
          false,
          NO_ANSWER,
          NO_ANSWER,
          report(now, false, NO_ANSWER, waiting.requestId(), List.of()),
          now);
    }
  }

  String pendingCheck(UUID targetId) {
    Pending waiting = pending.get(targetId);
    return waiting == null ? null : waiting.requestId();
  }

  private Pending livePending(UUID targetId) {
    Pending waiting = pending.get(targetId);
    return waiting != null && waiting.session().isOpen() ? waiting : null;
  }

  private String sendUnlessPending(UUID targetId, Instant now) {
    TargetRegistry.Session session = registry.serving(targetId);
    if (session == null) {
      return null;
    }
    Pending fresh = new Pending(UUID.randomUUID().toString(), now, session);
    Pending[] standing = {null};
    pending.compute(
        targetId,
        (id, waiting) -> {
          if (waiting != null && waiting.session().isOpen()) {
            standing[0] = waiting;
            return waiting;
          }
          return fresh;
        });
    if (standing[0] != null) {
      return standing[0].requestId();
    }
    if (!registry.send(session, new HealthCheck(fresh.requestId()))) {
      pending.remove(targetId, fresh);
      return null;
    }
    newestSent.put(targetId, now);
    LOG.infof("Asked target %s for health check %s", session.targetName(), fresh.requestId());
    return fresh.requestId();
  }

  private static String truncate(String s) {
    return s.length() <= 1000 ? s : s.substring(0, 1000);
  }
}
