package eu.wohlben.qits.system.targets;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * <b>The real {@link QitsEventBus}, keeping a copy of what it was handed</b> — so a suite can see
 * which events reached the bus. Not a mock: every call still goes to the shipped {@code publish},
 * which the suite's {@code %test.qits.eventstream.enabled=false} turns into a debug line, exactly as
 * without this class. The copy is the only difference, which is why it can stand in for the bus
 * for the whole suite ({@code @Alternative} with a {@code @Priority}, no profile, no second boot).
 *
 * <p>The copy is process-wide, so a test reads it filtered to its own target — every test here
 * stands up its own target id — and never clears it under another test's feet.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingEventBus extends QitsEventBus {

  private final List<QitsEvent> published = new CopyOnWriteArrayList<>();

  @Override
  public void publish(QitsEvent event, UUID parent) {
    published.add(event);
    super.publish(event, parent);
  }

  /** Every event of {@code type} handed to the bus so far that matches, oldest first. */
  public <E extends QitsEvent> List<E> published(Class<E> type, Predicate<E> matching) {
    return published.stream()
        .filter(type::isInstance)
        .map(type::cast)
        .filter(matching)
        .toList();
  }

  /**
   * Waits until {@code count} such events were handed to the bus — they go out on {@code
   * TargetEvents}' own thread, after the change — then a further {@code settle} for one that should
   * not come, and answers all of them.
   */
  public <E extends QitsEvent> List<E> await(
      Class<E> type, Predicate<E> matching, int count, Duration settle)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    while (published(type, matching).size() < count) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            count + " " + type.getSimpleName() + " never published; got " + published(type, matching));
      }
      Thread.sleep(20);
    }
    Thread.sleep(settle.toMillis());
    return published(type, matching);
  }
}
