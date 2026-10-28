package eu.wohlben.qits.system.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.system.targets.events.TargetConnectionChanged;
import eu.wohlben.qits.system.targets.runner.TargetRegistry;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetConnectionChanged} (qits-1674), end to end: a real runner socket against the real
 * registry, and the events as they reach the bus ({@link RecordingEventBus}). One event per FLIP
 * of whether the target is served — exactly one {@code true} for a greeting, exactly one {@code
 * false} for the last close — and none for a handover, where some connection serves throughout.
 *
 * <p>A handover across runner VERSIONS cannot be driven here: the pin is fixed for the life of the
 * process ({@code TargetPins}), so the suite has one version a runner is served at. What it can
 * drive are the two handovers that pin allows — a runner re-dialling at the pin, which replaces its
 * own connection ({@link TargetRegistry#ALREADY_CONNECTED}), and a runner at another version
 * dialling beside the serving one, which is told to upgrade and drains — and both keep the target
 * served at every step, which is the property the registry compares.
 */
@QuarkusTest
class TargetConnectionEventsTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  /** How long a test waits after the events it expects, for one it must not see. */
  private static final Duration SETTLE = Duration.ofMillis(500);

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject RecordingEventBus bus;

  private UUID target;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, CLIENT, false);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  /** Dials and says hello at the pin; the target is not quarantined, so it is served at once. */
  private FakeTargetRunner servingRunner() throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    assertEquals(4, runner.await(Ack.class).slots());
    runner.await(Estate.class);
    return runner;
  }

  private List<TargetConnectionChanged> events(int count) throws InterruptedException {
    return bus.await(
        TargetConnectionChanged.class, e -> e.targetId().equals(target.toString()), count, SETTLE);
  }

  @Test
  void aHelloAtThePinIsAnnouncedAsConnectedOnce() throws Exception {
    try (FakeTargetRunner runner = servingRunner()) {
      List<TargetConnectionChanged> events = events(1);
      assertEquals(1, events.size(), events.toString());
      TargetConnectionChanged connected = events.get(0);
      assertTrue(connected.connected());
      assertEquals("lab", connected.targetName());
      assertEquals(SystemRunnerBinary.VERSION, connected.runnerVersion());
      assertEquals("TargetConnectionChanged", connected.name());
    }
  }

  @Test
  void closingTheSocketIsAnnouncedAsDisconnectedOnce() throws Exception {
    FakeTargetRunner runner = servingRunner();
    events(1);
    runner.close();
    List<TargetConnectionChanged> events = events(2);
    assertEquals(2, events.size(), events.toString());
    TargetConnectionChanged gone = events.get(1);
    assertFalse(gone.connected());
    assertEquals(
        SystemRunnerBinary.VERSION,
        gone.runnerVersion(),
        "the version is the one the connection that stopped serving said");
  }

  @Test
  void aHandoverAnnouncesNothing() throws Exception {
    FakeTargetRunner first = servingRunner();
    assertEquals(1, events(1).size());

    // A runner at another version, beside the serving one: upgraded, draining, never served.
    try (FakeTargetRunner stale = FakeTargetRunner.dial(base, BEARER)) {
      stale.hello("2020.101.1", 4, "qits-system-runner-dot-claude-lab");
      stale.await(Upgrade.class);
      assertEquals(0, stale.await(Ack.class).slots());
    }

    // The runner re-dials at the pin: the new connection replaces the old one.
    try (FakeTargetRunner second = servingRunner()) {
      assertEquals(RunnerWire.CLOSE_POLICY_VIOLATION, first.awaitClose());
      assertEquals(TargetRegistry.ALREADY_CONNECTED, first.closeReason());
      first.close();
      List<TargetConnectionChanged> events = events(1);
      assertEquals(1, events.size(), "a handover keeps the target served: " + events);
      assertTrue(events.get(0).connected());
    }
    List<TargetConnectionChanged> events = events(2);
    assertEquals(2, events.size(), events.toString());
    assertFalse(events.get(1).connected(), "only the last serving connection's close flips it");
  }
}
