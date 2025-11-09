package com.bitsapplied.descartes.util;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * High-level stdout/stderr capture for the entire JVM.
 * <p>
 * Descartes executes user-supplied JShell code and MCP tools inside the host
 * JVM. In a typical workflow we want to return the snippet output
 * (stdout/stderr) back to the MCP client rather than writing directly to the
 * host console. Java does not provide per-thread output redirection, so the
 * only practical approach is to temporarily replace {@link System#out} and
 * {@link System#err} with custom streams that route writes into buffers.
 * </p>
 *
 * <p>
 * The implementation keeps a capture stack per context class loader rather than
 * per thread. JShell creates a distinct context class loader for each snippet
 * execution and every worker thread in the snippet pipeline inherits that
 * loader. By keying on the loader we ensure that the buffers remain visible to
 * all worker threads even when JShell hops between them. When a snippet starts,
 * {@link #begin(String)} pushes the caller-provided {@link Buffers buffers}
 * onto the stack associated with that loader. The mirroring streams consult the
 * stack via {@link #current()} on each write; as long as the capture stack for
 * the loader is non-empty, all writes made by any participating worker thread
 * are redirected into the buffers. When the snippet finishes, {@link #end()}
 * pops the most recent scope for that loader. Stacks are maintained as LIFO
 * deques so nested captures (e.g. recursive tool calls) remain isolated.
 * </p>
 *
 * <p>
 * <strong>Note:</strong> the JVM writes stdout/stderr bytes in the order they
 * are produced. If multiple snippets execute concurrently, their console output
 * can interleave even though each snippet has its own scope. The returned
 * {@link com.bitsapplied.descartes.util.EvalResult} still contains the
 * deterministic value/state via {@code events}, but console text should be
 * treated as best-effort in highly concurrent scenarios.
 * </p>
 *
 * <p>
 * Typical usage:
 * </p>
 *
 * <pre>
 * ConsoleCapture.installOnce();                    // install global System.out/System.err wrappers
 * Buffers bufs = new Buffers(outBuf, errBuf);      // allocate buffers for this evaluation
 * String token = ConsoleCapture.register(bufs);    // obtain a scope token
 * ConsoleCapture.begin(token);                     // start capturing
 * ... user code writes to System.out/err ...
 * ConsoleCapture.end();                            // stop capturing
 * ConsoleCapture.unregister(token);                // release the buffers
 * </pre>
 *
 * <p>
 * Note that {@link #installOnce()} should be invoked once during application
 * startup. Subsequent calls are idempotent.
 * {@link #setEchoDuringCapture(boolean, boolean)} allows mirrored writes to
 * optionally continue to the real console.
 * </p>
 */
public final class ConsoleCapture {
  private ConsoleCapture() {
  }

  // Originals preserved for optional uninstall.
  private static volatile PrintStream originalOut = System.out;
  private static volatile PrintStream originalErr = System.err;
  private static volatile boolean installed;

  // True originals captured on very first install - never modified after that
  private static volatile PrintStream trueOriginalOut = null;
  private static volatile PrintStream trueOriginalErr = null;

  // Echo policy during capture. Volatile for racy but safe reads.
  private static volatile boolean echoStdoutDuringCapture = false;
  private static volatile boolean echoStderrDuringCapture = false;

  /** Install PrintStreams that conditionally mirror based on CCL. Idempotent. */
  public static synchronized void installOnce() {
    if (installed)
      return;

    // Get the current streams
    PrintStream currentOut = System.out;
    PrintStream currentErr = System.err;

    // On very first install ever, capture the true originals
    // These are never modified and used for forceUninstall
    if (trueOriginalOut == null) {
      trueOriginalOut = currentOut;
      trueOriginalErr = currentErr;
    }

    // Try to unwrap if already wrapped with MirroringOutputStream
    originalOut = unwrapIfMirroring(currentOut, true);
    originalErr = unwrapIfMirroring(currentErr, false);

    System.setOut(new PrintStream(new MirroringOutputStream(originalOut, true), true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(new MirroringOutputStream(originalErr, false), true, StandardCharsets.UTF_8));
    installed = true;
  }

  /** Helper to unwrap a PrintStream if it contains our MirroringOutputStream */
  @SuppressWarnings("resource") // Not a leak - we're just getting a reference to an existing managed resource
  private static PrintStream unwrapIfMirroring(PrintStream stream, boolean isOut) {
    try {
      // Use reflection to check if the stream contains a MirroringOutputStream
      java.lang.reflect.Field outField = FilterOutputStream.class.getDeclaredField("out");
      outField.setAccessible(true);
      Object innerStream = outField.get(stream);

      if (innerStream instanceof MirroringOutputStream) {
        MirroringOutputStream mirror = (MirroringOutputStream) innerStream;
        // Return the downstream (original) stream
        return mirror.downstream;
      }
    } catch (Exception e) {
      // If reflection fails or not a MirroringOutputStream, use as-is
    }
    return stream;
  }

  /** Restore original System.out/err. */
  public static synchronized void uninstall() {
    if (!installed)
      return;
    System.setOut(originalOut);
    System.setErr(originalErr);
    installed = false;
    // Also clear any active captures to prevent captures after uninstall
    ACTIVE_BY_CL.clear();
  }

  /**
   * Force uninstall and restore to the TRUE original streams captured on first
   * ever install. This is useful in test environments where multiple tests may
   * install ConsoleCapture. After forceUninstall, ConsoleCapture is completely
   * removed from System.out/err.
   */
  public static synchronized void forceUninstall() {
    if (trueOriginalOut != null && trueOriginalErr != null) {
      System.setOut(trueOriginalOut);
      System.setErr(trueOriginalErr);
      originalOut = trueOriginalOut;
      originalErr = trueOriginalErr;
    }
    installed = false;
    ACTIVE_BY_CL.clear();
  }

  /** Configure whether to echo to the real console while capturing. */
  public static void setEchoDuringCapture(boolean echoStdout, boolean echoStderr) {
    echoStdoutDuringCapture = echoStdout;
    echoStderrDuringCapture = echoStderr;
  }

  /** Query current echo settings. */
  public static boolean isEchoStdoutDuringCapture() {
    return echoStdoutDuringCapture;
  }

  public static boolean isEchoStderrDuringCapture() {
    return echoStderrDuringCapture;
  }

  /** Pair of buffers per eval. */
  public static final class Buffers {
    public final ByteArrayOutputStream outBuf;
    public final ByteArrayOutputStream errBuf;

    public Buffers(ByteArrayOutputStream outBuf, ByteArrayOutputStream errBuf) {
      this.outBuf = Objects.requireNonNull(outBuf);
      this.errBuf = Objects.requireNonNull(errBuf);
    }
  }

  private static final class ActiveScope {
    final Buffers buffers;

    ActiveScope(Buffers buffers) {
      this.buffers = buffers;
    }
  }

  // ---- Activation keyed by Context ClassLoader hierarchy + thread ----

  private static final ConcurrentHashMap<String, Buffers> TOKENS = new ConcurrentHashMap<>();
  private static final Object NULL_CLASSLOADER_KEY = new Object();
  private static final ConcurrentHashMap<Object, ConcurrentLinkedDeque<ActiveScope>> ACTIVE_BY_CL = new ConcurrentHashMap<>();

  /**
   * Register buffers for an eval and get a token to activate later inside JShell.
   */
  public static String register(Buffers b) {
    String token = UUID.randomUUID().toString();
    TOKENS.put(token, b);
    return token;
  }

  /** Unregister buffers after eval completes. */
  public static void unregister(String token) {
    TOKENS.remove(token);
  }

  private static Object keyFor(ClassLoader cl) {
    return cl != null ? cl : NULL_CLASSLOADER_KEY;
  }

  /**
   * Activate capture for the current thread's Context ClassLoader. Called inside
   * JShell.
   */
  public static void begin(String token) {
    Buffers b = TOKENS.get(token);
    if (b == null)
      return;
    if (Boolean.getBoolean("descartes.consolecapture.debug")) {
      System.err.println("ConsoleCapture.begin on thread " + Thread.currentThread().getName());
    }
    Thread thread = Thread.currentThread();
    Object key = keyFor(thread.getContextClassLoader());
    ACTIVE_BY_CL.compute(key, (_, deque) -> {
      if (deque == null) {
        deque = new ConcurrentLinkedDeque<>();
      }
      deque.addFirst(new ActiveScope(b));
      return deque;
    });
  }

  /**
   * Deactivate capture for the current thread's Context ClassLoader. Called
   * inside JShell.
   */
  public static void end() {
    Thread thread = Thread.currentThread();
    if (Boolean.getBoolean("descartes.consolecapture.debug")) {
      System.err.println("ConsoleCapture.end on thread " + thread.getName());
    }
    Object key = keyFor(thread.getContextClassLoader());
    ACTIVE_BY_CL.computeIfPresent(key, (_, deque) -> {
      if (deque != null) {
        deque.pollFirst();
        if (deque.isEmpty()) {
          return null;
        }
      }
      return deque;
    });
  }

  /**
   * Resolve active buffers for the current thread by walking the CCL parent
   * chain.
   */
  static Buffers current() {
    Thread thread = Thread.currentThread();
    for (ClassLoader cl = thread.getContextClassLoader(); cl != null; cl = cl.getParent()) {
      ConcurrentLinkedDeque<ActiveScope> deque = ACTIVE_BY_CL.get(keyFor(cl));
      if (deque != null) {
        ActiveScope scope = deque.peekFirst();
        if (scope != null) {
          return scope.buffers;
        }
      }
    }
    ConcurrentLinkedDeque<ActiveScope> bootstrapDeque = ACTIVE_BY_CL.get(NULL_CLASSLOADER_KEY);
    if (bootstrapDeque != null) {
      ActiveScope scope = bootstrapDeque.peekFirst();
      if (scope != null) {
        return scope.buffers;
      }
    }
    return null;
  }

  /**
   * OutputStream that conditionally mirrors and optionally echoes during capture.
   */
  private static final class MirroringOutputStream extends OutputStream {
    private final PrintStream downstream;
    private final boolean isOut;

    MirroringOutputStream(PrintStream downstream, boolean isOut) {
      this.downstream = downstream;
      this.isOut = isOut;
    }

    @Override
    public void write(int b) {
      Buffers buf = current();
      if (buf != null) {
        if (isOut)
          buf.outBuf.write(b);
        else
          buf.errBuf.write(b);
        // optional echo to console during capture
        if ((isOut && echoStdoutDuringCapture) || (!isOut && echoStderrDuringCapture)) {
          downstream.write(b);
        }
      } else {
        downstream.write(b);
      }
    }

    @Override
    public void write(byte[] b, int off, int len) {
      if (len <= 0)
        return;
      Buffers buf = current();
      if (buf != null) {
        if (isOut)
          buf.outBuf.write(b, off, len);
        else
          buf.errBuf.write(b, off, len);
        if ((isOut && echoStdoutDuringCapture) || (!isOut && echoStderrDuringCapture)) {
          downstream.write(b, off, len);
        }
      } else {
        downstream.write(b, off, len);
      }
    }

    @Override
    public void flush() {
      downstream.flush();
    }

    @Override
    public void close() {
      flush();
    }
  }
}
