package eu.wohlben.qits.system.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.system.error.BadRequestException;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The first checkpoint on every caller-supplied string that reaches a docker argv, and the place a
 * refusal becomes this service's 400.
 *
 * <p>Everything checked here arrives in a URL path or a JSON body from a browser, so all of it is
 * attacker-reachable by design even behind {@code qits:admin} — a link is a thing somebody can be
 * sent.
 *
 * <p><b>The SHAPE of a reference is not decided here.</b> It is qits-containers-driver's ({@link
 * ContainersIdentifiers#requireReference}, {@link ContainersIdentifiers#requireFullId}): no leading
 * {@code -} that a positional argument could be read as an option with, no {@code :} or {@code /}
 * that could give a reference a different shape, no whitespace or control character, and a bounded
 * length. The library belts the same values again inside its argv builders, so there are still two
 * checkpoints and neither is trusted alone. What this class adds is the ANSWER: the library refuses
 * with an {@code IllegalArgumentException}, and a caller who sent an implausible reference is told
 * so as a 400 {@code not a valid <what>: <value>}, never a 500.
 *
 * <p><b>The exec target is resolved, not passed through.</b> The reference a caller sends is checked
 * here and then RESOLVED through {@code docker container inspect}; the argv that opens the shell
 * carries the canonical 64-hex id the daemon printed ({@link #requireFullId}), never the caller's
 * string.
 */
public final class CallerValues {

  /** How much of a rejected value is echoed back: enough to recognise, short enough to log. */
  private static final int ECHO_MAX = 64;

  private CallerValues() {}

  /**
   * A container reference as a person types it: an id, an id prefix, or a name — a swarm task name
   * ({@code dev-qits-ci.1.k4g0nn1ld7o2}) included, which is what this service is mostly asked for.
   */
  public static String requireContainerRef(String value) {
    return require("container", value, v -> ContainersIdentifiers.requireReference("container", v));
  }

  /** A node, service or config reference from a path; {@code field} names which. */
  public static String requireSwarmRef(String field, String value) {
    return require(field, value, v -> ContainersIdentifiers.requireReference(field, v));
  }

  /**
   * The canonical id the daemon printed. Called on the way OUT of resolution rather than on the way
   * in: if the daemon ever answered something that is not an id, no terminal opens.
   */
  public static String requireFullId(String value) {
    return require("container id", value, ContainersIdentifiers::requireFullId);
  }

  /** A log tail count. Docker takes any integer; a caller does not get to ask for a heap. */
  public static int requireTail(int value, int max) {
    if (value < 1 || value > max) {
      throw new BadRequestException("tail must be between 1 and " + max + ", got " + value);
    }
    return value;
  }

  /**
   * A terminal session id from a path. It is this service's own UUID, so anything else is a 404
   * rather than a 400 — the caller named a session, it just does not exist.
   */
  public static UUID parseSessionId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  private static String require(String field, String value, UnaryOperator<String> belt) {
    try {
      return belt.apply(value);
    } catch (IllegalArgumentException refused) {
      throw new BadRequestException("not a valid " + field + ": " + echo(value));
    }
  }

  /**
   * A rejected value goes into a log line and an HTTP body, so control characters are stripped: one
   * that carried a newline could forge a second log line.
   */
  private static String echo(String value) {
    if (value == null) {
      return "(none)";
    }
    String cleaned = value.replaceAll("\\p{Cntrl}", "?");
    return cleaned.length() <= ECHO_MAX ? cleaned : cleaned.substring(0, ECHO_MAX) + "…";
  }
}
