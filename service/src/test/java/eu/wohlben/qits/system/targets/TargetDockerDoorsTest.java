package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Docker API (epic qits-1621, "Docker over the runner socket") end to end: a REST call sends a
 * real {@code docker} frame to a {@link FakeTargetRunner}, which answers a {@code docker-result};
 * and every call leaves one {@code target_audit} row, the refused, unanswered and unconnected ones
 * included.
 */
@QuarkusTest
class TargetDockerDoorsTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final String LEASE_CONTAINER = "qits-system-runner-lease-l1-abcd1234";

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  private UUID target;

  private String api;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, CLIENT, true);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
    api = "/system/api/targets/" + target + "/docker";
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  private FakeTargetRunner greetedRunner() throws InterruptedException {
    FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
    runner.hello(4, "qits-system-runner-dot-claude-lab");
    runner.await(Ack.class);
    runner.await(Quarantined.class);
    runner.await(Estate.class);
    HealthCheck check = runner.await(HealthCheck.class);
    runner.healthChecked(check.requestId(), true);
    runner.await(Reinstated.class);
    runner.await(Ack.class);
    return runner;
  }

  private static RequestSpecification as(String user, String roles) {
    return given().header("X-Qits-User", user).header("X-Qits-Roles", roles);
  }

  private static RequestSpecification operator() {
    return as("an-operator", "qits:admin");
  }

  /** Starts {@code call} off the test thread, so the runner can answer while it waits. */
  private static CompletableFuture<Response> async(Supplier<Response> call) {
    return CompletableFuture.supplyAsync(call);
  }

  private static TargetAudit onlyAuditRow() {
    List<TargetAudit> rows = TargetRows.allAudit();
    assertEquals(1, rows.size(), "one call, one row: " + rows.size());
    return rows.get(0);
  }

  @Test
  void aListingIsOneDockerFrameItsLabelsRedactedAndOneAuditRow() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> call = async(() -> operator().get(api + "/containers"));
      Docker request = runner.await(Docker.class, d -> d.op() == DockerOp.LIST_CONTAINERS);
      assertNull(request.name());
      runner.dockerResult(
          request,
          DockerStatus.OK,
          "[{\"Names\":\"" + LEASE_CONTAINER + "\",\"Labels\":\"qits.system.runner=" + target
              + ",QITS_TOKEN=tok-abcdef\"}]");
      call.join()
          .then()
          .statusCode(200)
          .body("[0].Names", equalTo(LEASE_CONTAINER))
          .body("[0].Labels", equalTo("qits.system.runner=" + target + ",QITS_TOKEN=***"));
    }
    TargetAudit row = onlyAuditRow();
    assertEquals("DOCKER_LIST_CONTAINERS", row.operation);
    assertEquals("OK", row.outcome);
    assertEquals("an-operator", row.actor);
    assertEquals("qits:admin", row.roles);
    assertEquals(target, row.targetId);
    assertNull(row.resource);
    assertNull(row.args);
  }

  @Test
  void anInspectByTheTargetsNameRedactsEnvAndLabelValuesBySecretKey() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> call =
          async(
              () ->
                  as("delivery-desk-admin", "qits:desk-admin")
                      .get("/system/api/targets/lab/docker/containers/" + LEASE_CONTAINER));
      Docker request = runner.await(Docker.class, d -> d.op() == DockerOp.INSPECT_CONTAINER);
      assertEquals(LEASE_CONTAINER, request.name());
      runner.dockerResult(
          request,
          DockerStatus.OK,
          "{\"Name\":\"/" + LEASE_CONTAINER + "\",\"Config\":{\"Env\":[\"PATH=/bin\","
              + "\"GIT_PASSWORD=hunter22\"],\"Labels\":{\"qits.registry.auth\":\"Zm9v\","
              + "\"qits.system.runner.lease\":\"l1\"}}}");
      call.join()
          .then()
          .statusCode(200)
          .body("Config.Env", equalTo(List.of("PATH=/bin", "GIT_PASSWORD=***")))
          .body("Config.Labels.'qits.registry.auth'", equalTo("***"))
          .body("Config.Labels.'qits.system.runner.lease'", equalTo("l1"))
          .body(not(containsString("hunter22")));
    }
    TargetAudit row = onlyAuditRow();
    assertEquals("DOCKER_INSPECT_CONTAINER", row.operation);
    assertEquals(LEASE_CONTAINER, row.resource);
    assertEquals("qits:desk-admin", row.roles);
    assertEquals("OK", row.outcome);
  }

  @Test
  void aLogIsPlainTextWithTheTruncationHeaderAndItsArgsAreAudited() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> call =
          async(
              () ->
                  operator().get(api + "/containers/" + LEASE_CONTAINER + "/logs?tail=50&since=10m"));
      Docker request = runner.await(Docker.class, d -> d.op() == DockerOp.LOGS);
      assertEquals(50, request.tail());
      assertEquals("10m", request.since());
      runner.send(
          new DockerResult(request.requestId(), DockerStatus.OK, "line 1\nline 2\n", true, null, null));
      Response answered = call.join();
      answered.then().statusCode(200).header("X-Qits-Truncated", "true");
      assertTrue(answered.contentType().startsWith("text/plain"), answered.contentType());
      assertEquals("line 1\nline 2\n", answered.asString());
    }
    TargetAudit row = onlyAuditRow();
    assertEquals("DOCKER_LOGS", row.operation);
    assertTrue(row.args.contains("\"tail\"") && row.args.contains("10m"), row.args);
  }

  @Test
  void anExecSendsItsArgvAndAnswersItsExitAndBothStreams() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> call =
          async(
              () ->
                  operator()
                      .contentType("application/json")
                      .body("{\"argv\":[\"ls\",\"-la\",\"/work\"],\"timeoutSeconds\":5}")
                      .post(api + "/containers/" + LEASE_CONTAINER + "/exec"));
      Docker request = runner.await(Docker.class, d -> d.op() == DockerOp.EXEC);
      assertEquals(List.of("ls", "-la", "/work"), request.argv());
      assertEquals(5, request.timeoutSeconds());
      runner.send(
          new DockerResult(request.requestId(), DockerStatus.OK, "total 0\n", false, 2, "ls: oops\n"));
      call.join()
          .then()
          .statusCode(200)
          .body("exitCode", equalTo(2))
          .body("stdout", equalTo("total 0\n"))
          .body("stderr", equalTo("ls: oops\n"))
          .body("truncated", equalTo(false));
    }
    TargetAudit row = onlyAuditRow();
    assertEquals("DOCKER_EXEC", row.operation);
    assertEquals("OK", row.outcome);
    assertEquals("exit 2", row.detail);
    assertTrue(row.args.contains("\"-la\""), row.args);
  }

  @Test
  void anExecWithNoArgvIsRefusedBeforeAnythingIsSentAndAudited() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      operator()
          .contentType("application/json")
          .body("{\"argv\":[]}")
          .post(api + "/containers/" + LEASE_CONTAINER + "/exec")
          .then()
          .statusCode(400);
      assertTrue(!runner.received(Docker.class));
    }
    assertEquals("BAD_REQUEST", onlyAuditRow().outcome);
  }

  @Test
  void theRunnersStatusesAreTheirHttpAnswersAndEachIsAudited() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> missing =
          async(() -> operator().get(api + "/volumes/qits-system-runner-work-gone"));
      runner.dockerResult(
          runner.await(Docker.class, d -> d.op() == DockerOp.INSPECT_VOLUME),
          DockerStatus.NOT_FOUND,
          "no volume qits-system-runner-work-gone");
      missing.join().then().statusCode(404).body("message", containsString("no volume"));

      CompletableFuture<Response> refused =
          async(() -> operator().post(api + "/containers/qits-system-runner-process/stop"));
      runner.dockerResult(
          runner.await(Docker.class, d -> d.op() == DockerOp.STOP),
          DockerStatus.REFUSED,
          "the runner's own container");
      refused.join().then().statusCode(403).body("code", equalTo("DOCKER_REFUSED"));

      CompletableFuture<Response> failed =
          async(() -> operator().delete(api + "/containers/" + LEASE_CONTAINER));
      runner.dockerResult(
          runner.await(Docker.class, d -> d.op() == DockerOp.REMOVE),
          DockerStatus.FAILED,
          "Cannot connect to the Docker daemon");
      failed
          .join()
          .then()
          .statusCode(502)
          .body("code", equalTo("DOCKER_FAILED"))
          .body("message", equalTo("Cannot connect to the Docker daemon"));
    }
    List<TargetAudit> rows = TargetRows.allAudit();
    assertEquals(
        List.of("DOCKER_INSPECT_VOLUME", "DOCKER_STOP", "DOCKER_REMOVE"),
        rows.stream().map(r -> r.operation).toList());
    assertEquals(
        List.of("NOT_FOUND", "REFUSED", "FAILED"), rows.stream().map(r -> r.outcome).toList());
    assertEquals("no volume qits-system-runner-work-gone", rows.get(0).detail);
  }

  @Test
  void anUnansweredCallIsA504AndATimeoutRow() throws Exception {
    try (FakeTargetRunner runner = greetedRunner()) {
      operator()
          .get(api + "/volumes")
          .then()
          .statusCode(504)
          .body("code", equalTo("DOCKER_TIMEOUT"));
      runner.await(Docker.class, d -> d.op() == DockerOp.LIST_VOLUMES);
    }
    TargetAudit row = onlyAuditRow();
    assertEquals("DOCKER_LIST_VOLUMES", row.operation);
    assertEquals("TIMEOUT", row.outcome);
  }

  @Test
  void aTargetWithNoLiveSocketIsA409AndANotConnectedRow() {
    operator()
        .get(api + "/containers")
        .then()
        .statusCode(409)
        .body("code", equalTo("TARGET_UNAVAILABLE"))
        .body("message", equalTo("target lab is not connected"));
    TargetAudit row = onlyAuditRow();
    assertEquals("NOT_CONNECTED", row.outcome);
    assertEquals(target, row.targetId);
  }

  @Test
  void anUnknownTargetIsA404AndANotFoundRowWithNoTarget() {
    operator().get("/system/api/targets/no-such-box/docker/containers").then().statusCode(404);
    TargetAudit row = onlyAuditRow();
    assertEquals("NOT_FOUND", row.outcome);
    assertEquals("DOCKER_LIST_CONTAINERS", row.operation);
    assertNull(row.targetId);
  }

  @Test
  void onlyTheOperatorRolesReachTheDockerDoors() {
    as("dyn-agent", "qits:agent").get(api + "/containers").then().statusCode(403);
    as("qits-ci", "qits:system").get(api + "/containers").then().statusCode(403);
    as("delivery-desk", "qits:desk").get(api + "/containers").then().statusCode(403);
    as("dyn-agent", "qits:agent").get("/system/api/leases/" + UUID.randomUUID() + "/logs").then().statusCode(403);
    as("delivery-desk-admin", "qits:desk-admin").get(api + "/audit").then().statusCode(200);
    as("the-coordinator", "qits:admin-agent").get(api + "/audit").then().statusCode(200);
    assertEquals(0, TargetRows.allAudit().size(), "a refused role reaches no door, so no row");
  }

  @Test
  void aLeasesLogIsItsLabelledContainersLogOnItsTarget() throws Exception {
    UUID lease = UUID.randomUUID();
    TargetRows.lease(lease, SlotType.CI, "qits-ci", "run-7", LeaseState.RUNNING, target);
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> call =
          async(
              () ->
                  as("delivery-desk-admin", "qits:desk-admin")
                      .get("/system/api/leases/" + lease + "/logs?tail=20"));
      runner.dockerResult(
          runner.await(Docker.class, d -> d.op() == DockerOp.LIST_CONTAINERS),
          DockerStatus.OK,
          "[{\"Names\":\"qits-system-runner-lease-other-1\",\"Labels\":\"qits.system.runner.lease="
              + UUID.randomUUID()
              + "\"},{\"Names\":\"qits-system-runner-lease-mine-2\",\"Labels\":\"qits.system.runner="
              + target
              + ",qits.system.runner.lease="
              + lease
              + "\"}]");
      Docker logs = runner.await(Docker.class, d -> d.op() == DockerOp.LOGS);
      assertEquals("qits-system-runner-lease-mine-2", logs.name());
      assertEquals(20, logs.tail());
      runner.dockerResult(logs, DockerStatus.OK, "build ok\n");
      call.join().then().statusCode(200).header("X-Qits-Truncated", "false");
    }
    TargetAudit row = onlyAuditRow();
    assertEquals("DOCKER_LOGS", row.operation);
    assertEquals(lease, row.leaseId);
    assertEquals(target, row.targetId);
    assertEquals("qits-system-runner-lease-mine-2", row.resource);
    assertEquals("OK", row.outcome);
  }

  @Test
  void aLeaseWithNoContainerOrNoLeaseAtAllIsA404AndARow() throws Exception {
    operator().get("/system/api/leases/" + UUID.randomUUID() + "/logs").then().statusCode(404);
    UUID queued = UUID.randomUUID();
    TargetRows.lease(queued, SlotType.CI, "qits-ci", "run-8", LeaseState.QUEUED, null);
    operator().get("/system/api/leases/" + queued + "/logs").then().statusCode(404);
    UUID lease = UUID.randomUUID();
    TargetRows.lease(lease, SlotType.CI, "qits-ci", "run-9", LeaseState.RUNNING, target);
    try (FakeTargetRunner runner = greetedRunner()) {
      CompletableFuture<Response> call =
          async(() -> operator().get("/system/api/leases/" + lease + "/logs"));
      runner.dockerResult(
          runner.await(Docker.class, d -> d.op() == DockerOp.LIST_CONTAINERS), DockerStatus.OK, "[]");
      call.join().then().statusCode(404).body("message", containsString("has no container"));
    }
    List<TargetAudit> rows = TargetRows.allAudit();
    assertEquals(3, rows.size());
    assertTrue(rows.stream().allMatch(r -> "NOT_FOUND".equals(r.outcome) && "DOCKER_LOGS".equals(r.operation)));
  }

  @Test
  void theAuditReadsTheTargetsRowsNewestFirst() {
    operator().get(api + "/containers").then().statusCode(409);
    operator().get(api + "/volumes").then().statusCode(409);
    operator()
        .get(api + "/audit?limit=1")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].operation", equalTo("DOCKER_LIST_VOLUMES"))
        .body("[0].outcome", equalTo("NOT_CONNECTED"))
        .body("[0].actor", equalTo("an-operator"));
    operator().get("/system/api/targets/lab/docker/audit").then().statusCode(200).body("size()", equalTo(2));
    operator().get("/system/api/targets/nope/docker/audit").then().statusCode(404);
  }
}
