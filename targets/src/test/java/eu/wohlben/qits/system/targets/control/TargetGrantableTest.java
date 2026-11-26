package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.system.targets.control.SlotAllocator.Grantable;
import eu.wohlben.qits.system.targets.control.SlotAllocator.Memory;
import eu.wohlben.qits.system.targets.control.SlotAllocator.Request;
import eu.wohlben.qits.system.targets.control.SlotAllocator.TargetSlots;
import eu.wohlben.qits.system.targets.entity.SlotType;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SlotAllocator#grantable} as a table (qits-2573): how many more leases of a type the
 * targets can grant now, by the allocator's own rule. The estate as it stood on 2026-11-26 21:10Z
 * first: every target grants 0 workspaces, with the reason each gives.
 */
class TargetGrantableTest {

  private static final UUID LOCALHOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID WS_PROOF = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID WORKSTATION = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private static final long GIB = 1024L * 1024 * 1024;
  private static final long RESERVE = 2 * GIB;
  private static final long BUILDER = 8 * GIB;
  private static final long LEASE = 6 * GIB;

  private static Map<SlotType, Integer> counts(int ci, int workspace, int desk, int agent) {
    Map<SlotType, Integer> counts = new EnumMap<>(SlotType.class);
    counts.put(SlotType.CI, ci);
    counts.put(SlotType.WORKSPACE, workspace);
    counts.put(SlotType.DESK, desk);
    counts.put(SlotType.AGENT, agent);
    return counts;
  }

  private static Map<SlotType, Integer> maximum(SlotType type, int value) {
    Map<SlotType, Integer> maximums = new EnumMap<>(SlotType.class);
    maximums.put(type, value);
    return maximums;
  }

  private static Map<SlotType, Long> charges(SlotType... types) {
    Map<SlotType, Long> charges = new EnumMap<>(SlotType.class);
    for (SlotType type : types) {
      charges.put(type, LEASE);
    }
    return charges;
  }

  private static Memory memory(long bytes, Map<SlotType, Integer> used, Map<SlotType, Long> last) {
    long committed = used.values().stream().mapToInt(Integer::intValue).sum() * LEASE;
    return new Memory(Memory.capacity(RESERVE, bytes), committed, last, BUILDER);
  }

  /** ws-proof at 42 GiB: 8 slots, ci 2 + ws 3, at most ci 2 and ws 3, with {@code used}. */
  private static TargetSlots wsProof(Map<SlotType, Integer> used, Map<SlotType, Integer> maximums) {
    return new TargetSlots(
        WS_PROOF,
        "ws-proof",
        8,
        counts(2, 3, 0, 0),
        maximums,
        used,
        true,
        false,
        true,
        memory(42 * GIB, used, charges(SlotType.CI, SlotType.WORKSPACE)));
  }

  /** localhost: 2 slots, desk 1 + agent 1, both used; no memory reported in this table. */
  private static TargetSlots localhost() {
    return new TargetSlots(
        LOCALHOST,
        "localhost",
        2,
        counts(0, 0, 1, 1),
        null,
        counts(0, 0, 1, 1),
        true,
        false,
        true);
  }

  /** The workstation: ci 6 of 6 reserved, not connected since 2026-11-23. */
  private static TargetSlots workstation(boolean connected) {
    return new TargetSlots(
        WORKSTATION,
        "workstation",
        6,
        counts(6, 0, 0, 0),
        null,
        counts(0, 0, 0, 0),
        connected,
        false,
        true);
  }

  /** {@link SlotAllocator#grantable} with the estate's newest charge at 6g, as on the 26th. */
  private static List<Grantable> grantable(
      List<TargetSlots> targets, List<Request> queue, SlotType type) {
    return SlotAllocator.grantable(targets, queue, type, LEASE);
  }

  private static Grantable of(List<Grantable> all, UUID id) {
    return all.stream().filter(g -> g.targetId().equals(id)).findFirst().orElseThrow();
  }

  private static int sum(List<Grantable> all) {
    return all.stream().mapToInt(Grantable::grantable).sum();
  }

