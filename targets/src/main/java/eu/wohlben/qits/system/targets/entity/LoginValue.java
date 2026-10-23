package eu.wohlben.qits.system.targets.entity;

/** One harness's sign-in on a target's credential volume, as the runner's login probe read it. */
public enum LoginValue {
  PRESENT,
  ABSENT,
  UNKNOWN;

  /** The value a wire string names; anything unreadable is UNKNOWN, never an error. */
  public static LoginValue parse(String value) {
    if (value != null) {
      for (LoginValue login : values()) {
        if (login.name().equalsIgnoreCase(value.trim())) {
          return login;
        }
      }
    }
    return UNKNOWN;
  }
}
