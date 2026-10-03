package eu.wohlben.qits.system.startup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.docker.DockerCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The startup writer, as a plain object: a @QuarkusTest profile per credential state would cost a
 * whole application for a two-line decision. The file's content is DockerClientConfigTest's.
 */
class DockerCredentialWriterTest {

  @TempDir Path tmp;

  private DockerCredentialWriter writer(Optional<String> id, Optional<String> secret) {
    DockerCredentialWriter writer = new DockerCredentialWriter();
    writer.clientId = id;
    writer.clientSecret = secret;
    writer.glancesRepo = "mirror.dev.localhost:8080/hub/nicolargo/glances";
    Path dir = tmp.resolve("docker");
    writer.docker =
        new DockerCli() {
          @Override
          public Path configDir() {
            return dir;
          }
        };
    return writer;
  }

  @Test
  void itWritesWhenTheDeployerInjectedTheClient() {
    assertTrue(writer(Optional.of("dev-qits-system"), Optional.of("s3cr3t")).write());
    assertTrue(Files.isRegularFile(tmp.resolve("docker").resolve("config.json")));
  }

  @Test
  void itWritesNothingWhenEitherHalfIsUnset() {
    assertFalse(writer(Optional.empty(), Optional.empty()).write());
    assertFalse(writer(Optional.of("dev-qits-system"), Optional.empty()).write());
    assertFalse(writer(Optional.empty(), Optional.of("s3cr3t")).write());
    assertFalse(Files.exists(tmp.resolve("docker")));
  }
}
