package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.control.SlotAllocator.Decision;
import eu.wohlben.qits.system.targets.control.SlotAllocator.Request;
import eu.wohlben.qits.system.targets.control.SlotAllocator.TargetSlots;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * <b>Runs {@link SlotAllocator} against the rows</b>, and writes what it decides: a grant (GRANTED,
 * {@code target_id}, the work volume's row on first use), or a queued lease's {@code detail}. Every
 * grant pushes its target's {@code estate}.
 *
 * <p><b>When.</b> On every lease change and every inventory ({@link #requestPass}, coalesced onto
 * one thread so a burst is one pass), and every {@code qits.system.targets.estate-interval}, when
 * every connected target is also sent its whole estate again ({@link #tick}). A pass is serialised:
 * two never interleave, so a target is never granted past its total by a race between them.
 *
 * <p><b>The work volume pins.</b> A lease whose work key already has a volume is considered on that
 * volume's target only; one whose volume is marked for purging waits until the purge is done.
 */
@ApplicationScoped
public class TargetAllocation {

  private static final Logger LOG = Logger.getLogger(TargetAllocation.class);

  /** The queued detail of a lease whose work volume is being purged. */
  static final String VOLUME_PURGING = "its work volume is being purged";

  @Inject TargetPresence presence;

  @Inject TargetEvents events;

  @Inject WorkVolumes workVolumes;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "system-targets-allocator");
            t.setDaemon(true);
            return t;
          });

  private final AtomicBoolean queued = new AtomicBoolean();

  /** A pass soon, on the allocator's thread; requests made before it starts are one pass. */
  public void requestPass() {
    if (!queued.compareAndSet(false, true)) {
      return;
    }
    try {
      worker.execute(
          () -> {
            queued.set(false);
            try {
              pass();
            } catch (RuntimeException e) {
              LOG.warnf("An allocation pass did not complete: %s", e.toString());
            }
          });
    } catch (RejectedExecutionException stopping) {
      queued.set(false);
    }
  }

  /** {@link TargetPresence#estateChanged} for a target that may be null. */
  public void estateChanged(UUID targetId) {
    if (targetId != null) {
      presence.estateChanged(targetId);
    }
  }

  /** Every estate interval: one pass, then every connected target's estate again. */
  @Scheduled(
      every = "{qits.system.targets.estate-interval}",
      delayed = "{qits.system.targets.estate-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    try {
      pass();
      List<UUID> ids =
          QuarkusTransaction.requiringNew()
              .call(() -> Target.<Target>listAll().stream().map(t -> t.id).toList());
      for (UUID id : ids) {
        if (presence.connected(id)) {
          presence.estateChanged(id);
        }
      }
    } catch (RuntimeException e) {
      if (LaunchMode.current() != LaunchMode.TEST) {
        LOG.warnf("The estate tick did not complete: %s", e.toString());
      }
    }
  }

  /** One pass; answers its decisions, the number the tests read. */
  public synchronized List<Decision> pass() {
    Outcome outcome = QuarkusTransaction.requiringNew().call(this::decideAndWrite);
    for (Lease lease : outcome.changed()) {
      events.leaseChanged(lease);
    }
    for (UUID target : outcome.granted()) {
      presence.estateChanged(target);
    }
    presence.probeImageMayHaveChanged();
    return outcome.decisions();
  }

  record Outcome(List<Decision> decisions, List<Lease> changed, Set<UUID> granted) {}

  private Outcome decideAndWrite() {
    // FOR UPDATE, in id order: an owner's end or an inventory of the same lease waits for this pass
    // (or this pass for it, and a lease it ended is then no longer QUEUED and skipped), so a grant
    // can never overwrite an ENDED, nor an ENDED be lost under a grant. See Leases.
    List<Lease> queue =
        Lease.<Lease>find("state = ?1 order by id", LeaseState.QUEUED)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .list();
    if (queue.isEmpty()) {
      return new Outcome(List.of(), List.of(), Set.of());
    }
    List<Target> rows = Target.<Target>listAll();
    Map<UUID, Map<SlotType, Integer>> used = Targets.usedInTransaction();
    List<TargetSlots> slots = new ArrayList<>();
    Map<UUID, Target> byId = new HashMap<>();
    for (Target target : rows) {
      byId.put(target.id, target);
      slots.add(
          new TargetSlots(
              target.id,
              target.name,
              target.slotsTotal,
              target.minimums(),
              used.getOrDefault(target.id, Map.of()),
              presence.connected(target.id),
              target.quarantined(),
              target.signedIn()));
    }
    Map<UUID, Lease> leases = new HashMap<>();
    Map<UUID, WorkVolume> volumes = new HashMap<>();
    List<Request> requests = new ArrayList<>();
    List<Lease> changed = new ArrayList<>();
    for (Lease lease : queue) {
      leases.put(lease.id, lease);
      UUID pinned = lease.pinTargetId;
      if (lease.workKey != null) {
        WorkVolume volume = WorkVolume.findById(new WorkVolume.Key(lease.ownerApp, lease.workKey));
        if (volume != null) {
          if (volume.purgeRequestedAt != null) {
            if (setDetail(lease, VOLUME_PURGING)) {
              changed.add(lease);
            }
            continue;
          }
          volumes.put(lease.id, volume);
          pinned = volume.targetId;
        }
      }
      requests.add(
          new Request(lease.id, lease.slotType, lease.priority, lease.requestedAt, pinned));
    }
    List<Decision> decisions = SlotAllocator.allocate(slots, requests);
    Set<UUID> granted = new LinkedHashSet<>();
    Instant now = Instant.now();
    for (Decision decision : decisions) {
      Lease lease = leases.get(decision.leaseId());
      if (!decision.granted()) {
        if (setDetail(lease, decision.detail())) {
          changed.add(lease);
        }
        continue;
      }
      Target target = byId.get(decision.targetId());
      lease.state = LeaseState.GRANTED;
      lease.targetId = target.id;
      lease.grantedAt = now;
      lease.detail = null;
      if (lease.workKey != null && !volumes.containsKey(lease.id)) {
        WorkVolume volume = new WorkVolume();
        volume.ownerApp = lease.ownerApp;
        volume.workKey = lease.workKey;
        volume.targetId = target.id;
        volume.volumeName = volumeName(lease, target.id);
        volume.createdAt = now;
        volume.persist();
      }
      LOG.infof(
          "Granted %s lease %s (%s of %s) on target %s",
          lease.slotType, lease.id, lease.ownerRef, lease.ownerApp, target.name);
      changed.add(lease);
      granted.add(target.id);
    }
    return new Outcome(decisions, changed, granted);
  }

  /**
   * The work volume's name on {@code targetId}: an adopted volume keeps its own name; a new one is
   * {@code qits-system-runner-work-<workKey>-<id8>}.
   */
  static String volumeName(Lease lease, UUID targetId) {
    var adopt = LeaseSpecs.parse(lease.adopt);
    if (adopt != null && adopt.hasNonNull("volume")) {
      return adopt.get("volume").asText();
    }
    return "qits-system-runner-work-" + lease.workKey + "-" + targetId.toString().substring(0, 8);
  }

  private static boolean setDetail(Lease lease, String detail) {
    if (Objects.equals(lease.detail, detail)) {
      return false;
    }
    lease.detail = detail;
    return true;
  }

  // --- the estate -------------------------------------------------------------------------------

  /**
   * One lease as a target's estate carries it. Spec and adopt are JSON text; {@code workKey} is what
   * the wire's {@code workVolume} carries (the runner derives the volume's name from it).
   */
  public record EstateEntry(
      UUID leaseId,
      SlotType slotType,
      String desired,
      String spec,
      String specHash,
      String workKey,
      String adopt) {}

  /** A target's whole desired state. */
  public record EstateView(List<EstateEntry> leases, List<String> purges) {}

  /**
   * {@code targetId}'s estate: every lease on it that has not ENDED, and the volumes it is to purge.
   * GRANTED, RUNNING and EXITED leases are {@code desired: RUNNING} — the runner never restarts an
   * exited container, and keeping it listed keeps it on the node until its owner ends the lease;
   * ENDING and FAILED ones are {@code ENDED}, which the runner stops and removes.
   */
  public EstateView estateOf(UUID targetId) {
    List<EstateEntry> entries =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<EstateEntry> out = new ArrayList<>();
                  for (Lease lease :
                      Lease.<Lease>find(
                              "targetId = ?1 and state <> ?2 order by grantedAt, id",
                              targetId,
                              LeaseState.ENDED)
                          .list()) {
                    if (lease.state == LeaseState.QUEUED) {
                      continue;
                    }
                    out.add(
                        new EstateEntry(
                            lease.id,
                            lease.slotType,
                            desired(lease.state),
                            lease.spec,
                            lease.specHash,
                            lease.workKey,
                            lease.adopt));
                  }
                  return out;
                });
    return new EstateView(entries, workVolumes.purgesOf(targetId));
  }

  /** The estate's {@code desired} for a lease on a target; see {@link #estateOf}. */
  static String desired(LeaseState state) {
    return state == LeaseState.GRANTED || state == LeaseState.RUNNING || state == LeaseState.EXITED
        ? "RUNNING"
        : "ENDED";
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }
}
