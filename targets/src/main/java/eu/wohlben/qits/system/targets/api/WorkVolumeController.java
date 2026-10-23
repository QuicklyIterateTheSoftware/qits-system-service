package eu.wohlben.qits.system.targets.api;

import eu.wohlben.qits.system.targets.control.OwnerIdentity;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.control.WorkVolumes;
import eu.wohlben.qits.system.targets.entity.WorkVolume;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/** {@code DELETE /system/api/work-volumes/{workKey}}: the owner is done with a piece of work for good. */
@Path("/work-volumes")
@Produces(MediaType.APPLICATION_JSON)
@RegisterForReflection(targets = TargetViews.WorkVolumeDto.class)
public class WorkVolumeController {

  @Inject WorkVolumes volumes;

  @Inject OwnerIdentity owners;

  @Inject SecurityIdentity identity;

  @DELETE
  @Path("/{workKey}")
  @RolesAllowed("qits:system")
  @Operation(operationId = "purgeWorkVolume", summary = "Purge the caller's work volume on its target")
  @APIResponse(
      responseCode = "202",
      description = "Marked; the runner removes it with the next estate",
      content = @Content(schema = @Schema(implementation = TargetViews.WorkVolumeDto.class)))
  @APIResponse(responseCode = "403", description = "NOT_A_TENANT")
  @APIResponse(responseCode = "404", description = "The caller has no such work volume")
  @APIResponse(responseCode = "409", description = "WORK_VOLUME_IN_USE: a live lease uses it")
  public Response purge(@PathParam("workKey") String workKey) {
    String owner = owners.requireTenantOf(identity, null);
    if (workKey == null || workKey.isBlank()) {
      throw TargetRefusal.notFound("No work volume");
    }
    WorkVolume marked = volumes.purge(owner, workKey);
    return Response.accepted(
            new TargetViews.WorkVolumeDto(
                marked.ownerApp,
                marked.workKey,
                marked.targetId,
                marked.volumeName,
                marked.purgeRequestedAt))
        .build();
  }
}
