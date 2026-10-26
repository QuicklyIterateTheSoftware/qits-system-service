package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The target rows: every read and write of {@code target}, each its own {@code requiringNew}
 * transaction, so nothing holds one across the network (qits-idp, a runner socket) and a caller
 * never needs to be inside one.
 *
 * <p><b>A new target starts quarantined</b> ({@link #AWAITING_FIRST_HEALTH_CHECK}): holding a token
 * proves nothing about running a slot. A passing health check or a greenlight reinstates it.
 */
@ApplicationScoped
public class Targets {

  /** A target's name: a lower-case letter, then up to 63 lower-case letters, digits and hyphens. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  public static final int DESCRIPTION_MAX = 1024;

  /** The quarantine every target is created with. */
  public static final String AWAITING_FIRST_HEALTH_CHECK = "awaiting first health check";

  /** The minimums a request sets, per type; null members leave the current value. */
  public record Minimums(Integer ci, Integer workspace, Integer desk) {
    public static final Minimums NONE = new Minimums(0, 0, 0);
  }

  /** What a quarantine did: began it, or only changed the reason of one already standing. */
  public record Quarantine(Target target, boolean began, boolean reasonChanged) {}

  // --- reads ------------------------------------------------------------------------------------

  public List<Target> list() {
    return QuarkusTransaction.requiringNew()
        .call(() -> Target.<Target>find("order by name").list());
  }

  /** @throws TargetRefusal 404 */
  public Target get(UUID id) {
    Target target = id == null ? null : QuarkusTransaction.requiringNew().call(() -> Target.findById(id));
    if (target == null) {
      throw TargetRefusal.notFound("No target " + id);
    }
    return target;
  }

  public Target find(UUID id) {
    return id == null ? null : QuarkusTransaction.requiringNew().call(() -> Target.findById(id));
  }

  /** The target named {@code name}, or null. */
  public Target findByName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> Target.<Target>find("name", name).firstResult());
  }

  public Target findByClientId(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> Target.<Target>find("clientId", clientId).firstResult());
  }

  /** The slots each target's leases occupy now (GRANTED, RUNNING, ENDING), by type. */
  public Map<UUID, Map<SlotType, Integer>> used() {
    return QuarkusTransaction.requiringNew().call(Targets::usedInTransaction);
  }

  /** {@link #used()} inside the caller's transaction. */
  static Map<UUID, Map<SlotType, Integer>> usedInTransaction() {
    Map<UUID, Map<SlotType, Integer>> used = new HashMap<>();
    List<Object[]> rows =
        Target.getEntityManager()
            .createQuery(
                "select l.targetId, l.slotType, count(l) from Lease l where l.targetId is not null"
                    + " and l.state in :occupying group by l.targetId, l.slotType",
                Object[].class)
            .setParameter("occupying", LeaseState.OCCUPYING)
            .getResultList();
    for (Object[] row : rows) {
      used.computeIfAbsent((UUID) row[0], k -> new EnumMap<>(SlotType.class))
          .put((SlotType) row[1], ((Number) row[2]).intValue());
    }
    return used;
  }

  /** Whether any lease that is not ENDED sits on the target. */
  public boolean busy(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Target.getEntityManager()
                        .createQuery(
                            "select count(l) from Lease l where l.targetId = :id and l.state <> :ended",
                            Long.class)
                        .setParameter("id", id)
                        .setParameter("ended", LeaseState.ENDED)
                        .getSingleResult()
                    > 0);
  }

  /** Whether the target runs a CI lease now (the {@code buildkit} check applies). */
  public boolean runsCi(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Target.getEntityManager()
                        .createQuery(
                            "select count(l) from Lease l where l.targetId = :id and l.slotType ="
                                + " :ci and l.state in :occupying",
                            Long.class)
                        .setParameter("id", id)
                        .setParameter("ci", SlotType.CI)
                        .setParameter("occupying", LeaseState.OCCUPYING)
                        .getSingleResult()
                    > 0);
  }

  // --- the operator's writes --------------------------------------------------------------------

  /** The most slots an install line may name. */
  public static final int SLOTS_MAX = 1024;

  /**
   * 400 unless {@code slots} — the total an install line carries, never stored — is absent, or
   * within 0..{@value #SLOTS_MAX} and at least {@code minimumsSum}. Asked before anything is
   * commissioned.
   */
  public static void requireInstallSlots(Integer slots, int minimumsSum) {
    if (slots == null) {
      return;
    }
    if (slots < 0 || slots > SLOTS_MAX) {
      throw TargetRefusal.badRequest(
          "A slots total is between 0 and " + SLOTS_MAX + ", not " + slots);
    }
    if (slots < minimumsSum) {
      throw TargetRefusal.badRequest(
          "A slots total of "
              + slots
              + " is below the minimums, which reserve "
              + minimumsSum
              + " slot(s)");
    }
  }

  /** The sum of the minimums a request sets, absent members counted 0. */
  public static int sum(Minimums minimums) {
    return minimums == null
        ? 0
        : orZero(minimums.ci()) + orZero(minimums.workspace()) + orZero(minimums.desk());
  }

  /** 400 or 409 for a create that could not be written; asked before anything is commissioned. */
  public void requireCreatable(String name, String description, Minimums minimums) {
    requireName(name);
    requireDescription(description);
    requireMinimums(minimums);
    boolean taken =
        QuarkusTransaction.requiringNew().call(() -> Target.count("name", name) > 0);
    if (taken) {
      throw nameTaken(name);
    }
  }

  public Target create(
      UUID id,
      String name,
      String description,
      Minimums minimums,
      String registrationTokenId,
      String registrationTokenSubject) {
    Minimums given = minimums == null ? Minimums.NONE : minimums;
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                Target target = new Target();
                Instant now = Instant.now();
                target.id = id;
                target.name = name;
                target.description = blankToNull(description);
                target.minCi = orZero(given.ci());
                target.minWorkspace = orZero(given.workspace());
                target.minDesk = orZero(given.desk());
                target.registrationTokenId = registrationTokenId;
                target.registrationTokenSubject = registrationTokenSubject;
                target.loginClaude = LoginValue.UNKNOWN;
                target.loginKimi = LoginValue.UNKNOWN;
                target.quarantinedAt = now;
                target.quarantineReason = AWAITING_FIRST_HEALTH_CHECK;
                target.createdAt = now;
                target.persistAndFlush();
                return target;
              });
    } catch (PersistenceException duplicate) {
      throw nameTaken(name);
    }
  }

  /**
   * A new description and minimums. 409 {@link TargetRefusal#MINIMUMS_EXCEED_TOTAL} when their sum
   * is above a reported total ({@code slotsTotal > 0}); before the first {@code hello} any sum goes.
   */
  public Target patch(UUID id, String description, Minimums minimums) {
    requireDescription(description);
    requireMinimums(minimums);
    Target patched =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Target target = Target.findById(id);
                  if (target == null) {
                    return null;
                  }
                  if (description != null) {
                    target.description = blankToNull(description);
                  }
                  if (minimums != null) {
                    int ci = minimums.ci() == null ? target.minCi : minimums.ci();
                    int ws = minimums.workspace() == null ? target.minWorkspace : minimums.workspace();
                    int desk = minimums.desk() == null ? target.minDesk : minimums.desk();
                    if (target.slotsTotal > 0 && ci + ws + desk > target.slotsTotal) {
                      throw TargetRefusal.conflict(
                          TargetRefusal.MINIMUMS_EXCEED_TOTAL,
                          "The minimums sum to "
                              + (ci + ws + desk)
                              + ", above the "
                              + target.slotsTotal
                              + " slot(s) target "
                              + target.name
                              + " reports");
                    }
                    target.minCi = ci;
                    target.minWorkspace = ws;
                    target.minDesk = desk;
                  }
                  return target;
                });
    if (patched == null) {
      throw TargetRefusal.notFound("No target " + id);
    }
    return patched;
  }

  /**
   * Deletes the row and its work-volume rows. 409 {@link TargetRefusal#TARGET_BUSY} while any lease
   * that is not ENDED sits on it. Answers the row as it was, for the caller to give its credentials
   * back and retire its runner.
   */
  public Target delete(UUID id) {
    Target gone =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Target target = Target.findById(id);
                  if (target == null) {
                    return null;
                  }
                  long live =
                      Target.getEntityManager()
                          .createQuery(
                              "select count(l) from Lease l where l.targetId = :id and l.state <>"
                                  + " :ended",
                              Long.class)
                          .setParameter("id", id)
                          .setParameter("ended", LeaseState.ENDED)
                          .getSingleResult();
                  if (live > 0) {
                    throw TargetRefusal.conflict(
                        TargetRefusal.TARGET_BUSY,
                        "Target "
                            + target.name
                            + " holds "
                            + live
                            + " lease(s) that have not ended; end them first");
                  }
                  WorkVolume.delete("targetId", id);
                  target.delete();
                  return target;
                });
    if (gone == null) {
      throw TargetRefusal.notFound("No target " + id);
    }
    return gone;
  }

  /**
   * Swaps in a new registration token; answers the replaced one's id, for the caller to delete. A
   * registered target keeps its client meanwhile: its runner keeps working until the one installed
   * with the new token registers (qits-1579).
   */
  public String replaceRegistrationToken(UUID id, String tokenId, String subject) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = Target.findById(id);
              if (target == null) {
                throw TargetRefusal.notFound("No target " + id);
              }
              String previous = target.registrationTokenId;
              target.registrationTokenId = tokenId;
              target.registrationTokenSubject = subject;
              return previous;
            });
  }

  /**
   * 409 when registered and holding no pending registration token (its token was spent, so nothing
   * else can be checked against it); 403 unless {@code subject} is the target's registration token.
   * A registered target whose token was rotated registers again with the new one (qits-1579).
   */
  public Target requireRegistrable(UUID id, String subject) {
    Target target = get(id);
    if (target.registered() && target.registrationTokenSubject == null) {
      throw registeredAlready(target);
    }
    if (subject == null
        || subject.isBlank()
        || !subject.equals(target.registrationTokenSubject)) {
      throw new TargetRefusal(403, null, "This registration token is not this target's");
    }
    return target;
  }

  /** What a registration did: the target as it now is, and the client it replaced (null if none). */
  public record Registered(Target target, String previousClientId) {}

  /**
   * Records the runner's client; the registration token is spent and forgotten. Inside the write the
   * token {@code subject} registered with must still be the pending one, so of two registrations on
   * one token only the first wins and the other is 409 {@link TargetRefusal#ALREADY_REGISTERED}.
   * Answers the client it replaced, which the caller gives back to qits-idp.
   */
  public Registered markRegistered(UUID id, String subject, String clientId, String capabilities) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = Target.findById(id);
              if (target == null) {
                throw TargetRefusal.notFound("No target " + id);
              }
              if (subject == null || !subject.equals(target.registrationTokenSubject)) {
                // Spent by a racer on the same token, or rotated away since requireRegistrable.
                throw target.registered()
                    ? registeredAlready(target)
                    : new TargetRefusal(403, null, "This registration token is not this target's");
              }
              String previous = target.clientId;
              Instant now = Instant.now();
              target.clientId = clientId;
              target.registeredAt = now;
              target.registrationTokenId = null;
              target.registrationTokenSubject = null;
              if (capabilities != null) {
                target.capabilities = capabilities;
              }
              if (!target.quarantined()) {
                target.quarantinedAt = now;
                target.quarantineReason = AWAITING_FIRST_HEALTH_CHECK;
              }
              return new Registered(target, previous);
            });
  }

  // --- the runner's reports ---------------------------------------------------------------------

  /** What a {@code hello} said: the total, the version and the capabilities. Null when gone. */
  public Target recordHello(
      UUID id, int slots, String runnerVersion, String capabilities, String credentialVolume) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = Target.findById(id);
              if (target == null) {
                return null;
              }
              Instant now = Instant.now();
              target.slotsTotal = Math.max(0, slots);
              target.runnerVersion = runnerVersion;
              if (capabilities != null) {
                target.capabilities = capabilities;
              }
              if (credentialVolume != null && !credentialVolume.isBlank()) {
                target.credentialVolume = credentialVolume;
              }
              target.lastSeenAt = now;
              return target;
            });
  }

  public void touchSeen(UUID id) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Target target = Target.findById(id);
              if (target != null) {
                target.lastSeenAt = Instant.now();
              }
            });
  }

  /** What a login probe read. */
  public record LoginRecorded(Target target, boolean changed) {}

  /** Writes the login state; {@code changed} when either harness's value moved. Null when gone. */
  public LoginRecorded recordLogin(
      UUID id, LoginValue claude, LoginValue kimi, Instant checkedAt, String volume) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = Target.findById(id);
              if (target == null) {
                return null;
              }
              boolean changed =
                  !Objects.equals(target.loginClaude, claude)
                      || !Objects.equals(target.loginKimi, kimi);
              target.loginClaude = claude;
              target.loginKimi = kimi;
              target.loginCheckedAt = checkedAt;
              if (volume != null && !volume.isBlank()) {
                target.credentialVolume = volume;
              }
              return new LoginRecorded(target, changed);
            });
  }

  /** Quarantines a target that is not; null when it already was or is gone. */
  public Target quarantine(UUID id, String reason) {
    Quarantine out = quarantineFor(id, reason);
    return out.began() ? out.target() : null;
  }

  /** Quarantines it, or gives a standing quarantine the newer reason. */
  public Quarantine quarantineFor(UUID id, String reason) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = Target.findById(id);
              if (target == null) {
                return new Quarantine(null, false, false);
              }
              if (target.quarantined()) {
                boolean changed = !Objects.equals(target.quarantineReason, reason);
                target.quarantineReason = reason;
                return new Quarantine(target, false, changed);
              }
              target.quarantinedAt = Instant.now();
              target.quarantineReason = reason;
              return new Quarantine(target, true, true);
            });
  }

  /** Lifts a quarantine (a no-op on a target that has none). */
  public Target greenlight(UUID id) {
    Target lifted =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Target target = Target.findById(id);
                  if (target != null) {
                    target.quarantinedAt = null;
                    target.quarantineReason = null;
                  }
                  return target;
                });
    if (lifted == null) {
      throw TargetRefusal.notFound("No target " + id);
    }
    return lifted;
  }

  /** A settled check's verdict and report. Null when gone. */
  public Target recordHealthCheck(UUID id, boolean ok, Instant at, String report) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = Target.findById(id);
              if (target == null) {
                return null;
              }
              target.lastHealthCheckAt = at;
              target.lastHealthCheckOk = ok;
              target.health = report;
              return target;
            });
  }

  // --- rules ------------------------------------------------------------------------------------

  public static void requireName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw TargetRefusal.badRequest(
          "A target's name is a lower-case letter, then up to 63 lower-case letters, digits and"
              + " hyphens; not '"
              + name
              + "'");
    }
  }

  static void requireDescription(String description) {
    if (description != null && description.length() > DESCRIPTION_MAX) {
      throw TargetRefusal.badRequest(
          "A target's description is at most " + DESCRIPTION_MAX + " characters");
    }
  }

  static void requireMinimums(Minimums minimums) {
    if (minimums == null) {
      return;
    }
    for (Integer value : new Integer[] {minimums.ci(), minimums.workspace(), minimums.desk()}) {
      if (value != null && (value < 0 || value > 1024)) {
        throw TargetRefusal.badRequest("A minimum is between 0 and 1024, not " + value);
      }
    }
  }

  private static TargetRefusal nameTaken(String name) {
    return TargetRefusal.conflict(TargetRefusal.TARGET_NAME_TAKEN, "Target name " + name + " is taken");
  }

  private static TargetRefusal registeredAlready(Target target) {
    return TargetRefusal.conflict(
        TargetRefusal.ALREADY_REGISTERED,
        "Target "
            + target.name
            + " is registered already; rotate its registration token to reinstall it");
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
