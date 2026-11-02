package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.control.Leases.Held;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link Leases#transition}: a runner's report against one lease's row, qits-1693 included. */
class LeasesTransitionTest {

  private static final Instant T0 = Instant.parse("2026-10-27T19:55:49Z");

  private static Lease lease(LeaseState state, String detail) {
    Lease lease = new Lease();
    lease.id = UUID.randomUUID();
    lease.state = state;
    lease.detail = detail;
    return lease;
  }

  private static Held starting(UUID leaseId, String detail) {
    return new Held(leaseId, "STARTING", null, null, detail);
  }

  @Test
  void grantedStartingWithADetailMovesAndKeepsTheState() {
    Lease lease = lease(LeaseState.GRANTED, null);
    boolean moved =
        Leases.transition(
            lease,
            starting(lease.id, "pulling registry.x/qits/workspace:2026.1027.144757 since "
                + "2026-10-27T19:55:49Z"),
            T0);
    assertTrue(moved);
    assertEquals(LeaseState.GRANTED, lease.state);
    assertEquals(
        "pulling registry.x/qits/workspace:2026.1027.144757 since 2026-10-27T19:55:49Z",
        lease.detail);
  }

  @Test
  void grantedStartingWithTheSameDetailAgainDoesNotMove() {
    String detail = "pulling registry.x/qits/workspace:2026.1027.144757 since 2026-10-27T19:55:49Z";
    Lease lease = lease(LeaseState.GRANTED, detail);
    boolean moved = Leases.transition(lease, starting(lease.id, detail), T0);
    assertFalse(moved);
    assertEquals(LeaseState.GRANTED, lease.state);
    assertEquals(detail, lease.detail);
  }

  @Test
  void runningStartingDoesNotMoveOrTouchDetail() {
    Lease lease = lease(LeaseState.RUNNING, null);
    boolean moved = Leases.transition(lease, starting(lease.id, "pulling again"), T0);
    assertFalse(moved);
    assertEquals(LeaseState.RUNNING, lease.state);
    assertNull(lease.detail);
  }

  @Test
  void grantedStartingThenRunningClearsTheDetail() {
    Lease lease = lease(LeaseState.GRANTED, null);
    assertTrue(Leases.transition(lease, starting(lease.id, "pulling the image"), T0));
    assertEquals("pulling the image", lease.detail);

    boolean moved =
        Leases.transition(lease, new Held(lease.id, "RUNNING", null, null, null), T0);
    assertTrue(moved);
    assertEquals(LeaseState.RUNNING, lease.state);
    assertNull(lease.detail);
  }

  @Test
  void grantedStartingWithABlankDetailIsTreatedAsNull() {
    Lease lease = lease(LeaseState.GRANTED, null);
    boolean moved = Leases.transition(lease, starting(lease.id, "   "), T0);
    assertFalse(moved);
    assertNull(lease.detail);
  }

  @Test
  void grantedStartingWithNoDetailClearsAPreviousOne() {
    Lease lease = lease(LeaseState.GRANTED, "pulling the image");
    boolean moved = Leases.transition(lease, starting(lease.id, null), T0);
    assertTrue(moved);
    assertEquals(LeaseState.GRANTED, lease.state);
    assertNull(lease.detail);
  }

  // --- qits-1818: the memory facts ---------------------------------------------------------------

  private static Held memory(
      UUID leaseId, String state, Long oomKills, Long oomEvents, Long peak, Boolean oomKilled) {
    return new Held(leaseId, state, null, null, null, oomKills, oomEvents, peak, oomKilled);
  }

  @Test
  void theMemoryFactsAreStoredAndOnlyAKillCounts() {
    Lease lease = lease(LeaseState.RUNNING, null);
    assertFalse(Leases.recordMemory(lease, memory(lease.id, "RUNNING", 0L, 0L, 512L, null)));
    assertEquals(Long.valueOf(0), lease.oomKills);
    assertEquals(Long.valueOf(0), lease.oomEvents);
    assertEquals(Long.valueOf(512), lease.memoryPeakBytes);
    assertNull(lease.oomKilled);
    assertFalse(Leases.transition(lease, memory(lease.id, "RUNNING", 0L, 0L, 512L, null), T0));
  }

  @Test
  void aPeakThatGrewIsStoredButIsNoChange() {
    Lease lease = lease(LeaseState.RUNNING, null);
    lease.memoryPeakBytes = 512L;
    assertFalse(Leases.recordMemory(lease, memory(lease.id, "RUNNING", null, null, 4096L, null)));
    assertEquals(Long.valueOf(4096), lease.memoryPeakBytes);
  }

  @Test
  void thePeakAndTheCountersNeverMoveDown() {
    Lease lease = lease(LeaseState.RUNNING, null);
    lease.oomKills = 3L;
    lease.oomEvents = 2L;
    lease.memoryPeakBytes = 4096L;
    assertFalse(Leases.recordMemory(lease, memory(lease.id, "RUNNING", 1L, 0L, 1024L, null)));
    assertEquals(Long.valueOf(3), lease.oomKills);
    assertEquals(Long.valueOf(2), lease.oomEvents);
    assertEquals(Long.valueOf(4096), lease.memoryPeakBytes);
  }

  @Test
  void aReportWithoutTheFactsLeavesThemAsTheyWere() {
    Lease lease = lease(LeaseState.RUNNING, null);
    lease.oomKills = 3L;
    lease.oomEvents = 1L;
    lease.memoryPeakBytes = 4096L;
    lease.oomKilled = Boolean.TRUE;
    assertFalse(Leases.recordMemory(lease, new Held(lease.id, "RUNNING", null, null, null)));
    assertFalse(Leases.recordMemory(lease, null));
    assertEquals(Long.valueOf(3), lease.oomKills);
    assertEquals(Long.valueOf(1), lease.oomEvents);
    assertEquals(Long.valueOf(4096), lease.memoryPeakBytes);
    assertEquals(Boolean.TRUE, lease.oomKilled);
  }

  @Test
  void aRiseOfTheKillsCountsAFirstOneAboveZeroIncluded() {
    Lease lease = lease(LeaseState.RUNNING, null);
    assertTrue(Leases.recordMemory(lease, memory(lease.id, "RUNNING", 1L, 0L, null, null)));
    assertTrue(Leases.recordMemory(lease, memory(lease.id, "RUNNING", 2L, 1L, null, null)));
    assertFalse(Leases.recordMemory(lease, memory(lease.id, "RUNNING", 2L, 1L, null, null)));
    assertEquals(Long.valueOf(2), lease.oomKills);
  }

  @Test
  void anExitCarriesItsLastSampleAndOomKilled() {
    Lease lease = lease(LeaseState.RUNNING, null);
    Held exited =
        new Held(lease.id, "EXITED", null, 137, null, 1L, 1L, 2048L, Boolean.TRUE);
    assertTrue(Leases.recordMemory(lease, exited));
    assertTrue(Leases.transition(lease, exited, T0));
    assertEquals(LeaseState.EXITED, lease.state);
    assertEquals(Long.valueOf(1), lease.oomKills);
    assertEquals(Boolean.TRUE, lease.oomKilled);
    assertEquals("LEASE", lease.oomScope());
  }

  @Test
  void theOomScopeIsLeaseOnItsOwnLimitAndNodeOtherwise() {
    assertNull(Lease.oomScope(null, null));
    assertNull(Lease.oomScope(0L, 3L));
    assertEquals("LEASE", Lease.oomScope(1L, 1L));
    assertEquals("NODE", Lease.oomScope(1L, 0L));
    assertEquals("NODE", Lease.oomScope(2L, null));
  }
}
