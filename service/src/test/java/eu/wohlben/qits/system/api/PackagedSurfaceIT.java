package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.system.testdb.EmbeddedPg;
import eu.wohlben.qits.system.testdocker.FakeDocker;
import eu.wohlben.qits.system.testdocker.TerminalClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative} — because that is where a whole class
 * of failure is visible and nowhere else.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it augments and runs in the build JVM, with
 * the full classpath present and reflection unrestricted. A native image has neither. What this
 * asserts is exactly what that difference can lose:
 *
 * <ul>
 *   <li>the build-time route prefixes — {@code /system/api} and {@code /system/q} — which the edge
 *       path-routes verbatim on every host and no unprefixed form falls back to;
 *   <li>every wire record surviving as something Jackson can write, which the build-time analysis
 *       cannot see through a {@code Response.entity(...)} — that is what {@link ApiWireReflection}
 *       is for, and a missing entry there is a 500 in the binary while the JVM suite stays green.
 *       The terminal POST is exactly such a response;
 *   <li><b>THE PSEUDO-TERMINAL ITSELF.</b> {@code ForeignPty} reaches libc through
 *       {@code java.lang.foreign}, and in a native image that needs two separate things to be
 *       right: the downcall descriptors in the domain jar's {@code reachability-metadata.json}, and
 *       {@code --initialize-at-run-time} for the class. Neither is exercised by any JVM test — the
 *       binary either fails to build or throws {@code MissingForeignRegistrationError} on the first
 *       terminal. The round trip below is the only place that is proved. It also proves {@code
 *       setsid} is really in the runtime image, which nothing else does either;
 *   <li><b>the client is served, and does not swallow the API.</b> Quinoa is disabled by default in
 *       test mode, so no {@code @QuarkusTest} builds or serves the SPA and every assertion about
 *       {@code /} would pass against a process with no client in it.
 * </ul>
 *
 * <p><b>This is also the only place the identity contract is real.</b> A {@code @QuarkusTest} runs
 * under the {@code test} profile, where qits-auth-core ships a dev user; the launched artifact runs
 * as a deployment does, so the roles have to arrive the way qits-gateway sends them — in {@code
 * X-Qits-User} and {@code X-Qits-Roles}, on the WebSocket handshake as well as on a request.
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a {@code
 * package} to have happened. Ask for them explicitly.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedSurfaceIT.PackagedUnderTarget.class)
public class PackagedSurfaceIT {

  /**
   * The one string that identifies a response as the CLIENT's index.html rather than anything else
   * this process serves. It also has to agree with {@code quarkus.quinoa.ui-root-path} here and
   * with {@code baseHref} in qits-platform-spa-system's angular.json, so the probes below double as
   * the check that all three still do.
   */
  private static final String BASE_HREF = "<base href=\"/\">";

  private static final String CONTAINER = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  @TestHTTPResource("/")
  URI base;

  /**
   * Hands the launched artifact the same stand-in docker the suite uses.
   *
   * <p>It has to be a config OVERRIDE rather than the test config source: the launched process is
   * the packaged artifact, which does not have this module's test classpath and therefore never
   * sees {@code FakeDockerConfigSource}. The path is resolved here, in the test JVM, which does.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides = new java.util.LinkedHashMap<>();
      overrides.put("qits.system.docker.binary", FakeDocker.binaryPath());
      // The boot sweep would run its three calls against the fake on every launch; the sweep
      // has its own coverage and this IT is about the surface.
      overrides.put("qits.system.glances.pull-at-startup", "false");
      // THE TARGETS MODULE'S TWO DATABASES (qits-1481), as the deployer injects them: the packaged
      // artifact opens both and runs Flyway at boot, so it gets the suite's embedded postgres.
      overrides.put("QITS_RESOURCE_DB_URL", EmbeddedPg.url("qits_system_packaged_it"));
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url("qits_system_eventstream_packaged_it"));
      overrides.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
      // No qits-events to publish to: the outbox stays dark, so no story draws a dial to it.
      overrides.put("qits.eventstream.enabled", "false");
      overrides.put("quarkus.flyway.system.clean-at-start", "true");
      overrides.put("quarkus.flyway.system.clean-disabled", "false");
      return Map.copyOf(overrides);
    }
  }

  /** What qits-gateway asserts for an authenticated operator. */
  private static RequestSpecification asAdmin() {
    return given().header("X-Qits-User", "packaged-it").header("X-Qits-Roles", "qits:admin");
  }

