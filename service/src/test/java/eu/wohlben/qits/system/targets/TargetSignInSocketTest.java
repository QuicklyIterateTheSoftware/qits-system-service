package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.system.targets.runner.TargetSignIns;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInCancel;
import eu.wohlben.qits.systemrunner.protocol.SignInCode;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A harness sign-in end to end (qits-1593): the {@code /sign-ins} doors against a real runner
 * socket. What the runner is sent, what its {@code signInProgress} moves, and what ends one — a
 * cancel, a disconnect, the clock.
 */
@QuarkusTest
class TargetSignInSocketTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final String CLAUDE_URL =
      "https://claude.com/cai/oauth/authorize?code=true&client_id=suite";

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject TargetSignIns signIns;

  private UUID target;

  private String api;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    api = "/system/api/targets/" + target + "/sign-ins";
    TargetRows.target(target, "lab", 4, CLIENT, false);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  private static RequestSpecification admin() {
    return given().header("X-Qits-User", "an-operator").header("X-Qits-Roles", "qits:admin");
  }

  /** Dials and says hello; served once its estate arrived, which follows the greeting. */
  private FakeTargetRunner servedRunner() throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    runner.await(Ack.class);
    runner.await(Estate.class);
    return runner;
  }

  private Response start(String harness) {
    return admin()
        .contentType("application/json")
        .body("{\"harness\":\"" + harness + "\"}")
        .post(api);
  }

  /** Polls the sign-in until it is {@code state}. */
  private Response awaitState(String signInId, SignInState state) throws InterruptedException {
    long deadline = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    Response read = null;
    while (System.currentTimeMillis() < deadline) {
      read = admin().get(api + "/" + signInId);
      if (read.statusCode() == 200 && state.name().equals(read.path("state"))) {
        return read;
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "sign-in " + signInId + " never became " + state + ": " + (read == null ? "" : read.asString()));
  }

  @Test
  void aClaudeSignInRunsOnTheRunnerAndTheCodeIsRelayedToIt() throws Exception {
    try (FakeTargetRunner runner = servedRunner()) {
      Response started = start("claude");
      started
          .then()
          .statusCode(202)
          .body("targetId", equalTo(target.toString()))
          .body("harness", equalTo("claude"))
          .body("state", equalTo("STARTING"))
          .body("url", nullValue())
          .body("userCode", nullValue())
          .body("detail", nullValue());
      String id = started.path("signInId");
      SignIn sent = runner.await(SignIn.class);
      assertEquals(id, sent.signInId());
      assertEquals("claude", sent.harness());

      start("claude")
          .then()
          .statusCode(409)
          .body("code", equalTo("SIGN_IN_RUNNING"))
          .body("message", containsString(id));
      admin()
          .contentType("application/json")
          .body("{\"code\":\"too-early\"}")
          .post(api + "/" + id + "/code")
          .then()
          .statusCode(409)
          .body("code", equalTo("SIGN_IN_NOT_AWAITING_CODE"));

      runner.signInProgress(id, "claude", SignInState.AWAITING_CODE, CLAUDE_URL, null, null);
      awaitState(id, SignInState.AWAITING_CODE).then().body("url", equalTo(CLAUDE_URL));
      admin()
          .contentType("application/json")
          .body("{\"code\":\"\"}")
          .post(api + "/" + id + "/code")
          .then()
          .statusCode(400);
      admin()
          .contentType("application/json")
          .body("{\"code\":\"the-browser-code#state\"}")
          .post(api + "/" + id + "/code")
          .then()
          .statusCode(202)
          .body("state", equalTo("AWAITING_CODE"));
      SignInCode code = runner.await(SignInCode.class);
      assertEquals(id, code.signInId());
      assertEquals("the-browser-code#state", code.code());

      runner.signInProgress(id, "claude", SignInState.SUCCEEDED, null, null, null);
      awaitState(id, SignInState.SUCCEEDED)
          .then()
          .body("url", equalTo(CLAUDE_URL))
          .body("detail", nullValue());
      start("claude").then().statusCode(202).body("state", equalTo("STARTING"));
    }
  }

  @Test
  void aReportMovesOnlyTheSignInItNamesAndNeverItsHarness() throws Exception {
    try (FakeTargetRunner runner = servedRunner()) {
      String id = start("claude").then().statusCode(202).extract().path("signInId");
      runner.await(SignIn.class);
      runner.signInProgress(
          UUID.randomUUID().toString(), "claude", SignInState.FAILED, null, null, "not ours");
      runner.signInProgress(id, "kimi", SignInState.AWAITING_CODE, CLAUDE_URL, null, null);
      awaitState(id, SignInState.AWAITING_CODE).then().body("harness", equalTo("claude"));
      start("kimi").then().statusCode(202).body("harness", equalTo("kimi"));
    }
  }

  @Test
  void aCancelTellsTheRunnerAndSettlesAtOnce() throws Exception {
    try (FakeTargetRunner runner = servedRunner()) {
      String id = start("kimi").then().statusCode(202).extract().path("signInId");
      runner.await(SignIn.class);
      runner.signInProgress(
          id,
          "kimi",
          SignInState.AWAITING_BROWSER,
          "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234",
          "ABCD-1234",
          null);
      awaitState(id, SignInState.AWAITING_BROWSER).then().body("userCode", equalTo("ABCD-1234"));
      admin()
          .contentType("application/json")
          .body("{\"code\":\"c\"}")
          .post(api + "/" + id + "/code")
          .then()
          .statusCode(409)
          .body("code", equalTo("SIGN_IN_NOT_AWAITING_CODE"));

      admin().delete(api + "/" + id).then().statusCode(202).body("state", equalTo("CANCELLED"));
      assertEquals(id, runner.await(SignInCancel.class).signInId());
      runner.signInProgress(id, "kimi", SignInState.SUCCEEDED, null, null, null);
      runner.signInProgress(id, "kimi", SignInState.CANCELLED, null, null, null);
      admin().delete(api + "/" + id).then().statusCode(202).body("state", equalTo("CANCELLED"));
      admin()
          .get(api + "/" + id)
          .then()
          .statusCode(200)
          .body("state", equalTo("CANCELLED"))
          .body("userCode", equalTo("ABCD-1234"));
    }
  }

  @Test
  void aRunnerThatDisconnectsFailsItsSignIn() throws Exception {
    String id;
    try (FakeTargetRunner runner = servedRunner()) {
      id = start("claude").then().statusCode(202).extract().path("signInId");
      runner.await(SignIn.class);
    }
    awaitState(id, SignInState.FAILED)
        .then()
        .body("detail", equalTo(TargetSignIns.RUNNER_DISCONNECTED));
    start("claude").then().statusCode(409).body("code", equalTo("TARGET_UNAVAILABLE"));
  }

  @Test
  void anUnfinishedSignInExpiresAfterTenMinutesAndIsDroppedFifteenAfter() throws Exception {
    try (FakeTargetRunner runner = servedRunner()) {
      String id = start("claude").then().statusCode(202).extract().path("signInId");
      runner.await(SignIn.class);
      Instant now = Instant.now();
      signIns.sweep(now.plus(Duration.ofMinutes(9)));
      admin().get(api + "/" + id).then().statusCode(200).body("state", equalTo("STARTING"));

      signIns.sweep(now.plus(Duration.ofMinutes(10)).plusSeconds(1));
      admin()
          .get(api + "/" + id)
          .then()
          .statusCode(200)
          .body("state", equalTo("EXPIRED"))
          .body("detail", containsString("10 minutes"));
      assertEquals(id, runner.await(SignInCancel.class).signInId());

      signIns.sweep(Instant.now().plus(Duration.ofMinutes(14)));
      admin().get(api + "/" + id).then().statusCode(200);
      signIns.sweep(Instant.now().plus(Duration.ofMinutes(16)));
      admin().get(api + "/" + id).then().statusCode(404).body("code", equalTo("SIGN_IN_NOT_FOUND"));
    }
  }
}
