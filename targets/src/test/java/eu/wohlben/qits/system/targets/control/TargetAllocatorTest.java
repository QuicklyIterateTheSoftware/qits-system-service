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
    return counts(ci, workspace, desk, 0);
  }

  private static Map<SlotType, Integer> counts(int ci, int workspace, int desk, int agent) {
    Map<SlotType, Integer> counts = new EnumMap<>(SlotType.class);
    counts.put(SlotType.CI, ci);
    counts.put(SlotType.WORKSPACE, workspace);
    counts.put(SlotType.DESK, desk);
    counts.put(SlotType.AGENT, agent);
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

  @Test
  void anAgentReservationStaysFreeForAgentLeasesAsADesksDoes() {
    // T=6, reservations ci 2, workspace 1, desk 1, agent 1, 2 ci running: one more ci fits (2 + 1 +
    // 1 + 1 + 1 = 6), a third does not, and the agent's reserved slot is still an agent's to take.
    TargetSlots slots =
        new TargetSlots(A, "alpha", 6, counts(2, 1, 1, 1), counts(2, 0, 0), true, false, true);
    assertTrue(SlotAllocator.fits(slots, SlotType.CI));
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(slots),
            List.of(
                request(SlotType.CI, 0, 0),
                request(SlotType.CI, 0, 1),
                request(SlotType.AGENT, 0, 2),
                request(SlotType.AGENT, 0, 3)));
    assertEquals(List.of(true, false, true, false), decisions.stream().map(Decision::granted).toList());
    assertEquals("no target has a free AGENT slot", decisions.get(3).detail());
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

  // --- avoidance (qits-2382) ------------------------------------------------------------------------

  private static Request avoiding(SlotType type, UUID pinnedTo, UUID avoided) {
    return new Request(UUID.randomUUID(), type, 0, T0, pinnedTo, 0, avoided);
  }

  @Test
  void anAvoidedTargetWithMoreFreeSlotsIsPassedOverForAnotherThatCanTakeTheLease() {
    TargetSlots roomy = target(A, "alpha", 8, counts(0, 0, 0), true);
    TargetSlots tight = target(B, "beta", 2, counts(1, 0, 0), true);
    assertEquals(
        A, SlotAllocator.allocate(List.of(roomy, tight), List.of(request(SlotType.CI, 0, 0))).get(0).targetId(),
        "without avoidance the most free slots win");
    Decision decision =
        SlotAllocator.allocate(List.of(roomy, tight), List.of(avoiding(SlotType.CI, null, A))).get(0);
    assertEquals(B, decision.targetId());
  }

  @Test
  void anAvoidedTargetTakesTheLeaseWhenNoOtherCan() {
    TargetSlots avoided = target(A, "alpha", 4, counts(0, 0, 0), true);
    TargetSlots full = target(B, "beta", 1, counts(1, 0, 0), true);
    TargetSlots quarantined =
        new TargetSlots(
            UUID.randomUUID(), "gamma", 4, counts(0, 0, 0), counts(0, 0, 0), true, true, true);
    Decision decision =
        SlotAllocator.allocate(
                List.of(avoided, full, quarantined), List.of(avoiding(SlotType.CI, null, A)))
            .get(0);
    assertEquals(A, decision.targetId());
  }

  @Test
  void aPinnedLeaseLandsOnItsPinEvenWhenItAvoidsIt() {
    TargetSlots alpha = target(A, "alpha", 4, counts(0, 0, 0), true);
    TargetSlots beta = target(B, "beta", 8, counts(0, 0, 0), true);
    Decision decision =
        SlotAllocator.allocate(List.of(alpha, beta), List.of(avoiding(SlotType.CI, A, A))).get(0);
    assertEquals(A, decision.targetId());
  }

  @Test
  void anAvoidingLeaseThatNoTargetCanTakeWaitsAsAnyOther() {
    TargetSlots full = target(A, "alpha", 1, counts(1, 0, 0), true);
    Decision decision =
        SlotAllocator.allocate(List.of(full), List.of(avoiding(SlotType.CI, null, A))).get(0);
    assertFalse(decision.granted());
    assertEquals("no target has a free CI slot", decision.detail());
  }

  // --- the sign-in gate ---------------------------------------------------------------------------

  @Test
  void anAgentLeaseWaitsOnASignedOutTargetAndSaysWhy() {
    TargetSlots signedOut = target(A, "alpha", 4, counts(0, 0, 0), false);
    for (SlotType type : List.of(SlotType.WORKSPACE, SlotType.DESK, SlotType.AGENT)) {
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

  // --- maximums (qits-1813): the ceiling a reservation is not ----------------------------------

  /** A map with a null (no ceiling) where a type is unset. */
  private static Map<SlotType, Integer> maximums(Integer ci, Integer workspace, Integer desk) {
    return maximums(ci, workspace, desk, null);
  }

  private static Map<SlotType, Integer> maximums(
      Integer ci, Integer workspace, Integer desk, Integer agent) {
    Map<SlotType, Integer> maximums = new EnumMap<>(SlotType.class);
    maximums.put(SlotType.CI, ci);
    maximums.put(SlotType.WORKSPACE, workspace);
    maximums.put(SlotType.DESK, desk);
    maximums.put(SlotType.AGENT, agent);
    return maximums;
  }

  /** ws-proof: T=8, reservations ci 2, workspace 1, workspace 4 used, ci at most {@code ciMax}. */
  private static TargetSlots wsProof(Integer ciMax, int ciUsed) {
    return new TargetSlots(
        A,
        "ws-proof",
        8,
        counts(2, 1, 0),
        maximums(ciMax, null, null),
        counts(ciUsed, 4, 0),
        true,
        false,
        true);
  }

  private static List<Request> ciQueue(int n) {
    List<Request> queue = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      queue.add(request(SlotType.CI, 0, i));
    }
    return queue;
  }

  @Test
  void aCiMaximumOfTwoGrantsExactlyTwoOfSixQueued() {
    List<Decision> decisions = SlotAllocator.allocate(List.of(wsProof(2, 0)), ciQueue(6));
    assertEquals(2, decisions.stream().filter(Decision::granted).count());
    assertEquals(
        List.of(true, true, false, false, false, false),
        decisions.stream().map(Decision::granted).toList());
    assertEquals("no target has a free CI slot", decisions.get(2).detail());
  }

  @Test
  void withoutAMaximumCiBorrowsEveryFreeSlot() {
    // Today's rule, unchanged: 8 − 4 workspace = 4 free, no reservation left unused but ci's own.
    List<Decision> decisions = SlotAllocator.allocate(List.of(wsProof(null, 0)), ciQueue(6));
    assertEquals(4, decisions.stream().filter(Decision::granted).count());
  }

  @Test
  void theMaximumCountsGrantsWithinTheSamePass() {
    // One CI already runs: the first grant of this pass reaches the maximum of 2.
    assertTrue(SlotAllocator.fits(wsProof(2, 1), SlotType.CI));
    List<Decision> decisions = SlotAllocator.allocate(List.of(wsProof(2, 1)), ciQueue(3));
    assertEquals(List.of(true, false, false), decisions.stream().map(Decision::granted).toList());
    assertFalse(SlotAllocator.fits(wsProof(2, 2), SlotType.CI));
  }

  @Test
  void aTypeAtItsMaximumLeavesTheSharedSlotsToTheOthers() {
    TargetSlots slots = wsProof(2, 2);
    assertTrue(SlotAllocator.fits(slots, SlotType.WORKSPACE));
    assertTrue(SlotAllocator.fits(slots, SlotType.DESK));
    assertTrue(SlotAllocator.fits(slots, SlotType.AGENT));
    // A maximum on one type holds back no other: the workspace ceiling is unset.
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(slots),
            List.of(
                request(SlotType.WORKSPACE, 0, 0),
                request(SlotType.WORKSPACE, 0, 1),
                request(SlotType.WORKSPACE, 0, 2)));
    assertEquals(List.of(true, true, false), decisions.stream().map(Decision::granted).toList());
  }

  @Test
  void aPinnedLeaseAtTheMaximumSaysSo() {
    Request pinned = new Request(UUID.randomUUID(), SlotType.CI, 0, T0, A);
    assertEquals(
        "target ws-proof runs its maximum of 2 CI lease(s)",
        SlotAllocator.allocate(List.of(wsProof(2, 2)), List.of(pinned)).get(0).detail());
  }

  @Test
  void anAgentMaximumIsItsCeilingAsADesksIs() {
    // T=8, nothing reserved, agent at most 1: one AGENT lease is granted, the second waits while
    // every other slot is free — and a DESK lease beside them is not held back by it.
    TargetSlots slots =
        new TargetSlots(
            A,
            "alpha",
            8,
            counts(0, 0, 0),
            maximums(null, null, null, 1),
            counts(0, 0, 0),
            true,
            false,
            true);
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(slots),
            List.of(
                request(SlotType.AGENT, 0, 0),
                request(SlotType.AGENT, 0, 1),
                request(SlotType.DESK, 0, 2)));
    assertEquals(List.of(true, false, true), decisions.stream().map(Decision::granted).toList());
    Request pinned = new Request(UUID.randomUUID(), SlotType.AGENT, 0, T0, A);
    TargetSlots atMaximum =
        new TargetSlots(
            A,
            "alpha",
            8,
            counts(0, 0, 0),
            maximums(null, null, null, 1),
            counts(0, 0, 0, 1),
            true,
            false,
            true);
    assertEquals(
        "target alpha runs its maximum of 1 AGENT lease(s)",
        SlotAllocator.allocate(List.of(atMaximum), List.of(pinned)).get(0).detail());
  }

  @Test
  void theMaximumHoldsWhenTheReservationsAreIgnored() {
    // Reservations 5 against a total of 4 are ignored; a ci maximum of 1 still holds.
    TargetSlots shrunk =
        new TargetSlots(
            A, "alpha", 4, counts(2, 2, 1), maximums(2, null, null), counts(2, 0, 0), true, false, true);
    assertFalse(SlotAllocator.fits(shrunk, SlotType.CI));
    assertTrue(SlotAllocator.fits(shrunk, SlotType.WORKSPACE));
  }

  @Test
  void aMaximumAboveTheTotalIsNoCeilingAtAll() {
    TargetSlots generous =
        new TargetSlots(
            A, "alpha", 2, counts(0, 0, 0), maximums(9, null, null), counts(0, 0, 0), true, false, true);
    assertEquals(
        2,
        SlotAllocator.allocate(List.of(generous), ciQueue(4)).stream()
            .filter(Decision::granted)
            .count());
  }

  // --- memory (qits-2362): one more conjunct ----------------------------------------------------

  private static final long GIB = 1024L * 1024 * 1024;

  private static final long SIX_G = 6 * GIB;

  /** Every type last granted at 6g. */
  private static Map<SlotType, Long> lastAtSixG() {
    Map<SlotType, Long> last = new EnumMap<>(SlotType.class);
    for (SlotType type : SlotType.values()) {
      last.put(type, SIX_G);
    }
    return last;
  }

  /** ws-proof's slots (T=8, ci 2/ws 1) with {@code used} and a memory picture. */
  private static TargetSlots wsProofMemory(
      Map<SlotType, Integer> used, Long capacity, long committed, Map<SlotType, Long> last) {
    return new TargetSlots(
        A,
        "ws-proof",
        8,
        counts(2, 1, 0),
        null,
        used,
        true,
        false,
        true,
        new SlotAllocator.Memory(capacity, committed, last));
  }

  private static Request charged(SlotType type, int secondsAfter, long charge) {
    return new Request(UUID.randomUUID(), type, 0, T0.plusSeconds(secondsAfter), null, charge);
  }

  @Test
  void theReproductionAFifthSixGWorkspaceIsRefused() {
    // ws-proof as it OOMed: ci 2 and ws 4 running at 6g each (36 GiB) on 16 GiB − 2 GiB. The slots
    // alone would take a fifth workspace (6 + 1 + 0 ≤ 8); its memory would not.
    long capacity = SlotAllocator.Memory.capacity(2 * GIB, 16 * GIB);
    assertEquals(14 * GIB, capacity);
    TargetSlots target = wsProofMemory(counts(2, 4, 0), capacity, 6 * SIX_G, lastAtSixG());
    assertTrue(SlotAllocator.slotsFit(target, SlotType.WORKSPACE));
    assertFalse(SlotAllocator.fits(target, SlotType.WORKSPACE, SIX_G));
    Decision decision =
        SlotAllocator.allocate(List.of(target), List.of(charged(SlotType.WORKSPACE, 0, SIX_G)))
            .get(0);
    assertFalse(decision.granted());
    assertEquals("no target has memory for a 6g WORKSPACE lease", decision.detail());
    Request pinned = new Request(UUID.randomUUID(), SlotType.WORKSPACE, 0, T0, A, SIX_G);
    assertEquals(
        "target ws-proof has no memory for a 6g WORKSPACE lease",
        SlotAllocator.allocate(List.of(target), List.of(pinned)).get(0).detail());
  }

  @Test
  void aLeaseFitsWhenTheMemoryHasRoom() {
    // ci 2 running (12 GiB), 32 GiB capacity, the workspace reservation priced at 6g: 12 + 6 + 6.
    TargetSlots target = wsProofMemory(counts(2, 0, 0), 32 * GIB, 2 * SIX_G, lastAtSixG());
    assertTrue(SlotAllocator.fits(target, SlotType.WORKSPACE, SIX_G));
    assertTrue(SlotAllocator.fits(target, SlotType.CI, SIX_G));
    assertEquals(
        A,
        SlotAllocator.allocate(List.of(target), List.of(charged(SlotType.WORKSPACE, 0, SIX_G)))
            .get(0)
            .targetId());
  }

  @Test
  void theOtherTypesUnusedReservationsHoldTheirMemory() {
    // 20 GiB, nothing running: a workspace must leave 2 × 6g for ci's reservation (6 + 12 = 18).
    TargetSlots empty = wsProofMemory(counts(0, 0, 0), 20 * GIB, 0, lastAtSixG());
    assertTrue(SlotAllocator.fits(empty, SlotType.WORKSPACE, SIX_G));
    // One workspace runs: a second needs 6 + 12 + 6 = 24 > 20, though its slots are free.
    TargetSlots oneWs = wsProofMemory(counts(0, 1, 0), 20 * GIB, SIX_G, lastAtSixG());
    assertTrue(SlotAllocator.slotsFit(oneWs, SlotType.WORKSPACE));
    assertFalse(SlotAllocator.fits(oneWs, SlotType.WORKSPACE, SIX_G));
    // ... while ci, whose own reservation is not held against it, still gets 6 + 0 + 6 = 12.
    assertTrue(SlotAllocator.fits(oneWs, SlotType.CI, SIX_G));
    // A type never granted here is priced at 0: no reservation is held in memory for it.
    TargetSlots neverCi =
        wsProofMemory(counts(0, 1, 0), 20 * GIB, SIX_G, Map.of(SlotType.WORKSPACE, SIX_G));
    assertTrue(SlotAllocator.fits(neverCi, SlotType.WORKSPACE, SIX_G));
  }

  @Test
  void theReservedMemoryIsIgnoredWithTheReservations() {
    // Reservations 2+2+1 = 5 against a total of 4 are ignored, and so is their memory.
    TargetSlots shrunk =
        new TargetSlots(
            A,
            "alpha",
            4,
            counts(2, 2, 1),
            null,
            counts(0, 0, 0),
            true,
            false,
            true,
            new SlotAllocator.Memory(7 * GIB, 0, lastAtSixG()));
    assertTrue(SlotAllocator.fits(shrunk, SlotType.CI, SIX_G));
    assertFalse(SlotAllocator.fits(shrunk, SlotType.CI, 8 * GIB));
  }

  @Test
  void noCapacityReportedIsTheSlotRuleAlone() {
    TargetSlots unknown = wsProofMemory(counts(2, 4, 0), null, 6 * SIX_G, lastAtSixG());
    assertTrue(SlotAllocator.fits(unknown, SlotType.WORKSPACE, SIX_G));
    assertNull(SlotAllocator.Memory.capacity(2 * GIB, (Long) null));
    // The default memory is UNKNOWN too: every slot-only constructor keeps today's rule.
    assertEquals(SlotAllocator.Memory.UNKNOWN, worked(0).memory());
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(unknown),
            List.of(charged(SlotType.WORKSPACE, 0, SIX_G), charged(SlotType.WORKSPACE, 1, SIX_G)));
    assertEquals(List.of(true, true), decisions.stream().map(Decision::granted).toList());
  }

  @Test
  void aLeaseWithoutALimitIsChargedNothing() {
    // Full to the byte: a 6g lease does not fit, one charged 0 does.
    TargetSlots full = wsProofMemory(counts(2, 0, 0), 2 * SIX_G, 2 * SIX_G, lastAtSixG());
    assertTrue(SlotAllocator.fits(full, SlotType.WORKSPACE, 0));
    assertFalse(SlotAllocator.fits(full, SlotType.WORKSPACE, 1));
    assertEquals(0, DockerSizes.charge("{\"image\":\"x\"}"));
    assertEquals(0, DockerSizes.charge("{\"image\":\"x\",\"limits\":{\"memorySwap\":\"8g\"}}"));
    // A lease granted with no limit leaves its type's last charge at 0 for what follows.
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(wsProofMemory(counts(0, 0, 0), 6 * GIB, 0, Map.of())),
            List.of(charged(SlotType.CI, 0, 0), charged(SlotType.WORKSPACE, 1, SIX_G)));
    assertEquals(List.of(true, true), decisions.stream().map(Decision::granted).toList());
  }

  @Test
  void memoryGrantsInOnePassCountAtOnce() {
    // 14 GiB, nothing ever granted (every reservation priced at 0): two 6g CI fit, and the third
    // finds the first two's 12 GiB committed, 12 + 6 > 14, although six slots are free.
    TargetSlots target = wsProofMemory(counts(0, 0, 0), 14 * GIB, 0, Map.of());
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(target),
            List.of(
                charged(SlotType.CI, 0, SIX_G),
                charged(SlotType.CI, 1, SIX_G),
                charged(SlotType.CI, 2, SIX_G)));
    assertEquals(List.of(true, true, false), decisions.stream().map(Decision::granted).toList());
    assertEquals("no target has memory for a 6g CI lease", decisions.get(2).detail());
    // A grant's charge also prices its type's reservation for the rest of the pass: with workspace
    // reserved 2, the first workspace granted prices the other one at 6g, so ci then finds
    // 6 + 1 × 6 + 6 = 18 > 14 where, priced at the 0 it began the pass with, 12 would have fit.
    TargetSlots wsTwo =
        new TargetSlots(
            A, "ws-proof", 8, counts(0, 2, 0), null, counts(0, 0, 0), true, false, true,
            new SlotAllocator.Memory(14 * GIB, 0, Map.of()));
    List<Decision> mixed =
        SlotAllocator.allocate(
            List.of(wsTwo),
            List.of(
                charged(SlotType.WORKSPACE, 0, SIX_G),
                charged(SlotType.CI, 1, SIX_G)));
    assertEquals(List.of(true, false), mixed.stream().map(Decision::granted).toList());
  }

  // --- reservations the memory cannot meet are honoured in priority order (qits-2515) ----------

  /** ws-proof as it reports: 16 GiB − 2 GiB, every type last granted at 6g, no builder cap. */
  private static TargetSlots wsProofAt14(Map<SlotType, Integer> used, long committed) {
    long capacity = SlotAllocator.Memory.capacity(2 * GIB, 16 * GIB);
    return wsProofMemory(used, capacity, committed, lastAtSixG());
  }

  /** ws-proof's slots (T=8, ci 2/ws 1) at {@code capacity} with an 8 GiB builder, every type at 6g. */
  private static TargetSlots wsProofWithBuilder(
      Map<SlotType, Integer> used, long capacity, long committed) {
    return withBuilder(counts(2, 1, 0), used, capacity, committed, 8 * GIB);
  }

  @Test
  void anEmptyWsProofKeepsItsCiLeaseAndTakesNoWorkspace() {
    // ws-proof as it runs: 14 GiB, an 8 GiB builder (r[CI] = 2 > 0), ci 2 / ws 1 at 6g (18 GiB).
    // A = 14 − 8 = 6: h[CI] = min(2, ⌊6/6⌋) = 1, A = 0; h[WS] = min(1, 0) = 0.
    TargetSlots empty = wsProofWithBuilder(counts(0, 0, 0), 14 * GIB, 0);
    assertEquals(counts(1, 0, 0), SlotAllocator.honouredReservations(empty, SlotType.CI));
    assertEquals(counts(1, 0, 0), SlotAllocator.honouredReservations(empty, SlotType.WORKSPACE));
    // CI: 0 + 0 (h[WS] = 0) + 8 + 6 = 14 ≤ 14.
    assertTrue(SlotAllocator.fits(empty, SlotType.CI, SIX_G));
    assertEquals(
        A,
        SlotAllocator.allocate(List.of(empty), List.of(charged(SlotType.CI, 0, SIX_G)))
            .get(0)
            .targetId());
    // WORKSPACE: 0 + 1 × 6 (h[CI]) + 8 + 6 = 20 > 14, though its slots are free.
    assertTrue(SlotAllocator.slotsFit(empty, SlotType.WORKSPACE));
    Decision workspace =
        SlotAllocator.allocate(List.of(empty), List.of(charged(SlotType.WORKSPACE, 0, SIX_G)))
            .get(0);
    assertFalse(workspace.granted());
    assertEquals("no target has memory for a 6g WORKSPACE lease", workspace.detail());
    // Without a builder cap (an older runner) A = 14: h[CI] = 2, A = 2, h[WS] = 0, and a workspace
    // finds 0 + 2 × 6 + 6 = 18 > 14 while CI finds 0 + 0 + 6 = 6.
    TargetSlots noBuilder = wsProofAt14(counts(0, 0, 0), 0);
    assertEquals(counts(2, 0, 0), SlotAllocator.honouredReservations(noBuilder, SlotType.WORKSPACE));
    assertFalse(SlotAllocator.fits(noBuilder, SlotType.WORKSPACE, SIX_G));
    assertTrue(SlotAllocator.fits(noBuilder, SlotType.CI, SIX_G));
  }

  @Test
  void anOlderWorkspaceInTheQueueDoesNotTakeWsProofsCiMemory() {
    // One pass, the workspace queued first: it finds 0 + 6 + 8 + 6 = 20 > 14 and waits; the CI
    // lease behind it finds 0 + 0 + 8 + 6 = 14 and is granted — the starvation qits-2515 ends.
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(wsProofWithBuilder(counts(0, 0, 0), 14 * GIB, 0)),
            List.of(charged(SlotType.WORKSPACE, 0, SIX_G), charged(SlotType.CI, 1, SIX_G)));
    assertEquals(List.of(false, true), decisions.stream().map(Decision::granted).toList());
    assertEquals("no target has memory for a 6g WORKSPACE lease", decisions.get(0).detail());
  }

  @Test
  void wsProofAt32GiBHonoursEveryReservation() {
    // A = 32 − 8 = 24: h[CI] = min(2, 4) = 2, A = 12; h[WS] = min(1, 2) = 1 — h = r.
    TargetSlots empty = wsProofWithBuilder(counts(0, 0, 0), 32 * GIB, 0);
    assertEquals(counts(2, 1, 0), SlotAllocator.honouredReservations(empty, SlotType.WORKSPACE));
    assertEquals(counts(2, 1, 0), SlotAllocator.honouredReservations(empty, SlotType.CI));
    // Workspaces fit while M + 2 × 6 + 8 + 6 ≤ 32: at M = 0 (26) and M = 6 (32), not at M = 12 (38).
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(empty),
            List.of(
                charged(SlotType.WORKSPACE, 0, SIX_G),
                charged(SlotType.WORKSPACE, 1, SIX_G),
                charged(SlotType.WORKSPACE, 2, SIX_G)));
    assertEquals(List.of(true, true, false), decisions.stream().map(Decision::granted).toList());
    assertEquals("no target has memory for a 6g WORKSPACE lease", decisions.get(2).detail());
  }

  @Test
  void wsProofWithoutABuilderHoldsTwoSixGCiAndNoWorkspace() {
    // 14 GiB, no builder cap: h[CI] = 2, h[WS] = 0. One CI and one workspace running (M = 12):
    // CI finds 12 + 0 + 6 = 18 > 14, a workspace 12 + (2 − 1) × 6 + 6 = 24 > 14.
    TargetSlots two = wsProofAt14(counts(1, 1, 0), 2 * SIX_G);
    for (SlotType type : List.of(SlotType.CI, SlotType.WORKSPACE)) {
      assertTrue(SlotAllocator.slotsFit(two, type), type.name());
      Decision decision =
          SlotAllocator.allocate(List.of(two), List.of(charged(type, 0, SIX_G))).get(0);
      assertFalse(decision.granted(), type.name());
      assertEquals("no target has memory for a 6g " + type.name() + " lease", decision.detail());
    }
    // From empty, one pass of four: each workspace finds 0 + 12 + 6 = 18 > 14 and waits, the first
    // CI 0 + 0 + 6 = 6 and the second 6 + 0 + 6 = 12 are granted.
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(wsProofAt14(counts(0, 0, 0), 0)),
            List.of(
                charged(SlotType.WORKSPACE, 0, SIX_G),
                charged(SlotType.WORKSPACE, 1, SIX_G),
                charged(SlotType.CI, 2, SIX_G),
                charged(SlotType.CI, 3, SIX_G)));
    assertEquals(
        List.of(false, false, true, true), decisions.stream().map(Decision::granted).toList());
  }

  @Test
  void reservationsTheMemoryCanMeetStillHoldIt() {
    // 20 GiB: Σ r × c = 18 ≤ 20, so h = r, and a workspace beside one running must leave ci's
    // 12 GiB: 6 + 12 + 6 = 24 > 20.
    TargetSlots oneWs = wsProofMemory(counts(0, 1, 0), 20 * GIB, SIX_G, lastAtSixG());
    assertEquals(counts(2, 1, 0), SlotAllocator.honouredReservations(oneWs, SlotType.WORKSPACE));
    assertFalse(SlotAllocator.fits(oneWs, SlotType.WORKSPACE, SIX_G));
    // The same target at 14 GiB honours ci 2 (A = 14: ⌊14/6⌋ = 2) and workspace 0, so the second
    // workspace still finds 6 + 12 + 6 = 24 > 14: ci's reservation comes first.
    TargetSlots oneWsAt14 = wsProofAt14(counts(0, 1, 0), SIX_G);
    assertEquals(counts(2, 0, 0), SlotAllocator.honouredReservations(oneWsAt14, SlotType.WORKSPACE));
    assertFalse(SlotAllocator.fits(oneWsAt14, SlotType.WORKSPACE, SIX_G));
  }

  @Test
  void aTargetShortOfMemoryLeavesTheLeaseToOneThatHasIt() {
    TargetSlots full =
        new TargetSlots(
            A, "alpha", 8, counts(0, 0, 0), null, counts(0, 0, 0), true, false, true,
            new SlotAllocator.Memory(4 * GIB, 0, Map.of()));
    TargetSlots roomy =
        new TargetSlots(
            B, "beta", 2, counts(0, 0, 0), null, counts(0, 0, 0), true, false, true,
            new SlotAllocator.Memory(8 * GIB, 0, Map.of()));
    assertEquals(
        B,
        SlotAllocator.allocate(List.of(full, roomy), List.of(charged(SlotType.CI, 0, SIX_G)))
            .get(0)
            .targetId());
  }

  // --- the builder (qits-2503): charged on a target that takes CI ------------------------------

  /** A target of 8 slots with {@code reservations}, {@code used}, a memory picture and a builder. */
  private static TargetSlots withBuilder(
      Map<SlotType, Integer> reservations,
      Map<SlotType, Integer> used,
      long capacity,
      long committed,
      Long builder) {
    return new TargetSlots(
        A,
        "ws-proof",
        8,
        reservations,
        null,
        used,
        true,
        false,
        true,
        new SlotAllocator.Memory(
            capacity, committed, lastAtSixG(), SlotAllocator.Memory.builderOf(builder)));
  }

  @Test
  void aTargetRunningCiHoldsOneSixGLeaseBesideItsBuilder() {
    // 14 GiB, one 6g CI lease occupying it, a 6 GiB builder: 6 + 6 + 6 = 18 > 14 for either type,
    // where without the builder qits-2362's rule took any two.
    // A = 14 − 6 = 8 honours ci 1 (⌊8/6⌋) and workspace 0; ci's one is in use, so nothing more is
    // held, and the lease, the builder and the candidate alone overflow.
    TargetSlots oneCi = withBuilder(counts(2, 1, 0), counts(1, 0, 0), 14 * GIB, SIX_G, SIX_G);
    assertEquals(counts(1, 0, 0), SlotAllocator.honouredReservations(oneCi, SlotType.WORKSPACE));
    for (SlotType type : List.of(SlotType.CI, SlotType.WORKSPACE, SlotType.DESK, SlotType.AGENT)) {
      assertTrue(SlotAllocator.slotsFit(oneCi, type), type.name());
      Decision decision =
          SlotAllocator.allocate(List.of(oneCi), List.of(charged(type, 0, SIX_G))).get(0);
      assertFalse(decision.granted(), type.name());
      assertEquals("no target has memory for a 6g " + type.name() + " lease", decision.detail());
    }
    // A CI reservation with nothing running charges it too: the runner starts the builder for a
    // target that may take CI. One 6g workspace there leaves no room for a second.
    TargetSlots oneWsCiReserved =
        withBuilder(counts(2, 1, 0), counts(0, 1, 0), 14 * GIB, SIX_G, SIX_G);
    assertFalse(SlotAllocator.fits(oneWsCiReserved, SlotType.WORKSPACE, SIX_G));
  }

  @Test
  void aTargetThatTakesNoCiIsNotChargedTheBuilder() {
    // No CI reservation, no CI lease, a workspace candidate: the builder never starts there, so
    // 6 + 6 ≤ 14 and the second 6g workspace is granted beside the first.
    TargetSlots wsOnly = withBuilder(counts(0, 1, 0), counts(0, 1, 0), 14 * GIB, SIX_G, SIX_G);
    assertTrue(SlotAllocator.fits(wsOnly, SlotType.WORKSPACE, SIX_G));
    assertTrue(SlotAllocator.fits(wsOnly, SlotType.DESK, SIX_G));
    assertTrue(SlotAllocator.fits(wsOnly, SlotType.AGENT, SIX_G));
    assertEquals(
        A,
        SlotAllocator.allocate(List.of(wsOnly), List.of(charged(SlotType.WORKSPACE, 0, SIX_G)))
            .get(0)
            .targetId());
    // ... while a CI candidate on the same target would start it: 6 + 6 + 6 > 14.
    assertFalse(SlotAllocator.fits(wsOnly, SlotType.CI, SIX_G));
  }

  @Test
  void aCiCandidateOnAnEmptyTargetIsChargedTheBuilder() {
    // 8 GiB, nothing reserved, nothing running: the CI lease's 6g plus the 6 GiB builder is 12 > 8.
    TargetSlots empty = withBuilder(counts(0, 0, 0), counts(0, 0, 0), 8 * GIB, 0, SIX_G);
    Decision decision =
        SlotAllocator.allocate(List.of(empty), List.of(charged(SlotType.CI, 0, SIX_G))).get(0);
    assertFalse(decision.granted());
    assertEquals("no target has memory for a 6g CI lease", decision.detail());
    // The same 6g as a workspace is not charged it, and fits.
    assertTrue(SlotAllocator.fits(empty, SlotType.WORKSPACE, SIX_G));
    // A grant of CI in a pass makes the target take CI for the rest of it: a workspace after it
    // finds 6 + 6 + 2 > 12 on a 12 GiB target that took the CI lease alone at 6 + 6.
    TargetSlots twelve = withBuilder(counts(0, 0, 0), counts(0, 0, 0), 12 * GIB, 0, SIX_G);
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(twelve),
            List.of(charged(SlotType.CI, 0, SIX_G), charged(SlotType.WORKSPACE, 1, 2 * GIB)));
    assertEquals(List.of(true, false), decisions.stream().map(Decision::granted).toList());
  }

  @Test
  void aBuilderThatReportsNoCapIsChargedNothing() {
    // An older runner reports null: no builder, so A = 14 honours ci 2 and workspace 0
    // (qits-2515). Beside one 6g CI lease a second CI fits, 6 + 0 + 6 = 12, but a workspace must
    // leave ci's second reservation: 6 + 1 × 6 + 6 = 18 > 14.
    TargetSlots oneCi = withBuilder(counts(2, 1, 0), counts(1, 0, 0), 14 * GIB, SIX_G, null);
    assertEquals(0, oneCi.memory().builderBytes());
    assertEquals(counts(2, 0, 0), SlotAllocator.honouredReservations(oneCi, SlotType.WORKSPACE));
    assertTrue(SlotAllocator.fits(oneCi, SlotType.CI, SIX_G));
    assertFalse(SlotAllocator.fits(oneCi, SlotType.WORKSPACE, SIX_G));
    TargetSlots emptyEight = withBuilder(counts(0, 0, 0), counts(0, 0, 0), 8 * GIB, 0, null);
    assertTrue(SlotAllocator.fits(emptyEight, SlotType.CI, SIX_G));
    // And the three-argument memory is the same as a null builder.
    assertEquals(
        new SlotAllocator.Memory(14 * GIB, SIX_G, lastAtSixG()),
        new SlotAllocator.Memory(
            14 * GIB, SIX_G, lastAtSixG(), SlotAllocator.Memory.builderOf(null)));
  }

  // --- which slot types a target can ever run (qits-1692: its warm images) ---------------------

  @Test
  void aCiOnlyTargetCanEverRunCiAlone() {
    // T=2 all reserved for ci: workspace and desk have 2 - 2 = 0 and never fit.
    Map<SlotType, Integer> ciOnly = counts(2, 0, 0);
    assertTrue(SlotAllocator.canEverRun(2, ciOnly, SlotType.CI));
    assertFalse(SlotAllocator.canEverRun(2, ciOnly, SlotType.WORKSPACE));
    assertFalse(SlotAllocator.canEverRun(2, ciOnly, SlotType.DESK));
    assertFalse(SlotAllocator.canEverRun(2, ciOnly, SlotType.AGENT));
  }

  @Test
  void aHostWithNoCiRoomRunsWorkspaceAndDeskOnly() {
    // T=4, workspace 2, desk 2: CI has 4 - 4 = 0 and never fits; each agent type has 4 - 2 = 2.
    Map<SlotType, Integer> agents = counts(0, 2, 2);
    assertFalse(SlotAllocator.canEverRun(4, agents, SlotType.CI));
    assertTrue(SlotAllocator.canEverRun(4, agents, SlotType.WORKSPACE));
    assertTrue(SlotAllocator.canEverRun(4, agents, SlotType.DESK));
  }

  @Test
  void reservationsAboveTheTotalAreIgnoredAndNoSlotsRunNothing() {
    assertTrue(SlotAllocator.canEverRun(2, counts(3, 0, 0), SlotType.WORKSPACE));
    for (SlotType type : SlotType.values()) {
      assertFalse(SlotAllocator.canEverRun(0, counts(0, 0, 0), type));
    }
  }
}