  /** The same identity, on a WebSocket handshake. */
  private static Map<String, String> adminHeaders() {
    return Map.of("X-Qits-User", "packaged-it", "X-Qits-Roles", "qits:admin");
  }

  @Test
  public void thereIsNoAnonymousSurface() {
    given().when().get("/system/api/overview").then().statusCode(401);
    given().when().get("/system/api/terminals").then().statusCode(401);
  }

  @Test
  public void theMachineRoutesAreUnderTheSegmentAndAMistypedOneAnswersNoData() {
    asAdmin().when().get("/system/api/overview").then().statusCode(200);
    asAdmin().when().get("/system/api/swarm/nodes").then().statusCode(200);
    asAdmin().when().get("/system/api/nodes/local/containers").then().statusCode(200);

    // The edge path-routes verbatim, so there is no unprefixed form to fall back to. The client sits
    // at the root now, so an unprefixed path is inside the SPA fallback's reach and comes back as
    // the page rather than as a 404 — which is right: /api belongs to no machine surface here.
    asAdmin()
        .when()
        .get("/api/overview")
        .then()
        .statusCode(200)
        .body(Matchers.containsString(BASE_HREF));

    String body =
        asAdmin().when().get("/system/api/nope").then().statusCode(404).extract().asString();
    assertFalse(body.contains(BASE_HREF), "a mistyped path must not answer with the page: " + body);
  }

  /**
   * Every wire record, written by the binary. A record the build-time analysis never saw is a 500
   * here and green in every {@code @QuarkusTest}, which is the failure this whole IT exists for.
   */
  @Test
  public void everyReadRendersInTheBinary() {
    asAdmin()
        .when()
        .get("/system/api/overview")
        .then()
        .statusCode(200)
        .body("host.hostname", Matchers.equalTo("fake-host"))
        .body("usage.imagesBytes", Matchers.equalTo(308300000000L))
        .body("swarm.state", Matchers.equalTo("active"));

    asAdmin().when().get("/system/api/swarm/nodes/iwbwrdui2z0n62kqcjfo1erwh").then().statusCode(200);
    asAdmin()
        .when()
        .get("/system/api/swarm/services/dev-qits-artifacts")
        .then()
        .statusCode(200)
        .body("tasks", Matchers.hasSize(2));
    asAdmin().when().get("/system/api/swarm/configs/qits-edge-vhosts").then().statusCode(200);
    asAdmin().when().get("/system/api/swarm/secrets").then().statusCode(200);
    asAdmin().when().get("/system/api/nodes/local/containers/" + CONTAINER).then().statusCode(200);
    asAdmin()
        .when()
        .get("/system/api/nodes/local/containers/" + CONTAINER + "/logs")
        .then()
        .statusCode(200)
        .body("truncated", Matchers.equalTo(false));
    asAdmin().when().get("/system/api/nodes/local/images").then().statusCode(200);
    asAdmin().when().get("/system/api/nodes/local/volumes").then().statusCode(200);
    asAdmin().when().get("/system/api/nodes/local/networks").then().statusCode(200);

    // The error body is also a Response.entity(...), and the code is what the client branches on.
    asAdmin()
        .when()
        .get("/system/api/nodes/someotherwharfnode1234/containers")
        .then()
        .statusCode(409)
        .body("code", Matchers.equalTo("NODE_REMOTE"));
  }

  /**
   * THE PROOF THAT THE PSEUDO-TERMINAL SURVIVED THE IMAGE. A real PTY, a real child, keystrokes
   * down and output back, over a WebSocket whose handshake carries the gateway's headers.
   *
   * <p>Nothing else in this repository can fail this way: the FFM metadata, the run-time
   * initialisation of {@code ForeignPty} and {@code setsid} being installed in the runtime image
   * are all invisible to every JVM test and to the image build itself.
   */
  @Test
  public void aTerminalRoundTripsAgainstThePackagedArtifact() throws Exception {
    String socketPath =
        asAdmin()
            .contentType(ContentType.JSON)
            .body("{\"kind\":\"EXEC\",\"container\":\"" + CONTAINER + "\",\"shell\":\"sh\"}")
            .when()
            .post("/system/api/terminals")
            .then()
            .statusCode(201)
            .body("container.id", Matchers.hasLength(64))
            .extract()
            .path("socketPath");
    String id = socketPath.substring(socketPath.lastIndexOf('/') + 1);

    try (TerminalClient client =
        TerminalClient.connect(TerminalClient.socketUri(base, socketPath), adminHeaders())) {
      client.awaitText("fake-terminal ready");
      client.sendData("hello-from-the-binary\n");
      client.awaitText("echo:hello-from-the-binary");
      client.sendData("exit\n");
      client.awaitText("[terminal exited (code 0)]");
      assertEquals(1000, client.awaitClose());
    }

    asAdmin().when().get("/system/api/terminals/" + id).then().statusCode(404);
  }

