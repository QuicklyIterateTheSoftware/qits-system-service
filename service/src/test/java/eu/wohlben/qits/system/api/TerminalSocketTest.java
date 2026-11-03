package eu.wohlben.qits.system.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.testdocker.TerminalClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The terminal end to end: a REST create, a WebSocket attach, keystrokes down a real pseudo-terminal
 * to a real child, its output back, a resize that reaches the child as SIGWINCH, and the two close
 * codes that mean different things to the client.
 *
 * <p>Linux-only, because the PTY is.
 */
@QuarkusTest
@EnabledOnOs(OS.LINUX)
@TestSecurity(user = "an-operator", roles = "qits:admin")
class TerminalSocketTest {

  private static final String CONTAINER = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  @TestHTTPResource("/")
  URI base;

  @AfterEach
  void endEveryTerminal() {
    given()
        .when()
        .get("/system/api/terminals")
        .then()
        .extract()
        .jsonPath()
        .getList("id", String.class)
        .forEach(id -> given().when().delete("/system/api/terminals/" + id).then().statusCode(204));
  }

  private String openTerminal() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"kind\":\"EXEC\",\"container\":\"" + CONTAINER + "\",\"shell\":\"sh\"}")
        .when()
        .post("/system/api/terminals")
        .then()
        .statusCode(201)
        .extract()
        .path("socketPath");
  }

  private TerminalClient attach(String socketPath) {
    return TerminalClient.connect(TerminalClient.socketUri(base, socketPath), Map.of());
  }

  @Test
  void keystrokesReachTheChildAndItsOutputComesBack() throws Exception {
    try (TerminalClient client = attach(openTerminal())) {
      client.awaitText("fake-terminal ready");

      client.sendData("hello\n");

      client.awaitText("echo:hello");
    }
  }

  /**
   * THE RESIZE CHAIN, end to end and in one test, because every link of it is invisible on its own:
   * a JSON frame, an ioctl on the master, the kernel signalling the terminal's foreground process
   * group, and that group being the child only because {@code setsid --ctty} made it one. The child
   * has a {@code trap 'stty size' WINCH}, so the new size comes back as output.
   *
   * <p><b>The keystroke after the resize is not padding.</b> A shell blocked in its {@code read}
   * builtin defers a trapped signal until that read returns — measured on this host — so without
   * something to type the trap would fire only when the session ended. That is a property of the
   * stand-in, not of the service: {@code TerminalSessionTest} drives the same chain against a
   * child that is NOT blocked in a read and sees the size arrive on its own.
   */
  @Test
  void aResizeReachesTheChildAsSigwinch() throws Exception {
    try (TerminalClient client = attach(openTerminal())) {
      client.awaitText("fake-terminal ready");

      client.sendResize(132, 50);
      client.sendData("nudge\n");

      // `stty size` prints rows then columns — the winsize struct's own order. A swapped field
      // order in ForeignPty would print "132 50" here and nowhere else.
      client.awaitText("50 132");
    }
  }

  @Test
  void aReattachReplaysTheScrollback() throws Exception {
    String socketPath = openTerminal();
    try (TerminalClient first = attach(socketPath)) {
      first.awaitText("fake-terminal ready");
      first.sendData("before-the-reload\n");
      first.awaitText("echo:before-the-reload");
    }

    // A reload, a second tab, a dropped connection: the session lingers and the next attach gets
    // everything it missed before it gets anything live.
    try (TerminalClient second = attach(socketPath)) {
      second.awaitText("echo:before-the-reload");
      second.sendData("after\n");
      second.awaitText("echo:after");
    }
  }

  @Test
  void anExitClosesWith1000SoTheClientDoesNotReconnect() throws Exception {
    try (TerminalClient client = attach(openTerminal())) {
      client.awaitText("fake-terminal ready");

      client.sendData("exit\n");

      client.awaitText("[terminal exited (code 0)]");
      // 1000 is FINAL. Any other code is the client's signal to reconnect, which for a session
      // that has ended would be a reconnect loop.
      assertEquals(1000, client.awaitClose());
    }
  }

  @Test
  void anUnknownTerminalIsToldSoAndClosedCleanly() throws Exception {
    // The ordinary answer to a browser reconnecting to a session from before a restart — the
    // registry is in memory, so a restart drops every one of them.
    try (TerminalClient client =
        attach("/system/api/terminals/11111111-2222-3333-4444-555555555555")) {
      client.awaitText("no longer running");
      assertEquals(1000, client.awaitClose());
    }
  }

  @Test
  void anUnreadableFrameIsDroppedRatherThanClosingTheSession() throws Exception {
    try (TerminalClient client = attach(openTerminal())) {
      client.awaitText("fake-terminal ready");

      client.sendRaw("this is not json");
      client.sendRaw("{\"type\":\"teleport\"}");
      client.sendData("still-here\n");

      // A terminal is a stream; losing a live session to one malformed frame would be a client bug
      // costing an operator their shell.
      client.awaitText("echo:still-here");
    }
  }

  @Test
  void twoClientsShareOneTerminal() throws Exception {
    String socketPath = openTerminal();
    try (TerminalClient first = attach(socketPath);
        TerminalClient second = attach(socketPath)) {
      first.awaitText("fake-terminal ready");
      second.awaitText("fake-terminal ready");

      first.sendData("from-the-first\n");

      second.awaitText("echo:from-the-first");
      String id = socketPath.substring(socketPath.lastIndexOf('/') + 1);
      given()
          .when()
          .get("/system/api/terminals/" + id)
          .then()
          .statusCode(200)
          .body("attachedClients", org.hamcrest.Matchers.equalTo(2));
    }
  }

  @Test
  void aDeleteEndsTheSocketToo() throws Exception {
    String socketPath = openTerminal();
    String id = socketPath.substring(socketPath.lastIndexOf('/') + 1);
    try (TerminalClient client = attach(socketPath)) {
      client.awaitText("fake-terminal ready");

      given().when().delete("/system/api/terminals/" + id).then().statusCode(204);

      assertTrue(client.awaitClose() > 0, "the socket was closed when the session ended");
    }
  }
}
