package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.testdocker.FakeDocker;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The terminal LIFECYCLE over REST — created, listed, read and ended. The bytes are
 * {@link TerminalSocketTest}'s.
 *
 * <p>Every terminal here is a REAL pseudo-terminal with a real child on it: the fake docker script
 * turns `run -it` and `exec -it` into an echo loop, and everything between the POST and the child
 * is the shipped code.
 */
@QuarkusTest
@TestSecurity(user = "an-operator", roles = "qits:admin")
class TerminalApiTest {

  private static final String CONTAINER = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  /** Nothing may outlive a test: a leaked session holds a PTY and a child for the whole suite. */
  @AfterEach
  void endEveryTerminal() {
    given()
        .when()
        .get("/system/api/terminals")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("id", String.class)
        .forEach(id -> given().when().delete("/system/api/terminals/" + id).then().statusCode(204));
    FakeDocker.clearCalls();
  }

  @Test
  void anExecTerminalIsCreatedAgainstTheResolvedContainer() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"kind\":\"EXEC\",\"container\":\"" + CONTAINER + "\",\"shell\":\"sh\"}")
            .when()
            .post("/system/api/terminals")
            .then()
            .statusCode(201)
            .contentType(ContentType.JSON)
            .body("kind", Matchers.equalTo("EXEC"))
            .body("shell", Matchers.equalTo("sh"))
            .body("container.name", Matchers.equalTo(CONTAINER))
            // The CANONICAL id, which is what the argv carries — never the caller's string.
            .body("container.id", Matchers.hasLength(64))
            .body("createdBy", Matchers.notNullValue())
            .body("socketPath", Matchers.startsWith("/system/api/terminals/"))
            .extract()
            .path("id");

    given().when().get("/system/api/terminals").then().statusCode(200).body("$", Matchers.hasSize(1));
    given()
        .when()
        .get("/system/api/terminals/" + id)
        .then()
        .statusCode(200)
        .body("id", Matchers.equalTo(id))
        .body("attachedClients", Matchers.equalTo(0));

    // The argv the service really ran: the canonical id and nothing the caller typed.
    assertTrue(FakeDocker.called("exec -it"), FakeDocker.calls().toString());
    assertTrue(
        FakeDocker.calls().stream().noneMatch(call -> call.startsWith("exec") && call.contains(CONTAINER)),
        "the caller's reference must not reach the exec argv: " + FakeDocker.calls());
  }

  @Test
  void aGlancesTerminalIsFoundRatherThanCreatedTwice() {
    String first =
        given()
            .contentType(ContentType.JSON)
            .body("{\"kind\":\"GLANCES\"}")
            .when()
            .post("/system/api/terminals")
            .then()
            .statusCode(201)
            .body("kind", Matchers.equalTo("GLANCES"))
            .body("container", Matchers.nullValue())
            .extract()
            .path("id");

    // There is one host, so a second request for its monitor is the same monitor — and 200 rather
    // than 201 is how the client is told it did not just start something.
    String second =
        given()
            .contentType(ContentType.JSON)
            .body("{\"kind\":\"GLANCES\"}")
            .when()
            .post("/system/api/terminals")
            .then()
            .statusCode(200)
            .extract()
            .path("id");

    assertEquals(first, second);
    given().when().get("/system/api/terminals").then().body("$", Matchers.hasSize(1));
  }

  @Test
  void endingAGlancesTerminalAlsoRemovesItsContainer() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"kind\":\"GLANCES\"}")
            .when()
            .post("/system/api/terminals")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given().when().delete("/system/api/terminals/" + id).then().statusCode(204);

    // `--rm` fires when a CONTAINER exits, and killing the CLI attached to it is not that. Without
    // this second act a glances container outlives every session that ever started one.
    assertTrue(
        FakeDocker.called("rm -f qits-system-glances-" + id),
        "the glances container was not removed: " + FakeDocker.calls());
    given().when().get("/system/api/terminals/" + id).then().statusCode(404);
  }

  @Test
  void aBadRequestIsRefusedBeforeAnythingIsSpawned() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"NONSENSE\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("kind must be GLANCES or EXEC"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"EXEC\",\"container\":\"" + CONTAINER + "\",\"shell\":\"zsh\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("shell must be bash or sh"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"EXEC\",\"shell\":\"sh\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("container is required"));

    given().when().get("/system/api/terminals").then().body("$", Matchers.hasSize(0));
  }

  @Test
  void anUnknownContainerIs404AndAStoppedOneIs409() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"EXEC\",\"container\":\"no-such-thing\",\"shell\":\"sh\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(404)
        .body("message", Matchers.containsString("no such container"));

    // `docker exec` into a stopped container fails with a message the browser could do nothing
    // with; resolving first turns it into a status the client can act on.
    given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"EXEC\",\"container\":\"a-stopped-one\",\"shell\":\"sh\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("is not running"));
  }

  @Test
  void anUnknownTerminalIsA404OnEveryVerb() {
    given().when().get("/system/api/terminals/nonsense").then().statusCode(404);
    given()
        .when()
        .get("/system/api/terminals/11111111-2222-3333-4444-555555555555")
        .then()
        .statusCode(404);
    given().when().delete("/system/api/terminals/nonsense").then().statusCode(404);
  }
}
