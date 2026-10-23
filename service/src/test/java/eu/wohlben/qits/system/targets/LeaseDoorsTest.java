package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code /system/api/leases} and {@code /work-volumes} doors: owners, refusals, life. */
@QuarkusTest
class LeaseDoorsTest {

  private static final String API = "/system/api/leases";

  static final String CI_IMAGE = "registry.qits.wohlben.eu/qits/build-images/ci-slot:2026.1";
  static final String WORKSPACE_IMAGE = "registry.qits.wohlben.eu/qits/workspace:2026.1";

  @Inject TargetAllocation allocation;

  @BeforeEach
  void clean() {
    TargetRows.clear();
  }

  static RequestSpecification service(String clientId) {
    return given().header("X-Qits-User", clientId).header("X-Qits-Roles", "qits:system");
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
        .header("X-Qits-User", "an-operator")
        .header("X-Qits-Roles", "qits:admin")
        .get(API + "?slotType=WORKSPACE&state=QUEUED")
        .then()
        .statusCode(200)
        .body("$", hasSize(1));
    given()
        .header("X-Qits-User", "dyn-agent")
        .header("X-Qits-Roles", "qits:agent")
        .get(API)
        .then()
        .statusCode(200)
        .body("$", hasSize(2));
    service("dev-qits-ci").get(API + "?state=NOPE").then().statusCode(400);
  }

  @Test
  void endingAQueuedLeaseEndsItAtOnceAndFreesItsRef() {
    String id = lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").path("id");
    service("dev-qits-workspaces").delete(API + "/" + id).then().statusCode(403).body("code", equalTo("NOT_YOUR_LEASE"));
    service("dev-qits-ci").delete(API + "/" + id).then().statusCode(200).body("state", equalTo("ENDED"));
    lease("dev-qits-ci", "CI", "run-1", CI_IMAGE, "").then().statusCode(201);
  }

  @Test
  void endingAGrantedLeaseIsEndingUntilTheRunnerSaysItIsGone() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, "client-lab", false);
    UUID id = UUID.randomUUID();
    TargetRows.lease(id, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    service("dev-qits-ci").delete(API + "/" + id).then().statusCode(200).body("state", equalTo("ENDING"));
    assertEquals(LeaseState.ENDING, TargetRows.reload(id).state);
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
