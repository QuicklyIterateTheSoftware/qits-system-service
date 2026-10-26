package eu.wohlben.qits.system.targets.runner;

import eu.wohlben.qits.system.targets.control.TargetsConfig;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * <b>One docker request and its answer</b> (epic qits-1621, "Docker over the runner socket"): sends
 * a {@link Docker} frame on the target's serving connection and waits for the {@link DockerResult}
 * with the same {@code requestId}, the way {@link TargetHealth} keeps its pending checks — a map
 * keyed by request id, settled from {@link TargetRunnerSocket}'s dispatch.
 *
 * <p>Only the asker waits: a REST worker thread blocks on the future, while the socket's frame
 * handler only completes it, so the runner's connection is never held up by a slow docker. A result
 * is taken only from the target the request went to; one arriving after its asker gave up is
 * dropped.
 *
 * <p>The wait is {@code qits.system.targets.docker.timeout} (30 s), and for an {@link DockerOp#EXEC}
 * its own {@code timeoutSeconds} (the runner's default 30 when absent) plus {@code exec-grace}.
 */
@ApplicationScoped
public class TargetDocker {

  private static final Logger LOG = Logger.getLogger(TargetDocker.class);

  /** The runner's exec default, when a request names none. */
  static final int EXEC_DEFAULT_SECONDS = 30;

  @Inject TargetRegistry registry;

  @Inject TargetsConfig config;

  private record Pending(UUID targetId, CompletableFuture<DockerResult> answer) {}

  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

  /** How a call ended: the runner's answer, or why there is none. */
  public sealed interface Outcome {}

  /** The runner answered. */
  public record Answered(DockerResult result) implements Outcome {}

  /** The target holds no live socket, or the frame could not leave. */
  public record NotConnected() implements Outcome {}

  /** No answer within {@code waited}. */
  public record TimedOut(Duration waited) implements Outcome {}

  /** Sends {@code request} to {@code targetId} and waits for its answer. Blocks the caller. */
  public Outcome call(UUID targetId, Docker request) {
    if (!registry.connected(targetId)) {
      return new NotConnected();
    }
    Duration wait = wait(request);
    CompletableFuture<DockerResult> answer = new CompletableFuture<>();
    pending.put(request.requestId(), new Pending(targetId, answer));
    try {
      if (!registry.send(targetId, request)) {
        return new NotConnected();
      }
      return new Answered(answer.get(wait.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException late) {
      LOG.infof(
          "Target %s did not answer docker %s %s within %s",
          targetId, request.op(), request.requestId(), wait);
      return new TimedOut(wait);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new TimedOut(wait);
    } catch (ExecutionException impossible) {
      throw new IllegalStateException(impossible.getCause());
    } finally {
      pending.remove(request.requestId());
    }
  }

  /** A runner's answer, from the socket: settles its request when that went to this target. */
  void onResult(UUID targetId, DockerResult result) {
    Pending waiting = pending.get(result.requestId());
    if (waiting == null || !waiting.targetId().equals(targetId)) {
      LOG.debugf(
          "Dropped a docker-result %s from target %s: nobody waits for it",
          result.requestId(), targetId);
      return;
    }
    waiting.answer().complete(result);
  }

  Duration wait(Docker request) {
    if (request.op() != DockerOp.EXEC) {
      return config.docker().timeout();
    }
    int seconds = request.timeoutSeconds() == null ? EXEC_DEFAULT_SECONDS : request.timeoutSeconds();
    return Duration.ofSeconds(Math.max(0, seconds)).plus(config.docker().execGrace());
  }
}
