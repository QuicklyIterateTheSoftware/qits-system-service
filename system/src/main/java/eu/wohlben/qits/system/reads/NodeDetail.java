package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * One node, from `docker node inspect` — the daemon's own document rather than the CLI's columns,
 * which is where the resources, the labels, the address and the manager's reachability live.
 *
 * <p>It carries every component {@link NodeSummary} does, so a client that opened a row from a list
 * does not have to hold two shapes for one thing.
 *
 * <p>The document spells its states in lowercase ("ready", "active") where the list's columns are
 * capitalised ("Ready", "Active"); {@code status}, {@code availability} and {@code managerStatus}
 * are rebuilt in the LIST's vocabulary, so both shapes agree and one tone mapping reads either.
 *
 * @param status the LIST's vocabulary — "Ready", "Down", … — from the document's `Status.State`
 * @param availability the LIST's vocabulary — "Active", "Pause", "Drain" — from `Spec.Availability`
 * @param cpus derived from `NanoCPUs`, because a node reports its CPU as a nanocore count
 * @param managerStatus the LIST's vocabulary — "Leader", "Reachable", or absent for a worker —
 *     rebuilt from the document's own leader flag and reachability
 * @param self whether this is the node qits-system runs on: the document does not say, so the
 *     caller compares the id with the local daemon's own, as {@link NodeSummary}'s `Self` column
 *     does for the list
 */
public record NodeDetail(
    String id,
    String hostname,
    String role,
    String status,
    String availability,
    String managerStatus,
    String engineVersion,
    String address,
    String os,
    String architecture,
    double cpus,
    long memoryBytes,
    Map<String, String> labels,
    String createdAt,
    String updatedAt,
    boolean self) {

  private static final double NANO = 1_000_000_000d;

  public static NodeDetail from(JsonNode node, boolean self) {
    JsonNode manager = Json.at(node, "ManagerStatus");
    String managerStatus = null;
    if (manager != null) {
      managerStatus = Json.boolAt(manager, "Leader") ? "Leader" : capitalise(Json.text(manager, "Reachability"));
    }
    return new NodeDetail(
        Json.text(node, "ID"),
        Json.textAt(node, "Description", "Hostname"),
        Json.textAt(node, "Spec", "Role"),
        capitalise(Json.textAt(node, "Status", "State")),
        capitalise(Json.textAt(node, "Spec", "Availability")),
        managerStatus,
        Json.textAt(node, "Description", "Engine", "EngineVersion"),
        Json.textAt(node, "Status", "Addr"),
        Json.textAt(node, "Description", "Platform", "OS"),
        Json.textAt(node, "Description", "Platform", "Architecture"),
        Json.longAt(node, "Description", "Resources", "NanoCPUs") / NANO,
        Json.longAt(node, "Description", "Resources", "MemoryBytes"),
        Json.map(node, "Spec", "Labels"),
        Json.text(node, "CreatedAt"),
        Json.text(node, "UpdatedAt"),
        self);
  }

  private static String capitalise(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }
}
