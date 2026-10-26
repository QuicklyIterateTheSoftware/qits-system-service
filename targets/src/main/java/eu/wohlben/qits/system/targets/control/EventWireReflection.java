package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.system.targets.events.TargetLoginChanged;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What {@code QitsEventBus.publish} binds to and from JSON here, told to native-image (qits-1526).
 * No code, no bean: the annotation is the entire content. Copied from
 * qits-workspaces-service's {@code bus/EventWireReflection}, which carries the same registration
 * for its own lifecycle events and for {@link TargetLeaseChanged} received back from this module.
 *
 * <p><b>Why {@link TargetEvents}' own {@code @RegisterForReflection} was not enough.</b> It named
 * {@link TargetLeaseChanged} and {@link TargetLoginChanged} — the records {@code QitsEventBus}
 * serializes — but never {@link EventEnvelope}, the PUT body that carries the serialized event to
 * qits-events. {@code CanonicalJson} builds its own {@code ObjectMapper} by hand — the canonical
 * form is a wire contract compared byte for byte — so to the build step scanning for what needs
 * reflection, that mapper and everything it touches, {@link EventEnvelope} included, are invisible.
 * Left unregistered, the native binary serializes the envelope empty and qits-events refuses every
 * publish with 400 {@code publish.request.name must not be blank}, {@code
 * publish.request.occurredAt must not be null} — the JVM suite stays green throughout, because the
 * JVM needs no such registration.
 *
 * <p><b>No {@code EventFrame}.</b> qits-system-service subscribes to no durable or raw event
 * listener and never reads {@code /events/stream}, so nothing here binds a frame; add it the day
 * that changes.
 *
 * <p><b>And the mix-in by name.</b> {@code CanonicalJson$QitsEventMixin} keeps {@code QitsEvent}'s
 * declared methods — {@code eventId} above all — out of a payload, and Jackson finds its {@code
 * @JsonIgnore}s by reflection. Left out, nothing crashes: {@code eventId} simply appears in every
 * payload, which is the worse failure. A string because the class is private.
 */
@RegisterForReflection(
    targets = {TargetLeaseChanged.class, TargetLoginChanged.class, EventEnvelope.class},
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
