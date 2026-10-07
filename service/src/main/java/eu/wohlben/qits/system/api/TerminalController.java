package eu.wohlben.qits.system.api;

import eu.wohlben.qits.system.docker.DockerIdentifiers;
import eu.wohlben.qits.system.error.BadRequestException;
import eu.wohlben.qits.system.error.NotFoundException;
import eu.wohlben.qits.system.reads.NodeReads;
import eu.wohlben.qits.system.terminal.ExecShell;
import eu.wohlben.qits.system.terminal.TerminalLaunch;
import eu.wohlben.qits.system.terminal.TerminalSession;
import eu.wohlben.qits.system.terminal.TerminalSessions;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The terminal LIFECYCLE — create, list, read, end. The bytes go over the WebSocket in {@link
 * TerminalSocket}; this is the resource that exists before any socket does.
 *
 * <p>Splitting the two is what lets the client show what is running before it connects, reconnect
 * to a session by address after a reload, and end one from a list without opening it.
 *
 * <p><b>Validation happens HERE and resolution happens against the daemon</b>, so that by the time
 * a {@link TerminalLaunch} exists, every string in it is either an enum or something the daemon
 * itself printed.
 *
 * <p>The two reads also take {@code qits:agent}: an agent may see which terminals exist. Opening
 * and ending one stays with the class-level pair. A method-level {@code @RolesAllowed} replaces the
 * class-level one, so each read names all three roles.
 */
@Path("/terminals")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
public class TerminalController {

  @Inject TerminalSessions sessions;

  @Inject NodeReads nodes;

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public List<TerminalView> list() {
    return sessions.list().stream().map(TerminalView::of).toList();
  }

  @GET
  @Path("/{id}")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public TerminalView get(@PathParam("id") String id) {
    return TerminalView.of(require(id));
  }

  /**
   * Open a terminal.
   *
   * <p>201 for a new one; 200 for the live glances session, because there is one host and asking
   * for its monitor twice is asking for the same monitor. The body is the same either way, so a
   * client that ignores the status still works — the status is what tells it whether it just
   * started something.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response open(TerminalRequest request, SecurityContext security) {
    TerminalLaunch launch = launchOf(request);
    String principal =
        security.getUserPrincipal() == null ? "unknown" : security.getUserPrincipal().getName();
    TerminalSessions.Created created = sessions.create(launch, principal);
    return Response.status(created.fresh() ? 201 : 200)
        .entity(TerminalView.of(created.session()))
        .build();
  }

  /** End a terminal now, rather than waiting for the linger window. Idempotent-ish: 404 if gone. */
  @DELETE
  @Path("/{id}")
  public Response close(@PathParam("id") String id) {
    UUID sessionId = DockerIdentifiers.parseSessionId(id);
    if (sessionId == null || !sessions.terminate(sessionId)) {
      throw new NotFoundException("no such terminal: " + id);
    }
    return Response.noContent().build();
  }

  private TerminalSession require(String id) {
    UUID sessionId = DockerIdentifiers.parseSessionId(id);
    if (sessionId == null) {
      throw new NotFoundException("no such terminal: " + id);
    }
    return sessions
        .get(sessionId)
        .orElseThrow(() -> new NotFoundException("no such terminal: " + id));
  }

  /**
   * Read the request into a launch, refusing everything that is not one of the two shapes.
   *
   * <p>The EXEC path resolves the container BEFORE the launch exists: a reference that names
   * nothing is a 404, one that names a stopped container is a 409, and one the daemon cannot be
   * asked about is a 503 — none of which is a terminal that opens and immediately dies with a
   * message the browser cannot act on.
   */
  private TerminalLaunch launchOf(TerminalRequest request) {
    if (request == null || request.kind() == null || request.kind().isBlank()) {
      throw new BadRequestException("kind is required: GLANCES or EXEC");
    }
    return switch (request.kind().trim().toUpperCase(Locale.ROOT)) {
      case "GLANCES" -> new TerminalLaunch.Glances();
      case "EXEC" -> {
        if (request.container() == null || request.container().isBlank()) {
          throw new BadRequestException("container is required for an EXEC terminal");
        }
        ExecShell shell = ExecShell.parse(request.shell());
        if (shell == null) {
          throw new BadRequestException("shell must be bash or sh, got: " + request.shell());
        }
        DockerIdentifiers.requireContainerRef(request.container());
        NodeReads.ContainerTarget target = nodes.requireRunningContainer(request.container());
        yield new TerminalLaunch.Exec(target.id(), target.name(), shell);
      }
      default -> throw new BadRequestException("kind must be GLANCES or EXEC, got: " + request.kind());
    };
  }
}
