package eu.wohlben.qits.system.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.system.targets.control.LeaseEndingDeadline;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ENDING deadline, on a fixed clock: {@link LeaseEndingDeadline#sweep} is handed the instant it
 * judges by, against the defaults (deadline PT10M, grace PT2M). A connected target's runner is
 * nudged with its estate once, then the lease is ENDED; a disconnected one's is ENDED at the
 * deadline.
 */
@QuarkusTest
class LeaseEndingDeadlineTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final Instant SINCE = TargetRows.T0;

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject LeaseEndingDeadline deadline;

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

  private FakeTargetRunner greetedRunner() throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    runner.await(Ack.class);
    runner.await(Quarantined.class);
    runner.await(Estate.class);
    HealthCheck check = runner.await(HealthCheck.class);
    runner.healthChecked(check.requestId(), true);
    runner.await(Reinstated.class);
    runner.await(Ack.class);
    return runner;
  }

  private static String sentence() {
    return "ENDING deadline passed: the runner on lab never reported the container gone (since "
        + SINCE
        + ")";
  }

  @Test
  void aConnectedTargetIsNudgedOnceThenTheLeaseIsEndedAfterTheGrace() throws Exception {
    UUID id = UUID.randomUUID();
    try (FakeTargetRunner runner = greetedRunner()) {
      TargetRows.ending(id, SlotType.CI, "qits-ci", "run-9", target, SINCE);

      LeaseEndingDeadline.Swept early = deadline.sweep(SINCE.plus(Duration.ofMinutes(9)));
      assertTrue(early.nudged().isEmpty() && early.ended().isEmpty(), "inside the deadline: nothing");
      assertNull(TargetRows.reload(id).endingNudgedAt);

      Instant due = SINCE.plus(Duration.ofMinutes(10));
      assertEquals(List.of(id), deadline.sweep(due).nudged());
      // The nudge is the estate that tells the runner, once more, to remove it.
      runner.await(
          Estate.class,
          e ->
              e.leases().stream()
                  .anyMatch(l -> l.leaseId().equals(id.toString()) && l.desired() == DesiredState.ENDED));
      Lease nudged = TargetRows.reload(id);
      assertEquals(LeaseState.ENDING, nudged.state);
      assertEquals(due, nudged.endingNudgedAt);

      LeaseEndingDeadline.Swept inGrace = deadline.sweep(due.plus(Duration.ofMinutes(1)));
      assertTrue(inGrace.nudged().isEmpty(), "a lease is nudged once");
      assertTrue(inGrace.ended().isEmpty(), "inside the grace: still ENDING");

      assertEquals(List.of(id), deadline.sweep(due.plus(Duration.ofMinutes(2))).ended());
      Lease ended = TargetRows.reload(id);
      assertEquals(LeaseState.ENDED, ended.state);
      assertEquals(sentence(), ended.detail);

      List<TargetAudit> audit = TargetRows.audit(id);
      assertEquals(1, audit.size());
      assertEquals("LEASE_ENDING_DEADLINE", audit.get(0).operation);
      assertEquals("qits-system", audit.get(0).actor);
      assertEquals("ENDED", audit.get(0).outcome);
      assertEquals(target, audit.get(0).targetId);
      assertEquals(sentence(), audit.get(0).detail);
    }
  }

  @Test
  void aDisconnectedTargetsLeaseIsEndedAtTheDeadlineWithoutANudge() {
    UUID id = UUID.randomUUID();
    TargetRows.ending(id, SlotType.WORKSPACE, "qits-workspaces", "17", target, SINCE);

    LeaseEndingDeadline.Swept swept = deadline.sweep(SINCE.plus(Duration.ofMinutes(10)));
    assertTrue(swept.nudged().isEmpty(), "nobody to nudge");
    assertEquals(List.of(id), swept.ended());
    Lease ended = TargetRows.reload(id);
    assertEquals(LeaseState.ENDED, ended.state);
    assertEquals(sentence(), ended.detail);
    assertEquals("LEASE_ENDING_DEADLINE", TargetRows.audit(id).get(0).operation);
  }

  @Test
  void aLeaseTheRunnerReportedGoneIsLeftAlone() {
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-1", LeaseState.ENDED, target);
    LeaseEndingDeadline.Swept swept = deadline.sweep(SINCE.plus(Duration.ofDays(1)));
    assertTrue(swept.ended().isEmpty());
    assertTrue(TargetRows.audit(id).isEmpty());
  }
}
