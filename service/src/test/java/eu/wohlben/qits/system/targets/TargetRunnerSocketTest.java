package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.system.targets.control.SlotAllocator;
import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerAck;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner socket end to end: a real WebSocket speaking the protocol jar's frames, against the
 * real registry, allocator and store. What a runner is told, in order; what its reports move; and
 * the sign-in gate, which only a {@code loginState} opens.
 */
@QuarkusTest
class TargetRunnerSocketTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  static final String WORKSPACE_IMAGE = "registry.qits.wohlben.eu/qits/workspace:2026.1";

  static final String CI_IMAGE = "registry.qits.wohlben.eu/qits/build-images/ci-slot:2026.1";

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject TargetAllocation allocation;

  private UUID target;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, CLIENT, true);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  /** Dials, says hello with 4 slots, passes the first health check and is greeted with 4. */
  private FakeTargetRunner greetedRunner() throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    Ack first = runner.await(Ack.class);
    assertEquals(0, first.slots(), "a quarantined target is acked with no slots");
    runner.await(Quarantined.class);
    runner.await(Estate.class);
    HealthCheck check = runner.await(HealthCheck.class);
    runner.healthChecked(check.requestId(), true);
    runner.await(Reinstated.class);
    assertEquals(4, runner.await(Ack.class).slots());
    return runner;
  }

  @Test
  void aGreetedRunnerIsAckedWithTheSlotImagesAndItsSlotsAreTheTotal() throws Exception {
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER)) {
      runner.hello(4, "qits-system-runner-dot-claude-lab");
      Ack ack = runner.await(Ack.class);
      SystemRunnerAck extension = SystemRunnerAck.of(ack);
      assertEquals(SystemRunnerProtocol.CAPABILITY_VERSION, ack.capabilityVersion());
      assertEquals("registry.qits.wohlben.eu/qits/build-images/ci-slot", extension.slotImages().ci());
      assertEquals("registry.qits.wohlben.eu/qits/workspace", extension.slotImages().workspace());
      assertEquals("registry.qits.wohlben.eu/qits/project-agent", extension.slotImages().desk());
      assertEquals("registry.qits.wohlben.eu/qits/workspace:latest", extension.probeImage());
      assertEquals("mirror.qits.wohlben.eu/hub", extension.registryMirrors().get("docker.io"));
      Target row = TargetRows.reloadTarget(target);
      assertEquals(4, row.slotsTotal);
      assertEquals("qits-system-runner-dot-claude-lab", row.credentialVolume);
      assertTrue(row.capabilities.contains("ENGINE-1"), row.capabilities);
    }
  }

  @Test
  void aPassingHealthCheckReinstatesTheTarget() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      Target row = TargetRows.reloadTarget(target);
      assertFalse(row.quarantined());
      assertTrue(row.lastHealthCheckOk);
      assertTrue(row.health.contains("\"slots\""), row.health);
    }
  }

  @Test
  void aFailingHealthCheckQuarantinesAgain() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      String requestId =
          given()
              .header("X-Qits-User", "an-operator")
              .header("X-Qits-Roles", "qits:admin")
              .post("/system/api/targets/" + target + "/healthcheck")
              .then()
              .statusCode(202)
              .extract()
              .path("requestId");
      runner.await(HealthCheck.class, c -> c.requestId().equals(requestId));
      runner.healthChecked(requestId, false);
      Quarantined quarantined = runner.await(Quarantined.class);
      assertTrue(quarantined.reason().contains("docker is gone"), quarantined.reason());
      assertEquals(0, runner.await(Ack.class).slots());
    }
  }

  @Test
  void theSignInGateHoldsAnAgentLeaseUntilALoginStateSaysPresent() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      String workspace =
          LeaseDoorsTest.lease("dev-qits-workspaces", "WORKSPACE", "17", WORKSPACE_IMAGE, ",\"workKey\":\"ws-17\"")
              .then()
              .statusCode(201)
              .extract()
              .path("id");
      allocation.pass();
      Lease waiting = TargetRows.reload(UUID.fromString(workspace));
      assertEquals(LeaseState.QUEUED, waiting.state);
      assertEquals(SlotAllocator.notSignedIn("lab"), waiting.detail);
      given()
          .header("X-Qits-User", "an-operator")
          .header("X-Qits-Roles", "qits:admin")
          .get("/system/api/targets/" + target)
          .then()
          .body("loginCommand", nullValue());

      runner.loginState(LoginPresence.PRESENT, LoginPresence.ABSENT, "qits-system-runner-dot-claude-lab");
      Estate estate =
          runner.await(Estate.class, e -> e.leases().stream().anyMatch(l -> l.leaseId().equals(workspace)));
      var entry = estate.leases().get(0);
      assertEquals(DesiredState.RUNNING, entry.desired());
      assertEquals("ws-17", entry.workVolume(), "the estate carries the work key");
      assertEquals(WORKSPACE_IMAGE, entry.spec().image());
      assertEquals("1001", entry.spec().user());
      assertEquals("2g", entry.spec().limits().memory());
      Lease granted = TargetRows.reload(UUID.fromString(workspace));
      assertEquals(LeaseState.GRANTED, granted.state);
      assertEquals(target, granted.targetId);
      Target row = TargetRows.reloadTarget(target);
      assertEquals(LoginValue.PRESENT, row.loginClaude);
      given()
          .header("X-Qits-User", "an-operator")
          .header("X-Qits-Roles", "qits:admin")
          .get("/system/api/targets/" + target)
          .then()
          .body(
              "loginCommand",
              equalTo(
                  "docker run --rm -it --user 1000 --entrypoint claude -v"
                      + " qits-system-runner-dot-claude-lab:/claude-home -e HOME=/claude-home -e"
                      + " CLAUDE_CONFIG_DIR=/claude-home/.claude"
                      + " registry.qits.wohlben.eu/qits/workspace:latest"))
          .body("kimiLoginCommand", startsWith("docker run --rm -it --user 1000 --entrypoint kimi"));
    }
  }

  @Test
  void ciIsNeverGatedAndTheInventoryMovesTheLeaseToTheEnd() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      String id =
          LeaseDoorsTest.lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "")
              .then()
              .statusCode(201)
              .extract()
              .path("id");
      runner.await(Estate.class, e -> e.leases().stream().anyMatch(l -> l.leaseId().equals(id)));
      assertEquals(LeaseState.GRANTED, TargetRows.reload(UUID.fromString(id)).state);

      runner.inventory(
          List.of(FakeTargetRunner.report(id, eu.wohlben.qits.systemrunner.protocol.LeaseState.RUNNING)),
          List.of());
      awaitState(id, LeaseState.RUNNING);

      LeaseDoorsTest.service("dev-qits-ci")
          .delete("/system/api/leases/" + id)
          .then()
          .statusCode(200)
          .body("state", equalTo("ENDING"));
      runner.await(
          Estate.class,
          e ->
              e.leases().stream()
                  .anyMatch(l -> l.leaseId().equals(id) && l.desired() == DesiredState.ENDED));
      runner.inventory(List.of(), List.of());
      Lease ended = awaitState(id, LeaseState.ENDED);
      assertNotNull(ended.endedAt);
    }
  }

  @Test
  void anExitedLeaseIsKeptRunningInTheEstateUntilItsOwnerEndsIt() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      String id =
          LeaseDoorsTest.lease("dev-qits-ci", "CI", "run-2", CI_IMAGE, "").path("id");
      runner.await(Estate.class, e -> e.leases().stream().anyMatch(l -> l.leaseId().equals(id)));
      runner.inventory(
          List.of(FakeTargetRunner.report(id, eu.wohlben.qits.systemrunner.protocol.LeaseState.EXITED)),
          List.of());
      Lease exited = awaitState(id, LeaseState.EXITED);
      assertEquals(0, exited.exitCode);
      assertEquals("RUNNING", allocation.estateOf(target).leases().get(0).desired());
      LeaseDoorsTest.service("dev-qits-ci").delete("/system/api/leases/" + id).then().statusCode(200);
      assertEquals("ENDED", allocation.estateOf(target).leases().get(0).desired());
    }
  }

  @Test
  void aDialWhoseSubjectIsNoTargetIsToldItIsDeleted() throws Exception {
    bearers.grant("Bearer stranger", "no-such-client", "qits:target-runner");
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, "Bearer stranger")) {
      assertEquals(RunnerWire.CLOSE_POLICY_VIOLATION, runner.awaitClose());
      assertEquals(RunnerWire.CloseReason.RUNNER_DELETED, runner.closeReason());
    }
  }

  @Test
  void aRunnerNotAtThePinIsUpgradedAndTakesNothing() throws Exception {
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER)) {
      runner.hello("2020.101.1", 4, "v");
      Upgrade upgrade = runner.await(Upgrade.class);
      assertTrue(upgrade.image().startsWith("registry.qits.wohlben.eu/qits/qits-system-runner:"));
      assertEquals(0, runner.await(Ack.class).slots());
    }
  }

  @Test
  void aDeletedTargetsRunnerIsRetired() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      given()
          .header("X-Qits-User", "an-operator")
          .header("X-Qits-Roles", "qits:admin")
          .delete("/system/api/targets/" + target)
          .then()
          .statusCode(204);
      var retire = runner.await(eu.wohlben.qits.runner.protocol.Retire.class);
      assertEquals(eu.wohlben.qits.runner.protocol.Retire.Kind.DELETED, retire.kind());
      assertEquals(RunnerWire.CLOSE_POLICY_VIOLATION, runner.awaitClose());
      assertNull(TargetRows.reloadTarget(target));
    }
  }

  private static Lease awaitState(String id, LeaseState state) throws InterruptedException {
    long deadline = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    Lease lease = null;
    while (System.currentTimeMillis() < deadline) {
      lease = TargetRows.reload(UUID.fromString(id));
      if (lease.state == state) {
        return lease;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("lease " + id + " never became " + state + "; it is " + lease.state);
  }
}
