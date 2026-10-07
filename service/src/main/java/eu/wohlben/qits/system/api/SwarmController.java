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

/**
 * The cluster-wide reads. These are answered by the swarm MANAGER this service runs on, so they
 * cover every node — unlike {@link NodeController}, which can only see this machine.
 *
 * <p>There is no secret DETAIL route, and that is not an omission: swarm returns no secret value to
 * anybody, so the only thing a detail could add is the same metadata the list already carries.
 */
@Path("/swarm")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
public class SwarmController {

  @Inject SwarmReads swarm;

  @GET
  @Path("/nodes")
  public List<NodeSummary> nodes() {
    return swarm.nodes();
  }

  @GET
  @Path("/nodes/{id}")
  public NodeDetail node(@PathParam("id") String id) {
    return swarm.node(id);
  }

  @GET
  @Path("/services")
  public List<ServiceSummary> services() {
    return swarm.services();
  }

  @GET
  @Path("/services/{id}")
  public ServiceDetail service(@PathParam("id") String id) {
    return swarm.service(id);
  }

  @GET
  @Path("/configs")
  public List<ConfigSummary> configs() {
    return swarm.configs();
  }

  /** Carries the config's DATA, base64-decoded. See {@link ConfigDetail} for why that is right. */
  @GET
  @Path("/configs/{id}")
  public ConfigDetail config(@PathParam("id") String id) {
    return swarm.config(id);
  }

  @GET
  @Path("/secrets")
  public List<SecretSummary> secrets() {
    return swarm.secrets();
  }
}
