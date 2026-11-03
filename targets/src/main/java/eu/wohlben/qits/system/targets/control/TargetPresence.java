package eu.wohlben.qits.system.targets.control;

import java.time.Instant;
import java.util.UUID;

/**
 * What the allocator needs from the runner sockets, and nothing more: whether a target is
 * connected (a greeted connection at the pin), whether it is still present, a way to have its estate
 * pushed, and a way to tell its runner it is quarantined. {@code runner/TargetRegistry} is the one
 * implementation; keeping it an interface keeps the control layer free of socket types.
 */
public interface TargetPresence {

  /** Whether the target holds a greeted, serving connection right now. */
  boolean connected(UUID targetId);

  /**
   * Whether the target is still present as of {@code now}: it holds an open connection, or it has
   * been without one for less than {@code qits.system.targets.reconnect-grace}. A target not seen
   * since this process started counts as dropped at the start, so a restart gives every runner the
   * grace to dial again. {@code now} is the caller's clock, which is how the suite judges it.
   */
  boolean present(UUID targetId, Instant now);

  /** The target's leases or purges changed: push its estate, off the caller's thread. */
  void estateChanged(UUID targetId);

  /**
   * The target was just quarantined (or its quarantine's reason renewed): its serving connection
   * is sent {@code quarantined} and acked again with 0 slots.
   */
  void quarantined(UUID targetId, String reason, Instant since);

  /**
   * The leases changed, so the login probe's image may have: every serving connection whose
   * {@code ack} named another one is acked again, off the caller's thread.
   */
  void probeImageMayHaveChanged();
}
