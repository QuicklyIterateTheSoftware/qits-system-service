package eu.wohlben.qits.system.reads;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One row of `docker secret ls` — METADATA, and there is no detail route beside it.
 *
 * <p>Not a policy this service enforces: swarm does not return a secret's value to anyone, ever,
 * including a manager. What an operator can learn is that the secret exists, when it was made and
 * what it is labelled — which is enough to answer "is the thing this service is missing even
 * there".
 *
 * <p><b>{@code createdAt} and {@code updatedAt} are docker's text, not timestamps.</b> The {@code
 * secret ls} formatter humanises them ({@code "2 months ago"}); absolute instants would take {@code
 * secret inspect}, which this service never runs.
 *
 * <p><b>{@code usedBy} is read from the SERVICE SPECS, never from the secret</b> (qits-2350): the
 * names of the services whose {@code Spec.TaskTemplate.ContainerSpec.Secrets} reference this
 * secret, sorted, {@code []} when none does — which is how an orphaned secret shows. A reference
 * matches by {@code SecretID}; one that carries no id matches by {@code SecretName}. A secret holds
 * no list of its users, so nothing about it is asked beyond {@code secret ls}.
 */
public record SecretSummary(
    String id,
    String name,
    String driver,
    String createdAt,
    String updatedAt,
    Map<String, String> labels,
    List<String> usedBy) {

  /** A row as {@code secret ls} printed it, used by no service. */
  public static SecretSummary from(JsonNode node) {
    return from(node, List.of());
  }

  /**
   * A row as {@code secret ls} printed it, with the services that use it read out of {@code
   * services}: one object per service, {@code {"name": …, "secrets": [{"SecretID": …,
   * "SecretName": …}, …] | null}} — the usage template's output, and nothing else of a spec.
   */
  public static SecretSummary from(JsonNode node, List<JsonNode> services) {
    String id = Json.text(node, "ID");
    String name = Json.text(node, "Name");
    return new SecretSummary(
        id,
        name,
        Json.text(node, "Driver"),
        Json.text(node, "CreatedAt"),
        Json.text(node, "UpdatedAt"),
        Json.labelColumn(Json.text(node, "Labels")),
        usedBy(id, name, services));
  }

  /** The sorted, distinct names of the services referencing the secret {@code id}/{@code name}. */
  static List<String> usedBy(String id, String name, List<JsonNode> services) {
    TreeSet<String> users = new TreeSet<>();
    for (JsonNode service : services) {
      String serviceName = Json.text(service, "name");
      if (serviceName == null) {
        continue;
      }
      for (JsonNode reference : Json.nodes(service, "secrets")) {
        if (references(reference, id, name)) {
          users.add(serviceName);
          break;
        }
      }
    }
    return List.copyOf(users);
  }

  /** By id where the reference carries one — a recreated secret of the same name is another one. */
  private static boolean references(JsonNode reference, String id, String name) {
    String secretId = Json.text(reference, "SecretID");
    if (secretId != null && !secretId.isEmpty()) {
      return secretId.equals(id);
    }
    String secretName = Json.text(reference, "SecretName");
    return secretName != null && secretName.equals(name);
  }
}
