package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.targets.runner.TargetAddresses;
import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@code qits.system.targets.*} key, defaulted in this jar's {@code
 * META-INF/microprofile-config.properties}, which says what each one is for.
 */
@ConfigMapping(prefix = "qits.system.targets")
public interface TargetsConfig {

  /** {@code <application>} → the slot type it may lease ({@code CI}, {@code WORKSPACE}, {@code DESK}). */
  Map<String, String> tenants();

  /** The {@code <environment>-} prefix stripped from a caller's client id. */
  String environment();

  /** {@code ci|workspace|desk} → the prefix a lease's {@code spec.image} must start with. */
  Map<String, String> slotImages();

  /** The login probe's image when no WORKSPACE or DESK lease names one; see {@code ProbeImages}. */
  Optional<String> probeImage();

  /** The platform's public domain; blank or dotless is none. */
  Optional<String> domain();

  /** The wss base a runner's socket url is composed on. */
  String publicUrl();

  /**
   * qits-network-access-service's own base, filled into the runner-toolkit install script's
   * {@code {{NETWORK_ACCESS_URL}}} placeholder (qits-1977); blank is "this deployment has no
   * private network to join". {@link TargetAddresses#networkAccessUrl()} is the value to read —
   * it is blank whenever there is no public domain, even if this key resolves to something, since
   * the key embeds {@code QITS_DOMAIN} the same way {@link #domain()} does.
   */
  Optional<String> networkAccessUrl();

  RegistryMirrors registryMirrors();

  Optional<String> runnerVersionOverride();

  Duration reconnectGrace();

  String commissionReconcileInterval();

  Duration estateInterval();

  Healthcheck healthcheck();

  Dialback dialback();

  Idp idp();

  Docker docker();

  /** Sent to every runner in its {@code ack}. */
  interface RegistryMirrors {
    Optional<List<String>> registryHosts();

    Optional<List<String>> mirrorHosts();

    Optional<List<String>> upstreams();
  }

  interface Healthcheck {
    List<Duration> schedule();

    Duration timeout();

    String sweepInterval();
  }

  /**
   * A target whose CI leases never dial back (qits-1809): {@code threshold} such ends in a row
   * quarantine it, and the {@code dialback} health check fails for {@code cooloff} after the newest.
   */
  interface Dialback {
    int threshold();

    Duration cooloff();
  }

  /** The Docker API's wait for a runner's {@code docker-result} ({@code runner/TargetDocker}). */
  interface Docker {
    /** How long a call waits for its answer. */
    Duration timeout();

    /** An exec waits its own {@code timeoutSeconds} plus this. */
    Duration execGrace();
  }

  interface Idp {
    String url();

    Optional<String> clientId();

    Optional<String> clientSecret();

    Duration patience();

    Duration requestTimeout();
  }
}
