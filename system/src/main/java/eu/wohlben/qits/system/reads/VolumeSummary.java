package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/**
 * One row of `docker volume ls`.
 *
 * <p>`createdAt` is always null. It is kept for the wire shape the old frontend reads, and the list
 * never fills it: the list columns do not carry it, and a `volume inspect` per row would be one
 * docker fork per volume for a column nobody sorts on.
 *
 * <p>`unused` is true when docker lists the volume as dangling (`docker volume ls -q --filter
 * dangling=true`): no container, running or stopped, references it. It does NOT mean safe to
 * delete — a swarm service scaled to 0 or a runner between leases still owns its volume; the
 * labels say whose it is.
 */
public record VolumeSummary(
    String name,
    String driver,
    String scope,
    String mountpoint,
    String createdAt,
    Map<String, String> labels,
    boolean unused) {

  public static VolumeSummary from(JsonNode node, Set<String> danglingNames) {
    String name = Json.text(node, "Name");
    return new VolumeSummary(
        name,
        Json.text(node, "Driver"),
        Json.text(node, "Scope"),
        Json.text(node, "Mountpoint"),
        null,
        Json.labelColumn(Json.text(node, "Labels")),
        name != null && danglingNames.contains(name));
  }
}
