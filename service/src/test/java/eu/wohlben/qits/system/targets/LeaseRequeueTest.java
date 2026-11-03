package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.system.targets.control.LeaseEndingDeadline;
import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.control.TargetPresence;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A GRANTED lease does not wait on a target that will never start it (qits-1829), on the sweep's
 * fixed clock: one whose target is gone past the reconnect grace (PT2M) is queued again and granted
 * elsewhere, and a runner nudged about an ENDING lease that still did nothing is quarantined before
 * that lease is ended, so the slot it frees is not granted straight back to it.
 */
@QuarkusTest
class LeaseRequeueTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final String OTHER_BEARER = "Bearer runner-client-token-2";

  private static final String OTHER_CLIENT = "target-runner-client-lab-2";

  private static final Duration GRACE = Duration.ofMinutes(2);

  private static final Duration SETTLE = Duration.ofMillis(300);

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject LeaseEndingDeadline deadline;

  @Inject TargetAllocation allocation;

  @Inject TargetPresence presence;

  @Inject RecordingEventBus bus;

  private UUID target;

  private UUID other;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    other = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, CLIENT, true);
    TargetRows.target(other, "lab-2", 0, OTHER_CLIENT, true);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
    bearers.grant(OTHER_BEARER, OTHER_CLIENT, "qits:target-runner");
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  /** Dials as {@code bearer}, says hello with {@code slots}, passes the first health check. */
  private FakeTargetRunner greetedRunner(String bearer, int slots) throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, bearer);
    runner.hello(slots, "qits-system-runner-dot-claude-lab");
    runner.await(Ack.class);
    runner.await(Quarantined.class);
    runner.await(Estate.class);
    HealthCheck check = runner.await(HealthCheck.class);
    runner.healthChecked(check.requestId(), true);
    runner.await(Reinstated.class);
    assertEquals(slots, runner.await(Ack.class).slots());
    return runner;
  }

  private static UUID ciLease(String ownerRef) {
    return UUID.fromString(
        LeaseDoorsTest.lease("dev-qits-ci", "CI", ownerRef, LeaseDoorsTest.CI_IMAGE, "")
            .then()
            .statusCode(201)
            .extract()
            .path("id"));
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long until = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > until) {
        throw new AssertionError(what);
      }
      Thread.sleep(25);
    }
  }

  private List<TargetLeaseChanged> events(UUID lease, String state) {
    return bus.published(
        TargetLeaseChanged.class,
        e -> e.leaseId().equals(lease.toString()) && e.state().equals(state));
  }

  private static void workKey(UUID lease, String workKey) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Lease row = Lease.findById(lease);
              row.workKey = workKey;
            });
  }

  private static void nudgedAt(UUID lease, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Lease row = Lease.findById(lease);
              row.endingNudgedAt = at;
            });
  }

  @Test
  void aGrantedLeaseOfAGoneTargetIsQueuedAgainAfterTheGraceAndGrantedOnAnother() throws Exception {
    UUID id;
    Instant grantedAt;
    try (FakeTargetRunner runner = greetedRunner(BEARER, 4)) {
      id = ciLease("run-1");
      allocation.pass();
      Lease granted = TargetRows.reload(id);
      assertEquals(LeaseState.GRANTED, granted.state);
      assertEquals(target, granted.targetId);
      grantedAt = granted.grantedAt;
    }
    // The server has seen the close once the target is absent as of a clock past the grace.
    await(
        () -> !presence.present(target, Instant.now().plus(GRACE).plusSeconds(1)),
        "the server never saw the runner go");

    LeaseEndingDeadline.Swept inGrace = deadline.sweep(Instant.now().plus(Duration.ofMinutes(1)));
    assertTrue(inGrace.requeued().isEmpty(), "inside the reconnect grace: still GRANTED");
    assertEquals(LeaseState.GRANTED, TargetRows.reload(id).state);
    assertTrue(TargetRows.audit(id).isEmpty());

    Instant past = Instant.now().plus(GRACE).plusSeconds(1);
    assertEquals(List.of(id), deadline.sweep(past).requeued());
    Lease queued = TargetRows.reload(id);
    assertEquals(LeaseState.QUEUED, queued.state);
    assertNull(queued.targetId);
    assertNull(queued.grantedAt);

    String detail =
        "target lab went away before it started the lease (granted " + grantedAt + "); queued again";
    List<TargetAudit> audit = TargetRows.audit(id);
    assertEquals(1, audit.size());
    assertEquals("LEASE_REQUEUED", audit.get(0).operation);
    assertEquals("qits-system", audit.get(0).actor);
    assertEquals("QUEUED", audit.get(0).outcome);
    assertEquals(target, audit.get(0).targetId);
    assertEquals(detail, audit.get(0).detail);
    JsonNode args = new ObjectMapper().readTree(audit.get(0).args);
    assertEquals("GRANTED", args.path("from").asText(), audit.get(0).args);
    assertEquals("QUEUED", args.path("to").asText(), audit.get(0).args);
    TargetLeaseChanged announced =
        bus.await(
                TargetLeaseChanged.class,
                e -> e.leaseId().equals(id.toString()) && detail.equals(e.detail()),
                1,
                Duration.ZERO)
            .get(0);
    assertEquals("QUEUED", announced.state());
    assertNull(announced.targetId());

    assertTrue(deadline.sweep(past).requeued().isEmpty(), "a QUEUED lease is not queued again");

    try (FakeTargetRunner second = greetedRunner(OTHER_BEARER, 4)) {
      allocation.pass();
      await(
          () -> {
            Lease row = TargetRows.reload(id);
            return row.state == LeaseState.GRANTED && other.equals(row.targetId);
          },
          "the requeued lease was never granted on the connected target");
      second.await(
          Estate.class, e -> e.leases().stream().anyMatch(l -> l.leaseId().equals(id.toString())));
    }
  }

  @Test
  void onlyAGrantedLeaseWithoutAWorkKeyIsQueuedAgain() {
    // No runner has dialled for this target, and the clock is an hour past this process's start.
    UUID ci = UUID.randomUUID();
    TargetRows.lease(ci, SlotType.CI, "qits-ci", "run-1", LeaseState.GRANTED, target);
    UUID keyed = UUID.randomUUID();
    TargetRows.lease(keyed, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.GRANTED, target);
    workKey(keyed, "ws-17");
    List<UUID> left =
        List.of(
            TargetRows.lease(
                    UUID.randomUUID(), SlotType.CI, "qits-ci", "run-2", LeaseState.RUNNING, target)
                .id,
            TargetRows.lease(
                    UUID.randomUUID(), SlotType.CI, "qits-ci", "run-3", LeaseState.EXITED, target)
                .id,
            TargetRows.lease(
                    UUID.randomUUID(), SlotType.CI, "qits-ci", "run-4", LeaseState.FAILED, target)
                .id,
            TargetRows.lease(
                    UUID.randomUUID(), SlotType.CI, "qits-ci", "run-5", LeaseState.ENDING, target)
                .id);

    LeaseEndingDeadline.Swept swept = deadline.sweep(Instant.now().plus(Duration.ofHours(1)));
    assertEquals(List.of(ci), swept.requeued());
    assertEquals(LeaseState.QUEUED, TargetRows.reload(ci).state);

    Lease stays = TargetRows.reload(keyed);
    assertEquals(LeaseState.GRANTED, stays.state, "a work volume pins it to its target");
    assertEquals(target, stays.targetId);
    assertTrue(TargetRows.audit(keyed).isEmpty());
    for (UUID id : left) {
      Lease row = TargetRows.reload(id);
      assertEquals(target, row.targetId, row.state + " keeps its target");
      assertTrue(
          row.state != LeaseState.QUEUED && TargetRows.audit(id).isEmpty(),
          row.state + " is left alone");
    }
    assertEquals(LeaseState.RUNNING, TargetRows.reload(left.get(0)).state);
    assertEquals(LeaseState.EXITED, TargetRows.reload(left.get(1)).state);
    assertEquals(LeaseState.FAILED, TargetRows.reload(left.get(2)).state);
    assertEquals(LeaseState.ENDING, TargetRows.reload(left.get(3)).state);
  }

  @Test
  void aRunnerThatIgnoredItsNudgeIsQuarantinedBeforeTheEndAndGrantedNothingUntilACheckPasses()
      throws Exception {
    Instant since = TargetRows.T0;
    UUID ending = UUID.randomUUID();
    UUID granted = UUID.randomUUID();
    UUID keyed = UUID.randomUUID();
    try (FakeTargetRunner runner = greetedRunner(BEARER, 3)) {
      TargetRows.ending(ending, SlotType.CI, "qits-ci", "run-1", target, since);
      TargetRows.lease(granted, SlotType.CI, "qits-ci", "run-2", LeaseState.GRANTED, target);
      TargetRows.lease(keyed, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.GRANTED, target);
      workKey(keyed, "ws-17");
      UUID waiting = ciLease("run-3");
      allocation.pass();
      assertEquals(LeaseState.QUEUED, TargetRows.reload(waiting).state, "every slot is occupied");

      Instant due = since.plus(Duration.ofMinutes(10));
      assertEquals(List.of(ending), deadline.sweep(due).nudged());
      // The runner says nothing: no inventory, no report.
      LeaseEndingDeadline.Swept swept = deadline.sweep(due.plus(Duration.ofMinutes(2)));
      assertEquals(List.of(ending), swept.ended());
      assertEquals(List.of(target), swept.quarantined());
      assertEquals(List.of(granted), swept.requeued());

      String reason = LeaseEndingDeadline.ignoredItsEstate(ending);
      assertEquals(
          "its runner did not act on its estate: lease " + ending + " stayed ENDING past the deadline",
          reason);
      Target row = TargetRows.reloadTarget(target);
      assertTrue(row.quarantined());
      assertEquals(reason, row.quarantineReason);
      assertEquals(reason, runner.await(Quarantined.class, q -> reason.equals(q.reason())).reason());
      assertEquals(0, runner.await(Ack.class, a -> a.slots() == 0).slots());

      assertEquals(LeaseState.ENDED, TargetRows.reload(ending).state);
      Lease requeued = TargetRows.reload(granted);
      assertEquals(LeaseState.QUEUED, requeued.state);
      assertNull(requeued.targetId);
      TargetAudit audit = TargetRows.audit(granted).get(0);
      assertEquals("LEASE_REQUEUED", audit.operation);
      assertTrue(
          audit.detail.startsWith("target lab stopped acting on its estate before it started"),
          audit.detail);
      assertEquals(LeaseState.GRANTED, TargetRows.reload(keyed).state, "a work key keeps it there");

      // The ended lease's pass, and any after it, see the quarantine: nothing is granted on lab.
      allocation.pass();
      bus.await(TargetLeaseChanged.class, e -> e.leaseId().equals(ending.toString()), 1, SETTLE);
      assertEquals(LeaseState.QUEUED, TargetRows.reload(waiting).state);
      assertTrue(events(waiting, "GRANTED").isEmpty(), "never granted onto the silent runner");
      assertEquals(
          List.of(audit.detail),
          events(granted, "QUEUED").stream()
              .map(TargetLeaseChanged::detail)
              .filter(d -> d != null && d.startsWith("target lab"))
              .toList(),
          "the requeue is announced once");
      assertTrue(events(granted, "GRANTED").isEmpty(), "nor granted back to it");

      // A health check that passes is what reinstates it.
      String requestId =
          given()
              .header("Authorization", ContractBearers.bearer("an-operator", "qits:admin"))
              .post("/system/api/targets/" + target + "/healthcheck")
              .then()
              .statusCode(202)
              .extract()
              .path("requestId");
      runner.await(HealthCheck.class, c -> c.requestId().equals(requestId));
      runner.healthChecked(requestId, true);
      runner.await(Reinstated.class);
      await(
          () ->
              TargetRows.reload(waiting).state == LeaseState.GRANTED
                  && TargetRows.reload(granted).state == LeaseState.GRANTED,
          "the queued leases were never granted once the check passed");
      assertFalse(TargetRows.reloadTarget(target).quarantined());
    }
  }

  @Test
  void aLeaseEndedOnADisconnectedTargetQuarantinesNothing() {
    UUID free = UUID.randomUUID();
    TargetRows.target(free, "spare", 4, "target-runner-client-spare", false);
    UUID id = UUID.randomUUID();
    Instant since = TargetRows.T0;
    TargetRows.ending(id, SlotType.CI, "qits-ci", "run-1", free, since);
    // Nudged while it was connected; its runner has gone since.
    nudgedAt(id, since.plus(Duration.ofMinutes(10)));

    LeaseEndingDeadline.Swept swept = deadline.sweep(since.plus(Duration.ofMinutes(12)));
    assertEquals(List.of(id), swept.ended());
    assertTrue(swept.quarantined().isEmpty());
    Target row = TargetRows.reloadTarget(free);
    assertFalse(row.quarantined());
    assertNull(row.quarantineReason);
  }
}
