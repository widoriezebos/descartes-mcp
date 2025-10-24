package com.bitsapplied.descartes.util;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide stdout/stderr capture with Context ClassLoader scoping.
 *
 * Behavior: - If a thread has an active capture (matched by its Context
 * ClassLoader or parents), writes are mirrored to that capture's buffers. -
 * Echo policy during capture is configurable: - If echo is enabled for the
 * stream, bytes are ALSO forwarded to the real console. - If echo is disabled,
 * the real console is suppressed during capture. - If no active capture, writes
 * go to the real console normally.
 *
 * Usage: ConsoleCapture.installOnce(); // Optional: configure echo
 * ConsoleCapture.setEchoDuringCapture(true, false); // echo stdout, suppress
 * stderr Buffers bufs = new Buffers(new ByteArrayOutputStream(...), new
 * ByteArrayOutputStream(...)); String token = ConsoleCapture.register(bufs); //
 * inside JShell:
 * com.bitsapplied.descartes.util.ConsoleCapture.begin(token); // ...
 * user code prints ...
 * com.bitsapplied.descartes.util.ConsoleCapture.end();
 * ConsoleCapture.unregister(token);
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

  // ---- Activation keyed by Context ClassLoader hierarchy ----

  private static final ConcurrentHashMap<String, Buffers> TOKENS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<ClassLoader, Deque<Buffers>> ACTIVE_BY_CL = new ConcurrentHashMap<>();

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

  /**
   * Activate capture for the current thread's Context ClassLoader. Called inside
   * JShell.
   */
  public static void begin(String token) {
    Buffers b = TOKENS.get(token);
    if (b == null)
      return;
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl != null) {
      ACTIVE_BY_CL.compute(cl, (_, stack) -> {
        if (stack == null)
          stack = new ArrayDeque<>();
        stack.push(b);
        return stack;
      });
    }
  }

  /**
   * Deactivate capture for the current thread's Context ClassLoader. Called
   * inside JShell.
   */
  public static void end() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null)
      return;
    ACTIVE_BY_CL.computeIfPresent(cl, (_, stack) -> {
      if (stack != null && !stack.isEmpty())
        stack.pop();
      return (stack == null || stack.isEmpty()) ? null : stack;
    });
  }

  /**
   * Resolve active buffers for the current thread by walking the CCL parent
   * chain.
   */
  static Buffers current() {
    for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
      Deque<Buffers> stack = ACTIVE_BY_CL.get(cl);
      if (stack != null && !stack.isEmpty())
        return stack.peek();
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
