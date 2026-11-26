package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.entity.SlotType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * ({@code Σ r > T}). A target that reports no memory is held to the slot rule alone. A lease the
 * slots of some target would take but whose memory none can hold waits with {@code no target has
 * memory for a 6g WORKSPACE lease}.
 *
 * <p><b>The builder is charged on a target that takes CI</b> (qits-2503). An image build a CI lease
 * sends to the target's shared buildkitd runs in the builder's cgroup, outside the lease's own
 * {@code limits.memory}, and the runner caps that builder at {@code BUILDER_CONCURRENT_BUILDS × 8
 * GiB} and reports the cap as {@code builder_memory_bytes} ({@code B}, 0 when unreported — an older
 * runner). It is charged once per target, and only when the target takes CI: {@code r[CI] > 0}, or
 * {@code n[CI] > 0}, or the candidate is a CI lease. A workspace-, desk- or agent-only target never starts
 * the builder and is not charged it.
 *
 * <p><b>Memory honours the reservations in priority order, CI first</b> (qits-2515). Reservations
 * the memory cannot meet are not dropped but honoured as far as it reaches: with {@code A = C −
 * [takes CI] × B}, for {@code u} in CI, WORKSPACE, DESK, AGENT, {@code h[u] = min(r[u], ⌊A / c[u]⌋)}
 * ({@code r[u]} when {@code c[u] = 0}) and {@code A −= h[u] × c[u]} ({@link #honouredReservations}).
 * So the memory conjunct is
 *
 * <pre>
 *   C unknown  or  M + Σ_{u ≠ t} max(0, h[u] − n[u]) × c[u] + [takes CI] × B + c ≤ C
 * </pre>
 *
 * When they fit ({@code Σ_u r[u] × c[u] ≤ A}) {@code h = r} and nothing changes. Dropping them
 * whole, as qits-2362 did once they did not fit, let a workspace take ws-proof's only lease-sized
 * memory and starved CI for hours: ws-proof (14 GiB, an 8 GiB builder, ci 2 and workspace 1 at 6g)
 * honours ci 1 and workspace 0, so it always keeps one 6g CI lease ({@code 0 + 8 + 6 = 14}) and takes
 * no workspace ({@code 0 + 6 + 8 + 6 = 20 > 14}); at 32 GiB it honours ci 2 and workspace 1, and a
 * workspace fits while {@code M + 12 + 8 + 6 ≤ 32}.
 *
 * <p><b>Image-building CI leases are capped at the builder's width plus one</b> (qits-2568). A CI
 * lease whose run sends image builds to the target's shared buildkitd ({@link Request#builds()},
 * qits-ci's {@code build: true}) passes qits-ci-daemon's target-wide build gate (qits-1807) only
 * {@code W} at a time, {@code W} the gate's width as the runner reports it ({@code
 * builder_concurrent_builds}). A lease past that waits at the gate holding its whole charge while
 * its step's clock is stopped: on 2026-11-22 four release runs held the workstation's memory for half
 * an hour, one building, and runs of seconds waited behind them. So with {@code b} the target's
 * GRANTED, RUNNING and ENDING leases that build, a lease that builds also needs
 *
 * <pre>
 *   W unknown  or  b &lt; W + {@value #BUILDER_HEADROOM}
 * </pre>
 *
 * — the one past {@code W} keeps the builder fed, its build already queued when one ends. A lease
 * that builds nothing is never held by it, so the memory the capped ones would have held goes to
 * them. A capped lease waits with {@code target workstation runs 2 image-building CI lease(s) for a
 * builder of 1 build(s) at a time} when pinned, else {@value #NO_BUILDER_ROOM}.
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
 * granted anything. A WORKSPACE, DESK or AGENT lease is granted only on a target that is signed in (either
 * harness PRESENT): an agent slot on a signed-out target only produces refusals. CI is never gated.
 * A lease that waits is answered a {@link Decision#detail()} saying why, in the words an owner shows.
 */
public final class SlotAllocator {

  private SlotAllocator() {}

  /**
   * How many image-building leases a target holds beyond its builder's width (qits-2568): one, so
   * the next build is already queued at the gate when one ends.
   */
  public static final int BUILDER_HEADROOM = 1;

  /** The detail of an unpinned lease that builds when every target that would take it is full. */
  public static final String NO_BUILDER_ROOM =
      "no target has builder room for an image-building CI lease";

  /**
   * A target's builder as this pass sees it (qits-2568): {@code width}, how many image builds its
   * gate lets onto its buildkitd at once, null when the runner reports none (then nothing is
   * capped); {@code building}, its GRANTED, RUNNING and ENDING leases that build.
   */
  public record Builder(Integer width, int building) {

    /** No width reported: nothing capped. */
    public static final Builder UNKNOWN = new Builder(null, 0);

    public Builder {
      width = width == null ? null : Math.max(0, width);
      building = Math.max(0, building);
    }

    /** {@code W} known and {@code b ≥ W + 1}: no more leases that build. */
    public boolean full() {
      return width != null && building >= width + BUILDER_HEADROOM;
    }

    Builder plusOne() {
      return new Builder(width, building + 1);
    }
  }

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
      Memory memory,
      Builder builder) {

    public TargetSlots {
      reservations = counts(reservations);
      maximums = ceilings(maximums);
      used = counts(used);
      memory = memory == null ? Memory.UNKNOWN : memory;
      builder = builder == null ? Builder.UNKNOWN : builder;
    }

    /** A target whose builder reports no width (qits-2568): no lease that builds is capped. */
    public TargetSlots(
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
      this(
          id, name, total, reservations, maximums, used, connected, quarantined, signedIn, memory,
          null);
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
          id, name, total, reservations, maximums, used, connected, quarantined, signedIn,
          (Memory) null);
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
      this(
          id, name, total, reservations, null, used, connected, quarantined, signedIn, (Memory) null);
    }

    /** The same target with {@code memory}. */
    public TargetSlots withMemory(Memory memory) {
      return new TargetSlots(
          id, name, total, reservations, maximums, used, connected, quarantined, signedIn, memory,
          builder);
    }

    /** The same target with {@code builder} (qits-2568). */
    public TargetSlots withBuilder(Builder builder) {
      return new TargetSlots(
          id, name, total, reservations, maximums, used, connected, quarantined, signedIn, memory,
          builder);
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
      return plus(type, chargeBytes, false);
    }

    /** One more lease of {@code type}; one more that builds when {@code builds} (qits-2568). */
    TargetSlots plus(SlotType type, long chargeBytes, boolean builds) {
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
          memory.plus(type, chargeBytes),
          builds ? builder.plusOne() : builder);
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
      UUID avoiding,
      boolean builds) {

    /** A lease that builds no image (qits-2568). */
    public Request(
        UUID leaseId,
        SlotType type,
        int priority,
        Instant requestedAt,
        UUID pinnedTo,
        long chargeBytes,
        UUID avoiding) {
      this(leaseId, type, priority, requestedAt, pinnedTo, chargeBytes, avoiding, false);
    }

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
        state.computeIfPresent(
            decision.targetId(),
            (id, target) -> target.plus(request.type(), request.chargeBytes(), request.builds()));
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
      String refusal = refusal(pinned, request.type(), request.chargeBytes(), request.builds());
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
    boolean builderFull = false;
    for (TargetSlots target : candidates) {
      if (!target.available() || !slotsFit(target, request.type())) {
        continue;
      }
      if (request.builds() && target.builder().full()) {
        builderFull = true;
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
    if (builderFull) {
      return new Decision(request.leaseId(), null, NO_BUILDER_ROOM);
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
    return refusal(target, type, chargeBytes, false);
  }

  /** {@link #refusal(TargetSlots, SlotType, long)} for a lease that builds when {@code builds}. */
  static String refusal(TargetSlots target, SlotType type, long chargeBytes, boolean builds) {
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
    if (builds && target.builder().full()) {
      return builderFull(target);
    }
    if (!memoryFits(target, type, chargeBytes)) {
      return "target " + target.name() + " has no memory for " + sized(type, chargeBytes);
    }
    if (type.needsSignIn() && !target.signedIn()) {
      return notSignedIn(target.name());
    }
    return null;
  }

  /** The detail of a lease that builds on a target whose builder is full (qits-2568). */
  static String builderFull(TargetSlots target) {
    return "target "
        + target.name()
        + " runs "
        + target.builder().building()
        + " image-building CI lease(s) for a builder of "
        + target.builder().width()
        + " build(s) at a time";
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
   * The memory conjunct (qits-2362): {@code C} unknown, or {@code M + Σ_{u≠t} max(0, h[u] − n[u]) ×
   * c[u] + [takes CI] × B + c ≤ C}, with {@code h} the reservations memory honours ({@link
   * #honouredReservations}, qits-2515) and the reserved term dropped when the reservations are
   * ignored in slots ({@code Σ r > T}); the builder {@code B} charged when {@link #takesCi}
   * (qits-2503).
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
    Map<SlotType, Integer> honoured = honouredReservations(target, type);
    for (SlotType u : SlotType.values()) {
      if (u != type) {
        int unused = Math.max(0, honoured.get(u) - target.used().getOrDefault(u, 0));
        needed += unused * memory.lastCharge().getOrDefault(u, 0L);
      }
    }
    return needed <= memory.capacityBytes();
  }

  /**
   * {@code h[u]}, the reservations {@code target}'s memory honours when it is asked for a lease of
   * {@code type} (qits-2515): with {@code A = C − [takes CI] × B}, in {@link SlotType} order — CI
   * first — {@code h[u] = r[u]} when {@code c[u] = 0}, else {@code min(r[u], ⌊A / c[u]⌋)}, and
   * {@code A} drops by {@code h[u] × c[u]}. Reservations the memory can meet ({@code Σ_u r[u] × c[u]
   * ≤ A}) are honoured whole; ones it cannot are honoured in priority order rather than dropped, so
   * ws-proof (14 GiB, an 8 GiB builder, ci 2 and workspace 1 at 6g) honours ci 1 and workspace 0 and
   * always keeps one 6g CI lease's memory free. Every {@code h[u]} is 0 when the reservations are
   * ignored in slots ({@code Σ r > T}); {@code h = r} when the capacity is unknown.
   */
  public static Map<SlotType, Integer> honouredReservations(TargetSlots target, SlotType type) {
    Map<SlotType, Integer> honoured = new EnumMap<>(SlotType.class);
    Memory memory = target.memory();
    boolean ignored = reservationsExceedTotal(target.total(), target.reservations());
    long available =
        memory.capacityBytes() == null
            ? 0
            : Math.max(
                0L,
                memory.capacityBytes() - (takesCi(target, type) ? memory.builderBytes() : 0L));
    for (SlotType u : SlotType.values()) {
      int reserved = target.reservations().getOrDefault(u, 0);
      long price = memory.lastCharge().getOrDefault(u, 0L);
      int h;
      if (ignored) {
        h = 0;
      } else if (memory.capacityBytes() == null || price == 0) {
        h = reserved;
      } else {
        h = (int) Math.min(reserved, available / price);
      }
      honoured.put(u, h);
      available = Math.max(0L, available - h * price);
    }
    return honoured;
  }

  /**
   * Why {@code target}'s slot settings can never be met by its memory (qits-2581), or null when they
   * can: the sentence the patch door answers 400 {@code SLOTS_EXCEED_MEMORY} with. The figures are
   * placement's own, on the target with nothing used: {@code C}, {@code B} and {@code c[u]} from
   * {@link TargetSlots#memory()}.
   *
   * <ul>
   *   <li>{@code C} unknown: null — a target that reports no memory is held to its slots alone.
   *   <li>A maximum {@code m[t]} that can never be reached even alone: with {@code c[t] > 0}, {@code
   *       m[t] > ⌊(C − [t = CI or r[CI] > 0] × B) / c[t]⌋}. An unset maximum is not checked.
   *   <li>Reservations that cannot all be honoured at once: {@code h[u] < r[u]} for some {@code u},
   *       {@code h} as {@link #honouredReservations} computes it on the empty target, the builder
   *       charged iff {@code r[CI] > 0}. Skipped when {@code Σ r > T}, which the slots already ignore.
   * </ul>
   *
   * A type never granted there ({@code c[u] = 0}) is priced at nothing, as placement prices it, so it
   * is never checked.
   */
  public static String settingsRefusal(TargetSlots target) {
    Memory memory = target.memory();
    Long capacity = memory.capacityBytes();
    if (capacity == null) {
      return null;
    }
    boolean reservesCi = target.reservations().getOrDefault(SlotType.CI, 0) > 0;
    for (SlotType type : SlotType.values()) {
      Integer maximum = target.maximums().get(type);
      long price = memory.lastCharge().getOrDefault(type, 0L);
      if (maximum == null || price == 0) {
        continue;
      }
      boolean builder = (type == SlotType.CI || reservesCi) && memory.builderBytes() > 0;
      long available = Math.max(0L, capacity - (builder ? memory.builderBytes() : 0L));
      long limit = available / price;
      if (maximum > limit) {
        return "target "
            + target.name()
            + " holds at most "
            + limit
            + " "
            + type.name()
            + " lease(s) of "
            + gib(price)
            + " "
            + capacityOf(capacity, builder ? memory.builderBytes() : 0L)
            + ", not "
            + maximum;
      }
    }
    if (reservationsExceedTotal(target.total(), target.reservations())) {
      return null;
    }
    TargetSlots empty =
        new TargetSlots(
            target.id(),
            target.name(),
            target.total(),
            target.reservations(),
            target.maximums(),
            null,
            true,
            false,
            true,
            memory);
    Map<SlotType, Integer> honoured =
        honouredReservations(empty, reservesCi ? SlotType.CI : SlotType.WORKSPACE);
    List<SlotType> counted = new ArrayList<>();
    boolean shortfall = false;
    for (SlotType u : SlotType.values()) {
      if (target.reservations().getOrDefault(u, 0) > 0
          && memory.lastCharge().getOrDefault(u, 0L) > 0) {
        counted.add(u);
        shortfall |= honoured.get(u) < target.reservations().get(u);
      }
    }
    if (!shortfall) {
      return null;
    }
    boolean onePrice =
        counted.stream().map(u -> memory.lastCharge().get(u)).distinct().count() == 1;
    List<String> can = new ArrayList<>();
    List<String> asked = new ArrayList<>();
    for (SlotType u : counted) {
      String each = onePrice ? "" : " of " + gib(memory.lastCharge().get(u));
      can.add(honoured.get(u) + " " + u.name() + each);
      asked.add(target.reservations().get(u) + " " + u.name());
    }
    boolean builder = reservesCi && memory.builderBytes() > 0;
    return "target "
        + target.name()
        + " can honour at most "
        + joined(can)
        + " reserved lease(s)"
        + (onePrice ? " of " + gib(memory.lastCharge().get(counted.get(0))) : "")
        + " "
        + capacityOf(capacity, builder ? memory.builderBytes() : 0L)
        + ", not "
        + joined(asked);
  }

  private static final double GIB = 1024d * 1024 * 1024;

  /** {@code 29.2 GiB}: one decimal, a trailing {@code .0} dropped. */
  static String gib(long bytes) {
    String text = String.format(Locale.ROOT, "%.1f", bytes / GIB);
    return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + " GiB";
  }

  /** {@code (29.2 GiB less the 8 GiB builder)}, or {@code (29.2 GiB)} with no builder charged. */
  private static String capacityOf(long capacity, long builder) {
    return builder > 0
        ? "(" + gib(capacity) + " less the " + gib(builder) + " builder)"
        : "(" + gib(capacity) + ")";
  }

  /** {@code a}, {@code a and b}, {@code a, b and c}. */
  private static String joined(List<String> parts) {
    if (parts.size() <= 1) {
      return String.join("", parts);
    }
    return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
  }

  /**
   * Whether {@code target}, granted a lease of {@code type}, runs the shared buildkitd and so is
   * charged its memory (qits-2503): CI is reserved there ({@code r[CI] > 0}), a CI lease occupies it
   * ({@code n[CI] > 0}: GRANTED, RUNNING or ENDING), or the candidate is CI. The reservation counts
   * even when ignored in slots or only partly honoured in memory: the runner starts the builder for a
   * target that may take CI, whether or not it runs a CI lease right now.
   */
  static boolean takesCi(TargetSlots target, SlotType type) {
    return type == SlotType.CI
        || target.reservations().getOrDefault(SlotType.CI, 0) > 0
        || target.used().getOrDefault(SlotType.CI, 0) > 0;
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
