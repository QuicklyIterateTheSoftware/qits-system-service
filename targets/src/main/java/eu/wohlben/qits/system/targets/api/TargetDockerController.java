package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.system.targets.control.DockerRedaction;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.TargetAuditLog;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /system/api/targets/{id}/docker} (epic qits-1621, "Docker over the runner socket"): an
 * operator reads and handles the target's own containers and volumes without a shell on it. Each
 * door is one {@code docker} frame to the target's runner and its one {@code docker-result}, waited
 * for synchronously ({@link DockerDoor}); the runner answers only for resources it owns. {@code
 * {id}} is the target's UUID or its name.
 *
 * <p><b>Every call is a {@code target_audit} row</b>, written before the answer, refusals and
 * timeouts included; {@code GET .../audit} reads them back. An inspect's environment and label
 * values whose key names a secret are {@code ***} ({@link DockerRedaction}); a log and an exec's
 * output were redacted by the runner.
 *
 * <p>Answers: 404 for no such target or resource, 403 {@code DOCKER_REFUSED}, 409 {@code
 * TARGET_UNAVAILABLE} ("target &lt;name&gt; is not connected"), 502 {@code DOCKER_FAILED}, 504 {@code
 * DOCKER_TIMEOUT} after 30 s (an exec: its {@code timeoutSeconds} plus 10 s).
 */
@Path("/targets/{id}/docker")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  TargetDockerController.ADMIN,
  TargetDockerController.ADMIN_AGENT,
  TargetDockerController.DESK_ADMIN
})
@RegisterForReflection(
    targets = {
      TargetDockerController.ExecRequest.class,
      TargetDockerController.ExecResult.class,
      TargetDockerController.DockerDone.class,
      TargetDockerController.AuditRow.class
    })
public class TargetDockerController {

  static final String ADMIN = "qits:admin";
  static final String ADMIN_AGENT = "qits:admin-agent";

  /** The delivery desk's administrator (epic qits-1621): an operator here like {@link #ADMIN_AGENT}. */
  static final String DESK_ADMIN = "qits:desk-admin";

  /** The header a log answer says whether the runner cut it in. */
  public static final String TRUNCATED_HEADER = "X-Qits-Truncated";

  @Inject DockerDoor door;

  @Inject TargetAuditLog audit;

  @Schema(name = "DockerExecRequest")
  public record ExecRequest(
      @Schema(description = "The command and its arguments; no shell is put in front", required = true)
          List<String> argv,
      @Schema(description = "How long it may run; default 30, at most 120") Integer timeoutSeconds) {}

  @Schema(name = "DockerExecResult")
  public record ExecResult(int exitCode, String stdout, String stderr, boolean truncated) {}

  @Schema(name = "DockerDone")
  public record DockerDone(String name, String output) {}

  @Schema(name = "TargetAuditRow")
  public record AuditRow(
      UUID id,
      Instant at,
      String actor,
      String roles,
      UUID targetId,
      UUID leaseId,
      String operation,
      String resource,
      JsonNode args,
      String outcome,
      String detail) {

    static AuditRow of(TargetAudit row) {
      return new AuditRow(
          row.id,
          row.at,
          row.actor,
          row.roles,
          row.targetId,
          row.leaseId,
          row.operation,
          row.resource,
          LeaseSpecs.parse(row.args),
          row.outcome,
          row.detail);
    }
  }

  // --- containers -------------------------------------------------------------------------------

  @GET
  @Path("/containers")
  @Operation(operationId = "listTargetContainers", summary = "The target runner's own containers (docker ps -a)")
  @APIResponse(responseCode = "200", description = "docker ps rows, labels redacted")
  public JsonNode containers(@PathParam("id") String id) {
    return listing(id, DockerOp.LIST_CONTAINERS);
  }

  @GET
  @Path("/containers/{name}")
  @Operation(operationId = "inspectTargetContainer", summary = "docker inspect of one of the runner's containers, secrets redacted")
  @APIResponse(responseCode = "200", description = "The inspected container")
  @APIResponse(responseCode = "404", description = "No such target, or no such container of its runner")
  public JsonNode inspectContainer(@PathParam("id") String id, @PathParam("name") String name) {
    return inspected(id, DockerOp.INSPECT_CONTAINER, name);
  }

  @GET
  @Path("/containers/{name}/logs")
  @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
  @Operation(operationId = "targetContainerLogs", summary = "docker logs of one of the runner's containers")
  @APIResponse(responseCode = "200", description = "The log; X-Qits-Truncated says whether it was cut at 1 MiB")
  public Response logs(
      @PathParam("id") String id,
      @PathParam("name") String name,
      @QueryParam("tail") Integer tail,
      @QueryParam("since") String since) {
    DockerDoor.Call call =
        new DockerDoor.Call(null, null, DockerOp.LOGS, name, tail, blankToNull(since), null, null);
    Target target = door.target(id, call);
    return logResponse(door.run(target, call));
  }

