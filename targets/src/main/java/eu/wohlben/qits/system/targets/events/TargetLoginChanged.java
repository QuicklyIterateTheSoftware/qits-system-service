package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A target's sign-in changed: either harness's value ({@code PRESENT}, {@code ABSENT}, {@code
 * UNKNOWN}) is not what the row held before the runner's {@code loginState}. {@code checkedAt} is the
 * probe's own instant, as the runner reported it.
 */
public record TargetLoginChanged(
    UUID eventId,
    String targetId,
    String name,
    String claude,
    String kimi,
    String checkedAt,
    Instant occurredAt)
    implements QitsEvent {

  public TargetLoginChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  public TargetLoginChanged(
      String targetId, String name, String claude, String kimi, String checkedAt, Instant occurredAt) {
    this(null, targetId, name, claude, kimi, checkedAt, occurredAt);
  }
}
