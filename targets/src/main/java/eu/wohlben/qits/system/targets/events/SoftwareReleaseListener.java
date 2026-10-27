package eu.wohlben.qits.system.targets.events;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.system.targets.control.WarmImages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A slot image was released: keep it warm</b> (qits-1692). Consumes qits-ci's {@code
 * SoftwareRelease} — one per declared artifact of a green release pipeline — and hands a {@code
 * docker} one to {@link WarmImages}, which moves a slot type's warm image when the package is that
 * type's configured slot image and the version is newer. Every other package, and every other
 * {@code packageType}, is settled with nothing done.
 *
 * <p><b>Durable</b>, after qits-maintenance-service's {@code bus/SoftwareReleaseListener}: a
 * release announced while this process was restarting is caught up from the log, so a target is
 * never left warming an image two releases old. Catch-up delivers out of order, which {@link
 * WarmImages} absorbs by moving forward only.
 *
 * <p><b>The write is not the claim's transaction.</b> The claim lives in the {@code eventstream}
 * datasource and the warm image in {@code system}; {@link WarmImages} writes in its own
 * transaction, as every write in this module does. A claim that then fails to commit offers the
 * release again, and the second write is a no-op: the version is no longer newer.
 *
 * <p><b>Poison is swallowed.</b> A payload that will not parse, or that names no package or no
 * version, fails identically on every offer; it is a WARN and a settled event, never a throw that
 * would hold this consumer's watermark behind it for ever.
 */
@ApplicationScoped
public class SoftwareReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(SoftwareReleaseListener.class);

  /**
   * This consumption's storage key: the {@code consumer_watermark} and every {@code consumed_event}
   * row. <b>Never change it</b>: a new value is a new consumer starting at the head of the log.
   */
  static final String CONSUMER_ID = "system.targets.warm-images";

  /** The one event this listener wants: {@code SoftwareRelease}'s signature. */
  static final String SIGNATURE = "SoftwareRelease";

  /** qits-ci's {@code packageType} for an image. */
  static final String DOCKER = "docker";

  /**
   * The {@code SoftwareRelease} payload fields read here, bound by {@link CanonicalJson} — a copy
   * of qits-ci's wire shape, as qits-maintenance-service keeps one, rather than a dependency on its
   * module. Unknown fields are ignored. Registered for native in {@code
   * control/EventWireReflection}.
   */
  public record SoftwareReleasePayload(
      String repository, String version, String packageType, String packageName) {}

  @Inject WarmImages warmImages;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /** Only a docker release is claimed; a predicate on the frame's text alone, cheap and pure. */
  @Override
  public boolean selects(EventFrame frame) {
    return frame.payload() != null && frame.payload().contains("\"" + DOCKER + "\"");
  }

  @Override
  public void onFrame(EventFrame frame) {
    SoftwareReleasePayload release;
    try {
      release = CanonicalJson.payloadTo(frame.payload(), SoftwareReleasePayload.class);
    } catch (RuntimeException e) {
      LOG.warnf(
          "%s %s has a payload that does not parse; it is skipped: %s",
          frame.name(), frame.id(), e.toString());
      return;
    }
    released(release);
  }

  /** One release, as decoded: what the tests drive directly. */
  public void released(SoftwareReleasePayload release) {
    if (release == null || !DOCKER.equals(release.packageType())) {
      return;
    }
    if (release.packageName() == null
        || release.packageName().isBlank()
        || release.version() == null
        || release.version().isBlank()) {
      LOG.warnf("A docker SoftwareRelease names no package or no version; it is skipped");
      return;
    }
    warmImages.released(release.packageName(), release.version());
  }
}
