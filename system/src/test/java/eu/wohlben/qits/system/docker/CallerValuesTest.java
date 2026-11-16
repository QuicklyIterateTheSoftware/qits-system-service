package eu.wohlben.qits.system.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The first checkpoint on every caller-supplied string that reaches a docker argv. The shape is the
 * library's ({@code ContainersIdentifiersTest} owns it); what is asserted here is that this
 * service's callers are still answered a 400 in this service's words, never a 500.
 */
class CallerValuesTest {

  @Test
  void acceptsTheReferencesDockerItselfPrints() {
    // A swarm task name is what an operator copies out of `docker ps` on this platform, so it has
    // to pass: dots and digits and dashes.
    assertEquals(
        "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m",
        CallerValues.requireContainerRef("dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m"));
    assertEquals("b248472f7a8e", CallerValues.requireContainerRef("b248472f7a8e"));
    assertEquals("qits_shared_m2", CallerValues.requireContainerRef("qits_shared_m2"));
    assertEquals("dev-qits-ci", CallerValues.requireSwarmRef("service", "dev-qits-ci"));
  }

  @Test
  void refusesWhatCouldForgeAnElementBoundaryAsA400() {
    // A leading dash is read as an option, not as a positional argument.
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef("-f"));
    // A space, a slash, a colon and a newline each move a boundary docker or a log line relies on.
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef("a b"));
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef("a/b"));
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef("a:b"));
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef("a\nb"));
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef(""));
    assertThrows(BadRequestException.class, () -> CallerValues.requireContainerRef(null));
    assertThrows(
        BadRequestException.class, () -> CallerValues.requireContainerRef("x".repeat(200)));
    assertThrows(BadRequestException.class, () -> CallerValues.requireSwarmRef("node", "a/b"));
    assertThrows(BadRequestException.class, () -> CallerValues.requireSwarmRef("service", ""));
  }

  @Test
  void aRefusalNamesTheFieldAndCannotForgeASecondLogLine() {
    // The rejected value ends up in a log and in an HTTP body, so its control characters are gone.
    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () -> CallerValues.requireContainerRef("bad\nlevel=fatal msg=owned"));

    assertTrue(refused.getMessage().startsWith("not a valid container: "), refused.getMessage());
    assertTrue(refused.getMessage().contains("bad?level"), refused.getMessage());
    assertEquals(1, refused.getMessage().lines().count(), refused.getMessage());

    BadRequestException node =
        assertThrows(BadRequestException.class, () -> CallerValues.requireSwarmRef("node", "a/b"));
    assertEquals("not a valid node: a/b", node.getMessage());
  }

  @Test
  void onlyAFullIdMayReachAnExec() {
    String id = "b248472f7a8e00bba8cd036232373baed8856d90eb77b06c53f3eb0b321fdb17";
    assertEquals(id, CallerValues.requireFullId(id));
    // A prefix is a valid REFERENCE and not a valid exec target: the exec argv carries only what
    // the daemon printed when the reference was resolved.
    assertThrows(BadRequestException.class, () -> CallerValues.requireFullId("b248472f7a8e"));
    assertThrows(BadRequestException.class, () -> CallerValues.requireFullId(id.toUpperCase()));
  }

  @Test
  void aTailIsBounded() {
    assertEquals(200, CallerValues.requireTail(200, 5000));
    assertThrows(BadRequestException.class, () -> CallerValues.requireTail(0, 5000));
    assertThrows(BadRequestException.class, () -> CallerValues.requireTail(-1, 5000));
    // A log panel is a glance at the last few hundred lines, not a log store.
    assertThrows(BadRequestException.class, () -> CallerValues.requireTail(100_000, 5000));
  }

  @Test
  void aSessionIdIsThisServicesOwnUuid() {
    assertNotNull(CallerValues.parseSessionId("11111111-2222-3333-4444-555555555555"));
    // Not a 400: the caller named a session, it just does not exist. The controller answers 404.
    assertNull(CallerValues.parseSessionId("nonsense"));
    assertNull(CallerValues.parseSessionId(null));
  }
}
