package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.idp.TargetRunnerCommissioner;
import eu.wohlben.qits.system.targets.runner.TargetAddresses;
import eu.wohlben.qits.system.targets.runner.TargetHealth;
import eu.wohlben.qits.system.targets.runner.TargetInstallScript;
import eu.wohlben.qits.system.targets.runner.TargetRegistry;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * {@code /system/api/targets}: an operator declares the machines a qits-system-runner runs on,
 * reads them, sets their minimums, gates them, and a runner registers with its one-use token. The
 * roles are the dossier's table ("Targets in qits-system: data, doors, allocation").
 *
 * <p><b>A registration is rotated, never redeclared</b> (qits-1579): a registered target's token may
 * be rotated, and a runner installed with the new one registers again — the target keeps its id,
 * name, minimums, leases and work volumes, the old client is given back to qits-idp and the old
 * runner's connections are closed {@link TargetRegistry#REREGISTERED}. Until then the old runner
 * keeps working.
 */
@Path("/targets")
@Produces(MediaType.APPLICATION_JSON)
@RegisterForReflection(
    targets = {
      TargetViews.TargetDto.class,
      TargetViews.SlotCounts.class,
      TargetViews.Login.class,
      TargetViews.TargetRegistrationDto.class,
      TargetViews.RegisteredTarget.class,
      TargetViews.HealthCheckRequested.class,
      TargetController.CreateTargetRequest.class,
      TargetController.PatchTargetRequest.class,
      TargetController.RegisterTargetRequest.class,
      TargetController.RotateTargetRegistrationTokenRequest.class
    })
public class TargetController {

  private static final Logger LOG = Logger.getLogger(TargetController.class);

  static final String ADMIN = "qits:admin";
  static final String ADMIN_AGENT = "qits:admin-agent";

  /**
   * A project's delivery desk (qits-1596): admitted outright beside {@link #ADMIN_AGENT} wherever
   * it is admitted here. A target is platform infrastructure with no project of its own to bind a
   * desk's access to, and none of these doors write a git ref or touch the desk's own credentials
   * — a registration token mints a runner's client, never the desk's.
   */
  static final String DESK = "qits:desk";

  static final String SYSTEM = "qits:system";
  static final String AGENT = "qits:agent";
  static final String REGISTRATION = "qits:target-runner-registration";

  @Inject Targets targets;

  @Inject TargetViews views;

  @Inject TargetRegistry registry;

  @Inject TargetHealth health;

  @Inject TargetRunnerCommissioner idp;

  @Inject TargetAddresses addresses;

  @Inject TargetInstallScript installScript;

  @Inject MachineAuth machineAuth;

  @Inject SecurityIdentity identity;

  @Schema(name = "CreateTargetRequest")
  public record CreateTargetRequest(
      @Schema(description = "[a-z][a-z0-9-]{0,63}, unique", required = true) String name,
      @Schema(description = "Free text, at most 1024 characters") String description,
      @Schema(description = "Slots reserved per type; absent is 0") TargetViews.SlotCounts minimums,
      @Schema(
              description =
                  "The slots total the install line carries, 0..1024 and at least the minimums'"
                      + " sum; absent is the minimums' sum. Not stored: the runner reports its total")
          Integer slots) {}

  @Schema(name = "PatchTargetRequest")
  public record PatchTargetRequest(
      @Schema(description = "Blank clears it; absent leaves it") String description,
      @Schema(description = "Absent members leave theirs") TargetViews.SlotCounts minimums) {}

  @Schema(name = "RotateTargetRegistrationTokenRequest")
  public record RotateTargetRegistrationTokenRequest(
      @Schema(
              description =
                  "The slots total the new install line carries, 0..1024 and at least the minimums'"
                      + " sum; absent is the reported total, or the minimums' sum when that is"
                      + " more. Not stored: the runner reports its total")
          Integer slots) {}

