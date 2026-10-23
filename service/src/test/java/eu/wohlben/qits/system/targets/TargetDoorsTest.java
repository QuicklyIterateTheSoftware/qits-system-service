package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code /system/api/targets} doors against a real store and the idp stub. */
@QuarkusTest
class TargetDoorsTest {

  private static final String API = "/system/api/targets";

  @Inject Targets targets;

  @Inject ContractBearers bearers;

  @BeforeEach
  void clean() {
    TargetRows.clear();
    IdpStub.reset();
    bearers.reset();
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  private static RequestSpecification as(String user, String roles) {
    return given().header("X-Qits-User", user).header("X-Qits-Roles", roles);
  }

  private static RequestSpecification admin() {
    return as("an-operator", "qits:admin");
  }

  private static Response create(String name, String minimums) {
    return admin()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"description\":\"the lab box\",\"minimums\":"
                + minimums
                + "}")
        .post(API);
  }

  @Test
  void aCreateAnswersTheTargetItsTokenAndItsInstallLineOnce() {
    Response created = create("lab", "{\"ci\":2,\"workspace\":1}");
    created.then().statusCode(201);
    String id = created.path("target.id");
    created
        .then()
        .body("target.name", equalTo("lab"))
        .body("target.minimums.ci", equalTo(2))
        .body("target.minimums.workspace", equalTo(1))
        .body("target.minimums.desk", equalTo(0))
        .body("target.quarantined", is(true))
        .body("target.connected", is(false))
        .body("target.loginCommand", nullValue())
        .body("registrationToken", equalTo("qits_tok_suite-1"))
        .body("installLine", startsWith("curl -fsSL -H 'Authorization: Bearer qits_tok_suite-1' "))
        .body("installLine", containsString("https://system.qits.wohlben.eu/system/api/targets/install.sh"))
        .body("installLine", containsString("QITS_SYSTEM_RUNNER_ID='" + id + "'"))
        .body("installLine", containsString("QITS_SYSTEM_RUNNER_SLOTS='3'"));
    assertEquals(java.util.List.of("POST /idp/api/tokens"), IdpStub.requests());
    Target row = targets.get(UUID.fromString(id));
    assertEquals("tok-1", row.registrationTokenId);
    assertEquals("tok-subject-1", row.registrationTokenSubject);
  }

  @Test
  void aTakenOrMalformedNameIsRefusedBeforeAnythingIsMinted() {
    create("lab", "null").then().statusCode(201);
    IdpStub.reset();
    create("lab", "null").then().statusCode(409).body("code", equalTo("TARGET_NAME_TAKEN"));
    create("Not A Name", "null").then().statusCode(400);
    create("neg", "{\"ci\":-1}").then().statusCode(400);
    assertEquals(java.util.List.of(), IdpStub.requests());
  }

  @Test
  void anAgentReadsTargetsAndDeclaresNone() {
    create("lab", "null").then().statusCode(201);
    as("dyn-agent", "qits:agent").get(API).then().statusCode(200).body("name", hasItem("lab"));
    as("dyn-agent", "qits:agent")
        .contentType("application/json")
        .body("{\"name\":\"other\"}")
        .post(API)
        .then()
        .statusCode(403);
  }

  @Test
  void theMinimumsMayNotExceedAReportedTotal() {
    UUID id = UUID.randomUUID();
    TargetRows.target(id, "lab", 4, "client-lab", false);
    admin()
        .contentType("application/json")
        .body("{\"minimums\":{\"ci\":2,\"workspace\":2,\"desk\":1}}")
        .patch(API + "/" + id)
        .then()
        .statusCode(409)
        .body("code", equalTo("MINIMUMS_EXCEED_TOTAL"));
    admin()
        .contentType("application/json")
        .body("{\"minimums\":{\"ci\":2,\"workspace\":2}, \"description\":\"four slots\"}")
        .patch(API + "/" + id)
        .then()
        .statusCode(200)
        .body("minimums.ci", equalTo(2))
        .body("minimums.workspace", equalTo(2))
        .body("description", equalTo("four slots"));
  }

  @Test
  void beforeTheFirstHelloAnySumGoes() {
    UUID id = UUID.randomUUID();
    TargetRows.target(id, "fresh", 0, null, true);
    admin()
        .contentType("application/json")
        .body("{\"minimums\":{\"ci\":9}}")
        .patch(API + "/" + id)
        .then()
        .statusCode(200)
        .body("minimums.ci", equalTo(9));
  }

  @Test
  void aTargetHoldingALiveLeaseCannotBeDeleted() {
    UUID id = UUID.randomUUID();
    TargetRows.target(id, "busy", 4, "client-busy", false);
    UUID lease = UUID.randomUUID();
    TargetRows.lease(lease, SlotType.CI, "qits-ci", "run-1", LeaseState.RUNNING, id);
    admin().delete(API + "/" + id).then().statusCode(409).body("code", equalTo("TARGET_BUSY"));
    TargetRows.clear();
    TargetRows.target(id, "busy", 4, "client-busy", false);
    TargetRows.lease(lease, SlotType.CI, "qits-ci", "run-1", LeaseState.ENDED, id);
    admin().delete(API + "/" + id).then().statusCode(204);
    assertNull(TargetRows.reloadTarget(id));
    assertNull(TargetRows.reload(lease).targetId, "an ended lease outlives its target, unplaced");
    assertTrue(IdpStub.requests().contains("DELETE /idp/api/clients/client-busy"));
  }

