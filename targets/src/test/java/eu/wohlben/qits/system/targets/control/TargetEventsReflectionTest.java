package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.system.targets.events.TargetLoginChanged;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetEvents} hands every event it builds to {@code QitsEventBus.publish}, which
 * serializes it with Jackson. The native-image build only discovers reflection for a JAX-RS
 * method's declared return type, which an event handed to the bus never is — so a forgotten one
 * surfaces only as "could not be published or recorded" in the native binary, never in a JVM test.
 * This asserts every record {@code targets/events/} declares is covered by {@link TargetEvents}'
 * {@link RegisterForReflection}, so the next one joins the list in the same commit that adds it.
 */
class TargetEventsReflectionTest {

  @Test
  void everyEventClassIsRegisteredForReflection() {
    RegisterForReflection registration = TargetEvents.class.getAnnotation(RegisterForReflection.class);
    assertTrue(registration != null, "TargetEvents must carry @RegisterForReflection");

    List<Class<?>> registered = Arrays.asList(registration.targets());
    assertTrue(
        registered.contains(TargetLeaseChanged.class),
        "TargetLeaseChanged must be in TargetEvents' @RegisterForReflection targets");
    assertTrue(
        registered.contains(TargetLoginChanged.class),
        "TargetLoginChanged must be in TargetEvents' @RegisterForReflection targets");
  }
}
