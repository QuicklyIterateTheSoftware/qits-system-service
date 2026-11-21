package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading docker's JSON defensively.
 *
 * <p>EVERY FIELD IS OPTIONAL, and that is not laziness. Docker's CLI columns and API documents both
 * change between releases; a field this service reads may be absent on an older daemon, absent for
 * an object of a different shape (a global service has no {@code Mode.Replicated}), or null. A
 * parser that assumed presence would turn one renamed field into a 500 for a whole panel. So every
 * accessor here answers a default, and a missing field shows as blank in the UI — which is a true
 * statement about what the daemon said.
 *
 * <p>The one thing that is NOT tolerated is a whole document that is not JSON: {@code DockerCli}
 * turns that into a 503, because it means docker did not answer, not that a field moved.
 */
public final class Json {

  private Json() {}

  /** A string field, or null when absent, null, or not textual. */
  public static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  /** A string at a nested path — {@code text(node, "State", "Status")}. */
  public static String textAt(JsonNode node, String... path) {
    JsonNode found = at(node, path);
    return found == null || found.isNull() || found.isContainerNode() ? null : found.asText();
  }

  public static long longAt(JsonNode node, String... path) {
    JsonNode found = at(node, path);
    return found == null || !found.isNumber() ? 0L : found.asLong();
  }

  public static int intAt(JsonNode node, String... path) {
    return (int) longAt(node, path);
  }

  public static boolean boolAt(JsonNode node, String... path) {
    JsonNode found = at(node, path);
    return found != null && found.asBoolean(false);
  }

  /** Walk a path, tolerating every missing step. */
  public static JsonNode at(JsonNode node, String... path) {
    JsonNode current = node;
    for (String step : path) {
      if (current == null) {
        return null;
      }
      current = current.get(step);
    }
    return current;
  }

  /** An object field read as a map of strings — docker's real label and option maps. */
  public static Map<String, String> map(JsonNode node, String... path) {
    JsonNode found = at(node, path);
    Map<String, String> map = new LinkedHashMap<>();
    if (found != null && found.isObject()) {
      found.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText()));
    }
    return map;
  }

  /** An array field read as a list of strings. */
  public static List<String> strings(JsonNode node, String... path) {
    JsonNode found = at(node, path);
    List<String> values = new ArrayList<>();
    if (found != null && found.isArray()) {
      found.forEach(element -> values.add(element.asText()));
    }
    return values;
  }

  /** An array field, or an empty list — for callers that map each element themselves. */
  public static List<JsonNode> nodes(JsonNode node, String... path) {
    JsonNode found = at(node, path);
    List<JsonNode> values = new ArrayList<>();
    if (found != null && found.isArray()) {
      found.forEach(values::add);
    }
    return values;
  }

  /**
   * The KEYS of an {@code Env} array — {@code ["QITS_RESOURCE_DB_PASSWORD=hunter2"]} becomes
   * {@code ["QITS_RESOURCE_DB_PASSWORD"]}.
   *
   * <p><b>Values are dropped on purpose, and this is the one place this service withholds
   * something.</b> A platform service's environment is where its injected database password, its
   * idp client secret and its machine tokens live. Swarm hands those to a container as plain
   * strings, so a detail view that printed the environment verbatim would be a credential dump
   * behind one role check — and this console exists to be opened casually, on a phone, over a
   * shared screen. The KEYS are the diagnostic value ("is QITS_RESOURCE_DB_URL even set?"); the
   * values are not.
   */
  public static List<String> envKeys(JsonNode node, String... path) {
    List<String> keys = new ArrayList<>();
    for (String entry : strings(node, path)) {
      int equals = entry.indexOf('=');
      keys.add(equals < 0 ? entry : entry.substring(0, equals));
    }
    return keys;
  }

  /**
   * The CLI's comma-joined label column, split back into a map.
   *
   * <p><b>Only for objects whose labels the platform itself sets</b> — volumes and networks (a
   * config's and a secret's list rows come from their inspect documents, which carry a real map,
   * qits-2487). It is deliberately NOT used for containers: the join is lossy, because a value may
   * contain a comma, and a container's labels come from arbitrary images. (A real one from this
   * host: {@code maintainer=Red Hat, Inc.} splits into two entries, the second of which is not a
   * label at all.) A container's labels come from {@code inspect}, which returns a real map.
   */
  public static Map<String, String> labelColumn(String joined) {
    Map<String, String> labels = new LinkedHashMap<>();
    if (joined == null || joined.isBlank()) {
      return labels;
    }
    for (String entry : joined.split(",")) {
      int equals = entry.indexOf('=');
      if (equals > 0) {
        labels.put(entry.substring(0, equals).trim(), entry.substring(equals + 1));
      }
    }
    return labels;
  }
}
