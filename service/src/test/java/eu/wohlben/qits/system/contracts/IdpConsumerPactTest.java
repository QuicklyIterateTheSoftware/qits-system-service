package eu.wohlben.qits.system.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.system.targets.idp.TargetRunnerCommissioner;
import io.opentelemetry.api.OpenTelemetry;

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

  // --- the targets module's commissioner (qits-1481): its reads and give-backs --------------------
  //
  // TargetRunnerCommissioner's listings feed TargetCommissionReconcile, and its deletes give a
  // deleted target's credentials back. The two commissions (kinds target-runner and
  // target-runner-registration) have no recorded qits-idp state yet, so they are not rows here.

  static final Trigger RECONCILE = Trigger.schedule("qits.system.targets.commission-reconcile-interval");

  static final Trigger DELETE_TARGET = Trigger.operation("deleteTarget");

  static final String BASIC = "{authorization}";

  static final GoldenInteraction LIST_CLIENTS =
      GoldenInteraction.of(RECONCILE, "a commissioned client", "listClients")
          .header("Authorization", BASIC)
          .consumes("[].clientId", "[].contextKind", "[].contextId");

  static final GoldenInteraction LIST_TOKENS =
      GoldenInteraction.of(RECONCILE, "a commissioned token", "listTokens")
          .header("Authorization", BASIC)
          .consumes("[].tokenId", "[].contextKind", "[].contextId", "[].createdAt");

  static final GoldenInteraction DECOMMISSION_CLIENT =
      GoldenInteraction.of(DELETE_TARGET, "a commissioned client", "decommissionClient")
          .header("Authorization", BASIC);

  static final GoldenInteraction DECOMMISSION_GONE_CLIENT =
      GoldenInteraction.of(DELETE_TARGET, "no commissioned client with the given id", "decommissionClient")
          .header("Authorization", BASIC);

  static final GoldenInteraction DELETE_TOKEN =
      GoldenInteraction.of(DELETE_TARGET, "a commissioned token", "deleteToken")
          .header("Authorization", BASIC);

  static final GoldenInteraction DELETE_GONE_TOKEN =
      GoldenInteraction.of(DELETE_TARGET, "no commissioned token with the given id", "deleteToken")
          .header("Authorization", BASIC);

  static final ConsumerPact PACT =
      ConsumerPact.of(
          CONSUMER,
          IDP,
          DISCOVERY,
          JWKS,
          LIST_CLIENTS,
          LIST_TOKENS,
          DECOMMISSION_CLIENT,
          DECOMMISSION_GONE_CLIENT,
          DELETE_TOKEN,
          DELETE_GONE_TOKEN);

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
  void theReconcileReadsTheCommissionedClients() {
    PACT.run(LIST_CLIENTS, (url, recorded) -> {
      var live = commissioner(url, recorded).liveClients("contract").orElseThrow();
      assertEquals(
          IDP.json(recorded.state(), recorded.operationId()).path(0).path("clientId").asText(),
          live.get(0).clientId());
    });
  }

  @Test
  void theReconcileReadsTheCommissionedTokens() {
    PACT.run(LIST_TOKENS, (url, recorded) -> {
      var live = commissioner(url, recorded).liveTokens().orElseThrow();
      assertEquals(recorded.params().get("tokenId"), live.get(0).tokenId());
      assertNotNull(live.get(0).createdAt());
    });
  }

  @Test
  void aDeletedTargetsClientIsGivenBackAndAGoneOneIsGoneToo() {
    for (GoldenInteraction row : List.of(DECOMMISSION_CLIENT, DECOMMISSION_GONE_CLIENT)) {
      PACT.run(row, (url, recorded) ->
          assertTrue(commissioner(url, recorded).decommissionClient(recorded.params().get("clientId"))));
    }
  }

  @Test
  void aDeletedTargetsTokenIsDeletedAndAGoneOneIsGoneToo() {
    for (GoldenInteraction row : List.of(DELETE_TOKEN, DELETE_GONE_TOKEN)) {
      PACT.run(row, (url, recorded) ->
          assertTrue(commissioner(url, recorded).deleteToken(recorded.params().get("tokenId"))));
    }
  }

  /** The real commissioner, its idp the pact's mock, its Basic pair the state's caller. */
  private static TargetRunnerCommissioner commissioner(String url, GoldenMasters.Operation recorded) {
    String basic = recorded.params().get("authorization").substring("Basic ".length());
    String[] pair =
        new String(java.util.Base64.getDecoder().decode(basic), java.nio.charset.StandardCharsets.UTF_8)
            .split(":", 2);
    return new TargetRunnerCommissioner(
        QitsHttpClients.of(OpenTelemetry.noop(), CONSUMER),
        new SuiteTargetsConfig(url + "/idp", pair[0], pair[1]),
        new ObjectMapper());
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyRowIsRecordedAndReferenced() {
    assertEquals(
        List.of(
            DISCOVERY,
            JWKS,
            LIST_CLIENTS,
            LIST_TOKENS,
            DECOMMISSION_CLIENT,
            DECOMMISSION_GONE_CLIENT,
            DELETE_TOKEN,
            DELETE_GONE_TOKEN),
        PACT.recorded());
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
