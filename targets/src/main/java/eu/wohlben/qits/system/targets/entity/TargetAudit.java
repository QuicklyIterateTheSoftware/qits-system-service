package eu.wohlben.qits.system.targets.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing done to a target or a lease beyond its owner's own doors: an operator's lease end
 * ({@code LEASE_END}), the ENDING deadline ({@code LEASE_ENDING_DEADLINE}), and the Docker API's
 * operations ({@code DOCKER_<OP>}). Written only by {@code control/TargetAuditLog}, never updated.
 */
@Entity
@Table(name = "target_audit")
public class TargetAudit extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false)
  public Instant at;

  /** The caller's principal (its token subject), or {@code qits-system} for this service. */
  @Column(nullable = false)
  public String actor;

  /** The caller's {@code qits:*} groups, comma-joined; empty for this service. */
  @Column(nullable = false)
  public String roles;

  /** Null for a lease with no target. */
  @Column(name = "target_id")
  public UUID targetId;

  @Column(name = "lease_id")
  public UUID leaseId;

  @Column(nullable = false)
  public String operation;

  public String resource;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String args;

  @Column(nullable = false)
  public String outcome;

  public String detail;
}
