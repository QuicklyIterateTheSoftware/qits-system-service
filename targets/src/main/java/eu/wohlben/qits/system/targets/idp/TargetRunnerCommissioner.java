package eu.wohlben.qits.system.targets.idp;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.system.targets.control.TargetsConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The credentials a target's runner is commissioned at qits-idp: its one-use registration TOKEN,
 * minted when an operator declares the target (and at every rotation), and its own CLIENT, minted by
 * the register door. Both are given back when the target is deleted. The pattern of
 * qits-workspaces-service's {@code wiring/IdpRunnerCommissioner} — and of its copy in
 * qits-projects-service, whose plain {@link HttpClient} this one shares, because this service has no
 * REST client of its own.
 *
 * <p><b>Two kinds, and qits-idp maps each to one role</b> ({@code CommissionRoles}, qits-1523): a
 * {@link #REGISTRATION_KIND} token grants {@code qits:target-runner-registration}, which opens the
 * register door and {@code install.sh} and nothing else; a {@link #RUNNER_KIND} client grants
 * {@code qits:target-runner}, which opens the runner socket. Both are named by the target's id as
 * their context, which is what {@code TargetCommissionReconcile} judges them against. Neither
 * states a project claim or Git refs: a runner acts in no project and pushes nothing.
 *
 * <p><b>Authorization is HTTP Basic with this service's own idp client</b> ({@code idp:client} in
 * deployments.yml, {@code QITS_RESOURCE_IDP_CLIENT_ID}/{@code _SECRET}), a static service client,
 * which qits-idp's {@code BasicCaller.staticOnly} admits for commissioning.
 *
 * <p><b>Commissioning is patient and throws; giving back is one attempt and never throws.</b> A
 * commission is somebody waiting on an answer, asked again across an idp restart for {@code
 * qits.system.targets.idp.patience}; a give-back runs after the row is already gone, so a failure
 * leaves an orphan for the reconcile rather than an error for nobody. With no secret {@link
 * #enabled()} is false: a commission throws, and every give-back and listing does nothing.
 */
@ApplicationScoped
public class TargetRunnerCommissioner {

  private static final Logger LOG = Logger.getLogger(TargetRunnerCommissioner.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

  /** A target runner's own client, named by the target's id. */
  public static final String RUNNER_KIND = "target-runner";

  /** A target runner's one-use registration token, named by the target's id. */
  public static final String REGISTRATION_KIND = "target-runner-registration";

  /** A commission that could not be made: not wired, or every attempt the window allowed failed. */
  public static class CommissionFailedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CommissionFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A runner's client. The secret is answered once, by qits-idp. */
  public record RunnerClient(String clientId, String secret) {
    @Override
    public String toString() {
      return "RunnerClient[clientId=" + clientId + "]";
    }
  }

  /** A commissioned token; {@code token} is the value, answered once and never logged. */
  public record IssuedToken(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      return "IssuedToken[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /** One live client of this service's, as the reconcile reads it. Never a secret. */
  public record LiveClient(String clientId, String contextKind, String contextId) {}

  /** One live token of this service's, as the reconcile reads it. Never a value. */
  public record LiveToken(String tokenId, String contextKind, String contextId, Instant createdAt) {}

  /** A failed attempt; {@code retryable} when it was about the moment (unreachable, 5xx, 401). */
  static final class AttemptFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    final boolean retryable;

    AttemptFailed(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }
  }

  @Inject TargetsConfig config;

  @Inject ObjectMapper objectMapper;

  private final HttpClient client;

  @Inject
  public TargetRunnerCommissioner(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** Outside CDI: the consumer pact drives the real HTTP against qits-idp's recorded answers. */
  public TargetRunnerCommissioner(
      QitsHttpClients clients, TargetsConfig config, ObjectMapper objectMapper) {
    this(clients);
    this.config = config;
    this.objectMapper = objectMapper;
  }

  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  /** Whether this process can commission at all: an id and a secret. */
  public boolean enabled() {
    return set(config.idp().clientId()).isPresent() && set(config.idp().clientSecret()).isPresent();
  }

  /** The registration token for {@code targetId}. */
  public IssuedToken registrationToken(UUID targetId) {
    String contextId = targetId.toString();
    requireEnabled(REGISTRATION_KIND, contextId);
    return patiently(
        "a " + REGISTRATION_KIND + " token for " + contextId,
        () -> commissionToken(REGISTRATION_KIND, contextId));
  }

  /** The runner's own client: kind {@link #RUNNER_KIND}, context {@code targetId}. */
  public RunnerClient runnerClient(UUID targetId) {
    String contextId = targetId.toString();
    requireEnabled(RUNNER_KIND, contextId);
    return patiently(
        "a " + RUNNER_KIND + " client for " + contextId, () -> commissionClient(contextId));
  }

  /** Give a runner's client back. One attempt; 404 is success; answers whether it is gone. */
  public boolean decommissionClient(String clientId) {
    return deleteAt(clientsUrl(), clientId, "runner client");
  }

  /** Delete a token. One attempt; 404 is success; answers whether it is gone. */
  public boolean deleteToken(String tokenId) {
    return deleteAt(tokensUrl(), tokenId, "token");
  }

  /** Every live client of {@code kind} this service owns, or EMPTY when it could not be read. */
  public Optional<List<LiveClient>> liveClients(String kind) {
    Optional<List<Map<?, ?>>> rows = listAt(clientsUrl(), "clients");
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    List<LiveClient> live = new ArrayList<>();
    for (Map<?, ?> fields : rows.get()) {
      String rowKind = text(fields.get("contextKind"));
      String id = text(fields.get("clientId"));
      if (kind.equals(rowKind) && !blank(id)) {
        live.add(new LiveClient(id, rowKind, text(fields.get("contextId"))));
      }
    }
    return Optional.of(List.copyOf(live));
  }

  /**
   * Every live token this service owns, or EMPTY when it could not be read. Empty and an empty list
   * are different answers on purpose: the reconcile deletes from what this returns.
   */
  public Optional<List<LiveToken>> liveTokens() {
    Optional<List<Map<?, ?>>> rows = listAt(tokensUrl(), "tokens");
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    List<LiveToken> live = new ArrayList<>();
    for (Map<?, ?> fields : rows.get()) {
      String id = text(fields.get("tokenId"));
      if (!blank(id)) {
        live.add(
            new LiveToken(
                id,
                text(fields.get("contextKind")),
                text(fields.get("contextId")),
                instant(text(fields.get("createdAt")))));
      }
    }
    return Optional.of(List.copyOf(live));
  }

  // --- the calls --------------------------------------------------------------------------------

  private IssuedToken commissionToken(String kind, String contextId) {
    Map<?, ?> answer = post(tokensUrl(), kind, contextId);
    String tokenId = text(answer.get("tokenId"));
    String token = text(answer.get("token"));
    String subject = text(answer.get("subject"));
    if (blank(tokenId) || blank(token) || blank(subject)) {
      throw new AttemptFailed(
          "qits-idp answered a token commission for " + contextId + " with no usable token",
          false,
          null);
    }
    return new IssuedToken(tokenId, token, subject);
  }

  private RunnerClient commissionClient(String contextId) {
    Map<?, ?> answer = post(clientsUrl(), RUNNER_KIND, contextId);
    String id = text(answer.get("clientId"));
    String secret = text(answer.get("secret"));
    if (blank(id) || blank(secret)) {
      throw new AttemptFailed(
          "qits-idp answered a client commission for " + contextId + " with no usable pair",
          false,
          null);
    }
    return new RunnerClient(id, secret);
  }

  private Map<?, ?> post(String url, String kind, String contextId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contextKind", kind);
    body.put("contextId", contextId);
    String doing = "commissioning a " + kind + " for " + contextId;
    String json;
    try {
      json = objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new AttemptFailed("Could not build the commission request", false, e);
    }
    HttpResponse<String> response =
        send(
            request(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            doing);
    int status = response.statusCode();
    if (status != 201 && status != 200) {
      throw new AttemptFailed(
          "qits-idp answered " + status + " " + doing + ": " + response.body(),
          status == 401 || status >= 500,
          null);
    }
    try {
      return objectMapper.readValue(response.body(), Map.class);
    } catch (IOException e) {
      throw new AttemptFailed("Could not read the commission answer", false, e);
    }
  }

  private boolean deleteAt(String base, String id, String what) {
    if (!enabled() || blank(id)) {
      return false;
    }
    try {
      HttpResponse<String> response =
          send(
              request(base + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8)).DELETE().build(),
              "deleting " + what + " " + id);
      int status = response.statusCode();
      if (status == 204 || status == 200 || status == 404) {
        return true;
      }
      LOG.warnf(
          "qits-idp answered %d while deleting %s %s; the reconcile will reap it", status, what, id);
    } catch (AttemptFailed unreachable) {
      LOG.warnf(
          "Could not reach qits-idp to delete %s %s; the reconcile will reap it: %s",
          what, id, unreachable.getMessage());
    }
    return false;
  }

  private Optional<List<Map<?, ?>>> listAt(String url, String what) {
    if (!enabled()) {
      return Optional.empty();
    }
    try {
      HttpResponse<String> response =
          send(
              request(url).header("Accept", "application/json").GET().build(),
              "listing this service's " + what);
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-idp answered %d listing this service's %s", response.statusCode(), what);
        return Optional.empty();
      }
      List<?> rows = objectMapper.readValue(response.body(), List.class);
      if (rows == null) {
        return Optional.empty();
      }
      List<Map<?, ?>> maps = new ArrayList<>();
      for (Object row : rows) {
        if (row instanceof Map<?, ?> fields) {
          maps.add(fields);
        }
      }
      return Optional.of(maps);
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Could not list this service's %s at qits-idp: %s", what, e.toString());
      return Optional.empty();
    }
  }

  private <T> T patiently(String what, Supplier<T> attempt) {
    Duration patience = config.idp().patience();
    Instant giveUpAt = Instant.now().plus(patience);
    Duration pause = RETRY_PAUSE.compareTo(patience) > 0 ? patience : RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        return attempt.get();
      } catch (AttemptFailed e) {
        if (!e.retryable || !Instant.now().isBefore(giveUpAt) || !sleep(pause)) {
          throw new CommissionFailedException(
              "Could not commission "
                  + what
                  + " after "
                  + attempts
                  + " attempt(s): "
                  + e.getMessage(),
              e);
        }
        LOG.infof("Attempt %d to commission %s did not land (%s); asking again", attempts, what,
            e.getMessage());
      }
    }
  }

  private void requireEnabled(String kind, String contextId) {
    if (!enabled()) {
      throw new CommissionFailedException(
          "Cannot commission a "
              + kind
              + " credential for "
              + contextId
              + ": this service has no idp client secret (qits.system.targets.idp.*)",
          null);
    }
  }

  private String base() {
    String url = config.idp().url();
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  String clientsUrl() {
    return base() + "/api/clients";
  }

  String tokensUrl() {
    return base() + "/api/tokens";
  }

  private HttpRequest.Builder request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(config.idp().requestTimeout())
        .header("Authorization", basic());
  }

  private String basic() {
    String pair = set(config.idp().clientId()).orElse("") + ":" + set(config.idp().clientSecret()).orElse("");
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> send(HttpRequest request, String doing) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AttemptFailed("qits-idp unreachable " + doing + ": " + e, true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AttemptFailed("Interrupted " + doing, false, e);
    }
  }

  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static Instant instant(String text) {
    if (blank(text)) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }
}
