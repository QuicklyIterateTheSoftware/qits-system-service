package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.system.targets.control.DockerRedaction;
import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.runner.TargetDocker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /system/api/leases/{id}/logs} (epic qits-1621): a lease's own log, which exists only in
 * {@code docker logs} on its target. The lease names its target; its container is the one the
 * runner lists with {@code qits.system.runner.lease=<id>}, and its log is that container's — two
 * docker exchanges, one {@code DOCKER_LOGS} audit row carrying the lease. The roles, the answers and
 * the audit are {@link TargetDockerController}'s.
 */
@Path("/leases/{id}/logs")
@RolesAllowed({
  TargetDockerController.ADMIN,
  TargetDockerController.ADMIN_AGENT,
  TargetDockerController.DESK_ADMIN
})
public class LeaseLogsController {

  /** The label the runner puts on a lease's container. */
  static final String LEASE_LABEL = "qits.system.runner.lease";

  @Inject DockerDoor door;

  @Inject Leases leases;

  @Inject Targets targets;

  @GET
  @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
  @Operation(operationId = "leaseLogs", summary = "A lease's own log: docker logs of its container on its target")
  @APIResponse(responseCode = "200", description = "The log; X-Qits-Truncated says whether it was cut at 1 MiB")
  @APIResponse(responseCode = "404", description = "No such lease, it is on no target, or its container is gone")
  @APIResponse(responseCode = "409", description = "TARGET_UNAVAILABLE: its target is not connected")
  public Response logs(
      @PathParam("id") String id, @QueryParam("tail") Integer tail, @QueryParam("since") String since) {
    String from = since == null || since.isBlank() ? null : since;
    UUID leaseId = uuid(id);
    DockerDoor.Call call = new DockerDoor.Call(null, leaseId, DockerOp.LOGS, null, tail, from, null, null);
    Lease lease = leaseId == null ? null : leases.find(leaseId);
    if (lease == null) {
      String message = "No lease " + id;
      door.record(call.naming(leaseId == null ? id : null), DockerDoor.NOT_FOUND, message);
      throw TargetRefusal.notFound(message);
    }
    Target target = lease.targetId == null ? null : targets.find(lease.targetId);
    if (target == null) {
      String message = "Lease " + id + " is on no target (" + lease.state + ")";
      door.record(call, DockerDoor.NOT_FOUND, message);
      throw TargetRefusal.notFound(message);
    }
    DockerDoor.Call listing = DockerDoor.Call.of(DockerOp.LIST_CONTAINERS, null).on(target.id);
    TargetDocker.Outcome listed = door.exchange(target, listing);
    DockerDoor.Call onTarget = call.on(target.id);
    if (!(listed instanceof TargetDocker.Answered answered)
        || answered.result().status() != DockerStatus.OK) {
      door.settle(target, onTarget, listed);
      throw new IllegalStateException("a listing that is not OK always refuses");
    }
    String container = containerOf(answered.result().body(), leaseId.toString());
    if (container == null) {
      String message = "Lease " + id + " has no container on target " + target.name;
      door.record(onTarget, DockerDoor.NOT_FOUND, message);
      throw TargetRefusal.notFound(message);
    }
    DockerDoor.Call logs = onTarget.naming(container);
    DockerResult result = door.settle(target, logs, door.exchange(target, logs));
    return TargetDockerController.logResponse(result);
  }

  /**
   * The name of the first listed container labelled {@code qits.system.runner.lease=<leaseId>}, or
   * null. {@code docker ps --format '{{json .}}'} spells {@code Labels} as one {@code k=v,k=v}
   * string and {@code Names} as a comma-joined list; an object of labels is read too.
   */
  static String containerOf(String listing, String leaseId) {
    JsonNode rows;
    try {
      rows = DockerRedaction.listed(listing);
    } catch (IllegalArgumentException unreadable) {
      throw new TargetRefusal(
          502, TargetRefusal.DOCKER_FAILED, "The runner's listing was not JSON: " + unreadable.getMessage());
    }
    for (JsonNode row : rows) {
      if (labelled(row.get("Labels"), leaseId)) {
        JsonNode names = row.get("Names");
        String name = names == null || names.isNull() ? null : names.asText().split(",")[0].strip();
        if (name != null && !name.isEmpty()) {
          return name.startsWith("/") ? name.substring(1) : name;
        }
      }
    }
    return null;
  }

  private static boolean labelled(JsonNode labels, String leaseId) {
    if (labels == null || labels.isNull()) {
      return false;
    }
    if (labels.isObject()) {
      JsonNode value = labels.get(LEASE_LABEL);
      return value != null && leaseId.equals(value.asText());
    }
    for (String pair : labels.asText().split(",")) {
      if (pair.strip().equals(LEASE_LABEL + "=" + leaseId)) {
        return true;
      }
    }
    return false;
  }

  private static UUID uuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      return null;
    }
  }
}
