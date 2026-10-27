package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.install.InstallScript;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The install line a target is declared with and the generic {@code install.sh} it pipes into
 * {@code sh}, both rendered by qits-runner-toolkit's {@link InstallScript} from the toolkit's own
 * template — qits-workspaces-service's {@code WorkspaceRunnerInstallScript}. There is no copy of the
 * template here.
 *
 * <p>The page passes through three of the runner's own variables an operator may add to the line:
 * {@code QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME}, {@code _M2_VOLUME} and {@code _PNPM_VOLUME} (on the
 * platform host {@code qits_shared_dot_claude}, {@code qits_shared_m2}, {@code qits_shared_pnpm}).
 *
 * <p><b>The identity is the runner's, built the runner's way</b>: {@link SystemRunnerProtocol}'s
 * roots in {@link RunnerIdentity}'s order, at the pinned version ({@link TargetPins}), exactly as the
 * runner's own identity is — so the variables the line sets and the names the script creates are
 * the ones the runner reads.
 */
@ApplicationScoped
public class TargetInstallScript {

  /** Where {@code install.sh} is served, under this service's public origin. */
  public static final String PATH = "/system/api/targets/install.sh";

  static final InstallScript.InstallPage PAGE =
      new InstallScript.InstallPage(
          "the System UI's Targets page", List.of("CREDENTIAL_VOLUME", "M2_VOLUME", "PNPM_VOLUME"));

  @Inject TargetAddresses addresses;

  @Inject TargetPins pins;

  /** The runner's identity at {@code version}, built the runner's way. */
  public static RunnerIdentity identity(String version) {
    return new RunnerIdentity(
        SystemRunnerProtocol.KIND,
        SystemRunnerProtocol.ENV_PREFIX,
        SystemRunnerProtocol.LABEL_ROOT,
        SystemRunnerProtocol.NAME_PREFIX,
        SystemRunnerProtocol.IMAGE_REPOSITORY,
        SystemRunnerProtocol.STATE_DIR,
        SystemRunnerProtocol.REGISTER_PATH,
        SystemRunnerProtocol.SERVICE_NAME,
        version,
        SystemRunnerProtocol.CAPABILITY_VERSION,
        SystemRunnerProtocol.VOCABULARY,
        SystemRunnerProtocol.WORK_LABEL_SUFFIX);
  }

  /** 503 unless both renderings would succeed now; asked before anything is minted. */
  public void requireRenderable() {
    addresses.requireConfigured();
    script();
  }

  public String script() {
    try {
      return InstallScript.script(identity(version()), PAGE, addresses.registryHost());
    } catch (IllegalStateException unrenderable) {
      throw new TargetRefusal(503, null, unrenderable.getMessage());
    }
  }

  /**
   * The one line that installs {@code target}'s runner, carrying {@code registrationToken}; its
   * slots are the reported total, or the reservations' sum when that is more.
   */
  public String line(Target target, String registrationToken) {
    return line(target, registrationToken, defaultSlots(target));
  }

  /** The same line, with {@code slots} as the runner's total. */
  public String line(Target target, String registrationToken, int slots) {
    try {
      return InstallScript.line(
          identity(version()),
          addresses.serviceBase(),
          PATH,
          target.id.toString(),
          registrationToken,
          slots);
    } catch (IllegalStateException unrenderable) {
      throw new TargetRefusal(503, null, unrenderable.getMessage());
    }
  }

  /** The total an install line carries when the operator names none. */
  public static int defaultSlots(Target target) {
    return Math.max(target.slotsTotal, target.reservationsSum());
  }

  public static void requireCarriable(String registrationToken) {
    InstallScript.requireCarriable(registrationToken);
  }

  private String version() {
    return pins.version()
        .orElseThrow(
            () ->
                new TargetRefusal(
                    503,
                    null,
                    "No qits-system-runner version is pinned, so there is no runner image to"
                        + " install"));
  }
}
