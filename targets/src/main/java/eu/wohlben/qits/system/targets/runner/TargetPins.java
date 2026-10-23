package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Which {@code qits-system-runner} version a target's runner is meant to be: the image an install
 * script starts, and the version every {@code hello} is compared with (a runner of any other is sent
 * {@code upgrade}). It is {@code qits.system.targets.runner-version-override} when that is set
 * (blank is unset), else {@link SystemRunnerBinary#VERSION} — the version of the protocol jar the
 * root pom pins ({@code qits.system-runner-protocol.version}), which is by construction the tag of
 * the image the same release published. A pin moves by a gated bump of that property.
 */
@ApplicationScoped
public class TargetPins {

  @Inject TargetsConfig config;

  /** The pinned runner version. Never empty once the protocol jar is on the classpath. */
  public Optional<String> version() {
    return config
        .runnerVersionOverride()
        .map(String::trim)
        .filter(v -> !v.isEmpty())
        .or(() -> Optional.of(SystemRunnerBinary.VERSION));
  }
}
