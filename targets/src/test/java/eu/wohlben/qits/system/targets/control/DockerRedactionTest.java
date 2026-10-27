package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** {@link DockerRedaction}: what an inspect or a listing may say of a secret. */
class DockerRedactionTest {

  @Test
  void theKeyPatternIsTheDossiersCaseBlind() {
    for (String key :
        new String[] {
          "QITS_TOKEN", "client_secret", "DB_PASSWORD", "passwd", "GIT_CREDENTIALS", "API_KEY",
          "openai_apikey", "SSH_PRIVATE_KEY", "AUTHORIZATION", "qits.auth.header"
        }) {
      assertTrue(DockerRedaction.secretKey(key), key);
    }
    // qits-1621 verify: by key segment, so AUTH inside AUTHOR or a host's GIT_AUTH is no secret.
    for (String key :
        new String[] {
          "PATH", "HOME", "QITS_LEASE_ID", "qits.system.runner", "LANG", "GIT_AUTHOR_NAME",
          "GIT_AUTHOR_EMAIL", "QITS_GIT_AUTH_HOST", "GIT_COMMITTER_EMAIL"
        }) {
      assertFalse(DockerRedaction.secretKey(key), key);
    }
  }

  @Test
  void aContainersSecretEnvAndLabelValuesAreMaskedAndTheRestKept() {
    JsonNode inspected =
        DockerRedaction.inspected(
            """
            [{"Id":"abc","Name":"/qits-system-runner-lease-1",
              "Config":{"Env":["PATH=/usr/bin","QITS_TOKEN=tok-123456","EMPTY_SECRET=","NO_EQUALS"],
                        "Labels":{"qits.system.runner":"t-1","qits.git.password":"hunter22"}}}]
            """);
    JsonNode config = inspected.get(0).get("Config");
    assertEquals("PATH=/usr/bin", config.get("Env").get(0).asText());
    assertEquals("QITS_TOKEN=***", config.get("Env").get(1).asText());
    assertEquals("EMPTY_SECRET=***", config.get("Env").get(2).asText());
    assertEquals("NO_EQUALS", config.get("Env").get(3).asText());
    assertEquals("t-1", config.get("Labels").get("qits.system.runner").asText());
    assertEquals("***", config.get("Labels").get("qits.git.password").asText());
    assertFalse(inspected.toString().contains("tok-123456"));
    assertFalse(inspected.toString().contains("hunter22"));
  }

  @Test
  void aVolumesSecretLabelsAreMaskedAsAnObject() {
    JsonNode inspected =
        DockerRedaction.inspected(
            """
            {"Name":"qits-system-runner-work-ws-1-abcd1234","Size":"12MB",
             "Labels":{"qits.system.runner":"t-1","registry-auth":"Zm9vOmJhcg=="}}
            """);
    assertEquals("***", inspected.get("Labels").get("registry-auth").asText());
    assertEquals("t-1", inspected.get("Labels").get("qits.system.runner").asText());
    assertEquals("12MB", inspected.get("Size").asText());
  }

  @Test
  void aListingRowsLabelStringIsMaskedPairByPair() {
    JsonNode listed =
        DockerRedaction.listed(
            """
            [{"Names":"qits-system-runner-lease-1","Labels":"qits.system.runner=t-1,API_KEY=sk-live-9,qits.system.runner.lease=l-1"},
             {"Names":"other","Labels":""}]
            """);
    assertEquals(
        "qits.system.runner=t-1,API_KEY=***,qits.system.runner.lease=l-1",
        listed.get(0).get("Labels").asText());
    assertEquals("", listed.get(1).get("Labels").asText());
  }

  @Test
  void anAnswerThatIsNotJsonIsRefusedNotPassedThrough() {
    assertThrows(IllegalArgumentException.class, () -> DockerRedaction.inspected("QITS_TOKEN=x"));
    assertThrows(IllegalArgumentException.class, () -> DockerRedaction.listed(""));
  }
}
