package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.control.SlotAllocator.Decision;
import eu.wohlben.qits.system.targets.control.SlotAllocator.Request;
import eu.wohlben.qits.system.targets.control.SlotAllocator.TargetSlots;
import eu.wohlben.qits.system.targets.entity.SlotType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link SlotAllocator} as a table: the dossier's worked example first, then every listed case. */
class TargetAllocatorTest {

  private static final Instant T0 = Instant.parse("2026-10-23T08:00:00Z");

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  private static Map<SlotType, Integer> counts(int ci, int workspace, int desk) {
    Map<SlotType, Integer> counts = new EnumMap<>(SlotType.class);
    counts.put(SlotType.CI, ci);
    counts.put(SlotType.WORKSPACE, workspace);
    counts.put(SlotType.DESK, desk);
    return counts;
  }

  /** The worked example's target: T=6, reservations ci 2, workspace 2, desk 1, signed in. */
  private static TargetSlots worked(int ciRunning) {
    return new TargetSlots(A, "alpha", 6, counts(2, 2, 1), counts(ciRunning, 0, 0), true, false, true);
  }

  private static TargetSlots target(
      UUID id, String name, int total, Map<SlotType, Integer> used, boolean signedIn) {
    return new TargetSlots(id, name, total, counts(0, 0, 0), used, true, false, signedIn);
  }

  private static Request request(SlotType type, int priority, int secondsAfter) {
    return new Request(UUID.randomUUID(), type, priority, T0.plusSeconds(secondsAfter), null);
  }

  // --- the dossier's worked example ---------------------------------------------------------------

  @Test
  void twoCiRunningOneMoreFits() {
    // 2 + 1 + 2 + 1 = 6 <= 6
    assertTrue(SlotAllocator.fits(worked(2), SlotType.CI));
  }

  @Test
  void threeCiRunningOneMoreIsRefused() {
    // 3 + 1 + 2 + 1 = 7 > 6
    assertFalse(SlotAllocator.fits(worked(3), SlotType.CI));
  }

