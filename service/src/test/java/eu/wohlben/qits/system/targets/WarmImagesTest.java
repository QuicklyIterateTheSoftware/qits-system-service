package eu.wohlben.qits.system.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.system.targets.control.WarmImages;
import eu.wohlben.qits.system.targets.entity.SlotType;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.entity.WarmImage;
import eu.wohlben.qits.system.targets.events.SoftwareReleaseListener;
import eu.wohlben.qits.system.targets.runner.TargetFrames;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Warm images (qits-1692): a {@code SoftwareRelease} of a slot image moves that type's warm image,
 * forward only; a target's estate carries the warm images of the types it can ever run; the row
 * outlives the process; and a move pushes a fresh estate to the runners it concerns.
 */
@QuarkusTest
class WarmImagesTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final String WORKSPACE = "registry.qits.wohlben.eu/qits/workspace";

  private static final String CI = "registry.qits.wohlben.eu/qits/build-images/ci-slot";

  private static final String DESK = "registry.qits.wohlben.eu/qits/project-agent";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject SoftwareReleaseListener listener;

  @Inject WarmImages warmImages;

  @Inject TargetAllocation allocation;

  @Inject TargetFrames frames;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  /** A {@code SoftwareRelease} frame as qits-events delivers one, with qits-ci's whole payload. */
  private static EventFrame release(String packageType, String packageName, String version) {
    String payload =
        "{\"packageName\":\""
            + packageName
            + "\",\"packageType\":\""
            + packageType
            + "\",\"projectId\":\"p-1\",\"repository\":\"r-1\",\"runId\":\""
            + UUID.randomUUID()
            + "\",\"section\":\"artifacts\",\"version\":\""
            + version
            + "\"}";
    return new EventFrame(
        UUID.randomUUID().toString(),
        "SoftwareRelease",
        Instant.now(),
        payload,
        null,
        null,
        "dev");
  }

  private void offer(EventFrame frame) {
    if (listener.selects(frame)) {
      listener.onFrame(frame);
    }
  }

  private static void reservations(UUID targetId, int ci, int workspace, int desk) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Target target = Target.findById(targetId);
              target.reservedCi = ci;
              target.reservedWorkspace = workspace;
              target.reservedDesk = desk;
            });
  }

  // --- the release ------------------------------------------------------------------------------

  @Test
  void aReleaseOfTheWorkspaceImageMovesTheWarmWorkspaceImage() {
    offer(release("docker", "qits/workspace", "2026.1027.193351"));
    assertEquals(Map.of(SlotType.WORKSPACE, WORKSPACE + ":2026.1027.193351"), warmImages.current());
  }

  @Test
  void eachSlotImageIsMatchedToItsOwnType() {
    offer(release("docker", "qits/build-images/ci-slot", "2026.1027.1"));
    offer(release("docker", "qits/project-agent", "2026.1027.2"));
    assertEquals(
        Map.of(SlotType.CI, CI + ":2026.1027.1", SlotType.DESK, DESK + ":2026.1027.2"),
        warmImages.current());
  }

  @Test
  void anotherPackageOrAnotherTypeMovesNothing() {
    offer(release("docker", "qits/qits-ci", "2026.1027.193351"));
    offer(release("docker", "ci-slot", "2026.1027.193351"));
    offer(release("npm", "qits/workspace", "2026.1027.193351"));
    offer(release("maven", "eu.wohlben.qits:qits-eventstream", "2026.1027.193351"));
    assertEquals(Map.of(), warmImages.current());
  }

  @Test
  void anOlderVersionArrivingLateMovesNothing() {
    offer(release("docker", "qits/workspace", "2026.1027.193351"));
    // A text comparison would call 93000 newer than 193351; per part as numbers it is older.
    offer(release("docker", "qits/workspace", "2026.1027.93000"));
    offer(release("docker", "qits/workspace", "2026.927.235959"));
    offer(release("docker", "qits/workspace", "2026.1027.193351"));
    assertEquals(WORKSPACE + ":2026.1027.193351", warmImages.current().get(SlotType.WORKSPACE));
    offer(release("docker", "qits/workspace", "2026.1028.10"));
    assertEquals(WORKSPACE + ":2026.1028.10", warmImages.current().get(SlotType.WORKSPACE));
  }

  @Test
  void anUnreadablePayloadIsSettledNotThrown() {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(), "SoftwareRelease", Instant.now(), "{not json", null, null,
            "dev"));
    listener.onFrame(release("docker", "qits/workspace", " "));
    assertEquals(Map.of(), warmImages.current());
  }

  /**
   * Restart persistence: the warm image is a row, read back in a fresh transaction with nothing
   * held in memory — what a restarted process reads.
   */
  @Test
  void theWarmImageIsARowThatOutlivesTheProcess() {
    offer(release("docker", "qits/workspace", "2026.1027.193351"));
    WarmImage row =
        QuarkusTransaction.requiringNew().call(() -> WarmImage.findById(SlotType.WORKSPACE));
    assertEquals("qits/workspace", row.packageName);
    assertEquals("2026.1027.193351", row.version);
    assertEquals(WORKSPACE + ":2026.1027.193351", row.image);
    assertEquals(
        Map.of(SlotType.WORKSPACE, WORKSPACE + ":2026.1027.193351"),
        QuarkusTransaction.requiringNew().call(WarmImages::currentInTransaction));
  }

  // --- the estate -------------------------------------------------------------------------------

  @Test
  void theEstateCarriesTheWarmImagesOfTheTypesTheTargetCanRun() throws Exception {
    UUID anything = UUID.randomUUID();
    UUID ciOnly = UUID.randomUUID();
    UUID agentsOnly = UUID.randomUUID();
    TargetRows.target(anything, "anything", 4, null, false);
    TargetRows.target(ciOnly, "ci-only", 2, null, false);
    reservations(ciOnly, 2, 0, 0);
    TargetRows.target(agentsOnly, "platform", 4, null, false);
    reservations(agentsOnly, 0, 2, 2);
    offer(release("docker", "qits/workspace", "2026.1027.193351"));
    offer(release("docker", "qits/build-images/ci-slot", "2026.1027.1"));
    offer(release("docker", "qits/project-agent", "2026.1027.2"));

    assertEquals(
        List.of(CI + ":2026.1027.1", WORKSPACE + ":2026.1027.193351", DESK + ":2026.1027.2"),
        allocation.estateOf(anything).warmImages());
    assertEquals(List.of(CI + ":2026.1027.1"), allocation.estateOf(ciOnly).warmImages());
    assertEquals(
        List.of(WORKSPACE + ":2026.1027.193351", DESK + ":2026.1027.2"),
        allocation.estateOf(agentsOnly).warmImages());

    JsonNode wire = JSON.readTree(frames.encodeEstate(allocation.estateOf(ciOnly)));
    assertEquals("estate", wire.get("type").asText());
    assertTrue(wire.get("leases").isArray());
    assertEquals(1, wire.get("warmImages").size());
    assertEquals(CI + ":2026.1027.1", wire.get("warmImages").get(0).asText());
  }

  @Test
  void anEstateWithNothingWarmSaysSoWithAnEmptyList() throws Exception {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 4, null, false);
    JsonNode wire = JSON.readTree(frames.encodeEstate(allocation.estateOf(target)));
    assertTrue(wire.get("warmImages").isArray());
    assertEquals(0, wire.get("warmImages").size());
  }

  @Test
  void aMovedWarmImageIsPushedToTheConnectedRunnersThatCanRunIt() throws Exception {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, CLIENT, true);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
    try (FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER)) {
      runner.hello(4, "qits-system-runner-dot-claude-lab");
      runner.await(Ack.class);
      runner.await(Quarantined.class);
      runner.await(Estate.class);
      assertEquals(List.of(), runner.awaitWarmImages(images -> true));
      HealthCheck check = runner.await(HealthCheck.class);
      runner.healthChecked(check.requestId(), true);
      runner.await(Reinstated.class);

      offer(release("docker", "qits/workspace", "2026.1027.193351"));

      assertEquals(
          List.of(WORKSPACE + ":2026.1027.193351"),
          runner.awaitWarmImages(images -> images != null && !images.isEmpty()),
          "the release pushes a fresh estate naming the image");
    }
  }

  @Test
  void aReleaseNoTargetCanRunPushesNothingButIsStillKept() {
    UUID target = UUID.randomUUID();
    TargetRows.target(target, "ci-only", 2, null, false);
    reservations(target, 2, 0, 0);
    offer(release("docker", "qits/project-agent", "2026.1027.2"));
    assertEquals(List.of(), allocation.estateOf(target).warmImages());
    assertEquals(DESK + ":2026.1027.2", warmImages.current().get(SlotType.DESK));
    assertNull(warmImages.current().get(SlotType.WORKSPACE));
  }
}
