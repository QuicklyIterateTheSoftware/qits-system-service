package eu.wohlben.qits.system.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This service's own docker {@code config.json} (qits-879): which hosts, which bytes, which mode,
 * and the one decision that keeps a rollback working — {@code DOCKER_CONFIG} follows the FILE.
 */
class DockerClientConfigTest {

  private static final String REPO = "mirror.dev.localhost:8080/hub/nicolargo/glances";

  @TempDir Path tmp;

  @Test
  void theMirrorHostAndItsRegistrySiblingAreTheHosts() {
    assertEquals(
        List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080"),
        DockerClientConfig.hosts(REPO));
  }

  @Test
  void aHostThatIsNotAMirrorNameIsWrittenAloneAndDockerHubNamesNone() {
    assertEquals(
        List.of("registry.dev.localhost:8080"),
        DockerClientConfig.hosts("registry.dev.localhost:8080/qits/glances"));
    assertEquals(List.of("localhost:5000"), DockerClientConfig.hosts("localhost:5000/glances"));
    assertEquals(List.of(), DockerClientConfig.hosts("nicolargo/glances"));
    assertEquals(List.of(), DockerClientConfig.hosts("glances"));
  }

  @Test
  void theFileIsTheBootstrapsShapeWithABasicIdSecretPerHost() throws Exception {
    List<String> hosts =
        DockerClientConfig.writeIfConfigured(
            tmp.resolve("docker"), REPO, Optional.of("dev-qits-system"), Optional.of("s3cr3t"));

    assertEquals(List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080"), hosts);
    Path file = tmp.resolve("docker").resolve("config.json");
    String auth =
        Base64.getEncoder()
            .encodeToString("dev-qits-system:s3cr3t".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "{\"auths\":{\"registry.dev.localhost:8080\":{\"auth\":\""
            + auth
            + "\"},\"mirror.dev.localhost:8080\":{\"auth\":\""
            + auth
            + "\"}}}\n",
        Files.readString(file));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp.resolve("docker"))));
    // Nothing but the file itself: the staged temp file is moved, never left beside it.
    try (var listing = Files.list(tmp.resolve("docker"))) {
      assertEquals(List.of(file), listing.toList());
    }
  }

  @Test
  void aRewriteReplacesTheFileAndKeepsItPrivate() throws Exception {
    Path dir = tmp.resolve("docker");
    DockerClientConfig.writeIfConfigured(dir, REPO, Optional.of("a"), Optional.of("old"));
    DockerClientConfig.writeIfConfigured(dir, REPO, Optional.of("a"), Optional.of("new"));

    Path file = dir.resolve("config.json");
    String content = Files.readString(file);
    assertTrue(
        content.contains(
            Base64.getEncoder().encodeToString("a:new".getBytes(StandardCharsets.UTF_8))));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  @Test
  void nothingIsWrittenUnlessBothHalvesAreSet() throws Exception {
    Path dir = tmp.resolve("docker");
    for (var pair :
        List.of(
            List.of(Optional.<String>empty(), Optional.<String>empty()),
            List.of(Optional.of("dev-qits-system"), Optional.<String>empty()),
            List.of(Optional.<String>empty(), Optional.of("s3cr3t")),
            List.of(Optional.of(" "), Optional.of("s3cr3t")))) {
      assertEquals(
          List.of(), DockerClientConfig.writeIfConfigured(dir, REPO, pair.get(0), pair.get(1)));
    }
    assertFalse(Files.exists(dir));
  }

  @Test
  void dockerConfigFollowsTheFileNotTheConfiguration() throws Exception {
    Path dir = tmp.resolve("docker");
    Map<String, String> env = new HashMap<>(Map.of("DOCKER_CONFIG", "/work/config"));

    assertFalse(DockerClientConfig.applyTo(env, dir));
    assertEquals("/work/config", env.get("DOCKER_CONFIG"), "absent: the inherited value stands");

    DockerClientConfig.writeIfConfigured(dir, REPO, Optional.of("id"), Optional.of("secret"));
    assertTrue(DockerClientConfig.applyTo(env, dir));
    assertEquals(dir.toString(), env.get("DOCKER_CONFIG"));
  }

  @Test
  void theDockerChildIsPointedAtTheDirectoryOnlyOnceTheFileExists() throws Exception {
    // The hop itself: DockerProcess.run must carry the decision into the child's environment.
    Path dir = tmp.resolve("docker");
    List<String> argv = List.of("sh", "-c", "printf %s \"${DOCKER_CONFIG-unset}\"");
    String inherited = Optional.ofNullable(System.getenv("DOCKER_CONFIG")).orElse("unset");

    DockerProcess.Result before = DockerProcess.run(argv, Duration.ofSeconds(10), 1000, dir);
    assertEquals(inherited, before.output());

    DockerClientConfig.writeIfConfigured(dir, REPO, Optional.of("id"), Optional.of("secret"));
    DockerProcess.Result after = DockerProcess.run(argv, Duration.ofSeconds(10), 1000, dir);
    assertEquals(dir.toString(), after.output());
  }
}
