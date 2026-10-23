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

  public String detail;

  @Column(name = "requested_at", nullable = false)
  public Instant requestedAt;

  @Column(name = "granted_at")
  public Instant grantedAt;

  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "ended_at")
  public Instant endedAt;

  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
