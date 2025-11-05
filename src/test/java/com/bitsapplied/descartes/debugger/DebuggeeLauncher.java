package com.bitsapplied.descartes.debugger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches SimpleTestApplication as a separate debuggee process with JDWP enabled.
 *
 * <p>
 * This utility spawns a new JVM running {@link SimpleTestApplication} with pre-configured JDWP
 * debugging on a random available port. It waits for the debuggee to be ready before returning,
 * ensuring tests can attach immediately. We are forced to launch an external process because
 * HotSpot's JDWP agent (JDK 11 through 23) cannot be attached dynamically—the agent exports no
 * {@code Agent_OnAttach}—so the only reliable strategy is to run a helper JVM that starts with
 * {@code -agentlib:jdwp}.
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>
 * // In @BeforeAll
 * DebuggeeHandle debuggee = DebuggeeLauncher.launchAndWait();
 * connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
 * // ... tests run ...
 *
 * // In @AfterAll
 * connectionManager.shutdown();
 * debuggee.terminate();
 * </pre>
 */
public class DebuggeeLauncher {
  private static final Logger logger = LoggerFactory.getLogger(DebuggeeLauncher.class);

  /**
   * Launches debuggee in continuous mode (stays alive until killed).
   *
   * @return handle to the launched process
   * @throws IOException if launch fails
   * @throws InterruptedException if interrupted while waiting
   */
  public static DebuggeeHandle launchAndWait() throws IOException, InterruptedException {
    return launchAndWait(true, 10_000);
  }

  /**
   * Launches debuggee with specified mode and timeout.
   *
   * @param continuous if true, runs continuously; if false, runs once and exits
   * @param timeoutMs maximum time to wait for JDWP readiness
   * @return handle to the launched process
   * @throws IOException if launch fails
   * @throws InterruptedException if interrupted while waiting
   */
  public static DebuggeeHandle launchAndWait(boolean continuous, int timeoutMs)
      throws IOException, InterruptedException {

    int jdwpPort = findFreePort();
    logger.info("Launching debuggee on JDWP port {}", jdwpPort);

    ProcessBuilder pb = buildDebuggeeProcess(jdwpPort, continuous);
    pb.redirectErrorStream(false); // Keep stdout/stderr separate

    Process process = pb.start();

    // Start output consumers to prevent deadlock from full buffers
    OutputConsumer stdout = new OutputConsumer(process.getInputStream(), "DEBUGGEE-OUT");
    OutputConsumer stderr = new OutputConsumer(process.getErrorStream(), "DEBUGGEE-ERR");
    stdout.start();
    stderr.start();

    // Wait for JDWP to become ready
    if (!waitForJdwpReady(jdwpPort, timeoutMs)) {
      process.destroyForcibly();
      stdout.stopConsuming();
      stderr.stopConsuming();
      throw new IOException("Debuggee JDWP not ready within " + timeoutMs + "ms");
    }

    // Wait for application startup message
    if (!waitForStartupMessage(stdout, 5000)) {
      logger.warn("Debuggee started but no startup message seen");
    }

    logger.info("Debuggee launched successfully (PID: {}, JDWP port: {})", process.pid(), jdwpPort);

    return new DebuggeeHandle(process, jdwpPort, stdout, stderr);
  }

