package eu.wohlben.qits.system.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The docker CLI's own {@code config.json}, written by this service from its OWN idp client
 * (qits-879) rather than read off a volume the bootstrap seeded.
 *
 * <p><b>WHERE THE CREDENTIAL COMES FROM.</b> qits-deployments provisions this application an idp
 * client ({@code idp:client} in {@code .config/qits/deployments.yml}) and injects its id and secret
 * as {@code QITS_RESOURCE_IDP_CLIENT_ID} / {@code _SECRET}. The pull is a Basic {@code id:secret} at
 * the edge, the same bytes {@code docker login} would store — the format is the bootstrap's {@code
 * SeedPhases.dockerConfigJson}, one {@code auths} entry per host.
 *
 * <p><b>THE HOSTS ARE DERIVED, NOT CONFIGURED.</b> The mirror host is the host part of {@code
 * qits.system.glances.image-repo} — the one image this service pulls. The registry is its sibling
 * on the same machine-vhost grammar the bootstrap names both by ({@code
 * mirror.<env>.localhost:<port>} and {@code registry.<env>.localhost:<port>}, {@code
 * BootstrapConfig.mirrorVhost/registryVhost}): the first label {@code mirror} becomes {@code
 * registry}. A repo whose host is not a {@code mirror.} name carries just that host, and a repo
 * with no host (docker.io implied) carries none.
 *
 * <p><b>THE FILE DECIDES {@code DOCKER_CONFIG}, NOT THE CONFIGURATION.</b> {@link #applyTo} points a
 * docker child at the directory only when {@code config.json} is actually there. Otherwise the
 * environment is left alone, so the {@code DOCKER_CONFIG=/work/config} an older deployment spec
 * sets — and its mounted, bootstrap-written file — keeps working for that spec and a rollback.
 *
 * <p>Pure functions plus one write; no CDI, so the suite asserts it with no application.
 */
public final class DockerClientConfig {

  /** The file the CLI reads under {@code $DOCKER_CONFIG}. */
  public static final String FILE = "config.json";

  /** The variable the CLI reads its config directory from. */
  public static final String ENV = "DOCKER_CONFIG";

  private static final String MIRROR_LABEL = "mirror.";
  private static final String REGISTRY_LABEL = "registry.";

  private static final Set<PosixFilePermission> DIR_MODE =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");

  private DockerClientConfig() {}

  /**
   * The hosts a credential is written for, registry first (the bootstrap's order), deduplicated.
   *
   * @param imageRepo an image reference without its tag, e.g. {@code
   *     mirror.dev.localhost:8080/hub/nicolargo/glances}
   */
  public static List<String> hosts(String imageRepo) {
    Optional<String> mirror = hostOf(imageRepo);
    if (mirror.isEmpty()) {
      return List.of();
    }
    Set<String> hosts = new LinkedHashSet<>();
    String host = mirror.get();
    if (host.startsWith(MIRROR_LABEL) && host.length() > MIRROR_LABEL.length()) {
      hosts.add(REGISTRY_LABEL + host.substring(MIRROR_LABEL.length()));
    }
    hosts.add(host);
    return new ArrayList<>(hosts);
  }

  /**
   * The registry host of an image reference, by docker's own rule: the first path component is a
   * host only when it contains a {@code .} or a {@code :}, or is {@code localhost}. Otherwise the
   * reference is docker.io's and names no host of ours.
   */
  static Optional<String> hostOf(String imageRepo) {
    if (imageRepo == null) {
      return Optional.empty();
    }
    String trimmed = imageRepo.trim();
    int slash = trimmed.indexOf('/');
    if (slash <= 0) {
      return Optional.empty();
    }
    String first = trimmed.substring(0, slash);
    if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
      return Optional.of(first);
    }
    return Optional.empty();
  }

  /**
   * The file's content: {@code {"auths":{"<host>":{"auth":"<base64 id:secret>"},…}}}. Hand-written
   * like the bootstrap's — base64 has no character JSON escapes, and the hosts are escaped anyway.
   */
  public static String json(List<String> hosts, String clientId, String secret) {
    String auth =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    return hosts.stream()
        .map(
            host ->
                "\""
                    + host.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\":{\"auth\":\""
                    + auth
                    + "\"}")
        .collect(Collectors.joining(",", "{\"auths\":{", "}}\n"));
  }

  /**
   * Write {@code <dir>/config.json} when there is something to write: both halves of the
   * credential set and at least one host. The directory is 0700 and the file 0600, and the file is
   * replaced atomically, so a docker child never reads half of one.
   *
   * @return the hosts written for; empty when nothing was written
   */
  public static List<String> writeIfConfigured(
      Path dir, String imageRepo, Optional<String> clientId, Optional<String> secret)
      throws IOException {
    String id = clientId.map(String::trim).orElse("");
    String pass = secret.map(String::trim).orElse("");
    if (id.isEmpty() || pass.isEmpty()) {
      return List.of();
    }
    List<String> hosts = hosts(imageRepo);
    if (hosts.isEmpty()) {
      return List.of();
    }
    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
    Files.setPosixFilePermissions(dir, DIR_MODE);
    Path staged =
        Files.createTempFile(dir, FILE, ".tmp", PosixFilePermissions.asFileAttribute(FILE_MODE));
    try {
      Files.writeString(staged, json(hosts, id, pass), StandardCharsets.UTF_8);
      Path target = dir.resolve(FILE);
      Files.move(
          staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Files.setPosixFilePermissions(target, FILE_MODE);
    } finally {
      Files.deleteIfExists(staged);
    }
    return hosts;
  }

  /**
   * Point a docker child at {@code dir} — but only when {@code dir/config.json} exists. Absent, the
   * environment is untouched and whatever this process inherited (the mounted {@code /work/config}
   * of an older spec) still applies.
   *
   * @return whether {@code DOCKER_CONFIG} was set
   */
  public static boolean applyTo(Map<String, String> environment, Path dir) {
    if (dir == null || !Files.isRegularFile(dir.resolve(FILE))) {
      return false;
    }
    environment.put(ENV, dir.toString());
    return true;
  }
}
