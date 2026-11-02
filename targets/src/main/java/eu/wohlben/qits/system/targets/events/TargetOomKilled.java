package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A target's node lost processes to an OOM killer (qits-1818): the counter its runner reports rose —
 * {@code nodeOomKills}, the node's cgroup tree's own, or {@code kernelOomKills}, the physical host's,
 * when the node has none. {@code delta} is by how much that counter rose since the last inventory,
 * {@code projectIds} the distinct projects of the leases occupying the target (GRANTED, RUNNING,
 * ENDING) at that moment — the ones whose work may have been hit. A runner's first report sets the
 * baseline and publishes nothing; a counter that went down (the host rebooted) is stored as the new
 * baseline, also without an event.
 *
 * <p>{@code targetName}, not {@code name}: a component named {@code name} would shadow {@link
 * QitsEvent#name()} — see {@link TargetConnectionChanged}.
 */
public record TargetOomKilled(
    UUID eventId,
    UUID targetId,
    String targetName,
    Long nodeOomKills,
    Long kernelOomKills,
    long delta,
    List<UUID> projectIds,
    Instant occurredAt)
    implements QitsEvent {

  public TargetOomKilled {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public TargetOomKilled(
      UUID targetId,
      String targetName,
      Long nodeOomKills,
      Long kernelOomKills,
      long delta,
      List<UUID> projectIds,
      Instant occurredAt) {
    this(null, targetId, targetName, nodeOomKills, kernelOomKills, delta, projectIds, occurredAt);
  }
}
