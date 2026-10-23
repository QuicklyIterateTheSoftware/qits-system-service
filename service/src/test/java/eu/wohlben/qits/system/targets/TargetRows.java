package eu.wohlben.qits.system.targets;

import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Instant;
import java.util.UUID;

/** The rows a test stands up directly, with fixed values, and the one way they are all removed. */
public final class TargetRows {

  public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private TargetRows() {}

  /** Removes every lease, work volume and target. */
  public static void clear() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Lease.deleteAll();
              WorkVolume.deleteAll();
              Target.deleteAll();
            });
  }

  /** A target row: registered as {@code clientId} when that is set, quarantined or not. */
  public static Target target(
      UUID id, String name, int slots, String clientId, boolean quarantined) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Target target = new Target();
              target.id = id;
              target.name = name;
              target.slotsTotal = slots;
              target.clientId = clientId;
              target.registeredAt = clientId == null ? null : T0;
              target.loginClaude = LoginValue.UNKNOWN;
              target.loginKimi = LoginValue.UNKNOWN;
              target.createdAt = T0;
              if (quarantined) {
                target.quarantinedAt = T0;
                target.quarantineReason = "awaiting first health check";
              }
              target.persist();
              return target;
            });
  }

  /** Sets a target's registration token, as a create would have. */
  public static void registrationToken(UUID id, String tokenId, String subject) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Target target = Target.findById(id);
              target.registrationTokenId = tokenId;
              target.registrationTokenSubject = subject;
            });
  }

  /** A lease row in {@code state} on {@code targetId} (null while QUEUED). */
  public static Lease lease(
      UUID id, SlotType type, String ownerApp, String ownerRef, LeaseState state, UUID targetId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Lease lease = new Lease();
              lease.id = id;
              lease.slotType = type;
              lease.ownerApp = ownerApp;
              lease.ownerRef = ownerRef;
              lease.state = state;
              lease.targetId = targetId;
              lease.spec = "{\"image\":\"registry.qits.wohlben.eu/qits/workspace:1\"}";
              lease.specHash = "hash";
              lease.requestedAt = T0;
              lease.grantedAt = targetId == null ? null : T0;
              lease.persist();
              return lease;
            });
  }

  public static Lease reload(UUID leaseId) {
    return QuarkusTransaction.requiringNew().call(() -> Lease.findById(leaseId));
  }

  public static Target reloadTarget(UUID targetId) {
    return QuarkusTransaction.requiringNew().call(() -> Target.findById(targetId));
  }
}
