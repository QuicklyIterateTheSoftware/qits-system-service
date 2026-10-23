package eu.wohlben.qits.system.targets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * qits-idp's commission API as the targets module calls it — {@code /idp/api/clients} and {@code
 * /idp/api/tokens}, HTTP Basic — on a JDK {@link HttpServer} with a free port, so the create and
 * register doors and the reconcile run their real HTTP. Not a mock of a class: the commissioner's
 * whole request path is exercised, and the stub only answers in qits-idp's shapes.
 *
 * <p>It is also the {@code qits.system.targets.idp.url} config source. A Quarkus test run loads
 * config sources in more than one classloader, so the one server is found through a system property
 * and driven over HTTP ({@code POST /stub/reset}, {@code GET /stub/requests}), never through a
 * static another copy of this class cannot see.
 */
public class IdpStub implements ConfigSource {

  private static final String PORT_PROPERTY = "qits.test.idp-stub.port";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The answers' counters and live credentials. */
  private static final class State {
    int tokens;
    int clients;
    final Map<String, Map<String, Object>> liveTokens = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> liveClients = new LinkedHashMap<>();
    final List<String> requests = new ArrayList<>();
  }

  private static State state = new State();

  private static HttpServer server;

  /** The stub's base, {@code http://127.0.0.1:<port>/idp}, starting it on the first call. */
  public static synchronized String baseUrl() {
    String recorded = System.getProperty(PORT_PROPERTY);
    if (recorded == null) {
      start();
      recorded = System.getProperty(PORT_PROPERTY);
    }
    return "http://127.0.0.1:" + recorded + "/idp";
  }

  /** Forgets every credential and request and restarts the counters, whichever copy started it. */
  public static void reset() {
    control("POST", "/stub/reset");
  }

  /** Every request the stub answered since the last reset, as {@code METHOD path}. */
  @SuppressWarnings("unchecked")
  public static List<String> requests() {
    try {
      return JSON.readValue(control("GET", "/stub/requests"), List.class);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String control(String method, String path) {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl().replace("/idp", "") + path))
              .method(method, HttpRequest.BodyPublishers.noBody())
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void start() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start the idp stub", e);
    }
    server.setExecutor(Executors.newFixedThreadPool(4, r -> {
      Thread t = new Thread(r, "idp-stub");
      t.setDaemon(true);
      return t;
    }));
    server.createContext("/", IdpStub::handle);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
  }

  private static synchronized void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    try {
      if (path.equals("/stub/reset")) {
        state = new State();
        answer(exchange, 204, null);
        return;
      }
      if (path.equals("/stub/requests")) {
        answer(exchange, 200, JSON.writeValueAsString(state.requests));
        return;
      }
      state.requests.add(method + " " + path);
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      if (auth == null || !auth.startsWith("Basic ")) {
        answer(exchange, 401, "{\"message\":\"no client\"}");
        return;
      }
      if (path.equals("/idp/api/tokens") && method.equals("POST")) {
        Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
        int n = ++state.tokens;
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("tokenId", "tok-" + n);
        token.put("subject", "tok-subject-" + n);
        token.put("contextKind", body.get("contextKind"));
        token.put("contextId", body.get("contextId"));
        token.put("createdAt", "2026-01-01T00:00:00Z");
        state.liveTokens.put("tok-" + n, token);
        Map<String, Object> answer = new LinkedHashMap<>(token);
        answer.put("token", "qits_tok_suite-" + n);
        answer(exchange, 201, JSON.writeValueAsString(answer));
      } else if (path.equals("/idp/api/tokens") && method.equals("GET")) {
        answer(exchange, 200, JSON.writeValueAsString(state.liveTokens.values()));
      } else if (path.startsWith("/idp/api/tokens/") && method.equals("DELETE")) {
        boolean gone = state.liveTokens.remove(path.substring("/idp/api/tokens/".length())) != null;
        answer(exchange, gone ? 204 : 404, null);
      } else if (path.equals("/idp/api/clients") && method.equals("POST")) {
        Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
        int n = ++state.clients;
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", "target-runner-client-" + n);
        client.put("contextKind", body.get("contextKind"));
        client.put("contextId", body.get("contextId"));
        state.liveClients.put("target-runner-client-" + n, client);
        Map<String, Object> answer = new LinkedHashMap<>(client);
        answer.put("secret", "target-runner-s3cr3t-" + n);
        answer(exchange, 201, JSON.writeValueAsString(answer));
      } else if (path.equals("/idp/api/clients") && method.equals("GET")) {
        answer(exchange, 200, JSON.writeValueAsString(state.liveClients.values()));
      } else if (path.startsWith("/idp/api/clients/") && method.equals("DELETE")) {
        boolean gone =
            state.liveClients.remove(path.substring("/idp/api/clients/".length())) != null;
        answer(exchange, gone ? 204 : 404, null);
      } else {
        answer(exchange, 404, "{\"message\":\"not a stub door\"}");
      }
    } catch (RuntimeException e) {
      answer(exchange, 500, "{\"message\":\"" + e + "\"}");
    }
  }

  private static void answer(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  // --- the config source ------------------------------------------------------------------------

  private static final String KEY = "qits.system.targets.idp.url";

  @Override
  public Set<String> getPropertyNames() {
    return Set.of(KEY);
  }

  @Override
  public String getValue(String name) {
    return KEY.equals(name) ? baseUrl() : null;
  }

  @Override
  public String getName() {
    return "idp-stub";
  }

  @Override
  public int getOrdinal() {
    return 450;
  }
}
