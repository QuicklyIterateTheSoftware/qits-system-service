package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;

import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code /system/api/leases} and {@code /work-volumes} doors: owners, refusals, life. */
@QuarkusTest
class LeaseDoorsTest {

  private static final String API = "/system/api/leases";

  static final String CI_IMAGE = "registry.qits.wohlben.eu/qits/build-images/ci-slot:2026.1";
  static final String WORKSPACE_IMAGE = "registry.qits.wohlben.eu/qits/workspace:2026.1";

  @Inject TargetAllocation allocation;

  @Inject ContractBearers bearers;

  @TestHTTPResource("/")
  URI base;

  @BeforeEach
  void clean() {
    TargetRows.clear();
    bearers.reset();
  }

  /** The runner {@link #connectedLab} dialled, closed after each test. */
  private FakeTargetRunner runner;

  @AfterEach
  void disarm() {
    if (runner != null) {
      runner.close();
      runner = null;
    }
    bearers.reset();
  }

  /**
   * Target {@code lab} with its runner connected, registered as {@code client-lab}: a RUNNING lease
   * on it ends through ENDING, since a runner is there to report its container gone (qits-2227).
   */
  private void connectedLab(UUID target) throws InterruptedException {
    TargetRows.target(target, "lab", 4, "client-lab", false);
    bearers.grant("Bearer runner-lab", "client-lab", "qits:target-runner");
    runner = FakeTargetRunner.dial(base, "Bearer runner-lab");
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    runner.await(Ack.class);
    runner.await(Estate.class);
  }

  static RequestSpecification service(String clientId) {
    return given().header("Authorization", ContractBearers.bearer(clientId, "qits:system"));
  }

  static Response lease(String clientId, String type, String ownerRef, String image, String extra) {
    return service(clientId)
        .contentType("application/json")
        .body(
            "{\"slotType\":\""
                + type
                + "\",\"ownerRef\":\""
                + ownerRef
                + "\",\"spec\":{\"image\":\""
                + image
                + "\",\"env\":{\"QITS_TOKEN\":\"t\"},\"user\":\"1001\",\"limits\":{\"memory\":\"2g\"}}"
                + extra
                + "}")
        .post(API);
  }

