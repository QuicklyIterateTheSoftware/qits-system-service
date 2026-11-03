package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dial-back streak (qits-1809): an owner's end of a RUNNING CI lease with {@code
 * never-dialled-back} counts on its target, any other end of one resets the count, three in a row
 * quarantine the target, and a greenlight resets it. A GRANTED CI lease its runner reports FAILED
 * counts in the same streak (qits-525).
 */
@QuarkusTest
class DialbackStreakTest {

  private static final String API = "/system/api/leases";

  @Inject Leases leases;

  @Inject ContractBearers bearers;

  @TestHTTPResource("/")
  URI base;

  private UUID target;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    target = UUID.randomUUID();
    TargetRows.target(target, "workstation", 6, "client-workstation", false);
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  private UUID running(String ownerRef) {
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", ownerRef, LeaseState.RUNNING, target);
    return id;
  }

  private void end(UUID lease, String reason) {
    LeaseDoorsTest.service("dev-qits-ci")
        .delete(API + "/" + lease + (reason == null ? "" : "?reason=" + reason))
        .then()
        .statusCode(200)
        .body("state", anyOf(equalTo("ENDING"), equalTo("ENDED")));
  }

  @Test
  void anOwnersReasonLandsInTheLeasesDetail() {
    UUID lease = running("run-1");
    end(lease, "never-dialled-back");
    assertEquals("never-dialled-back", TargetRows.reload(lease).detail);
    UUID quiet = running("run-2");
    end(quiet, null);
    assertNull(TargetRows.reload(quiet).detail, "no reason leaves the detail as it was");
  }

  @Test
  void aNeverDialledBackEndCountsAndStampsTheTarget() {
    Instant before = Instant.now();
    end(running("run-1"), "never-dialled-back");
    end(running("run-2"), "never-dialled-back");
    Target row = TargetRows.reloadTarget(target);
    assertEquals(2, row.dialbackFailures);
    assertNotNull(row.dialbackFailedAt);
    assertFalse(row.dialbackFailedAt.isBefore(before));
    assertFalse(row.quarantined(), "two are below the threshold");
  }

  @Test
  void aRunningCiLeaseEndedForAnyOtherReasonResetsTheCount() {
    end(running("run-1"), "never-dialled-back");
    end(running("run-2"), "never-dialled-back");
    end(running("run-3"), "cancelled");
    assertEquals(0, TargetRows.reloadTarget(target).dialbackFailures);
    end(running("run-4"), "never-dialled-back");
    end(running("run-5"), null);
    assertEquals(0, TargetRows.reloadTarget(target).dialbackFailures, "no reason resets too");
  }

  @Test
  void threeInARowQuarantineTheTargetNamingTheFailuresAndTheLastLease() {
    end(running("run-1"), "never-dialled-back");
    end(running("run-2"), "never-dialled-back");
    UUID last = running("run-3");
    end(last, "never-dialled-back");
    Target row = TargetRows.reloadTarget(target);
    assertEquals(3, row.dialbackFailures);
    assertTrue(row.quarantined());
    assertEquals(
        "dial-back: 3 CI leases in a row never dialled back their ci daemon; last lease "
            + last
            + " (run-3)",
        row.quarantineReason);

    // A fourth on the quarantined target renews the reason and keeps when it began.
    Instant began = row.quarantinedAt;
    UUID fourth = running("run-4");
    end(fourth, "never-dialled-back");
    row = TargetRows.reloadTarget(target);
    assertEquals(4, row.dialbackFailures);
    assertEquals(began, row.quarantinedAt);
    assertTrue(row.quarantineReason.contains("4 CI leases") && row.quarantineReason.contains(fourth.toString()));
  }

  @Test
  void otherSlotTypesAndLeasesThatNeverRanLeaveTheCountAlone() {
    end(running("run-1"), "never-dialled-back");

    UUID workspace = UUID.randomUUID();
    TargetRows.lease(workspace, SlotType.WORKSPACE, "qits-workspaces", "ws-1", LeaseState.RUNNING, target);
    LeaseDoorsTest.service("dev-qits-workspaces").delete(API + "/" + workspace).then().statusCode(200);

    UUID granted = UUID.randomUUID();
    TargetRows.lease(granted, SlotType.CI, "qits-ci", "run-2", LeaseState.GRANTED, target);
    end(granted, null);

    UUID exited = UUID.randomUUID();
    TargetRows.lease(exited, SlotType.CI, "qits-ci", "run-3", LeaseState.EXITED, target);
    end(exited, "never-dialled-back");

    assertEquals(1, TargetRows.reloadTarget(target).dialbackFailures);
  }

  @Test
  void anOperatorsEndIsNotAnOwnersAndLeavesTheCountAlone() {
    end(running("run-1"), "never-dialled-back");
    UUID lease = running("run-2");
    given()
        .header("Authorization", ContractBearers.bearer("an-operator", "qits:admin"))
        .delete(API + "/" + lease + "?reason=never-dialled-back")
        .then()
        .statusCode(200);
    assertEquals(1, TargetRows.reloadTarget(target).dialbackFailures);
  }