  @Test
  void aDisconnectedTargetCannotBeAskedAnything() {
    UUID id = UUID.randomUUID();
    TargetRows.target(id, "away", 4, "client-away", false);
    admin().post(API + "/" + id + "/healthcheck").then().statusCode(409).body("code", equalTo("TARGET_UNAVAILABLE"));
    admin().post(API + "/" + id + "/login-check").then().statusCode(409).body("code", equalTo("TARGET_UNAVAILABLE"));
    admin().get(API + "/" + id + "/health").then().statusCode(204);
    admin().get(API + "/" + UUID.randomUUID()).then().statusCode(404);
    admin().get(API + "/not-a-uuid").then().statusCode(404);
  }

  @Test
  void aGreenlightLiftsTheQuarantineAndOnlyAnAdministratorGivesOne() {
    UUID id = UUID.randomUUID();
    TargetRows.target(id, "q", 4, "client-q", true);
    as("a-service", "qits:system").post(API + "/" + id + "/greenlight").then().statusCode(403);
    admin().post(API + "/" + id + "/greenlight").then().statusCode(200).body("quarantined", is(false));
  }

  @Test
  void aRotationAnswersANewLineAndGivesTheOldTokenBack() {
    String id = create("lab", "null").path("target.id");
    admin()
        .post(API + "/" + id + "/registration-token")
        .then()
        .statusCode(200)
        .body("registrationToken", equalTo("qits_tok_suite-2"))
        .body("installLine", containsString("qits_tok_suite-2"));
    assertTrue(IdpStub.requests().contains("DELETE /idp/api/tokens/tok-1"));
  }

  @Test
  void theInstallScriptNamesThePinnedRunnerAndPassesTheVolumesThrough() {
    String script =
        as("runner", "qits:target-runner-registration")
            .get(API + "/install.sh")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertTrue(
        script.contains("registry.qits.wohlben.eu/qits/qits-system-runner:" + SystemRunnerBinary.VERSION),
        script);
    for (String variable : new String[] {"CREDENTIAL_VOLUME", "M2_VOLUME", "PNPM_VOLUME"}) {
      assertTrue(script.contains("QITS_SYSTEM_RUNNER_" + variable), variable);
    }
    assertFalse(script.contains("qits_tok_"), "the generic script carries no token");
  }

  @Test
  void aRunnerRegistersOnceWithItsOwnToken() {
    UUID id = UUID.fromString(create("lab", "null").path("target.id"));
    bearers.grant("Bearer qits_tok_suite-1", "tok-subject-1", "qits:target-runner-registration");
    given()
        .header("Authorization", "Bearer qits_tok_suite-1")
        .contentType("application/json")
        .body("{\"capabilities\":{\"docker\":true,\"arch\":\"amd64\"}}")
        .post(API + "/" + id + "/register")
        .then()
        .statusCode(200)
        .body("clientId", equalTo("target-runner-client-1"))
        .body("secret", equalTo("target-runner-s3cr3t-1"))
        .body("tokenUrl", equalTo("https://idp.qits.wohlben.eu/idp/token"))
        .body("audience", equalTo("qits-platform"))
        .body("socketUrl", equalTo("wss://system.qits.wohlben.eu/system/targets/socket"));
    Target row = targets.get(id);
    assertEquals("target-runner-client-1", row.clientId);
    assertNull(row.registrationTokenId);
    assertTrue(IdpStub.requests().contains("DELETE /idp/api/tokens/tok-1"));
    given()
        .header("Authorization", "Bearer qits_tok_suite-1")
        .contentType("application/json")
        .body("{}")
        .post(API + "/" + id + "/register")
        .then()
        .statusCode(409)
        .body("code", equalTo("ALREADY_REGISTERED"));
  }

  @Test
  void anotherTargetsTokenDoesNotRegisterThisOne() {
    UUID id = UUID.fromString(create("lab", "null").path("target.id"));
    bearers.grant("Bearer someone-else", "tok-subject-99", "qits:target-runner-registration");
    given()
        .header("Authorization", "Bearer someone-else")
        .contentType("application/json")
        .body("{}")
        .post(API + "/" + id + "/register")
        .then()
        .statusCode(403);
    admin().contentType("application/json").body("{}").post(API + "/" + id + "/register").then().statusCode(403);
    assertEquals(null, targets.get(id).clientId);
  }

  @Test
  void theListCarriesTheUsedSlotsPerType() {
    UUID id = UUID.randomUUID();
    TargetRows.target(id, "lab", 6, "client-lab", false);
    TargetRows.lease(UUID.randomUUID(), SlotType.CI, "qits-ci", "r1", LeaseState.RUNNING, id);
    TargetRows.lease(UUID.randomUUID(), SlotType.CI, "qits-ci", "r2", LeaseState.ENDING, id);
    TargetRows.lease(UUID.randomUUID(), SlotType.WORKSPACE, "qits-workspaces", "w1", LeaseState.GRANTED, id);
    TargetRows.lease(UUID.randomUUID(), SlotType.DESK, "qits-projects", "d1", LeaseState.EXITED, id);
    admin()
        .get(API + "/" + id)
        .then()
        .statusCode(200)
        .body("used.ci", equalTo(2))
        .body("used.workspace", equalTo(1))
        .body("used.desk", equalTo(0))
        .body("slotsTotal", equalTo(6))
        .body("login.claude", equalTo("UNKNOWN"))
        .body("kimiLoginCommand", nullValue())
        .body("runnerVersion", nullValue());
  }
}
