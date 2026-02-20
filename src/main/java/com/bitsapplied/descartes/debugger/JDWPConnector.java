package com.bitsapplied.descartes.debugger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachineDescriptor;

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
  private static final Object attachLock = new Object();

  private static final AtomicInteger attachedPort = new AtomicInteger(-1);
  private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private static volatile Instant circuitOpenUntil = null;

  private static final int MAX_FAILURES_BEFORE_CIRCUIT_OPEN = 3;
  private static final Duration CIRCUIT_BREAKER_DURATION = Duration.ofSeconds(5);

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
    synchronized (attachLock) {
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
            VirtualMachine vm = attachWithRetries(host, port, remaining);
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

        VirtualMachine vm = attachWithRetries(host, port, remaining);

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
          if (CIRCUIT_BREAKER_DURATION.isZero() || CIRCUIT_BREAKER_DURATION.isNegative()) {
            // Disable open state when duration is non-positive while still resetting
            // failure streak.
            consecutiveFailures.set(0);
            circuitOpenUntil = null;
            logger.warn(
                "Circuit breaker threshold reached after {} failures, but open duration is {}. Continuing retries.",
                failures, CIRCUIT_BREAKER_DURATION);
          } else {
            circuitOpenUntil = Instant.now().plus(CIRCUIT_BREAKER_DURATION);
            // Start a new failure window after cooldown instead of carrying stale failures
            // forever.
            consecutiveFailures.set(0);
            logger.error("Circuit breaker OPENED after {} failures. Retry after {}", failures, circuitOpenUntil);
          }
        }

        if (allowCache) {
          attachedPort.set(-1);
        }

        throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "Failed to attach to JDWP on " + host + ":" + port + ": " + e.getMessage(), e);
      }
    }
  }

  private static VirtualMachine attachWithRetries(String host, int port, int timeoutMs)
      throws IOException, IllegalConnectorArgumentsException {
    long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
    int attempt = 0;
    Exception lastFailure = null;

    while (System.currentTimeMillis() <= deadline) {
      attempt++;
      int remaining = (int) (deadline - System.currentTimeMillis());
      if (remaining <= 0) {
        break;
      }

      int attemptTimeout = Math.max(100, Math.min(remaining, 1000));
      try {
        logger.trace("JDWP attach attempt {} to {}:{} (attemptTimeout={}ms, remaining={}ms)", attempt, host, port,
            attemptTimeout, remaining);
        return attachToHost(host, port, attemptTimeout);
      } catch (IOException | IllegalConnectorArgumentsException e) {
        lastFailure = e;
        if (!isRetryableAttachFailure(e)) {
          throw e;
        }

        long sleepMillis = Math.min(500L, 50L * (1L << Math.min(attempt, 3)));
        if (System.currentTimeMillis() + sleepMillis > deadline) {
          break;
        }

        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while attaching to JDWP", ie);
        }
      }
    }

    if (lastFailure instanceof IOException io) {
      throw io;
    }
    if (lastFailure instanceof IllegalConnectorArgumentsException connectorArgs) {
      throw connectorArgs;
    }

    throw new IOException("Timed out while attaching to JDWP on " + host + ":" + port);
  }

  private static boolean isRetryableAttachFailure(Exception e) {
    if (e instanceof IllegalConnectorArgumentsException) {
      return false;
    }
    String message = e.getMessage();
    if (message == null) {
      return true;
    }
    String normalized = message.toLowerCase();
    return normalized.contains("connection refused") || normalized.contains("connection reset")
        || normalized.contains("connection closed") || normalized.contains("connection prematurely closed")
        || normalized.contains("timed out") || normalized.contains("handshake")
        || normalized.contains("transport error");
  }

  /**
   * Checks circuit breaker status and throws if open.
   */
  private static void checkCircuitBreaker() {
    Instant openUntil = circuitOpenUntil;
    if (openUntil == null) {
      return;
    }

    Instant now = Instant.now();
    if (now.isBefore(openUntil)) {
      long secondsRemaining = Duration.between(now, openUntil).getSeconds();
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          String.format("Circuit breaker open. Retry in %d seconds", secondsRemaining));
    }

    // Cooldown has elapsed, so reopen attempts with a clean failure window.
    circuitOpenUntil = null;
    consecutiveFailures.set(0);
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

  /**
   * Discovers all Java processes running in debug mode on the local machine.
   *
   * <p>
   * Uses the Java Attach API to list all JVMs running as the same user, then
   * filters for processes with JDWP enabled.
   *
   * @return list of discovered JDWP processes
   */
  public static List<JdwpProcess> discoverLocalJdwpProcesses() {
    logger.debug("Discovering local JDWP processes using Java Attach API");
    List<JdwpProcess> result = new ArrayList<>();

    try {
      List<VirtualMachineDescriptor> vms = com.sun.tools.attach.VirtualMachine.list();
      logger.debug("Found {} JVM process(es) to check", vms.size());

      for (VirtualMachineDescriptor vmd : vms) {
        try {
          logger.trace("Checking process: {} (PID: {})", vmd.displayName(), vmd.id());
          com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(vmd.id());

          // Get agent properties which contain runtime arguments
          String jdwpConfig = extractJdwpConfig(vm);

          if (jdwpConfig != null) {
            int port = parseJdwpPort(jdwpConfig);
            if (port > 0) {
              JdwpProcess process = new JdwpProcess(vmd.id(), vmd.displayName(), "localhost", port);
              result.add(process);
              logger.debug("Discovered JDWP process: {} (PID: {}, port: {})", process.displayName, process.pid,
                  process.jdwpPort);
            }
          }

          vm.detach();
        } catch (AttachNotSupportedException | IOException e) {
          logger.trace("Cannot attach to process {} (PID: {}): {}", vmd.displayName(), vmd.id(), e.getMessage());
        }
      }

      logger.info("Discovery complete: found {} JDWP process(es)", result.size());
    } catch (Exception e) {
      logger.error("Error during JDWP process discovery: {}", e.getMessage(), e);
    }

    return result;
  }

  /**
   * Finds a JDWP process matching the given pattern.
   *
   * <p>
   * Matching strategy:
   * <ol>
   * <li>Try exact match (case-insensitive)</li>
   * <li>Try wildcard match using * and ? patterns</li>
   * <li>If multiple matches, return first</li>
   * <li>If no matches, throw exception with list of available processes</li>
   * </ol>
   *
   * @param pattern the pattern to match (supports * and ? wildcards,
   *                case-insensitive)
   * @return the discovered process
   * @throws DebuggerException if no process matches or no debug processes found
   */
  public static JdwpProcess findByPattern(String pattern) throws DebuggerException {
    logger.info("Searching for JDWP process matching pattern: '{}'", pattern);

    List<JdwpProcess> all = discoverLocalJdwpProcesses();

    if (all.isEmpty()) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          "No Java debug processes found on this machine. "
              + "Ensure the target JVM is running with -agentlib:jdwp=... enabled.");
    }

    // Step 1: Try exact match (case-insensitive)
    Optional<JdwpProcess> exact = all.stream().filter(p -> p.displayName.equalsIgnoreCase(pattern)).findFirst();

    if (exact.isPresent()) {
      logger.info("Found exact match: {} (PID: {}, port: {})", exact.get().displayName, exact.get().pid,
          exact.get().jdwpPort);
      return exact.get();
    }

    // Step 2: Try wildcard match
    String regex = wildcardToRegex(pattern);
    Pattern compiled = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

    List<JdwpProcess> matches = all.stream().filter(p -> compiled.matcher(p.displayName).find())
        .collect(Collectors.toList());

    if (matches.isEmpty()) {
      // Build helpful error message with all available processes
      String available = all.stream()
          .map(p -> String.format("  - %s (PID: %s, port: %d)", p.displayName, p.pid, p.jdwpPort))
          .collect(Collectors.joining("\n"));

      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, String.format(
          "No debug process found matching pattern: '%s'\n\nAvailable debug processes:\n%s", pattern, available));
    }

    // Return first match (with logging if multiple)
    JdwpProcess selected = matches.get(0);
    if (matches.size() > 1) {
      logger.warn("Multiple processes match pattern '{}': {}. Using first: {} (PID: {})", pattern, matches.size(),
          selected.displayName, selected.pid);
      logger.debug("All matches: {}",
          matches.stream().map(p -> p.displayName + " (PID: " + p.pid + ")").collect(Collectors.joining(", ")));
    } else {
      logger.info("Found match: {} (PID: {}, port: {})", selected.displayName, selected.pid, selected.jdwpPort);
    }

    return selected;
  }

  /**
   * Extracts JDWP configuration from an attached VM.
   *
   * <p>
   * Uses multiple strategies to detect JDWP:
   * <ol>
   * <li>Check sun.jvm.args system property</li>
   * <li>Scan all system properties for agentlib:jdwp</li>
   * <li>Check if this is our own JVM and use getExistingJDWPPort()</li>
   * <li>Try loading agent properties (sun.jdwp.listenerAddress)</li>
   * </ol>
   *
   * @param vm the attached virtual machine
   * @return JDWP configuration string, or null if not found
   */
  private static String extractJdwpConfig(com.sun.tools.attach.VirtualMachine vm) {
    try {
      var props = vm.getSystemProperties();

      // Strategy 1: Check sun.jvm.args (most common location)
      String vmArgs = props.getProperty("sun.jvm.args", "");
      if (vmArgs.contains("agentlib:jdwp")) {
        logger.trace("Found JDWP in sun.jvm.args: {}", vmArgs);
        return vmArgs;
      }

      // Strategy 2: Check sun.jvm.flags
      String vmFlags = props.getProperty("sun.jvm.flags", "");
      if (vmFlags.contains("agentlib:jdwp")) {
        logger.trace("Found JDWP in sun.jvm.flags: {}", vmFlags);
        return vmFlags;
      }

      // Strategy 3: Scan all system properties
      for (Object key : props.keySet()) {
        String value = props.getProperty(key.toString(), "");
        if (value.contains("agentlib:jdwp")) {
          logger.trace("Found JDWP in property {}: {}", key, value);
          return value;
        }
      }

      // Strategy 4: Check if this is our own JVM
      String currentPid = String.valueOf(ProcessHandle.current().pid());
      if (vm.id().equals(currentPid)) {
        int port = getExistingJDWPPort();
        if (port > 0) {
          logger.trace("This is our own JVM, found JDWP port: {}", port);
          return "address=" + port;
        }
      }

      // Strategy 5: Try agent properties (requires loading agent library first)
      // This property is set when JDWP is active
      try {
        var agentProps = vm.getAgentProperties();
        String listenerAddress = agentProps.getProperty("sun.jdwp.listenerAddress");
        if (listenerAddress != null && !listenerAddress.isEmpty()) {
          logger.trace("Found JDWP listener address: {}", listenerAddress);
          return "address=" + listenerAddress;
        }
      } catch (Exception e) {
        logger.trace("Could not access agent properties: {}", e.getMessage());
      }

    } catch (Exception e) {
      logger.trace("Error extracting JDWP config from VM {}: {}", vm.id(), e.getMessage());
    }

    return null;
  }

  /**
   * Parses JDWP port from a JDWP configuration string.
   *
   * @param jdwpConfig the JDWP configuration string
   * @return the port number, or -1 if not found
   */
  private static int parseJdwpPort(String jdwpConfig) {
    try {
      if (jdwpConfig.contains("address=")) {
        String addressPart = jdwpConfig.substring(jdwpConfig.indexOf("address=") + 8);

        // Handle both "address=5005" and "address=127.0.0.1:5005" and "address=*:5005"
        String portStr = addressPart.contains(":") ? addressPart.substring(addressPart.lastIndexOf(':') + 1)
            : addressPart;

        // Remove any trailing parameters
        if (portStr.contains(",")) {
          portStr = portStr.substring(0, portStr.indexOf(','));
        }
        if (portStr.contains(" ")) {
          portStr = portStr.substring(0, portStr.indexOf(' '));
        }

        return Integer.parseInt(portStr.trim());
      }
    } catch (Exception e) {
      logger.trace("Failed to parse JDWP port from: {} - {}", jdwpConfig, e.getMessage());
    }

    return -1;
  }

  /**
   * Converts a wildcard pattern to a regex pattern.
   *
   * <p>
   * Supports:
   * <ul>
   * <li>* - matches any characters</li>
   * <li>? - matches single character</li>
   * </ul>
   *
   * @param pattern the wildcard pattern
   * @return the equivalent regex pattern
   */
  private static String wildcardToRegex(String pattern) {
    StringBuilder sb = new StringBuilder();
    for (char c : pattern.toCharArray()) {
      switch (c) {
      case '*':
        sb.append(".*");
        break;
      case '?':
        sb.append(".");
        break;
      case '.':
      case '\\':
      case '+':
      case '^':
      case '$':
      case '(':
      case ')':
      case '[':
      case ']':
      case '{':
      case '}':
      case '|':
        sb.append('\\').append(c);
        break;
      default:
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Represents a discovered JDWP process.
   */
  public static class JdwpProcess {
    public final String pid;
    public final String displayName;
    public final String host;
    public final int jdwpPort;

    public JdwpProcess(String pid, String displayName, String host, int jdwpPort) {
      this.pid = pid;
      this.displayName = displayName;
      this.host = host;
      this.jdwpPort = jdwpPort;
    }

    @Override
    public String toString() {
      return String.format("JdwpProcess[pid=%s, name=%s, %s:%d]", pid, displayName, host, jdwpPort);
    }
  }
}
