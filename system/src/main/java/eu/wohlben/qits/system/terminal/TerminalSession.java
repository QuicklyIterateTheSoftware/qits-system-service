package eu.wohlben.qits.system.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.jboss.logging.Logger;

/**
 * One live pseudo-terminal: the docker CLI child that runs on it, the bounded scrollback that lets
 * a re-attaching browser catch up, and the sinks currently watching it.
 *
 * <p>Adapted from qits-projects' {@code RemoteLoginSession}. The reader loop, the ring and the
 * end-listener contract are that class's; what changed is written down below.
 *
 * <p><b>The ring is BYTES and the decoder is STATEFUL, which is the one real change.</b> The
 * original decoded each 4 KiB read with {@code new String(buffer, 0, n, UTF_8)}. That is fine for
 * git's prose and wrong for a full-screen program: glances draws with box-drawing characters, a
 * read boundary lands in the middle of a three-byte sequence perhaps once a second, and each one
 * became U+FFFD — a visible hole in the frame that never healed, because the next read's leading
 * continuation bytes were garbage too. One {@link CharsetDecoder} per session, fed with {@code
 * endOfInput=false}, keeps the incomplete tail and decodes it when the rest arrives.
 *
 * <p><b>Replay decodes the snapshot once</b>, with a decode of its own rather than the live one:
 * the live decoder is mid-stream and feeding it the scrollback would corrupt both. The snapshot may
 * itself begin mid-character (the ring drops whole chunks from the front), which costs one
 * replacement character at the top of a replay and nothing after it.
 *
 * <p>The terminal and the process are two objects rather than one: {@link ForeignPty} is only the
 * pseudo-terminal, and the child is a plain {@link ProcessBuilder} one whose stdio happens to be
 * the slave device. Streams and resize therefore come from the PTY, and liveness, exit code and
 * killing from the {@link Process}.
 */
public final class TerminalSession {

  private static final Logger LOG = Logger.getLogger(TerminalSession.class);

  /**
   * How long after {@code destroy} before escalating to SIGKILL, and also the bound {@link
   * #drainReader()} gives the reader to pick up the dead child's last output before the hang-up:
   * a grandchild (the far side of {@code docker -t}) can still hold the slave open, and then EOF
   * never comes.
   */
  private static final long TERMINATE_GRACE_MILLIS = 2000;

  /**
   * How long the polite goodbye gets. A full-screen program leaves the terminal in an alternate
   * screen buffer and a raw input mode; {@code ^C} then {@code ^D} is what a person would press,
   * and a program that takes it exits through its own teardown and restores the screen. A second is
   * enough for that and short enough that a DELETE still feels immediate.
   */
  private static final long POLITE_EXIT_MILLIS = 1000;

  private static final byte CTRL_C = 0x03;
  private static final byte CTRL_D = 0x04;

  private final UUID id;
  private final TerminalLaunch launch;
  private final String createdBy;
  private final Instant createdAt = Instant.now();
  private final Process process;
  private final ForeignPty pty;
  private final int scrollbackBytes;

  /** Registry-level completion: drop the session from the registry, given the exit code. */
  private final IntConsumer onFinished;

  /** Recent raw output chunks, total bounded by {@link #scrollbackBytes}, for replay. */
  private final Deque<byte[]> ring = new ArrayDeque<>();

  private int ringBytes;

