package eu.wohlben.qits.system.targets.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.system.targets.entity.SlotType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A lease's {@code spec}: checked, made canonical and hashed. Pure.
 *
 * <p>{@code LeaseSpec{image, env, user, limits}} is the owner's; mounts, network, capabilities,
 * init and restart are the slot type's policy, fixed in the runner, and never read from it. This
 * service checks the one thing it is responsible for — {@code image} starts with the type's
 * {@code qits.system.targets.slot-images.<type>} prefix, or 400 {@link
 * TargetRefusal#IMAGE_NOT_ALLOWED} — and relays the rest. The runner refuses the same images, and
 * a spec env key that collides with a policy-owned key, itself.
 *
 * <p>{@link #hash} is sha256 of the canonical form (object keys sorted, recursively), so the same
 * spec sent twice is the same hash, and the runner recreates a container exactly when it changed.
 */
public final class LeaseSpecs {

  /** The widest spec this service keeps. */
  public static final int MAX_BYTES = 64 * 1024;

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The env keys a slot type's policy owns in the runner — {@code BUILDKIT_HOST} for CI, {@code
   * HOME} and {@code CLAUDE_CONFIG_DIR} for the agent slots. The runner fails a lease that carries
   * one; this service refuses it at the door rather than granting a slot to a lease that will fail.
   */
  public static final java.util.Set<String> POLICY_ENV =
      java.util.Set.of("BUILDKIT_HOST", "HOME", "CLAUDE_CONFIG_DIR");

  private LeaseSpecs() {}

  /**
   * The spec as this service stores it, checked against {@code type}'s image prefix.
   *
   * @throws TargetRefusal 400 when it is not an object, has no image, or names one not allowed
   */
  public static ObjectNode require(JsonNode spec, SlotType type, Map<String, String> slotImages) {
    if (spec == null || !spec.isObject()) {
      throw TargetRefusal.badRequest("A lease needs a spec, a JSON object with an image");
    }
    JsonNode image = spec.get("image");
    if (image == null || !image.isTextual() || image.asText().isBlank()) {
      throw TargetRefusal.badRequest("A lease's spec needs an image");
    }
    String prefix = slotImages.get(type.key());
    if (prefix == null || prefix.isBlank() || !allowed(image.asText(), prefix)) {
      throw TargetRefusal.badRequest(
          TargetRefusal.IMAGE_NOT_ALLOWED,
          "A " + type.name() + " slot runs only " + prefix + " images, not " + image.asText());
    }
    requireShape(spec);
    ObjectNode canonical = (ObjectNode) canonical(spec);
    if (text(canonical).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw TargetRefusal.badRequest("A lease's spec is larger than " + MAX_BYTES + " bytes");
    }
    return canonical;
  }

  /**
   * The rest of {@code LeaseSpec{image, env, user, limits}}: {@code env} an object of scalar values
   * and no policy-owned key, {@code user} a scalar that is not root, {@code limits} an object.
   */
  static void requireShape(JsonNode spec) {
    JsonNode env = spec.get("env");
    if (env != null && !env.isNull()) {
      if (!env.isObject()) {
        throw TargetRefusal.badRequest("A lease's spec.env is an object of strings");
      }
      var names = env.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (POLICY_ENV.contains(name)) {
          throw TargetRefusal.badRequest(
              TargetRefusal.ENV_KEY_NOT_ALLOWED,
              "spec.env." + name + " is set by the slot's policy, not by a lease");
        }
        if (!env.get(name).isValueNode() || env.get(name).isNull()) {
          throw TargetRefusal.badRequest("spec.env." + name + " is not a string");
        }
      }
    }
    JsonNode user = spec.get("user");
    if (user != null && !user.isNull()) {
      if (!user.isValueNode()) {
        throw TargetRefusal.badRequest("A lease's spec.user is a uid or a uid:gid");
      }
      String text = user.asText().trim();
      if (text.equals("0") || text.startsWith("0:") || text.equals("root")
          || text.startsWith("root:")) {
        throw TargetRefusal.badRequest("A slot never runs as root (spec.user " + text + ")");
      }
    }
    JsonNode limits = spec.get("limits");
    if (limits != null && !limits.isNull() && !limits.isObject()) {
      throw TargetRefusal.badRequest("A lease's spec.limits is an object");
    }
  }

  /**
   * Whether {@code image} is the prefix's repository: the prefix itself followed by a tag, a digest
   * or a deeper path — never a sibling repository that merely shares its first letters.
   */
  static boolean allowed(String image, String prefix) {
    String repository = prefix.replaceAll("/+$", "");
    if (!image.startsWith(repository)) {
      return false;
    }
    if (image.length() == repository.length()) {
      return true;
    }
    char next = image.charAt(repository.length());
    return next == ':' || next == '@' || next == '/';
  }

  /** The canonical form: every object's keys sorted, recursively. */
  public static JsonNode canonical(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isObject()) {
      ObjectNode sorted = JsonNodeFactory.instance.objectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(null);
      for (String name : names) {
        sorted.set(name, canonical(node.get(name)));
      }
      return sorted;
    }
    if (node.isArray()) {
      var array = JsonNodeFactory.instance.arrayNode();
      node.forEach(item -> array.add(canonical(item)));
      return array;
    }
    return node;
  }

  /** sha256 of the canonical text, hex. */
  public static String hash(JsonNode spec) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(text(canonical(spec)).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String text(JsonNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public static JsonNode parse(String text) {
    if (text == null) {
      return null;
    }
    try {
      return JSON.readTree(text);
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
