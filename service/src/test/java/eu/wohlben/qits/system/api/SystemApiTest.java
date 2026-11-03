package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Every read route, against the fake docker script.
 *
 * <p><b>Nothing is mocked.</b> The service really forks a process, really parses what it printed
 * and really renders the records — the only substitution is which program `docker` is, which is
 * exactly the substitution a config key is for. So what these assert is the WIRE: the field names
 * the client binds to, the statuses it branches on, and the two rules that are easy to get wrong
 * (a foreign node is a 409 with a code, and an environment value never leaves this service).
 */
@QuarkusTest
@TestSecurity(user = "an-operator", roles = "qits:admin")
class SystemApiTest {

  /** This node, as the fake `docker info` reports it. */
  private static final String NODE = "iwbwrdui2z0n62kqcjfo1erwh";

  private static final String CONTAINER = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  @Test
  void theOverviewIsTheHostTheDiskAndTheSwarmInOneCall() {
    given()
        .when()
        .get("/system/api/overview")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        // camelCase, and the client's vocabulary rather than docker's PascalCase.
        .body("host.hostname", Matchers.equalTo("fake-host"))
        .body("host.dockerVersion", Matchers.equalTo("29.7.2"))
        .body("host.os", Matchers.equalTo("Fedora Linux 43 (WSL)"))
        .body("host.cpus", Matchers.equalTo(24))
        .body("host.memoryBytes", Matchers.equalTo(33529409536L))
        // Bytes, read back out of docker's rendering, because the client draws a bar with them.
        .body("usage.imagesBytes", Matchers.equalTo(308300000000L))
        .body("usage.buildCacheBytes", Matchers.equalTo(302500000000L))
        .body("swarm.state", Matchers.equalTo("active"))
        .body("swarm.nodeId", Matchers.equalTo(NODE))
        .body("swarm.managers", Matchers.equalTo(1));
  }

  @Test
  void theSwarmListsAreBareArrays() {
    // Not {items: […]}. Several sibling explorers wrap; this contract does not, and a wrapper
    // grown later would break every page silently.
    given()
        .when()
        .get("/system/api/swarm/nodes")
        .then()
        .statusCode(200)
        .body("$", Matchers.hasSize(1))
        .body("[0].id", Matchers.equalTo(NODE))
        .body("[0].role", Matchers.equalTo("manager"))
        .body("[0].status", Matchers.equalTo("Ready"))
        .body("[0].self", Matchers.equalTo(true));

    given()
        .when()
        .get("/system/api/swarm/services")
        .then()
        .statusCode(200)
        .body("$", Matchers.hasSize(2))
        .body("[0].name", Matchers.equalTo("dev-qits-artifacts"))
        .body("[0].replicas", Matchers.equalTo("1/1"));

    given()
        .when()
        .get("/system/api/swarm/configs")
        .then()
        .statusCode(200)
        .body("[0].name", Matchers.equalTo("qits-edge-vhosts"));

    given()
        .when()
        .get("/system/api/swarm/secrets")
        .then()
        .statusCode(200)
        .body("[0].name", Matchers.equalTo("qits-platform-idp-signing-key"));
  }

  @Test
  void aServiceDetailCarriesItsTasksAndNoEnvironmentValues() {
    String body =
        given()
            .when()
            .get("/system/api/swarm/services/dev-qits-artifacts")
            .then()
            .statusCode(200)
            .body("name", Matchers.equalTo("dev-qits-artifacts"))
            .body("replicas", Matchers.equalTo("1/1"))
            .body("tasks", Matchers.hasSize(2))
            .body("tasks[0].state", Matchers.startsWith("Running"))
            .body("tasks[0].slot", Matchers.equalTo(1))
            .body("tasks[1].error", Matchers.containsString("No such container"))
            .body("envKeys", Matchers.hasItem("QITS_RESOURCE_DB_PASSWORD"))
            .extract()
            .asString();

    // The one assertion worth more than the rest: the fake service's env carries a value shaped
    // like a secret, and nothing this service answers may contain it.
    assertFalse(body.contains("not-a-real-secret"), "an environment VALUE reached the wire: " + body);
  }

