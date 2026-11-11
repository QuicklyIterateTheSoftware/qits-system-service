package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A lease's resolvers and its refresh (qits-2091), end to end over a real runner socket: every
 * report's resolvers land on the row — RUNNING on a RUNNING lease included, and without an event —
 * and an owner's refresh raises the generation the next estate carries.
 */
@QuarkusTest
class TargetLeaseResolversTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

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

  private static LeaseReport running(UUID lease, List<String> resolvers, boolean stale) {
    return new LeaseReport(
        lease.toString(), RUNNING, null, null, null, null, null, null, null, resolvers, stale);
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

  @Test
  void everyReportsResolversLandOnTheRunningLeaseAndPublishNothing() throws Exception {
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.RUNNING, target);
    try (FakeTargetRunner runner = servingRunner()) {
      runner.inventory(List.of(running(id, List.of("1.1.1.1"), true)), List.of());
      until(
          "the stale resolvers",
          () -> TargetRows.reload(id).resolversStale
              && Leases.resolvers(TargetRows.reload(id).resolvers).equals(List.of("1.1.1.1")));
      given()
          .header("Authorization", ContractBearers.bearer("dyn-agent", "qits:agent"))
          .get("/system/api/leases/" + id)
          .then()
          .statusCode(200)
          .body("resolvers", contains("1.1.1.1"))
          .body("resolversStale", equalTo(true))
          .body("generation", equalTo(0))
          .body("state", equalTo("RUNNING"));

      runner.inventory(
          List.of(running(id, List.of("100.96.0.1", "1.1.1.1"), false)), List.of());
      until("the cured resolvers", () -> !TargetRows.reload(id).resolversStale);
      assertEquals(
          List.of("100.96.0.1", "1.1.1.1"), Leases.resolvers(TargetRows.reload(id).resolvers));
      assertEquals(LeaseState.RUNNING, TargetRows.reload(id).state);
      Thread.sleep(500);
      assertTrue(
          bus.published(TargetLeaseChanged.class, e -> e.leaseId().equals(id.toString())).isEmpty(),
          "resolvers are no change: no event");
    }
  }

  @Test
  void aRefreshPushesAnEstateCarryingTheNewGeneration() throws Exception {
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.RUNNING, target);
    try (FakeTargetRunner runner = servingRunner()) {
      given()
          .header("Authorization", ContractBearers.bearer("dev-qits-workspaces", "qits:system"))
          .post("/system/api/leases/" + id + "/refresh")
          .then()
          .statusCode(200)
          .body("generation", equalTo(1));
      Estate estate =
          runner.await(
              Estate.class,
              e -> e.leases().stream().anyMatch(l -> l.leaseId().equals(id.toString()) && l.generation() == 1));
      LeaseEntry entry =
          estate.leases().stream().filter(l -> l.leaseId().equals(id.toString())).findFirst().orElseThrow();
      assertEquals(1L, entry.generation());
    }
  }
}
