package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A target was decommissioned: {@code DELETE /targets/{id}} ({@code api/TargetController}) answered
 * — its runner retired and its credentials given back to qits-idp (qits-2022). Published once per
 * successful delete, after {@code registry.deleted} and the client decommission both succeed; a
 * delete that answers 404 (no such target) publishes nothing. qits-network-access-service is the
 * subscriber this exists for: it revokes whatever it holds for the target.
 *
 * <p>{@code targetName}, not {@code name}, for the same reason {@link TargetConnectionChanged} uses
 * it: a component named {@code name} would shadow {@link QitsEvent#name()}'s {@code signature()}
 * default and put the target's name on the wire where the event's own name belongs.
 */
public record TargetDeleted(UUID eventId, String targetId, String targetName, Instant occurredAt)
    implements QitsEvent {

  public TargetDeleted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  public TargetDeleted(String targetId, String targetName, Instant occurredAt) {
    this(null, targetId, targetName, occurredAt);
  }
}
