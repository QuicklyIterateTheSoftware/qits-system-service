package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The work-volume rows: a purge an owner asks for, and the runner's report that it happened.
 *
 * <p>A purge is a mark ({@code purge_requested_at}), carried in the target's estate as {@code
 * purges[volumeName]}; the row goes once the runner's {@code inventory} no longer lists the volume.
 * While the mark stands, a new lease for the same work waits rather than being granted onto a
 * volume that is about to disappear.
 */
@ApplicationScoped
public class WorkVolumes {

  private static final Logger LOG = Logger.getLogger(WorkVolumes.class);

  @Inject TargetPresence presence;

  /**
   * Marks {@code ownerApp}'s volume for {@code workKey} for purging.
   *
   * @throws TargetRefusal 404 when there is none, 409 {@link TargetRefusal#WORK_VOLUME_IN_USE}
   *     while a lease that is not ENDED uses it
   */
  public WorkVolume purge(String ownerApp, String workKey) {
    WorkVolume marked =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  WorkVolume volume = WorkVolume.findById(new WorkVolume.Key(ownerApp, workKey));
                  if (volume == null) {
                    throw TargetRefusal.notFound(ownerApp + " has no work volume " + workKey);
                  }
                  long live =
                      Lease.count(
                          "ownerApp = ?1 and workKey = ?2 and state <> ?3",
                          ownerApp,
                          workKey,
                          LeaseState.ENDED);
                  if (live > 0) {
                    throw TargetRefusal.conflict(
                        TargetRefusal.WORK_VOLUME_IN_USE,
                        "Work volume " + workKey + " is used by a lease that has not ended");
                  }
                  if (volume.purgeRequestedAt == null) {
                    volume.purgeRequestedAt = Instant.now();
                  }
                  return volume;
                });
    LOG.infof(
        "%s asked for work volume %s (%s) to be purged on target %s",
        ownerApp, workKey, marked.volumeName, marked.targetId);
    presence.estateChanged(marked.targetId);
    return marked;
  }

  /** The volume names whose purge {@code targetId}'s estate carries. */
  public List<String> purgesOf(UUID targetId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                WorkVolume.<WorkVolume>find(
                        "targetId = ?1 and purgeRequestedAt is not null order by volumeName",
                        targetId)
                    .list()
                    .stream()
                    .map(v -> v.volumeName)
                    .toList());
  }

  /**
   * The runner listed the work volumes it holds: every purge-marked row of this target whose volume
   * is no longer among them is gone. A report with no volume list says nothing.
   */
  public int onVolumes(UUID targetId, List<String> volumes) {
    if (volumes == null) {
      return 0;
    }
    Set<String> held = Set.copyOf(volumes);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              int reaped = 0;
              for (WorkVolume volume :
                  WorkVolume.<WorkVolume>find(
                          "targetId = ?1 and purgeRequestedAt is not null", targetId)
                      .list()) {
                if (!held.contains(volume.volumeName)) {
                  LOG.infof("Work volume %s is purged from target %s", volume.volumeName, targetId);
                  volume.delete();
                  reaped++;
                }
              }
              return reaped;
            });
  }
}
