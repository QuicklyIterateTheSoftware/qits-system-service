package eu.wohlben.qits.system.targets;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
import eu.wohlben.qits.systemrunner.protocol.HealthChecked;
import eu.wohlben.qits.systemrunner.protocol.Inventory;
import eu.wohlben.qits.systemrunner.protocol.LeaseReport;
import eu.wohlben.qits.systemrunner.protocol.LeaseState;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCapabilities;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCodec;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerMessage;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * A qits-system-runner as the socket sees one: a real WebSocket to {@code /system/targets/socket},
 * speaking the runner protocol through the same codecs the runner uses, and keeping every frame the
 * server sent so a test can wait for the one it is about.
 */
public final class FakeTargetRunner implements AutoCloseable {

  static final Duration TIMEOUT = Duration.ofSeconds(20);

  static final RunnerCodec<SystemRunnerMessage> CODEC =
      new RunnerCodec<>(SystemRunnerProtocol.VOCABULARY, new SystemRunnerCodec());

  private static final ObjectMapper JSON = new ObjectMapper();

  private final WebSocket socket;
  private final LinkedBlockingQueue<RunnerMessage> inbox = new LinkedBlockingQueue<>();
  private final List<RunnerMessage> seen = new ArrayList<>();
  private final CountDownLatch closed = new CountDownLatch(1);
  private final AtomicInteger closeCode = new AtomicInteger(-1);
  private final AtomicReference<String> closeReason = new AtomicReference<>("");

  private FakeTargetRunner(URI uri, String authorization) {
    WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
    builder.header("Authorization", authorization);
    this.socket = builder.buildAsync(uri, new Collector()).join();
  }

  /** Dials {@code base}'s target socket with {@code authorization}. */
  public static FakeTargetRunner dial(URI base, String authorization) {
    return new FakeTargetRunner(
        URI.create("ws://" + base.getAuthority() + SystemRunnerProtocol.SOCKET_PATH), authorization);
  }

  public void hello(int slots, String credentialVolume) {
    hello(SystemRunnerBinary.VERSION, slots, credentialVolume);
  }

  public void hello(String version, int slots, String credentialVolume) {
    SystemRunnerCapabilities capabilities =
        new SystemRunnerCapabilities(
            true, "amd64", "linux", slots, "ENGINE-1", "node-1", credentialVolume, true);
    send(
        new Hello(
            version,
            SystemRunnerProtocol.CAPABILITY_VERSION,
            slots,
            capabilities.toWire(),
            List.of(),
            Map.of()));
  }

  public void heartbeat() {
    send(new Heartbeat());
  }

  public void loginState(LoginPresence claude, LoginPresence kimi, String volume) {
    send(new LoginState(claude, kimi, "2026-10-23T08:00:00Z", volume));
  }

  /** A sign-in moved, as the runner reports it. */
  public void signInProgress(
      String signInId, String harness, SignInState state, String url, String userCode, String detail) {
    send(new SignInProgress(signInId, harness, state, url, userCode, detail));
  }

  public void inventory(List<LeaseReport> leases, List<String> volumes) {
    send(new Inventory(leases, volumes));
  }

  public static LeaseReport report(String leaseId, LeaseState state) {
    return new LeaseReport(leaseId, state, null, state == LeaseState.EXITED ? 0 : null, null);
  }

  public void healthChecked(String requestId, boolean ok) {
    send(HealthChecked.of(new HealthReport(ok, ok ? "" : "docker is gone", requestId, List.of())));
  }

  /** A report naming its checks, as the toolkit's {@code HealthChecks} send it. */
  public void healthChecked(
      String requestId, List<eu.wohlben.qits.runner.protocol.health.CheckResult> checks) {
    boolean ok = checks.stream().allMatch(eu.wohlben.qits.runner.protocol.health.CheckResult::ok);
    send(HealthChecked.of(new HealthReport(ok, ok ? "" : "a check failed", requestId, checks)));
  }

  /** Answers a {@code docker} request as the runner would. */
  public void dockerResult(Docker request, DockerStatus status, String body) {
    send(new DockerResult(request.requestId(), status, body, false, null, null));
  }

  public void send(RunnerMessage message) {
    try {
      socket.sendText(JSON.writeValueAsString(CODEC.encode(message)), true).join();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The next frame of {@code type} the server sent that matches, waiting for it; earlier ones are kept. */
  public synchronized <T extends RunnerMessage> T await(Class<T> type, Predicate<T> matching)
      throws InterruptedException {
    for (RunnerMessage message : seen) {
      if (type.isInstance(message) && matching.test(type.cast(message))) {
        seen.remove(message);
        return type.cast(message);
      }
    }
    long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      RunnerMessage message = inbox.poll(50, TimeUnit.MILLISECONDS);
      if (message == null) {
        continue;
      }
      if (type.isInstance(message) && matching.test(type.cast(message))) {
        return type.cast(message);
      }
      seen.add(message);
    }
    throw new AssertionError("never received a matching " + type.getSimpleName() + "; got " + seen);
  }

  public <T extends RunnerMessage> T await(Class<T> type) throws InterruptedException {
    return await(type, m -> true);
  }

  /** Whether any frame of {@code type} has arrived so far, without waiting for one. */
  public synchronized boolean received(Class<? extends RunnerMessage> type) {
    inbox.drainTo(seen);
    return seen.stream().anyMatch(type::isInstance);
  }

  public int awaitClose() throws InterruptedException {
    if (!closed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      throw new AssertionError("the server never closed the socket");
    }
    return closeCode.get();
  }

  public String closeReason() {
    return closeReason.get();
  }

  @Override
  public void close() {
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    } catch (RuntimeException ignored) {
      // Already gone.
    }
  }

  private final class Collector implements WebSocket.Listener {
    private final StringBuilder partial = new StringBuilder();

    @Override
    @SuppressWarnings("unchecked")
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        try {
          inbox.add(CODEC.decode(JSON.readValue(partial.toString(), Map.class)));
        } catch (Exception e) {
          throw new IllegalStateException("an undecodable frame: " + partial, e);
        }
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closeCode.set(statusCode);
      closeReason.set(reason);
      closed.countDown();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      closed.countDown();
    }
  }
}
