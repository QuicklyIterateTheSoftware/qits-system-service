package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.entity.Lease;
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
 * left of it. {@link #sweep} takes the clock as an argument, which is how the suite drives it.
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

  /** What one sweep did, the numbers the tests read. */
  public record Swept(List<UUID> nudged, List<UUID> ended) {}

  @Scheduled(
      every = "{qits.system.leases.ending-check-interval}",
      delayed = "{qits.system.leases.ending-check-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    sweep(Instant.now());
  }

  /** One sweep as of {@code now}; never throws. */
  public Swept sweep(Instant now) {
    List<UUID> nudged = new ArrayList<>();
    List<UUID> ended = new ArrayList<>();
    List<Leases.Ending> ending;
    try {
      ending = leases.ending(now);
    } catch (RuntimeException e) {
      if (LaunchMode.current() != LaunchMode.TEST) {
        LOG.warnf("Could not read the ENDING leases: %s", e.toString());
      }
      return new Swept(nudged, ended);
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
        if (endPastDeadline(lease, now)) {
          ended.add(lease.id());
        }
      } catch (RuntimeException e) {
        LOG.warnf("The ENDING deadline of lease %s did not complete: %s", lease.id(), e.toString());
      }
    }
    return new Swept(nudged, ended);
  }

  private boolean endPastDeadline(Leases.Ending lease, Instant now) {
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

  private String targetName(UUID targetId) {
    if (targetId == null) {
      return "(no target)";
    }
    Target target = targets.find(targetId);
    return target == null ? targetId.toString() : target.name;
  }
}
