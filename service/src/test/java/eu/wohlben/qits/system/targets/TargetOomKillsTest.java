package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.system.targets.events.TargetOomKilled;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.LeaseReport;
import eu.wohlben.qits.systemrunner.protocol.NodeMemory;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A target reports its OOM kills (qits-1818), end to end: a real runner socket sends inventories
 * carrying the node's and each lease's memory facts, the rows keep them, an agent reads them on the
 * doors, and the bus is handed {@link TargetLeaseChanged} for a lease's kill and {@link
 * TargetOomKilled} for the node's — and nothing for a fact that is not a kill.
 */
@QuarkusTest
class TargetOomKillsTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final Duration SETTLE = Duration.ofMillis(500);

  private static final long MIB = 1024L * 1024L;

  private static final long NODE_MAX = 8L * 1024L * MIB;

  private static final eu.wohlben.qits.systemrunner.protocol.LeaseState RUNNING =
      eu.wohlben.qits.systemrunner.protocol.LeaseState.RUNNING;

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

  private FakeTargetRunner servingRunner() throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    assertEquals(4, runner.await(Ack.class).slots());
    runner.await(Estate.class);
    return runner;
  }

  private static RequestSpecification agent() {
    return given().header("X-Qits-User", "dyn-agent").header("X-Qits-Roles", "qits:agent");
  }

  private UUID lease(LeaseState state, UUID project) {
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "dev-qits-ci", "run-" + id, state, target);
    if (project != null) {
      TargetRows.project(id, project);
    }
    return id;
  }

  private List<TargetLeaseChanged> leaseEvents(UUID lease) {
    return bus.published(TargetLeaseChanged.class, e -> e.leaseId().equals(lease.toString()));
  }

  private List<TargetOomKilled> nodeEvents() {
    return bus.published(TargetOomKilled.class, e -> e.targetId().equals(target));
  }

  private static void until(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(what + " never happened");
      }
      Thread.sleep(25);
    }
  }

  private static LeaseReport running(
      UUID lease, Long oomKills, Long oomEvents, Long peak, Boolean oomKilled) {
    return FakeTargetRunner.report(lease.toString(), RUNNING, oomKills, oomEvents, peak, oomKilled);
  }

  @Test
  void aLeasesMemoryIsStoredTheKillAloneIsAnnouncedAndAnAgentReadsIt() throws Exception {
    UUID project = UUID.randomUUID();
    UUID id = lease(LeaseState.RUNNING, project);
    try (FakeTargetRunner runner = servingRunner()) {
      runner.inventory(List.of(running(id, 0L, 0L, 512 * MIB, null)), List.of());
      until("the first sample", () -> Long.valueOf(512 * MIB).equals(TargetRows.reload(id).memoryPeakBytes));

      runner.inventory(List.of(running(id, 0L, 0L, 1024 * MIB, null)), List.of());
      until("the grown peak", () -> Long.valueOf(1024 * MIB).equals(TargetRows.reload(id).memoryPeakBytes));
      Thread.sleep(SETTLE.toMillis());
      assertTrue(leaseEvents(id).isEmpty(), "a sample with no kill publishes nothing: " + leaseEvents(id));

      // The node ran out: a kill in the lease without it hitting its own limit, and a lower peak.
      runner.inventory(List.of(running(id, 1L, 0L, 256 * MIB, null)), List.of());
      List<TargetLeaseChanged> events =
          bus.await(
              TargetLeaseChanged.class, e -> e.leaseId().equals(id.toString()), 1, SETTLE);
      assertEquals(1, events.size(), events.toString());
      TargetLeaseChanged killed = events.get(0);
      assertEquals("RUNNING", killed.state(), "the state held; the kill alone is announced");
      assertEquals(Long.valueOf(1), killed.oomKills());
      assertEquals("NODE", killed.oomScope());

      Lease stored = TargetRows.reload(id);
      assertEquals(Long.valueOf(1024 * MIB), stored.memoryPeakBytes, "the peak never lowers");
      assertEquals(Long.valueOf(1), stored.oomKills);

      agent()
          .get("/system/api/leases/" + id)
          .then()
          .statusCode(200)
          .body("oomKills", equalTo(1))
          .body("oomScope", equalTo("NODE"))
          .body("memoryPeakBytes", equalTo((int) (1024 * MIB)))
          .body("oomKilled", nullValue());

      // It hits its own limit and exits: the last sample and OOMKilled stay on the row.
      runner.inventory(
          List.of(
              FakeTargetRunner.report(
                  id.toString(),
                  eu.wohlben.qits.systemrunner.protocol.LeaseState.EXITED,
                  2L,
                  1L,
                  null,
                  Boolean.TRUE)),
          List.of());
      events =
          bus.await(
              TargetLeaseChanged.class, e -> e.leaseId().equals(id.toString()), 2, SETTLE);
      assertEquals(2, events.size(), events.toString());
      assertEquals("EXITED", events.get(1).state());
      assertEquals(Long.valueOf(2), events.get(1).oomKills());
      assertEquals("LEASE", events.get(1).oomScope());
      agent()
          .get("/system/api/leases")
          .then()
          .statusCode(200)
          .body("find { it.id == '" + id + "' }.oomKills", equalTo(2))
          .body("find { it.id == '" + id + "' }.oomScope", equalTo("LEASE"))
          .body("find { it.id == '" + id + "' }.memoryPeakBytes", equalTo((int) (1024 * MIB)))
          .body("find { it.id == '" + id + "' }.oomKilled", equalTo(true));
    }
  }

  @Test
  void aNodeCounterThatRoseIsAnnouncedWithTheProjectsOccupyingTheTarget() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    lease(LeaseState.RUNNING, first);
    lease(LeaseState.GRANTED, first);
    lease(LeaseState.RUNNING, second);
    lease(LeaseState.RUNNING, null);
    lease(LeaseState.EXITED, UUID.randomUUID());
    try (FakeTargetRunner runner = servingRunner()) {
      runner.inventory(List.of(), List.of(), new NodeMemory(null, 10L, NODE_MAX));
      until("the baseline", () -> TargetRows.reloadTarget(target).memoryReportedAt != null);
      Thread.sleep(SETTLE.toMillis());
      assertTrue(nodeEvents().isEmpty(), "a first report is the baseline: " + nodeEvents());

      runner.inventory(List.of(), List.of(), new NodeMemory(null, 13L, NODE_MAX));
      List<TargetOomKilled> events =
          bus.await(TargetOomKilled.class, e -> e.targetId().equals(target), 1, SETTLE);
      assertEquals(1, events.size(), events.toString());
      TargetOomKilled killed = events.get(0);
      assertEquals("lab", killed.targetName());
      assertEquals(3, killed.delta());
      assertNull(killed.nodeOomKills());
      assertEquals(Long.valueOf(13), killed.kernelOomKills());
      assertEquals(Set.of(first, second), Set.copyOf(killed.projectIds()));
      assertEquals(2, killed.projectIds().size(), "distinct: " + killed.projectIds());
      assertEquals("TargetOomKilled", killed.name());

      // The host rebooted: the counter went down, which is a new baseline and no kill.
      runner.inventory(List.of(), List.of(), new NodeMemory(null, 2L, NODE_MAX));
      until("the reboot", () -> Long.valueOf(2).equals(TargetRows.reloadTarget(target).kernelOomKills));

      // A node counter appears (an LXC): its first value is a baseline too, and from then on it is
      // the one compared — the kernel's rising alone is not this node's kill.
      runner.inventory(List.of(), List.of(), new NodeMemory(5L, 2L, NODE_MAX));
      until("the node baseline", () -> Long.valueOf(5).equals(TargetRows.reloadTarget(target).nodeOomKills));
      runner.inventory(List.of(), List.of(), new NodeMemory(5L, 9L, NODE_MAX));
      until("the kernel's rise", () -> Long.valueOf(9).equals(TargetRows.reloadTarget(target).kernelOomKills));
      Thread.sleep(SETTLE.toMillis());
      assertEquals(1, nodeEvents().size(), nodeEvents().toString());

      runner.inventory(List.of(), List.of(), new NodeMemory(6L, 9L, NODE_MAX));
      events = bus.await(TargetOomKilled.class, e -> e.targetId().equals(target), 2, SETTLE);
      assertEquals(2, events.size(), events.toString());
      assertEquals(1, events.get(1).delta());
      assertEquals(Long.valueOf(6), events.get(1).nodeOomKills());

      agent()
          .get("/system/api/targets/" + target)
          .then()
          .statusCode(200)
          .body("memory.nodeOomKills", equalTo(6))
          .body("memory.kernelOomKills", equalTo(9))
          .body("memory.memoryMaxBytes", equalTo(NODE_MAX))
          .body("memory.reportedAt", notNullValue());
    }
  }

  @Test
  void anInventoryWithoutTheFactsChangesNone() throws Exception {
    UUID id = lease(LeaseState.GRANTED, UUID.randomUUID());
    try (FakeTargetRunner runner = servingRunner()) {
      agent()
          .get("/system/api/targets/" + target)
          .then()
          .statusCode(200)
          .body("memory.nodeOomKills", nullValue())
          .body("memory.kernelOomKills", nullValue())
          .body("memory.memoryMaxBytes", nullValue())
          .body("memory.reportedAt", nullValue());

      runner.inventory(
          List.of(
              FakeTargetRunner.report(
                  id.toString(),
                  eu.wohlben.qits.systemrunner.protocol.LeaseState.STARTING,
                  1L,
                  1L,
                  64 * MIB,
                  null)),
          List.of(),
          new NodeMemory(4L, 7L, NODE_MAX));
      until("the facts", () -> TargetRows.reloadTarget(target).memoryReportedAt != null);
      until("the lease's facts", () -> Long.valueOf(1).equals(TargetRows.reload(id).oomKills));
      Target before = TargetRows.reloadTarget(target);

      // A runner before qits-1818: no node, no lease facts; only the STARTING detail moves.
      runner.inventory(
          List.of(
              new LeaseReport(
                  id.toString(),
                  eu.wohlben.qits.systemrunner.protocol.LeaseState.STARTING,
                  null,
                  null,
                  "pulling the image")),
          List.of());
      until("the detail", () -> "pulling the image".equals(TargetRows.reload(id).detail));

      Target after = TargetRows.reloadTarget(target);
      assertEquals(before.nodeOomKills, after.nodeOomKills);
      assertEquals(before.kernelOomKills, after.kernelOomKills);
      assertEquals(before.memoryMaxBytes, after.memoryMaxBytes);
      assertEquals(before.memoryReportedAt, after.memoryReportedAt);
      Lease lease = TargetRows.reload(id);
      assertEquals(Long.valueOf(1), lease.oomKills);
      assertEquals(Long.valueOf(1), lease.oomEvents);
      assertEquals(Long.valueOf(64 * MIB), lease.memoryPeakBytes);
      Thread.sleep(SETTLE.toMillis());
      assertTrue(nodeEvents().isEmpty(), nodeEvents().toString());
    }
  }
}
