package eu.wohlben.qits.system.contracts;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.system.targets.ContractBearers;
import eu.wohlben.qits.system.targets.FakeTargetRunner;
import eu.wohlben.qits.system.targets.IdpStub;
import eu.wohlben.qits.system.targets.TargetRows;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import eu.wohlben.qits.system.targets.runner.TargetSignIns;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.net.URI;
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
 *
 * <p>The sign-in states (qits-1593) need a target that is CONNECTED, which only a socket makes: they
 * dial a {@link FakeTargetRunner} — the suite's real WebSocket runner — and keep it open until the
 * next state ({@link #close}), so the doors relay to a live connection exactly as in a deployment.
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

  /**
   * Target {@code lab} registered as {@code target-runner-client-lab}, its registration token
   * rotated since: the runner installed with the new token registers again (qits-1579).
   */
  public static final String A_ROTATED_RUNNER = "a rotated runner";

  /** qits-ci's CI lease {@code run-1}, QUEUED, and the bearer qits-ci calls with. */
  public static final String A_QUEUED_LEASE = "a queued lease";

  /**
   * qits-workspaces' work volume {@code ws-17} on target {@code lab}, its last lease ENDED. The
   * same as {@link #AN_UNUSED_WORKSPACE_WORK_VOLUME}, kept by this name because qits-workspaces'
   * SystemContract pins it.
   */
  public static final String AN_UNUSED_WORK_VOLUME = "an unused work volume";

  // --- the same lease states per tenant, each recorded with its own bearer ------------------------

  /** qits-workspaces' WORKSPACE lease {@code 17} (work key {@code ws-17}), QUEUED. */
  public static final String A_QUEUED_WORKSPACE_LEASE = "a queued workspace lease";

  /** The same lease RUNNING on target {@code lab}, its work volume there. */
  public static final String A_RUNNING_WORKSPACE_LEASE = "a running workspace lease";

  /** qits-workspaces' work volume {@code ws-17} on target {@code lab}, its last lease ENDED. */
  public static final String AN_UNUSED_WORKSPACE_WORK_VOLUME = "an unused workspace work volume";

  /** qits-projects' DESK lease for the front desk of project {@link #PROJECT_ID}, QUEUED. */
  public static final String A_QUEUED_DESK_LEASE = "a queued desk lease";

  /** The same lease RUNNING on target {@code lab}, its work volume there. */
  public static final String A_RUNNING_DESK_LEASE = "a running desk lease";

  /** qits-projects' work volume {@code desk-<project>} on target {@code lab}, its lease ENDED. */
  public static final String AN_UNUSED_DESK_WORK_VOLUME = "an unused desk work volume";

  /** Target {@code lab}, its runner connected and served, no sign-in running (qits-1593). */
  public static final String A_CONNECTED_TARGET = "a connected target";

  /**
   * The same target, a claude sign-in started and its runner reporting AWAITING_CODE with the url
   * {@link #CLAUDE_SIGN_IN_URL}: param {@code signInId}.
   */
  public static final String A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE =
      "a connected target with a claude sign-in awaiting its code";

  static final String CLAUDE_SIGN_IN_URL =
      "https://claude.com/cai/oauth/authorize?code=true&client_id=contract&state=contract";

  static final String RUNNER_BEARER = "Bearer qits_tok_contract-target-runner";

  static final String RUNNER_CLIENT = "target-runner-client-lab";

  /** Not seeded into the freezer: it freezes to the next free id, in the params and the body alike. */
  static final String PROJECT_ID = Freezer.frozenId(0x10);

  static final String PROJECTS_BEARER = "Bearer qits_tok_contract-qits-projects";

  static final String WORKSPACE_IMAGE = "registry.qits.wohlben.eu/qits/workspace:2026.1";

  static final String DESK_IMAGE = "registry.qits.wohlben.eu/qits/project-agent:2026.1";

  /** A tenant's lease, as the per-tenant states stand it up. */
  record Tenant(
      String app, String client, String bearer, SlotType type, String image, String ownerRef,
      String workKey) {}


  static final String TARGET_ID = Freezer.frozenId(1);

  static final String LEASE_ID = Freezer.frozenId(2);

  static final String REGISTRATION_BEARER = "Bearer qits_tok_contract-registration";

  static final String REGISTRATION_SUBJECT = "contract-registration-subject";

  static final String CI_BEARER = "Bearer qits_tok_contract-qits-ci";

  static final String WORKSPACES_BEARER = "Bearer qits_tok_contract-qits-workspaces";

  static final String CI_IMAGE = "registry.qits.wohlben.eu/qits/build-images/ci-slot:2026.1";

  static final Tenant WORKSPACES =
      new Tenant(
          "qits-workspaces",
          "dev-qits-workspaces",
          WORKSPACES_BEARER,
          SlotType.WORKSPACE,
          WORKSPACE_IMAGE,
          "17",
          "ws-17");

  static final Tenant PROJECTS =
      new Tenant(
          "qits-projects",
          "dev-qits-projects",
          PROJECTS_BEARER,
          SlotType.DESK,
          DESK_IMAGE,
          PROJECT_ID,
          "desk-" + PROJECT_ID);

  public record Setup(Map<String, String> params, Set<String> uniqueTokens) {
    Setup(Map<String, String> params) {
      this(params, Set.of());
    }
  }

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  private final ContractBearers bearers;

  /** Where a sign-in state dials its runner; null leaves those states unanswerable. */
  private final URI base;

  /** The runner a sign-in state holds connected, until the next state or {@link #close}. */
  private FakeTargetRunner runner;

  public ProviderStates(ContractBearers bearers) {
    this(bearers, null);
  }

  public ProviderStates(ContractBearers bearers, URI base) {
    this.bearers = bearers;
    this.base = base;
    states.put(A_SINGLE_NODE_SWARM, ProviderStates::aSingleNodeSwarm);
    states.put(NO_TARGET, this::noTarget);
    states.put(A_DECLARED_TARGET, this::aDeclaredTarget);
    states.put(AN_UNREGISTERED_RUNNER, this::anUnregisteredRunner);
    states.put(A_REGISTERED_RUNNER, this::aRegisteredRunner);
    states.put(A_ROTATED_RUNNER, this::aRotatedRunner);
    states.put(A_QUEUED_LEASE, this::aQueuedLease);
    states.put(AN_UNUSED_WORK_VOLUME, this::anUnusedWorkVolume);
    states.put(A_QUEUED_WORKSPACE_LEASE, () -> tenantLease(WORKSPACES, LeaseState.QUEUED));
    states.put(A_RUNNING_WORKSPACE_LEASE, () -> tenantLease(WORKSPACES, LeaseState.RUNNING));
    states.put(AN_UNUSED_WORKSPACE_WORK_VOLUME, () -> tenantLease(WORKSPACES, LeaseState.ENDED));
    states.put(A_QUEUED_DESK_LEASE, () -> tenantLease(PROJECTS, LeaseState.QUEUED));
    states.put(A_RUNNING_DESK_LEASE, () -> tenantLease(PROJECTS, LeaseState.RUNNING));
    states.put(AN_UNUSED_DESK_WORK_VOLUME, () -> tenantLease(PROJECTS, LeaseState.ENDED));
    states.put(A_CONNECTED_TARGET, this::aConnectedTarget);
    states.put(A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE, this::aClaudeSignInAwaitingItsCode);
  }

  /** Hangs up the runner a sign-in state dialled, if one did. */
  public void close() {
    if (runner != null) {
      runner.close();
      runner = null;
    }
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

  /** Every targets state starts here: no runner, no sign-in, no rows, a fresh idp stub, no bearer. */
  private void fresh() {
    close();
    signIns().forget(UUID.fromString(TARGET_ID));
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
              target.capabilities =
                  "{\"runnerVersion\":\"2026.1023.1\",\"docker\":true,\"arch\":\"amd64\","
                      + "\"os\":\"linux\",\"slots\":6,\"engineId\":\"ENGINE-LAB\","
                      + "\"hostname\":\"lab-host\",\"credentialVolume\":"
                      + "\"qits-system-runner-dot-claude-00000000\",\"buildkit\":true}";
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

  private Setup aRotatedRunner() {
    fresh();
    UUID id = UUID.fromString(TARGET_ID);
    TargetRows.target(id, "lab", 4, "target-runner-client-lab", false);
    TargetRows.registrationToken(id, "tok-contract", REGISTRATION_SUBJECT);
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

  /**
   * {@code tenant}'s lease {@link #LEASE_ID} in {@code state}: QUEUED on no target; RUNNING on
   * target {@code lab} ({@link #TARGET_ID}) with its work volume there; ENDED on {@code lab} with the
   * volume left unused. The tenant's bearer is armed; params {@code authorization}, {@code leaseId},
   * {@code workKey}, {@code ownerRef} (and {@code targetId} once placed).
   */
  private Setup tenantLease(Tenant tenant, LeaseState state) {
    fresh();
    UUID target = UUID.fromString(TARGET_ID);
    UUID id = UUID.fromString(LEASE_ID);
    boolean placed = state != LeaseState.QUEUED;
    if (placed) {
      TargetRows.target(target, "lab", 6, "target-runner-client-lab", false);
    }
    TargetRows.lease(id, tenant.type(), tenant.app(), tenant.ownerRef(), state, placed ? target : null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Lease lease = Lease.findById(id);
              lease.workKey = tenant.workKey();
              lease.spec =
                  "{\"env\":{\"QITS_TOKEN\":\"qits_tok_lease\"},\"image\":\""
                      + tenant.image()
                      + "\",\"limits\":{\"memory\":\"4g\"},\"user\":\"1000\"}";
              lease.specHash = "spec-hash-1";
              if (state == LeaseState.QUEUED) {
                lease.detail =
                    "target lab is not signed in: qits targets sign-in --target lab";
              }
              if (state == LeaseState.RUNNING) {
                lease.startedAt = TargetRows.T0;
              }
              if (state == LeaseState.ENDED) {
                lease.endedAt = TargetRows.T0;
              }
              if (placed) {
                WorkVolume volume = new WorkVolume();
                volume.ownerApp = tenant.app();
                volume.workKey = tenant.workKey();
                volume.targetId = target;
                volume.volumeName =
                    "qits-system-runner-work-" + tenant.workKey() + "-00000000";
                volume.createdAt = TargetRows.T0;
                volume.persist();
              }
            });
    bearers.grant(tenant.bearer(), tenant.client(), "qits:system");
    Map<String, String> params = new TreeMap<>();
    params.put("authorization", tenant.bearer());
    params.put("leaseId", LEASE_ID);
    params.put("workKey", tenant.workKey());
    params.put("ownerRef", tenant.ownerRef());
    if (placed) {
      params.put("targetId", TARGET_ID);
    }
    return new Setup(params);
  }

  private Setup aConnectedTarget() {
    if (base == null) {
      throw new IllegalStateException("'" + A_CONNECTED_TARGET + "' dials a runner: pass the base URI");
    }
    fresh();
    TargetRows.target(UUID.fromString(TARGET_ID), "lab", 4, RUNNER_CLIENT, false);
    bearers.grant(RUNNER_BEARER, RUNNER_CLIENT, "qits:target-runner");
    runner = FakeTargetRunner.dial(base, RUNNER_BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-00000000");
    try {
      runner.await(Ack.class);
      // The estate follows the greeting: once it is here, the connection serves the target.
      runner.await(Estate.class);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
    return new Setup(Map.of("targetId", TARGET_ID));
  }

  private Setup aClaudeSignInAwaitingItsCode() {
    aConnectedTarget();
    UUID target = UUID.fromString(TARGET_ID);
    TargetSignIns.View started = signIns().start(target, "lab", "claude");
    String id = started.signInId().toString();
    try {
      runner.await(SignIn.class, s -> s.signInId().equals(id));
      runner.signInProgress(id, "claude", SignInState.AWAITING_CODE, CLAUDE_SIGN_IN_URL, null, null);
      long deadline = System.currentTimeMillis() + 20_000;
      while (signIns().get(target, id).state() != SignInState.AWAITING_CODE) {
        if (System.currentTimeMillis() > deadline) {
          throw new IllegalStateException("sign-in " + id + " never became AWAITING_CODE");
        }
        Thread.sleep(20);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
    return new Setup(Map.of("targetId", TARGET_ID, "signInId", id));
  }

  private static TargetSignIns signIns() {
    return Arc.container().instance(TargetSignIns.class).get();
  }

  /** The states' fixed ids, for a recording's freezer to keep as they are. */
  static List<String> fixedIds() {
    return List.of(TARGET_ID, LEASE_ID);
  }
}