  @Test
  void theEstateOfTheTwentySixthGrantsNoWorkspace() {
    List<Grantable> all =
        grantable(
            List.of(
                localhost(),
                wsProof(counts(2, 3, 0, 0), maximum(SlotType.WORKSPACE, 3)),
                workstation(false)),
            List.of(),
            SlotType.WORKSPACE);
    assertEquals(0, sum(all));
    assertEquals(3, of(all, WS_PROOF).held());
    assertEquals(
        "target ws-proof runs its maximum of 3 WORKSPACE lease(s)", of(all, WS_PROOF).detail());
    assertEquals("target localhost has no free WORKSPACE slot", of(all, LOCALHOST).detail());
    assertEquals("target workstation is not connected", of(all, WORKSTATION).detail());
  }

  @Test
  void wsProofWithNoMaximumIsStillHeldToThreeByItsMemory() {
    // 40 GiB of capacity: 5 × 6g + the 8g builder = 38, a 4th workspace needs 44.
    Grantable proof =
        grantable(
                List.of(wsProof(counts(2, 3, 0, 0), null)), List.of(), SlotType.WORKSPACE)
            .get(0);
    assertEquals(0, proof.grantable());
    assertEquals("target ws-proof has no memory for a 6g WORKSPACE lease", proof.detail());
  }

  @Test
  void wsProofKeepsItsHonouredCiReservationWhenItsCiLeasesEnd() {
    // 3 × 6g + the two reserved CI at 6g + the builder + 6g = 44 > 40.
    Grantable proof =
        grantable(
                List.of(wsProof(counts(0, 3, 0, 0), null)), List.of(), SlotType.WORKSPACE)
            .get(0);
    assertEquals(0, proof.grantable());
  }

  @Test
  void anEmptyWsProofGrantsItsThreeWorkspacesAndNamesTheMaximum() {
    Grantable proof =
        grantable(
                List.of(wsProof(counts(0, 0, 0, 0), maximum(SlotType.WORKSPACE, 3))),
                List.of(),
                SlotType.WORKSPACE)
            .get(0);
    assertEquals(3, proof.grantable());
    assertEquals(0, proof.held());
    assertEquals(
        "target ws-proof runs its maximum of 3 WORKSPACE lease(s)", proof.detail());
  }

  @Test
  void aLeaseAlreadyQueuedTakesItsPlaceFirst() {
    TargetSlots proof = wsProof(counts(0, 1, 0, 0), maximum(SlotType.WORKSPACE, 3));
    Request queued =
        new Request(UUID.randomUUID(), SlotType.WORKSPACE, 0, Instant.EPOCH, null, LEASE);
    assertEquals(
        2, grantable(List.of(proof), List.of(), SlotType.WORKSPACE).get(0).grantable());
    Grantable after =
        grantable(List.of(proof), List.of(queued), SlotType.WORKSPACE).get(0);
    assertEquals(1, after.grantable());
    assertEquals(1, after.held(), "held is what the target holds now, not after the queue");
  }

  @Test
  void aSignedOutTargetGrantsNoWorkspaceAndSaysWhy() {
    TargetSlots signedOut =
        new TargetSlots(
            LOCALHOST, "localhost", 4, counts(0, 0, 0, 0), null, counts(0, 0, 0, 0), true, false,
            false);
    Grantable g =
        grantable(List.of(signedOut), List.of(), SlotType.WORKSPACE).get(0);
    assertEquals(0, g.grantable());
    assertEquals(SlotAllocator.notSignedIn("localhost"), g.detail());
  }

  @Test
  void aTargetThatNeverRanTheTypeIsPricedLikeTheOthers() {
    // A 16 GiB target (14 of capacity) that never ran a workspace: at 6g, from ws-proof's charge,
    // it holds 2; priced at nothing it would take all 4 of its slots.
    TargetSlots fresh =
        new TargetSlots(
            LOCALHOST,
            "fresh",
            4,
            counts(0, 0, 0, 0),
            null,
            counts(0, 0, 0, 0),
            true,
            false,
            true,
            new Memory(Memory.capacity(RESERVE, 16 * GIB), 0, Map.of(), 0));
    List<Grantable> all =
        grantable(
            List.of(fresh, wsProof(counts(2, 3, 0, 0), maximum(SlotType.WORKSPACE, 3))),
            List.of(),
            SlotType.WORKSPACE);
    assertEquals(2, of(all, LOCALHOST).grantable());
    assertEquals("target fresh has no memory for a 6g WORKSPACE lease", of(all, LOCALHOST).detail());
  }

