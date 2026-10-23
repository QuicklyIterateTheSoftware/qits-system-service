package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.idp.TargetRunnerCommissioner;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Gives back the {@code target-runner*} credentials no target row holds any more — the pattern of
 * qits-projects-service's {@code DeskRunnerCommissionReconcile}.
 *
 * <p><b>Two arms, judged by the target table.</b> A {@code target-runner} client belongs to the
 * target its {@code contextId} names, and is decommissioned when that row is gone or names a
 * different client; a row with no client yet spares every client of its target (a registration
 * between the commission and the write). A {@code target-runner-registration} token is deleted when
 * no row's {@code registration_token_id} names it; one younger than {@link #TOKEN_GRACE} is spared,
 * because a create and a rotation commission it before the row names it.
 *
 * <p>It only ever deletes what the issuer just listed, filtered to its two kinds; a listing, or a
 * target table, that could not be read reaps nothing. At boot (packaged runs only) and every {@code
 * qits.system.targets.commission-reconcile-interval}. Never throws.
 */
@ApplicationScoped
public class TargetCommissionReconcile {

  private static final Logger LOG = Logger.getLogger(TargetCommissionReconcile.class);

  static final Duration TOKEN_GRACE = Duration.ofMinutes(10);

  @Inject TargetRunnerCommissioner commissioner;

  @Inject Targets targets;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-system-target-commission-reconcile").start(this::reconcile);
  }

  @Scheduled(
      every = "{qits.system.targets.commission-reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    reconcile();
  }

  /** One pass; answers how many credentials it gave back. */
  int reconcile() {
    try {
      if (!commissioner.enabled()) {
        return 0;
      }
      return reapClients() + reapRegistrationTokens(Instant.now());
    } catch (RuntimeException e) {
      LOG.warnf("The target commission reconcile did not complete: %s", e.toString());
      return 0;
    }
  }

  int reapClients() {
    Optional<List<TargetRunnerCommissioner.LiveClient>> live =
        commissioner.liveClients(TargetRunnerCommissioner.RUNNER_KIND);
    if (live.isEmpty() || live.get().isEmpty()) {
      return 0;
    }
    Map<String, Credentials> rows = credentials();
    if (rows == null) {
      return 0;
    }
    int reaped = 0;
    for (TargetRunnerCommissioner.LiveClient client : live.get()) {
      Credentials row = rows.get(client.contextId());
      if (row != null && (row.clientId() == null || row.clientId().equals(client.clientId()))) {
        continue;
      }
      LOG.infof(
          "Decommissioning target runner client %s of target %s, which %s",
          client.clientId(),
          client.contextId(),
          row == null ? "is gone" : "is registered as " + row.clientId());
      commissioner.decommissionClient(client.clientId());
      reaped++;
    }
    return reaped;
  }

  int reapRegistrationTokens(Instant now) {
    Optional<List<TargetRunnerCommissioner.LiveToken>> live = commissioner.liveTokens();
    if (live.isEmpty()
        || live.get().stream()
            .noneMatch(t -> TargetRunnerCommissioner.REGISTRATION_KIND.equals(t.contextKind()))) {
      return 0;
    }
    Map<String, Credentials> rows = credentials();
    if (rows == null) {
      return 0;
    }
    Set<String> referenced = new HashSet<>();
    rows.values().stream()
        .map(Credentials::registrationTokenId)
        .filter(Objects::nonNull)
        .forEach(referenced::add);
    int reaped = 0;
    for (TargetRunnerCommissioner.LiveToken token : live.get()) {
      if (!TargetRunnerCommissioner.REGISTRATION_KIND.equals(token.contextKind())
          || referenced.contains(token.tokenId())) {
        continue;
      }
      if (token.createdAt() != null && token.createdAt().isAfter(now.minus(TOKEN_GRACE))) {
        continue;
      }
      LOG.infof(
          "Deleting target registration token %s of target %s: no target row names it",
          token.tokenId(), token.contextId());
      commissioner.deleteToken(token.tokenId());
      reaped++;
    }
    return reaped;
  }

  record Credentials(String clientId, String registrationTokenId) {}

  private Map<String, Credentials> credentials() {
    try {
      Map<String, Credentials> rows = new HashMap<>();
      for (Target target : targets.list()) {
        rows.put(
            target.id.toString(), new Credentials(target.clientId, target.registrationTokenId));
      }
      return rows;
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the targets, so no runner credential is reaped: %s", e.toString());
      return null;
    }
  }
}
