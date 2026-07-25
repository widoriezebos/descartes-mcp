package com.bitsapplied.descartes.debugger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PID-file bookkeeping and MCP-port conflict resolution for the remote debug
 * proxy.
 *
 * <p>
 * Each proxy instance records its PID, build id, and start time in
 * {@code <pid-dir>/proxy-<mcpPort>.pid} so a later launch that finds the port
 * occupied can identify the owner and, with {@code --replace}, terminate it.
 * The PID directory defaults to {@code ~/.descartes-mcp} and can be overridden
 * with the {@code descartes.pid.dir} system property or the
 * {@code DESCARTES_PID_DIR} environment variable.
 */
final class ProxyInstanceGuard {

  private static final Logger logger = LoggerFactory.getLogger(ProxyInstanceGuard.class);

  record ExistingInstance(long pid, String build, String started) {

    String describe() {
      return String.format("pid %d, build %s, started %s", pid, build, started);
    }
  }

  private ProxyInstanceGuard() {
  }

  static Path pidFile(int mcpPort) {
    return pidDirectory().resolve("proxy-" + mcpPort + ".pid");
  }

  private static Path pidDirectory() {
    String override = System.getProperty("descartes.pid.dir");
    if (override == null || override.isBlank()) {
      override = System.getenv("DESCARTES_PID_DIR");
    }
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return Path.of(System.getProperty("user.home"), ".descartes-mcp");
  }

  /**
   * Records this process as the owner of the given MCP port. Best-effort: a
   * failure is logged but never blocks startup.
   */
  static void writePidFile(int mcpPort, String buildId) {
    Path file = pidFile(mcpPort);
    Properties props = new Properties();
    props.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
    props.setProperty("build", buildId);
    props.setProperty("started", Instant.now().toString());
    props.setProperty("mcpPort", Integer.toString(mcpPort));
    try {
      Files.createDirectories(file.getParent());
      try (OutputStream out = Files.newOutputStream(file)) {
        props.store(out, "descartes-mcp remote debug proxy instance");
      }
      logger.info("Recorded proxy instance in {}", file);
    } catch (IOException e) {
      logger.warn("Could not write PID file {}: {}", file, e.getMessage());
    }
  }

  /** Removes this instance's PID file. Best-effort. */
  static void deletePidFile(int mcpPort) {
    Path file = pidFile(mcpPort);
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      logger.warn("Could not delete PID file {}: {}", file, e.getMessage());
    }
  }

  /**
   * Identifies the live process owning the given MCP port: first via this
   * proxy's PID file (stale files are cleaned up), then via a best-effort
   * {@code lsof} lookup for owners that predate PID-file support.
   */
  static Optional<ExistingInstance> findExisting(int mcpPort) {
    Optional<ExistingInstance> fromFile = readPidFile(mcpPort);
    if (fromFile.isPresent()) {
      return fromFile;
    }
    return findViaLsof(mcpPort);
  }

  private static Optional<ExistingInstance> readPidFile(int mcpPort) {
    Path file = pidFile(mcpPort);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
    } catch (IOException e) {
      logger.warn("Could not read PID file {}: {}", file, e.getMessage());
      return Optional.empty();
    }
    long pid;
    try {
      pid = Long.parseLong(props.getProperty("pid", "").trim());
    } catch (NumberFormatException e) {
      logger.warn("PID file {} has no valid pid entry; ignoring it", file);
      return Optional.empty();
    }
    if (pid == ProcessHandle.current().pid()) {
      return Optional.empty();
    }
    if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
      logger.info("Removing stale PID file {} (pid {} is not running)", file, pid);
      deletePidFile(mcpPort);
      return Optional.empty();
    }
    return Optional.of(new ExistingInstance(pid, props.getProperty("build", "unknown"),
        props.getProperty("started", "unknown")));
  }

  private static Optional<ExistingInstance> findViaLsof(int mcpPort) {
    try {
      Process process = new ProcessBuilder("lsof", "-ti", "tcp:" + mcpPort, "-sTCP:LISTEN")
          .redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!process.waitFor(5, TimeUnit.SECONDS) || output.isEmpty()) {
        return Optional.empty();
      }
      long pid = Long.parseLong(output.lines().findFirst().orElse("").trim());
      if (pid == ProcessHandle.current().pid()) {
        return Optional.empty();
      }
      return Optional.of(new ExistingInstance(pid, "unknown (no PID file)", "unknown"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      logger.debug("lsof lookup for port {} failed: {}", mcpPort, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Terminates the given process: SIGTERM first, escalating to SIGKILL after
   * half the timeout.
   *
   * @return true once the process has exited within the timeout
   */
  static boolean terminate(long pid, Duration timeout) {
    if (pid == ProcessHandle.current().pid()) {
      throw new IllegalArgumentException("Refusing to terminate own process (pid " + pid + ")");
    }
    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
    if (handle.isEmpty() || !handle.get().isAlive()) {
      return true;
    }
    ProcessHandle process = handle.get();
    process.destroy();
    long halfMs = Math.max(1, timeout.toMillis() / 2);
    if (awaitExit(process, halfMs)) {
      return true;
    }
    logger.warn("Process {} did not exit after SIGTERM; forcing termination", pid);
    process.destroyForcibly();
    return awaitExit(process, halfMs);
  }

  private static boolean awaitExit(ProcessHandle process, long timeoutMs) {
    try {
      process.onExit().get(timeoutMs, TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException e) {
      // onExit completed exceptionally; treat as exited.
      return true;
    }
  }
}
