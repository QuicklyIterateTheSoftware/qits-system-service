package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One config's list row, printed by {@code config inspect} through {@code SwarmReads.CONFIG_ROW}
 * over the ids {@code config ls} named (qits-2487) — so {@code createdAt} and {@code updatedAt} are
 * docker's RFC 3339 instants as text, never the {@code ls} formatter's age, and {@code labels} is
 * the document's own map. No content: the row template never asks for {@code Spec.Data}, which is
 * {@link ConfigDetail}'s.
 */
public record ConfigSummary(
    String id,
    String name,
    @Schema(format = "date-time") String createdAt,
    @Schema(format = "date-time") String updatedAt,
    Map<String, String> labels) {

  public static ConfigSummary from(JsonNode node) {
    return new ConfigSummary(
        Json.text(node, "ID"),
        Json.text(node, "Name"),
        Json.text(node, "CreatedAt"),
        Json.text(node, "UpdatedAt"),
        Json.map(node, "Labels"));
  }
}
