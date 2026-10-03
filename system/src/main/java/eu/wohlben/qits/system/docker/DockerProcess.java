package eu.wohlben.qits.system.docker;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * This service's tiny process shell-out, copied from qits-containers' {@code ContainerProcess} —
 * deliberately a copy rather than a shared jar, so this repository stays clone-alone. Combined
 * stdout+stderr, drained on a virtual thread; on timeout the process is {@link
 * Process#destroyForcibly() force-killed} and whatever output was captured so far is returned with
 * {@code timedOut=true}.
 *
 * <p><b>What it is used for is the docker CLI and nothing else</b>, and here the vocabulary is
 * narrower than in qits-containers: {@code info}, {@code system df}, the {@code ls} and {@code
 * inspect} verbs of node/service/config/secret/container/image/volume/network, {@code logs},
 * {@code version}, {@code pull}, and the {@code rm -f} of this service's own labelled terminal
 * containers. Nothing a caller sends becomes a host command line: an argv is assembled element by
 * element by {@link DockerArgv} and handed to {@link ProcessBuilder}, which never re-splits.
 *
 * <p><b>The two interactive verbs are NOT here.</b> {@code docker run -it} and {@code docker exec
 * -it} are not captured output, they are a terminal — they run through {@code
 * terminal/TerminalProcesses} on a PTY, with no deadline and no bound, because the deadline is the
 * operator closing the tab and the bound is the session's scrollback ring.
 *
 * <p><b>Every entry point takes both a timeout and a bound.</b> A docker call with no deadline is a
 * worker held forever by a daemon that stopped answering, and a capture with no bound is a heap
 * whose size the output chose — and container logs are exactly output somebody else's workload
 * wrote. Making both unavoidable in the API is the property; a convenience overload is the
 * regression.
 *
 * <p>Output is <b>bounded while reading</b>: the buffer keeps only the trailing {@code maxChars}
 * and reports {@code truncated}. For {@code docker logs --tail} that is the right end — the tail is
 * what an operator asked for.
 */
public final class DockerProcess {

  /** Rolling buffer slack: trim back to {@code maxChars} once it grows past this multiple. */
  private static final int TRIM_FACTOR = 2;

  /**
   * Exit code, bounded combined output, whether the hard timeout expired ({@code exitCode} is -1
   * then), and whether output was dropped from the front.
   */
  public record Result(int exitCode, String output, boolean timedOut, boolean truncated) {

    /** Whether docker answered at all and said yes. */
    public boolean ok() {
      return exitCode == 0 && !timedOut;
    }
  }

  private DockerProcess() {}

  /**
   * Run the argv, capturing the trailing {@code maxChars} of its merged output.
   *
   * @param argv the command, already assembled element by element — never a shell string
   * @param timeout the hard deadline; past it the process is force-killed
   * @param maxChars how much of the output tail is kept
   * @param dockerConfigDir the directory this service writes its own {@code config.json} into;
   *     {@code DOCKER_CONFIG} points there only when that file exists (see {@link
   *     DockerClientConfig#applyTo}), otherwise the inherited environment stands
   */
  public static Result run(
      List<String> argv, Duration timeout, int maxChars, Path dockerConfigDir) {
    try {
      ProcessBuilder pb = new ProcessBuilder(argv);
      pb.redirectErrorStream(true);
      DockerClientConfig.applyTo(pb.environment(), dockerConfigDir);
      Process process = pb.start();
      Tail tail = new Tail(maxChars);
      Thread reader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = process.getInputStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                  }
                } catch (Exception ignored) {
                  // stream closes when the process dies — nothing to report beyond the exit code
                }
              });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new Result(
          finished ? process.exitValue() : -1, tail.text(), !finished, tail.truncated());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(-1, "interrupted", false, false);
    } catch (Exception e) {
      // A missing binary lands here (IOException: Cannot run program "docker"), which is a real
      // deployment state — the image without its CLI — and reads as a 503 like any other
      // unreachable daemon.
      return new Result(-1, String.valueOf(e.getMessage()), false, false);
    }
  }

  /** A synchronized rolling tail — appends are trimmed so memory stays O(maxChars). */
  private static final class Tail {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;
    private boolean truncated;

    Tail(int maxChars) {
      this.maxChars = Math.max(1, maxChars);
    }

    synchronized void append(String chunk) {
      buffer.append(chunk);
      if (buffer.length() > (long) maxChars * TRIM_FACTOR) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
    }

    synchronized String text() {
      if (buffer.length() > maxChars) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
      return buffer.toString();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }
}
