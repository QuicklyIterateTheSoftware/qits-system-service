package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.control.SlotAllocator.Builder;
import eu.wohlben.qits.system.targets.control.SlotAllocator.Decision;
import eu.wohlben.qits.system.targets.control.SlotAllocator.Memory;
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

/**
 * {@link SlotAllocator}'s builder cap (qits-2568) as a table: a target whose builder gate runs
 * {@code W} builds at once holds at most {@code W + 1} image-building CI leases, and the leases that
 * build nothing take what is left.
 */
class BuilderCapAllocatorTest {

  private static final Instant T0 = Instant.parse("2026-11-22T07:17:31Z");

  private static final UUID WORKSTATION = UUID.fromString("28272007-0418-45c1-97f3-6505a89c6502");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  private static final long GIB = 1024L * 1024 * 1024;
  private static final long SIX_G = 6 * GIB;

  /** The workstation as it reports itself: MemTotal 33529393152, less the 2 GiB node reserve. */
  private static final long WORKSTATION_CAPACITY = 33_529_393_152L - 2 * GIB;

  private static Map<SlotType, Integer> ci(int n) {
    Map<SlotType, Integer> counts = new EnumMap<>(SlotType.class);
    counts.put(SlotType.CI, n);
    return counts;
  }

  private static Map<SlotType, Long> lastCiAtSixG() {
    Map<SlotType, Long> last = new EnumMap<>(SlotType.class);
    last.put(SlotType.CI, SIX_G);
    return last;
  }

  /**
   * The workstation: 6 slots, ci 6 reserved, an 8 GiB builder of width {@code width}, {@code
   * running} 6g CI leases of which {@code building} build.
   */
  private static TargetSlots workstation(Integer width, int running, int building) {
    return new TargetSlots(
        WORKSTATION,
        "workstation",
        6,
        ci(6),
        null,
        ci(running),
        true,
        false,
        true,
        new Memory(WORKSTATION_CAPACITY, running * SIX_G, lastCiAtSixG(), 8 * GIB),
        new Builder(width, building));
  }

  /** A target that reports no memory, so only slots and the builder decide. */
  private static TargetSlots roomy(UUID id, String name, Integer width, int running, int building) {
    return new TargetSlots(
        id, name, 10, ci(0), null, ci(running), true, false, true, null,
        new Builder(width, building));
  }

  private static Request ci(int priority, int secondsAfter, boolean builds) {
    return new Request(
        UUID.randomUUID(), SlotType.CI, priority, T0.plusSeconds(secondsAfter), null, SIX_G, null,
        builds);
  }

  private static Request pinned(UUID target, int secondsAfter, boolean builds) {
    return new Request(
        UUID.randomUUID(), SlotType.CI, 0, T0.plusSeconds(secondsAfter), target, SIX_G, null,
        builds);
  }

  // --- the cap -----------------------------------------------------------------------------------

  @Test
  void aBuilderOfOneHoldsTwoBuildingLeases() {
    assertFalse(new Builder(1, 1).full());
    assertTrue(new Builder(1, 2).full());
    assertFalse(new Builder(4, 4).full());
    assertTrue(new Builder(4, 5).full());
    assertFalse(new Builder(null, 9).full(), "no width reported caps nothing");
    assertTrue(new Builder(0, 1).full());
  }

