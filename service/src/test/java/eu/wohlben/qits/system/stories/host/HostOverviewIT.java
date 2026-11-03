package eu.wohlben.qits.system.stories.host;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.system.stories.support.StoryDocker;
import eu.wohlben.qits.system.stories.support.StoryIdentities;
import eu.wohlben.qits.system.stories.support.StoryProfile;
import eu.wohlben.qits.system.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The machine window</b> — an operator opening the console and reading the host this platform
 * runs on, and then the swarm that host is one node of.
 *
 * <p>The two stories are the two halves of what this service answers, and each one's diagram says
 * something the other's cannot:
 *
 * <ul>
 *   <li><b>the machine</b>: every number on the first screen is read off the daemon <i>at the
 *       moment it is asked</i>. There is no store here, no cache and no schema, so a size or a
 *       count in the answer can only have come from a docker call this request itself forked — and
 *       the diagram shows every one of them.
 *   <li><b>the swarm</b>: a manager genuinely knows the whole cluster, and what it is allowed to
 *       show of it is a rule rather than a limitation. A config's DATA is decoded into the answer,
 *       because that is what a config is for; a secret's never is, because swarm does not return it
 *       to anybody; and an environment — a service's and a container's alike — is answered as KEYS
 *       only, because that is where a platform service's injected database password lives.
 * </ul>
 *
 * <p><b>The operator is a person, and a person carries a bearer.</b> The idp signs the person's
 * token; this service validates it with the keys it fetched at boot, and the {@code qits:admin} in
 * its {@code groups} is what makes the caller an operator rather than a machine.
 *
 * <p><b>The docker hop is observed and the socket behind it is declared</b>, and the split is the
 * whole discipline. {@code DockerProcess} forks the CLI and reads its pipes, so {@link StoryDocker}
 * stands in for the binary and records every argv with the exit code it answered: that hop is
 * evidence. What no tap out here stands in front of is the unix socket the CLI then opens, so that
 * one edge — and only that one — is a declaration.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HostOverviewIT {

  static final String CATEGORY = "the host";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String MACHINE = "An operator reads the machine this platform runs on";

  static final String MACHINE_SLUG = Slugs.slug(MACHINE);

  static final String SWARM = "An operator reads the swarm, and never a secret in it";

  static final String SWARM_SLUG = Slugs.slug(SWARM);

  /**
   * The canonical 64-hex id the stand-in daemon prints for {@link StoryTarget#CONTAINER} — the id
   * an operator sees, and the only string this service will ever put in an exec argv.
   */
  static final String KNOWN_CONTAINER_ID =
      "b248472f7a8e00bba8cd036232373baed8856d90eb77b06c53f3eb0b321fdb17";

  @BeforeAll
  static void tapBothSidesOfTheService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
  }

  @UserStory(value = MACHINE, category = CATEGORY)
  @UserStoryDescription(
      """
      An operator opens the console on a platform host and reads what that machine is: its name,
      its kernel and its cpus, what its disk is holding and how much of that is reclaimable, and
      which node of the swarm it happens to be. Then the node itself — every container on it,
      one of them in detail, its images, its volumes and its networks. None of it is stored
      anywhere: this service has no database and no cache, so every number on the screen was read
      off the docker daemon by the request that answered it, and the diagram beside this story is
      the whole list of those reads. One thing the detail deliberately does NOT carry is the
      environment's VALUES — a container's env is answered as keys, because that is where a
      platform service's injected database password and idp client secret live.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void anOperatorReadsTheMachine(Interactions story, Network network) {
    // The tap sees a request and never a narrative role, so the actor is named before the first
    // call rather than described afterwards.
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    int before = StoryDocker.mark();

    // --- the first screen -------------------------------------------------------------------------
    StoryIdentities.operator(given())
        .get(StoryTarget.OVERVIEW)
        .then()
        .statusCode(200)
        .body("host.hostname", equalTo("fake-host"))
        .body("host.dockerVersion", equalTo("29.7.2"))
        .body("host.os", equalTo("Fedora Linux 43 (WSL)"))
        .body("host.cpus", equalTo(24))
        .body("host.memoryBytes", equalTo(33529409536L))
        .body("host.containers", equalTo(26))
        .body("host.containersRunning", equalTo(23))
        .body("host.images", equalTo(1244))
        // The daemon's own warnings travel through untouched: an operator reading a host wants the
        // sentence docker prints, not one this service invented for it.
        .body("host.warnings", hasItem(containsString("bridge-nf-call-iptables")))
        // The sizes docker renders as text, read back into numbers so the client can draw a bar.
        // The original strings travel beside them; this is the number.
        .body("usage.imagesBytes", equalTo(308300000000L))
        .body("usage.volumesBytes", equalTo(33490000000L))
        .body("usage.reclaimableBytes", greaterThan(0L))
        // And the swarm block, which comes out of the same `docker info` document rather than from
        // a second call — one read fills the whole header.
        .body("swarm.state", equalTo("active"))
        .body("swarm.nodes", equalTo(1))
        .body("swarm.managers", equalTo(1));
    story
        .note(
            "the first screen is one call: what this machine is, what its disk holds, and which"
                + " node of the swarm it is")
        .as("overview-served");
    story
        .note(
            "and it cost exactly two docker calls — `info` for the host and the swarm block alike,"
                + " `system df` for the disk — because there is nothing here to have cached them in")
        .as("read-off-the-daemon");

    // --- what is on this node -----------------------------------------------------------------------
    StoryIdentities.operator(given())
        .queryParam("all", true)
        .get(StoryTarget.LOCAL_NODE + "/containers")
        .then()
        .statusCode(200)
        // `all=true` is what asks for the dead ones too, and after something died that is the
        // whole reason an operator is looking.
        .body("size()", equalTo(2))
        .body("state", hasItem("exited"));
    story
        .note("every container on this node, the stopped ones included — `all=true` is what asks")
        .as("containers-listed");

    StoryIdentities.operator(given())
        .get(StoryTarget.LOCAL_NODE + "/containers/" + StoryTarget.CONTAINER)
        .then()
        .statusCode(200)
        .body("id", equalTo(KNOWN_CONTAINER_ID))
        .body("state", equalTo("running"))
        .body("health", equalTo("healthy"))
        // A container's labels come from `inspect` and never from the list column: a list joins
        // them with commas and a real label value contains one (`maintainer=Red Hat, Inc.`).
        .body("labels['qits.platform.deployments.app-name']", equalTo("qits-ci"))
        // THE RULE, checked from both sides: the KEY is there…
        .body("envKeys", hasItem(StoryTarget.SECRET_ENV_KEY))
        // …and no value is, anywhere in the document.
        .body(not(containsString(StoryTarget.SCRUBBED_SECRET)));
    story
        .note(
            "one container in detail — and its environment is answered as KEYS, so the injected"
                + " database password behind one of them never leaves this service")
        .as("environment-is-keys-only");

    StoryIdentities.operator(given())
        .get(StoryTarget.LOCAL_NODE + "/images")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        // The CLI renders a size as text and offers no way to ask for the number; HumanSize reads
        // it back so the client can draw a bar, and the original string travels beside it.
        .body("[0].size", equalTo("325MB"))
        .body("[0].sizeBytes", equalTo(325000000));
    StoryIdentities.operator(given())
        .get(StoryTarget.LOCAL_NODE + "/volumes")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1));
    StoryIdentities.operator(given())
        .get(StoryTarget.LOCAL_NODE + "/networks")
        .then()
        .statusCode(200)
        .body("[0].name", equalTo("qits-net"));
    story
        .note("and the rest of what this node is holding: its images, its volumes, its networks")
        .as("node-inventory-read");

    // The argv is the evidence behind the diagram's summaries, and it is asserted rather than
    // inferred from the picture the same run also draws.
    List<String> calls = StoryDocker.callsSince(before);
    assertTrue(
        calls.contains(StoryDocker.label("info", "0")),
        "the overview answered without ever asking the daemon: " + calls);
    assertTrue(
        calls.contains(StoryDocker.label("system df", "0")),
        "the disk numbers came from somewhere other than `docker system df`: " + calls);

    // THE ONE DECLARATION. A forked child talking to a unix socket is what `declare` exists for:
    // there is no port to sit in front of and no request log that is the daemon's rather than the
    // fixture's. It renders muted and marked [declared], and the seven observed process edges above
    // it are what stands where evidence can stand.
    network.declare(NetworkEdge.SOCKET, StoryDocker.DOCKER, StoryDocker.DAEMON, StoryDocker.SOCKET_LABEL);
  }

  @UserStory(value = SWARM, category = CATEGORY)
  @UserStoryDescription(
      """
      The same operator, one level out: the swarm this machine is a manager of. Nodes, services,
      a service's tasks with the error the failed one carried, and the two object kinds that
      look alike and are not. A config's data IS decoded into the answer — a config exists to be
      read, and an operator debugging an edge vhost file needs to see it. A secret's never is:
      swarm does not return a secret's value to anybody, including a manager, and this service
      does not ask. A service's environment goes the same way a container's does, as keys.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void anOperatorReadsTheSwarm(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    int before = StoryDocker.mark();

    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/nodes")
        .then()
        .statusCode(200)
        .body("[0].hostname", equalTo("fake-host"))
        .body("[0].managerStatus", equalTo("Leader"));
    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/nodes/" + StoryTarget.NODE_ID)
        .then()
        .statusCode(200)
        .body("role", equalTo("manager"))
        .body("managerStatus", equalTo("Leader"))
        .body("address", equalTo("192.168.152.4"));
    story
        .note("the swarm's nodes, and this one in detail — a manager knows the whole cluster")
        .as("nodes-read");

    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/services")
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("name", hasItem(StoryTarget.SWARM_SERVICE));
    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/services/" + StoryTarget.SWARM_SERVICE)
        .then()
        .statusCode(200)
        .body("replicas", equalTo("1/1"))
        .body("updateOrder", equalTo("stop-first"))
        // Two tasks, and the failed one keeps its error: `--no-trunc` is on that argv precisely
        // because the default truncates the column an operator opened this view for.
        .body("tasks", hasSize(2))
        .body("tasks[1].error", containsString("No such container"))
        // The same rule as a container's, in the place it matters most: this IS a platform
        // service's spec, and its environment carries the password the deployment injected.
        .body("envKeys", hasItem(StoryTarget.SECRET_ENV_KEY))
        .body(not(containsString(StoryTarget.SCRUBBED_SECRET)));
    story
        .note(
            "a service in detail, with the error its failed task carried — and its environment as"
                + " keys, which is where a deployment's injected password lives")
        .as("service-read");

    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/configs")
        .then()
        .statusCode(200)
        .body("[0].name", equalTo(StoryTarget.SWARM_CONFIG));
    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/configs/" + StoryTarget.SWARM_CONFIG)
        .then()
        .statusCode(200)
        // DECODED, and that is the difference from a secret: a config is a file somebody put in the
        // swarm to be read, and reading it is the entire reason this view exists.
        .body("data", containsString("registry.dev.localhost"))
        .body("binary", equalTo(false));
    story
        .note("a config's data comes back decoded — that is what a config is for")
        .as("config-decoded");

    StoryIdentities.operator(given())
        .get(StoryTarget.API + "/swarm/secrets")
        .then()
        .statusCode(200)
        .body("[0].name", equalTo("qits-platform-idp-signing-key"))
        // The names, the labels and the timestamps of every secret — and no value on any of them,
        // because there is no docker call that would return one.
        // There is no `data` field on a secret at all — not empty, absent. Nothing in this
        // service's vocabulary could fill one: `docker secret inspect` is not in DockerArgv.
        .body("[0].data", nullValue())
        .body(not(containsString(StoryTarget.SCRUBBED_SECRET)));
    story
        .note(
            "and the secrets are a LIST OF NAMES. Swarm returns a secret's value to nobody, this"
                + " service asks for none, and there is no route here that could answer one")
        .as("secrets-are-names-only");

    List<String> calls = StoryDocker.callsSince(before);
    assertTrue(
        calls.contains(StoryDocker.label("secret ls", "0")),
        "the secrets answer did not come from `docker secret ls`: " + calls);
    assertTrue(
        calls.stream().noneMatch(call -> call.startsWith("secret inspect")),
        "nothing here may inspect a secret: " + calls);

    network.declare(NetworkEdge.SOCKET, StoryDocker.DOCKER, StoryDocker.DAEMON, StoryDocker.SOCKET_LABEL);
  }

  @AfterAll
  static void bothMachineStoriesAreComplete() {
    // --- the machine ------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, MACHINE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, MACHINE_SLUG, "overview-served");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MACHINE_SLUG, "read-off-the-daemon");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MACHINE_SLUG, "containers-listed");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MACHINE_SLUG, "environment-is-keys-only");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MACHINE_SLUG, "node-inventory-read");

    http(MACHINE_SLUG, "GET " + StoryTarget.OVERVIEW + " -> 200");
    http(MACHINE_SLUG, "GET " + StoryTarget.LOCAL_NODE + "/containers -> 200");
    http(MACHINE_SLUG, "GET " + StoryTarget.LOCAL_NODE + "/containers/" + StoryTarget.CONTAINER + " -> 200");
    http(MACHINE_SLUG, "GET " + StoryTarget.LOCAL_NODE + "/images -> 200");
    http(MACHINE_SLUG, "GET " + StoryTarget.LOCAL_NODE + "/volumes -> 200");
    http(MACHINE_SLUG, "GET " + StoryTarget.LOCAL_NODE + "/networks -> 200");

    docker(MACHINE_SLUG, "info", "0");
    docker(MACHINE_SLUG, "system df", "0");
    docker(MACHINE_SLUG, "ps -a", "0");
    docker(MACHINE_SLUG, "container inspect " + StoryTarget.CONTAINER, "0");
    docker(MACHINE_SLUG, "image ls", "0");
    docker(MACHINE_SLUG, "volume ls", "0");
    docker(MACHINE_SLUG, "network ls", "0");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        MACHINE_SLUG,
        NetworkEdge.SOCKET,
        StoryDocker.DOCKER,
        StoryDocker.DAEMON,
        StoryDocker.SOCKET_LABEL);

    // Six reads in, seven calls out to the CLI, one socket behind it. THE CLOSURE IS THE CLAIM:
    // "it is introspection of THIS machine and no peer" is what the story says, and a call to a
    // host this catalogue would then have to stand in for would be a fifteenth edge no presence
    // check could see.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, MACHINE_SLUG, 14);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        MACHINE_SLUG,
        List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE, StoryDocker.DOCKER));
    // A person's bearer is checked against the keys fetched at boot, so nothing here re-asks the
    // idp who they are — not once, not on a cache miss, not at all.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, MACHINE_SLUG, "qits-platform-idp");
    // And the value behind the environment key reached no label, no note and no transcript.
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, MACHINE_SLUG, StoryTarget.SCRUBBED_SECRET);

    // --- the swarm --------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, SWARM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, SWARM_SLUG, "nodes-read");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SWARM_SLUG, "service-read");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SWARM_SLUG, "config-decoded");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SWARM_SLUG, "secrets-are-names-only");

    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/nodes -> 200");
    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/nodes/" + StoryTarget.NODE_ID + " -> 200");
    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/services -> 200");
    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/services/" + StoryTarget.SWARM_SERVICE + " -> 200");
    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/configs -> 200");
    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/configs/" + StoryTarget.SWARM_CONFIG + " -> 200");
    http(SWARM_SLUG, "GET " + StoryTarget.API + "/swarm/secrets -> 200");

    docker(SWARM_SLUG, "node ls", "0");
    docker(SWARM_SLUG, "node inspect " + StoryTarget.NODE_ID, "0");
    docker(SWARM_SLUG, "service ls", "0");
    docker(SWARM_SLUG, "service inspect " + StoryTarget.SWARM_SERVICE, "0");
    docker(SWARM_SLUG, "service ps " + StoryTarget.SWARM_SERVICE, "0");
    docker(SWARM_SLUG, "config ls", "0");
    docker(SWARM_SLUG, "config inspect " + StoryTarget.SWARM_CONFIG, "0");
    docker(SWARM_SLUG, "secret ls", "0");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SWARM_SLUG,
        NetworkEdge.SOCKET,
        StoryDocker.DOCKER,
        StoryDocker.DAEMON,
        StoryDocker.SOCKET_LABEL);

    // THE STORY'S TITLE, ASSERTED AS A SHAPE. Seven reads, eight calls, one socket — and NO `secret
    // inspect` among them, which is the one edge whose absence is the whole second half of the
    // title. A count is what makes that absence checkable rather than merely unmentioned.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SWARM_SLUG, 16);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SWARM_SLUG,
        List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE, StoryDocker.DOCKER));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, SWARM_SLUG, StoryTarget.SCRUBBED_SECRET);
  }

  private static void http(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, StoryIdentities.OPERATOR, StoryTarget.SERVICE, label);
  }

  private static void docker(String slug, String summary, String exitCode) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        slug,
        StoryDocker.KIND,
        StoryTarget.SERVICE,
        StoryDocker.DOCKER,
        StoryDocker.label(summary, exitCode));
  }
}
