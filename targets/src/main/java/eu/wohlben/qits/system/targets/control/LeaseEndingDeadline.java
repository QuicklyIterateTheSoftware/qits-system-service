package eu.wohlben.qits.system.targets.control;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.Target;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>An ENDING lease does not hold its slot forever.</b> ENDING becomes ENDED when the runner
 * reports the container gone ({@link Leases#applyInventory}); a runner that never does would keep
 * the slot occupied ({@code LeaseState.OCCUPYING}) for good. So, every check interval:
 *
 * <ol>
 *   <li>an ENDING lease past {@code ending_since + ending-deadline} and not yet nudged has its
 *       target's estate sent once more ({@link TargetPresence#estateChanged}, the registry's own
 *       push) and {@code ending_nudged_at} set — unless its target is not connected, when there is
 *       no one to nudge and it goes straight to step 2;
 *   <li>one past {@code ending_nudged_at + ending-grace} and still ENDING is ENDED with a detail
 *       that says so, announced like any other move, and recorded as a {@link
 *       TargetAuditLog#LEASE_ENDING_DEADLINE} row by {@link TargetAuditLog#SYSTEM_ACTOR}.
 * </ol>
 *
 * The next estate lists an ENDED lease as nothing, so a runner that comes back removes whatever is
 * left of it.
 *
 * <p><b>A runner that was nudged and still did nothing has stopped acting on its estate</b>
 * (qits-1829). Its socket is open, so it is connected, and ending the lease frees a slot that the
 * pass {@link Leases#endPastDeadline} asks for would grant straight back to it. So when the lease
 * was nudged and its target is still connected, the target is quarantined first ({@link
 * #ignoredItsEstate}, through {@link Targets#quarantineFor}, its own committed transaction, and its
 * runner told through {@link TargetPresence#quarantined}), then its GRANTED leases are queued again
 * (below), and only then is the lease ended. The health-check schedule reinstates the target once
 * its runner answers a check that passes; a wedged one never does. A lease ENDED on a disconnected
 * target was never nudged and quarantines nothing: a comeback past the grace is quarantined anyway.
 *
 * <p><b>A GRANTED lease on a target that is gone goes back to QUEUED</b> (qits-1829). Nothing was
 * started for it, so nothing will ever report it, and its owner would wait for a start that never
 * comes. Every sweep, a GRANTED lease without a work key whose target is not {@linkplain
 * TargetPresence#present present} — no open connection, and none for the reconnect grace — is
 * queued again ({@link Leases#requeue}), announced, recorded as a {@link
 * TargetAuditLog#LEASE_REQUEUED} row, and the pass places it on another target, or on its pinned one
 * when that returns. A lease with a work key is left alone: its work volume's row pins it to that
 * target, so queuing it again would change only its detail. RUNNING, EXITED, FAILED and ENDING
 * leases are left as they are: their containers outlive the socket.
 *
 * <p>{@link #sweep} takes the clock as an argument, which is how the suite drives it.
 */
@ApplicationScoped
public class LeaseEndingDeadline {

  private static final Logger LOG = Logger.getLogger(LeaseEndingDeadline.class);

  @Inject Leases leases;

  @Inject Targets targets;

  @Inject TargetPresence presence;

  @Inject TargetAuditLog audit;

  @ConfigProperty(name = "qits.system.leases.ending-deadline")
  Duration deadline;

  @ConfigProperty(name = "qits.system.leases.ending-grace")
  Duration grace;

  /**
   * The quarantine reason of a target whose runner was nudged about {@code leaseId} and still did not
   * act on its estate (qits-1829).
   */
  public static String ignoredItsEstate(UUID leaseId) {
    return "its runner did not act on its estate: lease " + leaseId + " stayed ENDING past the deadline";
  }

  /**
   * What one sweep did, the numbers the tests read: the leases nudged, ENDED and queued again, and
   * the targets quarantined.
   */
  public record Swept(
      List<UUID> nudged, List<UUID> ended, List<UUID> requeued, List<UUID> quarantined) {}

  @Scheduled(
      every = "{qits.system.leases.ending-check-interval}",
      delayed = "{qits.system.leases.ending-check-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    sweep(Instant.now());
  }

  /** One sweep as of {@code now}: the ENDING deadline, then the requeue; never throws. */
  public Swept sweep(Instant now) {
    Swept swept = new Swept(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    endingDeadline(now, swept);
    requeueAbsent(now, swept);
    return swept;
  }

  private void endingDeadline(Instant now, Swept swept) {
    List<UUID> nudged = swept.nudged();
    List<UUID> ended = swept.ended();
    List<Leases.Ending> ending;
    try {
      ending = leases.ending(now);
    } catch (RuntimeException e) {
      if (LaunchMode.current() != LaunchMode.TEST) {
        LOG.warnf("Could not read the ENDING leases: %s", e.toString());
      }
      return;
    }
    for (Leases.Ending lease : ending) {
      try {
        if (lease.endingNudgedAt() == null) {
          if (now.isBefore(lease.endingSince().plus(deadline))) {
            continue;
          }
          if (lease.targetId() != null && presence.connected(lease.targetId())) {
            if (leases.nudged(lease.id(), now)) {
              LOG.infof(
                  "Lease %s has been ENDING since %s; its target's estate is sent again",
                  lease.id(), lease.endingSince());
              presence.estateChanged(lease.targetId());
              nudged.add(lease.id());
            }
            continue;
          }
        } else if (now.isBefore(lease.endingNudgedAt().plus(grace))) {
          continue;
        }
        if (endPastDeadline(lease, now, swept)) {
          ended.add(lease.id());
        }
      } catch (RuntimeException e) {
        LOG.warnf("The ENDING deadline of lease %s did not complete: %s", lease.id(), e.toString());
      }
    }
  }

  private boolean endPastDeadline(Leases.Ending lease, Instant now, Swept swept) {
    if (lease.endingNudgedAt() != null
        && lease.targetId() != null
        && presence.connected(lease.targetId())) {
      Lease current = leases.find(lease.id());
      if (current == null || current.state != LeaseState.ENDING) {
        return false;
      }
      // Before the end below, whose pass would otherwise grant the freed slot straight back.
      quarantineSilent(lease, now, swept);
    }
    String detail =
        "ENDING deadline passed: the runner on "
            + targetName(lease.targetId())
            + " never reported the container gone (since "
            + lease.endingSince()
            + ")";
    Lease ended = leases.endPastDeadline(lease.id(), now, detail);
    if (ended == null) {
      return false;
    }
    audit.record(
        new TargetAuditLog.Entry(
            TargetAuditLog.SYSTEM_ACTOR,
            "",
            ended.targetId,
            ended.id,
            TargetAuditLog.LEASE_ENDING_DEADLINE,
            null,
            null,
            ended.state.name(),
            detail),
        now);
    return true;
  }

  /**
   * The runner of {@code lease}'s target was nudged and still did not act: the target is quarantined,
   * its runner told, and its GRANTED leases without a work key queued again — they were handed to
   * the same runner, which will not start them either.
   */
  private void quarantineSilent(Leases.Ending lease, Instant now, Swept swept) {
    UUID targetId = lease.targetId();
    String reason = ignoredItsEstate(lease.id());
    Targets.Quarantine out = targets.quarantineFor(targetId, reason);
    if (out.target() == null) {
      return;
    }
    LOG.warnf(
        "Target %s is quarantined%s: %s",
        out.target().name, out.began() ? "" : " (already, reason renewed)", reason);
    if (out.began() || out.reasonChanged()) {
      presence.quarantined(targetId, out.target().quarantineReason, out.target().quarantinedAt);
    }
    if (out.began() && !swept.quarantined().contains(targetId)) {
      swept.quarantined().add(targetId);
    }
    String name = out.target().name;
    Function<Instant, String> detail =
        grantedAt ->
            "target "
                + name
                + " stopped acting on its estate before it started the lease (granted "
                + grantedAt
                + "); queued again";
    for (Leases.Granted granted : leases.grantedWithoutWorkKey()) {
      if (targetId.equals(granted.targetId())) {
        requeue(granted, detail, now, swept);
      }
    }
  }

  /** Every GRANTED lease without a work key whose target is not present goes back to QUEUED. */
  private void requeueAbsent(Instant now, Swept swept) {
    List<Leases.Granted> granted;
    try {
      granted = leases.grantedWithoutWorkKey();
    } catch (RuntimeException e) {
      if (LaunchMode.current() != LaunchMode.TEST) {
        LOG.warnf("Could not read the GRANTED leases: %s", e.toString());
      }
      return;
    }
    for (Leases.Granted lease : granted) {
      try {
        if (presence.present(lease.targetId(), now)) {
          continue;
        }
        String name = targetName(lease.targetId());
        Function<Instant, String> detail =
            grantedAt ->
                "target "
                    + name
                    + " went away before it started the lease (granted "
                    + grantedAt
                    + "); queued again";
        requeue(lease, detail, now, swept);
      } catch (RuntimeException e) {
        LOG.warnf("The requeue of lease %s did not complete: %s", lease.id(), e.toString());
      }
    }
  }

  /** {@code detail} is the lease's new detail, given the instant it was granted at. */
  private void requeue(
      Leases.Granted granted, Function<Instant, String> detail, Instant now, Swept swept) {
    String said = detail.apply(granted.grantedAt());
    Lease queued = leases.requeue(granted, said);
    if (queued == null) {
      return;
    }
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("from", LeaseState.GRANTED.name());
    args.put("to", LeaseState.QUEUED.name());
    audit.record(
        new TargetAuditLog.Entry(
            TargetAuditLog.SYSTEM_ACTOR,
            "",
            granted.targetId(),
            queued.id,
            TargetAuditLog.LEASE_REQUEUED,
            null,
            args,
            queued.state.name(),
            said),
        now);
    swept.requeued().add(queued.id);
  }

  private String targetName(UUID targetId) {
    if (targetId == null) {
      return "(no target)";
    }
    Target target = targets.find(targetId);
    return target == null ? targetId.toString() : target.name;
  }
}
