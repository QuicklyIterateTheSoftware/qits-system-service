package eu.wohlben.qits.system.targets.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.system.targets.control.LeaseSpecs;
import eu.wohlben.qits.system.targets.control.TargetAllocation;
import eu.wohlben.qits.systemrunner.protocol.Adopt;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseSpec;
import eu.wohlben.qits.systemrunner.protocol.Limits;
import eu.wohlben.qits.systemrunner.protocol.SlotType;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCodec;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerMessage;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bridge between a runner frame and its JSON text — qits-workspaces-service's {@code
 * WorkspaceRunnerMessageCodec} for this socket: qits-runner-protocol's {@link RunnerCodec} for the
 * lifecycle frames, handing every other {@code type} to qits-system-runner-protocol's {@link
 * SystemRunnerCodec}. The wire is spelled in the protocol jars and never here; this class only
 * bolts on Jackson, and turns the allocator's {@link TargetAllocation.EstateView} and a stored spec
 * into the protocol's records.
 */
@ApplicationScoped
public class TargetFrames {

  static final RunnerCodec<SystemRunnerMessage> CODEC =
      new RunnerCodec<>(SystemRunnerProtocol.VOCABULARY, new SystemRunnerCodec());

  @Inject ObjectMapper objectMapper;

  public String encode(RunnerMessage message) {
    try {
      return objectMapper.writeValueAsString(CODEC.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode a system-runner frame", e);
    }
  }

  /**
   * The {@code estate} frame's JSON text for an estate the allocator read: the protocol jar's own
   * encoding of {@link #estate}, plus {@value #WARM_IMAGES} (qits-1692).
   *
   * <p><b>The seam, and why it is here.</b> {@code warmImages} — the full image references the
   * runner keeps pulled — is part of the system-runner wire contract, but the {@code
   * qits-system-runner-protocol} this service pins predates it: its {@link Estate} record has no
   * such component and its {@link SystemRunnerCodec} writes no such field. So the field is added to
   * the codec's map here, after the jar has spelled everything else, and the text that leaves is
   * the jar's frame with one more key. A runner on the pinned protocol ignores it (its codec reads
   * through {@code WireMaps}, which ignores unknown fields); a runner on the new one reads it. Always
   * written, as a list and never null: an empty list means "keep nothing warm". Once a protocol jar
   * whose {@code Estate} carries {@code warmImages} is pinned, this method becomes {@code
   * encode(estate(view))} with {@link #estate} passing {@code view.warmImages()} to the record, and
   * the constant goes.
   */
  public String encodeEstate(TargetAllocation.EstateView view) {
    Map<String, Object> map = new LinkedHashMap<>(CODEC.encode(estate(view)));
    map.put(WARM_IMAGES, new ArrayList<>(view.warmImages()));
    try {
      return objectMapper.writeValueAsString(map);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode a system-runner estate frame", e);
    }
  }

  /** The {@code estate} field the pinned protocol jar does not spell yet; see {@link #encodeEstate}. */
  public static final String WARM_IMAGES = "warmImages";

  @SuppressWarnings("unchecked")
  public RunnerMessage decode(String json) {
    try {
      return CODEC.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode a system-runner frame", e);
    }
  }

  /**
   * The {@code estate} frame for an estate the allocator read. {@code workVolume} is the lease's
   * WORK KEY: the runner derives {@code qits-system-runner-work-<workKey>-<id8>} itself, or mounts
   * {@code adopt.volume}, which an adopted lease carries for its whole life.
   */
  public Estate estate(TargetAllocation.EstateView view) {
    List<LeaseEntry> leases = new ArrayList<>();
    for (TargetAllocation.EstateEntry entry : view.leases()) {
      JsonNode adopt = LeaseSpecs.parse(entry.adopt());
      leases.add(
          new LeaseEntry(
              entry.leaseId().toString(),
              SlotType.valueOf(entry.slotType().name()),
              DesiredState.valueOf(entry.desired()),
              entry.spec() == null ? null : spec(LeaseSpecs.parse(entry.spec())),
              entry.specHash(),
              entry.workKey(),
              adopt == null
                  ? null
                  : new Adopt(text(adopt, "container"), text(adopt, "volume"))));
    }
    return new Estate(leases, view.purges());
  }

  /**
   * A stored spec as the protocol's {@link LeaseSpec}. The spec was checked into this shape at the
   * door ({@link LeaseSpecs#require}); this is the reading of it.
   */
  public static LeaseSpec spec(JsonNode spec) {
    Map<String, String> env = new LinkedHashMap<>();
    JsonNode envNode = spec.get("env");
    if (envNode != null && envNode.isObject()) {
      envNode.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
    }
    JsonNode user = spec.get("user");
    JsonNode limits = spec.get("limits");
    Limits bounds = Limits.NONE;
    if (limits != null && limits.isObject()) {
      bounds =
          new Limits(
              text(limits, "memory"),
              text(limits, "memorySwap"),
              limits.hasNonNull("pidsLimit") ? limits.get("pidsLimit").asLong() : null,
              text(limits, "cpus"),
              limits.hasNonNull("oomScoreAdj") ? limits.get("oomScoreAdj").asInt() : null);
    }
    return new LeaseSpec(
        spec.get("image").asText(), env, user == null || user.isNull() ? null : user.asText(), bounds);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
