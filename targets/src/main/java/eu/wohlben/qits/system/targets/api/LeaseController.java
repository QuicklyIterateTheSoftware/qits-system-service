package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.system.targets.control.Leases;
import eu.wohlben.qits.system.targets.control.OwnerIdentity;
import eu.wohlben.qits.system.targets.control.TargetAuditLog;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.entity.Lease;
import eu.wohlben.qits.system.targets.entity.LeaseState;
import eu.wohlben.qits.system.targets.entity.SlotType;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /system/api/leases}: qits-ci, qits-workspaces and qits-projects ask for a slot of their
 * type, roll its spec and end it. The owner is the caller's application ({@link OwnerIdentity}),
 * never a body field. A {@code qits:system} caller reads its own leases; an administrator, the
 * delivery desk's administrator or an agent reads every lease. An operator ({@code qits:admin},
 * {@code qits:admin-agent}, {@code qits:desk-admin}) ends any lease, and every such end is a
 * {@code target_audit} row.
 */
@Path("/leases")
@Produces(MediaType.APPLICATION_JSON)
@RegisterForReflection(
    targets = {
      TargetViews.LeaseDto.class,
      LeaseController.CreateLeaseRequest.class,
      LeaseController.ReplaceSpecRequest.class
    })
public class LeaseController {

  static final String ADMIN = "qits:admin";
  static final String ADMIN_AGENT = "qits:admin-agent";

  /**
   * The delivery desk's administrator (epic qits-1621): an operator on {@link #end}, beside {@link
   * #ADMIN} and {@link #ADMIN_AGENT}, so a desk can clear a lease stuck in ENDING.
   */
  static final String DESK_ADMIN = "qits:desk-admin";
  static final String SYSTEM = "qits:system";
  static final String AGENT = "qits:agent";

  /** The longest {@code ?reason=} an end takes. */
  static final int REASON_MAX = 500;

  @Inject Leases leases;

  @Inject OwnerIdentity owners;

  @Inject SecurityIdentity identity;

  @Schema(name = "CreateLeaseRequest")
  public record CreateLeaseRequest(
      @Schema(description = "CI, WORKSPACE or DESK; must be the caller's", required = true)
          String slotType,
      @Schema(description = "The owner's own key for the work (a run id, a row id)", required = true)
          String ownerRef,
      UUID projectId,
      @Schema(description = "ws-<rowId> or desk-<deskId>: the work volume, kept between leases")
          String workKey,
      @Schema(description = "Higher first; default 0") Integer priority,
      @Schema(description = "LeaseSpec{image, env, user, limits}", required = true) JsonNode spec,
      @Schema(description = "Consider this target only") UUID pinTargetId,
      @Schema(description = "{container, volume} of a retired runner's work to take over")
          Map<String, String> adopt) {}

  @Schema(name = "ReplaceLeaseSpecRequest")
  public record ReplaceSpecRequest(@Schema(required = true) JsonNode spec) {}

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(SYSTEM)
  @Operation(operationId = "createLease", summary = "Ask for a slot of the caller's type; QUEUED until granted")
  @APIResponse(
      responseCode = "201",
      description = "The lease, QUEUED",
      content = @Content(schema = @Schema(implementation = TargetViews.LeaseDto.class)))
  @APIResponse(responseCode = "400", description = "A malformed request, or IMAGE_NOT_ALLOWED")
  @APIResponse(responseCode = "403", description = "NOT_A_TENANT, or SLOT_TYPE_NOT_YOURS")
  @APIResponse(responseCode = "409", description = "LEASE_EXISTS: a live lease for this ownerRef")
  public Response create(CreateLeaseRequest request) {
    if (request == null) {
      throw TargetRefusal.badRequest("A lease needs a slotType, an ownerRef and a spec");
    }
    SlotType type = SlotType.parse(request.slotType());
    if (type == null) {
      throw TargetRefusal.badRequest("A lease's slotType is CI, WORKSPACE or DESK");
    }
    String owner = owners.requireTenantOf(identity, type);
    Lease lease =
        leases.create(
            owner,
            new Leases.NewLease(
                type,
                request.ownerRef(),
                request.projectId(),
                request.workKey(),
                request.priority() == null ? 0 : request.priority(),
                request.spec(),
                request.pinTargetId(),
                request.adopt()));
    return Response.status(Response.Status.CREATED).entity(TargetViews.view(lease)).build();
  }