  /**
   * The live stream's decoder, plus whatever bytes of an incomplete character the last read ended
   * on. Touched only by the reader thread.
   */
  private final CharsetDecoder decoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);

  private byte[] carry = new byte[0];

  /** Attached sinks with their end-listeners; mutated and iterated only under the monitor. */
  private final Map<TerminalOutputSink, IntConsumer> attachments = new LinkedHashMap<>();

  /** Serializes stdin writes from concurrent clients so their bytes do not interleave. */
  private final Object stdinLock = new Object();

  private boolean terminal;
  private int exitCode = -1;

  /** The reader thread, set once by {@link #startReader()}; read by {@link #drainReader()}. */
  private volatile Thread reader;

  public TerminalSession(
      UUID id,
      TerminalLaunch launch,
      String createdBy,
      Process process,
      ForeignPty pty,
      int scrollbackBytes,
      IntConsumer onFinished) {
    this.id = id;
    this.launch = launch;
    this.createdBy = createdBy;
    this.process = process;
    this.pty = pty;
    this.scrollbackBytes = Math.max(4096, scrollbackBytes);
    this.onFinished = onFinished;
  }

  public UUID id() {
    return id;
  }

  public TerminalLaunch launch() {
    return launch;
  }

  public TerminalKind kind() {
    return launch.kind();
  }

  public String createdBy() {
    return createdBy;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** How many clients are watching right now — shown in the list so a second tab is visible. */
  public synchronized int attachedClients() {
    return attachments.size();
  }

  /** Whether any client is attached — the linger backstop must not kill a watched terminal. */
  public synchronized boolean hasClients() {
    return !attachments.isEmpty();
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  /** Seed intro text into the ring before the reader starts, so every attach replays it first. */
  public synchronized void seedBanner(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    ring.add(bytes);
    ringBytes += bytes.length;
  }

  /**
   * Start draining the PTY.
   *
   * <p>A PLATFORM thread, deliberately not a virtual one. The read is a blocking FFM downcall on
   * the master fd, and a native call pins its carrier: on a virtual thread it would hold a
   * carrier thread for the whole life of the session, which is every session holding one of the
   * few carriers the scheduler has.
   */
  public void startReader() {
    Thread thread = new Thread(this::readLoop, "terminal-" + id);
    thread.setDaemon(true);
    reader = thread;
    thread.start();
  }

  private void readLoop() {
    byte[] buffer = new byte[4096];
    try (InputStream in = pty.in()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        String text = decodeLive(buffer, read);
        synchronized (this) {
          appendToRing(buffer, read);
          if (!text.isEmpty()) {
            broadcast(text);
          }
        }
      }
    } catch (Exception e) {
      LOG.debugf(e, "Output pump ended for terminal %s", id);
    } finally {
      finish();
    }
  }

  /**
   * Decode one read against the running stream, keeping an incomplete trailing character for the
   * next one. Called only from the reader thread, which is what makes the unsynchronized {@link
   * #carry} safe.
   */
  private String decodeLive(byte[] buffer, int length) {
    ByteBuffer in = ByteBuffer.allocate(carry.length + length);
    in.put(carry).put(buffer, 0, length).flip();
    CharBuffer out = CharBuffer.allocate((int) (in.remaining() * decoder.maxCharsPerByte()) + 1);
    decoder.decode(in, out, false);
    // Whatever is left is the beginning of a character whose remaining bytes have not arrived.
    carry = new byte[in.remaining()];
    in.get(carry);
    out.flip();
    return out.toString();
  }

  /**
   * Attach a client: replay the buffered scrollback, then receive live output; {@code endListener}
   * fires with the exit code when the process ends (immediately if it already has).
   */
  public synchronized void attach(TerminalOutputSink sink, IntConsumer endListener) {
    if (ringBytes > 0) {
      byte[] snapshot = new byte[ringBytes];
      int pos = 0;
      for (byte[] chunk : ring) {
        System.arraycopy(chunk, 0, snapshot, pos, chunk.length);
        pos += chunk.length;
      }
      try {
        // A decode of its own: the live decoder is mid-stream and must not be fed the scrollback.
        sink.write(new String(snapshot, StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        LOG.debugf(e, "Replay failed for terminal %s", id);
        return;
      }
    }
    if (terminal) {
      endListener.accept(exitCode);
      return;
    }
    attachments.put(sink, endListener);
  }

  /** Detach a client; returns how many sinks remain attached (for the registry's linger timer). */
  public synchronized int detach(TerminalOutputSink sink) {
    attachments.remove(sink);
    return attachments.size();
  }

  /** Forward a client's keystrokes to the terminal. */
  public void input(byte[] data) {
    synchronized (stdinLock) {
      try {
        OutputStream out = pty.out();
        out.write(data);
        out.flush();
      } catch (IOException e) {
        LOG.debugf(e, "stdin write failed for terminal %s", id);
      }
    }
  }

  public void resize(int cols, int rows) {
    pty.resize(cols, rows);
  }

  /**
   * End the session.
   *
   * <p>THREE ESCALATIONS, and the first one is why a glances terminal does not leave a mess. A
   * polite {@code ^C} then {@code ^D} on the terminal reaches the foreground process group of the
   * child's own session, which is the docker CLI and, through {@code -t}, the program on the far
   * side: it exits through its own teardown. Only then is the terminal hung up (closing the master
   * delivers SIGHUP), the child destroyed, and — after a short grace — killed.
   *
   * <p>Closing the PTY is also what unblocks the reader thread: its {@code read} on the master fd
   * is a blocking native call, and the master is the only handle that ends it. But closing it is
   * also destructive: once the child has exited, whatever of its last output the reader has not
   * read yet — its own teardown text — is sitting in the master's buffer, and a close throws that
   * away instead of handing it to {@code read}. {@link #drainReader()} gives the reader a short
   * window to pick it up first.
   */
  public void terminate() {
    politeGoodbye();
    drainReader();
    pty.close();
    process.destroy();
    if (!waitQuietly(TERMINATE_GRACE_MILLIS)) {
      process.destroyForcibly();
    }
  }

  /**
   * Give the reader a bounded chance to pick up the dead child's last output before {@link
   * #terminate()} closes the master out from under it. A no-op while the child is still alive —
   * its output is not done yet, and the escalation steps below will reach it in time.
   */
  private void drainReader() {
    Thread r = reader;
    if (process.isAlive() || r == null) {
      return;
    }
    try {
      r.join(TERMINATE_GRACE_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void politeGoodbye() {
    if (!process.isAlive()) {
      return;
    }
    input(new byte[] {CTRL_C});
    if (waitQuietly(POLITE_EXIT_MILLIS / 2)) {
      return;
    }
    input(new byte[] {CTRL_D});
    waitQuietly(POLITE_EXIT_MILLIS / 2);
  }

  private boolean waitQuietly(long millis) {
    try {
      return process.waitFor(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void finish() {
    int code;
    try {
      // Wait for the OS process to be reaped so the exit code is reliable (PTY EOF can precede it).
      code = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      code = -1;
    }
    List<IntConsumer> listeners;
    synchronized (this) {
      terminal = true;
      exitCode = code;
      listeners = new ArrayList<>(attachments.values());
      attachments.clear();
    }
    // Registry cleanup FIRST (fast, non-blocking): the session leaves the registry the instant the
    // child exits, so a slot is never held behind a per-client socket write to a dead connection.
    try {
      onFinished.accept(code);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Session-finished callback failed for terminal %s", id);
    }
    // Then notify the clients (each closes its socket — may block on a dead connection, but nothing
    // important waits on this any more).
    for (IntConsumer listener : listeners) {
      try {
        listener.accept(code);
      } catch (RuntimeException e) {
        LOG.debugf(e, "End listener failed for terminal %s", id);
      }
    }
  }

  private void appendToRing(byte[] buffer, int read) {
    byte[] chunk = new byte[read];
    System.arraycopy(buffer, 0, chunk, 0, read);
    ring.add(chunk);
    ringBytes += read;
    while (ringBytes > scrollbackBytes && ring.size() > 1) {
      ringBytes -= ring.removeFirst().length;
    }
  }

  private void broadcast(String text) {
    for (Iterator<TerminalOutputSink> it = attachments.keySet().iterator(); it.hasNext(); ) {
      TerminalOutputSink sink = it.next();
      if (!sink.isOpen()) {
        it.remove();
        continue;
      }
      try {
        sink.write(text);
      } catch (RuntimeException e) {
        it.remove();
      }
    }
  }
}