  @Test
  void ciTopsOutAtThree() {
    List<Request> queue = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      queue.add(request(SlotType.CI, 0, i));
    }
    List<Decision> decisions = SlotAllocator.allocate(List.of(worked(0)), queue);
    assertEquals(3, decisions.stream().filter(Decision::granted).count());
  }

  @Test
  void aWorkspaceLeaseAtThreeCiIsGranted() {
    // 3 + 1 + 0 + 1 = 5 <= 6
    assertTrue(SlotAllocator.fits(worked(3), SlotType.WORKSPACE));
    List<Decision> decisions =
        SlotAllocator.allocate(List.of(worked(3)), List.of(request(SlotType.WORKSPACE, 0, 0)));
    assertEquals(A, decisions.get(0).targetId());
  }

  @Test
  void theReservedSlotsStayFreeForTheirTypes() {
    // At 3 ci: 2 workspaces and 1 desk still fit, and then the target is full.
    TargetSlots slots = worked(3);
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(slots),
            List.of(
                request(SlotType.WORKSPACE, 0, 0),
                request(SlotType.WORKSPACE, 0, 1),
                request(SlotType.DESK, 0, 2),
                request(SlotType.DESK, 0, 3)));
    assertEquals(List.of(true, true, true, false), decisions.stream().map(Decision::granted).toList());
  }

  // --- Σ min > total ------------------------------------------------------------------------------

  @Test
  void reservationsAboveTheTotalAreIgnored() {
    // Reservations 2+2+1 = 5 against a reported total of 4: ci may take all four.
    TargetSlots shrunk =
        new TargetSlots(A, "alpha", 4, counts(2, 2, 1), counts(3, 0, 0), true, false, true);
    assertTrue(SlotAllocator.reservationsExceedTotal(4, shrunk.reservations()));
    assertTrue(SlotAllocator.fits(shrunk, SlotType.CI));
    TargetSlots full =
        new TargetSlots(A, "alpha", 4, counts(2, 2, 1), counts(4, 0, 0), true, false, true);
    assertFalse(SlotAllocator.fits(full, SlotType.CI));
  }

  @Test
  void aZeroTotalTakesNothing() {
    TargetSlots none = target(A, "alpha", 0, counts(0, 0, 0), true);
    assertFalse(SlotAllocator.fits(none, SlotType.CI));
  }

  // --- order and placement ------------------------------------------------------------------------

  @Test
  void theQueueIsPriorityThenAge() {
    TargetSlots one = target(A, "alpha", 1, counts(0, 0, 0), true);
    Request old = request(SlotType.CI, 0, 0);
    Request urgent = request(SlotType.CI, 5, 10);
    List<Decision> decisions = SlotAllocator.allocate(List.of(one), List.of(old, urgent));
    assertEquals(urgent.leaseId(), decisions.get(0).leaseId());
    assertTrue(decisions.get(0).granted());
    assertFalse(decisions.get(1).granted());
  }

  @Test
  void anUnpinnedLeaseGoesToTheMostFreeSlotsThenTheName() {
    TargetSlots busy = target(A, "alpha", 4, counts(3, 0, 0), true);
    TargetSlots idle = target(B, "beta", 4, counts(0, 0, 0), true);
    assertEquals(
        B,
        SlotAllocator.allocate(List.of(busy, idle), List.of(request(SlotType.CI, 0, 0)))
            .get(0)
            .targetId());
    TargetSlots alphaIdle = target(A, "alpha", 4, counts(0, 0, 0), true);
    assertEquals(
        A,
        SlotAllocator.allocate(List.of(idle, alphaIdle), List.of(request(SlotType.CI, 0, 0)))
            .get(0)
            .targetId());
  }

  @Test
  void grantsInOnePassCountAtOnce() {
    TargetSlots alpha = target(A, "alpha", 2, counts(0, 0, 0), true);
    TargetSlots beta = target(B, "beta", 1, counts(0, 0, 0), true);
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(alpha, beta),
            List.of(
                request(SlotType.CI, 0, 0),
                request(SlotType.CI, 0, 1),
                request(SlotType.CI, 0, 2),
                request(SlotType.CI, 0, 3)));
    assertEquals(3, decisions.stream().filter(Decision::granted).count());
    assertEquals(2, decisions.stream().filter(d -> A.equals(d.targetId())).count());
  }

  // --- pins -----------------------------------------------------------------------------------------

  @Test
  void aPinnedLeaseIsConsideredOnItsTargetOnly() {
    TargetSlots full = target(A, "alpha", 1, counts(1, 0, 0), true);
    TargetSlots idle = target(B, "beta", 4, counts(0, 0, 0), true);
    Request pinned = new Request(UUID.randomUUID(), SlotType.WORKSPACE, 0, T0, A);
    Decision decision = SlotAllocator.allocate(List.of(full, idle), List.of(pinned)).get(0);
    assertNull(decision.targetId());
    assertEquals("target alpha has no free WORKSPACE slot", decision.detail());
  }

  @Test
  void aPinnedLeaseIsGrantedOnItsTarget() {
    TargetSlots alpha = target(A, "alpha", 4, counts(0, 0, 0), true);
    TargetSlots beta = target(B, "beta", 8, counts(0, 0, 0), true);
    Request pinned = new Request(UUID.randomUUID(), SlotType.DESK, 0, T0, A);
    assertEquals(A, SlotAllocator.allocate(List.of(alpha, beta), List.of(pinned)).get(0).targetId());
  }

  @Test
  void aLeasePinnedToAGoneTargetWaits() {
    Request pinned = new Request(UUID.randomUUID(), SlotType.CI, 0, T0, A);
    Decision decision =
        SlotAllocator.allocate(List.of(target(B, "beta", 4, counts(0, 0, 0), true)), List.of(pinned))
            .get(0);
    assertFalse(decision.granted());
  }

  // --- the sign-in gate ---------------------------------------------------------------------------

  @Test
  void anAgentLeaseWaitsOnASignedOutTargetAndSaysWhy() {
    TargetSlots signedOut = target(A, "alpha", 4, counts(0, 0, 0), false);
    for (SlotType type : List.of(SlotType.WORKSPACE, SlotType.DESK)) {
      Decision decision =
          SlotAllocator.allocate(List.of(signedOut), List.of(request(type, 0, 0))).get(0);
      assertNull(decision.targetId());
      assertEquals(
          "target alpha is not signed in: qits targets sign-in --target alpha", decision.detail());
    }
  }

  @Test
  void ciIsNeverGated() {
    TargetSlots signedOut = target(A, "alpha", 4, counts(0, 0, 0), false);
    assertEquals(
        A,
        SlotAllocator.allocate(List.of(signedOut), List.of(request(SlotType.CI, 0, 0)))
            .get(0)
            .targetId());
  }

  @Test
  void anAgentLeasePrefersASignedInTarget() {
    TargetSlots signedOut = target(A, "alpha", 8, counts(0, 0, 0), false);
    TargetSlots signedIn = target(B, "beta", 2, counts(0, 0, 0), true);
    assertEquals(
        B,
        SlotAllocator.allocate(
                List.of(signedOut, signedIn), List.of(request(SlotType.WORKSPACE, 0, 0)))
            .get(0)
            .targetId());
  }

  @Test
  void aPinnedAgentLeaseOnASignedOutTargetSaysWhy() {
    TargetSlots signedOut = target(A, "alpha", 8, counts(0, 0, 0), false);
    Request pinned = new Request(UUID.randomUUID(), SlotType.DESK, 0, T0, A);
    assertEquals(
        SlotAllocator.notSignedIn("alpha"),
        SlotAllocator.allocate(List.of(signedOut), List.of(pinned)).get(0).detail());
  }

  // --- quarantine and disconnection ---------------------------------------------------------------

  @Test
  void aQuarantinedTargetGetsNoGrants() {
    TargetSlots quarantined =
        new TargetSlots(A, "alpha", 4, counts(0, 0, 0), counts(0, 0, 0), true, true, true);
    Decision decision =
        SlotAllocator.allocate(List.of(quarantined), List.of(request(SlotType.CI, 0, 0))).get(0);
    assertFalse(decision.granted());
    Request pinned = new Request(UUID.randomUUID(), SlotType.CI, 0, T0, A);
    assertEquals(
        "target alpha is quarantined",
        SlotAllocator.allocate(List.of(quarantined), List.of(pinned)).get(0).detail());
  }

  @Test
  void aDisconnectedTargetGetsNoGrants() {
    TargetSlots offline =
        new TargetSlots(A, "alpha", 4, counts(0, 0, 0), counts(0, 0, 0), false, false, true);
    assertFalse(
        SlotAllocator.allocate(List.of(offline), List.of(request(SlotType.CI, 0, 0)))
            .get(0)
            .granted());
    Request pinned = new Request(UUID.randomUUID(), SlotType.CI, 0, T0, A);
    assertEquals(
        "target alpha is not connected",
        SlotAllocator.allocate(List.of(offline), List.of(pinned)).get(0).detail());
  }

  @Test
  void aBlockedLeaseDoesNotHoldBackTheOnesBehindIt() {
    TargetSlots signedOut = target(A, "alpha", 4, counts(0, 0, 0), false);
    Request workspace = request(SlotType.WORKSPACE, 9, 0);
    Request ci = request(SlotType.CI, 0, 1);
    List<Decision> decisions = SlotAllocator.allocate(List.of(signedOut), List.of(workspace, ci));
    assertFalse(decisions.get(0).granted());
    assertTrue(decisions.get(1).granted());
  }
}
