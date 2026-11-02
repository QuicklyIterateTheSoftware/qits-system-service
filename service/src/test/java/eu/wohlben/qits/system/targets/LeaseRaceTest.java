package eu.wohlben.qits.system.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two writers on one lease row at the same moment, many times over: the row is locked by whichever
 * reads it first, the other decides on what it wrote, and no transition is lost — the race the CI
 * log of qits-system-service-rr-59 showed as an {@code OptimisticLockException} on {@code update
 * lease}.
 */
@QuarkusTest
class LeaseRaceTest {

  private static final int ROUNDS = 25;

  @Inject Leases leases;

  @Inject TargetAllocation allocation;

  @Inject Targets targets;

  @Inject ContractBearers bearers;

  @TestHTTPResource("/")
  URI base;

  private UUID target;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, "target-runner-client-race", true);
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  /**
   * Both run at once, released by one barrier; both must complete.
   *
   * <p>Each racer gets a thread of its own. Not {@code CompletableFuture.runAsync}'s default
   * executor: that is the common ForkJoinPool, whose parallelism is {@code availableProcessors() -
   * 1} — ONE on the 2-CPU CI slot — so the second racer queued behind the first, which sat on the
   * two-party barrier until it timed out (qits-1526).
   */
  private static void race(Runnable a, Runnable b) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<Void> first = CompletableFuture.runAsync(() -> await(start, a), racers);
      CompletableFuture<Void> second = CompletableFuture.runAsync(() -> await(start, b), racers);
      CompletableFuture.allOf(first, second).get(20, TimeUnit.SECONDS);
    } finally {
      racers.shutdownNow();
    }
  }

  private static void await(CyclicBarrier start, Runnable work) {
    try {
      start.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    work.run();
  }

  @Test
  void anInventoryAndAnEndOnTheSameLeaseNeverLoseTheEnd() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      UUID id = UUID.randomUUID();
      TargetRows.lease(id, SlotType.CI, "qits-ci", "run-" + round, LeaseState.RUNNING, target);
      race(
          () -> leases.applyInventory(target, List.of(new Leases.Held(id, "EXITED", null, 0, null))),
          () -> leases.end(id, "qits-ci"));
      // End first: ENDING, and an EXITED report does not move an ENDING lease. The report first:
      // ending an EXITED lease makes it ENDED at once, since its slot is free already and ENDING
      // would occupy it again (qits-1813). EXITED would be a lost end.
      LeaseState after = TargetRows.reload(id).state;
      assertTrue(
          after == LeaseState.ENDING || after == LeaseState.ENDED, "round " + round + ": " + after);
    }
  }

  /**
   * qits-1813, as ws-proof showed it (total 9/8): an EXITED lease's slot is free at once and may be
   * granted to the next lease; its owner's end afterwards must not occupy that slot a second time.
   */
  @Test
  void endingAnExitedLeaseWhoseSlotWasGrantedAgainNeverCountsItTwice() throws Exception {
    bearers.grant("Bearer race", "target-runner-client-race", "qits:target-runner");
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, "Bearer race")) {
      runner.hello(2, "vol");
      HealthCheck check = runner.await(HealthCheck.class);
      runner.healthChecked(check.requestId(), true);
      runner.await(Reinstated.class);
      runner.await(Ack.class, a -> a.slots() == 2);
      UUID exits = UUID.randomUUID();
      UUID runs = UUID.randomUUID();
      UUID queued = UUID.randomUUID();
      TargetRows.lease(exits, SlotType.CI, "qits-ci", "run-1", LeaseState.RUNNING, target);
      TargetRows.lease(runs, SlotType.CI, "qits-ci", "run-2", LeaseState.RUNNING, target);
      TargetRows.lease(queued, SlotType.CI, "qits-ci", "run-3", LeaseState.QUEUED, null);
      leases.applyInventory(
          target,
          List.of(
              new Leases.Held(exits, "EXITED", "hash", 0, null),
              new Leases.Held(runs, "RUNNING", "hash", null, null)));
      assertEquals(LeaseState.EXITED, TargetRows.reload(exits).state);
      allocation.pass();
      assertEquals(LeaseState.GRANTED, TargetRows.reload(queued).state);
      assertEquals(target, TargetRows.reload(queued).targetId);
      leases.end(exits, "qits-ci");
      int used =
          targets.used().getOrDefault(target, Map.of()).values().stream()
              .mapToInt(Integer::intValue)
              .sum();
      assertEquals(2, used, "the ended EXITED lease occupies its slot again");
    }
  }

  @Test
  void aGrantAndAnEndOnTheSameQueuedLeaseNeverGrantAnEndedOne() throws Exception {
    bearers.grant("Bearer race", "target-runner-client-race", "qits:target-runner");
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, "Bearer race")) {
      runner.hello(64, "vol");
      HealthCheck check = runner.await(HealthCheck.class);
      runner.healthChecked(check.requestId(), true);
      runner.await(Reinstated.class);
      runner.await(Ack.class, a -> a.slots() == 64);
      for (int round = 0; round < ROUNDS; round++) {
        UUID id = UUID.randomUUID();
        TargetRows.lease(id, SlotType.CI, "qits-ci", "q-" + round, LeaseState.QUEUED, null);
        race(allocation::pass, () -> leases.end(id, "qits-ci"));
        Lease after = TargetRows.reload(id);
        // End first: ENDED, and the pass no longer sees it. The grant first: GRANTED, then the end
        // makes it ENDING. A GRANTED lease here would be an end the grant overwrote.
        if (after.state == LeaseState.ENDED) {
          assertEquals(null, after.targetId, "round " + round);
        } else {
          assertEquals(LeaseState.ENDING, after.state, "round " + round);
          assertEquals(target, after.targetId, "round " + round);
        }
      }
    }
  }
}
