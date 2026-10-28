package eu.wohlben.qits.system.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.system.targets.ContractBearers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-system's provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of the published golden-master packages consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), asks the service and renders {@code
 * golden-masters/<state-slug>/<operationId>.json}; then it renders {@code golden-masters/index.json}
 * in the format qits-projects-service set (format version 1).
 *
 * <p><b>The swarm reads are not frozen</b>: the fake docker prints the same captures every run. <b>The
 * targets doors are</b> (qits-1481): ids and instants by shape ({@link Freezer}), seeded with the
 * states' fixed ids so a path's {@code {targetId}} is the body's; the pinned runner version where it
 * appears. A door that takes a body records it as the index's {@code body}, a door whose answer
 * depends on the caller's bearer records {@code Authorization: {authorization}} as its {@code
 * headers}, and its path keeps its {@code {param}}s — the shape qits-ci set, and the one
 * qits-runner-toolkit's register rows read. An answer with no body (a 204) is recorded as JSON
 * {@code null}, a text answer ({@code install.sh}) as one JSON string.
 *
 * <p><b>Terminals are not recorded.</b> A terminal's id is random and its list depends on what the
 * other test classes in this JVM opened.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The application name, as every provider's index names itself. */
  static final String PROVIDER = "qits-system";

  /**
   * One recorded interaction. {@code path} may carry {@code {param}}s of its state; {@code body}
   * likewise; {@code headers} are recorded unexpanded and sent expanded.
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String body,
      Map<String, String> headers) {}

  private static final String STATE = ProviderStates.A_SINGLE_NODE_SWARM;
  private static final String API = "/system/api";
  private static final String CONTAINER =
      API + "/nodes/local/containers/" + ProviderStates.CONTAINER_ID;
  private static final Map<String, String> BEARER = Map.of("Authorization", "{authorization}");

  private static final String TARGET = API + "/targets/{targetId}";
  private static final String RUNNER = API + "/targets/{runnerId}";
  private static final String LEASE = API + "/leases/{leaseId}";
  private static final String SIGN_IN = TARGET + "/sign-ins/{signInId}";

  /**
   * The swarm reads publish no operationIds of their own (no {@code @Operation} annotations), so
   * their names are this table's; the targets doors name theirs in their {@code @Operation}.
   * Renaming one is a contract change all the same.
   */

  private static List<Interaction> interactions() {
    List<Interaction> all = new java.util.ArrayList<>(BASE);
    all.addAll(
        tenant(
            ProviderStates.A_QUEUED_WORKSPACE_LEASE,
            ProviderStates.A_RUNNING_WORKSPACE_LEASE,
            ProviderStates.AN_UNUSED_WORKSPACE_WORK_VOLUME,
            "WORKSPACE",
            "18",
            "ws-18",
            ProviderStates.WORKSPACE_IMAGE));
    all.addAll(
        tenant(
            ProviderStates.A_QUEUED_DESK_LEASE,
            ProviderStates.A_RUNNING_DESK_LEASE,
            ProviderStates.AN_UNUSED_DESK_WORK_VOLUME,
            "DESK",
            "00000000-0000-4000-8000-000000000004",
            "desk-00000000-0000-4000-8000-000000000004",
            ProviderStates.DESK_IMAGE));
    return List.copyOf(all);
  }

  /**
   * One tenant's lease doors over its three states, every call with its bearer: a create (beside
   * the queued lease, for another ownerRef), the reads, a roll and an end of the queued and of the
   * running lease, and the purge of the unused work volume.
   */
  private static List<Interaction> tenant(
      String queued,
      String running,
      String unused,
      String type,
      String newRef,
      String newWorkKey,
      String image) {
    String spec =
        "{\"image\":\""
            + image
            + "\",\"env\":{\"QITS_TOKEN\":\"qits_tok_lease\"},\"user\":\"1000\","
            + "\"limits\":{\"memory\":\"8g\"}}";
    List<Interaction> rows = new java.util.ArrayList<>();
    rows.add(
        call(
            queued,
            "createLease",
            "POST",
            API + "/leases",
            201,
            "{\"slotType\":\""
                + type
                + "\",\"ownerRef\":\""
                + newRef
                + "\",\"workKey\":\""
                + newWorkKey
                + "\",\"priority\":0,\"spec\":"
                + spec
                + "}",
            BEARER));
    for (String state : List.of(queued, running)) {
      rows.add(call(state, "getLease", "GET", LEASE, 200, null, BEARER));
      rows.add(call(state, "listLeases", "GET", API + "/leases", 200, null, BEARER));
      rows.add(
          call(state, "replaceLeaseSpec", "PUT", LEASE + "/spec", 200, "{\"spec\":" + spec + "}", BEARER));
      rows.add(call(state, "endLease", "DELETE", LEASE, 200, null, BEARER));
    }
    rows.add(
        call(unused, "purgeWorkVolume", "DELETE", API + "/work-volumes/{workKey}", 202, null, BEARER));
    return rows;
  }

  private static final List<Interaction> BASE =
      List.of(
          get("getOverview", API + "/overview"),
          get("listSwarmNodes", API + "/swarm/nodes"),
          get("getSwarmNode", API + "/swarm/nodes/" + ProviderStates.NODE_ID),
          get("listSwarmServices", API + "/swarm/services"),
          get("getSwarmService", API + "/swarm/services/" + ProviderStates.SERVICE_ID),
          get("listSwarmConfigs", API + "/swarm/configs"),
          get("getSwarmConfig", API + "/swarm/configs/" + ProviderStates.CONFIG_ID),
          get("listSwarmSecrets", API + "/swarm/secrets"),
          get("listNodeContainers", API + "/nodes/local/containers"),
          get("getNodeContainer", CONTAINER),
          get("getContainerLogs", CONTAINER + "/logs?tail=200"),
          get("listNodeImages", API + "/nodes/local/images"),
          get("listNodeVolumes", API + "/nodes/local/volumes"),
          get("listNodeNetworks", API + "/nodes/local/networks"),
          // --- the targets doors (qits-1481) ---
          call(
              ProviderStates.NO_TARGET,
              "createTarget",
              "POST",
              API + "/targets",
              201,
              "{\"name\":\"lab\",\"description\":\"the lab box\","
                  + "\"reservations\":{\"ci\":2,\"workspace\":2,\"desk\":1}}",
              Map.of()),
          call(
              ProviderStates.NO_TARGET,
              "getTargetInstallScript",
              "GET",
              API + "/targets/install.sh",
              200,
              null,
              Map.of()),
          call(
              ProviderStates.NO_TARGET,
              "createLease",
              "POST",
              API + "/leases",
              201,
              "{\"slotType\":\"CI\",\"ownerRef\":\"run-1\",\"priority\":0,\"spec\":{\"image\":\""
                  + ProviderStates.CI_IMAGE
                  + "\",\"env\":{\"QITS_TOKEN\":\"qits_tok_run\"},\"user\":\"1001\","
                  + "\"limits\":{\"memory\":\"4g\",\"pidsLimit\":4096}}}",
              BEARER),
          call(ProviderStates.A_DECLARED_TARGET, "listTargets", "GET", API + "/targets", 200, null, Map.of()),
          call(
              ProviderStates.A_CONNECTED_TARGET_WITH_LEASES,
              "listTargets",
              "GET",
              API + "/targets",
              200,
              null,
              Map.of()),
          call(ProviderStates.A_DECLARED_TARGET, "getTarget", "GET", TARGET, 200, null, Map.of()),
          call(
              ProviderStates.A_DECLARED_TARGET,
              "getTargetHealth",
              "GET",
              TARGET + "/health",
              200,
              null,
              Map.of()),
          call(
              ProviderStates.A_DECLARED_TARGET,
              "patchTarget",
              "PATCH",
              TARGET,
              200,
              "{\"description\":\"the lab box, rebuilt\",\"reservations\":{\"desk\":0}}",
              Map.of()),
          call(
              ProviderStates.A_DECLARED_TARGET,
              "greenlightTarget",
              "POST",
              TARGET + "/greenlight",
              200,
              null,
              Map.of()),
          call(
              ProviderStates.A_DECLARED_TARGET,
              "requestTargetHealthCheck",
              "POST",
              TARGET + "/healthcheck",
              409,
              null,
              Map.of()),
          call(
              ProviderStates.A_DECLARED_TARGET,
              "requestTargetLoginCheck",
              "POST",
              TARGET + "/login-check",
              409,
              null,
              Map.of()),
          call(ProviderStates.A_DECLARED_TARGET, "deleteTarget", "DELETE", TARGET, 204, null, Map.of()),
          // --- the sign-in doors (qits-1593) ---
          call(
              ProviderStates.A_CONNECTED_TARGET,
              "startTargetSignIn",
              "POST",
              TARGET + "/sign-ins",
              202,
              "{\"harness\":\"claude\"}",
              Map.of()),
          call(
              ProviderStates.A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE,
              "getTargetSignIn",
              "GET",
              SIGN_IN,
              200,
              null,
              Map.of()),
          call(
              ProviderStates.A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE,
              "submitTargetSignInCode",
              "POST",
              SIGN_IN + "/code",
              202,
              "{\"code\":\"contract-code#contract\"}",
              Map.of()),
          call(
              ProviderStates.A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE,
              "cancelTargetSignIn",
              "DELETE",
              SIGN_IN,
              202,
              null,
              Map.of()),
          call(
              ProviderStates.AN_UNREGISTERED_RUNNER,
              "registerTarget",
              "POST",
              RUNNER + "/register",
              200,
              "{\"capabilities\":{\"arch\":\"amd64\",\"docker\":true}}",
              BEARER),
          call(
              ProviderStates.AN_UNREGISTERED_RUNNER,
              "rotateTargetRegistrationToken",
              "POST",
              RUNNER + "/registration-token",
              200,
              null,
              Map.of()),
          call(
              ProviderStates.A_REGISTERED_RUNNER,
              "registerTarget",
              "POST",
              RUNNER + "/register",
              409,
              "{\"capabilities\":{\"arch\":\"amd64\",\"docker\":true}}",
              BEARER),
          call(
              ProviderStates.A_REGISTERED_RUNNER,
              "rotateTargetRegistrationToken",
              "POST",
              RUNNER + "/registration-token",
              200,
              "{\"slots\":2}",
              Map.of()),
          call(
              ProviderStates.A_ROTATED_RUNNER,
              "registerTarget",
              "POST",
              RUNNER + "/register",
              200,
              "{\"capabilities\":{\"arch\":\"amd64\",\"docker\":true}}",
              BEARER),
          call(ProviderStates.A_QUEUED_LEASE, "listLeases", "GET", API + "/leases", 200, null, BEARER),
          call(ProviderStates.A_QUEUED_LEASE, "getLease", "GET", LEASE, 200, null, BEARER),
          call(
              ProviderStates.A_QUEUED_LEASE,
              "replaceLeaseSpec",
              "PUT",
              LEASE + "/spec",
              200,
              "{\"spec\":{\"image\":\""
                  + ProviderStates.CI_IMAGE
                  + "\",\"env\":{\"QITS_TOKEN\":\"qits_tok_run\"},\"user\":\"1001\","
                  + "\"limits\":{\"memory\":\"8g\"}}}",
              BEARER),
          call(ProviderStates.A_QUEUED_LEASE, "endLease", "DELETE", LEASE, 200, null, BEARER),
          call(
              ProviderStates.AN_UNUSED_WORK_VOLUME,
              "purgeWorkVolume",
              "DELETE",
              API + "/work-volumes/{workKey}",
              202,
              null,
              BEARER));

  static final List<Interaction> INTERACTIONS = interactions();

  private static Interaction get(String operationId, String path) {
    return new Interaction(STATE, operationId, "GET", path, 200, null, Map.of());
  }

  private static Interaction call(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String body,
      Map<String, String> headers) {
    return new Interaction(state, operationId, method, path, status, body, headers);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ContractBearers bearers;

  @TestHTTPResource("/")
  URL base;

  private ProviderStates states;

  @AfterEach
  void disarm() {
    if (states != null) {
      states.close();
    }
    bearers.reset();
  }

  /** One interaction as recorded: the frozen answer, the frozen params, the freezer's paths. */
  record Recorded(JsonNode body, Map<String, String> params, Freezer freezer) {}

  @Test
  void goldenMastersMatchTheProvider() throws Exception {
    states = new ProviderStates(bearers, URI.create(base.toString()));
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      ProviderStates.Setup setup = states.setUp(interaction.state());
      Recorded recorded = record(interaction, setup);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
      new TreeMap<>(recorded.params()).forEach(frozenParams::put);
      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", frozenParams);
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(frozenParams)) {
        failures.add("State '" + interaction.state() + "' gave different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (!interaction.headers().isEmpty()) {
        ObjectNode headers = operation.putObject("headers");
        new TreeMap<>(interaction.headers()).forEach(headers::put);
      }
      if (interaction.body() != null) {
        operation.set("body", JSON.readTree(interaction.body()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private Recorded record(Interaction interaction, ProviderStates.Setup setup) throws Exception {
    Map<String, String> params = setup.params();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(base.toString()).resolve(expand(interaction.path(), params)))
            .header("Accept", "application/json, text/plain");
    for (Map.Entry<String, String> header : interaction.headers().entrySet()) {
      request.header(header.getKey(), expand(header.getValue(), params));
    }
    if (interaction.body() != null) {
      request
          .header("Content-Type", "application/json")
          .method(
              interaction.method(),
              HttpRequest.BodyPublishers.ofString(expand(interaction.body(), params)));
    } else {
      request.method(interaction.method(), HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + response.body());
    }
    JsonNode body;
    String text = response.body();
    String type = response.headers().firstValue("Content-Type").orElse("");
    if (text == null || text.isEmpty()) {
      body = NullNode.getInstance();
    } else if (type.startsWith("text/")) {
      body = TextNode.valueOf(text);
    } else {
      body = JSON.readTree(text);
    }
    if (interaction.state().equals(STATE)) {
      // The swarm reads are the fake docker's captures, the same every run: nothing to freeze.
      return new Recorded(body, params, new Freezer());
    }
    Freezer freezer =
        new Freezer().seed(ProviderStates.fixedIds()).uniqueTokens(setup.uniqueTokens());
    Map<String, String> frozenParams = new LinkedHashMap<>();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  private static String expand(String template, Map<String, String> params) {
    String out = template;
    for (Map.Entry<String, String> param : params.entrySet()) {
      out = out.replace("{" + param.getKey() + "}", param.getValue());
    }
    return out;
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode array = JsonNodeFactory.instance.arrayNode();
    values.forEach(array::add);
    return array;
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
