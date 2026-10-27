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

/**
 * The image one slot type keeps warm (qits-1692): the newest released version of its slot image,
 * as a full reference ({@code registry.qits.<domain>/<repository>:<version>}). One row per type,
 * moved forward only by {@code control/WarmImages}.
 */
@Entity
@Table(name = "warm_image")
@EntityListeners(CausationStamp.class)
public class WarmImage extends PanacheEntityBase implements CausedRow {

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "slot_type", nullable = false)
  public SlotType slotType;

  /** The released package, as qits-ci names it: {@code qits/workspace}. */
  @Column(name = "package_name", nullable = false)
  public String packageName;

  /** Its version, {@code YYYY.MMDD.HHMMSS}. */
  @Column(nullable = false)
  public String version;

  /** The full reference the estate carries. */
  @Column(nullable = false)
  public String image;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

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