  /**
   * Builds ProcessBuilder for debuggee JVM.
   */
  private static ProcessBuilder buildDebuggeeProcess(int jdwpPort, boolean continuous) {
    List<String> command = new ArrayList<>();

    // Java executable
    String javaHome = System.getProperty("java.home");
    String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
    command.add(javaBin);

    // JDWP agent configuration
    String jdwpArgs =
        String.format("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:%d", jdwpPort);
    command.add(jdwpArgs);

    // Classpath (reuse test classpath)
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));

    // Main class
    command.add("com.bitsapplied.descartes.debugger.SimpleTestApplication");

    // Arguments
    if (continuous) {
      command.add("--continuous");
    }

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(new File(System.getProperty("user.dir")));

    return pb;
  }

  /**
   * Finds a free port for JDWP.
   */
  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  /**
   * Waits for JDWP port to accept connections.
   */
  private static boolean waitForJdwpReady(int port, int timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    int attempts = 0;

    while (System.currentTimeMillis() < deadline) {
      attempts++;
      long remainingMs = deadline - System.currentTimeMillis();

      try {
        // Use SocketChannel for connect timeout control
        java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", port));

        // Wait for connection with timeout
        java.nio.channels.Selector selector = java.nio.channels.Selector.open();
        channel.register(selector, java.nio.channels.SelectionKey.OP_CONNECT);

        int connectTimeoutMs = (int) Math.min(1000, remainingMs);  // 1s max per attempt
        if (selector.select(connectTimeoutMs) > 0) {
          if (channel.finishConnect()) {
            channel.close();
            selector.close();
            logger.debug("JDWP port {} ready after {} attempts", port, attempts);
            return true;
          }
        }

        channel.close();
        selector.close();

      } catch (IOException e) {
        // Port not ready, retry
      }

      // Brief delay before retry
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    logger.error("JDWP port {} not ready after {} attempts over {}ms", port, attempts, timeoutMs);
    return false;
  }

  /**
   * Waits for "Starting test application..." message in stdout.
   */
  private static boolean waitForStartupMessage(OutputConsumer stdout, int timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;

    while (System.currentTimeMillis() < deadline) {
      List<String> lines = stdout.getLines();
      for (String line : lines) {
        if (line.contains("Starting test application")) {
          return true;
        }
      }

      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    return false;
  }

  /**
   * Handle to a running debuggee process.
   */
  public static class DebuggeeHandle {
    private final Process process;
    private final int jdwpPort;
    private final OutputConsumer stdout;
    private final OutputConsumer stderr;

    DebuggeeHandle(Process process, int jdwpPort, OutputConsumer stdout, OutputConsumer stderr) {
      this.process = process;
      this.jdwpPort = jdwpPort;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public int getJdwpPort() {
      return jdwpPort;
    }

    public Process getProcess() {
      return process;
    }

    public boolean isAlive() {
      return process.isAlive();
    }

    public void terminate() throws InterruptedException {
      logger.info("Terminating debuggee (PID: {})", process.pid());

      // Graceful shutdown first
      process.destroy();
      boolean exited = process.waitFor(5, TimeUnit.SECONDS);

      if (!exited) {
        logger.warn("Debuggee did not exit gracefully, forcing termination");
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }

      // Stop output consumers
      stdout.stopConsuming();
      stderr.stopConsuming();

      int exitCode = process.isAlive() ? -1 : process.exitValue();
      logger.info("Debuggee terminated (exit code: {})", exitCode);
    }

    public List<String> getStdoutLines() {
      return stdout.getLines();
    }

    public List<String> getStderrLines() {
      return stderr.getLines();
    }
  }

  /**
   * Consumes process output streams in background thread.
   */
  private static class OutputConsumer extends Thread {
    private final BufferedReader reader;
    private final String prefix;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;

    OutputConsumer(InputStream stream, String prefix) {
      this.reader = new BufferedReader(new InputStreamReader(stream));
      this.prefix = prefix;
      setDaemon(true);
      setName(prefix + "-Consumer");
    }

    @Override
    public void run() {
      try {
        String line;
        while (running && (line = reader.readLine()) != null) {
          lines.add(line);
          logger.trace("{}: {}", prefix, line);
        }
      } catch (IOException e) {
        if (running) {
          logger.warn("Error reading {}: {}", prefix, e.getMessage());
        }
      }
    }

    public void stopConsuming() {
      running = false;
      interrupt();
    }

    public List<String> getLines() {
      synchronized (lines) {
        return new ArrayList<>(lines);
      }
    }
  }
}
