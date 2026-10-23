package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.runner.TargetLoginCommand;
import eu.wohlben.qits.system.targets.runner.TargetRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire shapes of the targets doors, and how a row becomes one. */
@ApplicationScoped
public class TargetViews {

  @Inject TargetRegistry registry;

  @Inject TargetLoginCommand loginCommand;

  @Inject Targets targets;

  /** Slots per type. */
  @Schema(name = "SlotCounts")
  public record SlotCounts(Integer ci, Integer workspace, Integer desk) {
    static SlotCounts of(Map<SlotType, Integer> counts) {
      return new SlotCounts(
          counts.getOrDefault(SlotType.CI, 0),
          counts.getOrDefault(SlotType.WORKSPACE, 0),
          counts.getOrDefault(SlotType.DESK, 0));
    }

    Targets.Minimums minimums() {
      return new Targets.Minimums(ci, workspace, desk);
    }
  }

  @Schema(name = "TargetLogin")
  public record Login(String claude, String kimi, Instant checkedAt) {}

  @Schema(name = "Target")
  public record TargetDto(
      UUID id,
      String name,
      String description,
      @Schema(description = "What the runner said in its last hello") int slotsTotal,
      SlotCounts minimums,
      @Schema(description = "GRANTED, RUNNING and ENDING leases per type") SlotCounts used,
      Login login,
      String credentialVolume,
      @Schema(description = "Null until a loginState arrived on the current connection")
          String loginCommand,
      String kimiLoginCommand,
      boolean connected,
      Instant connectedSince,
      boolean quarantined,
      String quarantineReason,
      Instant quarantinedAt,
      String runnerVersion,
      boolean registered,
      Instant registeredAt,
      Instant lastSeenAt,
      Instant lastHealthCheckAt,
      Boolean lastHealthCheckOk,
      Instant createdAt) {}

  @Schema(name = "TargetRegistration")
  public record TargetRegistrationDto(
      TargetDto target,
      @Schema(description = "The runner's one-use registration token. Returned once")
          String registrationToken,
      @Schema(
              description =
                  "The one line that installs the target's runner: it fetches install.sh with the"
                      + " registration token and pipes it into sudo sh. Returned once")
          String installLine) {
    @Override
    public String toString() {
      return "TargetRegistrationDto[target=" + (target == null ? null : target.id()) + "]";
    }
  }

  @Schema(name = "TargetRegistered")
  public record RegisteredTarget(
      String clientId, String secret, String tokenUrl, String audience, String socketUrl) {
    @Override
    public String toString() {
      return "RegisteredTarget[clientId=" + clientId + ", socketUrl=" + socketUrl + "]";
    }
  }

  @Schema(name = "TargetHealthCheckRequested")
  public record HealthCheckRequested(String requestId) {}

  @Schema(name = "Lease")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record LeaseDto(
      UUID id,
      SlotType slotType,
      String ownerApp,
      String ownerRef,
      UUID projectId,
      String workKey,
      UUID pinTargetId,
      UUID targetId,
      int priority,
      String state,
      @Schema(description = "LeaseSpec{image, env, user, limits}") JsonNode spec,
      String specHash,
      JsonNode adopt,
      Integer exitCode,
      String detail,
      Instant requestedAt,
      Instant grantedAt,
      Instant startedAt,
      Instant endedAt) {}

  @Schema(name = "WorkVolume")
  public record WorkVolumeDto(
      String ownerApp, String workKey, UUID targetId, String volumeName, Instant purgeRequestedAt) {}

  // --- mapping ----------------------------------------------------------------------------------

  public TargetDto view(Target target, Map<SlotType, Integer> used) {
    return new TargetDto(
        target.id,
        target.name,
        target.description,
        target.slotsTotal,
        SlotCounts.of(target.minimums()),
        SlotCounts.of(used == null ? Map.of() : used),
        new Login(
            target.loginClaude == null ? null : target.loginClaude.name(),
            target.loginKimi == null ? null : target.loginKimi.name(),
            target.loginCheckedAt),
        target.credentialVolume,
        loginCommand.claude(target),
        loginCommand.kimi(target),
        registry.connected(target.id),
        registry.connectedSince(target.id),
        target.quarantined(),
        target.quarantineReason,
        target.quarantinedAt,
        target.runnerVersion,
        target.registered(),
        target.registeredAt,
        target.lastSeenAt,
        target.lastHealthCheckAt,
        target.lastHealthCheckOk,
        target.createdAt);
  }

  public TargetDto view(Target target) {
    return view(target, targets.used().getOrDefault(target.id, Map.of()));
  }

  public static LeaseDto view(Lease lease) {
    return new LeaseDto(
        lease.id,
        lease.slotType,
        lease.ownerApp,
        lease.ownerRef,
        lease.projectId,
        lease.workKey,
        lease.pinTargetId,
        lease.targetId,
        lease.priority,
        lease.state.name(),
        LeaseSpecs.parse(lease.spec),
        lease.specHash,
        LeaseSpecs.parse(lease.adopt),
        lease.exitCode,
        lease.detail,
        lease.requestedAt,
        lease.grantedAt,
        lease.startedAt,
        lease.endedAt);
  }
}