  @Test
  void anOwnerAsksForASlotOfItsTypeAndItIsQueued() {
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "")
        .then()
        .statusCode(201)
        .body("state", equalTo("QUEUED"))
        .body("ownerApp", equalTo("qits-ci"))
        .body("ownerRef", equalTo("run-1"))
        .body("slotType", equalTo("CI"))
        .body("targetId", nullValue())
        .body("specHash", notNullValue())
        .body("spec.image", equalTo(CI_IMAGE));
  }

  @Test
  void aLiveLeaseForTheSameRefIsRefused() {
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").then().statusCode(201);
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "")
        .then()
        .statusCode(409)
        .body("code", equalTo("LEASE_EXISTS"));
  }

  @Test
  void theOwnerIsTheBearerNeverTheBodyAndOnlyItsTypeIsItsToLease() {
    lease("dev-qits-ci", "WORKSPACE", "ws-1", WORKSPACE_IMAGE, "")
        .then()
        .statusCode(403)
        .body("code", equalTo("SLOT_TYPE_NOT_YOURS"));
    lease("dev-qits-deployments", "CI", "x", CI_IMAGE, "")
        .then()
        .statusCode(403)
        .body("code", equalTo("NOT_A_TENANT"));
    lease("dev-qits-workspaces", "WORKSPACE", "17", WORKSPACE_IMAGE, ",\"ownerApp\":\"qits-ci\"")
        .then()
        .statusCode(201)
        .body("ownerApp", equalTo("qits-workspaces"));
  }

  @Test
  void aSlotRunsOnlyItsTypesImages() {
    lease("dev-qits-ci", "CI", "run-1", WORKSPACE_IMAGE, "")
        .then()
        .statusCode(400)
        .body("code", equalTo("IMAGE_NOT_ALLOWED"));
    lease("dev-qits-ci", "CI", "run-2", "registry.qits.wohlben.eu/qits/build-images/ci-slotty:1", "")
        .then()
        .statusCode(400)
        .body("code", equalTo("IMAGE_NOT_ALLOWED"));
  }

  @Test
  void aPolicyOwnedEnvKeyOrRootIsRefused() {
    for (String key : new String[] {"HOME", "CLAUDE_CONFIG_DIR", "BUILDKIT_HOST"}) {
      service("dev-qits-workspaces")
          .contentType("application/json")
          .body(
              "{\"slotType\":\"WORKSPACE\",\"ownerRef\":\"w\",\"spec\":{\"image\":\""
                  + WORKSPACE_IMAGE
                  + "\",\"env\":{\""
                  + key
                  + "\":\"/x\"}}}")
          .post(API)
          .then()
          .statusCode(400)
          .body("code", equalTo("ENV_KEY_NOT_ALLOWED"));
    }
    service("dev-qits-workspaces")
        .contentType("application/json")
        .body(
            "{\"slotType\":\"WORKSPACE\",\"ownerRef\":\"w\",\"spec\":{\"image\":\""
                + WORKSPACE_IMAGE
                + "\",\"user\":\"0\"}}")
        .post(API)
        .then()
        .statusCode(400);
  }

  @Test
  void aServiceReadsItsOwnLeasesAndAnAdministratorReadsAll() {
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").then().statusCode(201);
    String workspace =
        lease("dev-qits-workspaces", "WORKSPACE", "17", WORKSPACE_IMAGE, "").path("id");
    service("dev-qits-ci").get(API).then().statusCode(200).body("$", hasSize(1)).body("[0].ownerRef", equalTo("run-1"));
    service("dev-qits-ci").get(API + "/" + workspace).then().statusCode(404);
    given()
        .header("Authorization", ContractBearers.bearer("an-operator", "qits:admin"))
        .get(API + "?slotType=WORKSPACE&state=QUEUED")
        .then()
        .statusCode(200)
        .body("$", hasSize(1));
    given()
        .header("Authorization", ContractBearers.bearer("dyn-agent", "qits:agent"))
        .get(API)
        .then()
        .statusCode(200)
        .body("$", hasSize(2));
    service("dev-qits-ci").get(API + "?state=NOPE").then().statusCode(400);
  }

  /**
   * The delivery desk's administrator ({@code qits:desk-admin}, epic qits-1621) reads every lease
   * like an administrator or an agent does, and may read one that is not its own.
   */
  @Test
  void aDeskAdministratorListsEveryLease() {
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").then().statusCode(201);
    String workspace =
        lease("dev-qits-workspaces", "WORKSPACE", "17", WORKSPACE_IMAGE, "").path("id");
    given()
        .header("Authorization", ContractBearers.bearer("delivery-desk-admin", "qits:desk-admin"))
        .get(API)
        .then()
        .statusCode(200)
        .body("$", hasSize(2));
    given()
        .header("Authorization", ContractBearers.bearer("delivery-desk-admin", "qits:desk-admin"))
        .get(API + "/" + workspace)
        .then()
        .statusCode(200)
        .body("ownerRef", equalTo("17"));
  }

  @Test
  void noDoorAnswersASpecEnvValueOnlyItsKey() throws Exception {
    String secret = "qits_tok_never_on_a_door";
    String spec =
        "{\"image\":\""
            + WORKSPACE_IMAGE
            + "\",\"env\":{\"QITS_TOKEN\":\""
            + secret
            + "\"},\"user\":\"1001\",\"limits\":{\"memory\":\"2g\"}}";
    Response created =
        service("dev-qits-workspaces")
            .contentType("application/json")
            .body("{\"slotType\":\"WORKSPACE\",\"ownerRef\":\"17\",\"spec\":" + spec + "}")
            .post(API);
    created.then().statusCode(201).body("spec.env.QITS_TOKEN", equalTo(LeaseSpecs.REDACTED));
    assertFalse(created.asString().contains(secret), "the create answer carries no env value");
    String id = created.path("id");
    String hash = LeaseSpecs.hash(new ObjectMapper().readTree(spec));
    assertEquals(hash, created.path("specHash"), "the hash is the real spec's");

    RequestSpecification agent =
        given().header("Authorization", ContractBearers.bearer("dyn-agent", "qits:agent"));
    RequestSpecification owner = service("dev-qits-workspaces");
    for (RequestSpecification caller : new RequestSpecification[] {agent, owner}) {
      Response list = given().spec(caller).get(API);
      list.then()
          .statusCode(200)
          .body("[0].spec.env.QITS_TOKEN", equalTo(LeaseSpecs.REDACTED))
          .body("[0].spec.image", equalTo(WORKSPACE_IMAGE))
          .body("[0].specHash", equalTo(hash));
      assertFalse(list.asString().contains(secret));
      Response one = given().spec(caller).get(API + "/" + id);
      one.then()
          .statusCode(200)
          .body("spec.env.QITS_TOKEN", equalTo(LeaseSpecs.REDACTED))
          .body("spec.user", equalTo("1001"))
          .body("spec.limits.memory", equalTo("2g"))
          .body("specHash", equalTo(hash));
      assertFalse(one.asString().contains(secret));
    }
    String stored = TargetRows.reload(UUID.fromString(id)).spec;
    assertEquals(
        secret,
        new ObjectMapper().readTree(stored).get("env").get("QITS_TOKEN").asText(),
        "the stored spec keeps the real value");

    Response replaced =
        service("dev-qits-workspaces")
            .contentType("application/json")
            .body("{\"spec\":" + spec + "}")
            .put(API + "/" + id + "/spec");
    replaced
        .then()
        .statusCode(200)
        .body("spec.env.QITS_TOKEN", equalTo(LeaseSpecs.REDACTED))
        .body("specHash", equalTo(hash));
    assertFalse(replaced.asString().contains(secret), "the replace answer carries no env value");
  }

  @Test
  void endingAQueuedLeaseEndsItAtOnceAndFreesItsRef() {
    String id = lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").path("id");
    service("dev-qits-workspaces").delete(API + "/" + id).then().statusCode(403).body("code", equalTo("NOT_YOUR_LEASE"));
    service("dev-qits-ci").delete(API + "/" + id).then().statusCode(200).body("state", equalTo("ENDED"));
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").then().statusCode(201);
  }

  @Test
  void endingARunningLeaseIsEndingUntilTheRunnerSaysItIsGone() throws Exception {
    UUID target = UUID.randomUUID();
    connectedLab(target);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    service("dev-qits-ci").delete(API + "/" + id).then().statusCode(200).body("state", equalTo("ENDING"));
    assertEquals(LeaseState.ENDING, TargetRows.reload(id).state);
  }

  @Test
  void endingARunningLeaseOnATargetWithNoRunnerEndsItAtOnce() {
    // No runner has dialled for lab: nothing would ever report the container gone (qits-2227).
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID running = UUID.randomUUID();
    TargetRows.lease(running, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    service("dev-qits-ci")
        .delete(API + "/" + running)
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDED"))
        .body("endedAt", notNullValue());
    Lease row = TargetRows.reload(running);
    assertEquals(LeaseState.ENDED, row.state);
    assertNull(row.endingSince, "it never was ENDING");
    assertTrue(
        allocation.estateOf(target).leases().isEmpty(),
        "an ENDED lease leaves the estate, so a runner that comes back removes its container");

    UUID granted = UUID.randomUUID();
    TargetRows.lease(granted, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.GRANTED, target);
    operator("an-operator", "qits:admin")
        .delete(API + "/" + granted + "?reason=the node is gone")
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDED"))
        .body("detail", equalTo("ended by an-operator: the node is gone"));
    assertEquals("ENDED", TargetRows.audit(granted).get(0).outcome);
  }

  static RequestSpecification operator(String principal, String roles) {
    return given().header("Authorization", ContractBearers.bearer(principal, roles));
  }

  @Test
  void anOperatorEndsARunningLeaseOfAnyOwnerAndItIsEnding() throws Exception {
    UUID target = UUID.randomUUID();
    connectedLab(target);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    operator("an-operator", "qits:admin")
        .delete(API + "/" + id + "?reason=rotating the node")
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDING"))
        .body("detail", equalTo("ended by an-operator: rotating the node"));
    Lease row = TargetRows.reload(id);
    assertEquals(LeaseState.ENDING, row.state);
    assertNotNull(row.endingSince, "every entry into ENDING starts the deadline");
    List<TargetAudit> audit = TargetRows.audit(id);
    assertEquals(1, audit.size());
    assertEquals("LEASE_END", audit.get(0).operation);
    assertEquals("ENDING", audit.get(0).outcome);
    assertEquals("an-operator", audit.get(0).actor);
    assertEquals("qits:admin", audit.get(0).roles);
    assertEquals(target, audit.get(0).targetId);
    assertTrue(audit.get(0).args.contains("rotating the node"), audit.get(0).args);
  }

  @Test
  void anOperatorEndsAnEndingLeaseAtOnceAndTheDetailSaysWhoAndWhy() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID id = UUID.randomUUID();
    TargetRows.ending(id, SlotType.WORKSPACE, "qits-workspaces", "17", target, TargetRows.T0);
    operator("an-operator", "qits:admin-agent")
        .delete(API + "/" + id + "?reason=stuck since monday")
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDED"))
        .body("detail", equalTo("ended by an-operator: stuck since monday"))
        .body("endedAt", notNullValue());
    assertEquals(LeaseState.ENDED, TargetRows.reload(id).state);
    assertTrue(
        allocation.estateOf(target).leases().isEmpty(),
        "an ENDED lease leaves its target's estate, so the runner removes what is left of it");

    List<TargetAudit> audit = TargetRows.audit(id);
    assertEquals(1, audit.size());
    assertEquals("ENDED", audit.get(0).outcome);
    assertEquals("ended by an-operator: stuck since monday", audit.get(0).detail);

    operator("an-operator", "qits:admin")
        .delete(API + "/" + id)
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDED"))
        .body("detail", equalTo("ended by an-operator: stuck since monday"));
    assertEquals("NOOP", TargetRows.audit(id).get(1).outcome, "a repeated end is recorded, and moves nothing");
  }

  @Test
  void theDeskAdministratorIsAnOperatorAndAReasonIsOptional() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID id = UUID.randomUUID();
    TargetRows.ending(id, SlotType.DESK, "qits-projects", "desk-1", target, TargetRows.T0);
    operator("dev-qits-desk-7", "qits:desk-admin")
        .delete(API + "/" + id)
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDED"))
        .body("detail", equalTo("ended by dev-qits-desk-7"));
    assertEquals("qits:desk-admin", TargetRows.audit(id).get(0).roles);
  }

  @Test
  void aServiceIsStillRefusedAnotherOwnersLeaseAndAnOperatorRoleWins() throws Exception {
    UUID target = UUID.randomUUID();
    connectedLab(target);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    service("dev-qits-workspaces")
        .delete(API + "/" + id + "?reason=mine now")
        .then()
        .statusCode(403)
        .body("code", equalTo("NOT_YOUR_LEASE"));
    assertEquals(LeaseState.RUNNING, TargetRows.reload(id).state);
    assertTrue(TargetRows.audit(id).isEmpty(), "an owner's door writes no audit row");
    operator("dyn-agent", "qits:agent").delete(API + "/" + id).then().statusCode(403);
    operator("dev-qits-workspaces", "qits:system,qits:admin")
        .delete(API + "/" + id)
        .then()
        .statusCode(200)
        .body("state", equalTo("ENDING"));
  }

  @Test
  void anOperatorsEndOfNoLeaseIsRecordedToo() {
    UUID missing = UUID.randomUUID();
    operator("an-operator", "qits:admin").delete(API + "/" + missing).then().statusCode(404);
    List<TargetAudit> audit = TargetRows.audit(missing);
    assertEquals(1, audit.size());
    assertEquals("NOT_FOUND", audit.get(0).outcome);
    operator("an-operator", "qits:admin").delete(API + "/not-a-lease").then().statusCode(404);
    assertEquals(
        1L,
        (long)
            QuarkusTransaction.requiringNew()
                .call(() -> TargetAudit.count("resource = ?1 and outcome = ?2", "not-a-lease", "NOT_FOUND")));
  }

  // --- the refresh (qits-2091) -------------------------------------------------------------------

  @Test
  void anOwnerRefreshesItsRunningLeaseAndTheEstateCarriesTheNewGeneration() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.RUNNING, target);
    assertEquals(0L, allocation.estateOf(target).leases().get(0).generation());
    service("dev-qits-workspaces")
        .post(API + "/" + id + "/refresh")
        .then()
        .statusCode(200)
        .body("id", equalTo(id.toString()))
        .body("state", equalTo("RUNNING"))
        .body("generation", equalTo(1))
        .body("resolvers", hasSize(0))
        .body("resolversStale", equalTo(false));
    service("dev-qits-workspaces")
        .post(API + "/" + id + "/refresh")
        .then()
        .statusCode(200)
        .body("generation", equalTo(2));
    assertEquals(2L, TargetRows.reload(id).generation);
    assertEquals(LeaseState.RUNNING, TargetRows.reload(id).state, "a refresh moves no state");
    assertEquals(
        2L,
        allocation.estateOf(target).leases().get(0).generation(),
        "the estate entry carries the lease's generation");
    assertTrue(TargetRows.audit(id).isEmpty(), "an owner's refresh writes no audit row");
  }

  @Test
  void anOperatorRefreshesAnyRunningLeaseAndItIsAudited() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.DESK, "qits-projects", "desk-1", LeaseState.RUNNING, target);
    for (String role : new String[] {"qits:admin", "qits:admin-agent", "qits:desk-admin"}) {
      operator("an-operator", role).post(API + "/" + id + "/refresh").then().statusCode(200);
    }
    assertEquals(3L, TargetRows.reload(id).generation);
    List<TargetAudit> audit = TargetRows.audit(id);
    assertEquals(3, audit.size());
    assertEquals("LEASE_REFRESH", audit.get(0).operation);
    assertEquals("REFRESHED", audit.get(0).outcome);
    assertEquals("an-operator", audit.get(0).actor);
    assertEquals("qits:admin", audit.get(0).roles);
    assertEquals(target, audit.get(0).targetId);
    assertEquals("generation 1", audit.get(0).detail);
  }

  @Test
  void aRefreshIsRefusedAnotherOwnerAnAgentAndALeaseThatIsNotRunning() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    service("dev-qits-workspaces")
        .post(API + "/" + id + "/refresh")
        .then()
        .statusCode(403)
        .body("code", equalTo("NOT_YOUR_LEASE"));
    operator("dyn-agent", "qits:agent").post(API + "/" + id + "/refresh").then().statusCode(403);
    assertEquals(0L, TargetRows.reload(id).generation);

    UUID granted = UUID.randomUUID();
    TargetRows.lease(granted, SlotType.CI, "qits-ci", "run-10", LeaseState.GRANTED, target);
    service("dev-qits-ci")
        .post(API + "/" + granted + "/refresh")
        .then()
        .statusCode(409)
        .body("code", equalTo("LEASE_NOT_RUNNING"))
        .body("message", org.hamcrest.Matchers.containsString("GRANTED"));
    operator("an-operator", "qits:admin")
        .post(API + "/" + granted + "/refresh")
        .then()
        .statusCode(409)
        .body("code", equalTo("LEASE_NOT_RUNNING"));
    assertEquals("LEASE_NOT_RUNNING", TargetRows.audit(granted).get(0).outcome);
    assertEquals(0L, TargetRows.reload(granted).generation);

    UUID missing = UUID.randomUUID();
    service("dev-qits-ci").post(API + "/" + missing + "/refresh").then().statusCode(404);
    operator("an-operator", "qits:admin").post(API + "/" + missing + "/refresh").then().statusCode(404);
    assertEquals("NOT_FOUND", TargetRows.audit(missing).get(0).outcome);
    service("dev-qits-ci").post(API + "/not-a-lease/refresh").then().statusCode(404);
  }

  @Test
  void aRollReplacesTheSpecAndItsHash() {
    Response created = lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "");
    String id = created.path("id");
    String before = created.path("specHash");
    String after =
        service("dev-qits-ci")
            .contentType("application/json")
            .body("{\"spec\":{\"image\":\"" + CI_IMAGE + "\",\"env\":{\"A\":\"b\"}}}")
            .put(API + "/" + id + "/spec")
            .then()
            .statusCode(200)
            .extract()
            .path("specHash");
    assertNotNull(after);
    org.junit.jupiter.api.Assertions.assertNotEquals(before, after);
  }

  @Test
  void theSameSpecIsTheSameHashWhateverItsKeyOrder() {
    String a =
        service("dev-qits-ci")
            .contentType("application/json")
            .body("{\"slotType\":\"CI\",\"ownerRef\":\"a\",\"spec\":{\"image\":\"" + CI_IMAGE + "\",\"user\":\"1001\",\"env\":{\"X\":\"1\",\"Y\":\"2\"}}}")
            .post(API)
            .path("specHash");
    String b =
        service("dev-qits-ci")
            .contentType("application/json")
            .body("{\"slotType\":\"CI\",\"ownerRef\":\"b\",\"spec\":{\"env\":{\"Y\":\"2\",\"X\":\"1\"},\"user\":\"1001\",\"image\":\"" + CI_IMAGE + "\"}}")
            .post(API)
            .path("specHash");
    assertEquals(a, b);
  }

  @Test
  void aWorkVolumeIsPurgedOnlyOnceNoLiveLeaseUsesIt() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    service("dev-qits-workspaces").delete("/system/api/work-volumes/ws-17").then().statusCode(404);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkVolume volume = new WorkVolume();
              volume.ownerApp = "qits-workspaces";
              volume.workKey = "ws-17";
              volume.targetId = target;
              volume.volumeName = "qits-system-runner-work-ws-17-" + target.toString().substring(0, 8);
              volume.createdAt = Instant.now();
              volume.persist();
            });
    UUID live = UUID.randomUUID();
    TargetRows.lease(live, SlotType.WORKSPACE, "qits-workspaces", "17", LeaseState.RUNNING, target);
    QuarkusTransaction.requiringNew()
        .run(() -> eu.wohlben.qits.system.targets.entity.Lease.<eu.wohlben.qits.system.targets.entity.Lease>findById(live).workKey = "ws-17");
    service("dev-qits-workspaces")
        .delete("/system/api/work-volumes/ws-17")
        .then()
        .statusCode(409)
        .body("code", equalTo("WORK_VOLUME_IN_USE"));
    service("dev-qits-ci").delete("/system/api/work-volumes/ws-17").then().statusCode(404);
    QuarkusTransaction.requiringNew()
        .run(() -> eu.wohlben.qits.system.targets.entity.Lease.<eu.wohlben.qits.system.targets.entity.Lease>findById(live).state = LeaseState.ENDED);
    service("dev-qits-workspaces")
        .delete("/system/api/work-volumes/ws-17")
        .then()
        .statusCode(202)
        .body("purgeRequestedAt", notNullValue());
    assertEquals(
        java.util.List.of("qits-system-runner-work-ws-17-" + target.toString().substring(0, 8)),
        allocation.estateOf(target).purges());
  }
}
