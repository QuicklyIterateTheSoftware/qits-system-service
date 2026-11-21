package eu.wohlben.qits.system.targets.control;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker's memory sizes ({@code --memory 6g}), read the way docker reads them (go-units' {@code
 * RAMInBytes}): a number, optionally a unit {@code k m g t p} in any case, optionally followed by
 * {@code b} or {@code ib} (so a bare {@code b} is bytes); every unit is a binary multiple, so
 * {@code 6g}, {@code 6G}, {@code 6gb} and {@code 6GiB} are all {@code 6 × 1024³}. A bare number is
 * bytes. Pure.
 */
public final class DockerSizes {

  private static final Pattern SIZE =
      Pattern.compile("^(\\d+(?:\\.\\d+)?) ?([kmgtp])?(?:i?b)?$", Pattern.CASE_INSENSITIVE);

  private static final String UNITS = "bkmgtp";

  private DockerSizes() {}

  /** The size in bytes; empty when {@code text} is null, blank, negative, malformed or too large. */
  public static OptionalLong parse(String text) {
    if (text == null) {
      return OptionalLong.empty();
    }
    Matcher m = SIZE.matcher(text.trim());
    if (!m.matches()) {
      return OptionalLong.empty();
    }
    String unit = m.group(2) == null ? "b" : m.group(2).toLowerCase(Locale.ROOT);
    BigDecimal bytes =
        new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(1024).pow(UNITS.indexOf(unit)));
    try {
      return OptionalLong.of(bytes.setScale(0, java.math.RoundingMode.DOWN).longValueExact());
    } catch (ArithmeticException tooLarge) {
      return OptionalLong.empty();
    }
  }

  /**
   * What a lease is charged against its target's memory (qits-2362): its {@code
   * spec.limits.memory}, the cap the runner gives its container. {@code memorySwap} is not charged.
   * A spec with no memory limit is charged 0 — every owner (qits-ci, qits-workspaces, the desk) sets
   * one today, so that is a lease nobody sizes rather than one that needs nothing. An unparseable
   * limit is charged 0 as well, without a log line: the runner hands the same text to docker, which
   * refuses it and fails the lease, so the allocator has nothing to add.
   */
  public static long charge(JsonNode spec) {
    if (spec == null || !spec.isObject()) {
      return 0;
    }
    JsonNode limits = spec.get("limits");
    if (limits == null || !limits.isObject()) {
      return 0;
    }
    JsonNode memory = limits.get("memory");
    if (memory == null || memory.isNull() || !memory.isValueNode()) {
      return 0;
    }
    return parse(memory.asText()).orElse(0);
  }

  /** {@link #charge(JsonNode)} of a stored spec's text. */
  public static long charge(String specText) {
    return charge(LeaseSpecs.parse(specText));
  }

  /** The shortest docker spelling of {@code bytes}: {@code 6g}, {@code 512m}, {@code 1536k}, … */
  public static String format(long bytes) {
    int unit = 0;
    long value = bytes;
    while (value != 0 && value % 1024 == 0 && unit < UNITS.length() - 1) {
      value /= 1024;
      unit++;
    }
    return value + String.valueOf(UNITS.charAt(unit));
  }
}
