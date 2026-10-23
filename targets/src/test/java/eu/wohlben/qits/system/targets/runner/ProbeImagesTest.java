package eu.wohlben.qits.system.targets.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link ProbeImages#choose}: workspace, else desk, else configured, else none. */
class ProbeImagesTest {

  private static final Optional<String> WS = Optional.of("registry/qits/workspace:2026.9");
  private static final Optional<String> DESK = Optional.of("registry/qits/project-agent:2026.7");
  private static final Optional<String> SET = Optional.of("registry/qits/workspace:2026.1");
  private static final Optional<String> NONE = Optional.empty();

  @Test
  void theNewestWorkspaceLeaseWinsOverEverything() {
    assertEquals(WS, ProbeImages.choose(WS, DESK, SET));
  }

  @Test
  void elseTheNewestDeskLease() {
    assertEquals(DESK, ProbeImages.choose(NONE, DESK, SET));
  }

  @Test
  void elseTheConfiguredImage() {
    assertEquals(SET, ProbeImages.choose(NONE, NONE, SET));
    assertEquals(SET, ProbeImages.choose(Optional.of(" "), NONE, SET));
  }

  @Test
  void elseNone() {
    assertEquals(NONE, ProbeImages.choose(NONE, NONE, NONE));
  }
}
