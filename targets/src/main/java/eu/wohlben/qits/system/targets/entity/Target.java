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
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An execution target: one machine, one qits-system-runner, a number of typed slots. */
@Entity
@Table(name = "target")
@EntityListeners(CausationStamp.class)
public class Target extends PanacheEntityBase implements CausedRow {

  @Id public UUID id;

  @Column(nullable = false)
  public String name;

  public String description;

  /** What the runner said in its last {@code hello}: the target's total. */
  @Column(name = "slots_total", nullable = false)
  public int slotsTotal;

  @Column(name = "reserved_ci", nullable = false)
  public int reservedCi;

  @Column(name = "reserved_workspace", nullable = false)
  public int reservedWorkspace;

  @Column(name = "reserved_desk", nullable = false)
  public int reservedDesk;

  /** The {@code hello} capabilities extension as the runner sent it. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String capabilities;

  /** The credential volume the runner actually mounts, from its {@code loginState}. */
  @Column(name = "credential_volume")
  public String credentialVolume;

  @Enumerated(EnumType.STRING)
  @Column(name = "login_claude")
  public LoginValue loginClaude;

  @Enumerated(EnumType.STRING)
  @Column(name = "login_kimi")
  public LoginValue loginKimi;

  @Column(name = "login_checked_at")
  public Instant loginCheckedAt;

  @Column(name = "registration_token_id")
  public String registrationTokenId;

  @Column(name = "registration_token_subject")
  public String registrationTokenSubject;

  @Column(name = "client_id")
  public String clientId;

  @Column(name = "quarantined_at")
  public Instant quarantinedAt;

  @Column(name = "quarantine_reason")
  public String quarantineReason;

  @Column(name = "last_health_check_at")
  public Instant lastHealthCheckAt;

  @Column(name = "last_health_check_ok")
  public Boolean lastHealthCheckOk;

  /** The newest settled health report, {@code {at, ok, detail, requestId, checks[]}}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String health;

  @Column(name = "runner_version")
  public String runnerVersion;

  @Column(name = "registered_at")
  public Instant registeredAt;

  @Column(name = "last_seen_at")
  public Instant lastSeenAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

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

  public boolean registered() {
    return clientId != null;
  }

  public boolean quarantined() {
    return quarantinedAt != null;
  }

  /** Either harness is signed in: the sign-in gate for WORKSPACE and DESK leases. */
  public boolean signedIn() {
    return loginClaude == LoginValue.PRESENT || loginKimi == LoginValue.PRESENT;
  }

  public Map<SlotType, Integer> reservations() {
    Map<SlotType, Integer> reservations = new EnumMap<>(SlotType.class);
    reservations.put(SlotType.CI, reservedCi);
    reservations.put(SlotType.WORKSPACE, reservedWorkspace);
    reservations.put(SlotType.DESK, reservedDesk);
    return reservations;
  }

  public int reservationsSum() {
    return reservedCi + reservedWorkspace + reservedDesk;
  }
}
