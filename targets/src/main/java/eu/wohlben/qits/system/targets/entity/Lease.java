package eu.wohlben.qits.system.targets.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One owner's claim on one slot of one type; see {@link LeaseState} for its life. */
@Entity
@Table(name = "lease")
@EntityListeners(CausationStamp.class)
public class Lease extends PanacheEntityBase implements CausedRow {

  @Id public UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "slot_type", nullable = false)
  public SlotType slotType;

  /** The calling service's application, from its bearer; never from the body. */
  @Column(name = "owner_app", nullable = false)
  public String ownerApp;

  @Column(name = "owner_ref", nullable = false)
  public String ownerRef;

  @Column(name = "project_id")
  public UUID projectId;

  @Column(name = "work_key")
  public String workKey;

  @Column(name = "pin_target_id")
  public UUID pinTargetId;

  @Column(name = "target_id")
  public UUID targetId;

  @Column(nullable = false)
  public int priority;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public LeaseState state;

  /** {@code LeaseSpec{image, env, user, limits}}, relayed to the runner as it was given. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  public String spec;

  @Column(name = "spec_hash")
  public String specHash;

  /** {@code {container, volume}} of a retired runner's work, or null. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String adopt;

  @Column(name = "exit_code")
  public Integer exitCode;

  /**
   * Why a lease waits (QUEUED), what it is doing (GRANTED: the image it is pulling), or why it
   * ended (EXITED, FAILED); else null.
   */
  public String detail;

  @Column(name = "requested_at", nullable = false)
  public Instant requestedAt;

  @Column(name = "granted_at")
  public Instant grantedAt;

  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "ended_at")
  public Instant endedAt;

  /**
   * Processes the OOM killer took in the lease container's cgroup ({@code memory.events}' {@code
   * oom_kill}), as the runner last reported it (qits-1818); never lowered, kept after the lease
   * exits. Null until a runner reported one.
   */
  @Column(name = "oom_kills")
  public Long oomKills;

  /** Times the lease hit its own memory limit ({@code memory.events}' {@code oom}); never lowered. */
  @Column(name = "oom_events")
  public Long oomEvents;

  /** The lease container's peak memory ({@code memory.peak}), never lowered; else null. */
  @Column(name = "memory_peak_bytes")
  public Long memoryPeakBytes;

  /** docker's {@code State.OOMKilled} of the container of an exited lease; else null. */
  @Column(name = "oom_killed")
  public Boolean oomKilled;

  /**
   * The lease container's {@code --dns} servers in order, as its runner last reported them
   * (qits-2091): a JSON array of strings, {@code []} for the node's own resolvers or no container.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  public String resolvers = "[]";

  /**
   * Whether the runner says the node now has a resolver — the tunnel's or the private edge's — that
   * this lease's container lacks first (qits-2091), as last reported; a refresh ({@link
   * #generation}) is what cures it, and the runner's next report after it says so.
   */
  @Column(name = "resolvers_stale", nullable = false)
  public boolean resolversStale;

  /**
   * The owner's refresh counter (qits-2091), carried on every estate entry: the runner recreates
   * the container, keeping its work volume, when it differs from the container's label. 0 is none.
   */
  @Column(nullable = false)
  public long generation;

  /** When it last went ENDING; {@code LeaseEndingDeadline} counts from here. */
  @Column(name = "ending_since")
  public Instant endingSince;

  /** When {@code LeaseEndingDeadline} re-sent its target's estate, once per ENDING; else null. */
  @Column(name = "ending_nudged_at")
  public Instant endingNudgedAt;

  @Column(name = "causation_id")
  public UUID causationId;

  /** {@link #oomScope(Long, Long)} of this row. */
  public String oomScope() {
    return oomScope(oomKills, oomEvents);
  }

  /**
   * Whose memory ran out when a lease lost processes to an OOM killer (qits-1818), the one spelling
   * the lease view and {@code TargetLeaseChanged} share: null when nothing was killed ({@code
   * oomKills} null or 0); {@code LEASE} when the lease hit its own limit ({@code oomEvents} above 0);
   * else {@code NODE} — the node or the kernel ran out, and the killer picked a process in this lease.
   */
  public static String oomScope(Long oomKills, Long oomEvents) {
    if (oomKills == null || oomKills <= 0) {
      return null;
    }
    return oomEvents != null && oomEvents > 0 ? "LEASE" : "NODE";
  }

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
