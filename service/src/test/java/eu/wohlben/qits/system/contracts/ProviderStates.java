package eu.wohlben.qits.system.contracts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-system's provider states</b> (ticket qits-1149): each names one situation a consumer
 * needs and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>The docker daemon is the state.</b> This service keeps no data of its own: every read is a
 * docker call, and the suite answers those with the fake docker script ({@code
 * src/test/resources/fake-docker/docker}, wired by {@code FakeDockerConfigSource}). So a state sets
 * nothing up. It names the objects that script knows, which is what a consumer needs to build its
 * paths.
 */
public class ProviderStates {

  /** One manager node, two services, one config, one secret and running containers on this node. */
  public static final String A_SINGLE_NODE_SWARM = "a single-node swarm";

  /** The node the fake {@code docker info} reports as this one. */
  static final String NODE_ID = "iwbwrdui2z0n62kqcjfo1erwh";

  /** A service the fake {@code docker service inspect} knows. */
  static final String SERVICE_ID = "dev-qits-artifacts";

  /** A config the fake {@code docker config inspect} knows. */
  static final String CONFIG_ID = "qits-edge-vhosts";

  /** A container the fake {@code docker container inspect} and {@code docker logs} know. */
  static final String CONTAINER_ID = "dev-qits-ci.1.k4g0nn1ld7o272jl7fciatg6m";

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_SINGLE_NODE_SWARM, ProviderStates::aSingleNodeSwarm);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  private static Setup aSingleNodeSwarm() {
    Map<String, String> params = new TreeMap<>();
    params.put("nodeId", NODE_ID);
    params.put("serviceId", SERVICE_ID);
    params.put("configId", CONFIG_ID);
    params.put("containerId", CONTAINER_ID);
    return new Setup(Collections.unmodifiableMap(params));
  }
}
