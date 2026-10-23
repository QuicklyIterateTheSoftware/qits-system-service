package eu.wohlben.qits.system.targets.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The volume a lease's {@code /workspace} lives on, kept across the leases of one piece of work
 * ({@code ws-<rowId>}, {@code desk-<deskId>}). It lives on exactly one target, so a lease whose
 * work key has one is pinned there.
 */
@Entity
@Table(name = "work_volume")
@IdClass(WorkVolume.Key.class)
public class WorkVolume extends PanacheEntityBase {

  @Id
  @Column(name = "owner_app", nullable = false)
  public String ownerApp;

  @Id
  @Column(name = "work_key", nullable = false)
  public String workKey;

  @Column(name = "target_id", nullable = false)
  public UUID targetId;

  @Column(name = "volume_name", nullable = false)
  public String volumeName;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "purge_requested_at")
  public Instant purgeRequestedAt;

  /** The composite key. */
  public static class Key implements Serializable {
    private static final long serialVersionUID = 1L;

    public String ownerApp;
    public String workKey;

    public Key() {}

    public Key(String ownerApp, String workKey) {
      this.ownerApp = ownerApp;
      this.workKey = workKey;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(ownerApp, key.ownerApp)
          && Objects.equals(workKey, key.workKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ownerApp, workKey);
    }
  }
}
