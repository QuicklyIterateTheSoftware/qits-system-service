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
 * <p><b>The rule.</b> For a target with total {@code T}, reservations {@code r[u]} and used counts
 * {@code n[u]} (GRANTED, RUNNING or ENDING), a lease of type {@code t} fits when
 *
 * <pre>
 *   n_total &lt; T  and  n_total + 1 + Σ_{u ≠ t} max(0, r[u] − n[u]) ≤ T
 * </pre>
 *
 * — what is reserved for the other types and not yet used by them stays free for them. A
 * reservation is not a limit: a type may use more than its reservation while shared (unreserved)
 * slots are free, so ci 3 used over 1 reserved is valid when 2 shared slots were free. When {@code
 * Σ r > T} (the runner reported a smaller total than the reservations were set against) the
 * reservations are ignored and only {@code n_total < T} holds; the {@code slots} health check
 * reports that case.
 *
 * <p><b>The order.</b> One queue across types, {@code priority desc, requested_at asc} (the lease id
 * breaks a tie, so a pass is deterministic). Each lease is considered on its own: one that cannot be
 * placed does not hold back the ones behind it. A lease pinned to a target — by {@code
 * pin_target_id} or by its work volume — is considered on that target only; any other goes to the
 * target with the most free slots, ties broken by name. Every grant counts at once, so one pass
 * never over-fills a target.
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
      Map<SlotType, Integer> used,
      boolean connected,
      boolean quarantined,
      boolean signedIn) {

    public TargetSlots {
      reservations = counts(reservations);
      used = counts(used);
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

    TargetSlots plus(SlotType type) {
      Map<SlotType, Integer> next = new EnumMap<>(used);
      next.merge(type, 1, Integer::sum);
      return new TargetSlots(id, name, total, reservations, next, connected, quarantined, signedIn);
    }
  }

  /** A queued lease. {@code pinnedTo} is its pin or its work volume's target, else null. */
  public record Request(
      UUID leaseId, SlotType type, int priority, Instant requestedAt, UUID pinnedTo) {}

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
        state.computeIfPresent(decision.targetId(), (id, target) -> target.plus(request.type()));
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
      String refusal = refusal(pinned, request.type());
      return refusal == null
          ? new Decision(request.leaseId(), pinned.id(), null)
          : new Decision(request.leaseId(), null, refusal);
    }
    TargetSlots best = null;
    TargetSlots signedOut = null;
    for (TargetSlots target : state.values()) {
      if (!target.available() || !fits(target, request.type())) {
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
    return new Decision(
        request.leaseId(), null, "no target has a free " + request.type().name() + " slot");
  }

  /** Why {@code target} would not take a lease of {@code type} now; null when it would. */
  static String refusal(TargetSlots target, SlotType type) {
    if (target.quarantined()) {
      return "target " + target.name() + " is quarantined";
    }
    if (!target.connected()) {
      return "target " + target.name() + " is not connected";
    }
    if (!fits(target, type)) {
      return "target " + target.name() + " has no free " + type.name() + " slot";
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

  /** The rule, see the class javadoc. */
  public static boolean fits(TargetSlots target, SlotType type) {
    return fits(target.total(), target.reservations(), target.used(), type);
  }

  /** The rule over bare numbers. */
  public static boolean fits(
      int total, Map<SlotType, Integer> reservations, Map<SlotType, Integer> used, SlotType type) {
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

  /** {@code Σ r > T}: the reservations are ignored, and the {@code slots} check fails. */
  public static boolean reservationsExceedTotal(int total, Map<SlotType, Integer> reservations) {
    int sum = 0;
    for (SlotType u : SlotType.values()) {
      sum += reservations.getOrDefault(u, 0);
    }
    return sum > total;
  }

  /** More free slots first, then the name. */
  private static boolean better(TargetSlots candidate, TargetSlots incumbent) {
    if (candidate.free() != incumbent.free()) {
      return candidate.free() > incumbent.free();
    }
    return candidate.name().compareTo(incumbent.name()) < 0;
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