  /** And the handshake is refused without one, in the binary too. */
  @Test
  public void theTerminalSocketStillNeedsAnIdentity() {
    try {
      TerminalClient.connect(
          TerminalClient.socketUri(base, "/system/api/terminals/11111111-2222-3333-4444-555555555555"),
          Map.of());
      throw new AssertionError("the handshake must not succeed without an identity");
    } catch (RuntimeException expected) {
      // A refused upgrade, which is what a class-level @RolesAllowed on the endpoint arranges.
    }
  }

  /**
   * The client is mounted, and its {@code <base href>} agrees with where it is mounted. The two are
   * configured in different repositories — {@code quarkus.quinoa.ui-root-path} here, {@code
   * baseHref} in qits-platform-spa-system's angular.json — and a disagreement serves a page that
   * loads and then fetches its own JavaScript from a path that 404s. Nothing on this side notices,
   * which is why the string is asserted rather than the status alone.
   *
   * <p><b>It answers anonymously, and that is not a hole in "no anonymous surface".</b> That rule
   * is about this service's DATA: every route is {@code @RolesAllowed} and the test above pins a
   * 401. What is served here is a static bundle with no configuration in it.
   */
  @Test
  public void theClientIsServedAtTheRootWithABaseHrefThatMatches() {
    given()
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));
  }

  /**
   * A deep link is the SPA fallback doing its job: {@code /swarm/nodes/local/containers} has no
   * file behind it, and {@code enable-spa-routing} is what makes a reload or a pasted link reach
   * the Angular router instead of a 404. An operator shares exactly these addresses.
   */
  @Test
  public void aDeepLinkFallsBackToTheClientSoTheAngularRouterOwnsIt() {
    given()
        .when()
        .get("/swarm/nodes/local/containers")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));
  }

  /**
   * THE HALF THAT COSTS SOMETHING IF IT IS WRONG. The SPA fallback is a late-order catch-all over
   * the WHOLE root now, so any path that matches no route is rerouted to index.html and answers
   * {@code 200 text/html} — unless {@code quarkus.quinoa.ignored-path-prefixes} claims it first. Its
   * one entry, {@code /system}, covers both machine prefixes: matching is by path segment.
   *
   * <p>The stake is the client's own polling: {@code GET /system/api/terminals} while a session
   * runs, which would hand a JSON parser an HTML document.
   *
   * <p>What is asserted is the status and the absence of the client's page — not the absence of
   * HTML. An ignored path falls to Quarkus' own not-found handler, which answers {@code 404
   * text/html}: a correct refusal wearing a browser's content type.
   */
  @Test
  public void aMistypedMachinePathIs404AndNeverThePage() {
    asAdmin()
        .when()
        .get("/system/api/nope")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));

    given()
        .when()
        .get("/system/q/nope")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));
  }

  /**
   * THE BARE SEGMENT IS A MACHINE PATH AND ANSWERS LIKE ONE. {@code /system} is claimed by {@code
   * ignored-path-prefixes}, so it never becomes the page; it belongs to no route either, so it is a
   * 404. The old trailing-slash wart went with the move to the root — {@code /} is the client now,
   * and there is no bare segment left for a reader to mistype.
   */
  @Test
  public void theBareSegmentIsAMachinePathAndIsA404NeverThePage() {
    given()
        .when()
        .get("/system")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));

    // The old client address, which nothing serves any more. It is claimed by the same entry, so a
    // stale bookmark gets a 404 rather than a page that would then fetch its assets from /.
    given()
        .when()
        .get("/system/")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/system/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /system on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/system/q/openapi").then().statusCode(200);
    given().when().get("/system/q/swagger-ui/").then().statusCode(200);
  }
}
