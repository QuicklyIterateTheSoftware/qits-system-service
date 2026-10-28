package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.system.targets.events.SoftwareReleaseListener.SoftwareReleasePayload;
import eu.wohlben.qits.system.targets.events.TargetConnectionChanged;
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

  private static final String PAGE = "eu.wohlben.qits.eventstream.control.EventPage";

  @Test
  void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            TargetLeaseChanged.class,
            TargetLoginChanged.class,
            TargetConnectionChanged.class,
            EventEnvelope.class,
            EventFrame.class,
            SoftwareReleasePayload.class),
        Set.of(registration.targets()),
        "the three targets events, the PUT body, the frame and every payload a listener binds — a"
            + " new QitsEvent record in targets/events/ joins here");
  }

  /**
   * qits-1692: {@code events/SoftwareReleaseListener} reads the stream and the catch-up log, so the
   * library's package-private {@code EventPage} is registered by name, and must still resolve.
   */
  @Test
  void theCatchUpPageNamedByStringStillExists() throws Exception {
    Set<String> names =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames());
    assertEquals(Set.of(MIXIN, PAGE), names);
    assertNotNull(Class.forName(PAGE));
  }

  /**
   * The flow the listener depends on in the binary: a real {@code SoftwareRelease} payload, with
   * fields this copy does not declare, bound through {@code CanonicalJson} into the registered
   * record.
   */
  @Test
  void aSoftwareReleasePayloadBindsIntoTheRegisteredRecord() {
    SoftwareReleasePayload release =
        eu.wohlben.qits.eventstream.control.CanonicalJson.payloadTo(
            "{\"packageName\":\"qits/workspace\",\"packageType\":\"docker\","
                + "\"version\":\"2026.1027.193351\",\"repository\":\"r-1\","
                + "\"projectId\":\"p-1\",\"section\":\"artifacts\",\"runId\":\"x\"}",
            SoftwareReleasePayload.class);
    assertEquals("qits/workspace", release.packageName());
    assertEquals("docker", release.packageType());
    assertEquals("2026.1027.193351", release.version());
  }

  @Test
  void theMixinNamedByStringStillExistsAndStillHidesTheEnvelopesFields() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertTrue(
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames())
            .contains(MIXIN));
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(eventId.getAnnotation(JsonIgnore.class));
  }

  /**
   * No {@code QitsEvent} record declares a component named like one of the interface's own default
   * methods ({@code name}, {@code signature}) — {@code eventId} and {@code occurredAt} are the only
   * ones meant to be components, since the interface declares those two abstract. A component named
   * {@code name} would shadow {@code QitsEvent.name()}'s {@code signature()} default and put the
   * wrong value in the envelope's wire {@code name}, exactly as {@link TargetLoginChanged}'s former
   * {@code name} component did (qits-1526, fixed by renaming it to {@code targetName}).
   */
  @Test
  void everyEventsRecordNameIsItsOwnSimpleClassName() {
    for (QitsEvent event :
        new QitsEvent[] {
          new TargetLeaseChanged(
              "lease-1",
              "qits-workspaces",
              "385",
              "WORKSPACE",
              "target-1",
              "RUNNING",
              null,
              null,
              java.time.Instant.now()),
          new TargetLoginChanged(
              "target-1", "runner-1", "PRESENT", "ABSENT", "2026-10-26T18:30:05Z", java.time.Instant.now()),
          new TargetConnectionChanged(
              "target-1", "runner-1", true, "2026.1028.124013", java.time.Instant.now())
        }) {
      assertEquals(event.getClass().getSimpleName(), event.name());
    }
  }

  /**
   * The flow the registration is for: canonicalized, PUT and bound back. {@link TargetLeaseChanged}
   * first, then {@link TargetLoginChanged} on its own below — its {@code targetName} component used
   * to be named {@code name} and shadow {@code QitsEvent.name()}, which this round trip would have
   * caught: the envelope's wire {@code name} would have been the target's name rather than the
   * signature {@code "TargetLoginChanged"}.
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

  /**
   * qits-1526: {@link TargetLoginChanged} used to declare its target-name component as {@code
   * name}, which shadowed {@code QitsEvent.name()}'s {@code signature()} default — the envelope's
   * wire {@code name} would have been the target's name ("runner-1") rather than the signature
   * {@code "TargetLoginChanged"}, and no subscriber selecting this event type would ever have seen
   * it published. The component is {@code targetName} now, and this is the round trip that would
   * have caught the old shape.
   */
  @Test
  void targetLoginChangedKeepsItsSignatureAsTheWireName() {
    TargetLoginChanged out =
        new TargetLoginChanged(
            "target-1",
            "runner-1",
            "PRESENT",
            "ABSENT",
            "2026-10-26T18:30:05Z",
            java.time.Instant.parse("2026-10-26T18:30:05Z"));

    EventEnvelope envelope = EventEnvelope.of(out);
    assertEquals("TargetLoginChanged", envelope.name());
    assertEquals(
        "TargetLoginChanged",
        eu.wohlben.qits.eventstream.control.CanonicalJson.parse(
                eu.wohlben.qits.eventstream.control.CanonicalJson.envelope(envelope))
            .get("name")
            .asText());

    TargetLoginChanged back =
        eu.wohlben.qits.eventstream.control.CanonicalJson.payloadTo(
            envelope.payload(), TargetLoginChanged.class);

    assertEquals(out.targetId(), back.targetId());
    assertEquals(out.targetName(), back.targetName());
  }

  /**
   * qits-1674: {@link TargetConnectionChanged} round trip — its wire name is its signature, and a
   * {@code false} with a null version binds back as itself rather than as a default.
   */
  @Test
  void targetConnectionChangedBindsBothWays() {
    TargetConnectionChanged out =
        new TargetConnectionChanged(
            "target-1", "runner-1", false, null, java.time.Instant.parse("2026-10-28T18:30:05Z"));

    EventEnvelope envelope = EventEnvelope.of(out);
    assertEquals("TargetConnectionChanged", envelope.name());

    TargetConnectionChanged back =
        eu.wohlben.qits.eventstream.control.CanonicalJson.payloadTo(
            envelope.payload(), TargetConnectionChanged.class);

    assertEquals(out.targetId(), back.targetId());
    assertEquals(out.targetName(), back.targetName());
    assertEquals(false, back.connected());
    assertEquals(null, back.runnerVersion());
  }
}
