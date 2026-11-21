package eu.wohlben.qits.system.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck.CheckResult;
import io.quarkus.websockets.next.HttpUpgradeCheck.HttpUpgradeContext;
import io.quarkus.websockets.next.UserData;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The terminal socket's origin rule (qits-2356), with no application booted: the check is built
 * by hand, its anchor set as config would set it, and the handshake is a fake request that answers
 * the three headers the rule reads. Not a mock — a {@link Proxy} over {@link HttpServerRequest}
 * that knows {@code getHeader} and refuses every other call, so a rule that started reading
 * something else fails here loudly.
 */
class TerminalOriginUpgradeCheckTest {

  private static final String DOMAIN = "example.test";
  private static final String ANCHOR = "qits." + DOMAIN;

  // --- admitted ---------------------------------------------------------------------------------

  @Test
  void permitsAHandshakeWithNoOrigin() {
    assertPermitted(headers(null, null, "system.qits.example.test"));
  }

  @Test
  void permitsAnOriginOnTheFirstForwardedHost() {
    assertPermitted(headers("https://a.example", "a.example, b.example", "internal:8080"));
  }

  @Test
  void permitsAnOriginOnTheHostWhenNothingWasForwarded() {
    assertPermitted(headers("http://localhost:8080", null, "localhost:8080"));
  }

  @Test
  void permitsTheLandingAppsApex() {
    assertPermitted(headers("https://" + ANCHOR, "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void permitsThisServicesOwnNameUnderTheAnchor() {
    assertPermitted(headers("https://system." + ANCHOR, "other.host", "internal:8080"));
  }

  @Test
  void permitsANameAnyDepthUnderTheAnchor() {
    assertPermitted(headers("https://a.b." + ANCHOR, "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void permitsTheAnchorInAnyCase() {
    assertPermitted(headers("HTTPS://Landing.QITS.Example.Test", "system." + ANCHOR, "internal:8080"));
  }

  // --- refused ----------------------------------------------------------------------------------

  @Test
  void refusesAForeignOrigin() {
    assertRefused(headers("https://evil.example", "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void refusesTheAnchorAsAPrefixOfAForeignName() {
    assertRefused(headers("https://" + ANCHOR + ".evil.example", "system." + ANCHOR, "internal"));
  }

  @Test
  void refusesANameThatMerelyEndsInTheAnchorsLetters() {
    assertRefused(headers("https://x" + ANCHOR, "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void refusesPlaintextHttpOnTheAnchor() {
    assertRefused(headers("http://" + ANCHOR, "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void refusesASidePortOnTheAnchor() {
    assertRefused(headers("https://" + ANCHOR + ":8443", "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void refusesUserinfoOnTheAnchor() {
    assertRefused(headers("https://someone@" + ANCHOR, "system." + ANCHOR, "internal:8080"));
  }

  @Test
  void refusesAForeignOriginMatchingOnlyALaterForwardedEntry() {
    assertRefused(headers("https://b.example", "a.example, b.example", "b.example"));
  }

  @Test
  void refusesEverythingUnderTheDomainWhenNoAnchorIsConfigured() {
    TerminalOriginUpgradeCheck check = new TerminalOriginUpgradeCheck();
    check.originDomain = "";
    CheckResult result =
        check.perform(context(headers("https://" + ANCHOR, "system." + ANCHOR, "internal"))).await()
            .atMost(Duration.ofSeconds(5));
    assertEquals(403, result.getHttpResponseCode());
  }

  // --- the fakes --------------------------------------------------------------------------------

  private static void assertPermitted(Map<String, String> headers) {
    CheckResult result = perform(headers);
    assertTrue(result.isUpgradePermitted(), () -> "refused " + headers);
  }

  private static void assertRefused(Map<String, String> headers) {
    CheckResult result = perform(headers);
    assertEquals(false, result.isUpgradePermitted(), () -> "permitted " + headers);
    assertEquals(403, result.getHttpResponseCode());
  }

  private static CheckResult perform(Map<String, String> headers) {
    TerminalOriginUpgradeCheck check = new TerminalOriginUpgradeCheck();
    check.originDomain = ANCHOR;
    return check.perform(context(headers)).await().atMost(Duration.ofSeconds(5));
  }

  /** The handshake's headers by lower-cased name; a null value is a header not sent. */
  private static Map<String, String> headers(String origin, String forwardedHost, String host) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (origin != null) {
      headers.put("origin", origin);
    }
    if (forwardedHost != null) {
      headers.put("x-forwarded-host", forwardedHost);
    }
    if (host != null) {
      headers.put("host", host);
    }
    return headers;
  }

  private static HttpUpgradeContext context(Map<String, String> headers) {
    HttpServerRequest request =
        (HttpServerRequest)
            Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getHeader")
                      && args != null
                      && args.length == 1
                      && args[0] instanceof String name) {
                    return headers.get(name.toLowerCase(Locale.ROOT));
                  }
                  if (method.getName().equals("toString")) {
                    return "fake request " + headers;
                  }
                  throw new UnsupportedOperationException(method.toString());
                });
    return new HttpUpgradeContext() {
      @Override
      public UserData userData() {
        throw new UnsupportedOperationException();
      }

      @Override
      public String pathParam(String name) {
        throw new UnsupportedOperationException();
      }

      @Override
      public HttpServerRequest httpRequest() {
        return request;
      }

      @Override
      public Uni<SecurityIdentity> securityIdentity() {
        throw new UnsupportedOperationException();
      }

      @Override
      public String endpointId() {
        throw new UnsupportedOperationException();
      }
    };
  }
}
