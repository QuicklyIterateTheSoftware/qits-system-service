package eu.wohlben.qits.system.api;

import eu.wohlben.qits.system.reads.HostReads;
import eu.wohlben.qits.system.reads.Overview;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The landing read: the machine, its disk and its swarm, in one call.
 *
 * <p>Both roles, like every route here: {@code qits:admin} is a person in a browser (identity from
 * qits-gateway's X-Qits-User / X-Qits-Roles) and {@code qits:system} is a machine holding a bearer
 * this service's idp client minted. {@code qits:agent}, a commissioned agent, may read it too.
 * There is no anonymous route in this service and there must never be one — what it reads is the
 * shape of the host.
 */
@Path("/overview")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
public class OverviewController {

  @Inject HostReads hosts;

  @GET
  public Overview overview() {
    return hosts.overview();
  }
}
