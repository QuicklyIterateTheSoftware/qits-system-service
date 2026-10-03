package eu.wohlben.qits.system.startup;

import eu.wohlben.qits.system.docker.DockerCli;
import eu.wohlben.qits.system.docker.DockerClientConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Writes the docker CLI's {@code config.json} from this application's OWN idp client (qits-879).
 *
 * <p>qits-deployments provisions the client ({@code idp:client}, id {@code dev-qits-system}) and
 * injects its id and secret; this turns them into one Basic {@code auths} entry per pull host under
 * {@code qits.system.docker.config-dir}. The hosts and the format are {@link DockerClientConfig}'s.
 *
 * <p><b>NOT A STARTUP OBSERVER OF ITS OWN, ON PURPOSE.</b> The file has to exist before {@link
 * TerminalBootSweep} pulls glances, and two {@code @Observes StartupEvent} methods are ordered only
 * by an {@code @Priority} each of them has to remember. So the sweep injects this bean and calls
 * {@link #write()} as the first statement of its own observer — the ordering is a line of code, not
 * a number two classes must agree on.
 *
 * <p><b>NEVER FATAL, NEVER LOGS THE SECRET.</b> A failed write costs the glances pull a 401 the
 * operator can read on the terminal; it must not cost the boot. The log names the client id and
 * the hosts, which are not secrets.
 */
@ApplicationScoped
public class DockerCredentialWriter {

  private static final Logger LOG = Logger.getLogger(DockerCredentialWriter.class);

  @ConfigProperty(name = "qits.system.docker.credential.client-id")
  Optional<String> clientId;

  @ConfigProperty(name = "qits.system.docker.credential.client-secret")
  Optional<String> clientSecret;

  @ConfigProperty(name = "qits.system.glances.image-repo")
  String glancesRepo;

  @Inject DockerCli docker;

  /**
   * Write the file when both halves of the credential are set; otherwise leave everything alone.
   *
   * @return whether a file was written
   */
  public boolean write() {
    if (clientId.map(String::isBlank).orElse(true)
        || clientSecret.map(String::isBlank).orElse(true)) {
      LOG.debug(
          "No idp client injected for this application; docker keeps the inherited DOCKER_CONFIG");
      return false;
    }
    try {
      List<String> hosts =
          DockerClientConfig.writeIfConfigured(
              docker.configDir(), glancesRepo, clientId, clientSecret);
      if (hosts.isEmpty()) {
        LOG.warnf(
            "The glances image %s names no registry host, so no docker credential was written",
            glancesRepo);
        return false;
      }
      LOG.infof(
          "Docker credential for client %s written to %s for %s",
          clientId.get().trim(), docker.configDir(), String.join(", ", hosts));
      return true;
    } catch (Exception e) {
      // The exception's message is a path or an errno, never the content — but name only its type
      // and message, never anything this method holds.
      LOG.warnf(
          "Could not write the docker credential to %s (%s: %s). Pulls fall back to the inherited"
              + " DOCKER_CONFIG.",
          docker.configDir(), e.getClass().getSimpleName(), e.getMessage());
      return false;
    }
  }
}
