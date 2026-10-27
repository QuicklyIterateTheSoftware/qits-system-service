package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.system.targets.events.SoftwareReleaseListener.SoftwareReleasePayload;
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
 * <p><b>And what it reads (qits-1692).</b> {@code events/SoftwareReleaseListener} consumes qits-ci's
 * {@code SoftwareRelease} durably, so three more types cross a hand-built mapper here: {@link
 * EventFrame}, the live frame off {@code /events/stream} and every row of the catch-up log; {@code
 * EventPage}, one page of {@code GET /events/api/events}, by name because it is package-private in
 * the library — without it the stream works in the binary and catch-up alone fails; and {@link
 * SoftwareReleasePayload}, what {@code CanonicalJson.payloadTo} binds the payload into. Left out,
 * the binary binds an empty payload, no slot image is ever kept warm, and the JVM suite stays
 * green. qits-maintenance-service registers the same three for its own listener.
 *
 * <p><b>And the mix-in by name.</b> {@code CanonicalJson$QitsEventMixin} keeps {@code QitsEvent}'s
 * declared methods — {@code eventId} above all — out of a payload, and Jackson finds its {@code
 * @JsonIgnore}s by reflection. Left out, nothing crashes: {@code eventId} simply appears in every
 * payload, which is the worse failure. A string because the class is private.
 */
@RegisterForReflection(
    targets = {
      TargetLeaseChanged.class,
      TargetLoginChanged.class,
      EventEnvelope.class,
      EventFrame.class,
      SoftwareReleasePayload.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin",
      "eu.wohlben.qits.eventstream.control.EventPage"
    })
public final class EventWireReflection {

  private EventWireReflection() {}
}
