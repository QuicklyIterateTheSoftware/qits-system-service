package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WarmImage;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>The image each slot type keeps warm</b> (qits-1692). A slot image released by qits-ci used to
 * be pulled cold by the first lease that named it, which is minutes of a lease spent on a pull. Now
 * the newest released version of each type's slot image is held here, one {@code warm_image} row
 * per type, and every target's estate names the warm images of the types it can ever run ({@link
 * #forTarget}), so its runner pulls them as soon as they are released.
 *
 * <p><b>Which package is whose.</b> {@code qits.system.targets.slot-images.<type>} holds a
 * repository with its registry, {@code registry.qits.<domain>/qits/workspace}; a release's {@code
 * packageName} ({@code qits/workspace}) is that value without its registry host, and the warm
 * reference is the configured value plus {@code :<version>} — the way {@code runner/ProbeImages}
 * composes its pinned image.
 *
 * <p><b>Forward only.</b> Releases arrive out of order on a catch-up, and an older one never
 * replaces a newer: versions are {@code YYYY.MMDD.HHMMSS}, compared part by part as numbers ({@link
 * #compareVersions}).
 *
 * <p>The write is its own transaction like every other in this module; a change pushes the estate
 * of every connected target that can run the type, through {@link TargetPresence#estateChanged},
 * the same path any lease change takes.
 */
@ApplicationScoped
public class WarmImages {

  private static final Logger LOG = Logger.getLogger(WarmImages.class);

  @Inject TargetsConfig config;

  @Inject TargetPresence presence;

  /**
   * A release of {@code packageName} at {@code version}: every slot type whose configured image is
   * that package now keeps {@code <configured image>:<version>} warm, unless it already holds this
   * version or a newer one. Answers the types that moved; their targets' estates are pushed.
   */
  public Set<SlotType> released(String packageName, String version) {
    Map<SlotType, String> repositories = repositoriesFor(config.slotImages(), packageName);
    if (repositories.isEmpty() || version == null || version.isBlank()) {
      return Set.of();
    }
    String released = version.trim();
    Set<SlotType> moved =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Set<SlotType> out = EnumSet.noneOf(SlotType.class);
                  for (Map.Entry<SlotType, String> entry : repositories.entrySet()) {
                    WarmImage row = WarmImage.findById(entry.getKey());
                    if (row != null && compareVersions(released, row.version) <= 0) {
                      continue;
                    }
                    if (row == null) {
                      row = new WarmImage();
                      row.slotType = entry.getKey();
                    }
                    row.packageName = packageName.trim();
                    row.version = released;
                    row.image = entry.getValue() + ":" + released;
                    row.updatedAt = Instant.now();
                    row.persist();
                    out.add(entry.getKey());
                  }
                  return out;
                });
    if (moved.isEmpty()) {
      LOG.debugf("%s %s is not newer than the warm image; nothing moved", packageName, released);
      return moved;
    }
    LOG.infof("%s images now keep %s:%s warm", moved, packageName, released);
    pushTo(moved);
    return moved;
  }

  /** Every slot type's warm image, as the store holds it now. */
  public Map<SlotType, String> current() {
    return QuarkusTransaction.requiringNew().call(WarmImages::currentInTransaction);
  }

  /** {@link #current()} inside the caller's transaction. */
  public static Map<SlotType, String> currentInTransaction() {
    Map<SlotType, String> images = new EnumMap<>(SlotType.class);
    for (WarmImage row : WarmImage.<WarmImage>listAll()) {
      images.put(row.slotType, row.image);
    }
    return images;
  }

  /**
   * The warm images a target's estate carries: one per slot type it can ever run ({@link
   * SlotAllocator#canEverRun}), in slot type order.
   */
  public static List<String> forTarget(
      int total, Map<SlotType, Integer> reservations, Map<SlotType, String> warm) {
    List<String> images = new ArrayList<>();
    for (SlotType type : SlotType.values()) {
      String image = warm.get(type);
      if (image != null && SlotAllocator.canEverRun(total, reservations, type)) {
        images.add(image);
      }
    }
    return images;
  }

  /**
   * The slot types whose configured image is {@code packageName}'s repository, each with that
   * configured value: {@code registry.qits.<domain>/qits/workspace} is {@code qits/workspace}'s.
   */
  static Map<SlotType, String> repositoriesFor(Map<String, String> slotImages, String packageName) {
    Map<SlotType, String> out = new EnumMap<>(SlotType.class);
    if (packageName == null || packageName.isBlank()) {
      return out;
    }
    String wanted = packageName.trim();
    for (SlotType type : SlotType.values()) {
      String configured = slotImages.get(type.key());
      if (configured == null || configured.isBlank()) {
        continue;
      }
      String image = configured.trim();
      int slash = image.indexOf('/');
      if (slash > 0 && image.substring(slash + 1).equals(wanted)) {
        out.put(type, image);
      }
    }
    return out;
  }

  /**
   * {@code YYYY.MMDD.HHMMSS} order: part by part, as numbers where both are, as text otherwise; with
   * every shared part equal the longer version is the newer. {@code 2026.1027.93000} is older than
   * {@code 2026.1027.193351}, which a text comparison gets wrong.
   */
  public static int compareVersions(String a, String b) {
    String[] left = a.trim().split("\\.");
    String[] right = b.trim().split("\\.");
    for (int i = 0; i < Math.min(left.length, right.length); i++) {
      int order = comparePart(left[i], right[i]);
      if (order != 0) {
        return order;
      }
    }
    return Integer.compare(left.length, right.length);
  }

  private static int comparePart(String a, String b) {
    if (a.matches("\\d+") && b.matches("\\d+")) {
      return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
    }
    return a.compareTo(b);
  }

  private void pushTo(Set<SlotType> moved) {
    List<UUID> affected =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<UUID> ids = new ArrayList<>();
                  for (Target target : Target.<Target>listAll()) {
                    for (SlotType type : moved) {
                      if (SlotAllocator.canEverRun(
                          target.slotsTotal, target.reservations(), type)) {
                        ids.add(target.id);
                        break;
                      }
                    }
                  }
                  return ids;
                });
    for (UUID id : affected) {
      if (presence.connected(id)) {
        presence.estateChanged(id);
      }
    }
  }
}
