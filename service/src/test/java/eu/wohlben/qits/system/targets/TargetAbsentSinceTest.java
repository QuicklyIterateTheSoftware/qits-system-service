package eu.wohlben.qits.system.targets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.system.targets.control.TargetPresence;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetPresence#absentSince} while a target's last connection closes (qits-2233): read in a
 * tight loop across the close, it answers empty until the drop is on record and the drop after,
 * never the process's start. Answered the start, the absent deadline of a target that has just
 * gone would have passed long ago, and the next sweep would fail its RUNNING CI leases at once.
 */
@QuarkusTest
class TargetAbsentSinceTest {

  private static final String BEARER = "Bearer runner-client-token";

  private static final String CLIENT = "target-runner-client-lab";

  private static final int CYCLES = 20;

  @TestHTTPResource("/")
  URI base;

  @Inject ContractBearers bearers;

  @Inject TargetPresence presence;

  private UUID target;

  @BeforeEach
  void standUp() {
    TargetRows.clear();
    bearers.reset();
    target = UUID.randomUUID();
    TargetRows.target(target, "lab", 0, CLIENT, true);
    bearers.grant(BEARER, CLIENT, "qits:target-runner");
  }

  @AfterEach
  void disarm() {
    bearers.reset();
  }

  @Test
  void aTargetWhoseConnectionClosesIsAbsentSinceTheDropNeverSinceTheStart() throws Exception {
    Instant started = presence.absentSince(UUID.randomUUID()).orElseThrow();
    for (int cycle = 0; cycle < CYCLES; cycle++) {
      FakeTargetRunner runner = FakeTargetRunner.dial(base, BEARER);
      awaitConnected();
      Instant beforeClose = Instant.now();
      runner.close();
      List<Instant> answers = new ArrayList<>();
      long until = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
      while (true) {
        Optional<Instant> since = presence.absentSince(target);
        if (since.isPresent()) {
          answers.add(since.get());
          if (!since.get().isBefore(beforeClose)) {
            break;
          }
        }
        if (System.currentTimeMillis() > until) {
          throw new AssertionError("cycle " + cycle + ": the server never saw the runner go");
        }
      }
      for (Instant answer : answers) {
        assertNotEquals(started, answer, "cycle " + cycle + ": absent since the process's start");
        assertFalse(answer.isBefore(beforeClose), "cycle " + cycle + ": absent before the close");
      }
    }
  }

  /** Until the server has admitted the connection: an open socket is no absence. */
  private void awaitConnected() throws InterruptedException {
    long until = System.currentTimeMillis() + FakeTargetRunner.TIMEOUT.toMillis();
    while (presence.absentSince(target).isPresent()) {
      if (System.currentTimeMillis() > until) {
        throw new AssertionError("the server never admitted the runner");
      }
      Thread.sleep(5);
    }
  }
}
