package eu.wohlben.qits.system.targets.entity;

import java.util.Set;

/**
 * A lease's life. QUEUED until the allocator grants it a target; GRANTED once it has one (the
 * runner is starting it), and QUEUED again, without one, when that target is gone past its
 * reconnect grace or its runner stopped acting on its estate before it started it (only a lease
 * without a work key, whose work volume would pin it there, qits-1829); RUNNING once the runner
 * reports its container running; EXITED or FAILED when the container stopped by itself or could
 * not start, and a RUNNING CI one FAILED too when its target has been gone past the absent
 * deadline (qits-2227);
 * ENDING once its owner deleted a GRANTED or RUNNING one and the runner has not yet reported it
 * gone (straight to ENDED when its target holds no connection to report on, qits-2227); ENDED for
 * good (an EXITED or FAILED one at its owner's end: its slot is free already, qits-1813).
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
