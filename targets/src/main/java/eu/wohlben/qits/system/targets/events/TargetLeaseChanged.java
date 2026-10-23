package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A lease's state moved, or a queued lease's {@code detail} changed (why it waits). Published on
 * every such change; owners react to it and also reconcile every PT30S from {@code GET
 * /system/api/leases}, so a lost event only delays them. {@code targetId} is null while QUEUED,
 * {@code exitCode} is set for EXITED, {@code detail} names why a lease waits or failed.
 */
public record TargetLeaseChanged(
    UUID eventId,
    String leaseId,
    String ownerApp,
    String ownerRef,
    String slotType,
    String targetId,
    String state,
    Integer exitCode,
    String detail,
    Instant occurredAt)
    implements QitsEvent {

  public TargetLeaseChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public TargetLeaseChanged(
      String leaseId,
      String ownerApp,
      String ownerRef,
      String slotType,
      String targetId,
      String state,
      Integer exitCode,
      String detail,
      Instant occurredAt) {
    this(
        null, leaseId, ownerApp, ownerRef, slotType, targetId, state, exitCode, detail, occurredAt);
  }
}
