package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.system.targets.entity.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.regex.Pattern;

/**
 * The commands an operator runs on a target's machine to sign its credential volume in — the
 * pattern of qits-workspaces-service's {@code RunnerLoginCommand}. The platform never sees or moves
 * a login secret: the operator runs the harness's own sign-in against the volume the runner mounts,
 * with the image the runner's login probe runs.
 *
 * <pre>
 * docker run --rm -it --user 1000 --entrypoint claude -v &lt;volume&gt;:/claude-home \
 *   -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude &lt;probeImage&gt;
 * </pre>
 *
 * and the same with {@code --entrypoint kimi … login} (one line each).
 *
 * <p><b>The volume is the one the runner reported</b> in its last {@code loginState} ({@code
 * target.credential_volume}), so the command can never name another volume; it is spliced in only
 * when it is a plain docker volume name. <b>No command is rendered until a {@code loginState} has
 * arrived on the target's current connection</b> ({@link TargetRegistry#loginStateSeen}): before it,
 * this service does not know the probe image is on that machine.
 */
@ApplicationScoped
public class TargetLoginCommand {

  /** The workspace image's agent user, whose home the credential volume is. */
  static final String USER = "1000";

  static final String HOME = "/claude-home";

  private static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

  private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,511}");

  @Inject TargetsConfig config;

  @Inject TargetRegistry registry;

  /** {@code loginCommand}, or null until a {@code loginState} arrived on the current connection. */
  public String claude(Target target) {
    return render(target, "claude", "");
  }

  /** {@code kimiLoginCommand}, under the same rule. */
  public String kimi(Target target) {
    return render(target, "kimi", " login");
  }

  private String render(Target target, String entrypoint, String tail) {
    if (target == null || !registry.loginStateSeen(target.id)) {
      return null;
    }
    return command(target.credentialVolume, config.probeImage(), entrypoint, tail);
  }

  /** The command's one shape; null when the volume or the image is not shell-safe. */
  static String command(String volume, String image, String entrypoint, String tail) {
    if (volume == null
        || !VOLUME.matcher(volume).matches()
        || image == null
        || !IMAGE.matcher(image).matches()) {
      return null;
    }
    return "docker run --rm -it --user "
        + USER
        + " --entrypoint "
        + entrypoint
        + " -v "
        + volume
        + ":"
        + HOME
        + " -e HOME="
        + HOME
        + " -e CLAUDE_CONFIG_DIR="
        + HOME
        + "/.claude "
        + image
        + tail;
  }
}
