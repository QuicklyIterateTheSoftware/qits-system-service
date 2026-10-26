package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.events.TargetLeaseChanged;
import eu.wohlben.qits.system.targets.events.TargetLoginChanged;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * The targets module's two events, handed to {@link QitsEventBus} on a thread of their own — never
 * on the caller's, which may still be inside the transaction that made the change, and never in a
 * way that can fail it. qits-workspaces-service's {@code EventstreamLifecycleAnnouncer}, kept in
 * shape: the causation of the caller is captured here and carried over.
 *
 * <p>{@code QitsEventBus.publish} serializes these with Jackson, which the native-image build
 * cannot see through: the only JAX-RS-declared types it discovers reflection for are return types,
 * and an event handed to the bus is neither. {@link EventWireReflection} is where that
 * registration lives now — for these two records, the PUT body ({@code EventEnvelope}) and the
 * canonical mix-in together, so there is one place a new wire type joins rather than two to keep in
 * sync. Without it the native binary logs "could not be published or recorded" for every lease or
 * login change while every JVM test stays green, because the JVM needs no such registration.
 */
@ApplicationScoped
public class TargetEvents {

  private static final Logger LOG = Logger.getLogger(TargetEvents.class);

  private static final long DRAIN_SECONDS = 5;

  @Inject QitsEventBus bus;

  private final ExecutorService publisher =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "system-targets-events");
            t.setDaemon(true);
            return t;
          });

  /** {@link TargetLeaseChanged} for {@code lease} as it is now. */
  public void leaseChanged(Lease lease) {
    announce(
        new TargetLeaseChanged(
            lease.id.toString(),
            lease.ownerApp,
            lease.ownerRef,
            lease.slotType.name(),
            lease.targetId == null ? null : lease.targetId.toString(),
            lease.state.name(),
            lease.exitCode,
            lease.detail,
            Instant.now()));
  }

  /** {@link TargetLoginChanged} for {@code target} as it is now. */
  public void loginChanged(Target target, String checkedAt) {
    announce(
        new TargetLoginChanged(
            target.id.toString(),
            target.name,
            target.loginClaude == null ? null : target.loginClaude.name(),
            target.loginKimi == null ? null : target.loginKimi.name(),
            checkedAt,
            Instant.now()));
  }

  void announce(QitsEvent event) {
    UUID parent = CausationScope.current();
    try {
      publisher.execute(() -> publish(event, parent));
    } catch (RejectedExecutionException stopped) {
      LOG.warnf("%s %s not published: this process is stopping", event.signature(), event.eventId());
    }
  }

  private void publish(QitsEvent event, UUID parent) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      bus.publish(event, parent);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s not published: %s", event.signature(), event.eventId(), e.toString());
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  @PreDestroy
  void drain() {
    publisher.shutdown();
    try {
      if (!publisher.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
        LOG.warnf(
            "%d target event(s) still queued after %ds; dropped",
            publisher.shutdownNow().size(), DRAIN_SECONDS);
      }
    } catch (InterruptedException e) {
      publisher.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
