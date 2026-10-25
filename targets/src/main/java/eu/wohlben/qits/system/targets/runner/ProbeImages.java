package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>Which image a target's login probe runs</b> — {@code Ack.probeImage}, and the image the login
 * commands name. The probe must run an image that exists on the registry, and the workspace image
 * is published under versioned tags only, so it is never a configured {@code :latest}. In order:
 *
 * <ol>
 *   <li>the image of the newest WORKSPACE lease that has not ENDED (QUEUED counts: a queued lease
 *       already carries the owner's real, pinned image);
 *   <li>else the newest DESK lease's;
 *   <li>else {@code qits.system.targets.probe-image}, when a deployment set it;
 *   <li>else the pinned workspace image, {@code qits.system.targets.slot-images.workspace} at
 *       {@link WorkspaceImage#VERSION} — the released {@code qits/workspace} tag this reactor
 *       depends on, so a brand-new target with no lease yet can still sign in instead of sitting
 *       at UNKNOWN until its first one.
 * </ol>
 *
 * The runner reads the probe image from every {@code ack} it is sent and probes at once, so when
 * the choice changes {@link TargetRegistry} acks every serving connection again.
 */
@ApplicationScoped
public class ProbeImages {

  private static final Logger LOG = Logger.getLogger(ProbeImages.class);

  @Inject Leases leases;

  @Inject TargetsConfig config;

  /** The current choice, or null; a lease read that failed falls through to the configured one. */
  public String current() {
    Optional<String> workspace = Optional.empty();
    Optional<String> desk = Optional.empty();
    try {
      workspace = leases.newestImage(SlotType.WORKSPACE);
      if (workspace.isEmpty()) {
        desk = leases.newestImage(SlotType.DESK);
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the leases' images for the login probe: %s", e.toString());
    }
    return choose(workspace, desk, config.probeImage(), pin()).orElse(null);
  }

  private Optional<String> pin() {
    String workspacePrefix = config.slotImages().get("workspace");
    return workspacePrefix == null || workspacePrefix.isBlank()
        ? Optional.empty()
        : Optional.of(workspacePrefix + ":" + WorkspaceImage.VERSION);
  }

  /** The order, pure. */
  static Optional<String> choose(
      Optional<String> workspace,
      Optional<String> desk,
      Optional<String> configured,
      Optional<String> pin) {
    return set(workspace).or(() -> set(desk)).or(() -> set(configured)).or(() -> set(pin));
  }

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }
}