  @Test
  void aConfigDetailCarriesItsDecodedData() {
    given()
        .when()
        .get("/system/api/swarm/configs/qits-edge-vhosts")
        .then()
        .statusCode(200)
        .body("data", Matchers.equalTo("registry.dev.localhost -> qits-artifacts\n"))
        .body("binary", Matchers.equalTo(false));
  }

  @Test
  void anUnknownSwarmObjectIsA404WithAMessage() {
    // Docker exits non-zero for both "no such node" and "cannot connect"; only the first may be a
    // 404, and the wording is what tells them apart.
    given()
        .when()
        .get("/system/api/swarm/nodes/somebody-elses-node")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", Matchers.containsString("no such node"));
  }

  @Test
  void theNodeReadsAnswerForThisNodeAndForTheAlias() {
    for (String node : new String[] {NODE, "local"}) {
      given()
          .when()
          .get("/system/api/nodes/" + node + "/containers")
          .then()
          .statusCode(200)
          .body("$", Matchers.hasSize(1))
          .body("[0].name", Matchers.equalTo(CONTAINER))
          .body("[0].state", Matchers.equalTo("running"))
          .body("[0].status", Matchers.equalTo("Up 4 hours (healthy)"));
    }

    // `all=true` is what the client sends, and what asks for the dead ones.
    given()
        .when()
        .get("/system/api/nodes/local/containers?all=true")
        .then()
        .statusCode(200)
        .body("$", Matchers.hasSize(2))
        .body("[1].state", Matchers.equalTo("exited"));

    given()
        .when()
        .get("/system/api/nodes/local/images")
        .then()
        .statusCode(200)
        .body("[0].repository", Matchers.equalTo("registry.dev.localhost:8080/qits/qits-ci"))
        .body("[0].sizeBytes", Matchers.equalTo(325000000));

    given()
        .when()
        .get("/system/api/nodes/local/volumes")
        .then()
        .statusCode(200)
        .body("[0].name", Matchers.equalTo("qits-shared-m2"));

    given()
        .when()
        .get("/system/api/nodes/local/networks")
        .then()
        .statusCode(200)
        .body("[0].name", Matchers.equalTo("qits-net"));
  }

  @Test
  void aForeignNodeIsA409WithACodeRatherThanAnEmptyList() {
    // THE BOUNDARY OF v1, and the client draws it as a card rather than as an error. An empty list
    // would say "there is nothing there", which is a different and false statement.
    given()
        .when()
        .get("/system/api/nodes/someotherwharfnode1234/containers")
        .then()
        .statusCode(409)
        .contentType(ContentType.JSON)
        .body("code", Matchers.equalTo("NODE_REMOTE"))
        .body("message", Matchers.containsString("only the local node"));
  }

  @Test
  void aContainerDetailAndItsLogs() {
    String body =
        given()
            .when()
            .get("/system/api/nodes/local/containers/" + CONTAINER)
            .then()
            .statusCode(200)
            .body("name", Matchers.equalTo(CONTAINER))
            .body("running", Matchers.equalTo(true))
            .body("health", Matchers.equalTo("healthy"))
            .body("user", Matchers.equalTo("1001"))
            .body("networks[0].name", Matchers.equalTo("qits-net"))
            .body("envKeys", Matchers.hasItem("QITS_RESOURCE_DB_PASSWORD"))
            .extract()
            .asString();
    assertFalse(body.contains("not-a-real-secret"), "an environment VALUE reached the wire");

    given()
        .when()
        .get("/system/api/nodes/local/containers/" + CONTAINER + "/logs?tail=200")
        .then()
        .statusCode(200)
        .body("text", Matchers.containsString("the last line of the log"))
        .body("truncated", Matchers.equalTo(false));
  }

  @Test
  void anUnknownContainerIs404AndAnImplausibleReferenceIs400() {
    given()
        .when()
        .get("/system/api/nodes/local/containers/no-such-thing")
        .then()
        .statusCode(404)
        .body("message", Matchers.containsString("no such container"));

    // A leading dash would be read as an option by anything that re-split the argv.
    given()
        .when()
        .get("/system/api/nodes/local/containers/-f/logs")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("not a valid container"));

    // A log panel is a glance, not a log store.
    given()
        .when()
        .get("/system/api/nodes/local/containers/" + CONTAINER + "/logs?tail=999999")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("tail must be between"));
  }
}
