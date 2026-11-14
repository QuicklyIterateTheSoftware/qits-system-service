package eu.wohlben.qits.system.targets;

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
import eu.wohlben.qits.system.targets.control.TargetPresence;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ENDING deadline, on a fixed clock: {@link LeaseEndingDeadline#sweep} is handed the instant it
 * judges by, against the defaults (deadline PT10M, grace PT2M). A connected target's runner is
 * nudged with its estate once, then the lease is ENDED; a disconnected one's is ENDED at the
 * deadline. A target without a connection for the absent deadline (PT30M) has its RUNNING leases
 * FAILED and its ENDING ones ENDED (qits-2227), counted from its last close, or from this process's
 * start for a target it has not seen.
 */
@QuarkusTest
class LeaseEndingDeadlineTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final Instant SINCE = TargetRows.T0;

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  private static final Duration ABSENT = Duration.ofMinutes(30);

  @Inject LeaseEndingDeadline deadline;

  @Inject TargetPresence presence;

  @Inject RecordingEventBus bus;

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

  /** The instant {@code target} lost its connection, once the server has seen the close. */
  private Instant absentSince(UUID target) throws InterruptedException {
    long until = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    while (presence.absentSince(target).isEmpty()) {
      if (System.currentTimeMillis() > until) {
        throw new AssertionError("the server never saw the runner go");
      }
      Thread.sleep(25);
    }
    return presence.absentSince(target).orElseThrow();
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

  @Test
  void aTargetGonePastTheAbsentDeadlineHasItsRunningLeaseFailedAndItsEndingOneEnded()
      throws Exception {
    UUID running = UUID.randomUUID();
    UUID ending = UUID.randomUUID();
    UUID exited = UUID.randomUUID();
    Instant since;
    try (FakeTargetRunner runner = greetedRunner()) {
      TargetRows.lease(running, SlotType.CI, "qits-ci", "run-1", LeaseState.RUNNING, target);
      TargetRows.lease(exited, SlotType.CI, "qits-ci", "run-2", LeaseState.EXITED, target);
    }
    since = absentSince(target);
    // ENDING recently enough that its own deadline (PT10M) has not passed when the absent one does.
    TargetRows.ending(ending, SlotType.CI, "qits-ci", "run-3", target, since.plus(Duration.ofMinutes(25)));

    LeaseEndingDeadline.Swept early = deadline.sweep(since.plus(ABSENT).minusSeconds(1));
    assertTrue(early.abandoned().isEmpty(), "inside the absent deadline: nothing");
    assertTrue(early.ended().isEmpty());
    assertEquals(LeaseState.RUNNING, TargetRows.reload(running).state);
    assertTrue(TargetRows.audit(running).isEmpty());

    Instant due = since.plus(ABSENT);
    LeaseEndingDeadline.Swept swept = deadline.sweep(due);
    assertEquals(Set.of(running, ending), Set.copyOf(swept.abandoned()));
    assertTrue(swept.ended().isEmpty(), "settled by the absent deadline, not the ENDING one");
    String detail = "target lab offline since " + since.truncatedTo(ChronoUnit.SECONDS);
    assertEquals(detail, LeaseEndingDeadline.offlineSince("lab", since));

    Lease failed = TargetRows.reload(running);
    assertEquals(LeaseState.FAILED, failed.state);
    assertEquals(due.truncatedTo(ChronoUnit.MICROS), failed.endedAt);
    assertEquals(detail, failed.detail);
    List<TargetAudit> audit = TargetRows.audit(running);
    assertEquals(1, audit.size());
    assertEquals("LEASE_ABANDONED", audit.get(0).operation);
    assertEquals("qits-system", audit.get(0).actor);
    assertEquals("FAILED", audit.get(0).outcome);
    assertEquals(target, audit.get(0).targetId);
    assertEquals(detail, audit.get(0).detail);
    JsonNode args = new ObjectMapper().readTree(audit.get(0).args);
    assertEquals("RUNNING", args.path("from").asText(), audit.get(0).args);
    assertEquals("FAILED", args.path("to").asText(), audit.get(0).args);
    TargetLeaseChanged announced =
        bus.await(
                TargetLeaseChanged.class,
                e -> e.leaseId().equals(running.toString()) && "FAILED".equals(e.state()),
                1,
                Duration.ZERO)
            .get(0);
    assertEquals(detail, announced.detail());

    Lease ended = TargetRows.reload(ending);
    assertEquals(LeaseState.ENDED, ended.state);
    assertEquals(due.truncatedTo(ChronoUnit.MICROS), ended.endedAt);
    assertEquals(detail, ended.detail);
    assertEquals("ENDED", TargetRows.audit(ending).get(0).outcome);
    assertEquals("LEASE_ABANDONED", TargetRows.audit(ending).get(0).operation);

    assertEquals(LeaseState.EXITED, TargetRows.reload(exited).state, "nothing left to settle");
    assertTrue(TargetRows.audit(exited).isEmpty());
    assertTrue(deadline.sweep(due.plusSeconds(60)).abandoned().isEmpty(), "settled once");
  }

  @Test
  void aTargetThatReconnectsInsideTheAbsentDeadlineKeepsItsRunningLease() throws Exception {
    UUID running = UUID.randomUUID();
    try (FakeTargetRunner first = greetedRunner()) {
      TargetRows.lease(running, SlotType.CI, "qits-ci", "run-1", LeaseState.RUNNING, target);
    }
    Instant since = absentSince(target);
    assertTrue(deadline.sweep(since.plus(Duration.ofMinutes(29))).abandoned().isEmpty());

    try (FakeTargetRunner again = FakeTargetRunner.dial(base, BEARER)) {
      again.hello(4, "qits-system-runner-dot-claude-lab");
      again.await(Ack.class);
      assertFalse(presence.absentSince(target).isPresent(), "connected again");
      LeaseEndingDeadline.Swept swept = deadline.sweep(since.plus(ABSENT).plus(Duration.ofHours(1)));
      assertTrue(swept.abandoned().isEmpty(), "a target that came back is not absent");
      assertEquals(LeaseState.RUNNING, TargetRows.reload(running).state);
      assertTrue(TargetRows.audit(running).isEmpty());
    }
  }

  @Test
  void aTargetNotSeenSinceThisProcessStartedIsAbsentSinceTheStart() {
    // No runner has dialled for lab: after a restart there is no drop on record, and the absent
    // deadline runs from the process's start rather than never.
    UUID running = UUID.randomUUID();
    TargetRows.lease(running, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.RUNNING, target);
    Instant started = presence.absentSince(target).orElseThrow();
    assertEquals(started, presence.absentSince(UUID.randomUUID()).orElseThrow(), "one start for all");
    assertFalse(started.isAfter(Instant.now()));

    assertTrue(deadline.sweep(started.plus(ABSENT).minusSeconds(1)).abandoned().isEmpty());
    assertEquals(List.of(running), deadline.sweep(started.plus(ABSENT)).abandoned());
    Lease failed = TargetRows.reload(running);
    assertEquals(LeaseState.FAILED, failed.state);
    assertEquals(
        "target lab offline since " + started.truncatedTo(ChronoUnit.SECONDS), failed.detail);
  }
}
