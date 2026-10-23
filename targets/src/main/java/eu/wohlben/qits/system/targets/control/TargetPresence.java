package eu.wohlben.qits.system.targets.control;

import java.util.UUID;

/**
 * What the allocator needs from the runner sockets, and nothing more: whether a target is
 * connected (a greeted connection at the pin), and a way to have its estate pushed. {@code
 * runner/TargetRegistry} is the one implementation; keeping it an interface keeps the control
 * layer free of socket types.
 */
public interface TargetPresence {

  /** Whether the target holds a greeted, serving connection right now. */
  boolean connected(UUID targetId);

  /** The target's leases or purges changed: push its estate, off the caller's thread. */
  void estateChanged(UUID targetId);
}
