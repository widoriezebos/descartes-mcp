package com.bitsapplied.descartes.debugger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InaccessibleObjectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
 * Supports self-attach debugging with:
 * <ul>
 * <li>JDK 11+ version validation</li>
 * <li>JDK 17+ JPMS compatibility checks</li>
 * <li>Circuit breaker for connection resilience</li>
 * <li>Port caching for repeated connections</li>
 * <li>Dynamic JDWP enablement</li>
 * </ul>
 */
public class JDWPConnector {
  private static final Logger logger = LoggerFactory.getLogger(JDWPConnector.class);

  private static final AtomicInteger attachedPort = new AtomicInteger(-1);
  private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private static volatile Instant circuitOpenUntil = null;

  private static final int MAX_FAILURES_BEFORE_CIRCUIT_OPEN = 3;
  private static final Duration CIRCUIT_BREAKER_DURATION = Duration.ofMinutes(5);

  /**
   * Attaches to the current JVM via JDWP for debugging.
   *
   * @param timeoutMs timeout in milliseconds
   * @return the connected VirtualMachine
   * @throws DebuggerException if connection fails
   */
  public static VirtualMachine attachToSelf(int timeoutMs) throws DebuggerException {
    long startTime = System.currentTimeMillis();

    // 1. JDK version check (require 11+)
    validateJdkVersion();

    // 2. Circuit breaker check
    checkCircuitBreaker();

    // 3. Check if already attached (use cached port)
    int cachedPort = attachedPort.get();
    if (cachedPort != -1) {
      logger.debug("Using cached JDWP port: {}", cachedPort);
      int remaining = timeoutMs - (int) (System.currentTimeMillis() - startTime);
      try {
        if (remaining <= 0 || !waitForJdwpReady(cachedPort, remaining)) {
          throw new IOException("JDWP listener not ready on cached port " + cachedPort);
        }
        VirtualMachine vm = attachToLocalhost(cachedPort, remaining);
        if (!validateVmIdentity(vm)) {
          logger.warn("Cached JDWP port {} belongs to a different process. Clearing cache.", cachedPort);
          safeDispose(vm);
          attachedPort.set(-1);
        } else {
          return vm;
        }
      } catch (Exception e) {
        logger.warn("Cached port {} failed, attempting fresh connection: {}", cachedPort, e.getMessage());
        attachedPort.set(-1); // Invalidate cache
      }
    }

    try {
      // 4. Ensure self-attach is enabled
      requireSelfAttachEnabled();

      // 5. Get or enable JDWP port
      int jdwpPort = getExistingJDWPPort();
      if (jdwpPort == -1) {
        jdwpPort = enableJDWP();
      }

      // 6. Cache and connect
      attachedPort.set(jdwpPort);
      int remaining = timeoutMs - (int) (System.currentTimeMillis() - startTime);
      if (remaining <= 0 || !waitForJdwpReady(jdwpPort, remaining)) {
        attachedPort.set(-1);
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "JDWP listener not ready on port " + jdwpPort);
      }
      VirtualMachine vm = attachToLocalhost(jdwpPort, remaining);
      if (!validateVmIdentity(vm)) {
        safeDispose(vm);
        attachedPort.set(-1);
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "Resolved JDWP port belongs to a different process instance. Cleared cache for retry.");
      }

      // Success - reset circuit breaker
      consecutiveFailures.set(0);
      circuitOpenUntil = null;