  @Test
  void aTypeNeverGrantedAnywhereIsPricedAtNothing() {
    // The same fresh target, the estate holding no charge of the type: 0 a lease, so its four free
    // slots are the whole limit.
    TargetSlots fresh =
        new TargetSlots(
            LOCALHOST,
            "fresh",
            4,
            counts(0, 0, 0, 0),
            null,
            counts(0, 0, 0, 0),
            true,
            false,
            true,
            new Memory(Memory.capacity(RESERVE, 16 * GIB), 0, Map.of(), 0));
    Grantable g =
        SlotAllocator.grantable(List.of(fresh), List.of(), SlotType.WORKSPACE, 0).get(0);
    assertEquals(4, g.grantable());
    assertEquals("target fresh has no free WORKSPACE slot", g.detail());
  }

  @Test
  void aSixtyFourGibTargetGrantsWhatItsMemoryHolds() {
    // 62 GiB of capacity, 10 slots, no CI taken so no builder: ⌊62 / 6⌋ = 10 by memory, 10 slots,
    // one held already: 9 more.
    Map<SlotType, Integer> used = counts(0, 1, 0, 0);
    TargetSlots big =
        new TargetSlots(
            WS_PROOF,
            "big",
            10,
            counts(0, 0, 0, 0),
            null,
            used,
            true,
            false,
            true,
            memory(64 * GIB, used, charges(SlotType.WORKSPACE)));
    Grantable g = grantable(List.of(big), List.of(), SlotType.WORKSPACE).get(0);
    assertEquals(9, g.grantable());
    assertEquals(1, g.held());
    assertEquals("target big has no free WORKSPACE slot", g.detail());
    TargetSlots twelve =
        new TargetSlots(
            WS_PROOF,
            "big",
            12,
            counts(0, 0, 0, 0),
            null,
            used,
            true,
            false,
            true,
            memory(64 * GIB, used, charges(SlotType.WORKSPACE)));
    // With 12 slots the memory stops it first: 10 × 6g = 60 ≤ 62, an 11th needs 66.
    Grantable byMemory = grantable(List.of(twelve), List.of(), SlotType.WORKSPACE).get(0);
    assertEquals(9, byMemory.grantable());
    assertEquals("target big has no memory for a 6g WORKSPACE lease", byMemory.detail());
  }

  @Test
  void unknownMemoryIsHeldToTheSlotRuleAlone() {
    TargetSlots slotsOnly =
        new TargetSlots(
            LOCALHOST, "big", 4, counts(1, 0, 0, 0), null, counts(0, 0, 0, 0), true, false, true);
    Grantable g = grantable(List.of(slotsOnly), List.of(), SlotType.WORKSPACE).get(0);
    assertEquals(3, g.grantable(), "one slot stays reserved for CI");
    assertEquals("target big has no free WORKSPACE slot", g.detail());
  }

  @Test
  void aConnectedWorkstationStillGrantsNoWorkspaceItsSlotsAreAllCi() {
    Grantable g =
        grantable(List.of(workstation(true)), List.of(), SlotType.WORKSPACE).get(0);
    assertEquals(0, g.grantable());
    assertEquals("target workstation has no free WORKSPACE slot", g.detail());
  }

  @Test
  void anotherTypeIsCountedByItsOwnRule() {
    // CI, with the builder charged and ws-proof's three reserved workspaces kept free: 2 fit.
    Grantable g =
        grantable(
                List.of(wsProof(counts(0, 0, 0, 0), maximum(SlotType.WORKSPACE, 3))),
                List.of(),
                SlotType.CI)
            .get(0);
    assertEquals(2, g.grantable());
    assertEquals("target ws-proof has no memory for a 6g CI lease", g.detail());
  }
}
