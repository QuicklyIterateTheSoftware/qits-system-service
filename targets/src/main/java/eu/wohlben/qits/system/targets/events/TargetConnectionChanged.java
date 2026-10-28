package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A target's runner came or went: whether this process SERVES the target — a connection that said
 * {@code hello} at the pin, is not draining and is open — flipped (qits-1674). {@code connected}
 * true when the first such connection is greeted; false when the last one closes, or starts draining
 * because its runner is not at the pin. A handover, where a runner at the new pin says {@code hello}
 * while the old one still serves and is then retired, keeps the target served throughout and
 * publishes nothing; so does a dial that has not said {@code hello}. It says nothing about the
 * reconnect grace: a target dropped a moment ago is {@code connected: false} here even while its
 * leases are still left alone.
 *
 * <p>{@code runnerVersion} is the version the serving runner said in its {@code hello}; on {@code
 * connected: false} the version of the connection that stopped serving, and null when none is known.
 *
 * <p>{@code targetName}, not {@code name}: {@link QitsEvent#name()} defaults to {@link
 * QitsEvent#signature()} — the simple class name, which is what a subscriber selects on and what
 * qits-events stores in the row's {@code name} column — and a record component named {@code name}
 * would shadow it, putting the target's name on the wire where the event's own name belongs.
 */
public record TargetConnectionChanged(
    UUID eventId,
    String targetId,
    String targetName,
    boolean connected,
    String runnerVersion,
    Instant occurredAt)
    implements QitsEvent {

  public TargetConnectionChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  public TargetConnectionChanged(
      String targetId,
      String targetName,
      boolean connected,
      String runnerVersion,
      Instant occurredAt) {
    this(null, targetId, targetName, connected, runnerVersion, occurredAt);
  }
}
