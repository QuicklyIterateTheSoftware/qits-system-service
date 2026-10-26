package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A target's sign-in changed: either harness's value ({@code PRESENT}, {@code ABSENT}, {@code
 * UNKNOWN}) is not what the row held before the runner's {@code loginState}. {@code checkedAt} is the
 * probe's own instant, as the runner reported it.
 *
 * <p>{@code targetName}, not {@code name}: {@link QitsEvent#name()} defaults to {@link
 * QitsEvent#signature()} — the simple class name, which is what a subscriber selects on and what
 * qits-events stores in the row's {@code name} column — and a record component named {@code name}
 * would shadow it, putting the target's name on the wire where the event's own name belongs.
 */
public record TargetLoginChanged(
    UUID eventId,
    String targetId,
    String targetName,
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
      String targetId,
      String targetName,
      String claude,
      String kimi,
      String checkedAt,
      Instant occurredAt) {
    this(null, targetId, targetName, claude, kimi, checkedAt, occurredAt);
  }
}
