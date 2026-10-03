package eu.wohlben.qits.system.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.system.error.DockerUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one place a docker command is actually executed: the configured binary, the configured
 * deadline, the configured output bound, and one rule about failure.
 *
 * <p><b>THE RULE: a call that did not succeed is a 503 naming docker's last words.</b> Not a 404,
 * not an empty list, not a null. A read that could not reach the daemon knows nothing, and the
 * three failures an operator actually meets — the socket not mounted, the group not added, the node
 * not being a swarm manager — all arrive as a non-zero exit with one clear sentence on stderr.
 * Passing that sentence through is worth more than any message this service could invent. The one
 * caller that needs to tell "docker said no" from "docker could not be asked" is the exec probe,
 * and it uses {@link #attempt} to get the raw result.
 *
 * <p><b>Two output shapes, two readers</b>, matching {@link DockerArgv}'s two flavours: {@link
 * #jsonLines} for {@code ls --format '{{json .}}'} (one object per line) and {@link #jsonArray} for
 * {@code inspect} (a JSON array of documents).
 */
@ApplicationScoped
public class DockerCli {

  private static final Logger LOG = Logger.getLogger(DockerCli.class);

  /** How much of docker's own message is quoted back in a 503. Enough to read, short enough to log. */
  private static final int MESSAGE_MAX = 400;

  @ConfigProperty(name = "qits.system.docker.binary")
  String binary;

  @ConfigProperty(name = "qits.system.docker.call-timeout")
  Duration callTimeout;

  @ConfigProperty(name = "qits.system.docker.max-output-chars")
  int maxOutputChars;

  /**
   * Where this service writes its own docker {@code config.json} (qits-879). Every docker child
   * is pointed at it — but only once the file exists; see {@link DockerClientConfig#applyTo}.
   */
  @ConfigProperty(name = "qits.system.docker.config-dir")
  Path configDir;

  @Inject ObjectMapper mapper;

  /** The binary the argv builders take as their first element. */
  public String binary() {
    return binary;
  }

  /** The directory this service's own docker {@code config.json} lives in, when it has one. */
  public Path configDir() {
    return configDir;
  }

  /** The deadline every call runs under. */
  public Duration callTimeout() {
    return callTimeout;
  }

  /**
   * Run a docker command and return its output, or throw.
   *
   * @throws DockerUnavailableException on a non-zero exit, a timeout, or a missing binary
   */
  public String run(List<String> argv) {
    return run(argv, maxOutputChars);
  }

  /** The same, with a bound of its own — {@code docker logs} asks for a smaller one. */
  public String run(List<String> argv, int maxChars) {
    DockerProcess.Result result = attempt(argv, maxChars);
    if (!result.ok()) {
      throw unavailable(argv, result);
    }
    return result.output();
  }

  /**
   * Run a docker command and hand back the raw result, failures included. For the two callers that
   * have to distinguish docker's "no such container" from docker being unreachable.
   */
  public DockerProcess.Result attempt(List<String> argv) {
    return attempt(argv, maxOutputChars);
  }

  public DockerProcess.Result attempt(List<String> argv, int maxChars) {
    return DockerProcess.run(argv, callTimeout, maxChars, configDir);
  }

  /**
   * Run a docker command whose failure is not worth an error — the boot sweep's removals and the
   * glances teardown. Logged at WARN and swallowed: neither is something a request is waiting on.
   */
  public void bestEffort(String what, List<String> argv) {
    DockerProcess.Result result = attempt(argv);
    if (!result.ok()) {
      LOG.warnf("%s did not succeed: %s", what, oneLine(result.output()));
    }
  }

  /** Read {@code ls --format '{{json .}}'} output: one JSON object per line, blank lines skipped. */
  public List<JsonNode> jsonLines(List<String> argv) {
    String output = run(argv);
    List<JsonNode> rows = new ArrayList<>();
    for (String line : output.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        rows.add(mapper.readTree(trimmed));
      } catch (Exception e) {
        // One unreadable line must not lose the other forty. It has never happened in practice;
        // when it does, the log names the command and the suite has a fixture to add.
        LOG.warnf("Unreadable line from `%s`: %s", commandOf(argv), oneLine(trimmed));
      }
    }
    return rows;
  }

  /**
   * Read {@code inspect} output: a JSON array of documents.
   *
   * @return the array's elements, empty when docker printed an empty array
   */
  public List<JsonNode> jsonArray(List<String> argv) {
    return parseArray(argv, run(argv));
  }

  /**
   * Read an already-captured inspect output. For the callers that ran {@link #attempt} first
   * because they had to tell docker's "no such object" from docker being unreachable — parsing the
   * output they already hold, rather than forking a second identical process to get it again.
   */
  public List<JsonNode> parseArray(List<String> argv, String output) {
    JsonNode root;
    try {
      root = mapper.readTree(output);
    } catch (Exception e) {
      throw new DockerUnavailableException(
          "docker printed something that is not JSON for `" + commandOf(argv) + "`");
    }
    if (root == null || !root.isArray()) {
      throw new DockerUnavailableException(
          "docker printed no inspect array for `" + commandOf(argv) + "`");
    }
    List<JsonNode> rows = new ArrayList<>();
    root.forEach(rows::add);
    return rows;
  }

  /** The first document of an inspect, or null when the array was empty. */
  public JsonNode inspectOne(List<String> argv) {
    List<JsonNode> rows = jsonArray(argv);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** Turn a failed result into the 503 an operator reads. */
  public DockerUnavailableException unavailable(List<String> argv, DockerProcess.Result result) {
    String what = commandOf(argv);
    if (result.timedOut()) {
      return new DockerUnavailableException(
          "docker did not answer `" + what + "` within " + callTimeout);
    }
    String said = oneLine(result.output());
    return new DockerUnavailableException(
        said.isEmpty()
            ? "docker refused `" + what + "` (exit " + result.exitCode() + ")"
            : "docker refused `" + what + "`: " + said);
  }

  /** The command, without the argv's noise — enough to know which read failed. */
  private static String commandOf(List<String> argv) {
    return String.join(" ", argv.subList(0, Math.min(argv.size(), 4)));
  }

  private static String oneLine(String text) {
    if (text == null) {
      return "";
    }
    String flat = text.replaceAll("\\s+", " ").trim();
    return flat.length() <= MESSAGE_MAX ? flat : flat.substring(0, MESSAGE_MAX) + "…";
  }
}