      logger.info("Successfully attached to JDWP on port {}", jdwpPort);
      return vm;

    } catch (Exception e) {
      // Record failure for circuit breaker
      int failures = consecutiveFailures.incrementAndGet();
      if (failures >= MAX_FAILURES_BEFORE_CIRCUIT_OPEN) {
        circuitOpenUntil = Instant.now().plus(CIRCUIT_BREAKER_DURATION);
        logger.error("Circuit breaker opened after {} failures. Retry after {}", failures, circuitOpenUntil);
      }

      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          "Failed to attach to JDWP: " + e.getMessage(), e);
    }
  }

  /**
   * Validates that the JDK version is 11 or higher.
   */
  private static void validateJdkVersion() {
    int javaVersion = Runtime.version().feature();
    if (javaVersion < 11) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          String.format("Debugger requires JDK 11+ (current: JDK %d)", javaVersion));
    }
    logger.debug("JDK version check passed: JDK {}", javaVersion);
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
   * Ensures self-attach is enabled and JPMS is properly configured for JDK 17+.
   */
  private static void requireSelfAttachEnabled() {
    // Check 1: Self-attach property
    String allowAttach = System.getProperty("jdk.attach.allowAttachSelf");
    if (!Boolean.parseBoolean(allowAttach)) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          "Self-attach disabled. Add JVM flag: -Djdk.attach.allowAttachSelf=true");
    }

    // Check 2: JDK 17+ JPMS verification
    int javaVersion = Runtime.version().feature();
    if (javaVersion >= 17) {
      try {
        // Attempt to access Attach API - will fail if --add-opens not set
        com.sun.tools.attach.VirtualMachine.list();
        logger.info("JDK 17+ JPMS check passed");
      } catch (IllegalAccessError | InaccessibleObjectException e) {
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "JDK 17+ requires JVM flag: --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED");
      } catch (Exception e) {
        // Other exceptions are OK - we just need to verify reflection access works
        logger.debug("JPMS check completed with exception (acceptable): {}", e.getMessage());
      }
    }
  }

  /**
   * Checks if JDWP is already enabled and returns the port.
   *
   * @return the JDWP port, or -1 if not enabled
   */
  private static int getExistingJDWPPort() {
    String jdwpAddress = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(arg -> arg.contains("agentlib:jdwp")).findFirst().orElse(null);

    if (jdwpAddress != null && jdwpAddress.contains("address=")) {
      try {
        String addressPart = jdwpAddress.substring(jdwpAddress.indexOf("address=") + 8);
        // Handle both "address=5005" and "address=127.0.0.1:5005"
        String portStr = addressPart.contains(":") ? addressPart.substring(addressPart.lastIndexOf(':') + 1)
            : addressPart;
        // Remove any trailing parameters
        if (portStr.contains(",")) {
          portStr = portStr.substring(0, portStr.indexOf(','));
        }
        int port = Integer.parseInt(portStr.trim());
        logger.debug("Found existing JDWP port: {}", port);
        return port;
      } catch (Exception e) {
        logger.warn("Failed to parse JDWP port from: {}", jdwpAddress);
      }
    }

    return -1;
  }

  /**
   * Dynamically enables JDWP on the current JVM.
   *
   * @return the JDWP port
   */
  private static int enableJDWP() throws DebuggerException {
    try {
      String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
      String pid = nameOfRunningVM.substring(0, nameOfRunningVM.indexOf('@'));

      com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(pid);

      // Find a free port
      int port = findFreePort();

      // Start JDWP agent
      String jdwpArgs = String.format("transport=dt_socket,server=y,suspend=n,address=127.0.0.1:%d", port);
      vm.startLocalManagementAgent(); // Ensure management agent is started

      // Load JDWP agent
      try {
        vm.loadAgentLibrary("jdwp", jdwpArgs);
      } catch (Exception e) {
        // Some JVMs don't support dynamic JDWP loading
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "Cannot enable JDWP dynamically. Start JVM with: -agentlib:jdwp=" + jdwpArgs);
      }

      vm.detach();

      logger.info("Dynamically enabled JDWP on port {}", port);

      if (!waitForJdwpReady(port, 2000)) {
        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "JDWP listener failed to start on dynamically enabled port " + port);
      }

      return port;
    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, "Failed to enable JDWP: " + e.getMessage(),
          e);
    }
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
   * Attaches to JDWP on localhost at the specified port.
   */
  private static VirtualMachine attachToLocalhost(int port, int timeoutMs)
      throws IOException, IllegalConnectorArgumentsException {

    AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
        .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst()
        .orElseThrow(() -> new IOException("SocketAttach connector not found"));

    Map<String, Connector.Argument> args = connector.defaultArguments();
    args.get("hostname").setValue("127.0.0.1");
    args.get("port").setValue(String.valueOf(port));
    args.get("timeout").setValue(String.valueOf(timeoutMs));

    logger.debug("Connecting to JDWP at 127.0.0.1:{} with timeout {}ms", port, timeoutMs);

    return connector.attach(args);
  }

  /**
   * Resets the circuit breaker (for testing).
   */

  private static boolean waitForJdwpReady(int port, int timeoutMs) {
    long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
    int attempt = 0;
    while (System.currentTimeMillis() <= deadline) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), Math.max(100, Math.min(500, timeoutMs)));
        return true;
      } catch (IOException ex) {
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
    return false;
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

  private static boolean validateVmIdentity(VirtualMachine vm) {
    if (vm == null) {
      return false;
    }

    try {
      String remoteCommand = vm.name();
      String localCommand = System.getProperty("sun.java.command");
      if (remoteCommand == null || localCommand == null) {
        return true;
      }

      if (remoteCommand.equals(localCommand) || remoteCommand.endsWith(localCommand)
          || localCommand.endsWith(remoteCommand)) {
        return true;
      }

      logger.warn("JDWP attach connected to unexpected command. Remote='{}', Local='{}'", remoteCommand, localCommand);
      return true;
    } catch (Exception e) {
      logger.debug("Unable to validate VM identity: {}", e.getMessage());
      return true;
    }
  }

  private static void safeDispose(VirtualMachine vm) {
    if (vm == null) {
      return;
    }
    try {
      vm.dispose();
    } catch (Exception ignore) {
      // Best-effort cleanup
    }
  }
}
