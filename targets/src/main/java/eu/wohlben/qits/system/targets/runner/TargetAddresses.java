package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every address a target's runner is handed, composed from the platform's public domain — the
 * pattern of qits-projects-service's {@code DeskRunnerAddresses}. A runner lives outside the swarm
 * and reaches this platform only through its edge: {@code https://system.qits.<d>} for the install
 * script and the register door, {@code qits.system.targets.public-url} ({@code
 * wss://system.qits.<d>}) plus {@link SystemRunnerProtocol#SOCKET_PATH} for its socket, {@code
 * https://idp.qits.<d>/idp/token} for its bearer, {@code registry.qits.<d>} for its image.
 *
 * <p><b>A domain without a dot is no domain</b>: every method then refuses 503 {@link
 * TargetRefusal#RUNNER_PLANE_UNCONFIGURED}, which the create door, the register door and {@code
 * install.sh} answer before anything is minted.
 */
@ApplicationScoped
public class TargetAddresses {

  /** The audience every token this platform mints carries, and the one a runner asks for. */
  public static final String AUDIENCE = "qits-platform";

  static final String PLATFORM_PROJECT = "qits";

  @Inject TargetsConfig config;

  public String serviceBase() {
    return "https://" + host("system");
  }

  public String socketUrl() {
    requireConfigured();
    return config.publicUrl().replaceAll("/+$", "") + SystemRunnerProtocol.SOCKET_PATH;
  }

  public String tokenUrl() {
    return "https://" + host("idp") + "/idp/token";
  }

  public String audience() {
    return AUDIENCE;
  }

  public String registryHost() {
    return host("registry");
  }

  public String mirrorHost() {
    return host("mirror");
  }

  /** {@code registry.qits.<d>/qits/qits-system-runner:<version>}: an upgrade's image. */
  public String runnerImage(String version) {
    return registryHost() + "/" + SystemRunnerProtocol.IMAGE_REPOSITORY + ":" + version;
  }

  public boolean configured() {
    return publicDomain().isPresent();
  }

  /** 503 {@link TargetRefusal#RUNNER_PLANE_UNCONFIGURED} unless a public domain is configured. */
  public void requireConfigured() {
    host("system");
  }

  /**
   * {@code registryMirrors} for the runner's {@code ack}: every spelling of this platform's registry
   * and mirror a step may name, mapped to the edge's, and each upstream registry to its path under
   * the mirror. Empty when the deployment has no public domain.
   */
  public Map<String, String> registryMirrors() {
    Map<String, String> mirrors = new LinkedHashMap<>();
    if (!configured()) {
      return mirrors;
    }
    String registry = registryHost();
    String mirror = mirrorHost();
    for (String host : config.registryMirrors().registryHosts().orElse(List.of())) {
      put(mirrors, host, registry);
    }
    for (String host : config.registryMirrors().mirrorHosts().orElse(List.of())) {
      put(mirrors, host, mirror);
    }
    for (String pair : config.registryMirrors().upstreams().orElse(List.of())) {
      int split = pair.indexOf('=');
      if (split <= 0) {
        continue;
      }
      String path = pair.substring(split + 1).trim();
      put(mirrors, pair.substring(0, split), mirror + (path.startsWith("/") ? path : "/" + path));
    }
    return mirrors;
  }

  private static void put(Map<String, String> mirrors, String host, String target) {
    if (host == null || host.isBlank()) {
      return;
    }
    String folded = host.trim().toLowerCase(Locale.ROOT);
    if (!folded.equals(target)) {
      mirrors.putIfAbsent(folded, target);
    }
  }

  private String host(String app) {
    return app
        + "."
        + PLATFORM_PROJECT
        + "."
        + publicDomain()
            .orElseThrow(
                () ->
                    new TargetRefusal(
                        503,
                        TargetRefusal.RUNNER_PLANE_UNCONFIGURED,
                        "qits-system knows no public domain (QITS_DOMAIN is '"
                            + config.domain().orElse("")
                            + "'), so it has no address to give a target's runner; set"
                            + " QITS_DOMAIN to the platform's dotted public domain"));
  }

  Optional<String> publicDomain() {
    return config
        .domain()
        .map(value -> value.trim().toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", ""))
        .filter(value -> value.indexOf('.') > 0);
  }
}
