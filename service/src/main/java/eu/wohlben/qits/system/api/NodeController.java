package eu.wohlben.qits.system.api;

import eu.wohlben.qits.system.reads.ContainerDetail;
import eu.wohlben.qits.system.reads.ContainerSummary;
import eu.wohlben.qits.system.reads.ImageSummary;
import eu.wohlben.qits.system.reads.LogChunk;
import eu.wohlben.qits.system.reads.NetworkSummary;
import eu.wohlben.qits.system.reads.NodeReads;
import eu.wohlben.qits.system.reads.VolumeSummary;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * What is on ONE node's docker daemon.
 *
 * <p>The node is in the path even though only one of them is reachable, because the client's model
 * is a cluster and the day a per-node agent exists these addresses do not change. A path naming any
 * other node answers 409 {@code NODE_REMOTE} — see {@link NodeReads}. {@code local} is always
 * accepted as a name for this node.
 */
@Path("/nodes")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
public class NodeController {

  @Inject NodeReads nodes;

  /** {@code all=true} includes stopped containers — which is what an operator wants after a death. */
  @GET
  @Path("/{id}/containers")
  public List<ContainerSummary> containers(
      @PathParam("id") String id, @QueryParam("all") @DefaultValue("false") boolean all) {
    return nodes.containers(id, all);
  }

  @GET
  @Path("/{id}/containers/{cid}")
  public ContainerDetail container(@PathParam("id") String id, @PathParam("cid") String cid) {
    return nodes.container(id, cid);
  }

  @GET
  @Path("/{id}/containers/{cid}/logs")
  public LogChunk logs(
      @PathParam("id") String id,
      @PathParam("cid") String cid,
      @QueryParam("tail") @DefaultValue("200") int tail) {
    return nodes.logs(id, cid, tail);
  }

  @GET
  @Path("/{id}/images")
  public List<ImageSummary> images(@PathParam("id") String id) {
    return nodes.images(id);
  }

  @GET
  @Path("/{id}/volumes")
  public List<VolumeSummary> volumes(@PathParam("id") String id) {
    return nodes.volumes(id);
  }

  @GET
  @Path("/{id}/networks")
  public List<NetworkSummary> networks(@PathParam("id") String id) {
    return nodes.networks(id);
  }
}
