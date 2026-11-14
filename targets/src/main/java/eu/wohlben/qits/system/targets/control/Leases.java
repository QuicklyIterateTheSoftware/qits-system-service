package eu.wohlben.qits.system.targets.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * The lease rows: an owner's requests, and the runner's reports of what became of them. Every
 * method is its own {@code requiringNew} transaction; every state change is announced ({@link
 * TargetEvents#leaseChanged}) after it committed, and every change that can move a grant asks
 * {@link TargetAllocation} for a pass.
 *
 * <p><b>One writer at a time per lease row.</b> Every transaction here that changes a lease reads
 * it {@code SELECT … FOR UPDATE} first — an owner's end or roll by id, a runner's inventory every
 * lease of its target in id order — and {@link TargetAllocation} locks the queue the same way, so two
 * writers never both read a row and the later commit silently undo the earlier's transition: the
 * second waits, then decides on what the first wrote (the rows carry no version column, so without
 * the lock the last writer would win). A row a concurrent writer moved out of the query's states is
 * skipped once the lock is granted, which is postgres' read-committed re-check.
 *
 * <p><b>The owner is the caller's application</b>, resolved by {@link OwnerIdentity} before any of
 * this is reached; a lease of another owner is 403 {@link TargetRefusal#NOT_YOUR_LEASE} to every
 * write. The one door past it is an operator's end ({@link #endByOperator}), which the controller
 * takes for a caller holding an operator role, and which leaves a {@code target_audit} row.
 */
@ApplicationScoped
public class Leases {

  private static final Logger LOG = Logger.getLogger(Leases.class);

  /** A work key: it becomes part of a docker volume name. {@code ws-<rowId>}, {@code desk-<id>}. */
  public static final Pattern WORK_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

  /** A docker container or volume name, as an {@code adopt} member may carry it. */
  static final Pattern DOCKER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

  public static final int OWNER_REF_MAX = 255;

  @Inject TargetsConfig config;

  @Inject TargetEvents events;

  @Inject TargetAllocation allocation;

  @Inject TargetAuditLog audit;

  @Inject Targets targets;

  @Inject TargetPresence presence;

  /** What an owner asks for. {@code spec} is checked by {@link LeaseSpecs#require}. */
  public record NewLease(
      SlotType slotType,
      String ownerRef,
      UUID projectId,
      String workKey,
      int priority,
      JsonNode spec,
      UUID pinTargetId,
      Map<String, String> adopt) {}

  /** A listing's filter; null members filter nothing. {@code ownerApp} null is every owner. */
  public record Filter(LeaseState state, SlotType slotType, UUID targetId, String ownerApp) {}

  // --- the owner's doors ------------------------------------------------------------------------

  /**
   * A QUEUED lease for {@code ownerApp}.
   *
   * @throws TargetRefusal 400 for a malformed request, 409 {@link TargetRefusal#LEASE_EXISTS} while
   *     the owner holds a live lease for the same {@code ownerRef}
   */
  public Lease create(String ownerApp, NewLease request) {
    if (request.slotType() == null) {
      throw TargetRefusal.badRequest("A lease needs a slotType: CI, WORKSPACE or DESK");
    }
    if (request.ownerRef() == null
        || request.ownerRef().isBlank()
        || request.ownerRef().length() > OWNER_REF_MAX) {
      throw TargetRefusal.badRequest(
          "A lease needs an ownerRef of at most " + OWNER_REF_MAX + " characters");
    }
    if (request.workKey() != null && !WORK_KEY.matcher(request.workKey()).matches()) {
      throw TargetRefusal.badRequest(
          "A work key is a letter or digit, then up to 127 letters, digits, '.', '_' or '-'");
    }
    if (request.slotType() == SlotType.CI && request.workKey() != null) {
      throw TargetRefusal.badRequest("A CI lease keeps no work volume, so it takes no workKey");
    }
    Map<String, String> adopt = requireAdopt(request.adopt(), request.workKey());
    ObjectNode spec = LeaseSpecs.require(request.spec(), request.slotType(), config.slotImages());
    Lease created;
    try {
      created =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    if (Lease.count(
                            "ownerApp = ?1 and ownerRef = ?2 and state <> ?3",
                            ownerApp,
                            request.ownerRef(),
                            LeaseState.ENDED)
                        > 0) {
                      throw leaseExists(ownerApp, request.ownerRef());
                    }
                    Lease lease = new Lease();
                    lease.id = UUID.randomUUID();
                    lease.slotType = request.slotType();
                    lease.ownerApp = ownerApp;
                    lease.ownerRef = request.ownerRef();
                    lease.projectId = request.projectId();
                    lease.workKey = request.workKey();
                    lease.pinTargetId = request.pinTargetId();
                    lease.priority = request.priority();
                    lease.state = LeaseState.QUEUED;
                    lease.spec = LeaseSpecs.text(spec);
                    lease.specHash = LeaseSpecs.hash(spec);
                    lease.adopt = adopt == null ? null : adoptText(adopt);
                    lease.requestedAt = Instant.now();
                    lease.persistAndFlush();
                    return lease;
                  });
    } catch (PersistenceException raced) {
      throw leaseExists(ownerApp, request.ownerRef());
    }
    LOG.infof(
        "%s asked for a %s lease %s (%s)",
        ownerApp, created.slotType, created.id, created.ownerRef);
    events.leaseChanged(created);
    allocation.requestPass();
    return created;
  }

  public List<Lease> list(Filter filter) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              StringBuilder query = new StringBuilder("1 = 1");
              Map<String, Object> params = new HashMap<>();
              if (filter.state() != null) {
                query.append(" and state = :state");
                params.put("state", filter.state());
              }
              if (filter.slotType() != null) {
                query.append(" and slotType = :slotType");
                params.put("slotType", filter.slotType());
              }
              if (filter.targetId() != null) {
                query.append(" and targetId = :targetId");
                params.put("targetId", filter.targetId());
              }
              if (filter.ownerApp() != null) {
                query.append(" and ownerApp = :ownerApp");
                params.put("ownerApp", filter.ownerApp());
              }
              query.append(" order by requestedAt, id");
              return Lease.<Lease>find(query.toString(), params).list();
            });
  }

  /** The lease, or null. */
  public Lease find(UUID id) {
    return id == null ? null : QuarkusTransaction.requiringNew().call(() -> Lease.findById(id));
  }

  /** @throws TargetRefusal 404 */
  public Lease get(UUID id) {
    Lease lease = id == null ? null : QuarkusTransaction.requiringNew().call(() -> Lease.findById(id));
    if (lease == null) {
      throw TargetRefusal.notFound("No lease " + id);
    }
    return lease;
  }

  /** A roll: the new spec, a new hash, and the runner recreates the container. */
  public Lease replaceSpec(UUID id, String ownerApp, JsonNode given) {
    Lease current = owned(id, ownerApp);
    ObjectNode spec = LeaseSpecs.require(given, current.slotType, config.slotImages());
    Lease rolled =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Lease lease = Lease.findById(id, LockModeType.PESSIMISTIC_WRITE);
                  if (lease == null) {
                    throw TargetRefusal.notFound("No lease " + id);
                  }
                  if (lease.state == LeaseState.ENDING || lease.state == LeaseState.ENDED) {
                    throw TargetRefusal.conflict(
                        "LEASE_ENDING", "Lease " + id + " is " + lease.state + "; it takes no spec");
                  }
                  lease.spec = LeaseSpecs.text(spec);
                  lease.specHash = LeaseSpecs.hash(spec);
                  return lease;
                });
    allocation.estateChanged(rolled.targetId);
    // A rolled image may be the newest WORKSPACE or DESK one, which is the login probe's.
    allocation.requestPass();
    return rolled;
  }

  /** {@link #end(UUID, String, String)} without a reason. */
  public Lease end(UUID id, String ownerApp) {
    return end(id, ownerApp, null).lease();
  }

  /**
   * The reason qits-ci ends a lease with when its run's step was {@code NEVER_STARTED}: the lease
   * ran and its ci daemon never dialled back within qits-ci's register window (qits-1809).
   */
  public static final String NEVER_DIALLED_BACK = "never-dialled-back";

  /**
   * An owner's end, and the quarantine its dial-back streak began or renewed (null when it touched
   * none).
   */
  public record OwnerEnd(Lease lease, Targets.Quarantine quarantine) {}

  /**
   * The owner is done with it: a QUEUED lease (or one with no target) is ENDED at once, and so is an
   * EXITED or FAILED one, whose container has stopped and whose slot is free already — the estate no
   * longer lists it, so the runner removes the container. A GRANTED or RUNNING lease is ENDING until
   * the runner reports its container gone — unless its target holds no connection, when no runner
   * is there to report it and it is ENDED at once (qits-2227). Ending an ENDING or ENDED lease is a
   * no-op. A {@code reason} lands in the detail of a lease it moves.
   *
   * <p><b>The dial-back streak</b> (qits-1809). The end of a CI lease that was RUNNING tells whether
   * its ci daemon dialled back: {@link #NEVER_DIALLED_BACK} counts one more failure on its target,
   * any other reason or none resets the count, since a daemon that registered proves the link
   * works. At {@code qits.system.targets.dialback.threshold} in a row the target is quarantined
   * ({@link Targets#quarantineFor}, which only renews the reason of a standing one); the {@code
   * dialback} health check then holds it there for the cool-off. Every other end leaves the count
   * alone: a lease that never ran says nothing about the link.
   *
   * <p>The streak has a second cause (qits-525): a GRANTED CI lease its runner reports FAILED — it
   * could not start it at all, an image it could not pull, a container docker refused — counts one
   * more failure too, from {@link #applyInventory}. A target whose runner cannot start CI leases is
   * as broken for CI as one whose ci daemons never dial back, and was otherwise granted run after
   * run to fail every one. Both causes share one count, so they add up, and an end here of a RUNNING
   * lease that did dial back resets it for both.
   */
  public OwnerEnd end(UUID id, String ownerApp, String reason) {
    owned(id, ownerApp);
    String why = reason == null || reason.isBlank() ? null : reason.strip();
    Ended ended = endOne(id, false, why);
    Lease lease = ended.lease();
    if (lease.slotType != SlotType.CI
        || ended.before() != LeaseState.RUNNING
        || lease.state == ended.before()
        || lease.targetId == null) {
      return new OwnerEnd(lease, null);
    }
    boolean neverDialled = NEVER_DIALLED_BACK.equals(why);
    Integer failures = targets.recordDialback(lease.targetId, neverDialled, Instant.now());
    if (!neverDialled || failures == null) {
      return new OwnerEnd(lease, null);
    }
    String detail =
        "dial-back: "
            + failures
            + " CI leases in a row never dialled back their ci daemon; last lease "
            + lease.id
            + " ("
            + lease.ownerRef
            + ")";
    return new OwnerEnd(lease, streakCounted(lease, failures, "never dialled back", detail));
  }

  /**
   * The tail both causes of the dial-back streak share, once {@link Targets#recordDialback} counted
   * one more failure: below {@code qits.system.targets.dialback.threshold} an info line naming the
   * lease and the count ({@code what} says what the lease did), at it the target is quarantined with
   * {@code detail} ({@link Targets#quarantineFor}, which only renews the reason of a standing
   * quarantine) and a warn line says so. Answers that quarantine, or null below the threshold.
   */
  private Targets.Quarantine streakCounted(Lease lease, int failures, String what, String detail) {
    if (failures < config.dialback().threshold()) {
      LOG.infof(
          "CI lease %s (%s) on target %s %s; %d in a row",
          lease.id, lease.ownerRef, lease.targetId, what, failures);
      return null;
    }
    Targets.Quarantine quarantine = targets.quarantineFor(lease.targetId, detail);
    LOG.warnf(
        "Target %s is quarantined%s: %s",
        lease.targetId, quarantine.began() ? "" : " (already, reason renewed)", detail);
    return quarantine;
  }

  /** Who ends a lease as an operator, for its detail and its audit row. */
  public record Operator(String actor, String roles) {}

  /**
   * An operator ends any lease, whoever owns it — the stuck-lease door. QUEUED (or targetless),
   * EXITED or FAILED is ENDED and GRANTED or RUNNING is ENDING, or ENDED on a target without a
   * connection (qits-2227), as for its owner; an ENDING lease is ENDED at once, without the runner's
   * word. A lease it moves gets {@code detail = "ended by
   * <actor>[: <reason>]"}. Every call writes a {@link TargetAuditLog#LEASE_END} row, a refused one
   * too: outcome ENDED, ENDING, NOOP (already ENDED) or NOT_FOUND.
   *
   * @param id null for an id that is not one, which is NOT_FOUND
   * @param asWritten the id as the caller wrote it, recorded as the row's resource when it is no
   *     lease's
   */
  public Lease endByOperator(UUID id, String asWritten, Operator operator, String reason) {
    String why = reason == null || reason.isBlank() ? null : reason.strip();
    String detail = "ended by " + operator.actor() + (why == null ? "" : ": " + why);
    ObjectNode args = null;
    if (why != null) {
      args = JsonNodeFactory.instance.objectNode();
      args.put("reason", why);
    }
    Ended ended;
    try {
      if (id == null) {
        throw TargetRefusal.notFound("No lease " + asWritten);
      }
      ended = endOne(id, true, detail);
    } catch (TargetRefusal refused) {
      audit.record(
          new TargetAuditLog.Entry(
              operator.actor(),
              operator.roles(),
              null,
              id,
              TargetAuditLog.LEASE_END,
              id == null ? asWritten : null,
              args,
              refused.status() == 404 ? "NOT_FOUND" : refused.code(),
              refused.getMessage()));
      throw refused;
    }
    Lease lease = ended.lease();
    boolean moved = lease.state != ended.before();
    LOG.infof(
        "%s ended lease %s (%s %s of %s): %s",
        operator.actor(),
        lease.id,
        lease.slotType,
        lease.ownerRef,
        lease.ownerApp,
        moved ? ended.before() + " -> " + lease.state : "already " + lease.state);
    audit.record(
        new TargetAuditLog.Entry(
            operator.actor(),
            operator.roles(),
            lease.targetId,
            lease.id,
            TargetAuditLog.LEASE_END,
            null,
            args,
            moved ? lease.state.name() : "NOOP",
            moved ? detail : "already " + lease.state));
    return lease;
  }

  // --- the refresh (qits-2091) -------------------------------------------------------------------

  /**
   * The owner's refresh: its RUNNING lease's {@code generation} raised by one and its target's
   * estate pushed, so the runner recreates the container — keeping its work volume — and decides
   * again what it decides only at create (its resolvers). Nothing else of the lease moves.
   *
   * @throws TargetRefusal 404, 403 {@link TargetRefusal#NOT_YOUR_LEASE}, 409 {@link
   *     TargetRefusal#LEASE_NOT_RUNNING}
   */
  public Lease refresh(UUID id, String ownerApp) {
    owned(id, ownerApp);
    Lease lease = refreshOne(id);
    LOG.infof(
        "%s refreshed lease %s (%s %s); generation %d",
        ownerApp, lease.id, lease.slotType, lease.ownerRef, lease.generation);
    return lease;
  }

  /**
   * An operator's refresh of any RUNNING lease, whoever owns it; {@link #refresh} otherwise. Every
   * call writes a {@link TargetAuditLog#LEASE_REFRESH} row, a refused one too: outcome REFRESHED,
   * NOT_FOUND or the refusal's code.
   *
   * @param id null for an id that is not one, which is NOT_FOUND
   * @param asWritten the id as the caller wrote it, recorded as the row's resource when it is no
   *     lease's
   */
  public Lease refreshByOperator(UUID id, String asWritten, Operator operator) {
    Lease lease;
    try {
      if (id == null) {
        throw TargetRefusal.notFound("No lease " + asWritten);
      }
      lease = refreshOne(id);
    } catch (TargetRefusal refused) {
      audit.record(
          new TargetAuditLog.Entry(
              operator.actor(),
              operator.roles(),
              null,
              id,
              TargetAuditLog.LEASE_REFRESH,
              id == null ? asWritten : null,
              null,
              refused.status() == 404 ? "NOT_FOUND" : refused.code(),
              refused.getMessage()));
      throw refused;
    }
    LOG.infof(
        "%s refreshed lease %s (%s %s of %s); generation %d",
        operator.actor(), lease.id, lease.slotType, lease.ownerRef, lease.ownerApp, lease.generation);
    audit.record(
        new TargetAuditLog.Entry(
            operator.actor(),
            operator.roles(),
            lease.targetId,
            lease.id,
            TargetAuditLog.LEASE_REFRESH,
            null,
            null,
            "REFRESHED",
            "generation " + lease.generation));
    return lease;
  }

  /** The one refresh: a RUNNING lease's generation raised by one, then its target's estate. */
  private Lease refreshOne(UUID id) {
    Lease refreshed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Lease lease = Lease.findById(id, LockModeType.PESSIMISTIC_WRITE);
                  if (lease == null) {
                    throw TargetRefusal.notFound("No lease " + id);
                  }
                  if (lease.state != LeaseState.RUNNING) {
                    throw TargetRefusal.conflict(
                        TargetRefusal.LEASE_NOT_RUNNING,
                        "Lease " + id + " is " + lease.state + "; only a RUNNING lease is refreshed");
                  }
                  lease.generation = lease.generation + 1;
                  return lease;
                });
    allocation.estateChanged(refreshed.targetId);
    return refreshed;
  }

  /** A lease after an end, and the state it was in before. */
  private record Ended(Lease lease, LeaseState before) {}

  /**
   * The one end transition. An owner's end ({@code byOperator} false) leaves an ENDING lease alone;
   * an operator's also ends an ENDING lease. A non-null {@code detail} is left on any lease it
   * moves. Every entry into ENDING stamps {@code ending_since}.
   *
   * <p><b>ENDING waits for a runner, so it needs one</b> (qits-2227). ENDING becomes ENDED when the
   * target's runner reports the container gone; a GRANTED or RUNNING lease whose target holds no
   * open connection ({@link TargetPresence#absentSince} is set) has no runner to say so, and ENDING
   * would only hold its slot until the ENDING deadline (an operator once had to end the same lease
   * twice for it). So it is ENDED at once, by its owner and an operator alike. Nothing is lost when
   * the runner comes back: the estate it is sent no longer lists the lease, and it removes the
   * container. A target that holds a connection keeps the path through ENDING; one that dropped
   * moments ago, inside its reconnect grace, does not — until it is back, nothing reports for it,
   * and when it is back its estate settles the container either way.
   */
  private Ended endOne(UUID id, boolean byOperator, String detail) {
    LeaseState[] before = {null};
    Lease ended =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Lease lease = Lease.findById(id, LockModeType.PESSIMISTIC_WRITE);
                  if (lease == null) {
                    throw TargetRefusal.notFound("No lease " + id);
                  }
                  before[0] = lease.state;
                  Instant now = Instant.now();
                  if (lease.state == LeaseState.ENDED
                      || (lease.state == LeaseState.ENDING && !byOperator)) {
                    return lease;
                  }
                  // EXITED and FAILED occupy nothing: their slot was freed when the runner
                  // reported them and may already be granted again, so ENDING (which occupies)
                  // would count it twice (qits-1813). ENDED drops them from the estate, and the
                  // runner stops and removes a lease container its estate no longer lists.
                  if (lease.state == LeaseState.ENDING
                      || lease.state == LeaseState.QUEUED
                      || lease.state == LeaseState.EXITED
                      || lease.state == LeaseState.FAILED
                      || lease.targetId == null
                      || presence.absentSince(lease.targetId).isPresent()) {
                    lease.state = LeaseState.ENDED;
                    lease.endedAt = now;
                  } else {
                    lease.state = LeaseState.ENDING;
                    lease.endingSince = now;
                    lease.endingNudgedAt = null;
                  }
                  if (detail != null) {
                    lease.detail = detail;
                  }
                  return lease;
                });
    if (ended.state != before[0]) {
      events.leaseChanged(ended);
      allocation.estateChanged(ended.targetId);
      allocation.requestPass();
    }
    return new Ended(ended, before[0]);
  }

  // --- the ENDING deadline (LeaseEndingDeadline) -----------------------------------------------

  /** An ENDING lease as the deadline reads it. */
  public record Ending(UUID id, UUID targetId, Instant endingSince, Instant endingNudgedAt) {}

  /**
   * Every ENDING lease. One whose {@code ending_since} is missing (it went ENDING before that was
   * recorded and after the migration's backfill) is given {@code now}, so its deadline starts.
   */
  public List<Ending> ending(Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Lease.<Lease>find("state = ?1 order by id", LeaseState.ENDING)
                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                    .list()
                    .stream()
                    .map(
                        lease -> {
                          if (lease.endingSince == null) {
                            lease.endingSince = now;
                          }
                          return new Ending(
                              lease.id, lease.targetId, lease.endingSince, lease.endingNudgedAt);
                        })
                    .toList());
  }

  /** Records the nudge of a lease that is still ENDING and not yet nudged; true when it did. */
  public boolean nudged(UUID id, Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Lease lease = Lease.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (lease == null
                  || lease.state != LeaseState.ENDING
                  || lease.endingNudgedAt != null) {
                return false;
              }
              lease.endingNudgedAt = now;
              return true;
            });
  }

  /**
   * ENDED without the runner's word, with {@code detail}, when it is still ENDING; announced. Answers
   * the lease as ENDED, or null when something else ended it first.
   */
  public Lease endPastDeadline(UUID id, Instant now, String detail) {
    Lease ended =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Lease lease = Lease.findById(id, LockModeType.PESSIMISTIC_WRITE);
                  if (lease == null || lease.state != LeaseState.ENDING) {
                    return null;
                  }
                  lease.state = LeaseState.ENDED;
                  lease.endedAt = now;
                  lease.detail = detail;
                  return lease;
                });
    if (ended != null) {
      LOG.warnf(
          "Lease %s (%s %s of %s) is ENDED: %s",
          ended.id, ended.slotType, ended.ownerRef, ended.ownerApp, detail);
      events.leaseChanged(ended);
      allocation.estateChanged(ended.targetId);
      allocation.requestPass();
    }
    return ended;
  }

  // --- the requeue (LeaseEndingDeadline) -------------------------------------------------------

  /** A GRANTED lease as the requeue reads it. */
  public record Granted(UUID id, UUID targetId, Instant grantedAt) {}

  /**
   * Every GRANTED lease without a work key, in id order: the ones a requeue may move. A lease with a
   * work key is left out, since its work volume's row pins it to its target ({@link
   * TargetAllocation}) and queuing it again would change nothing but its detail.
   */
  public List<Granted> grantedWithoutWorkKey() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Lease.<Lease>find(
                        "state = ?1 and workKey is null and targetId is not null order by id",
                        LeaseState.GRANTED)
                    .stream()
                    .map(lease -> new Granted(lease.id, lease.targetId, lease.grantedAt))
                    .toList());
  }

  /**
   * QUEUED again, with {@code detail}, when it is still the GRANTED lease {@code granted} read — on
   * the same target, granted at the same instant, without a work key: its target never started it
   * and never will. {@code target_id} and {@code granted_at} are cleared and {@code requested_at}
   * kept, so it takes its old place in the queue. Announced; its old target's estate is pushed (it no
   * longer lists the lease, so a runner that had begun on it removes what it made) and a pass is
   * asked for. Answers the lease as QUEUED, or null when something else moved it first.
   */
  public Lease requeue(Granted granted, String detail) {
    Lease queued =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Lease lease = Lease.findById(granted.id(), LockModeType.PESSIMISTIC_WRITE);
                  if (lease == null
                      || lease.state != LeaseState.GRANTED
                      || lease.workKey != null
                      || !Objects.equals(lease.targetId, granted.targetId())
                      || !Objects.equals(lease.grantedAt, granted.grantedAt())) {
                    return null;
                  }
                  lease.state = LeaseState.QUEUED;
                  lease.targetId = null;
                  lease.grantedAt = null;
                  lease.detail = detail;
                  return lease;
                });
    if (queued != null) {
      LOG.warnf(
          "Lease %s (%s %s of %s) is QUEUED again: %s",
          queued.id, queued.slotType, queued.ownerRef, queued.ownerApp, detail);
      events.leaseChanged(queued);
      allocation.estateChanged(granted.targetId());
      allocation.requestPass();
    }
    return queued;
  }

  // --- the absent target (LeaseEndingDeadline, qits-2227) ---------------------------------------

  /** A lease on a target as the absent pass reads it. */
  public record OnTarget(UUID id, UUID targetId, LeaseState state) {}

  /**
   * The leases {@link #abandon} may settle, in id order: every RUNNING CI lease and every ENDING one,
   * whatever its type. A RUNNING WORKSPACE or DESK lease is left out: its container holds an agent
   * session that outlives an edge outage, and settling it would have the returning runner remove it.
   * A GRANTED lease is left out too: a CI one has no work key, so {@link #requeue} has it back in the
   * queue after the reconnect grace, long before the absent deadline.
   */
  public List<OnTarget> onTargets() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Lease.<Lease>find(
                        "targetId is not null and (state = ?1 or (state = ?2 and slotType = ?3))"
                            + " order by id",
                        LeaseState.ENDING,
                        LeaseState.RUNNING,
                        SlotType.CI)
                    .stream()
                    .map(lease -> new OnTarget(lease.id, lease.targetId, lease.state))
                    .toList());
  }

  /** A lease the absent pass settled, and the state it was in before. */
  public record Abandoned(Lease lease, LeaseState before) {}

  /**
   * Settled without the runner's word, because its target has been gone past {@code
   * qits.system.leases.absent-deadline} (qits-2227): a RUNNING CI lease is FAILED — its owner learns
   * its step did not finish, and ends it as it ends any FAILED one — and an ENDING one of any type
   * is ENDED; both with {@code ended_at = now} and {@code detail}. Only while it is still on {@code
   * onTarget}'s target in one of those states, re-read under the row lock: a WORKSPACE or DESK lease
   * is never FAILED here. Announced; the target's
   * estate is pushed (FAILED and ENDED are both {@code desired: ENDED}, so a runner that comes back
   * removes the container) and a pass is asked for, since the slot is free. Answers the lease and
   * what it was, or null when something else moved it first.
   */
  public Abandoned abandon(OnTarget onTarget, Instant now, String detail) {
    LeaseState[] before = {null};
    Lease settled =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Lease lease = Lease.findById(onTarget.id(), LockModeType.PESSIMISTIC_WRITE);
                  if (lease == null || !Objects.equals(lease.targetId, onTarget.targetId())) {
                    return null;
                  }
                  before[0] = lease.state;
                  switch (lease.state) {
                    case RUNNING -> {
                      if (lease.slotType != SlotType.CI) {
                        return null;
                      }
                      lease.state = LeaseState.FAILED;
                    }
                    case ENDING -> lease.state = LeaseState.ENDED;
                    default -> {
                      return null;
                    }
                  }
                  lease.endedAt = now;
                  lease.detail = detail;
                  return lease;
                });
    if (settled == null) {
      return null;
    }
    LOG.warnf(
        "Lease %s (%s %s of %s) is %s: %s",
        settled.id, settled.slotType, settled.ownerRef, settled.ownerApp, settled.state, detail);
    events.leaseChanged(settled);
    allocation.estateChanged(settled.targetId);
    allocation.requestPass();
    return new Abandoned(settled, before[0]);
  }

  /**
   * The image of the newest lease of {@code type} that has not ENDED — QUEUED included, since a
   * queued lease already carries the owner's real, pinned image — or empty.
   */
  public java.util.Optional<String> newestImage(SlotType type) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Lease.<Lease>find(
                        "slotType = ?1 and state <> ?2 order by requestedAt desc, id desc",
                        type,
                        LeaseState.ENDED)
                    .stream()
                    .map(lease -> LeaseSpecs.parse(lease.spec))
                    .filter(spec -> spec != null && spec.hasNonNull("image"))
                    .map(spec -> spec.get("image").asText())
                    .findFirst());
  }

  // --- the runner's reports ---------------------------------------------------------------------

  /**
   * One held lease as a runner's {@code inventory} names it, with its memory facts (qits-1818) —
   * each null when the runner read none, and from a runner before them.
   */
  public record Held(
      UUID leaseId,
      String state,
      String specHash,
      Integer exitCode,
      String detail,
      Long oomKills,
      Long oomEvents,
      Long memoryPeakBytes,
      Boolean oomKilled,
      List<String> resolvers,
      Boolean resolversStale) {

    /**
     * A report without the resolvers (qits-2091): both null, which leaves the stored ones as they
     * were.
     */
    public Held(
        UUID leaseId,
        String state,
        String specHash,
        Integer exitCode,
        String detail,
        Long oomKills,
        Long oomEvents,
        Long memoryPeakBytes,
        Boolean oomKilled) {
      this(
          leaseId,
          state,
          specHash,
          exitCode,
          detail,
          oomKills,
          oomEvents,
          memoryPeakBytes,
          oomKilled,
          null,
          null);
    }

    /** A report without the memory facts: the shape before qits-1818. */
    public Held(UUID leaseId, String state, String specHash, Integer exitCode, String detail) {
      this(leaseId, state, specHash, exitCode, detail, null, null, null, null);
    }
  }

  /**
   * A runner's {@code inventory}: every lease it holds. Its reports move the leases of this target
   * — RUNNING, EXITED, FAILED, and ENDED for one that is ENDING — and an ENDING lease it no longer
   * holds is ENDED. STARTING on a GRANTED lease never moves its state, only its {@code detail} (the
   * image it is pulling); STARTING on a RUNNING lease (docker restarting its container) changes
   * nothing. A report about a lease that is not this target's is ignored: the estate omits it, and
   * the runner removes it. Answers the leases that changed.
   *
   * <p>Each report's memory facts (qits-1818) are written to its row by {@link #recordMemory}: on
   * their own they are not a change and publish nothing, except an {@code oomKills} that rose — a
   * process of the lease was just killed — which is announced like a state change, on a lease whose
   * state held too.
   *
   * <p>Each report's resolvers (qits-2091) are written to its row by {@link #recordResolvers}, on
   * every report and whatever its state did — RUNNING on a RUNNING lease included — and are never a
   * change: they publish nothing.
   *
   * <p>A CI lease that goes from GRANTED to FAILED — its runner could not start it — counts one
   * more failure in its target's dial-back streak (qits-525), which {@link #end(UUID, String,
   * String)} otherwise counts for a RUNNING lease that never dialled back (qits-1809); at the
   * threshold the target is quarantined the same way, with a {@code start: } reason naming the
   * last lease and its runner's detail. Nothing else here touches the count: a RUNNING lease that
   * failed or exited ran, so its target can start leases, and a GRANTED one that EXITED is no
   * start failure. The state before is kept per lease inside the transaction, no column.
   *
   * <p>The leases alone, for a caller with no runner to tell; {@link #apply} also answers the
   * quarantine.
   */
  public List<Lease> applyInventory(UUID targetId, List<Held> held) {
    return apply(targetId, held).changed();
  }

  /**
   * An inventory's outcome: the leases that changed, and the quarantine a start failure among them
   * began or renewed (null when none touched one; the last of several wins).
   */
  public record Applied(List<Lease> changed, Targets.Quarantine quarantine) {}

  /**
   * {@link #applyInventory}, answering the quarantine too: a quarantine its start failures began is
   * the target's runner's to hear (its {@code quarantined} frame and an ack of 0 slots), as an
   * owner's end hands its own to its door. That is the runner socket's to send — it holds the
   * session this inventory came on — so the quarantine is handed back rather than sent from here.
   */
  public Applied apply(UUID targetId, List<Held> held) {
    Map<UUID, Held> reported = new LinkedHashMap<>();
    for (Held report : held) {
      if (report.leaseId() != null) {
        reported.put(report.leaseId(), report);
      }
    }
    Map<UUID, LeaseState> before = new HashMap<>();
    List<Lease> changed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Lease> moved = new ArrayList<>();
                  List<Lease> mine =
                      Lease.<Lease>find(
                              "targetId = ?1 and state in ?2 order by id",
                              targetId,
                              Set.of(
                                  LeaseState.GRANTED,
                                  LeaseState.RUNNING,
                                  LeaseState.ENDING))
                          .withLock(LockModeType.PESSIMISTIC_WRITE)
                          .list();
                  Instant now = Instant.now();
                  for (Lease lease : mine) {
                    Held report = reported.get(lease.id);
                    boolean killed = recordMemory(lease, report);
                    recordResolvers(lease, report);
                    before.put(lease.id, lease.state);
                    if (transition(lease, report, now) | killed) {
                      moved.add(lease);
                    }
                  }
                  return moved;
                });
    for (Lease lease : changed) {
      LOG.infof(
          "Lease %s (%s %s of %s) is %s%s%s",
          lease.id,
          lease.slotType,
          lease.ownerRef,
          lease.ownerApp,
          lease.state,
          lease.detail == null ? "" : ": " + lease.detail,
          lease.oomScope() == null
              ? ""
              : " (" + lease.oomKills + " OOM kill(s), " + lease.oomScope() + ")");
      events.leaseChanged(lease);
    }
    Targets.Quarantine quarantine = null;
    for (Lease lease : changed) {
      if (lease.slotType == SlotType.CI
          && lease.targetId != null
          && before.get(lease.id) == LeaseState.GRANTED
          && lease.state == LeaseState.FAILED) {
        Targets.Quarantine began = startFailed(lease);
        if (began != null) {
          quarantine = began;
        }
      }
    }
    if (changed.stream().anyMatch(l -> !LeaseState.OCCUPYING.contains(l.state))) {
      allocation.requestPass();
    }
    return new Applied(changed, quarantine);
  }

  /**
   * A GRANTED CI lease its runner reported FAILED: one more failure in its target's dial-back
   * streak (qits-525), and the quarantine at the threshold — answered, null below it.
   */
  private Targets.Quarantine startFailed(Lease lease) {
    Integer failures = targets.recordDialback(lease.targetId, true, Instant.now());
    if (failures == null) {
      return null;
    }
    String detail =
        "start: "
            + failures
            + " CI leases in a row failed to start on this target; last lease "
            + lease.id
            + " ("
            + lease.ownerRef
            + "): "
            + lease.detail;
    return streakCounted(lease, failures, "failed to start", detail);
  }

  /**
   * One lease against one report (null: the runner does not hold it). True when it moved — a
   * state change, or (STARTING, GRANTED) a changed {@code detail} with the state held.
   */
  static boolean transition(Lease lease, Held report, Instant now) {
    if (report == null) {
      if (lease.state == LeaseState.ENDING) {
        lease.state = LeaseState.ENDED;
        lease.endedAt = now;
        return true;
      }
      return false;
    }
    String said = report.state() == null ? "" : report.state().trim().toUpperCase();
    switch (lease.state) {
      case ENDING -> {
        if (said.equals("ENDED")) {
          lease.state = LeaseState.ENDED;
          lease.endedAt = now;
          return true;
        }
        return false;
      }
      case GRANTED, RUNNING -> {
        switch (said) {
          case "RUNNING" -> {
            if (lease.state == LeaseState.RUNNING) {
              return false;
            }
            lease.state = LeaseState.RUNNING;
            lease.startedAt = now;
            lease.detail = null;
            return true;
          }
          case "STARTING" -> {
            if (lease.state != LeaseState.GRANTED) {
              return false;
            }
            String detail = blankToNull(report.detail());
            if (Objects.equals(detail, lease.detail)) {
              return false;
            }
            lease.detail = detail;
            return true;
          }
          case "EXITED" -> {
            lease.state = LeaseState.EXITED;
            lease.exitCode = report.exitCode();
            lease.detail = report.detail();
            lease.endedAt = now;
            return true;
          }
          case "FAILED" -> {
            lease.state = LeaseState.FAILED;
            lease.exitCode = report.exitCode();
            lease.detail = report.detail() == null ? "the runner could not start it" : report.detail();
            lease.endedAt = now;
            return true;
          }
          case "ENDED" -> {
            lease.state = LeaseState.EXITED;
            lease.exitCode = report.exitCode();
            lease.detail = report.detail() == null ? "the runner removed its container" : report.detail();
            lease.endedAt = now;
            return true;
          }
          default -> {
            return false;
          }
        }
      }
      default -> {
        return false;
      }
    }
  }

  /**
   * A report's memory facts onto its lease's row (qits-1818). A null leaves the stored value — an
   * EXITED lease keeps the last sample its container gave — and the counters and the peak never
   * move down: a lower value is a stale or coarser read, not a new truth. True when {@code
   * oomKills} rose, a first report above 0 included.
   */
  static boolean recordMemory(Lease lease, Held report) {
    if (report == null) {
      return false;
    }
    Long before = lease.oomKills;
    lease.oomKills = highest(lease.oomKills, report.oomKills());
    lease.oomEvents = highest(lease.oomEvents, report.oomEvents());
    lease.memoryPeakBytes = highest(lease.memoryPeakBytes, report.memoryPeakBytes());
    if (report.oomKilled() != null) {
      lease.oomKilled = report.oomKilled();
    }
    return lease.oomKills != null && lease.oomKills > (before == null ? 0 : before);
  }

  /**
   * A report's resolvers onto its lease's row (qits-2091), written only when they differ from the
   * stored ones; a null (a runner before qits-2091) leaves the stored value. True when it wrote.
   */
  static boolean recordResolvers(Lease lease, Held report) {
    if (report == null) {
      return false;
    }
    boolean wrote = false;
    if (report.resolvers() != null) {
      List<String> reported = List.copyOf(report.resolvers());
      if (!reported.equals(resolvers(lease.resolvers))) {
        lease.resolvers = resolversText(reported);
        wrote = true;
      }
    }
    if (report.resolversStale() != null && report.resolversStale() != lease.resolversStale) {
      lease.resolversStale = report.resolversStale();
      wrote = true;
    }
    return wrote;
  }

  /** A stored resolvers column as a list; empty for null or anything that is not a JSON array. */
  public static List<String> resolvers(String stored) {
    JsonNode node = LeaseSpecs.parse(stored);
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    node.forEach(member -> out.add(member.asText()));
    return List.copyOf(out);
  }

  static String resolversText(List<String> resolvers) {
    var array = JsonNodeFactory.instance.arrayNode();
    resolvers.forEach(array::add);
    return LeaseSpecs.text(array);
  }

  private static Long highest(Long stored, Long reported) {
    if (reported == null) {
      return stored;
    }
    return stored == null ? reported : Math.max(stored, reported);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  // --- internals --------------------------------------------------------------------------------

  private Lease owned(UUID id, String ownerApp) {
    Lease lease = get(id);
    if (ownerApp == null || !ownerApp.equals(lease.ownerApp)) {
      throw TargetRefusal.forbidden(
          TargetRefusal.NOT_YOUR_LEASE, "Lease " + id + " is " + lease.ownerApp + "'s");
    }
    return lease;
  }

  private static Map<String, String> requireAdopt(Map<String, String> adopt, String workKey) {
    if (adopt == null || adopt.isEmpty()) {
      return null;
    }
    String container = adopt.get("container");
    String volume = adopt.get("volume");
    if (container == null
        || volume == null
        || !DOCKER_NAME.matcher(container).matches()
        || !DOCKER_NAME.matcher(volume).matches()) {
      throw TargetRefusal.badRequest("adopt names a container and a volume, both docker names");
    }
    if (workKey == null) {
      throw TargetRefusal.badRequest("An adopted volume becomes the work volume: adopt needs a workKey");
    }
    Map<String, String> kept = new LinkedHashMap<>();
    kept.put("container", container);
    kept.put("volume", volume);
    return kept;
  }

  private static String adoptText(Map<String, String> adopt) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    adopt.forEach(node::put);
    return LeaseSpecs.text(node);
  }

  private static TargetRefusal leaseExists(String ownerApp, String ownerRef) {
    return TargetRefusal.conflict(
        TargetRefusal.LEASE_EXISTS, ownerApp + " holds a live lease for " + ownerRef + " already");
  }
}
