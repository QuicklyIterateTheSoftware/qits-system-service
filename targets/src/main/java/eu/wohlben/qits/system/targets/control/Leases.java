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

  /**
   * The owner is done with it: a QUEUED lease (or one with no target) is ENDED at once; any other is
   * ENDING until the runner reports its container gone. Ending an ENDING or ENDED lease is a no-op.
   */
  public Lease end(UUID id, String ownerApp) {
    owned(id, ownerApp);
    return endOne(id, null).lease();
  }

  /** Who ends a lease as an operator, for its detail and its audit row. */
  public record Operator(String actor, String roles) {}

  /**
   * An operator ends any lease, whoever owns it — the stuck-lease door. QUEUED (or targetless) is
   * ENDED and GRANTED, RUNNING, EXITED or FAILED is ENDING, as for its owner; an ENDING lease is
   * ENDED at once, without the runner's word. A lease it moves gets {@code detail = "ended by
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
      ended = endOne(id, detail);
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

  /** A lease after an end, and the state it was in before. */
  private record Ended(Lease lease, LeaseState before) {}

  /**
   * The one end transition. {@code operatorDetail} null is the owner's end, which leaves an ENDING
   * lease alone; set, it is an operator's, which also ends an ENDING lease and leaves the detail on
   * any lease it moves. Every entry into ENDING stamps {@code ending_since}.
   */
  private Ended endOne(UUID id, String operatorDetail) {
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
                      || (lease.state == LeaseState.ENDING && operatorDetail == null)) {
                    return lease;
                  }
                  if (lease.state == LeaseState.ENDING
                      || lease.state == LeaseState.QUEUED
                      || lease.targetId == null) {
                    lease.state = LeaseState.ENDED;
                    lease.endedAt = now;
                  } else {
                    lease.state = LeaseState.ENDING;
                    lease.endingSince = now;
                    lease.endingNudgedAt = null;
                  }
                  if (operatorDetail != null) {
                    lease.detail = operatorDetail;
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

  /** One held lease as a runner's {@code inventory} names it. */
  public record Held(UUID leaseId, String state, String specHash, Integer exitCode, String detail) {}

  /**
   * A runner's {@code inventory}: every lease it holds. Its reports move the leases of this target
   * — RUNNING, EXITED, FAILED, and ENDED for one that is ENDING — and an ENDING lease it no longer
   * holds is ENDED. A report about a lease that is not this target's is ignored: the estate omits
   * it, and the runner removes it. Answers the leases that changed.
   */
  public List<Lease> applyInventory(UUID targetId, List<Held> held) {
    Map<UUID, Held> reported = new LinkedHashMap<>();
    for (Held report : held) {
      if (report.leaseId() != null) {
        reported.put(report.leaseId(), report);
      }
    }
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
                    if (transition(lease, report, now)) {
                      moved.add(lease);
                    }
                  }
                  return moved;
                });
    for (Lease lease : changed) {
      LOG.infof(
          "Lease %s (%s %s of %s) is %s%s",
          lease.id,
          lease.slotType,
          lease.ownerRef,
          lease.ownerApp,
          lease.state,
          lease.detail == null ? "" : ": " + lease.detail);
      events.leaseChanged(lease);
    }
    if (changed.stream().anyMatch(l -> !LeaseState.OCCUPYING.contains(l.state))) {
      allocation.requestPass();
    }
    return changed;
  }

  /** One lease against one report (null: the runner does not hold it). True when it moved. */
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