  @Schema(name = "RegisterTargetRequest")
  public record RegisterTargetRequest(
      @Schema(description = "What the runner says about itself: a JSON object") JsonNode capabilities) {}

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({ADMIN, ADMIN_AGENT, DESK, SYSTEM})
  @Operation(operationId = "createTarget", summary = "Declare a target; answers its install line, once")
  @APIResponse(
      responseCode = "201",
      description = "The target, its registration token and the install line carrying it",
      content = @Content(schema = @Schema(implementation = TargetViews.TargetRegistrationDto.class)))
  @APIResponse(
      responseCode = "400",
      description = "A malformed name, description or minimum, or slots outside 0..1024 or below the minimums' sum")
  @APIResponse(responseCode = "409", description = "TARGET_NAME_TAKEN")
  @APIResponse(responseCode = "502", description = "qits-idp refused the registration token")
  @APIResponse(
      responseCode = "503",
      description = "Commissioning is off, RUNNER_PLANE_UNCONFIGURED, or no runner version is pinned")
  public Response create(CreateTargetRequest request) {
    requireMachineAudience();
    if (request == null) {
      throw TargetRefusal.badRequest("A target needs a name");
    }
    Targets.Minimums minimums = request.minimums() == null ? null : request.minimums().minimums();
    targets.requireCreatable(request.name(), request.description(), minimums);
    Targets.requireInstallSlots(request.slots(), Targets.sum(minimums));
    requireCommissioning();
    installScript.requireRenderable();
    UUID id = UUID.randomUUID();
    TargetRunnerCommissioner.IssuedToken token = commissionRegistrationToken(id);
    Target target;
    try {
      target =
          targets.create(
              id, request.name(), request.description(), minimums, token.tokenId(), token.subject());
    } catch (RuntimeException refused) {
      idp.deleteToken(token.tokenId());
      throw refused;
    }
    LOG.infof("Target %s (%s) declared; its registration token is %s", target.name, id, token.tokenId());
    return Response.status(Response.Status.CREATED)
        .entity(registration(target, token.token(), request.slots()))
        .build();
  }

  @GET
  @RolesAllowed({ADMIN, ADMIN_AGENT, SYSTEM, AGENT})
  @Operation(operationId = "listTargets", summary = "Every target, by name")
  @APIResponse(responseCode = "200", description = "Every target, by name")
  public List<TargetViews.TargetDto> list() {
    Map<UUID, Map<SlotType, Integer>> used = targets.used();
    return targets.list().stream()
        .map(t -> views.view(t, used.getOrDefault(t.id, Map.of())))
        .toList();
  }

  @GET
  @Path("/install.sh")
  @Produces(MediaType.TEXT_PLAIN)
  @RolesAllowed({REGISTRATION, ADMIN, ADMIN_AGENT, SYSTEM, AGENT})
  @Operation(operationId = "getTargetInstallScript", summary = "The generic install script the install line pipes to sh")
  @APIResponse(responseCode = "200", description = "A POSIX sh script, carrying no secret")
  @APIResponse(
      responseCode = "503",
      description = "RUNNER_PLANE_UNCONFIGURED, no runner version pinned, or unrenderable")
  public String installScript() {
    installScript.requireRenderable();
    return installScript.script();
  }

  @GET
  @Path("/{id}")
  @RolesAllowed({ADMIN, ADMIN_AGENT, SYSTEM, AGENT})
  @Operation(operationId = "getTarget", summary = "One target")
  @APIResponse(responseCode = "200", description = "The target")
  @APIResponse(responseCode = "404", description = "No such target")
  public TargetViews.TargetDto get(@PathParam("id") String id) {
    return views.view(targets.get(targetId(id)));
  }

  @GET
  @Path("/{id}/health")
  @RolesAllowed({ADMIN, ADMIN_AGENT, SYSTEM, AGENT})
  @Operation(operationId = "getTargetHealth", summary = "A target's newest settled health check")
  @APIResponse(responseCode = "200", description = "{at, ok, detail, requestId, checks[]}")
  @APIResponse(responseCode = "204", description = "No health check has settled yet")
  @APIResponse(responseCode = "404", description = "No such target")
  public Response health(@PathParam("id") String id) {
    Target target = targets.get(targetId(id));
    JsonNode report = LeaseSpecs.parse(target.health);
    return report == null ? Response.noContent().build() : Response.ok(report).build();
  }

