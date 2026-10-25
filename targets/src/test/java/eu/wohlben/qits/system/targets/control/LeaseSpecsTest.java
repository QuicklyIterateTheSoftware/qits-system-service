package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.system.targets.entity.SlotType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link LeaseSpecs} and {@link OwnerIdentity#application(String, String)}: pure rules. */
class LeaseSpecsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Map<String, String> IMAGES =
      Map.of(
          "ci", "registry.qits.example.eu/qits/build-images/ci-slot",
          "workspace", "registry.qits.example.eu/qits/workspace",
          "desk", "registry.qits.example.eu/qits/project-agent");

  @Test
  void anImageMustBeItsTypesRepositoryNotASibling() {
    String prefix = IMAGES.get("workspace");
    assertTrue(LeaseSpecs.allowed(prefix + ":2026.1", prefix));
    assertTrue(LeaseSpecs.allowed(prefix + "@sha256:abc", prefix));
    assertTrue(LeaseSpecs.allowed(prefix, prefix));
    assertFalse(LeaseSpecs.allowed(prefix + "-editor:1", prefix));
    assertFalse(LeaseSpecs.allowed("docker.io/library/alpine:3", prefix));
  }

  @Test
  void aSpecIsCheckedAgainstItsType() throws Exception {
    LeaseSpecs.require(
        JSON.readTree("{\"image\":\"registry.qits.example.eu/qits/project-agent:1\"}"),
        SlotType.DESK,
        IMAGES);
    TargetRefusal refused =
        assertThrows(
            TargetRefusal.class,
            () ->
                LeaseSpecs.require(
                    JSON.readTree("{\"image\":\"registry.qits.example.eu/qits/workspace:1\"}"),
                    SlotType.DESK,
                    IMAGES));
    assertEquals(TargetRefusal.IMAGE_NOT_ALLOWED, refused.code());
    assertThrows(TargetRefusal.class, () -> LeaseSpecs.require(JSON.readTree("[]"), SlotType.CI, IMAGES));
    assertThrows(
        TargetRefusal.class,
        () ->
            LeaseSpecs.require(
                JSON.readTree(
                    "{\"image\":\"registry.qits.example.eu/qits/workspace:1\",\"env\":{\"A\":{}}}"),
                SlotType.WORKSPACE,
                IMAGES));
  }

  @Test
  void theHashIsTheCanonicalForms() throws Exception {
    assertEquals(
        LeaseSpecs.hash(JSON.readTree("{\"a\":1,\"b\":{\"d\":2,\"c\":3}}")),
        LeaseSpecs.hash(JSON.readTree("{\"b\":{\"c\":3,\"d\":2},\"a\":1}")));
    assertFalse(
        LeaseSpecs.hash(JSON.readTree("{\"a\":1}")).equals(LeaseSpecs.hash(JSON.readTree("{\"a\":2}"))));
  }

  @Test
  void theOwnerIsTheClientIdWithoutItsEnvironment() {
    assertEquals("qits-ci", OwnerIdentity.application("dev-qits-ci", "dev"));
    assertEquals("qits-workspaces", OwnerIdentity.application("prod-qits-workspaces", "prod"));
    assertEquals("qits-projects", OwnerIdentity.application("qits-projects", "dev"));
    assertEquals("dev-", OwnerIdentity.application("dev-", "dev"));
    assertEquals(null, OwnerIdentity.application(" ", "dev"));
  }

  @Test
  void aRedactedSpecKeepsEveryEnvKeyAndNoEnvValue() throws Exception {
    String text =
        "{\"image\":\"registry.qits.example.eu/qits/workspace:1\",\"user\":\"1000:1000\","
            + "\"limits\":{\"memory\":\"4g\"},"
            + "\"env\":{\"QITS_TOKEN\":\"qits_tok_secret\",\"PORT\":8080}}";
    var spec = JSON.readTree(text);

    var redacted = LeaseSpecs.redacted(spec);

    assertEquals(LeaseSpecs.REDACTED, redacted.get("env").get("QITS_TOKEN").asText());
    assertEquals(LeaseSpecs.REDACTED, redacted.get("env").get("PORT").asText());
    assertEquals(2, redacted.get("env").size());
    assertEquals(spec.get("image"), redacted.get("image"));
    assertEquals(spec.get("user"), redacted.get("user"));
    assertEquals(spec.get("limits"), redacted.get("limits"));
    assertFalse(LeaseSpecs.text(redacted).contains("qits_tok_secret"));
    assertEquals(JSON.readTree(text), spec, "the input is not touched");
    assertEquals("qits_tok_secret", spec.get("env").get("QITS_TOKEN").asText());
  }

  @Test
  void aSpecWithoutEnvRedactsToItself() throws Exception {
    assertEquals(null, LeaseSpecs.redacted(null));
    var bare = JSON.readTree("{\"image\":\"a:1\"}");
    assertEquals(bare, LeaseSpecs.redacted(bare));
    var nullEnv = JSON.readTree("{\"image\":\"a:1\",\"env\":null}");
    assertEquals(nullEnv, LeaseSpecs.redacted(nullEnv));
  }
}
