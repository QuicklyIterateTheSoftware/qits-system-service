package eu.wohlben.qits.system.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.InteractiveRunSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What this service ASKS for when it opens a terminal. How that renders as an argv is the library's
 * ({@code InteractiveArgvTest} there, copied from the {@code TerminalArgvTest} this service used to
 * hold), and the suite's fake docker matches the whole rendered line.
 *
 * <p><b>The spec IS the sandbox.</b> Nothing else constrains what a glances container may do — no
 * seccomp profile of ours, no runtime policy — so every option below is a security decision and a
 * lost one is a privilege granted silently. That is why this test asserts field by field rather
 * than comparing a whole value somebody would then "fix" by pasting the new one in.
 */
class TerminalContainersTest {

  private static final UUID SESSION = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private static final String IMAGE = "mirror.dev.localhost:8080/hub/nicolargo/glances:4.5.6-full";

  @Test
  void theGlancesRunIsSandboxed() {
    InteractiveRunSpec spec =
        TerminalContainers.glances(IMAGE, List.of(), SESSION, "qits-platform-system");

    assertTrue(spec.capDropAll());
    assertTrue(spec.noNewPrivileges());
    // Equal memory and memory-swap is what turns swap OFF. A missing --memory-swap would grant
    // unlimited swap, which is the opposite of the intent.
    assertEquals("512m", spec.memory());
    assertEquals("512m", spec.memorySwap());
    assertEquals(256L, spec.pidsLimit());
    // The kernel takes a monitor before it takes a platform service.
    assertEquals(500, spec.oomScoreAdj());
    // Read-only, because this is a container we hand the host's docker socket to.
    assertEquals(List.of("/var/run/docker.sock:/var/run/docker.sock:ro"), spec.binds());
    assertEquals(Map.of("TERM", "xterm-256color"), spec.env());
  }

  @Test
  void glancesSeesTheHostRatherThanItsOwnContainer() {
    InteractiveRunSpec spec =
        TerminalContainers.glances(IMAGE, List.of(), SESSION, "qits-platform-system");

    // In its own pid namespace glances would report one process, itself.
    assertTrue(spec.hostPid());
    // And in its own netns it would report the container's veth rather than the host's interfaces.
    // Nothing is published: in terminal mode glances runs no listener.
    assertEquals("host", spec.network());
  }

  @Test
  void theGlancesContainerCanBeFoundAgainByASweepAndByATeardown() {
    InteractiveRunSpec spec =
        TerminalContainers.glances(IMAGE, List.of(), SESSION, "qits-platform-system");

    // By name, for the teardown that has only the session id — `--rm` fires on container exit, and
    // killing the CLI is not that.
    assertEquals("qits-system-glances-" + SESSION, TerminalContainers.glancesContainerName(SESSION));
    assertEquals(TerminalContainers.glancesContainerName(SESSION), spec.name());
    // And by label, for the boot sweep that has only the owner — in this order.
    assertEquals(
        List.of(
            Map.entry("qits.system.session", SESSION.toString()),
            Map.entry("qits.system.kind", "glances"),
            Map.entry("qits.system.owner", "qits-platform-system")),
        List.copyOf(spec.labels().entrySet()));
    // The sweep filters on the owner label's VALUE, so two platforms on one daemon keep apart.
    assertEquals(
        Map.of("qits.system.owner", "qits-platform-system"),
        TerminalContainers.ownerFilter("qits-platform-system"));
  }

  @Test
  void glancesOwnFlagsFollowTheImage() {
    InteractiveRunSpec spec =
        TerminalContainers.glances(IMAGE, List.of("--disable-plugin", "cloud"), SESSION, "owner");
    assertEquals(IMAGE, spec.image());
    assertEquals(List.of("--disable-plugin", "cloud"), spec.args());

    List<String> argv =
        TerminalContainers.runGlances(
            "docker", IMAGE, List.of("--disable-plugin", "cloud"), SESSION, "owner");
    assertEquals(
        List.of(IMAGE, "--disable-plugin", "cloud"),
        argv.subList(argv.size() - 3, argv.size()),
        "glances' own flags go AFTER the image, or docker reads them as its own");
  }

  @Test
  void theExecShellCarriesTheSessionAndAShellFromTheEnum() {
    String id = "b248472f7a8e00bba8cd036232373baed8856d90eb77b06c53f3eb0b321fdb17";

    List<String> argv = TerminalContainers.execShell("docker", id, ExecShell.SH, SESSION);

    assertTrue(argv.contains("TERM=xterm-256color"), argv.toString());
    assertTrue(argv.contains("QITS_SYSTEM_SESSION=" + SESSION), argv.toString());
    assertEquals(List.of(id, "sh"), argv.subList(argv.size() - 2, argv.size()));
  }

  @Test
  void anExecTargetThatIsNotACanonicalIdNeverOpensATerminal() {
    // The library's belt on the resolution step: only what the daemon printed may reach the argv.
    assertThrows(
        IllegalArgumentException.class,
        () -> TerminalContainers.execShell("docker", "dev-qits-ci", ExecShell.BASH, SESSION));
    assertThrows(
        IllegalArgumentException.class,
        () -> TerminalContainers.execShell("docker", "b248472f7a8e", ExecShell.BASH, SESSION));
  }

  @Test
  void theShellIsAClosedSet() {
    assertEquals(ExecShell.BASH, ExecShell.parse("bash"));
    assertEquals(ExecShell.SH, ExecShell.parse("SH"));
    // Everything else is a 400 at the boundary rather than a program in an argv.
    assertEquals(null, ExecShell.parse("zsh"));
    assertEquals(null, ExecShell.parse("/bin/sh"));
    assertEquals(null, ExecShell.parse(""));
    assertEquals(null, ExecShell.parse(null));
  }
}
