package eu.wohlben.qits.system.contracts;

import eu.wohlben.qits.system.targets.ContractBearers;
import eu.wohlben.qits.system.targets.IdpStub;
import eu.wohlben.qits.system.targets.TargetRows;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>The provider states qits-system answers for</b>, shared by {@link GoldenMasterRecordingTest}
 * (which records them) and {@link ConsumerPactVerificationTest} (which replays consumers' pacts
 * against them).
 *
 * <p>The swarm state is the fake docker's captures and needs no setup. The targets states
 * (qits-1481) stand up their rows directly, with fixed, already-frozen ids ({@link
 * Freezer#frozenId}), reset the idp stub so a commission answers {@code …-1}, and arm {@link
 * ContractBearers} with the bearer a consumer sends — the target runner's registration token, or a
 * tenant service's client — so the golden master and the pact both carry the {@code Authorization}
 * header a real consumer sends.
 */
public class ProviderStates {

  public static final String A_SINGLE_NODE_SWARM = "a single-node swarm";

  static final String NODE_ID = "iwbwrdui2z0n62kqcjfo1erwh";

  static final String SERVICE_ID = "dev-qits-artifacts";

  static final String CONFIG_ID = "qits-edge-vhosts";

  static final String CONTAINER_ID = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  // --- the targets states -----------------------------------------------------------------------

  /** No target and no lease: where a create starts. */
  public static final String NO_TARGET = "no target";

  /** Target {@code lab}: registered, connected nowhere, six slots, a settled health check. */
  public static final String A_DECLARED_TARGET = "a declared target";

  /** The toolkit's register states, which every runner host shares (qits-ci recorded them first). */
  public static final String AN_UNREGISTERED_RUNNER = "an unregistered runner";

  public static final String A_REGISTERED_RUNNER = "a registered runner";

  /** qits-ci's CI lease {@code run-1}, QUEUED, and the bearer qits-ci calls with. */
  public static final String A_QUEUED_LEASE = "a queued lease";

  /** qits-workspaces' work volume {@code ws-17} on target {@code lab}, its last lease ENDED. */
  public static final String AN_UNUSED_WORK_VOLUME = "an unused work volume";

  static final String TARGET_ID = Freezer.frozenId(1);

  static final String LEASE_ID = Freezer.frozenId(2);

  static final String REGISTRATION_BEARER = "Bearer qits_tok_contract-registration";

  static final String REGISTRATION_SUBJECT = "contract-registration-subject";

  static final String CI_BEARER = "Bearer qits_tok_contract-qits-ci";

  static final String WORKSPACES_BEARER = "Bearer qits_tok_contract-qits-workspaces";

  static final String CI_IMAGE = "registry.qits.wohlben.eu/qits/build-images/ci-slot:2026.1";

  public record Setup(Map<String, String> params, Set<String> uniqueTokens) {
    Setup(Map<String, String> params) {
      this(params, Set.of());
    }
  }

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  private final ContractBearers bearers;

  public ProviderStates(ContractBearers bearers) {
    this.bearers = bearers;
    states.put(A_SINGLE_NODE_SWARM, ProviderStates::aSingleNodeSwarm);
    states.put(NO_TARGET, this::noTarget);
    states.put(A_DECLARED_TARGET, this::aDeclaredTarget);
    states.put(AN_UNREGISTERED_RUNNER, this::anUnregisteredRunner);
    states.put(A_REGISTERED_RUNNER, this::aRegisteredRunner);
    states.put(A_QUEUED_LEASE, this::aQueuedLease);
    states.put(AN_UNUSED_WORK_VOLUME, this::anUnusedWorkVolume);
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  private static Setup aSingleNodeSwarm() {
    Map<String, String> params = new TreeMap<>();
    params.put("nodeId", NODE_ID);
    params.put("serviceId", SERVICE_ID);
    params.put("configId", CONFIG_ID);
    params.put("containerId", CONTAINER_ID);
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** Every targets state starts here: no rows, a fresh idp stub, no bearer. */
  private void fresh() {
    TargetRows.clear();
    IdpStub.reset();
    bearers.reset();
  }

  private Setup noTarget() {
    fresh();
    bearers.grant(CI_BEARER, "dev-qits-ci", "qits:system");
    return new Setup(Map.of("authorization", CI_BEARER), Set.of(SystemRunnerBinary.VERSION));
  }

  private Setup aDeclaredTarget() {
    fresh();
    UUID id = UUID.fromString(TARGET_ID);
    TargetRows.target(id, "lab", 6, "target-runner-client-lab", false);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Target target = Target.findById(id);
              target.description = "the lab box";
              target.minCi = 2;
              target.minWorkspace = 2;
              target.minDesk = 1;
              target.loginClaude = LoginValue.PRESENT;
              target.loginKimi = LoginValue.ABSENT;
              target.loginCheckedAt = TargetRows.T0;
              target.credentialVolume = "qits-system-runner-dot-claude-00000000";
              target.runnerVersion = "2026.1023.1";
              target.lastSeenAt = TargetRows.T0;
              target.lastHealthCheckAt = TargetRows.T0;
              target.lastHealthCheckOk = true;
              target.health =
                  "{\"at\":\"2026-01-01T00:00:00Z\",\"ok\":true,\"detail\":\"\",\"requestId\":null,"
                      + "\"checks\":[{\"name\":\"docker\",\"ok\":true,\"detail\":null,\"data\":{}},"
                      + "{\"name\":\"login\",\"ok\":true,\"detail\":null,\"data\":"
                      + "{\"claude\":\"PRESENT\",\"kimi\":\"ABSENT\"}},"
                      + "{\"name\":\"slots\",\"ok\":true,\"detail\":null,\"data\":"
                      + "{\"minimums\":5,\"total\":6}}]}";
            });
    return new Setup(Map.of("targetId", TARGET_ID));
  }

  private Setup anUnregisteredRunner() {
    fresh();
    UUID id = UUID.fromString(TARGET_ID);
    TargetRows.target(id, "lab", 0, null, true);
    TargetRows.registrationToken(id, "tok-contract", REGISTRATION_SUBJECT);
    bearers.grant(REGISTRATION_BEARER, REGISTRATION_SUBJECT, "qits:target-runner-registration");
    return new Setup(Map.of("authorization", REGISTRATION_BEARER, "runnerId", TARGET_ID));
  }

  private Setup aRegisteredRunner() {
    fresh();
    UUID id = UUID.fromString(TARGET_ID);
    TargetRows.target(id, "lab", 0, "target-runner-client-lab", true);
    bearers.grant(REGISTRATION_BEARER, REGISTRATION_SUBJECT, "qits:target-runner-registration");
    return new Setup(Map.of("authorization", REGISTRATION_BEARER, "runnerId", TARGET_ID));
  }

  private Setup aQueuedLease() {
    fresh();
    UUID id = UUID.fromString(LEASE_ID);
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-1", LeaseState.QUEUED, null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Lease lease = Lease.findById(id);
              lease.spec =
                  "{\"env\":{\"QITS_TOKEN\":\"qits_tok_run\"},\"image\":\""
                      + CI_IMAGE
                      + "\",\"limits\":{\"memory\":\"4g\"},\"user\":\"1001\"}";
              lease.specHash = "spec-hash-1";
              lease.detail = "no target has a free CI slot";
            });
    bearers.grant(CI_BEARER, "dev-qits-ci", "qits:system");
    return new Setup(Map.of("authorization", CI_BEARER, "leaseId", LEASE_ID));
  }

  private Setup anUnusedWorkVolume() {
    fresh();
    UUID target = UUID.fromString(TARGET_ID);
    TargetRows.target(target, "lab", 4, "target-runner-client-lab", false);
    TargetRows.lease(
        UUID.fromString(LEASE_ID),
        SlotType.WORKSPACE,
        "qits-workspaces",
        "17",
        LeaseState.ENDED,
        target);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Lease lease = Lease.findById(UUID.fromString(LEASE_ID));
              lease.workKey = "ws-17";
              WorkVolume volume = new WorkVolume();
              volume.ownerApp = "qits-workspaces";
              volume.workKey = "ws-17";
              volume.targetId = target;
              volume.volumeName = "qits-system-runner-work-ws-17-00000000";
              volume.createdAt = TargetRows.T0;
              volume.persist();
            });
    bearers.grant(WORKSPACES_BEARER, "dev-qits-workspaces", "qits:system");
    return new Setup(Map.of("authorization", WORKSPACES_BEARER, "workKey", "ws-17"));
  }

  /** The states' fixed ids, for a recording's freezer to keep as they are. */
  static List<String> fixedIds() {
    return List.of(TARGET_ID, LEASE_ID);
  }
}
