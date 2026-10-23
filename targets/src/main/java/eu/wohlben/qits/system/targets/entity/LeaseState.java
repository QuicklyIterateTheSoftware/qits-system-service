package eu.wohlben.qits.system.targets.entity;

import java.util.Set;

/**
 * A lease's life. QUEUED until the allocator grants it a target; GRANTED once it has one (the
 * runner is starting it); RUNNING once the runner reports its container running; EXITED or FAILED
 * when the container stopped by itself or could not start; ENDING once its owner deleted it and the
 * runner has not yet reported it gone; ENDED for good.
 */
public enum LeaseState {
  QUEUED,
  GRANTED,
  RUNNING,
  EXITED,
  FAILED,
  ENDING,
  ENDED;

  /** The states that occupy a slot of their target: the allocator's {@code n[u]}. */
  public static final Set<LeaseState> OCCUPYING = Set.of(GRANTED, RUNNING, ENDING);

  public boolean live() {
    return this != ENDED;
  }

  public static LeaseState parse(String value) {
    if (value == null) {
      return null;
    }
    for (LeaseState state : values()) {
      if (state.name().equalsIgnoreCase(value.trim())) {
        return state;
      }
    }
    return null;
  }
}
