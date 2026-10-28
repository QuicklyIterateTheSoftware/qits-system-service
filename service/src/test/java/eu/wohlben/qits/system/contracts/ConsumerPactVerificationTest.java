package eu.wohlben.qits.system.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running service</b>, the way qits-projects-service
 * and qits-edge-service do.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-system-service.json} (repository names on both sides), this repo pins
 * that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases a
 * changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>qits-landing-app pins its pact</b> (qits-1674: the target overview's {@code GET
 * /system/api/targets}), the first consumer to, so finding no pact is an error now: there is no
 * {@code @IgnoreNoPactsToVerify} and {@link ClasspathPactLoader#REQUIRED} is true. A provider whose
 * pinned pacts vanished has lost a dependency, and a green run with nothing verified would claim
 * the opposite. Every other pact that turns up on the classpath is verified the same way.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, with the
 * fake docker script behind it. Every {@code @State} method delegates to {@link ProviderStates};
 * {@link #target} fails an unknown state, and an interaction without {@code
 * comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-system-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @jakarta.inject.Inject eu.wohlben.qits.system.targets.ContractBearers bearers;

  private ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    states = new ProviderStates(bearers, URI.create(base.toString()));
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-system does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @AfterEach
  void hangUp() {
    if (states != null) {
      states.close();
    }
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.A_SINGLE_NODE_SWARM)
  Map<String, String> aSingleNodeSwarm() {
    return states.params(ProviderStates.A_SINGLE_NODE_SWARM);
  }

  @State(ProviderStates.NO_TARGET)
  Map<String, String> noTarget() {
    return states.params(ProviderStates.NO_TARGET);
  }

  @State(ProviderStates.A_DECLARED_TARGET)
  Map<String, String> aDeclaredTarget() {
    return states.params(ProviderStates.A_DECLARED_TARGET);
  }

  @State(ProviderStates.AN_UNREGISTERED_RUNNER)
  Map<String, String> anUnregisteredRunner() {
    return states.params(ProviderStates.AN_UNREGISTERED_RUNNER);
  }

  @State(ProviderStates.A_REGISTERED_RUNNER)
  Map<String, String> aRegisteredRunner() {
    return states.params(ProviderStates.A_REGISTERED_RUNNER);
  }

  @State(ProviderStates.A_QUEUED_LEASE)
  Map<String, String> aQueuedLease() {
    return states.params(ProviderStates.A_QUEUED_LEASE);
  }

  @State(ProviderStates.AN_UNUSED_WORK_VOLUME)
  Map<String, String> anUnusedWorkVolume() {
    return states.params(ProviderStates.AN_UNUSED_WORK_VOLUME);
  }

  @State(ProviderStates.A_QUEUED_WORKSPACE_LEASE)
  Map<String, String> aQueuedWorkspaceLease() {
    return states.params(ProviderStates.A_QUEUED_WORKSPACE_LEASE);
  }

  @State(ProviderStates.A_RUNNING_WORKSPACE_LEASE)
  Map<String, String> aRunningWorkspaceLease() {
    return states.params(ProviderStates.A_RUNNING_WORKSPACE_LEASE);
  }

  @State(ProviderStates.AN_UNUSED_WORKSPACE_WORK_VOLUME)
  Map<String, String> anUnusedWorkspaceWorkVolume() {
    return states.params(ProviderStates.AN_UNUSED_WORKSPACE_WORK_VOLUME);
  }

  @State(ProviderStates.A_QUEUED_DESK_LEASE)
  Map<String, String> aQueuedDeskLease() {
    return states.params(ProviderStates.A_QUEUED_DESK_LEASE);
  }

  @State(ProviderStates.A_RUNNING_DESK_LEASE)
  Map<String, String> aRunningDeskLease() {
    return states.params(ProviderStates.A_RUNNING_DESK_LEASE);
  }

  @State(ProviderStates.AN_UNUSED_DESK_WORK_VOLUME)
  Map<String, String> anUnusedDeskWorkVolume() {
    return states.params(ProviderStates.AN_UNUSED_DESK_WORK_VOLUME);
  }

  @State(ProviderStates.A_CONNECTED_TARGET)
  Map<String, String> aConnectedTarget() {
    return states.params(ProviderStates.A_CONNECTED_TARGET);
  }

  @State(ProviderStates.A_CONNECTED_TARGET_WITH_LEASES)
  Map<String, String> aConnectedTargetWithLeases() {
    return states.params(ProviderStates.A_CONNECTED_TARGET_WITH_LEASES);
  }

  @State(ProviderStates.A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE)
  Map<String, String> aClaudeSignInAwaitingItsCode() {
    return states.params(ProviderStates.A_CLAUDE_SIGN_IN_AWAITING_ITS_CODE);
  }
}
