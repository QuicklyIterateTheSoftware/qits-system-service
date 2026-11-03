package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import eu.wohlben.qits.system.testdocker.TerminalClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THERE IS NO ANONYMOUS SURFACE HERE, and this is the test that says so.
 *
 * <p>Every other {@code @QuarkusTest} in this module names its caller ({@code @TestSecurity} or a
 * suite bearer), which makes every one of them useless as proof of the identity rule. This one asks
 * the same questions with no identity at all.
 *
 * <p>What this service reads is the whole machine, and what it can open is a shell on it. A route
 * here that answered anonymously would be the worst defect this repository could have.
 */
@QuarkusTest
class AnonymousAccessTest {

  @TestHTTPResource("/")
  URI base;

  @Test
  void everyReadIsRefused() {
    for (String path :
        new String[] {
          "/system/api/overview",
          "/system/api/swarm/nodes",
          "/system/api/swarm/services",
          "/system/api/swarm/configs",
          "/system/api/swarm/secrets",
          "/system/api/nodes/local/containers",
          "/system/api/nodes/local/images",
          "/system/api/nodes/local/volumes",
          "/system/api/nodes/local/networks",
          "/system/api/terminals"
        }) {
      given().when().get(path).then().statusCode(401);
    }
  }

  @Test
  void openingATerminalIsRefused() {
    given()
        .contentType("application/json")
        .body("{\"kind\":\"GLANCES\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(401);
  }

  /**
   * And the UPGRADE itself is refused, not the frames afterwards. Class-level {@code @RolesAllowed}
   * on a WebSockets Next endpoint is what arranges that: without it the handshake succeeds and the
   * refusal arrives, if at all, as a closed socket a client would simply retry.
   */
  @Test
  void theTerminalSocketRefusesTheHandshake() throws Exception {
    URI uri =
        TerminalClient.socketUri(base, "/system/api/terminals/11111111-2222-3333-4444-555555555555");

    try {
      TerminalClient.connect(uri, Map.of());
      throw new AssertionError("the handshake must not succeed without an identity");
    } catch (RuntimeException thrown) {
      // The JDK client reports a refused upgrade as a WebSocketHandshakeException carrying the
      // HTTP response — which is what makes it possible to assert the STATUS rather than merely
      // that something went wrong.
      Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
      assertInstanceOf(WebSocketHandshakeException.class, cause, "expected a refused upgrade");
      assertEquals(401, ((WebSocketHandshakeException) cause).getResponse().statusCode());
    }
  }
}
