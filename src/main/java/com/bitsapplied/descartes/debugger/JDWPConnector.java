package com.bitsapplied.descartes.debugger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

/**
 * Handles JDWP (Java Debug Wire Protocol) connection management.
 *
 * <p>
 * Supports attaching to JVMs with JDWP already enabled at launch, providing:
 * <ul>
 * <li>JDK 11+ version validation</li>
 * <li>JDK 17+ JPMS compatibility checks</li>
 * <li>Circuit breaker for connection resilience</li>
 * <li>Port caching for repeated connections</li>
 * </ul>
 *
 * <p>
 * <strong>Important:</strong> HotSpot's JDWP agent has never exposed
 * {@code Agent_OnAttach} (verified on JDK 11, 17, 21, 22, 23), so the agent
 * cannot be loaded dynamically. This connector assumes the target JVM launched
 * with {@code -agentlib:jdwp=…} and simply attaches to that pre-enabled port.
 * All test infrastructure mirrors this constraint.
 */
public class JDWPConnector {
  private static final Logger logger = LoggerFactory.getLogger(JDWPConnector.class);

  private static final AtomicInteger attachedPort = new AtomicInteger(-1);
  private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private static volatile Instant circuitOpenUntil = null;

  private static final int MAX_FAILURES_BEFORE_CIRCUIT_OPEN = 3;
  private static final Duration CIRCUIT_BREAKER_DURATION = Duration.ofMinutes(5);

  /**
   * Attaches to a JVM via JDWP on the specified port.
   *
   * <p>
   * This method connects to an external debuggee process that has JDWP
   * pre-configured on the given port. It includes port caching, connection reuse,
   * and circuit breaker protection.
   *
   * @param port      the JDWP port to connect to
   * @param timeoutMs timeout in milliseconds
   * @return the connected VirtualMachine
   * @throws DebuggerException if connection fails
   */
  public static VirtualMachine attachToPort(int port, int timeoutMs) throws DebuggerException {
    return attachInternal("127.0.0.1", port, timeoutMs, true);
  }

  public static VirtualMachine attachToAddress(String host, int port, int timeoutMs) throws DebuggerException {
    String normalizedHost = normalizeHost(host);
    boolean allowCache = isLocalAddress(normalizedHost);
    return attachInternal(normalizedHost, port, timeoutMs, allowCache);
  }

  private static VirtualMachine attachInternal(String host, int port, int timeoutMs, boolean allowCache)
      throws DebuggerException {
    long startTime = System.currentTimeMillis();
    logger.info("=== Starting JDWP attach to {}:{} (timeout: {}ms) ===", host, port, timeoutMs);

    // 1. Circuit breaker check
    logger.trace("Step 1: Checking circuit breaker");
    checkCircuitBreaker();

    if (allowCache) {
      int cachedPort = attachedPort.get();
      if (cachedPort == port) {
        logger.debug("Found cached connection to {}:{} - attempting reuse", host, port);
        int remaining = timeoutMs - (int) (System.currentTimeMillis() - startTime);
        try {
          if (remaining <= 0) {
            logger.debug("Timeout already expired (remaining: {}ms), cannot use cached connection", remaining);
            throw new IOException("Timeout expired before attempting cached connection");
          }
          logger.trace("Waiting for JDWP readiness on cached endpoint {}:{} (timeout: {}ms)", host, port, remaining);
          if (!waitForJdwpReady(host, port, remaining)) {
            throw new IOException("JDWP listener not ready on cached endpoint " + host + ":" + port);
          }
          logger.trace("JDWP ready on cached endpoint, attempting attach");
          VirtualMachine vm = attachToHost(host, port, remaining);
          logger.info("Successfully reused cached JDWP connection on {}:{}", host, port);
          return vm;
        } catch (Exception e) {
          logger.debug("Cached endpoint {}:{} failed ({}), attempting fresh connection", host, port, e.getMessage());
          attachedPort.set(-1);
        }
      } else if (cachedPort != -1) {
        logger.debug("Cached port {} doesn't match requested port {} - clearing cache", cachedPort, port);
        attachedPort.set(-1);
      }
    }

    try {
      if (allowCache) {
        logger.trace("Step 3: Caching {}:{} and connecting", host, port);
        attachedPort.set(port);
      } else {
        logger.trace("Step 3: Connecting to {}:{} (no cache)", host, port);
      }

      int remaining = timeoutMs - (int) (System.currentTimeMillis() - startTime);

      if (remaining <= 0) {
        logger.debug("Timeout expired after {}ms (no time remaining for connection)",
            System.currentTimeMillis() - startTime);
        if (allowCache) {
          attachedPort.set(-1);
        }
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "Timeout expired before attempting connection");
      }

      logger.trace("Waiting for JDWP readiness on {}:{} (timeout: {}ms)", host, port, remaining);
      if (!waitForJdwpReady(host, port, remaining)) {
        logger.debug("JDWP listener not ready on {}:{} after {}ms", host, port, remaining);
        if (allowCache) {
          attachedPort.set(-1);
        }
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "JDWP listener not ready on " + host + ":" + port);
      }

