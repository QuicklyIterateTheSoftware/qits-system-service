package eu.wohlben.qits.system.targets.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Docker API's own redaction (epic qits-1621, "Docker over the runner socket"): what an
 * inspect or a listing carries of a container's environment and labels, with every value whose KEY
 * names a secret replaced by {@link #MASK}. The runner already redacts a log and an exec's output by
 * the values it finds; this is the other half, the place the values themselves are written.
 *
 * <ul>
 *   <li>a container: {@code Config.Env} entries {@code KEY=VALUE} become {@code KEY=***}, and
 *       {@code Config.Labels} values are masked by their key;
 *   <li>a volume: its {@code Labels} values;
 *   <li>a listing row ({@code docker ps}/{@code volume ls --format '{{json .}}'}): its {@code
 *       Labels}, which docker spells as one {@code k=v,k=v} string.
 * </ul>
 *
 * Pure: no state, no I/O.
 */
public final class DockerRedaction {

  /** A key whose value is never answered. */
  public static final Pattern SECRET_KEY =
      Pattern.compile("(?i)(TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|API_?KEY|PRIVATE_KEY|AUTH)");

  public static final String MASK = "***";

  private static final ObjectMapper JSON = new ObjectMapper();

  private DockerRedaction() {}

  /** Whether {@code key}'s value is a secret. */
  public static boolean secretKey(String key) {
    return key != null && SECRET_KEY.matcher(key).find();
  }

  /**
   * An inspect's JSON (an object, or docker's one-element array), redacted.
   *
   * @throws IllegalArgumentException when it is not JSON
   */
  public static JsonNode inspected(String json) {
    JsonNode node = parse(json);
    for (ObjectNode object : objects(node)) {
      JsonNode config = object.get("Config");
      if (config instanceof ObjectNode container) {
        redactEnv(container);
        redactLabels(container, "Labels");
      }
      redactLabels(object, "Labels");
    }
    return node;
  }

  /**
   * A listing's JSON array, each row's labels redacted.
   *
   * @throws IllegalArgumentException when it is not JSON
   */
  public static JsonNode listed(String json) {
    JsonNode node = parse(json);
    for (ObjectNode row : objects(node)) {
      redactLabels(row, "Labels");
    }
    return node;
  }

  /** {@code KEY=VALUE} with a secret KEY as {@code KEY=***}; anything else unchanged. */
  static String envEntry(String entry) {
    int eq = entry == null ? -1 : entry.indexOf('=');
    if (eq < 0 || !secretKey(entry.substring(0, eq))) {
      return entry;
    }
    return entry.substring(0, eq + 1) + MASK;
  }

  /** Docker's {@code k=v,k=v} label string, each secret key's value masked. */
  static String labelString(String labels) {
    if (labels == null || labels.isEmpty()) {
      return labels;
    }
    List<String> out = new ArrayList<>();
    for (String pair : labels.split(",", -1)) {
      out.add(envEntry(pair));
    }
    return String.join(",", out);
  }

  private static void redactEnv(ObjectNode config) {
    if (config.get("Env") instanceof ArrayNode env) {
      for (int i = 0; i < env.size(); i++) {
        if (env.get(i).isTextual()) {
          env.set(i, TextNode.valueOf(envEntry(env.get(i).asText())));
        }
      }
    }
  }

  private static void redactLabels(ObjectNode parent, String field) {
    JsonNode labels = parent.get(field);
    if (labels instanceof ObjectNode map) {
      List<String> secret = new ArrayList<>();
      for (Map.Entry<String, JsonNode> label : map.properties()) {
        if (secretKey(label.getKey())) {
          secret.add(label.getKey());
        }
      }
      secret.forEach(key -> map.put(key, MASK));
    } else if (labels != null && labels.isTextual()) {
      parent.put(field, labelString(labels.asText()));
    }
  }

  private static List<ObjectNode> objects(JsonNode node) {
    List<ObjectNode> objects = new ArrayList<>();
    if (node instanceof ObjectNode object) {
      objects.add(object);
    } else if (node instanceof ArrayNode array) {
      array.forEach(
          element -> {
            if (element instanceof ObjectNode object) {
              objects.add(object);
            }
          });
    }
    return objects;
  }

  private static JsonNode parse(String json) {
    JsonNode node;
    try {
      node = JSON.readTree(json == null ? "" : json);
    } catch (Exception e) {
      throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
    }
    if (node == null || node.isMissingNode()) {
      throw new IllegalArgumentException("not JSON: empty");
    }
    return node;
  }
}
