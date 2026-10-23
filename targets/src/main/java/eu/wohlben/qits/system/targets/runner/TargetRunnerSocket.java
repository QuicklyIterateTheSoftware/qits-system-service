package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.systemrunner.protocol.HealthChecked;
import eu.wohlben.qits.systemrunner.protocol.Inventory;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The endpoint a registered target's runner holds open for as long as its machine is up. It owns
 * the WebSocket lifecycle and the framing; {@link TargetRegistry} owns the sessions —
 * qits-workspaces-service's {@code WorkspaceRunnerSocket}, kept in shape.
 *
 * <p><b>Identity is the bearer, and only the bearer.</b> {@code @RolesAllowed("qits:target-runner")}
 * is enforced at the HTTP upgrade. In {@link #onOpen} the token's {@code sub} — qits-idp's client id
 * of a {@code client_credentials} token — is looked up against {@code target.client_id}. No row is a
 * 1008 {@link RunnerWire.CloseReason#RUNNER_DELETED}, on which the runner decommissions itself.
 * The subject is read off the validated token and nowhere else, so the machine gate ({@code
 * qits.auth.machine.required=true}, on in every deployed environment) must be on for a runner to
 * connect at all; a dial with no subject is closed {@link #UNKNOWN_RUNNER}.
 *
 * <p><b>The path is a literal</b>, {@link SystemRunnerProtocol#SOCKET_PATH} ({@code
 * /system/targets/socket}): a {@code @WebSocket} path does not follow {@code quarkus.rest.path}, and
 * it sits under {@code /system}, which Quinoa already leaves alone. {@link SocketBearerLifetime}
 * keeps the connection past its bearer's {@code exp}. Frames run on virtual threads; an undecodable
 * one is dropped and logged.
 */
@WebSocket(path = SystemRunnerProtocol.SOCKET_PATH)
@RolesAllowed(TargetRunnerSocket.RUNNER_ROLE)
public class TargetRunnerSocket {

  private static final Logger LOG = Logger.getLogger(TargetRunnerSocket.class);

  /** The role qits-idp grants a {@code target-runner} client, and the only one admitted. */
  public static final String RUNNER_ROLE = "qits:target-runner";

  public static final String UNKNOWN_RUNNER = "UNKNOWN_RUNNER";

  public static final String CAPABILITY_MISMATCH = "CAPABILITY_MISMATCH";

  private static final UserData.TypedKey<TargetRegistry.Session> SESSION =
      new UserData.TypedKey<>("systemTargetSession");

  @Inject TargetRegistry registry;

  @Inject TargetFrames frames;

  @Inject Targets targets;

  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    Optional<String> subject = MachineIdentity.claim(identity, "sub");
    Target target = subject.map(targets::findByClientId).orElse(null);
    if (target == null) {
      LOG.warnf(
          "Refused a target runner dial from %s: subject %s is no registered target",
          connection.handshakeRequest().remoteAddress(), subject.orElse("(none)"));
      refuse(
          connection, subject.isPresent() ? RunnerWire.CloseReason.RUNNER_DELETED : UNKNOWN_RUNNER);
      return;
    }
    connection.userData().put(SESSION, registry.admit(target, connection));
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    TargetRegistry.Session session = connection.userData().get(SESSION);
    if (session == null) {
      return;
    }
    RunnerMessage decoded;
    try {
      decoded = frames.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable frame from target %s: %s", session.targetName(), e.getMessage());
      return;
    }
    switch (decoded) {
      case Hello hello -> {
        switch (registry.onHello(session, hello)) {
          case GREETED -> {}
          case VERSION_MISMATCH -> refuse(connection, CAPABILITY_MISMATCH);
          case TARGET_GONE -> refuse(connection, RunnerWire.CloseReason.RUNNER_DELETED);
        }
      }
      case Heartbeat ignored -> registry.onHeartbeat(session);
      case Inventory inventory -> registry.onInventory(session, inventory);
      case LoginState login -> registry.onLoginState(session, login);
      case HealthChecked checked -> registry.onHealthChecked(session, checked);
      default ->
          LOG.debugf(
              "Target %s sent a host frame %s; ignored",
              session.targetName(), decoded.getClass().getSimpleName());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    TargetRegistry.Session session = connection.userData().get(SESSION);
    if (session != null) {
      registry.onClose(session);
    }
  }

  private void refuse(WebSocketConnection connection, String reason) {
    TargetRegistry.closeBounded(
        connection,
        new CloseReason(TargetRegistry.CLOSE_POLICY, reason),
        "a refused target runner dial");
  }
}