  @Test
  void aThirdBuildingLeaseWaitsWhileOneThatBuildsNothingIsGranted() {
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(roomy(WORKSTATION, "workstation", 1, 2, 2)),
            List.of(ci(100, 0, true), ci(0, 1, false)));
    assertFalse(decisions.get(0).granted());
    assertEquals(SlotAllocator.NO_BUILDER_ROOM, decisions.get(0).detail());
    assertTrue(decisions.get(1).granted());
  }

  @Test
  void onePassWithThreeBuildingRequestsGrantsTwo() {
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(roomy(WORKSTATION, "workstation", 1, 0, 0)),
            List.of(ci(100, 0, true), ci(100, 1, true), ci(100, 2, true)));
    assertEquals(2, decisions.stream().filter(Decision::granted).count());
    assertEquals(SlotAllocator.NO_BUILDER_ROOM, decisions.get(2).detail());
  }

  @Test
  void aWiderBuilderHoldsItsWidthPlusOne() {
    List<Request> queue = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      queue.add(ci(0, i, true));
    }
    List<Decision> decisions =
        SlotAllocator.allocate(List.of(roomy(WORKSTATION, "workstation", 4, 0, 0)), queue);
    assertEquals(5, decisions.stream().filter(Decision::granted).count());
  }

  @Test
  void aTargetThatReportsNoWidthCapsNothing() {
    List<Request> queue = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      queue.add(ci(0, i, true));
    }
    List<Decision> decisions =
        SlotAllocator.allocate(List.of(roomy(WORKSTATION, "workstation", null, 0, 0)), queue);
    assertEquals(5, decisions.stream().filter(Decision::granted).count());
  }

  @Test
  void leasesThatBuildNothingAreNeverCountedNorHeld() {
    // Six non-building leases already run beside a full builder; three more are still granted.
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(roomy(WORKSTATION, "workstation", 1, 8, 2)),
            List.of(ci(0, 0, false), ci(0, 1, false)));
    assertTrue(decisions.stream().allMatch(Decision::granted));
  }

  @Test
  void aPinnedBuildingLeaseNamesTheTargetAndItsBuilder() {
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(roomy(WORKSTATION, "workstation", 1, 2, 2)),
            List.of(pinned(WORKSTATION, 0, true), pinned(WORKSTATION, 1, false)));
    assertEquals(
        "target workstation runs 2 image-building CI lease(s) for a builder of 1 build(s) at a"
            + " time",
        decisions.get(0).detail());
    assertTrue(decisions.get(1).granted());
  }

  @Test
  void aBuildingLeaseGoesToATargetWhoseBuilderHasRoom() {
    // The full target has more free slots, so it would win without the cap.
    List<Decision> decisions =
        SlotAllocator.allocate(
            List.of(
                roomy(WORKSTATION, "workstation", 1, 2, 2),
                new TargetSlots(
                    OTHER, "ws-proof", 4, ci(0), null, ci(1), true, false, true, null,
                    new Builder(1, 1))),
            List.of(ci(0, 0, true)));
    assertEquals(OTHER, decisions.get(0).targetId());
  }

  @Test
  void theOlderConstructorsReportNoWidth() {
    TargetSlots plain =
        new TargetSlots(WORKSTATION, "w", 6, ci(0), ci(0), true, false, true);
    assertEquals(Builder.UNKNOWN, plain.builder());
    assertFalse(
        new Request(UUID.randomUUID(), SlotType.CI, 0, T0, null, SIX_G, null).builds());
  }

  // --- the window of 2026-11-22 07:17–07:52Z, replayed --------------------------------------------

  /**
   * Four release-tier runs that build images (d985513d, 5ca8a64a, bdd66307, 4f34497c) and the short
   * runs that build nothing (b1502b6e, dfe28d6c, 81571a64, cd4c17a8, 8c17d986) queue on the
   * workstation together. On the day all four builders took the memory and the short runs waited
   * half an hour for three of them to be cancelled. With the cap two builders run, the other two
   * wait for builder room, and the short runs take the memory that is left one after another
   * without waiting for a builder to end.
   */
  @Test
  void theWorkstationWindowRunsTheShortRunsBesideTwoBuilders() {
    List<Request> builders =
        List.of(ci(100, 0, true), ci(100, 1, true), ci(100, 2, true), ci(100, 243, true));
    List<Request> shorts =
        List.of(
            ci(0, 0, false), ci(0, 118, false), ci(0, 121, false), ci(0, 248, false),
            ci(0, 308, false));
    List<Request> queue = new ArrayList<>(builders);
    queue.addAll(shorts);

    // Pass 1: 29.2 GiB less the 8 GiB builder holds three 6g leases — two builders, one short run.
    List<Decision> first = SlotAllocator.allocate(List.of(workstation(1, 0, 0)), queue);
    assertTrue(first.get(0).granted());
    assertTrue(first.get(1).granted());
    assertEquals(SlotAllocator.NO_BUILDER_ROOM, first.get(2).detail());
    assertEquals(SlotAllocator.NO_BUILDER_ROOM, first.get(3).detail());
    assertTrue(first.get(4).granted(), "the first short run is granted beside the builders");
    for (int i = 5; i < 9; i++) {
      assertEquals("no target has memory for a 6g CI lease", first.get(i).detail());
    }

    // Each short run ends in seconds; the next is granted while both builders still run, and the
    // two waiting builders still wait for room at the builder rather than taking the memory.
    List<Request> waiting = new ArrayList<>(builders.subList(2, 4));
    waiting.addAll(shorts.subList(1, 5));
    for (int round = 0; round < 4; round++) {
      List<Decision> next = SlotAllocator.allocate(List.of(workstation(1, 2, 2)), waiting);
      assertEquals(SlotAllocator.NO_BUILDER_ROOM, next.get(0).detail());
      assertEquals(SlotAllocator.NO_BUILDER_ROOM, next.get(1).detail());
      assertTrue(next.get(2).granted(), "short run " + (round + 2) + " waits for no builder");
      waiting.remove(2);
    }

    // A builder ends: the next one takes its place at once, ahead of anything queued later.
    List<Decision> afterABuild =
        SlotAllocator.allocate(
            List.of(workstation(1, 1, 1)), List.of(ci(0, 400, false), builders.get(2)));
    assertTrue(afterABuild.get(0).granted());
    assertNull(afterABuild.get(0).detail());
    assertEquals(builders.get(2).leaseId(), afterABuild.get(0).leaseId());
  }
}
