package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.system.stories.support.StoryDocker;
import eu.wohlben.qits.system.stories.support.StoryIdentities;
import eu.wohlben.qits.system.stories.support.StoryProfile;
import eu.wohlben.qits.system.stories.support.StoryTarget;
import eu.wohlben.qits.system.stories.support.StoryTerminal;
import eu.wohlben.qits.system.testdocker.TerminalClient;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove. It is also the
 * <b>first</b> class of this repository's story catalogue, and the two facts are the same fact:
 * this is where the service starts.
 *
 * <p>The shipped tenant is {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}},
 * and <b>no suite in this repository turns that gate on at all</b> — the test
 * application.properties says so in as many words ("the OIDC tenant is disabled because
 * qits.auth.machine.required defaults false"), which is what keeps a clone-alone {@code ./mvnw
 * verify} free of an issuer. The consequence is that the entire shipped {@code quarkus.oidc.*}
 * block — auth-server-url with discovery on, the boot-time discovery and JWKS fetch that {@code
 * connection-delay} retries, audience enforcement,
 * groups→roles mapping — is exercised NOWHERE else. {@link StoryProfile} is what turns it on, for
 * the whole catalogue. The far side is {@link MockIdp}, whose recordings make the interaction
 * assertable on <b>both ends</b>.
 *
 * <p><b>The diagram is observed, never narrated.</b> Three passive feeds and one declaration, and
 * which is which is the whole discipline:
 *
 * <ul>
 *   <li>the framework's own {@code NetworkTaps.restAssured} tap for what a story sends here,
 *       skipping the {@code /q/} probe root;
 *   <li>{@link MockIdp}'s request log, a <b>cumulative</b> source, for the startup discovery and JWKS fetch;
 *   <li>{@link StoryDocker}'s recording, also cumulative, for what this service asked the docker
 *       CLI — including the two calls it makes on the way up, which this story owns;
 *   <li>and the refused terminal upgrade, observed at its own call site because it travels on the
 *       JDK's WebSocket client, which no RestAssured filter sees.
 * </ul>
 *
 * <p>The one edge that is <b>declared</b> is the hop behind the CLI: the unix socket the deployment
 * mounts into this container. No tap out here stands in front of it, so it is marked {@code
 * "declared": true} and drawn distinctly rather than passed off as evidence.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a
 * cumulative source is attributed by a cursor, so traffic that happened before any story ran — the
 * startup discovery and JWKS fetch and the boot sweep's two docker calls, which are half the subject of the first
 * story — lands in whichever story drains <i>first</i>. Pinning the order is what keeps that the
 * story it belongs to; and running a later class of this catalogue on its own makes ITS first story
 * inherit them and fail its edge count, loudly, which is the right way for the assumption to break.
 *
 * <p><b>The route both stories drive is {@code GET /system/api/overview}</b>, and it is chosen as
 * the least side-effectful read this service has: it names nothing (the only guarded read with no
 * path parameter), it is introspection of THIS machine and no peer, and it cannot write, because
 * nothing in this service can. What the whole read surface then looks like is {@code
 * stories.host.HostOverviewIT}'s subject, not this one's.
 *
 * <p><b>The daemon is a recording stand-in, not a mock.</b> {@link StoryDocker} stages the shell
 * script the surefire suite points {@code qits.system.docker.binary} at into a directory only the
 * launched artifact writes to: a real {@code ProcessBuilder} forks a real child and the argv it
 * receives is the argv the builders produced. That is why the accepted story can assert the read
 * reached the machine as well as that it was served — the recording is the CLI's end of the
 * interaction, exactly as {@code recordedRequests()} is the idp's.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because {@link PackagedSurfaceIT} is this module's other integration
 * test and a good half of it is about the CLIENT — the base href at the root, the deep links, the
 * fallback that must not swallow {@code /system} — which the userflow pipeline deliberately does
 * not build ({@code -Dquarkus.quinoa=false}, since the qits-platform-spa-system submodule arrives
 * EMPTY in a step container). A blanket {@code -DskipITs=false} would make that run red on a test
 * that is right. {@code .config/qits/ci-event-userflows.yml} names the story classes instead, which
 * is also what keeps the userflow pipeline about these stories and nothing else.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String ACCEPTED = "On start, the system panel fetches the platform's signing keys";

  static final String ACCEPTED_SLUG = Slugs.slug(ACCEPTED);

  static final String DENIED = "A stranger's token never reads the platform's machine";

  static final String DENIED_SLUG = Slugs.slug(DENIED);

  /** A terminal that does not exist, so nothing but the door is ever reached. */
  private static final String SOCKET_PATH =
      StoryTarget.socketPath("11111111-2222-3333-4444-555555555555");

  /** Every bearer these stories minted — each one pinned as absent from the published bundle. */
  private static final List<String> MINTED = new ArrayList<>();

  @TestHTTPResource("/")
  URI base;

  /**
   * Wires the three passive feeds, once, before either story runs.
   *
   * <p>The RestAssured tap is the framework's own; it is idempotent per service name, so every
   * story class in this catalogue installs it from its own {@code @BeforeAll} and nothing is drawn
   * twice. {@link StoryDocker#installSource()} is gated the same way.
   *
   * <p>The idp is registered as a <b>cumulative</b> source: the supplier hands over the mock's
   * whole request log every time it is asked and the framework remembers how much of it earlier
   * stories already consumed, so the startup fetch — recorded long before any story existed — is
   * attributed to the first story and to that one only. It is invoked lazily at story end, so
   * registering it here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and a
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   */
  @BeforeAll
  static void tapEveryEndOfTheNetwork() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            StoryTarget.SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  @UserStory(value = ACCEPTED, category = CATEGORY)
  @UserStoryDescription(
      """
      A freshly deployed qits-platform-system must validate service bearers before any caller
      arrives: at startup it reads qits-idp's discovery document and fetches the signing keys
      (JWKS) at the jwks_uri it names, so the very first machine request is accepted. It also
      says hello to the host it exists to look at, probing the daemon and sweeping up any terminal
      container a previous life of this service left running. What that bearer then buys is this
      service's read surface, answered live off the docker daemon; and what it does NOT buy is a
      shell. Only `qits:admin` may open a terminal socket, because reading the host's shape is
      something a machine may do and holding a shell on the platform host is not.
      """)
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story, Network network) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-platform-system starts with the OIDC tenant on, beside a reachable"
            + " qits-idp");
    given().get("/system/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. Readiness above is deliberately independent of that fetch — the shipped config
    // explains why: tying it to another service's would make a cold boot a question of ordering —
    // so a 200 there is not the claim. The recording is.
    //
    // The edge itself is drained from that recording and nothing here draws it; what is asserted is
    // that it happened, and the note carries the one thing a method, a path and a status cannot —
    // WHEN. This is also the story that owns it: the cursor gives pre-story traffic to whichever
    // story drains first, which is why this class pins its method order.
    assertTrue(
        idp.recordedRequests().stream()
            .anyMatch(r -> "/idp/.well-known/openid-configuration".equals(r.path())),
        "the packaged service never read the discovery document at startup");
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .note("the discovery document was read and the signing keys it points to were fetched at"
            + " startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), the host side, and it happened at startup too: the daemon probe whose one WARN line
    // turns "the socket is not mounted" from a support question into a fix, and the sweep that
    // reaps the terminal containers a killed service left behind — filtered on the owner label's
    // VALUE, because two platforms can share one daemon and neither may reap the other's.
    assertTrue(
        StoryDocker.callsSince(0).contains(StoryDocker.label(SWEEP, "0")),
        "the boot sweep never asked which terminal containers are this service's own: "
            + StoryDocker.callsSince(0));
    story
        .note(
            "and on the same boot it probed the daemon and swept up the terminal containers a"
                + " previous life of this service had left running")
        .as("host-greeted");

    // End (c), this service's side: those keys are what token validation now runs on. A platform
    // peer's bearer (aud = the platform, roles in `groups`) opens the landing read — no path
    // parameter, nothing named, nothing written.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `a platform service -> qits-platform-system`. It stays set
    // for the upgrade attempt at the end of this story, which is the same caller.
    NetworkCapture.actor(StoryIdentities.PEER);
    String peerToken = StoryIdentities.machineToken();
    MINTED.add(peerToken);
    StoryIdentities.bearer(given(), peerToken)
        .get(StoryTarget.OVERVIEW)
        .then()
        .statusCode(200)
        .body("host", notNullValue())
        .body("host.hostname", equalTo("fake-host"))
        .body("swarm.state", equalTo("active"));
    story
        .note("a platform peer's bearer (aud=qits-platform, groups=[qits:system]) opens the"
            + " landing read")
        .as("overview-served");

    // …and it is what makes this service's answer different from a row out of a table: there is no
    // store here, so the body above can only have come from a `docker info` this request itself
    // forked. THE HOP IS OBSERVED, not declared: DockerProcess spawns the CLI and reads its pipes,
    // and the stand-in records every argv with the exit code it answered.
    assertTrue(
        StoryDocker.callsSince(0).contains(StoryDocker.label("info", "0")),
        "the overview answered without ever asking the daemon: " + StoryDocker.callsSince(0));
    story
        .note("the answer was read off the daemon at the moment it was asked — `docker info` is the"
            + " one call nothing else here makes")
        .as("daemon-read");

    // THE ONE DECLARATION, and it sits one hop further out than it used to. What passivity cannot
    // reach is not the CLI — that is a forked child whose argv is recorded — but the unix socket
    // the CLI then opens: there is no port to sit in front of and no request log that is the
    // daemon's rather than the fixture's. So that edge renders muted and marked [declared], and
    // the observed process edges above it are what stands where evidence can stand.
    network.declare(
        NetworkEdge.SOCKET, StoryDocker.DOCKER, StoryDocker.DAEMON, StoryDocker.SOCKET_LABEL);

    // And the ceiling of that same credential. The socket is @RolesAllowed("qits:admin") at class
    // level, which is what secures the HTTP UPGRADE itself, so a bearer that opens every read is
    // still refused a terminal.
    //
    // THIS EDGE IS OBSERVED, NOT DECLARED, and the distinction is worth the four lines it costs.
    // The upgrade travels on the JDK's own WebSocket client, which no RestAssured filter sees — but
    // the story really does send it and really does watch it fail, so the honest record is an
    // observation made at the call site rather than a claim. It is placed in the catch block on
    // purpose: an upgrade that unexpectedly SUCCEEDED must not leave a "refused" arrow in the
    // document, and the AssertionError below leaves before recording anything.
    //
    // WHAT IS RECORDED IS THE REFUSAL, NOT ITS CODE: from a WebSocket client a rejected upgrade is
    // one failed handshake whether the server decided 401 or 403, and the claim this story makes is
    // only that the machine role does not open a shell. Who else is refused, and what a refusal
    // costs the machine, is stories.terminals.TerminalRefusalIT's whole subject.
    try {
      TerminalClient.connect(
          TerminalClient.socketUri(base, SOCKET_PATH),
          Map.of("Authorization", "Bearer " + peerToken));
      throw new AssertionError("a machine bearer must never open a terminal on the platform host");
    } catch (RuntimeException refused) {
      // A refused upgrade, which is what the class-level @RolesAllowed on the endpoint arranges.
      StoryTerminal.refusedUpgrade();
    }
    story
        .note("the same bearer that reads the whole machine is refused a shell on it: only"
            + " qits:admin opens a terminal socket")
        .as("shell-refused");
  }

  @UserStory(value = DENIED, category = CATEGORY)
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A token signed by a key the published JWKS
      never carried, or minted for an audience that is not this platform's, is refused at the door —
      however well-formed it looks: both are 401 and not 403, because the credential never became an
      identity and there is no caller to have been forbidden. A token carrying the platform audience
      and signed correctly but bearing a role this service has never heard of gets the other answer,
      403 — it authenticated and covers nothing. There is no anonymous route in this service and
      there must never be one: what it reads is the whole machine.
      """)
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();
    int before = StoryDocker.mark();

    // The first two credentials are both an impostor's, so the actor is set once, up front — and
    // before the first call, because the tap reads it at the moment the request is sent.
    NetworkCapture.actor(IMPOSTOR);

    String strangersToken =
        idp.token()
            .audience(StoryProfile.AUDIENCE)
            .groups(StoryIdentities.MACHINE_ROLE)
            .signedByUnknownKey()
            .mint();
    MINTED.add(strangersToken);
    StoryIdentities.bearer(given(), strangersToken)
        .get(StoryTarget.OVERVIEW)
        .then()
        .statusCode(401);
    // Both refusals are the same edge — same actor, same route, same status — so the framework
    // dedupes them, the diagram draws one arrow, and the notes are what keep the two credentials
    // distinguishable. That is the right division: the graph says who reached what and got what,
    // the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    // AND THE AUDIENCE THIS IS NOT IS WORTH BEING PRECISE ABOUT. A sibling platform service's
    // bearer is not it: qits-idp stamps `qits-platform` on every token it mints, so a
    // peer's credential is addressed here too and its ROLES are what decide what it may do. What
    // is refused is a token that was never addressed to this platform at all — an aud naming
    // something outside it, which is the only thing the audience check can still be about.
    String foreignAudienceToken =
        idp.token()
            .audience("some-other-platform")
            .groups(StoryIdentities.MACHINE_ROLE)
            .mint();
    MINTED.add(foreignAudienceToken);
    StoryIdentities.bearer(given(), foreignAudienceToken)
        .get(StoryTarget.OVERVIEW)
        .then()
        .statusCode(401);
    story
        .note("a token that carries no platform audience at all is refused just the same — 401 and"
            + " not 403, because the credential never became an identity")
        .as("wrong-audience-refused");

    // The third door, and the one that proves the groups→roles mapping really ran rather than being
    // waved through: `qits:reader` is a real platform role and it is not one of the two this
    // service's routes name. Minted into a token addressed here it authenticates perfectly and
    // still covers nothing. A different caller and a different answer, so this is its own arrow:
    // the actor is renamed before the call that draws it.
    NetworkCapture.actor(WRONG_ROLE);
    String readerToken =
        idp.token()
            .subject("somebody-elses-service")
            .audience(StoryProfile.AUDIENCE)
            .groups("qits:reader")
            .mint();
    MINTED.add(readerToken);
    StoryIdentities.bearer(given(), readerToken)
        .get(StoryTarget.OVERVIEW)
        .then()
        .statusCode(403);
    story
        .note("a token addressed here but carrying qits:reader authenticates and covers nothing:"
            + " 403, the other answer")
        .as("wrong-role-refused");

    // The title, checked from the recording's own side as well as from the diagram's: three
    // refusals and the daemon was not asked for anything at all.
    List<String> calls = StoryDocker.callsSince(before);
    assertTrue(calls.isEmpty(), "a refused read must reach nothing: " + calls);
  }

  /** How the diagram names a caller presenting a credential this service cannot trust. */
  private static final String IMPOSTOR = "an impostor";

  /** …and one whose credential is perfectly good and covers nothing here. */
  private static final String WRONG_ROLE = "a caller with the wrong role";

  /** The boot sweep's own call, as the diagram summarises it. */
  private static final String SWEEP =
      "ps -aq --filter label=qits.system.owner=" + StoryTarget.SERVICE;

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY_SLUG, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        MockIdp.SERVICE_NAME,
        "GET /idp/jwks -> 200");
    // …after the discovery document that named the JWKS address.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        MockIdp.SERVICE_NAME,
        "GET /idp/.well-known/openid-configuration -> 200");
    // Observed on the near side, by the framework's tap, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.PEER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.OVERVIEW + " -> 200");
    // Observed too, but at the story's own call site: the upgrade never passes through the tap.
    // Asserted as a plain edge and NOT as a declared one, which is the point — it is evidence.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ACCEPTED_SLUG,
        NetworkEdge.SOCKET,
        StoryIdentities.PEER,
        StoryTarget.SERVICE,
        StoryTarget.socketLabel(StoryTerminal.REFUSED));
    // The four docker calls this story owns: two the boot made, two the read made.
    docker(ACCEPTED_SLUG, "version");
    docker(ACCEPTED_SLUG, SWEEP);
    docker(ACCEPTED_SLUG, "info");
    docker(ACCEPTED_SLUG, "system df");
    // Declared, and asserted AS a declaration: assertDeclaredEdge fails if this ever became an
    // observation, which is the guard that keeps a claim from quietly starting to read like
    // evidence.
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        ACCEPTED_SLUG,
        NetworkEdge.SOCKET,
        StoryDocker.DOCKER,
        StoryDocker.DAEMON,
        StoryDocker.SOCKET_LABEL);
    // "It is introspection of THIS machine and no peer" is the story's own promise, and this is
    // where it is checkable rather than described: nine edges and no tenth. A call to a host this
    // IT would then have to stand in for would be a tenth, and no presence check could see it.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, ACCEPTED_SLUG, 9);
    ReportAssertions.assertStepId(CATEGORY_SLUG, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY_SLUG, ACCEPTED_SLUG, "host-greeted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, ACCEPTED_SLUG, "overview-served");
    ReportAssertions.assertStepId(CATEGORY_SLUG, ACCEPTED_SLUG, "daemon-read");
    ReportAssertions.assertStepId(CATEGORY_SLUG, ACCEPTED_SLUG, "shell-refused");

    ReportAssertions.assertComplete(CATEGORY_SLUG, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DENIED_SLUG,
        NetworkEdge.HTTP,
        IMPOSTOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.OVERVIEW + " -> 401");
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DENIED_SLUG,
        NetworkEdge.HTTP,
        WRONG_ROLE,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.OVERVIEW + " -> 403");
    // THE STORY'S TITLE, ASSERTED AS A SHAPE. "A stranger's token never READS the platform's
    // machine" is a claim about what did not leave this process: no docker call, nothing. Three
    // refused requests in, two arrows, and not one of them leaving here.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, DENIED_SLUG, 2);
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, DENIED_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, DENIED_SLUG, StoryDocker.DOCKER);
    ReportAssertions.assertStepId(CATEGORY_SLUG, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, DENIED_SLUG, "wrong-role-refused");

    // No bearer either story minted is anywhere in the bundle it publishes — including the two that
    // were refused, which are still credentials.
    for (String slug : List.of(ACCEPTED_SLUG, DENIED_SLUG)) {
      for (String token : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY_SLUG, slug, token);
      }
    }
  }

  private static void docker(String slug, String summary) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        slug,
        StoryDocker.KIND,
        StoryTarget.SERVICE,
        StoryDocker.DOCKER,
        StoryDocker.label(summary, "0"));
  }
}