  @POST
  @Path("/containers/{name}/exec")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "execInTargetContainer", summary = "docker exec in one of the runner's containers: no TTY, no stdin")
  @APIResponse(responseCode = "200", description = "It ran, whatever its exit code")
  @APIResponse(responseCode = "400", description = "No argv")
  public ExecResult exec(
      @PathParam("id") String id, @PathParam("name") String name, ExecRequest request) {
    List<String> argv = request == null ? null : request.argv();
    Integer timeout = request == null ? null : request.timeoutSeconds();
    DockerDoor.Call call =
        new DockerDoor.Call(null, null, DockerOp.EXEC, name, null, null, argv, timeout);
    Target target = door.target(id, call);
    if (argv == null || argv.isEmpty() || argv.stream().anyMatch(arg -> arg == null)) {
      throw door.badRequest(call.on(target.id), "An exec needs an argv of at least one string");
    }
    if (timeout != null && timeout <= 0) {
      throw door.badRequest(call.on(target.id), "timeoutSeconds is positive");
    }
    DockerResult result = door.run(target, call);
    return new ExecResult(
        result.exitCode() == null ? -1 : result.exitCode(),
        result.body(),
        result.stderr() == null ? "" : result.stderr(),
        result.truncated());
  }

  @POST
  @Path("/containers/{name}/stop")
  @Operation(operationId = "stopTargetContainer", summary = "docker stop -t 10 of one of the runner's containers")
  @APIResponse(responseCode = "200", description = "Stopped")
  @APIResponse(responseCode = "403", description = "DOCKER_REFUSED: the runner's own container")
  public DockerDone stop(@PathParam("id") String id, @PathParam("name") String name) {
    return done(id, DockerOp.STOP, name);
  }

  @DELETE
  @Path("/containers/{name}")
  @Operation(operationId = "removeTargetContainer", summary = "docker rm -f of one of the runner's containers")
  @APIResponse(responseCode = "200", description = "Removed")
  @APIResponse(responseCode = "403", description = "DOCKER_REFUSED: the runner's own container")
  public DockerDone remove(@PathParam("id") String id, @PathParam("name") String name) {
    return done(id, DockerOp.REMOVE, name);
  }

  // --- volumes ----------------------------------------------------------------------------------

  @GET
  @Path("/volumes")
  @Operation(operationId = "listTargetVolumes", summary = "The target runner's own volumes (docker volume ls)")
  @APIResponse(responseCode = "200", description = "docker volume ls rows, labels redacted")
  public JsonNode volumes(@PathParam("id") String id) {
    return listing(id, DockerOp.LIST_VOLUMES);
  }

  @GET
  @Path("/volumes/{name}")
  @Operation(operationId = "inspectTargetVolume", summary = "docker volume inspect of one of the runner's volumes, with its size")
  @APIResponse(responseCode = "200", description = "The inspected volume, secret labels redacted")
  @APIResponse(responseCode = "404", description = "No such target, or no such volume of its runner")
  public JsonNode inspectVolume(@PathParam("id") String id, @PathParam("name") String name) {
    return inspected(id, DockerOp.INSPECT_VOLUME, name);
  }

  // --- the audit --------------------------------------------------------------------------------

  @GET
  @Path("/audit")
  @Operation(operationId = "targetAudit", summary = "The target's audit rows, newest first")
  @APIResponse(responseCode = "200", description = "At most limit rows (default 100, at most 500)")
  @APIResponse(responseCode = "404", description = "No such target")
  public List<AuditRow> audit(
      @PathParam("id") String id, @QueryParam("limit") @DefaultValue("100") int limit) {
    Target target = door.resolve(id);
    return audit.ofTarget(target.id, limit).stream().map(AuditRow::of).toList();
  }

  // --- the pieces -------------------------------------------------------------------------------

  /** A log answer: text/plain, with whether the runner cut it. */
  static Response logResponse(DockerResult result) {
    return Response.ok(result.body(), MediaType.TEXT_PLAIN_TYPE)
        .header(TRUNCATED_HEADER, Boolean.toString(result.truncated()))
        .build();
  }

  private JsonNode listing(String id, DockerOp op) {
    DockerDoor.Call call = DockerDoor.Call.of(op, null);
    Target target = door.target(id, call);
    return readable(door.run(target, call), DockerRedaction::listed);
  }

  private JsonNode inspected(String id, DockerOp op, String name) {
    DockerDoor.Call call = DockerDoor.Call.of(op, name);
    Target target = door.target(id, call);
    return readable(door.run(target, call), DockerRedaction::inspected);
  }

  private DockerDone done(String id, DockerOp op, String name) {
    DockerDoor.Call call = DockerDoor.Call.of(op, name);
    Target target = door.target(id, call);
    return new DockerDone(name, door.run(target, call).body().strip());
  }

  /** An OK answer that is not the JSON it should be is docker's failure, never a pass-through. */
  private static JsonNode readable(DockerResult result, Function<String, JsonNode> redacted) {
    try {
      return redacted.apply(result.body());
    } catch (IllegalArgumentException unreadable) {
      throw new TargetRefusal(
          502, TargetRefusal.DOCKER_FAILED, "The runner's answer was not JSON: " + unreadable.getMessage());
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