  @PATCH
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({ADMIN, ADMIN_AGENT, DESK, SYSTEM})
  @Operation(operationId = "patchTarget", summary = "Change a target's description or minimums")
  @APIResponse(responseCode = "200", description = "The target as it now is")
  @APIResponse(responseCode = "400", description = "A malformed description or minimum")
  @APIResponse(responseCode = "404", description = "No such target")
  @APIResponse(
      responseCode = "409",
      description = "MINIMUMS_EXCEED_TOTAL: their sum is above the total the runner reports")
  public TargetViews.TargetDto patch(@PathParam("id") String id, PatchTargetRequest request) {
    requireMachineAudience();
    PatchTargetRequest change = request == null ? new PatchTargetRequest(null, null) : request;
    UUID targetId = targetId(id);
    Target patched =
        targets.patch(
            targetId,
            change.description(),
            change.minimums() == null ? null : change.minimums().minimums());
    if (change.minimums() != null) {
      registry.estateChanged(targetId);
    }
    return views.view(patched);
  }

  @DELETE
  @Path("/{id}")
  @RolesAllowed({ADMIN, ADMIN_AGENT, DESK, SYSTEM})
  @Operation(operationId = "deleteTarget", summary = "Decommission a target: retire its runner, give its credentials back")
  @APIResponse(responseCode = "204", description = "Gone")
  @APIResponse(responseCode = "404", description = "No such target")
  @APIResponse(responseCode = "409", description = "TARGET_BUSY: it holds a lease that has not ended")
  public Response delete(@PathParam("id") String id) {
    requireMachineAudience();
    Target gone = targets.delete(targetId(id));
    registry.deleted(gone.id);
    if (gone.clientId != null) {
      idp.decommissionClient(gone.clientId);
    }
    if (gone.registrationTokenId != null) {
      idp.deleteToken(gone.registrationTokenId);
    }
    LOG.infof("Target %s (%s) decommissioned", gone.name, gone.id);
    return Response.noContent().build();
  }

