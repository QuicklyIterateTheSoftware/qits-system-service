package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.system.targets.entity.SlotType;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;

/**
 * <b>Who a lease belongs to</b> — never the body's say. The caller's service-client application is
 * its bearer's client id (qits-idp sets a {@code client_credentials} token's {@code sub} to it)
 * without the deployer's {@code <environment>-} prefix: {@code dev-qits-ci} is {@code qits-ci}. A
 * caller behind the forward-auth headers is named by its principal, the same string.
 *
 * <p>{@code qits.system.targets.tenants.<app>=<TYPE>} decides which slot type each application may
 * lease. Any other caller is 403 {@link TargetRefusal#NOT_A_TENANT}; a tenant asking for another type
 * is 403 {@link TargetRefusal#SLOT_TYPE_NOT_YOURS}.
 */
@ApplicationScoped
public class OwnerIdentity {

  @Inject TargetsConfig config;

  /** The caller's client id: the bearer's {@code sub}, else its principal name; null for nobody. */
  public String clientId(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      return null;
    }
    Optional<String> subject =
        MachineIdentity.isMachine(identity) ? MachineIdentity.claim(identity, "sub") : Optional.empty();
    return subject.orElseGet(() -> identity.getPrincipal().getName());
  }

  /** The caller's application: its client id without the {@code <environment>-} prefix. */
  public String application(SecurityIdentity identity) {
    return application(clientId(identity), config.environment());
  }

  /** {@link #application(SecurityIdentity)} over bare strings. */
  public static String application(String clientId, String environment) {
    if (clientId == null || clientId.isBlank()) {
      return null;
    }
    String id = clientId.trim().toLowerCase(Locale.ROOT);
    String prefix = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT) + "-";
    return !prefix.equals("-") && id.startsWith(prefix) && id.length() > prefix.length()
        ? id.substring(prefix.length())
        : id;
  }

  /** The slot type {@code application} may lease, or empty. */
  public Optional<SlotType> tenantType(String application) {
    if (application == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(SlotType.parse(config.tenants().get(application)));
  }

  /**
   * The caller's application, refused unless it is a tenant allowed {@code type}.
   *
   * @throws TargetRefusal 403 NOT_A_TENANT or SLOT_TYPE_NOT_YOURS
   */
  public String requireTenantOf(SecurityIdentity identity, SlotType type) {
    String application = application(identity);
    SlotType allowed =
        tenantType(application)
            .orElseThrow(
                () ->
                    TargetRefusal.forbidden(
                        TargetRefusal.NOT_A_TENANT,
                        "Caller "
                            + (application == null ? "(anonymous)" : application)
                            + " leases no slot type here"));
    if (type != null && allowed != type) {
      throw TargetRefusal.forbidden(
          TargetRefusal.SLOT_TYPE_NOT_YOURS,
          application + " may lease " + allowed.name() + " slots, not " + type.name());
    }
    return application;
  }
}