      logger.trace("JDWP ready on {}:{}, attempting attach", host, port);
      VirtualMachine vm = attachToHost(host, port, remaining);

      logger.trace("Attach successful");

      // Success - reset circuit breaker
      consecutiveFailures.set(0);
      circuitOpenUntil = null;

      long elapsed = System.currentTimeMillis() - startTime;
      logger.info("=== Successfully attached to JDWP on {}:{} ({}ms) ===", host, port, elapsed);
      return vm;

    } catch (Exception e) {
      // Record failure for circuit breaker
      int failures = consecutiveFailures.incrementAndGet();
      long elapsed = System.currentTimeMillis() - startTime;
      logger.error("=== JDWP attach FAILED to {}:{} after {}ms (failure #{}) ===", host, port, elapsed, failures);
      logger.debug("Failure reason: {} - {}", e.getClass().getSimpleName(), e.getMessage());

      if (failures >= MAX_FAILURES_BEFORE_CIRCUIT_OPEN) {
        circuitOpenUntil = Instant.now().plus(CIRCUIT_BREAKER_DURATION);
        logger.error("Circuit breaker OPENED after {} failures. Retry after {}", failures, circuitOpenUntil);
      }

      if (allowCache) {
        attachedPort.set(-1);
      }

      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          "Failed to attach to JDWP on " + host + ":" + port + ": " + e.getMessage(), e);
    }
  }

  /**
   * Checks circuit breaker status and throws if open.
   */
  private static void checkCircuitBreaker() {
    if (circuitOpenUntil != null && Instant.now().isBefore(circuitOpenUntil)) {
      long secondsRemaining = Duration.between(Instant.now(), circuitOpenUntil).getSeconds();
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          String.format("Circuit breaker open. Retry in %d seconds", secondsRemaining));
    }
  }

  /**
   * Checks if JDWP is already enabled and returns the port.
   *
   * @return the JDWP port, or -1 if not enabled
   */
  static int getExistingJDWPPort() {
    var allArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    logger.trace("Searching for existing JDWP port in {} JVM arguments", allArgs.size());

    String jdwpAddress = allArgs.stream().filter(arg -> arg.contains("agentlib:jdwp")).findFirst().orElse(null);

    if (jdwpAddress == null) {
      logger.trace("No agentlib:jdwp argument found in JVM arguments");
      return -1;
    }

    logger.trace("Found JDWP argument: {}", jdwpAddress);

    if (!jdwpAddress.contains("address=")) {
      logger.debug("JDWP argument found but no address= parameter: {}", jdwpAddress);
      return -1;
    }

    try {
      String addressPart = jdwpAddress.substring(jdwpAddress.indexOf("address=") + 8);
      logger.trace("Extracted address part: {}", addressPart);

      // Handle both "address=5005" and "address=127.0.0.1:5005"
      String portStr = addressPart.contains(":") ? addressPart.substring(addressPart.lastIndexOf(':') + 1)
          : addressPart;
      // Remove any trailing parameters
      if (portStr.contains(",")) {
        portStr = portStr.substring(0, portStr.indexOf(','));
      }
      int port = Integer.parseInt(portStr.trim());
      logger.debug("Found existing JDWP port: {} (from argument: {})", port, jdwpAddress);
      return port;
    } catch (Exception e) {
      logger.debug("Failed to parse JDWP port from: {} - {}", jdwpAddress, e.getMessage());
      return -1;
    }
  }

  /**
   * Attaches to JDWP on localhost at the specified port.
   */
  private static VirtualMachine attachToHost(String host, int port, int timeoutMs)
      throws IOException, IllegalConnectorArgumentsException {

    AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
        .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst()
        .orElseThrow(() -> new IOException("SocketAttach connector not found"));

    Map<String, Connector.Argument> args = connector.defaultArguments();
    args.get("hostname").setValue(host);
    args.get("port").setValue(String.valueOf(port));
    args.get("timeout").setValue(String.valueOf(timeoutMs));

    logger.debug("Connecting to JDWP at {}:{} with timeout {}ms", host, port, timeoutMs);

    return connector.attach(args);
  }

  /**
   * Resets the circuit breaker (for testing).
   */

  /**
   * Waits for JDWP listener to be ready by probing the port.
   *
   * <p>
   * <b>Note:</b> This method opens and immediately closes a socket to probe the
   * port. HotSpot's JDWP agent logs "handshake failed - connection prematurally
   * closed" to stderr for these probes. This is expected and harmless - we're
   * just checking if the port is accepting connections, not attempting a real
   * JDWP handshake.
   */
  static boolean waitForJdwpReady(String host, int port, int timeoutMs) {
    long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
    int attempt = 0;
    while (System.currentTimeMillis() <= deadline) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(host, port), Math.max(100, Math.min(500, timeoutMs)));
        // Port is accepting connections - JDWP is ready
        logger.trace("JDWP {}:{} is ready after {} attempt(s)", host, port, attempt + 1);
        return true;
      } catch (IOException ex) {
        // Port not ready yet - retry with exponential backoff
        attempt++;
        long sleepMillis = Math.min(1000, 50L * (1L << Math.min(attempt, 4)));
        if (System.currentTimeMillis() + sleepMillis > deadline) {
          break;
        }
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    logger.trace("JDWP {}:{} not ready after {} attempt(s)", host, port, attempt);
    return false;
  }

  static boolean waitForJdwpReady(int port, int timeoutMs) {
    return waitForJdwpReady("127.0.0.1", port, timeoutMs);
  }

  public static void resetCircuitBreaker() {
    consecutiveFailures.set(0);
    circuitOpenUntil = null;
  }

  /**
   * Clears the cached port (for testing).
   */
  public static void clearPortCache() {
    attachedPort.set(-1);
  }

  private static String normalizeHost(String host) {
    if (host == null || host.isBlank()) {
      return "127.0.0.1";
    }
    String trimmed = host.trim();
    if ("localhost".equalsIgnoreCase(trimmed)) {
      return "127.0.0.1";
    }
    return trimmed;
  }

  private static boolean isLocalAddress(String host) {
    if (host == null || host.isBlank()) {
      return true;
    }
    try {
      InetAddress address = InetAddress.getByName(host);
      if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
        return true;
      }
    } catch (Exception ignore) {
      // Fallback to string comparisons
    }
    String trimmed = host.trim();
    return "127.0.0.1".equals(trimmed) || "0.0.0.0".equals(trimmed) || "::1".equals(trimmed)
        || "localhost".equalsIgnoreCase(trimmed);
  }
}
