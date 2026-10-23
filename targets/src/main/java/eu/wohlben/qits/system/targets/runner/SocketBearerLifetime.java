package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the target runner socket open past the bearer it was opened with — copied from
 * qits-workspaces-service's {@code runnerhost/SocketBearerLifetime} (qits-ci's qits-545 original).
 * quarkus-oidc stamps every identity it builds from a bearer with {@code
 * quarkus.identity.expire-time}, and websockets-next closes the connection "Authentication expired"
 * when it passes; a runner's token lives an hour, so without this every runner lost its socket an
 * hour after each connect. The socket is authenticated at the upgrade and nowhere after it: a runner
 * is cut off by its target's deletion ({@code retire} and a 1008 close), and refused at its next
 * dial by the missing row. So the attribute is removed, for this one path only.
 */
@ApplicationScoped
public class SocketBearerLifetime implements SecurityIdentityAugmentor {

  static final String EXPIRE_TIME = "quarkus.identity.expire-time";

  static final Set<String> SOCKET_PATHS = Set.of(SystemRunnerProtocol.SOCKET_PATH);

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity,
      AuthenticationRequestContext context,
      Map<String, Object> attributes) {
    RoutingContext request = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String path = request == null ? null : request.normalizedPath();
    return Uni.createFrom().item(forPath(identity, path));
  }

  static SecurityIdentity forPath(SecurityIdentity identity, String path) {
    if (path == null
        || !SOCKET_PATHS.contains(path)
        || identity.isAnonymous()
        || identity.getAttribute(EXPIRE_TIME) == null) {
      return identity;
    }
    Map<String, Object> kept = new HashMap<>(identity.getAttributes());
    kept.remove(EXPIRE_TIME);
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(identity.getPrincipal())
        .addRoles(identity.getRoles())
        .addCredentials(identity.getCredentials())
        .addPermissions(identity.getPermissions())
        .addAttributes(kept)
        .addPermissionChecker(identity::checkPermission)
        .build();
  }
}
