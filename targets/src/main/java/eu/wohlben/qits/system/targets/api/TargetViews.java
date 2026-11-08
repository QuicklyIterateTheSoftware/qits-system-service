package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WarmImage;
import eu.wohlben.qits.system.targets.runner.TargetLoginCommand;
import eu.wohlben.qits.system.targets.runner.TargetRegistry;
import eu.wohlben.qits.system.targets.runner.TargetSignIns;
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

    /** Every type as it is, a null kept null: the maximums, where null is no ceiling. */
    static SlotCounts ofNullable(Map<SlotType, Integer> counts) {
      return new SlotCounts(
          counts.get(SlotType.CI), counts.get(SlotType.WORKSPACE), counts.get(SlotType.DESK));
    }

    Targets.Reservations reservations() {
      return new Targets.Reservations(ci, workspace, desk);
    }
  }

  @Schema(name = "TargetLogin")
  public record Login(String claude, String kimi, Instant checkedAt) {}

  /**
   * The node's memory as its runner last reported it (qits-1818). Always present; every member null
   * until a runner that reports them did.
   */
  @Schema(name = "TargetMemory")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Memory(
      @Schema(
              description =
                  "Processes the OOM killer took in the node's cgroup tree (its root memory.events"
                      + " oom_kill); null on a bare host, whose root has none")
          Long nodeOomKills,
      @Schema(
              description =
                  "The kernel's own count (/proc/vmstat oom_kill): every OOM kill on the physical"
                      + " host, not only this node's")
          Long kernelOomKills,
      @Schema(description = "The node's memory limit (its root memory.max); null when none")
          Long memoryMaxBytes,
      @Schema(description = "When the runner last reported these") Instant reportedAt) {}

  @Schema(name = "Target")
  public record TargetDto(
      UUID id,
      String name,
      String description,
      @Schema(description = "What the runner said in its last hello") int slotsTotal,
      @Schema(
              description =
                  "Slots reserved per type. Not a limit: a type may use more while shared"
                      + " (unreserved) slots are free")
          SlotCounts reservations,
      @Schema(
              description =
                  "The most leases per type the target runs at once; null is no ceiling. The"
                      + " limit a reservation is not: a type at its maximum waits however many"
                      + " shared slots are free")
          SlotCounts maximums,
      @Schema(description = "GRANTED, RUNNING and ENDING leases per type") SlotCounts used,
      Login login,
      String credentialVolume,
      @Schema(
              description =
                  "The manual fallback, run on the target's node: the sign-in doors need no shell."
                      + " Null until a loginState arrived on the current connection")
          String loginCommand,
      String kimiLoginCommand,
      boolean connected,
      Instant connectedSince,
      boolean quarantined,
      String quarantineReason,
      Instant quarantinedAt,
      String runnerVersion,
      @Schema(description = "The runner's hostname, from its hello; null before one") String hostname,
      @Schema(description = "docker info's engine ID, from the runner's hello; null before one")
          String engineId,
      boolean registered,
      Instant registeredAt,
      Instant lastSeenAt,
      Instant lastHealthCheckAt,
      Boolean lastHealthCheckOk,
      Instant createdAt,
      @Schema(description = "The node's memory and OOM kills, as its runner last reported them")
          Memory memory) {}

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

  @Schema(name = "TargetSignIn")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SignInView(
      UUID signInId,
      UUID targetId,
      @Schema(description = "claude or kimi") String harness,
      @Schema(
              description =
                  "STARTING, AWAITING_CODE (claude: open url, post the code it shows),"
                      + " AWAITING_BROWSER (kimi: open url, approve userCode), or the terminal"
                      + " SUCCEEDED, FAILED, EXPIRED, CANCELLED")
          String state,
      @Schema(description = "The browser url the CLI printed; null until it did") String url,
      @Schema(description = "Kimi Code's device code; null for claude") String userCode,
      @Schema(description = "Why, on a terminal state; else null") String detail,
      Instant startedAt,
      Instant updatedAt) {}

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
      @Schema(
              description =
                  "LeaseSpec{image, env, user, limits}; every env value reads \"<redacted>\","
                      + " the keys are kept. specHash is the stored spec's, unredacted")
          JsonNode spec,
      String specHash,
      JsonNode adopt,
      Integer exitCode,
      String detail,
      Instant requestedAt,
      Instant grantedAt,
      Instant startedAt,
      Instant endedAt,
      @Schema(
              description =
                  "Processes of this lease killed by any OOM killer (its container's cgroup"
                      + " memory.events oom_kill); the last sample is kept after it exits. Null"
                      + " until a runner reported one")
          Long oomKills,
      @Schema(
              description =
                  "LEASE (the lease hit its own memory limit) or NODE (the node or kernel ran out"
                      + " and the killer picked a process in this lease); null when oomKills is"
                      + " null or 0",
              enumeration = {"LEASE", "NODE"})
          String oomScope,
      @Schema(description = "The lease container's peak memory (memory.peak); null when unread")
          Long memoryPeakBytes,
      @Schema(description = "docker's State.OOMKilled of the lease's exited container; else null")
          Boolean oomKilled) {}

  @Schema(name = "WorkVolume")
  public record WorkVolumeDto(
      String ownerApp, String workKey, UUID targetId, String volumeName, Instant purgeRequestedAt) {}

  /**
   * The image one slot type keeps warm (qits-1692): the newest released version of its slot image,
   * which every target that can run the type pulls ahead of its first lease.
   */
  @Schema(name = "WarmImage")
  public record WarmImageDto(
      SlotType slotType,
      @Schema(description = "The released package, as qits-ci names it: qits/workspace")
          String packageName,
      @Schema(description = "Its version, YYYY.MMDD.HHMMSS") String version,
      @Schema(description = "The full reference the estate carries: <configured image>:<version>")
          String image,
      @Schema(description = "When the newest release moved it here") Instant updatedAt) {}

  // --- mapping ----------------------------------------------------------------------------------

  public TargetDto view(Target target, Map<SlotType, Integer> used) {
    return new TargetDto(
        target.id,
        target.name,
        target.description,
        target.slotsTotal,
        SlotCounts.of(target.reservations()),
        SlotCounts.ofNullable(target.maximums()),
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
        capability(target, "hostname"),
        capability(target, "engineId"),
        target.registered(),
        target.registeredAt,
        target.lastSeenAt,
        target.lastHealthCheckAt,
        target.lastHealthCheckOk,
        target.createdAt,
        new Memory(
            target.nodeOomKills,
            target.kernelOomKills,
            target.memoryMaxBytes,
            target.memoryReportedAt));
  }

  /** One text member of the target's recorded {@code hello} capabilities, or null. */
  static String capability(Target target, String name) {
    JsonNode capabilities = LeaseSpecs.parse(target.capabilities);
    JsonNode value = capabilities == null ? null : capabilities.get(name);
    return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
  }

  public TargetDto view(Target target) {
    return view(target, targets.used().getOrDefault(target.id, Map.of()));
  }

  public static SignInView view(TargetSignIns.View signIn) {
    return new SignInView(
        signIn.signInId(),
        signIn.targetId(),
        signIn.harness(),
        signIn.state().name(),
        signIn.url(),
        signIn.userCode(),
        signIn.detail(),
        signIn.startedAt(),
        signIn.updatedAt());
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
        LeaseSpecs.redacted(LeaseSpecs.parse(lease.spec)),
        lease.specHash,
        LeaseSpecs.parse(lease.adopt),
        lease.exitCode,
        lease.detail,
        lease.requestedAt,
        lease.grantedAt,
        lease.startedAt,
        lease.endedAt,
        lease.oomKills,
        lease.oomScope(),
        lease.memoryPeakBytes,
        lease.oomKilled);
  }

  public static WarmImageDto view(WarmImage row) {
    return new WarmImageDto(row.slotType, row.packageName, row.version, row.image, row.updatedAt);
  }
}
