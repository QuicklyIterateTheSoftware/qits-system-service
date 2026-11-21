package eu.wohlben.qits.system.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The swarm parsers, against real `node`, `service`, `config` and `secret` output. */
class SwarmParsersTest {

  @Test
  void readsANodeList() {
    List<NodeSummary> nodes =
        Fixtures.lines("docker-node-ls.jsonl").stream().map(NodeSummary::from).toList();

    assertEquals(1, nodes.size());
    NodeSummary node = nodes.get(0);
    assertEquals("iwbwrdui2z0n62kqcjfo1erwh", node.id());
    assertEquals("DESKTOP-3630IEI", node.hostname());
    assertEquals("Ready", node.status());
    assertEquals("Active", node.availability());
    assertEquals("Leader", node.managerStatus());
    // Derived: the list has no Role column, and a node with any manager status IS a manager.
    assertEquals("manager", node.role());
    assertEquals("29.7.2", node.engineVersion());
    // The one field the client changes its behaviour on: only this node can be looked into.
    assertTrue(node.self());
  }

  @Test
  void readsANodeDocument() {
    NodeDetail node = NodeDetail.from(Fixtures.array("docker-node-inspect.json").get(0), true);

    assertEquals("iwbwrdui2z0n62kqcjfo1erwh", node.id());
    assertEquals("DESKTOP-3630IEI", node.hostname());
    assertEquals("manager", node.role());
    // The document says "active" and "ready"; the detail answers in the list's vocabulary, so a
    // row and the page it opens print the same word (qits-2347).
    assertEquals("Active", node.availability());
    assertEquals("Ready", node.status());
    assertEquals("192.168.152.4", node.address());
    assertEquals("29.7.2", node.engineVersion());
    assertEquals("x86_64", node.architecture());
    assertEquals("linux", node.os());
    // Reported as nanocores, shown as cores.
    assertEquals(24.0, node.cpus(), 0.001);
    assertTrue(node.memoryBytes() > 0);
    // Rebuilt in the LIST's vocabulary, so a detail page and the row it was opened from agree.
    assertEquals("Leader", node.managerStatus());
    // The document does not know which node read it; the caller says, and the detail carries it.
    assertTrue(node.self());
  }