  @POST
  @Path("/{id}/registration-token")
  @RolesAllowed({ADMIN, ADMIN_AGENT, DESK, SYSTEM})
  @Operation(
      operationId = "rotateTargetRegistrationToken",
      summary = "Replace a target's registration token; answers a new install line",
      description =
          "Registered or not: a registered target keeps its client, and its runner keeps working,"
              + " until a runner installed with the new token registers. The body is optional.")
  @APIResponse(
      responseCode = "200",
      description = "The target, its new registration token and the install line carrying it",
      content = @Content(schema = @Schema(implementation = TargetViews.TargetRegistrationDto.class)))
  @APIResponse(
      responseCode = "400",
      description = "slots outside 0..1024 or below the target's minimums' sum")
  @APIResponse(responseCode = "404", description = "No such target")
  @APIResponse(responseCode = "502", description = "qits-idp refused the registration token")
  @APIResponse(responseCode = "503", description = "Commissioning is off, or RUNNER_PLANE_UNCONFIGURED")
  public TargetViews.TargetRegistrationDto rotateRegistrationToken(
      @PathParam("id") String id,
      // Read as text, with no @Consumes: the body is optional, and the console and the CLI send
      // none and no content type, which a record parameter answers 415.
      @RequestBody(
              required = false,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema = @Schema(implementation = RotateTargetRegistrationTokenRequest.class)))
          String body) {
    requireMachineAudience();
    UUID targetId = targetId(id);
    Integer slots = rotateSlots(body);
    Targets.requireInstallSlots(slots, targets.get(targetId).minimumsSum());
    requireCommissioning();
    installScript.requireRenderable();
    TargetRunnerCommissioner.IssuedToken token = commissionRegistrationToken(targetId);
    String previous;
    try {
      previous = targets.replaceRegistrationToken(targetId, token.tokenId(), token.subject());
    } catch (RuntimeException refused) {
      idp.deleteToken(token.tokenId());
      throw refused;
    }
    if (previous != null && !idp.deleteToken(previous)) {
      LOG.warnf(
          "Target %s's replaced registration token %s is still live at qits-idp; the commission"
              + " reconcile reaps it",
          targetId, previous);
    }
    return registration(targets.get(targetId), token.token(), slots);
  }

  @POST
  @Path("/{id}/greenlight")
  @RolesAllowed({ADMIN, ADMIN_AGENT, DESK})
  @Operation(operationId = "greenlightTarget", summary = "Lift a target's quarantine")
  @APIResponse(responseCode = "200", description = "The target as it now is")
  @APIResponse(responseCode = "404", description = "No such target")
  public TargetViews.TargetDto greenlight(@PathParam("id") String id) {
    UUID targetId = targetId(id);
    boolean wasQuarantined = targets.get(targetId).quarantined();
    Target lifted = targets.greenlight(targetId);
    if (wasQuarantined) {
      registry.reinstated(targetId, by());
    }
    return views.view(lifted);
  }

  @POST
  @Path("/{id}/healthcheck")
  @RolesAllowed({ADMIN, ADMIN_AGENT, SYSTEM, AGENT})
  @Operation(operationId = "requestTargetHealthCheck", summary = "Ask a connected target for a health check")
  @APIResponse(
      responseCode = "202",
      description = "Sent, or one is pending already; its requestId",
      content = @Content(schema = @Schema(implementation = TargetViews.HealthCheckRequested.class)))
  @APIResponse(responseCode = "404", description = "No such target")
  @APIResponse(responseCode = "409", description = "TARGET_UNAVAILABLE: the target is not connected")
  public Response healthcheck(@PathParam("id") String id) {
    Target target = targets.get(targetId(id));
    String requestId = health.request(target.id);
    if (requestId == null) {
      throw unavailable(target, "run a health check");
    }
    return Response.accepted(new TargetViews.HealthCheckRequested(requestId)).build();
  }

  @POST
  @Path("/{id}/login-check")
  @RolesAllowed({ADMIN, ADMIN_AGENT, DESK})
  @Operation(operationId = "requestTargetLoginCheck", summary = "Ask a connected target to probe its sign-in now")
  @APIResponse(responseCode = "202", description = "Sent")
  @APIResponse(responseCode = "404", description = "No such target")
  @APIResponse(responseCode = "409", description = "TARGET_UNAVAILABLE: the target is not connected")
  public Response loginCheck(@PathParam("id") String id) {
    Target target = targets.get(targetId(id));
    if (!registry.probeLogin(target.id)) {
      throw unavailable(target, "check its sign-in");
    }
    return Response.accepted().build();
  }

  @POST
  @Path("/{id}/register")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(REGISTRATION)
  @Operation(
      operationId = "registerTarget",
      summary = "Register a target's runner with its registration token; answers its client")
  @APIResponse(
      responseCode = "200",
      description = "The runner's own client and where to use it",
      content = @Content(schema = @Schema(implementation = TargetViews.RegisteredTarget.class)))
  @APIResponse(responseCode = "400", description = "Capabilities that are not a JSON object")
  @APIResponse(responseCode = "403", description = "The token is not this target's")
  @APIResponse(responseCode = "404", description = "No such target")
  @APIResponse(
      responseCode = "409",
      description =
          "ALREADY_REGISTERED: registered, and its token spent; rotate it to register again")
  @APIResponse(responseCode = "502", description = "qits-idp refused the runner's client")
  @APIResponse(responseCode = "503", description = "Commissioning is off, or RUNNER_PLANE_UNCONFIGURED")
  public TargetViews.RegisteredTarget register(
      @PathParam("id") String id, RegisterTargetRequest request) {
    machineAuth.require();
    UUID targetId = targetId(id);
    JsonNode capabilities = request == null ? null : request.capabilities();
    if (capabilities != null && !capabilities.isNull() && !capabilities.isObject()) {
      throw TargetRefusal.badRequest("A runner's capabilities are a JSON object");
    }
    String subject = MachineIdentity.claim(identity, "sub").orElse(null);
    Target target = targets.requireRegistrable(targetId, subject);
    requireCommissioning();
    String tokenUrl = addresses.tokenUrl();
    String socketUrl = addresses.socketUrl();
    TargetRunnerCommissioner.RunnerClient client;
    try {
      client = idp.runnerClient(targetId);
    } catch (TargetRunnerCommissioner.CommissionFailedException failed) {
      throw new TargetRefusal(
          502,
          TargetRefusal.IDP_REFUSED,
          "qits-idp did not commission the runner's client: " + failed.getMessage());
    }
    Targets.Registered registered;
    try {
      registered =
          targets.markRegistered(
              targetId,
              subject,
              client.clientId(),
              capabilities == null || capabilities.isNull() ? null : LeaseSpecs.text(capabilities));
    } catch (RuntimeException refused) {
      idp.decommissionClient(client.clientId());
      throw refused;
    }
    String previous = registered.previousClientId();
    if (previous != null) {
      LOG.infof(
          "Target %s (%s) registered again as %s; giving back its previous client %s",
          target.name, targetId, client.clientId(), previous);
      registry.reregistered(targetId, previous);
      if (!idp.decommissionClient(previous)) {
        LOG.warnf(
            "Target %s's replaced runner client %s is still live at qits-idp; the commission"
                + " reconcile reaps it",
            targetId, previous);
      }
    }
    if (target.registrationTokenId != null && !idp.deleteToken(target.registrationTokenId)) {
      LOG.warnf(
          "Target %s registered, and its spent registration token %s is still live at qits-idp;"
              + " the reconcile reaps it",
          targetId, target.registrationTokenId);
    }
    LOG.infof(
        "Target %s (%s) registered as %s; quarantined until its first health check",
        target.name, targetId, client.clientId());
    return new TargetViews.RegisteredTarget(
        client.clientId(), client.secret(), tokenUrl, addresses.audience(), socketUrl);
  }

  // --- internals --------------------------------------------------------------------------------

  /** The {@code slots} of an optional rotate body: absent, empty, {@code {}} or null is none. */
  static Integer rotateSlots(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    JsonNode parsed = LeaseSpecs.parse(body);
    if (parsed == null || !(parsed.isObject() || parsed.isNull())) {
      throw TargetRefusal.badRequest("A rotation's body is a JSON object");
    }
    JsonNode slots = parsed.path("slots");
    if (slots.isMissingNode() || slots.isNull()) {
      return null;
    }
    if (!slots.isInt()) {
      throw TargetRefusal.badRequest("A slots total is a whole number, not " + slots);
    }
    return slots.intValue();
  }

  private TargetViews.TargetRegistrationDto registration(
      Target target, String token, Integer slots) {
    return new TargetViews.TargetRegistrationDto(
        views.view(target),
        token,
        installScript.line(
            target, token, slots == null ? TargetInstallScript.defaultSlots(target) : slots));
  }

  private TargetRunnerCommissioner.IssuedToken commissionRegistrationToken(UUID targetId) {
    TargetRunnerCommissioner.IssuedToken token;
    try {
      token = idp.registrationToken(targetId);
    } catch (TargetRunnerCommissioner.CommissionFailedException failed) {
      throw new TargetRefusal(
          502,
          TargetRefusal.IDP_REFUSED,
          "qits-idp did not commission the registration token: " + failed.getMessage());
    }
    try {
      TargetInstallScript.requireCarriable(token.token());
    } catch (IllegalArgumentException uncarriable) {
      idp.deleteToken(token.tokenId());
      throw new TargetRefusal(502, TargetRefusal.IDP_REFUSED, uncarriable.getMessage());
    }
    return token;
  }

  private static TargetRefusal unavailable(Target target, String what) {
    return TargetRefusal.conflict(
        TargetRefusal.TARGET_UNAVAILABLE,
        "Target " + target.name + " is not connected, so it cannot " + what + " now");
  }

  private String by() {
    return identity == null || identity.isAnonymous()
        ? "an administrator"
        : identity.getPrincipal().getName();
  }

  private void requireMachineAudience() {
    if (MachineIdentity.isMachine(identity)
        && !identity.hasRole(ADMIN)
        && !identity.hasRole(ADMIN_AGENT)) {
      machineAuth.require();
    }
  }

  private void requireCommissioning() {
    if (!idp.enabled()) {
      throw new TargetRefusal(
          503,
          TargetRefusal.COMMISSIONING_OFF,
          "This qits-system commissions no credentials (no idp client secret), so it cannot mint a"
              + " target runner's");
    }
  }

  static UUID targetId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw TargetRefusal.notFound("No target " + id);
    }
  }
}
