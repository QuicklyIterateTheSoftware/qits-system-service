package eu.wohlben.qits.system.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.system.docker.DockerIdentifiers;
import eu.wohlben.qits.system.terminal.TerminalFrame;
import eu.wohlben.qits.system.terminal.TerminalOutputSink;
import eu.wohlben.qits.system.terminal.TerminalSessions;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The terminal's transport: xterm.js on one end, a pseudo-terminal on the other.
 *
 * <p><b>ATTACH ONLY.</b> A session is created by {@code POST /system/api/terminals} and this socket
 * joins one that already exists. That is the difference from qits-projects' sign-in terminal, which
 * spawned on first connect: here a reload, a second tab and a dropped connection all re-attach to
 * the same live session with a scrollback replay, and a session nobody attaches to is ended by the
 * registry's linger timer rather than by the socket.
 *
 * <p><b>{@code qits:admin} ONLY — not the machine role.</b> Every REST route here takes {@code
 * qits:system} as well, because a machine reading the host's shape is ordinary. A terminal is not a
 * read: it is an interactive shell on the platform host, and no machine on this platform has a
 * reason to hold one. Class-level {@code @RolesAllowed} on a WebSockets Next endpoint secures the
 * HTTP UPGRADE itself (3.34's {@code SecurityHttpUpgradeCheck}), so an unauthorised client is
 * refused the handshake rather than connected and then ignored. {@code qits:admin-agent} is
 * admitted too (qits-628 follow-up); remove it here if this door must stay human-only.
 *
 * <p><b>The path is a LITERAL.</b> {@code @WebSocket} does not follow {@code quarkus.rest.path}, so
 * the {@code /system/api} prefix is spelled out here and has to move with that key by hand.
 * {@link TerminalView} builds its {@code socketPath} from {@link #PATH_PREFIX} so the client never
 * spells it a second time.
 *
 * <p><b>Close codes are the protocol.</b> 1000 means FINAL — the session ended, or never existed;
 * the client shows the note and does not reconnect. Anything else (a restart, a dropped sink) is
 * the client's signal to reconnect, which it does, and the replay puts it back where it was.
 */
@WebSocket(path = TerminalSocket.PATH_PREFIX + "{id}")
@RolesAllowed({"qits:admin", "qits:admin-agent"})
public class TerminalSocket {

  /** The literal prefix of the socket path. See the class javadoc for why it is spelled at all. */
  public static final String PATH_PREFIX = "/system/api/terminals/";

  private static final Logger LOG = Logger.getLogger(TerminalSocket.class);

  /** The per-connection routing handle, so input/resize/detach hit the exact attached session. */
  private final Map<String, TerminalSessions.Handle> handles = new ConcurrentHashMap<>();

  @Inject TerminalSessions sessions;

  @Inject ObjectMapper objectMapper;

  /**
   * How long a write to one browser may block before that browser is dropped. The session's reader
   * thread fans one PTY out to every attached sink, so a client that stopped reading would stall
   * the terminal for everybody else watching it.
   */
  @ConfigProperty(name = "qits.system.terminals.write-timeout")
  Duration writeTimeout;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("id") String id, WebSocketConnection connection) {
    UUID sessionId = DockerIdentifiers.parseSessionId(id);
    ConnectionSink sink = new ConnectionSink(connection, writeTimeout);
    Optional<TerminalSessions.Handle> handle =
        sessionId == null
            ? Optional.empty()
            : sessions.attach(
                sessionId,
                sink,
                exitCode -> {
                  if (connection.isOpen()) {
                    // In-band, so the operator sees WHY the terminal stopped, then a clean 1000 so
                    // the client knows not to reconnect to a session that has ended.
                    sink.write(
                        "\r\n\u001b[33m[terminal exited (code " + exitCode + ")]\u001b[0m\r\n");
                    connection.closeAndAwait(CloseReason.NORMAL);
                  }
                });
    if (handle.isEmpty()) {
      // A restart drops every session, so this is the ordinary answer to a client reconnecting to
      // one from before it. 1000 (final) — reconnecting would only be told the same thing again.
      sink.write("\r\n\u001b[33m[this terminal is no longer running]\u001b[0m\r\n");
      connection.closeAndAwait(CloseReason.NORMAL);
      return;
    }
    // The connection may already be closing (onClose can race a blocking onOpen): only register a
    // handle for a live connection, else detach now so the linger backstop still arms and the map
    // never leaks a dead connection.
    if (connection.isOpen()) {
      handles.put(connection.id(), handle.get());
    } else {
      handle.get().detach();
    }
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    TerminalSessions.Handle handle = handles.get(connection.id());
    if (handle == null) {
      return;
    }
    TerminalFrame frame = TerminalFrame.parse(objectMapper, message);
    // An unparseable frame is dropped, not fatal: a terminal is a stream, and closing it over one
    // bad frame would lose a session to a client bug.
    switch (frame) {
      case TerminalFrame.Data data -> handle.input(data.text().getBytes(StandardCharsets.UTF_8));
      case TerminalFrame.Resize resize -> handle.resize(resize.cols(), resize.rows());
      case null -> LOG.debugf("Unreadable frame on terminal socket %s", connection.id());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    TerminalSessions.Handle handle = handles.remove(connection.id());
    if (handle != null) {
      // Detach only — the session lingers for a re-attach. A closed tab is not a decision to end a
      // terminal; the registry's linger timer is what decides that.
      handle.detach();
    }
  }

  /**
   * Bridges a WebSocket connection to the framework-free output sink, WITH A DEADLINE.
   *
   * <p>{@code sendTextAndAwait} waits forever, and a browser that stopped reading (a suspended tab,
   * a laptop lid) fills the send buffer and never drains it — which would hold the session's single
   * reader thread and freeze the terminal for every other viewer. So the write is bounded, and a
   * client that misses the deadline is dropped and closed 1011: a non-1000 close, which is the
   * client's own signal to reconnect and take the replay.
   */
  private static final class ConnectionSink implements TerminalOutputSink {
    private final WebSocketConnection connection;
    private final Duration writeTimeout;
    private volatile boolean dropped;

    ConnectionSink(WebSocketConnection connection, Duration writeTimeout) {
      this.connection = connection;
      this.writeTimeout = writeTimeout;
    }

    @Override
    public void write(String data) {
      if (dropped || !connection.isOpen()) {
        return;
      }
      try {
        connection.sendText(data).await().atMost(writeTimeout);
      } catch (RuntimeException e) {
        dropped = true;
        LOG.debugf(
            "Dropping terminal client %s: it did not take a write within %s",
            connection.id(), writeTimeout);
        try {
          connection.closeAndAwait(new CloseReason(1011, "write timed out"));
        } catch (RuntimeException ignored) {
          // The connection is already gone; nothing further to do about it.
        }
      }
    }

    @Override
    public boolean isOpen() {
      return !dropped && connection.isOpen();
    }
  }
}
