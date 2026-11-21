package eu.wohlben.qits.system.api;

import eu.wohlben.qits.system.reads.ConfigDetail;
import eu.wohlben.qits.system.reads.ConfigSummary;
import eu.wohlben.qits.system.reads.NodeDetail;
import eu.wohlben.qits.system.reads.NodeSummary;
import eu.wohlben.qits.system.reads.SecretSummary;
import eu.wohlben.qits.system.reads.ServiceDetail;
import eu.wohlben.qits.system.reads.ServiceSummary;
import eu.wohlben.qits.system.reads.SwarmReads;
import eu.wohlben.qits.system.reads.TaskSummary;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The cluster-wide reads. These are answered by the swarm MANAGER this service runs on, so they
 * cover every node — unlike {@link NodeController}, which can only see this machine.
 *
 * <p>There is no secret DETAIL route, and that is not an omission: swarm returns no secret value to
 * anybody, so the only thing a detail could add is the same metadata the list already carries.
 */
@Path("/swarm")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
public class SwarmController {

  @Inject SwarmReads swarm;

  @GET
  @Path("/nodes")
  @Operation(operationId = "listSwarmNodes", summary = "Nodes")
  public List<NodeSummary> nodes() {
    return swarm.nodes();
  }

  @GET
  @Path("/nodes/{id}")
  @Operation(operationId = "getSwarmNode", summary = "Node")
  @APIResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = NodeDetail.class)))
  @APIResponse(responseCode = "404", description = "No such node")
  public NodeDetail node(@PathParam("id") String id) {
    return swarm.node(id);
  }

  /**
   * What swarm runs on one node — the one per-node question the manager answers for EVERY node,
   * unlike {@link NodeController}'s reads, which only reach this one.
   */
  @GET
  @Path("/nodes/{id}/tasks")
  @Operation(operationId = "listSwarmNodeTasks", summary = "Node tasks")
  @APIResponse(responseCode = "200", description = "OK")
  @APIResponse(responseCode = "404", description = "No such node")
  public List<TaskSummary> nodeTasks(@PathParam("id") String id) {
    return swarm.nodeTasks(id);
  }

  @GET
  @Path("/services")
  @Operation(operationId = "listSwarmServices", summary = "Services")
  public List<ServiceSummary> services() {
    return swarm.services();
  }

  @GET
  @Path("/services/{id}")
  @Operation(operationId = "getSwarmService", summary = "Service")
  @APIResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ServiceDetail.class)))
  @APIResponse(responseCode = "404", description = "No such service")
  public ServiceDetail service(@PathParam("id") String id) {
    return swarm.service(id);
  }

  @GET
  @Path("/configs")
  @Operation(operationId = "listSwarmConfigs", summary = "Configs")
  public List<ConfigSummary> configs() {
    return swarm.configs();
  }

  /** Carries the config's DATA, base64-decoded. See {@link ConfigDetail} for why that is right. */
  @GET
  @Path("/configs/{id}")
  @Operation(operationId = "getSwarmConfig", summary = "Config")
  @APIResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ConfigDetail.class)))
  @APIResponse(responseCode = "404", description = "No such config")
  public ConfigDetail config(@PathParam("id") String id) {
    return swarm.config(id);
  }

  @GET
  @Path("/secrets")
  @Operation(operationId = "listSwarmSecrets", summary = "Secrets")
  public List<SecretSummary> secrets() {
    return swarm.secrets();
  }
}
