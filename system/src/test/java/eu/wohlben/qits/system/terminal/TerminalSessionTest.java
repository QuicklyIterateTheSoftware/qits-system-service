package eu.wohlben.qits.system.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The PTY session, against plain shell stand-ins — no docker anywhere.
 *
 * <p>The stand-in is spawned exactly the way {@link TerminalSessions} spawns the docker CLI: a real
 * {@link ForeignPty} and a child launched through {@link TerminalProcesses#terminalProcess}, which
 * is the launch that carries the PID-1 SIGHUP fix. So what this drives is the real machinery, not a
 * pipe pair standing in for it. Linux-only, because {@link ForeignPty} is.
 */
@EnabledOnOs(OS.LINUX)
class TerminalSessionTest {

  private static final long AWAIT_MILLIS = 15_000;

  private static final int SCROLLBACK = 64 * 1024;

  /** Collects everything the session writes; always open. */
  private static final class CapturingSink implements TerminalOutputSink {
    final StringBuilder received = new StringBuilder();

    @Override
    public synchronized void write(String data) {
      received.append(data);
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    synchronized String text() {
      return received.toString();
    }
  }

  private static TerminalSession session(String script, IntConsumer onFinished) throws IOException {
    return session(script, SCROLLBACK, onFinished);
  }

  private static TerminalSession session(String script, int scrollback, IntConsumer onFinished)
      throws IOException {
    ForeignPty pty = ForeignPty.open(80, 24);
    Process process =
        TerminalProcesses.terminalProcess(pty.slavePath(), List.of("sh", "-c", script)).start();
    return new TerminalSession(
        UUID.randomUUID(),
        new TerminalLaunch.Glances(),
        "tester",
        process,
        pty,
        scrollback,
        onFinished);
  }

  private static void awaitContains(CapturingSink sink, String needle) throws Exception {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!sink.text().contains(needle) && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(sink.text().contains(needle), "expected '" + needle + "' in: " + sink.text());
  }

  @Test
  void attachReplaysTheScrollbackInputRoundTripsAndExitFiresTheEndListener() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(-99);
    TerminalSession session =
        session(
            "echo intro; read line; echo \"got:$line\"",
            code -> {
              exitCode.set(code);
              ended.countDown();
            });
    session.seedBanner("Attached to the host monitor\r\n");

    CapturingSink sink = new CapturingSink();
    session.attach(sink, code -> {});
    session.startReader();

    awaitContains(sink, "Attached to the host monitor");
    awaitContains(sink, "intro");

    session.input("hello\n".getBytes(StandardCharsets.UTF_8));
    awaitContains(sink, "got:hello");

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "end listener fired");
    assertEquals(0, exitCode.get());

    // A LATE ATTACH replays the whole ring and fires its end listener at once — which is what a
    // browser reconnecting to a session that just ended must see, rather than an empty terminal.
    CapturingSink late = new CapturingSink();
    CountDownLatch lateEnded = new CountDownLatch(1);
    session.attach(late, code -> lateEnded.countDown());
    assertTrue(lateEnded.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS));
    assertTrue(late.text().contains("Attached to the host monitor"), late.text());
    assertTrue(late.text().contains("got:hello"), late.text());
  }

  @Test
  void twoClientsSeeTheSameStreamAndDetachingOneLeavesTheOther() throws Exception {
    TerminalSession session = session("read line; echo \"echoed:$line\"", code -> {});
    CapturingSink first = new CapturingSink();
    CapturingSink second = new CapturingSink();
    session.attach(first, code -> {});
    session.attach(second, code -> {});
    session.startReader();
    assertEquals(2, session.attachedClients());

    assertEquals(1, session.detach(second));
    session.input("shared\n".getBytes(StandardCharsets.UTF_8));

    awaitContains(first, "echoed:shared");
    assertFalse(second.text().contains("echoed:shared"), "a detached sink stops receiving");
    session.terminate();
  }

  /**
   * THE REASON THE DECODER IS STATEFUL.
   *
   * <p>The reader takes 4 KiB at a time off the master, and a multi-byte character straddles that
   * boundary regularly — glances draws with box-drawing characters, so it happens about once a
   * second. Decoding each read on its own turns the split character into two replacement
   * characters, and a hole in a redrawn frame never heals.
   *
   * <p>The script writes enough box-drawing characters to guarantee several reads' worth, and the
   * assertion is simply that not one U+FFFD came back.
   */
  @Test
  void aMultiByteCharacterSplitAcrossTwoReadsIsNotLost() throws Exception {
    // 20000 three-byte characters: far past the 4096-byte read buffer, so the boundary lands
    // mid-character many times over.
    int count = 20_000;
    CountDownLatch ended = new CountDownLatch(1);
    TerminalSession session =
        session(
            "i=0; while [ $i -lt " + count + " ]; do printf '\\342\\224\\200'; i=$((i+1)); done",
            code -> ended.countDown());
    CapturingSink sink = new CapturingSink();
    session.attach(sink, code -> {});
    session.startReader();

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "the writer finished");
    // Give the last chunk a moment to be broadcast after the process exited.
    Thread.sleep(200);

    String text = sink.text();
    assertFalse(text.contains("�"), "a split character became a replacement character");
    long drawn = text.chars().filter(c -> c == '─').count();
    assertEquals(count, drawn, "every box-drawing character came through whole");
  }

  /**
   * RESIZE REACHES THE CHILD AS SIGWINCH, which is the whole chain the browser's resize frame
   * depends on: TIOCSWINSZ on the master, the kernel signalling the foreground process group of
   * the terminal's session, and that group being the child because {@code setsid --ctty} made it
   * one. Break any link and a resized browser shows a terminal still drawing at 80x24.
   */
  @Test
  void aResizeIsDeliveredToTheChildAsSigwinch() throws Exception {
    TerminalSession session =
        session("trap 'stty size' WINCH; while true; do sleep 0.1; done", code -> {});
    CapturingSink sink = new CapturingSink();
    session.attach(sink, code -> {});
    session.startReader();
    // Let the shell install its trap before the signal arrives.
    Thread.sleep(300);

    session.resize(132, 50);

    // `stty size` prints "<rows> <cols>". The order is the winsize struct's, and a swapped field
    // order here would print "132 50".
    awaitContains(sink, "50 132");
    session.terminate();
  }

  @Test
  void theRingIsBoundedAndKeepsTheRecentEnd() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    // 8 KiB of scrollback, 40 KiB of output: the front is dropped and the end is what replays.
    TerminalSession session =
        session(
            "i=0; while [ $i -lt 2000 ]; do echo \"line-$i-xxxxxxxxxxxxxx\"; i=$((i+1)); done",
            8192,
            code -> ended.countDown());
    session.startReader();
    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS));
    Thread.sleep(200);

    CapturingSink late = new CapturingSink();
    session.attach(late, code -> {});

    String replayed = late.text();
    assertTrue(replayed.length() <= 8192 + 4096, "the ring stayed bounded: " + replayed.length());
    assertTrue(replayed.contains("line-1999"), "the recent end is what an operator needs");
    assertFalse(replayed.contains("line-0-"), "the old front was dropped");
  }

  @Test
  void terminateEndsALingeringProcess() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(0);
    TerminalSession session =
        session(
            "sleep 30",
            code -> {
              exitCode.set(code);
              ended.countDown();
            });
    session.startReader();
    assertTrue(session.isAlive());

    session.terminate();

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "terminate ended the session");
    assertNotEquals(0, exitCode.get(), "a killed process exits non-zero");
  }

  /**
   * The polite half of the teardown. A full-screen program leaves the terminal in an alternate
   * screen buffer; {@code ^C} is what a person would press, and a program that takes it exits
   * through its own teardown instead of being killed mid-redraw.
   */
  @Test
  void terminateOffersACtrlCBeforeItKills() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(-99);
    TerminalSession session =
        session(
            "trap 'echo tidied-up; exit 7' INT; while true; do sleep 0.1; done",
            code -> {
              exitCode.set(code);
              ended.countDown();
            });
    CapturingSink sink = new CapturingSink();
    session.attach(sink, code -> {});
    session.startReader();
    Thread.sleep(300);

    session.terminate();

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS));
    assertEquals(7, exitCode.get(), "the child exited on its own terms, not on SIGKILL");
    assertTrue(sink.text().contains("tidied-up"), sink.text());
  }

  /**
   * THE REASON THE READER IS DRAINED BEFORE THE HANG-UP. A slow client holds the reader inside
   * {@code sink.write} while the child's teardown text ({@code tidied-up}) is already sitting
   * unread in the PTY master's buffer; closing the master out from under that read throws the text
   * away instead of handing it to the next one. Every sink, slow or fast, must see it.
   */
  @Test
  void terminateDeliversTheTeardownOutputEvenToASlowClient() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(-99);
    TerminalSession session =
        session(
            "trap 'echo tidied-up; exit 7' INT; while true; do sleep 0.1; done",
            code -> {
              exitCode.set(code);
              ended.countDown();
            });
    CapturingSink fast = new CapturingSink();
    AtomicBoolean slow = new AtomicBoolean(false);
    TerminalOutputSink slowSink =
        new TerminalOutputSink() {
          @Override
          public void write(String data) {
            if (slow.get()) {
              try {
                Thread.sleep(800);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }

          @Override
          public boolean isOpen() {
            return true;
          }
        };
    session.attach(fast, code -> {});
    session.attach(slowSink, code -> {});
    session.startReader();
    Thread.sleep(300);
    slow.set(true);

    session.terminate();

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS));
    assertEquals(7, exitCode.get());
    assertTrue(fast.text().contains("tidied-up"), fast.text());
  }
}
