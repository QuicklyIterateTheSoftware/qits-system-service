package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.system.testdocker.FakeDocker;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The secrets and configs lists' call sequence (qits-2487): an {@code ls} for the ids, then ONE
 * {@code inspect} over all of them for the rows — none when the {@code ls} listed nothing, and the
 * pair once more when an object vanished in between, before the 503.
 *
 * <p>The fake docker's knobs are files beside the script, which every test in the run shares, so
 * each one written here is removed after the test that wrote it.
 */
@QuarkusTest
@TestSecurity(user = "an-operator", roles = "qits:admin")
class SwarmListReadsTest {

  private static final List<String> MARKERS =
      List.of("no-secrets", "no-configs", "inspect-race-secret", "inspect-race-config");

  @BeforeEach
  void forgetEarlierCalls() {
    FakeDocker.clearCalls();
  }

  @AfterEach
  void clearMarkers() throws IOException {
    for (String marker : MARKERS) {
      Files.deleteIfExists(FakeDocker.directory().resolve(marker));
    }
  }

  @Test
  void aSecretListIsOneLsAndOneInspectOverEveryId() {
    given().when().get("/system/api/swarm/secrets").then().statusCode(200);

    assertEquals(1, count("secret ls --format {{.ID}}"));
    // Both ids in the one inspect, in the order `ls` printed them.
    assertEquals(1, count("secret inspect --format "));
    assertEquals(
        1, count("x4kq7v2m9p1c8d0w5n3b6e2rt jvbcnrfhrbn8u9c9c0nfg2rrj"), FakeDocker.calls()::toString);
  }

  @Test
  void anEmptySecretListInspectsNothing() {
    marker("no-secrets", "");

    given().when().get("/system/api/swarm/secrets").then().statusCode(200).body("$", Matchers.empty());

    assertEquals(1, count("secret ls"));
    // Nothing listed, nothing to inspect — and no usage pair either.
    assertEquals(0, count("secret inspect"), FakeDocker.calls()::toString);
    assertEquals(0, count("service "), FakeDocker.calls()::toString);
  }

  @Test
  void anEmptyConfigListInspectsNothing() {
    marker("no-configs", "");

    given().when().get("/system/api/swarm/configs").then().statusCode(200).body("$", Matchers.empty());

    assertEquals(1, count("config ls"));
    assertEquals(0, count("config inspect"), FakeDocker.calls()::toString);
  }

  @Test
  void aSecretRemovedBetweenTheLsAndTheInspectIsReadAgain() {
    marker("inspect-race-secret", "1");

    given()
        .when()
        .get("/system/api/swarm/secrets")
        .then()
        .statusCode(200)
        .body("$", Matchers.hasSize(2))
        .body("[0].createdAt", Matchers.equalTo("2026-09-21T10:12:44.118302751Z"));

    assertEquals(2, count("secret ls"), FakeDocker.calls()::toString);
    assertEquals(2, count("secret inspect"), FakeDocker.calls()::toString);
  }

  @Test
  void aSecretRaceTwiceIsA503CarryingDockersWords() {
    marker("inspect-race-secret", "2");

    given()
        .when()
        .get("/system/api/swarm/secrets")
        .then()
        .statusCode(503)
        .body("message", Matchers.containsString("no such secret"));

    // Once more, then no further: the second failure is the answer.
    assertEquals(2, count("secret ls"), FakeDocker.calls()::toString);
    assertEquals(2, count("secret inspect"), FakeDocker.calls()::toString);
    assertEquals(0, count("service "), FakeDocker.calls()::toString);
  }

  @Test
  void aConfigRemovedBetweenTheLsAndTheInspectIsReadAgain() {
    marker("inspect-race-config", "1");

    given()
        .when()
        .get("/system/api/swarm/configs")
        .then()
        .statusCode(200)
        .body("[0].name", Matchers.equalTo("qits-edge-vhosts"));

    assertEquals(2, count("config ls"), FakeDocker.calls()::toString);
    assertEquals(2, count("config inspect"), FakeDocker.calls()::toString);
  }

  @Test
  void aConfigRaceTwiceIsA503CarryingDockersWords() {
    marker("inspect-race-config", "2");

    given()
        .when()
        .get("/system/api/swarm/configs")
        .then()
        .statusCode(503)
        .body("message", Matchers.containsString("no such config"));

    assertEquals(2, count("config ls"), FakeDocker.calls()::toString);
    assertEquals(2, count("config inspect"), FakeDocker.calls()::toString);
  }

  @Test
  void aConfigListNeverAsksForData() {
    given().when().get("/system/api/swarm/configs").then().statusCode(200);

    List<String> inspects =
        FakeDocker.calls().stream().filter(call -> call.contains("config inspect")).toList();
    assertEquals(1, inspects.size(), inspects::toString);
    assertFalse(inspects.get(0).contains("Spec.Data"), inspects.get(0));
  }

  private static long count(String fragment) {
    return FakeDocker.calls().stream().filter(call -> call.contains(fragment)).count();
  }

  private static void marker(String name, String content) {
    try {
      Files.writeString(FakeDocker.directory().resolve(name), content);
    } catch (IOException e) {
      throw new IllegalStateException("could not set the fake docker's marker: " + name, e);
    }
  }
}