  @Test
  void aGreenlightResetsTheCount() {
    for (int i = 1; i <= 3; i++) {
      end(running("run-" + i), "never-dialled-back");
    }
    assertTrue(TargetRows.reloadTarget(target).quarantined());
    given()
        .header("Authorization", ContractBearers.bearer("an-operator", "qits:admin"))
        .post("/system/api/targets/" + target + "/greenlight")
        .then()
        .statusCode(200)
        .body("quarantined", is(false));
    Target row = TargetRows.reloadTarget(target);
    assertEquals(0, row.dialbackFailures);
    assertFalse(row.quarantined());
  }

  private UUID granted(SlotType type, String ownerRef) {
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, type, "qits-ci", ownerRef, LeaseState.GRANTED, target);
    return id;
  }

  private void failedToStart(UUID lease) {
    List<Lease> changed =
        leases.applyInventory(
            target, List.of(new Leases.Held(lease, "FAILED", null, null, "pull failed")));
    assertEquals(1, changed.size());
    assertEquals(LeaseState.FAILED, TargetRows.reload(lease).state);
  }

  @Test
  void aGrantedCiLeaseItsRunnerFailedToStartCounts() {
    Instant before = Instant.now();
    failedToStart(granted(SlotType.CI, "run-1"));
    Target row = TargetRows.reloadTarget(target);
    assertEquals(1, row.dialbackFailures);
    assertNotNull(row.dialbackFailedAt);
    assertFalse(row.dialbackFailedAt.isBefore(before));
    assertFalse(row.quarantined());
  }

  @Test
  void threeStartFailuresInARowQuarantineTheTarget() {
    failedToStart(granted(SlotType.CI, "run-1"));
    failedToStart(granted(SlotType.CI, "run-2"));
    UUID last = granted(SlotType.CI, "run-3");
    failedToStart(last);
    Target row = TargetRows.reloadTarget(target);
    assertEquals(3, row.dialbackFailures);
    assertTrue(row.quarantined());
    assertEquals(
        "start: 3 CI leases in a row failed to start on this target; last lease "
            + last
            + " (run-3): pull failed",
        row.quarantineReason);
  }

  @Test
  void aStartFailureOfAnotherSlotTypeLeavesTheCountAlone() {
    UUID workspace = UUID.randomUUID();
    TargetRows.lease(workspace, SlotType.WORKSPACE, "qits-workspaces", "ws-1", LeaseState.GRANTED, target);
    failedToStart(workspace);
    assertEquals(0, TargetRows.reloadTarget(target).dialbackFailures);
  }

  @Test
  void aRunningCiLeaseReportedFailedLeavesTheCountAlone() {
    failedToStart(running("run-1"));
    assertEquals(0, TargetRows.reloadTarget(target).dialbackFailures);
  }

  @Test
  void aLeaseThatDialledBackResetsAStartFailure() {
    failedToStart(granted(SlotType.CI, "run-1"));
    assertEquals(1, TargetRows.reloadTarget(target).dialbackFailures);
    end(running("run-2"), null);
    assertEquals(0, TargetRows.reloadTarget(target).dialbackFailures);
  }

  @Test
  void aStartFailureThatQuarantinesTheTargetTellsItsRunner() throws Exception {
    target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, "target-runner-client-streak", true);
    bearers.grant("Bearer streak", "target-runner-client-streak", "qits:target-runner");
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, "Bearer streak")) {
      runner.hello(2, "vol");
      HealthCheck check = runner.await(HealthCheck.class);
      runner.healthChecked(check.requestId(), true);
      runner.await(Reinstated.class);
      runner.await(Ack.class, a -> a.slots() == 2);
      failedToStart(granted(SlotType.CI, "run-1"));
      failedToStart(granted(SlotType.CI, "run-2"));
      UUID last = granted(SlotType.CI, "run-3");
      runner.inventory(
          List.of(
              FakeTargetRunner.report(
                  last.toString(), eu.wohlben.qits.systemrunner.protocol.LeaseState.FAILED)),
          List.of());
      Quarantined told = runner.await(Quarantined.class, q -> q.reason().startsWith("start: "));
      assertTrue(told.reason().contains(last.toString()), told.reason());
      assertEquals(0, runner.await(Ack.class, a -> a.slots() == 0).slots());
      Target row = TargetRows.reloadTarget(target);
      assertTrue(row.quarantined());
      assertEquals(row.quarantineReason, told.reason());
    }
  }

  /** Sets the streak directly, for the socket test's health checks. */
  static void streak(UUID target, int failures, Instant failedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Target row = Target.findById(target);
              row.dialbackFailures = failures;
              row.dialbackFailedAt = failedAt;
            });
  }
}