  @Test
  void aNodeDocumentSpeaksTheListsVocabularyForEveryState() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String[][] rows = {
      {"ready", "active", "Ready", "Active"},
      {"down", "drain", "Down", "Drain"},
      {"unknown", "pause", "Unknown", "Pause"},
    };
    for (String[] row : rows) {
      NodeDetail node =
          NodeDetail.from(
              mapper.readTree(
                  "{\"ID\":\"n1\",\"Spec\":{\"Role\":\"worker\",\"Availability\":\""
                      + row[1]
                      + "\"},\"Status\":{\"State\":\""
                      + row[0]
                      + "\"}}"),
              false);
      assertEquals(row[2], node.status());
      assertEquals(row[3], node.availability());
      // A worker has no manager status, and a node read from elsewhere is not this one.
      assertNull(node.managerStatus());
      assertFalse(node.self());
    }
  }

  @Test
  void readsAServiceList() {
    List<ServiceSummary> services =
        Fixtures.lines("docker-service-ls.jsonl").stream().map(ServiceSummary::from).toList();

    assertEquals(3, services.size());
    ServiceSummary first = services.get(0);
    assertEquals("dev-qits-artifacts", first.name());
    assertEquals("replicated", first.mode());
    // The CLI's own running-over-desired string, which is the number a list is scanned for.
    assertEquals("1/1", first.replicas());
    assertTrue(first.image().startsWith("registry.dev.localhost:8080/qits/qits-artifacts:"));
  }

  @Test
  void readsAServiceDocumentAndNeverItsEnvironmentValues() {
    List<TaskSummary> tasks =
        Fixtures.lines("docker-service-ps.jsonl").stream().map(TaskSummary::from).toList();

    ServiceDetail service =
        ServiceDetail.from(Fixtures.array("docker-service-inspect.json").get(0), tasks);

    assertEquals("dev-qits-artifacts", service.name());
    assertEquals("replicated", service.mode());
    // The list's own string, rebuilt: one task is Running of the one replica wanted.
    assertEquals("1/1", service.replicas());
    assertEquals("stop-first", service.updateOrder());
    assertEquals("rollback", service.updateFailureAction());
    assertEquals("any", service.restartCondition());
    assertEquals(
        "qits-artifacts", service.labels().get("qits.platform.deployments.app-name"));
    assertEquals(1, service.networks().size());
    assertEquals("10.0.1.110/24", service.networks().get(0).address());
    // ContainerSpec.Mounts, in the spec's order; an absent ReadOnly is docker's false.
    assertEquals(
        List.of(
            new ServiceDetail.Mount("volume", "dev-qits-artifacts-store", "/data", false),
            new ServiceDetail.Mount("bind", "/etc/qits/edge", "/etc/qits/edge", true)),
        service.mounts());

    // THE POINT OF THIS TEST. The captured service is a real platform one whose environment
    // carried a database password; the fixture scrubbed it to a marker, and the marker must not
    // survive into anything this service returns.
    assertTrue(
        service.envKeys().contains("QITS_RESOURCE_DB_PASSWORD"),
        "the KEY is diagnostic and is kept: " + service.envKeys());
    assertFalse(
        service.envKeys().stream().anyMatch(key -> key.contains("=")),
        "a key must never carry its value: " + service.envKeys());
    assertFalse(
        service.toString().contains("not-a-real-secret"),
        "no environment VALUE may reach the wire");
  }

  @Test
  void aServiceThatMountsNothingHasNoMounts() throws Exception {
    ServiceDetail service =
        ServiceDetail.from(
            new ObjectMapper()
                .readTree(
                    "{\"Spec\":{\"Name\":\"dev-qits-idle\",\"TaskTemplate\":{\"ContainerSpec\":{}},"
                        + "\"Mode\":{\"Replicated\":{\"Replicas\":0}}}}"),
            List.of());

    assertEquals(List.of(), service.mounts());
    assertEquals("0/0", service.replicas());
    assertEquals(List.of(), service.tasks());
  }

  @Test
  void readsTheTasksWithTheirUntruncatedErrors() {
    List<TaskSummary> tasks =
        Fixtures.lines("docker-service-ps.jsonl").stream().map(TaskSummary::from).toList();

    assertEquals(5, tasks.size());
    assertEquals("Running", tasks.get(0).desiredState());
    assertEquals("DESKTOP-3630IEI", tasks.get(0).nodeHostname());
    // Derived from the task name, which swarm spells <service>.<slot>.
    assertEquals(1, tasks.get(0).slot());
    // `--no-trunc` is why this is readable: the default cuts the error off mid-sentence, and the
    // error is the whole reason anybody opens a task list.
    TaskSummary failed = tasks.get(1);
    assertEquals("Shutdown", failed.desiredState());
    assertTrue(failed.state().startsWith("Failed"));
    assertTrue(failed.error().contains("No such container"), failed.error());
  }

  @Test
  void readsConfigsAndDecodesTheirData() {
    List<ConfigSummary> configs =
        Fixtures.lines("docker-config-rows.jsonl").stream().map(ConfigSummary::from).toList();
    assertEquals(2, configs.size());
    assertEquals("qits-edge-vhosts", configs.get(0).name());
    // Inspect's instant, as text: the `ls` formatter would have printed an age (qits-2487).
    assertEquals("2026-08-18T04:31:02.113044212Z", configs.get(0).createdAt());
    assertEquals("2026-08-18T04:31:02.113044212Z", configs.get(0).updatedAt());
    assertEquals("platform", configs.get(0).labels().get("qits.platform.deployments.target"));
    // `{{json .Spec.Labels}}` prints null for a config with none.
    assertTrue(configs.get(1).labels().isEmpty(), "an unlabelled config has no labels, not a null");

    ConfigDetail detail = ConfigDetail.from(Fixtures.array("docker-config-inspect.json").get(0));
    assertEquals("qits-edge-vhosts", detail.name());
    assertFalse(detail.binary());
    // Swarm base64-encodes the bytes; showing them is the point of a config detail.
    assertEquals("registry.dev.localhost -> qits-artifacts\n", detail.data());
  }

  @Test
  void readsSecretsAsMetadataOnly() {
    List<SecretSummary> secrets =
        Fixtures.lines("docker-secret-rows.jsonl").stream().map(SecretSummary::from).toList();

    assertEquals(1, secrets.size());
    assertEquals("qits-platform-idp-signing-key", secrets.get(0).name());
    assertEquals("", secrets.get(0).driver());
    assertEquals("2026-08-18T04:31:09.552009133Z", secrets.get(0).createdAt());
    assertEquals("2026-08-18T04:31:09.552009133Z", secrets.get(0).updatedAt());
    assertEquals(
        java.util.Map.of("qits.platform.deployments.target", "platform"), secrets.get(0).labels());
    // There is no `data` component on this record to fill. Not a policy this service enforces:
    // swarm returns no secret value to anybody, so there is nothing here that could leak one.
    assertFalse(
        java.util.Arrays.stream(SecretSummary.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("data")),
        "there is no component here for a value to be put in");
  }

  @Test
  void aSecretIsUsedByTheServicesWhoseSpecReferencesIt() throws Exception {
    var mapper = new ObjectMapper();
    var secret = Fixtures.lines("docker-secret-rows.jsonl").get(0);
    // The usage template's lines: name and secret references, nothing else of a spec. One service
    // references by id, one by name alone, one an older secret of the same name (another id), one
    // nothing at all.
    List<com.fasterxml.jackson.databind.JsonNode> services =
        List.of(
            mapper.readTree(
                "{\"name\":\"dev-qits-ci\",\"secrets\":[{\"SecretID\":\""
                    + secret.get("ID").asText()
                    + "\",\"SecretName\":\"renamed\"}]}"),
            mapper.readTree(
                "{\"name\":\"dev-qits-artifacts\",\"secrets\":[{\"SecretName\":\""
                    + secret.get("Name").asText()
                    + "\"},{\"SecretID\":\"other\",\"SecretName\":\"other\"}]}"),
            mapper.readTree(
                "{\"name\":\"dev-qits-stale\",\"secrets\":[{\"SecretID\":\"an-older-one\","
                    + "\"SecretName\":\""
                    + secret.get("Name").asText()
                    + "\"}]}"),
            mapper.readTree("{\"name\":\"dev-qits-idle\",\"secrets\":null}"));

    SecretSummary used = SecretSummary.from(secret, services);
    // Sorted, each service once; the stale reference names a secret that is not this one.
    assertEquals(List.of("dev-qits-artifacts", "dev-qits-ci"), used.usedBy());

    assertEquals(List.of(), SecretSummary.from(secret).usedBy());
    assertEquals(List.of(), SecretSummary.from(secret, List.of(services.get(3))).usedBy());
  }

  @Test
  void theConfigRowNeverAsksForAConfigsData() {
    // A list shows no content, so the list read's inspect must not put any in its process output:
    // `.Spec.Data` is the detail door's (qits-2487).
    assertFalse(SwarmReads.CONFIG_ROW.contains("Data"), SwarmReads.CONFIG_ROW);
    assertFalse(SwarmReads.SECRET_ROW.contains("Data"), SwarmReads.SECRET_ROW);
  }

  @Test
  void theRowTemplatesPrintTheKeysTheParsersRead() {
    // The fixtures above are the templates' output; this keeps the two from drifting apart.
    for (String key : List.of("ID", "Name", "CreatedAt", "UpdatedAt", "Labels")) {
      assertTrue(SwarmReads.CONFIG_ROW.contains("\"" + key + "\":"), key);
      assertTrue(SwarmReads.SECRET_ROW.contains("\"" + key + "\":"), key);
    }
    assertTrue(SwarmReads.SECRET_ROW.contains("\"Driver\":"));
    assertTrue(SwarmReads.CONFIG_ROW.contains("{{json .CreatedAt}}"));
    assertTrue(SwarmReads.SECRET_ROW.contains("{{json .Spec.Labels}}"));
  }

  @Test
  void aBinaryConfigSaysSoRatherThanReturningMojibake() {
    // 0xFF 0xFE is not valid UTF-8; a decode would answer replacement characters and call them
    // the config.
    var node =
        Fixtures.array("docker-config-inspect.json")
            .get(0)
            .deepCopy()
            .<com.fasterxml.jackson.databind.node.ObjectNode>deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("Spec")).put("Data", "//4=");

    ConfigDetail detail = ConfigDetail.from(node);

    assertTrue(detail.binary());
    // Empty rather than null: the client renders a document, and `binary` is what says why it is
    // empty.
    assertEquals("", detail.data());
  }
}
