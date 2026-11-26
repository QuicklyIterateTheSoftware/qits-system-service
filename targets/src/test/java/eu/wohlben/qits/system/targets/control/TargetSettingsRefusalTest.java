package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.system.targets.control.SlotAllocator.Memory;
import eu.wohlben.qits.system.targets.control.SlotAllocator.TargetSlots;
import eu.wohlben.qits.system.targets.entity.SlotType;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SlotAllocator#settingsRefusal} as a table (qits-2581): slot settings a target's reported
 * memory can never hold, with placement's own figures. The live figures of 2026-11-23 first.
 */
class TargetSettingsRefusalTest {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

  private static final long GIB = 1024L * 1024 * 1024;
  private static final long RESERVE = 2 * GIB;
  private static final long BUILDER = 8 * GIB;
  private static final long LEASE = 6 * GIB;

  /** 31.2 GiB of MemTotal, so 29.2 GiB of capacity. */
  private static final long WORKSTATION_MEMORY = (long) (31.2 * GIB);

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

  private static TargetSlots target(
      String name,
      int total,
      Map<SlotType, Integer> reservations,
      Map<SlotType, Integer> maximums,
      Long memoryBytes,
      long builderBytes,
      Map<SlotType, Long> lastCharge) {
    Memory memory =
        memoryBytes == null
            ? Memory.UNKNOWN
            : new Memory(Memory.capacity(RESERVE, memoryBytes), 0, lastCharge, builderBytes);
    return new TargetSlots(
        A, name, total, reservations, maximums, null, true, false, true, memory);
  }

  // --- the workstation: 29.2 GiB, an 8 GiB builder, CI charged 6g --------------------------------

  @Test
  void theWorkstationsSixReservedCiAreRefusedNamingThree() {
    TargetSlots workstation =
        target(
            "workstation",
            6,
            counts(6, 0, 0, 0),
            null,
            WORKSTATION_MEMORY,
            BUILDER,
            charges(SlotType.CI));
    assertEquals(
        "target workstation can honour at most 3 CI reserved lease(s) of 6 GiB"
            + " (29.2 GiB less the 8 GiB builder), not 6 CI",
        SlotAllocator.settingsRefusal(workstation));
  }

  @Test
  void theWorkstationAtThreeReservedCiIsAccepted() {
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "workstation",
                6,
                counts(3, 0, 0, 0),
                null,
                WORKSTATION_MEMORY,
                BUILDER,
                charges(SlotType.CI))));
  }

  @Test
  void theWorkstationsCiMaximumOfSixIsRefusedNamingThree() {
    TargetSlots workstation =
        target(
            "workstation",
            6,
            counts(0, 0, 0, 0),
            maximum(SlotType.CI, 6),
            WORKSTATION_MEMORY,
            BUILDER,
            charges(SlotType.CI));
    assertEquals(
        "target workstation holds at most 3 CI lease(s) of 6 GiB"
            + " (29.2 GiB less the 8 GiB builder), not 6",
        SlotAllocator.settingsRefusal(workstation));
  }

  @Test
  void theWorkstationsCiMaximumOfThreeIsAccepted() {
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "workstation",
                6,
                counts(0, 0, 0, 0),
                maximum(SlotType.CI, 3),
                WORKSTATION_MEMORY,
                BUILDER,
                charges(SlotType.CI))));
  }

  // --- ws-proof: ci 2 + workspace 3 reserved, both charged 6g -------------------------------------

  @Test
  void wsProofAt32GibCannotHonourThreeWorkspaces() {
    TargetSlots wsProof =
        target(
            "ws-proof",
            6,
            counts(2, 3, 0, 0),
            null,
            32 * GIB,
            BUILDER,
            charges(SlotType.CI, SlotType.WORKSPACE));
    assertEquals(
        "target ws-proof can honour at most 2 CI and 1 WORKSPACE reserved lease(s) of 6 GiB"
            + " (30 GiB less the 8 GiB builder), not 2 CI and 3 WORKSPACE",
        SlotAllocator.settingsRefusal(wsProof));
  }

  @Test
  void wsProofAt32GibHonoursOneWorkspace() {
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "ws-proof",
                6,
                counts(2, 1, 0, 0),
                null,
                32 * GIB,
                BUILDER,
                charges(SlotType.CI, SlotType.WORKSPACE))));
  }

  @Test
  void wsProofAt42GibHonoursThreeWorkspaces() {
    // 40 GiB of capacity: 8 + 2 × 6 + 3 × 6 = 38 ≤ 40.
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "ws-proof",
                6,
                counts(2, 3, 0, 0),
                null,
                42 * GIB,
                BUILDER,
                charges(SlotType.CI, SlotType.WORKSPACE))));
  }

  // --- what is not charged or not checked ---------------------------------------------------------

  @Test
  void aTargetTakingNoCiIsNotChargedTheBuilder() {
    // 14 GiB: two 6g workspaces fit only with no builder charged.
    TargetSlots workspaces =
        target(
            "localhost",
            4,
            counts(0, 2, 0, 0),
            maximum(SlotType.WORKSPACE, 2),
            16 * GIB,
            BUILDER,
            charges(SlotType.WORKSPACE));
    assertNull(SlotAllocator.settingsRefusal(workspaces));
    TargetSlots three =
        target(
            "localhost",
            4,
            counts(0, 0, 0, 0),
            maximum(SlotType.WORKSPACE, 3),
            16 * GIB,
            BUILDER,
            charges(SlotType.WORKSPACE));
    assertEquals(
        "target localhost holds at most 2 WORKSPACE lease(s) of 6 GiB (14 GiB), not 3",
        SlotAllocator.settingsRefusal(three));
  }

  @Test
  void aTypeNeverGrantedThereIsNotChecked() {
    // c[DESK] = 0: placement prices it at nothing, so neither its maximum nor its reservation counts.
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "lab",
                8,
                counts(0, 0, 8, 0),
                maximum(SlotType.DESK, 8),
                8 * GIB,
                BUILDER,
                charges(SlotType.CI))));
  }

  @Test
  void anUnsetMaximumIsNotChecked() {
    assertNull(
        SlotAllocator.settingsRefusal(
            target("lab", 8, counts(0, 0, 0, 0), null, 8 * GIB, BUILDER, charges(SlotType.CI))));
  }

  @Test
  void reservationsAboveTheTotalAreLeftToTheSlotRule() {
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "lab",
                2,
                counts(6, 0, 0, 0),
                null,
                WORKSTATION_MEMORY,
                BUILDER,
                charges(SlotType.CI))));
  }

  @Test
  void anUnknownCapacityAcceptsAnything() {
    assertNull(
        SlotAllocator.settingsRefusal(
            target(
                "fresh",
                6,
                counts(6, 0, 0, 0),
                maximum(SlotType.CI, 1024),
                null,
                BUILDER,
                charges(SlotType.CI))));
  }

  @Test
  void differentChargesAreNamedPerType() {
    Map<SlotType, Long> charges = charges(SlotType.CI);
    charges.put(SlotType.WORKSPACE, 4 * GIB);
    // 20 GiB less the 8 GiB builder leaves 12: two CI at 6g, and nothing for a 4g workspace.
    assertEquals(
        "target ws-proof can honour at most 2 CI of 6 GiB and 0 WORKSPACE of 4 GiB reserved"
            + " lease(s) (20 GiB less the 8 GiB builder), not 2 CI and 1 WORKSPACE",
        SlotAllocator.settingsRefusal(
            target("ws-proof", 6, counts(2, 1, 0, 0), null, 22 * GIB, BUILDER, charges)));
  }
}
