package eu.wohlben.qits.system.targets.entity;

import java.util.Locale;

/**
 * The three kinds of work a target's slots run. Each is leased by exactly one application ({@code
 * qits.system.targets.tenants.<app>=<TYPE>}), and each has its own image prefix ({@code
 * qits.system.targets.slot-images.<type>}) and its own container policy in the runner.
 */
public enum SlotType {
  CI,
  WORKSPACE,
  DESK;

  /** The lower-case spelling config keys and wire maps use: {@code ci}, {@code workspace}, {@code desk}. */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether a lease of this type runs a coding agent and so needs a signed-in target. */
  public boolean needsSignIn() {
    return this != CI;
  }

  /** The type a wire or config value names, or null. Case-insensitive. */
  public static SlotType parse(String value) {
    if (value == null) {
      return null;
    }
    for (SlotType type : values()) {
      if (type.name().equalsIgnoreCase(value.trim())) {
        return type;
      }
    }
    return null;
  }
}
