package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.system.targets.events.TargetLoginChanged;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetEvents} hands every event it builds to {@code QitsEventBus.publish}, which
 * serializes it — and the envelope around it — with Jackson through {@code CanonicalJson}'s own
 * hand-built {@code ObjectMapper}, invisible to the build-time analysis that drives native-image.
 * {@link EventWireReflection} is the registration that covers it; this is qits-system's copy of
 * qits-workspaces-service's {@code EventWireReflectionTest}, guarding its <em>completeness</em>: on
 * a JVM every class reflects whether registered or not, so nothing below proves the native binary
 * publishes — only running it does. What is checkable is that the registration still names every
 * {@code QitsEvent} record {@code targets/events/} declares, the PUT body, and that the mix-in
 * string entry still resolves — so the next event record joins the list in the same commit that
 * adds it.
 */
class TargetEventsReflectionTest {

  private static final String MIXIN =
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  @Test
  void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(TargetLeaseChanged.class, TargetLoginChanged.class, EventEnvelope.class),
        Set.of(registration.targets()),
        "the two targets events, plus the PUT body — a new QitsEvent record in targets/events/"
            + " joins here");
  }

  @Test
  void theMixinNamedByStringStillExistsAndStillHidesTheEnvelopesFields() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertEquals(
        MIXIN,
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames())
            .iterator()
            .next());
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(eventId.getAnnotation(JsonIgnore.class));
  }

  /**
   * The flow the registration is for: canonicalized, PUT and bound back. {@link TargetLeaseChanged}
   * rather than {@link TargetLoginChanged}: the latter declares its own {@code name} record
   * component (the target's name), which shadows {@code QitsEvent.name()}'s {@code signature()}
   * default and would put the wrong value in the envelope's wire {@code name} — a real but separate
   * defect from the one this file guards, left for a ticket of its own.
   */
  @Test
  void theWholeWirePathBindsBothWays() {
    TargetLeaseChanged out =
        new TargetLeaseChanged(
            "lease-1",
            "qits-workspaces",
            "385",
            "WORKSPACE",
            "target-1",
            "RUNNING",
            null,
            null,
            java.time.Instant.parse("2026-10-26T18:30:05Z"));

    EventEnvelope envelope = EventEnvelope.of(out);
    assertEquals(
        "TargetLeaseChanged",
        eu.wohlben.qits.eventstream.control.CanonicalJson.parse(
                eu.wohlben.qits.eventstream.control.CanonicalJson.envelope(envelope))
            .get("name")
            .asText());

    TargetLeaseChanged back =
        eu.wohlben.qits.eventstream.control.CanonicalJson.payloadTo(
            envelope.payload(), TargetLeaseChanged.class);

    assertTrue(back.leaseId().equals(out.leaseId()));
    assertEquals(out.ownerRef(), back.ownerRef());
    assertEquals(out.state(), back.state());
  }
}