  @GET
  @RolesAllowed({SYSTEM, ADMIN, ADMIN_AGENT, DESK_ADMIN, AGENT})
  @Operation(operationId = "listLeases", summary = "Leases, oldest first; a service sees its own")
  @APIResponse(responseCode = "200", description = "The leases the filter names")
  @APIResponse(responseCode = "400", description = "An unknown state or slotType")
  public List<TargetViews.LeaseDto> list(
      @QueryParam("state") String state,
      @QueryParam("slotType") String slotType,
      @QueryParam("targetId") String targetId) {
    LeaseState wantedState = state == null || state.isBlank() ? null : LeaseState.parse(state);
    if (state != null && !state.isBlank() && wantedState == null) {
      throw TargetRefusal.badRequest("No lease state " + state);
    }
    SlotType wantedType = slotType == null || slotType.isBlank() ? null : SlotType.parse(slotType);
    if (slotType != null && !slotType.isBlank() && wantedType == null) {
      throw TargetRefusal.badRequest("No slot type " + slotType);
    }
    UUID target = null;
    if (targetId != null && !targetId.isBlank()) {
      try {
        target = UUID.fromString(targetId);
      } catch (IllegalArgumentException notAUuid) {
        throw TargetRefusal.badRequest("targetId is a UUID");
      }
    }
    String owner = readsAll() ? null : ownOrNobody();
    return leases.list(new Leases.Filter(wantedState, wantedType, target, owner)).stream()
        .map(TargetViews::view)
        .toList();
  }

  @GET
  @Path("/{id}")
  @RolesAllowed({SYSTEM, ADMIN, ADMIN_AGENT, DESK_ADMIN, AGENT})
  @Operation(operationId = "getLease", summary = "One lease")
  @APIResponse(responseCode = "200", description = "The lease")
  @APIResponse(responseCode = "404", description = "No such lease, or not the caller's")
  public TargetViews.LeaseDto get(@PathParam("id") String id) {
    Lease lease = leases.get(leaseId(id));
    if (!readsAll() && !lease.ownerApp.equals(ownOrNobody())) {
      throw TargetRefusal.notFound("No lease " + id);
    }
    return TargetViews.view(lease);
  }

  @PUT
  @Path("/{id}/spec")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(SYSTEM)
  @Operation(operationId = "replaceLeaseSpec", summary = "Roll a lease: the runner recreates its container")
  @APIResponse(responseCode = "200", description = "The lease with its new spec and specHash")
  @APIResponse(responseCode = "400", description = "A malformed spec, or IMAGE_NOT_ALLOWED")
  @APIResponse(responseCode = "403", description = "NOT_YOUR_LEASE")
  @APIResponse(responseCode = "404", description = "No such lease")
  @APIResponse(responseCode = "409", description = "LEASE_ENDING: it is ending or ended")
  public TargetViews.LeaseDto replaceSpec(@PathParam("id") String id, ReplaceSpecRequest request) {
    return TargetViews.view(
        leases.replaceSpec(leaseId(id), owners.application(identity), request == null ? null : request.spec()));
  }

  @DELETE
  @Path("/{id}")
  @RolesAllowed({SYSTEM, ADMIN, ADMIN_AGENT, DESK_ADMIN})
  @Operation(
      operationId = "endLease",
      summary =
          "End a lease: ENDING, then ENDED once the runner reports it gone. An operator (qits:admin,"
              + " qits:admin-agent, qits:desk-admin) ends any lease, and an ENDING one at once")
  @APIResponse(responseCode = "200", description = "The lease, ENDING or ENDED")
  @APIResponse(responseCode = "400", description = "A reason longer than " + REASON_MAX + " characters")
  @APIResponse(responseCode = "403", description = "NOT_YOUR_LEASE: a qits:system caller's end of another's lease")
  @APIResponse(responseCode = "404", description = "No such lease")
  public TargetViews.LeaseDto end(
      @PathParam("id") String id,
      @Parameter(description = "Why; an operator's lands in the lease's detail and the audit")
          @QueryParam("reason")
          String reason) {
    if (reason != null && reason.length() > REASON_MAX) {
      throw TargetRefusal.badRequest("A reason is at most " + REASON_MAX + " characters");
    }
    if (!operates()) {
      return TargetViews.view(leases.end(leaseId(id), owners.application(identity)));
    }
    UUID leaseId;
    try {
      leaseId = UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      leaseId = null;
    }
    Leases.Operator operator =
        new Leases.Operator(owners.clientId(identity), TargetAuditLog.roles(identity));
    return TargetViews.view(leases.endByOperator(leaseId, id, operator, reason));
  }

  /**
   * Whether the caller ends leases as an operator. Decided by role, and an operator role wins: a
   * caller holding one and {@code qits:system} as well is an operator.
   */
  private boolean operates() {
    return identity.hasRole(ADMIN) || identity.hasRole(ADMIN_AGENT) || identity.hasRole(DESK_ADMIN);
  }

  private boolean readsAll() {
    return identity.hasRole(ADMIN)
        || identity.hasRole(ADMIN_AGENT)
        || identity.hasRole(DESK_ADMIN)
        || identity.hasRole(AGENT);
  }

  /** The caller's application, or a name no lease has. */
  private String ownOrNobody() {
    String own = owners.application(identity);
    return own == null ? "" : own;
  }

  static UUID leaseId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw TargetRefusal.notFound("No lease " + id);
    }
  }
}
