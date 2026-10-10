package eu.wohlben.qits.system.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.oidc.OidcConfigurationMetadata;
import io.quarkus.oidc.runtime.JsonWebKeySet;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pact against qits-idp-service, {@code pacts/qits-system-service_qits-idp-service.json}
 * (ticket qits-1149).
 *
 * <p>With the OIDC tenant on, quarkus-oidc reads two answers at startup: the discovery document,
 * for {@code issuer}, {@code jwks_uri} and {@code token_endpoint}, and then the JWKS at that
 * {@code jwks_uri}, for each key's {@code kid}, {@code kty}, {@code n}, {@code e}, {@code alg} and
 * {@code use}. Each row binds only those fields. The issuer is bound exactly: quarkus-oidc compares
 * every token's {@code iss} to it.
 */
class IdpConsumerPactTest {

  static final String CONSUMER = "qits-system-service";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String STATE = "a published signing key";

  /** The tenant resolves its keys early (Quarkus' default), so the calls happen at startup. */
  static final Trigger AT_STARTUP = Trigger.event("StartupEvent");

  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(AT_STARTUP, STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint")
          .exact("issuer");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(AT_STARTUP, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, DISCOVERY, JWKS);

  @Test
  void quarkusOidcReadsTheDiscoveryDocument() {
    PACT.run(DISCOVERY, (url, recorded) -> {
      OidcConfigurationMetadata metadata = new OidcConfigurationMetadata(new JsonObject(get(url + recorded.path())));
      assertEquals(IDP.json(STATE, "getOpenIdConfiguration").path("issuer").asText(), metadata.getIssuer());
      assertNotNull(metadata.getJsonWebKeySetUri());
      assertNotNull(metadata.getTokenUri());
    });
  }

  @Test
  void quarkusOidcFindsThePublishedSigningKey() {
    PACT.run(JWKS, (url, recorded) -> {
      JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
      assertNotNull(keys.getKeyWithId(recorded.params().get("kid")), "the signing key named by the state's kid");
    });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void bothRowsAreRecordedAndReferenced() {
    assertEquals(List.of(DISCOVERY, JWKS), PACT.recorded());
    PACT.assertEveryInteractionCarriesBothReferences();
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> answer = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode());
    return answer.body();
  }
}
