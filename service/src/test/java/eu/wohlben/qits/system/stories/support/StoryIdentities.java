package eu.wohlben.qits.system.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;
import java.util.Map;

/**
 * The two kinds of caller this service has, and how a story hands each of them to a request.
 *
 * <h2>A person is a bearer too</h2>
 *
 * <p>{@link #operator(RequestSpecification)} presents a person's token from this run's idp: the
 * operator's name in {@code sub}, the platform audience and {@code qits:admin} in {@code groups}.
 * Forward-auth — the {@code X-Qits-User} / {@code X-Qits-Roles} pair the edge used to assert —
 * leaves qits-auth-core with qits-1338 (epic qits-543), so a signed-in person reaches this service
 * the way a machine does, and the roles are what tell them apart.
 *
 * <h2>A machine is a bearer this run's idp minted</h2>
 *
 * <p>{@link #machine(RequestSpecification)} presents a token signed by {@link MockIdp}'s generated
 * keypair, carrying the platform audience this service enforces and {@code qits:system} in {@code
 * groups}. Every token is minted <b>fresh per call and never cached</b>: a helper that handed the
 * same string to two stories would make {@code assertNotLeaked} a weaker claim than it reads as.
 *
 * <h2>Why both, in one catalogue</h2>
 *
 * <p>Because the difference between them is a rule this service actually has. Every REST route is
 * {@code @RolesAllowed({"qits:admin", "qits:system"})} — reading the host's shape is something a
 * machine may do — and the terminal socket is {@code @RolesAllowed("qits:admin")} alone, because
 * holding an interactive shell on the platform host is not. A catalogue that only ever spoke as one
 * of the two could not show where the ceiling is.
 */
public final class StoryIdentities {

  /** The role a person needs for everything here, including a shell. */
  public static final String ADMIN_ROLE = "qits:admin";

  /** The role a platform peer holds: every read, and no terminal. */
  public static final String MACHINE_ROLE = "qits:system";

  /** How the diagram names a person at the console. */
  public static final String OPERATOR = "an operator";

  /** …and a platform peer holding a bearer. */
  public static final String PEER = "a platform service";

  /** The operator, by name — the {@code sub} of the operator's token. */
  private static final String OPERATOR_NAME = "alice";

  private StoryIdentities() {}

  /** {@code given()} carrying a signed-in operator's bearer. */
  public static RequestSpecification operator(RequestSpecification request) {
    return withRole(request, ADMIN_ROLE);
  }

  /** The same person, with a role this service's routes have never heard of. */
  public static RequestSpecification withRole(RequestSpecification request, String role) {
    return bearer(request, personToken(role));
  }

  /** The operator's bearer, on a WebSocket handshake. */
  public static Map<String, String> operatorHeaders() {
    return headersWithRole(ADMIN_ROLE);
  }

  /** The handshake headers of a person holding some other role. */
  public static Map<String, String> headersWithRole(String role) {
    return Map.of("Authorization", "Bearer " + personToken(role));
  }

  /** A freshly minted person's bearer: the operator's name, the platform audience, {@code role}. */
  private static String personToken(String role) {
    return MockIdp.attach()
        .token()
        .subject(OPERATOR_NAME)
        .audience(StoryProfile.AUDIENCE)
        .groups(role)
        .mint();
  }

  /** A freshly minted platform-peer bearer: the platform audience, {@code qits:system}. */
  public static String machineToken() {
    return MockIdp.attach()
        .token()
        .subject("a-platform-service")
        .audience(StoryProfile.AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} carrying a bearer the story already holds — it has to pin it as not leaked. */
  public static RequestSpecification bearer(RequestSpecification request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
