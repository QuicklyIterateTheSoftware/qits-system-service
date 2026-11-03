package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.system.testdocker.FakeDocker;
import eu.wohlben.qits.system.testdocker.TerminalClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * WHAT HAPPENS WHEN THE LAST VIEWER LEAVES — the one behaviour an operator notices as "the host
 * monitor stops when I navigate away".
 *
 * <p>Glances and a shell are answered differently on purpose: a host monitor holds nothing worth
 * coming back to, so it gets seconds; a shell holds a working directory and a half-typed command,
 * so it keeps the full window and survives a reload. Both windows are configured here, far apart,
 * so each test proves WHICH one was used rather than only that something eventually ended.
 *
 * <p>Linux-only, because the PTY is.
 */
@QuarkusTest
@TestProfile(TerminalLingerTest.ShortGlancesGrace.class)
@EnabledOnOs(OS.LINUX)
@TestSecurity(user = "an-operator", roles = "qits:admin")
class TerminalLingerTest {

  private static final String CONTAINER = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  /** The glances grace, as configured below — the tests wait in multiples of it. */
  private static final long GRACE_MILLIS = 1000;

  /**
   * A second apart from the general window, which stays long. A test that ends inside the general
   * window is the proof: nothing here can pass by accident on a timer both kinds share.
   */
  public static class ShortGlancesGrace implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.system.terminals.glances-linger", "PT1S",
          "qits.system.terminals.linger", "PT30S");
    }
  }

  @TestHTTPResource("/")
  URI base;

  /** Nothing may outlive a test: a leaked session holds a PTY and a child for the whole suite. */
  @AfterEach
  void endEveryTerminal() {
    given()
        .when()
        .get("/system/api/terminals")
        .then()
        .extract()
        .jsonPath()
        .getList("id", String.class)
        .forEach(id -> given().when().delete("/system/api/terminals/" + id).then().statusCode(204));
    FakeDocker.clearCalls();
  }

  @Test
  void theLastViewerLeavingEndsGlancesWithinTheShortGrace() throws Exception {
    String id = openGlances();
    try (TerminalClient viewer = attach(id)) {
      viewer.awaitText("fake-terminal ready");
    }

    // Well inside the 30-second general window, so reaching 404 here can only be the glances one.
    awaitGone(id, 10 * GRACE_MILLIS);

    // The belt the grace exists to reach: `--rm` fires when a CONTAINER exits, and killing the CLI
    // attached to it is not that.
    // The rm runs after the session leaves the map, once terminate() returns, so it is awaited
    // rather than asserted.
    awaitCalled("rm -f qits-system-glances-" + id, 10 * GRACE_MILLIS);
  }

  @Test
  void aShellKeepsTheFullWindowAfterItsLastClientLeaves() throws Exception {
    String id = openExec();
    try (TerminalClient user = attach(id)) {
      user.awaitText("fake-terminal ready");
    }

    Thread.sleep(4 * GRACE_MILLIS);

    // Four times the glances grace and still there: a reload does not cost an operator their shell.
    given().when().get("/system/api/terminals/" + id).then().statusCode(200);
  }

  @Test
  void aReattachInsideTheGraceKeepsGlancesAlive() throws Exception {
    String id = openGlances();
    try (TerminalClient first = attach(id)) {
      first.awaitText("fake-terminal ready");
    }

    // A reload: the socket closed and the next one opens within the grace, which is the whole
    // reason the grace is not zero.
    try (TerminalClient second = attach(id)) {
      second.awaitText("fake-terminal ready");
      Thread.sleep(4 * GRACE_MILLIS);

      given().when().get("/system/api/terminals/" + id).then().statusCode(200);
      second.sendData("still-here\n");
      second.awaitText("echo:still-here");
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String openGlances() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"GLANCES\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private String openExec() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"EXEC\",\"container\":\"" + CONTAINER + "\",\"shell\":\"sh\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private TerminalClient attach(String id) {
    return TerminalClient.connect(
        TerminalClient.socketUri(base, "/system/api/terminals/" + id), Map.of());
  }

  /** Poll until the session is gone, or fail saying how long it was still there. */
  private void awaitGone(String id, long budgetMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + budgetMillis;
    while (System.currentTimeMillis() < deadline) {
      if (given().when().get("/system/api/terminals/" + id).thenReturn().statusCode() == 404) {
        return;
      }
      Thread.sleep(50);
    }
    fail("terminal " + id + " was still running " + budgetMillis + "ms after its last viewer left");
  }

  /** Poll until the fake docker call happened, or fail saying how long it waited. */
  private void awaitCalled(String fragment, long budgetMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + budgetMillis;
    while (System.currentTimeMillis() < deadline) {
      if (FakeDocker.called(fragment)) {
        return;
      }
      Thread.sleep(50);
    }
    fail(
        "docker call \""
            + fragment
            + "\" did not happen within "
            + budgetMillis
            + "ms: "
            + FakeDocker.calls());
  }
}
