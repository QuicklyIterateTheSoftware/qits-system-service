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
}
