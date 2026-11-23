package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.entity.SlotType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Which queued lease goes to which target</b> (qits-1481, dossier "Allocation"). Pure: no
 * database, no clock, no CDI — {@link TargetAllocation} reads the rows, calls {@link #allocate} and
 * writes what it answers, and {@code TargetAllocatorTest} drives it as a table.
 *
 * <p><b>The rule.</b> For a target with total {@code T}, reservations {@code r[u]}, optional
 * maximums {@code m[u]} and used counts {@code n[u]} (GRANTED, RUNNING or ENDING), a lease of type
 * {@code t} fits when
 *
 * <pre>
 *   n_total &lt; T  and  n_total + 1 + Σ_{u ≠ t} max(0, r[u] − n[u]) ≤ T  and  (m[t] unset or n[t] &lt; m[t])
 * </pre>
 *
 * — what is reserved for the other types and not yet used by them stays free for them. A
 * reservation is not a limit: a type may use more than its reservation while shared (unreserved)
 * slots are free, so ci 3 used over 1 reserved is valid when 2 shared slots were free. When {@code
 * Σ r > T} (the runner reported a smaller total than the reservations were set against) the
 * reservations are ignored and only {@code n_total < T} holds; the {@code slots} health check
 * reports that case.
 *
 * <p><b>A maximum is the limit</b> (qits-1813): a type at its maximum is granted nothing more on
 * that target however many shared slots are free, so one type cannot borrow every free slot (four
 * concurrent native CI builds took a node's memory). Unset is no ceiling, today's rule unchanged.
 * It holds whether or not the reservations are ignored, and it never reserves anything: slots a
 * type may not take stay shared for the others. The door keeps {@code m[t] ≥ r[t]}; one above
 * {@code T} is harmless.
 *
 * <p><b>Memory is one more conjunct</b> (qits-2362). A target that reports its memory has a capacity
 * {@code C} — the smaller of its {@code memory_max_bytes} and {@code memory_total_bytes} less {@code
 * qits.system.targets.node-memory-reserve} — and every lease is charged its {@code
 * spec.limits.memory} ({@link DockerSizes#charge}). With {@code M} the charges of the target's GRANTED, RUNNING and ENDING leases of every type, and {@code c[u]}
 * the charge of the most recently granted lease of type {@code u} there (0 if none ever was), a
 * lease of type {@code t} charged {@code c} also needs
 *
 * <pre>
 *   C unknown  or  M + Σ_{u ≠ t} max(0, r[u] − n[u]) × c[u] + c ≤ C
 * </pre>
 *
 * — the memory the other types' unused reservations would need stays free for them, priced at what
 * their last lease here asked for. That term is ignored when the reservations are ignored in slots
 * ({@code Σ r > T}), and likewise when they cannot be met in memory: {@code Σ_u r[u] × c[u] > C},
 * what they would hold with every type at zero use, is more than the target has (ws-proof: ci 2 and
 * workspace 1 at 6g is 18 GiB against 14). Then only {@code M + c ≤ C} holds, and the reservations
 * still apply as slots. A target that reports no memory is held to the slot rule alone. A lease the slots of
 * some target would take but whose memory none can hold waits with {@code no target has memory for
 * a 6g WORKSPACE lease}.
 *
 * <p><b>The builder is charged on a target that takes CI</b> (qits-2503). An image build a CI lease
 * sends to the target's shared buildkitd runs in the builder's cgroup, outside the lease's own
 * {@code limits.memory}, and the runner caps that builder at {@code BUILDER_CONCURRENT_BUILDS × 6
 * GiB} and reports the cap as {@code builder_memory_bytes} ({@code B}, 0 when unreported — an older
 * runner). It is charged once per target, and only when the target takes CI: {@code r[CI] > 0}, or
 * {@code n[CI] > 0}, or the candidate is a CI lease. A workspace- or desk-only target never starts
 * the builder and is not charged it. So the memory conjunct is
 *
 * <pre>
 *   C unknown  or  M + Σ_{u ≠ t} max(0, r[u] − n[u]) × c[u] + [takes CI] × B + c ≤ C
 * </pre>
 *
 * The builder stays out of the {@code Σ_u r[u] × c[u] > C} comparison: that asks whether the
 * reservations alone could ever be honoured in memory, and the builder is no reservation — it is
 * fixed overhead, charged whether they are honoured or ignored. At 6g a lease and a 6 GiB builder,
 * ws-proof (14 GiB) holds one CI lease beside the builder, not two.
 *
 * <p><b>The order.</b> One queue across types, {@code priority desc, requested_at asc} (the lease id
 * breaks a tie, so a pass is deterministic). Each lease is considered on its own: one that cannot be
 * placed does not hold back the ones behind it. A lease pinned to a target — by {@code
 * pin_target_id} or by its work volume — is considered on that target only; any other goes to the
 * target with the most free slots, ties broken by name. An unpinned lease that avoids a target
 * ({@code avoid_target_id}, qits-2382) is chosen by that rule among every other target first, and
 * goes to the avoided one only when none of them can take it; a pinned lease ignores its avoidance.
 * Avoidance never reorders the queue. Every grant counts at once — its slot, its charge and its
 * type's last charge — so one pass never over-fills a target.
 *
 * <p><b>Who gets nothing.</b> A target that is not available (quarantined, or no connection) is never
 * granted anything. A WORKSPACE or DESK lease is granted only on a target that is signed in (either
 * harness PRESENT): an agent slot on a signed-out target only produces refusals. CI is never gated.
 * A lease that waits is answered a {@link Decision#detail()} saying why, in the words an owner shows.
 */
public final class SlotAllocator {

  private SlotAllocator() {}

  /** What a target offers, as of this pass. */
  public record TargetSlots(
      UUID id,
      String name,
      int total,
      Map<SlotType, Integer> reservations,
      Map<SlotType, Integer> maximums,
      Map<SlotType, Integer> used,
      boolean connected,
      boolean quarantined,
      boolean signedIn,
      Memory memory) {

    public TargetSlots {
      reservations = counts(reservations);
      maximums = ceilings(maximums);
      used = counts(used);
      memory = memory == null ? Memory.UNKNOWN : memory;
    }

    /** A target that reports no memory: the slot rule alone. */
    public TargetSlots(
        UUID id,
        String name,
        int total,
        Map<SlotType, Integer> reservations,
        Map<SlotType, Integer> maximums,
        Map<SlotType, Integer> used,
        boolean connected,
        boolean quarantined,
        boolean signedIn) {
      this(
          id, name, total, reservations, maximums, used, connected, quarantined, signedIn, null);
    }

    /** A target with no maximums. */
    public TargetSlots(
        UUID id,
        String name,
        int total,
        Map<SlotType, Integer> reservations,
        Map<SlotType, Integer> used,
        boolean connected,
        boolean quarantined,
        boolean signedIn) {
      this(id, name, total, reservations, null, used, connected, quarantined, signedIn, null);
    }

    /** The same target with {@code memory}. */
    public TargetSlots withMemory(Memory memory) {
      return new TargetSlots(
          id, name, total, reservations, maximums, used, connected, quarantined, signedIn, memory);
    }

    public int usedTotal() {
      return used.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int free() {
      return Math.max(0, total - usedTotal());
    }

    public boolean available() {
      return connected && !quarantined;
    }

    TargetSlots plus(SlotType type, long chargeBytes) {
      Map<SlotType, Integer> next = new EnumMap<>(used);
      next.merge(type, 1, Integer::sum);
      return new TargetSlots(
          id,
          name,
          total,
          reservations,
          maximums,
          next,
          connected,
          quarantined,
          signedIn,
          memory.plus(type, chargeBytes));
    }
  }

  /**
   * A target's memory as this pass sees it (qits-2362). {@code capacityBytes} is null when the
   * target reports none — no memory check at all; {@code committedBytes} the charges of its
   * GRANTED, RUNNING and ENDING leases; {@code lastCharge} the charge of the most recently granted
   * lease of each type there, 0 for a type never granted; {@code builderBytes} the memory cap of its
   * shared buildkitd (qits-2503), charged only while the target takes CI, 0 when unreported.
   */
  public record Memory(
      Long capacityBytes, long committedBytes, Map<SlotType, Long> lastCharge, long builderBytes) {

    /** No memory reported: the slot rule alone. */
    public static final Memory UNKNOWN = new Memory(null, 0, null);

    public Memory {
      committedBytes = Math.max(0, committedBytes);
      builderBytes = Math.max(0, builderBytes);
      Map<SlotType, Long> charges = new EnumMap<>(SlotType.class);
      for (SlotType type : SlotType.values()) {
        Long value = lastCharge == null ? null : lastCharge.get(type);
        charges.put(type, value == null ? 0L : Math.max(0L, value));
      }
      lastCharge = charges;
    }

    /** A target whose builder reports no cap (an older runner): nothing charged for it. */
    public Memory(Long capacityBytes, long committedBytes, Map<SlotType, Long> lastCharge) {
      this(capacityBytes, committedBytes, lastCharge, 0);
    }

    /** {@code builder_memory_bytes} as the row holds it: null, from an older runner, is 0. */
    public static long builderOf(Long reported) {
      return reported == null ? 0L : Math.max(0L, reported);
    }

    /**
     * The capacity of a node whose figures are {@code figures}: the smallest that is known, less
     * {@code reserveBytes}, never below 0; null when none is known. The figures are {@code
     * memory_max_bytes} (the node's root {@code memory.max}) and {@code memory_total_bytes} (its
     * {@code /proc/meminfo} MemTotal), so a root without a limit is sized by its MemTotal.
     */
    public static Long capacity(long reserveBytes, Long... figures) {
      Long smallest = null;
      for (Long figure : figures) {
        if (figure != null && (smallest == null || figure < smallest)) {
          smallest = figure;
        }
      }
      return smallest == null ? null : Math.max(0L, smallest - Math.max(0L, reserveBytes));
    }

    Memory plus(SlotType type, long chargeBytes) {
      Map<SlotType, Long> next = new EnumMap<>(lastCharge);
      next.put(type, Math.max(0L, chargeBytes));
      return new Memory(
          capacityBytes, committedBytes + Math.max(0L, chargeBytes), next, builderBytes);
    }
  }

  /**
   * A queued lease. {@code pinnedTo} is its pin or its work volume's target, else null; {@code
   * chargeBytes} its {@code spec.limits.memory} ({@link DockerSizes#charge}); {@code avoiding} the
   * target it would rather not land on, else null — ignored when it is pinned.
   */
  public record Request(
      UUID leaseId,
      SlotType type,
      int priority,
      Instant requestedAt,
      UUID pinnedTo,
      long chargeBytes,
      UUID avoiding) {

    /** A lease that avoids no target. */
    public Request(
        UUID leaseId,
        SlotType type,
        int priority,
        Instant requestedAt,
        UUID pinnedTo,
        long chargeBytes) {
      this(leaseId, type, priority, requestedAt, pinnedTo, chargeBytes, null);
    }

    /** A lease charged nothing. */
    public Request(UUID leaseId, SlotType type, int priority, Instant requestedAt, UUID pinnedTo) {
      this(leaseId, type, priority, requestedAt, pinnedTo, 0);
    }
  }

  /** What became of one request: a target, or null and why it waits. */
  public record Decision(UUID leaseId, UUID targetId, String detail) {
    public boolean granted() {
      return targetId != null;
    }
  }

  /** The queue's order: priority desc, requested_at asc, lease id. */
  public static final Comparator<Request> QUEUE_ORDER =
      Comparator.comparingInt((Request r) -> -r.priority())
          .thenComparing(Request::requestedAt, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Request::leaseId);

  /**
   * One pass over {@code queue}, in {@link #QUEUE_ORDER}: a decision per request, in that order.
   */
  public static List<Decision> allocate(List<TargetSlots> targets, List<Request> queue) {
    Map<UUID, TargetSlots> state = new LinkedHashMap<>();
    for (TargetSlots target : targets) {
      state.put(target.id(), target);
    }
    List<Request> ordered = new ArrayList<>(queue);
    ordered.sort(QUEUE_ORDER);
    List<Decision> decisions = new ArrayList<>();
    for (Request request : ordered) {
      Decision decision = decide(state, request);
      if (decision.granted()) {
        state.computeIfPresent(decision.targetId(), (id, target) -> target.plus(request.type(), request.chargeBytes()));
      }
      decisions.add(decision);
    }
    return decisions;
  }

  private static Decision decide(Map<UUID, TargetSlots> state, Request request) {
    if (request.pinnedTo() != null) {
      TargetSlots pinned = state.get(request.pinnedTo());
      if (pinned == null) {
        return new Decision(request.leaseId(), null, "the target it is pinned to is gone");
      }
      String refusal = refusal(pinned, request.type(), request.chargeBytes());
      return refusal == null
          ? new Decision(request.leaseId(), pinned.id(), null)
          : new Decision(request.leaseId(), null, refusal);
    }
    if (request.avoiding() != null) {
      List<TargetSlots> others =
          state.values().stream().filter(t -> !t.id().equals(request.avoiding())).toList();
      Decision elsewhere = choose(others, request);
      if (elsewhere.granted()) {
        return elsewhere;
      }
    }
    return choose(state.values(), request);
  }

  /** The unpinned rule over {@code candidates}: the most free slots, then the name; else why not. */
  private static Decision choose(Iterable<TargetSlots> candidates, Request request) {
    TargetSlots best = null;
    TargetSlots signedOut = null;
    boolean memoryShort = false;
    for (TargetSlots target : candidates) {
      if (!target.available() || !slotsFit(target, request.type())) {
        continue;
      }
      if (!memoryFits(target, request.type(), request.chargeBytes())) {
        memoryShort = true;
        continue;
      }
      if (request.type().needsSignIn() && !target.signedIn()) {
        if (signedOut == null || better(target, signedOut)) {
          signedOut = target;
        }
        continue;
      }
      if (best == null || better(target, best)) {
        best = target;
      }
    }
    if (best != null) {
      return new Decision(request.leaseId(), best.id(), null);
    }
    if (signedOut != null) {
      return new Decision(request.leaseId(), null, notSignedIn(signedOut.name()));
    }
    if (memoryShort) {
      return new Decision(
          request.leaseId(),
          null,
          "no target has memory for " + sized(request.type(), request.chargeBytes()));
    }
    return new Decision(
        request.leaseId(), null, "no target has a free " + request.type().name() + " slot");
  }

  /**
   * Why {@code target} would not take a lease of {@code type} charged {@code chargeBytes} now; null
   * when it would.
   */
  static String refusal(TargetSlots target, SlotType type, long chargeBytes) {
    if (target.quarantined()) {
      return "target " + target.name() + " is quarantined";
    }
    if (!target.connected()) {
      return "target " + target.name() + " is not connected";
    }
    if (atMaximum(target.maximums(), target.used(), type)) {
      return "target "
          + target.name()
          + " runs its maximum of "
          + target.maximums().get(type)
          + " "
          + type.name()
          + " lease(s)";
    }
    if (!slotsFit(target, type)) {
      return "target " + target.name() + " has no free " + type.name() + " slot";
    }
    if (!memoryFits(target, type, chargeBytes)) {
      return "target " + target.name() + " has no memory for " + sized(type, chargeBytes);
    }
    if (type.needsSignIn() && !target.signedIn()) {
      return notSignedIn(target.name());
    }
    return null;
  }

  /** The queued lease's detail on a signed-out target, naming the command that fixes it. */
  public static String notSignedIn(String targetName) {
    return "target "
        + targetName
        + " is not signed in: qits targets sign-in --target "
        + targetName;
  }

  /** {@code a 6g WORKSPACE lease}, or {@code a WORKSPACE lease} when it is charged nothing. */
  private static String sized(SlotType type, long chargeBytes) {
    return chargeBytes > 0
        ? "a " + DockerSizes.format(chargeBytes) + " " + type.name() + " lease"
        : "a " + type.name() + " lease";
  }

  /** The whole rule, see the class javadoc, for a lease charged {@code chargeBytes}. */
  public static boolean fits(TargetSlots target, SlotType type, long chargeBytes) {
    return slotsFit(target, type) && memoryFits(target, type, chargeBytes);
  }

  /** The whole rule for a lease charged nothing. */
  public static boolean fits(TargetSlots target, SlotType type) {
    return fits(target, type, 0);
  }

  /** The slot rule alone. */
  public static boolean slotsFit(TargetSlots target, SlotType type) {
    return fits(target.total(), target.reservations(), target.maximums(), target.used(), type);
  }

  /**
   * The memory conjunct (qits-2362): {@code C} unknown, or {@code M + Σ_{u≠t} max(0, r[u] − n[u]) ×
   * c[u] + [takes CI] × B + c ≤ C}, the reserved term dropped when the reservations are ignored, in
   * slots or in memory ({@link #reservationsExceedMemory}); the builder {@code B} charged when
   * {@link #takesCi} (qits-2503).
   */
  public static boolean memoryFits(TargetSlots target, SlotType type, long chargeBytes) {
    Memory memory = target.memory();
    if (memory.capacityBytes() == null) {
      return true;
    }
    long needed = memory.committedBytes() + Math.max(0L, chargeBytes);
    if (takesCi(target, type)) {
      needed += memory.builderBytes();
    }
    if (!reservationsExceedTotal(target.total(), target.reservations())
        && !reservationsExceedMemory(target)) {
      for (SlotType u : SlotType.values()) {
        if (u != type) {
          int reserved = target.reservations().getOrDefault(u, 0);
          int unused = Math.max(0, reserved - target.used().getOrDefault(u, 0));
          needed += unused * memory.lastCharge().getOrDefault(u, 0L);
        }
      }
    }
    return needed <= memory.capacityBytes();
  }

  /**
   * Whether {@code target}, granted a lease of {@code type}, runs the shared buildkitd and so is
   * charged its memory (qits-2503): CI is reserved there ({@code r[CI] > 0}), a CI lease occupies it
   * ({@code n[CI] > 0}: GRANTED, RUNNING or ENDING), or the candidate is CI. The reservation counts
   * even when ignored in slots or memory: the runner starts the builder for a target that may take
   * CI, whether or not it runs a CI lease right now.
   */
  static boolean takesCi(TargetSlots target, SlotType type) {
    return type == SlotType.CI
        || target.reservations().getOrDefault(SlotType.CI, 0) > 0
        || target.used().getOrDefault(SlotType.CI, 0) > 0;
  }

  /**
   * {@code Σ_u r[u] × c[u] > C}: what the reservations would hold in memory with every type at zero
   * use is more than the target has, so in memory they are ignored — the memory twin of {@link
   * #reservationsExceedTotal}. They still hold as slots. False when the capacity is unknown. The
   * builder (qits-2503) is not in this sum: it is no reservation but fixed overhead, charged
   * whether the reservations are honoured or ignored.
   */
  public static boolean reservationsExceedMemory(TargetSlots target) {
    Long capacity = target.memory().capacityBytes();
    if (capacity == null) {
      return false;
    }
    long held = 0;
    for (SlotType u : SlotType.values()) {
      long price = target.memory().lastCharge().getOrDefault(u, 0L);
      held += target.reservations().getOrDefault(u, 0) * price;
    }
    return held > capacity;
  }

  /** The rule over bare numbers, with no maximums. */
  public static boolean fits(
      int total, Map<SlotType, Integer> reservations, Map<SlotType, Integer> used, SlotType type) {
    return fits(total, reservations, null, used, type);
  }

  /** The rule over bare numbers; a null map or a null value in {@code maximums} is no ceiling. */
  public static boolean fits(
      int total,
      Map<SlotType, Integer> reservations,
      Map<SlotType, Integer> maximums,
      Map<SlotType, Integer> used,
      SlotType type) {
    if (atMaximum(maximums, used, type)) {
      return false;
    }
    int usedTotal = 0;
    for (SlotType u : SlotType.values()) {
      usedTotal += used.getOrDefault(u, 0);
    }
    if (usedTotal >= total) {
      return false;
    }
    if (reservationsExceedTotal(total, reservations)) {
      return true;
    }
    int reserved = 0;
    for (SlotType u : SlotType.values()) {
      if (u != type) {
        reserved += Math.max(0, reservations.getOrDefault(u, 0) - used.getOrDefault(u, 0));
      }
    }
    return usedTotal + 1 + reserved <= total;
  }

  /**
   * Whether a target could ever be granted a lease of {@code type}: the rule on an empty target,
   * {@code T − Σ_{u≠t} r[u] > 0} (and {@code T > 0} alone when {@code Σ r > T}). What decides which
   * warm images its estate carries (qits-1692): a ci-only target is never asked to pull the
   * workspace image.
   */
  public static boolean canEverRun(int total, Map<SlotType, Integer> reservations, SlotType type) {
    return fits(total, reservations, Map.of(), type);
  }

  /** {@code Σ r > T}: the reservations are ignored, and the {@code slots} check fails. */
  public static boolean reservationsExceedTotal(int total, Map<SlotType, Integer> reservations) {
    int sum = 0;
    for (SlotType u : SlotType.values()) {
      sum += reservations.getOrDefault(u, 0);
    }
    return sum > total;
  }

  /** {@code m[t]} is set and {@code n[t] ≥ m[t]}: the type may take no more on this target. */
  static boolean atMaximum(
      Map<SlotType, Integer> maximums, Map<SlotType, Integer> used, SlotType type) {
    Integer maximum = maximums == null ? null : maximums.get(type);
    return maximum != null && used.getOrDefault(type, 0) >= maximum;
  }

  /** More free slots first, then the name. */
  private static boolean better(TargetSlots candidate, TargetSlots incumbent) {
    if (candidate.free() != incumbent.free()) {
      return candidate.free() > incumbent.free();
    }
    return candidate.name().compareTo(incumbent.name()) < 0;
  }

  /** Every type as a key; a null value is no ceiling, a negative one is 0. */
  private static Map<SlotType, Integer> ceilings(Map<SlotType, Integer> given) {
    Map<SlotType, Integer> ceilings = new EnumMap<>(SlotType.class);
    for (SlotType type : SlotType.values()) {
      Integer value = given == null ? null : given.get(type);
      ceilings.put(type, value == null ? null : Math.max(0, value));
    }
    return ceilings;
  }

  private static Map<SlotType, Integer> counts(Map<SlotType, Integer> given) {
    Map<SlotType, Integer> counts = new EnumMap<>(SlotType.class);
    for (SlotType type : SlotType.values()) {
      Integer value = given == null ? null : given.get(type);
      counts.put(type, value == null ? 0 : Math.max(0, value));
    }
    return counts;
  }
}
