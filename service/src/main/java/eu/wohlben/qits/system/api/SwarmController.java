package eu.wohlben.qits.system.api;

import eu.wohlben.qits.system.reads.ConfigDetail;
import eu.wohlben.qits.system.reads.ConfigSummary;
import eu.wohlben.qits.system.reads.NodeDetail;
import eu.wohlben.qits.system.reads.NodeSummary;
import eu.wohlben.qits.system.reads.SecretSummary;
import eu.wohlben.qits.system.reads.ServiceDetail;
import eu.wohlben.qits.system.reads.ServiceSummary;
import eu.wohlben.qits.system.reads.SwarmReads;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

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
  public NodeDetail node(@PathParam("id") String id) {
    return swarm.node(id);
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
