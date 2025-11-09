package com.bitsapplied.descartes.debugger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.AccessWatchpointRequest;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.ClassUnloadRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.jdi.request.MonitorContendedEnterRequest;
import com.sun.jdi.request.MonitorContendedEnteredRequest;
import com.sun.jdi.request.MonitorWaitRequest;
import com.sun.jdi.request.MonitorWaitedRequest;
import com.sun.jdi.request.StepRequest;
import com.sun.jdi.request.ThreadDeathRequest;
import com.sun.jdi.request.ThreadStartRequest;
import com.sun.jdi.request.VMDeathRequest;

/**
 * Manages the lifecycle of the JDWP connection for debugging sessions.
 *
 * <p>
 * This class implements a lifecycle-managed connection pattern where a single
 * VirtualMachine instance is reused across multiple debugging sessions within
 * the same test class or application lifecycle. The manager expects that the
 * target JVM was launched with JDWP enabled (HotSpot cannot enable JDWP after
 * startup because the agent lacks Agent_OnAttach). This approach:
 * <ul>
 * <li>Eliminates reconnection overhead between sessions (~10s per
 * reconnect)</li>
 * <li>Prevents timing races in self-attach debugging scenarios</li>
 * <li>Ensures clean state between sessions via comprehensive reset()</li>
 * <li>Provides auto-recovery from connection failures</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li><b>Initialization</b>: Create instance (typically in @BeforeAll for
 * tests)</li>
 * <li><b>Connection</b>: First getOrCreateConnection() establishes JDWP
 * connection</li>
 * <li><b>Session Use</b>: Multiple sessions borrow the same VM instance</li>
 * <li><b>Reset</b>: Between sessions, reset() clears all state</li>
 * <li><b>Shutdown</b>: shutdown() disposes VM (typically in @AfterAll)</li>
 * </ol>
 *
 * <h2>State Reset Coverage</h2>
 * <p>
 * The reset() method ensures complete state cleanup by:
 * <ul>
 * <li><b>Resuming ALL threads</b> including virtual threads (JDK 21+) - handles
 * multiple suspend counts</li>
 * <li><b>Resuming VM</b> global suspend state</li>
 * <li><b>Clearing ALL EventRequest types</b>: breakpoints, steps, watchpoints,
 * method entry/exit, exceptions, class prepare/unload, thread start/death,
 * monitor events (13 total types)</li>
 * <li><b>Verifying clean state</b>: Asserts no suspended threads or active
 * requests remain</li>
 * </ul>
 *
 * <h2>Health Checks</h2>
 * <p>
 * Multi-level health validation with automatic recovery:
 * <ol>
 * <li><b>Level 1</b> (cheap, local): vm.canGetSystemProperties() - detects
 * disposed VM</li>
 * <li><b>Level 2</b> (cheap, local): process.isAlive() - detects target JVM
 * termination</li>
 * <li><b>Level 3</b> (network round-trip): vm.version() - detects JDWP
 * transport wedge</li>
 * </ol>
 * On health check failure, the connection is invalidated and the next
 * getOrCreateConnection() automatically reconnects.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * All public methods are synchronized to prevent concurrent access to the
 * shared VirtualMachine instance. This is necessary because:
 * <ul>
 * <li>VirtualMachine is not thread-safe</li>
 * <li>Multiple test threads or application threads may access the manager</li>
 * <li>Connection state (vm field) must be consistently visible across
 * threads</li>
 * </ul>
 *
 * <h2>Usage Example - Tests</h2>
 *
 * <pre>
 * &#64;TestInstance(TestInstance.Lifecycle.PER_CLASS)
 * class DebuggerTest {
 *   private JDWPConnectionManager connectionManager;
 *   private DebuggerService service;
 *
 *   &#64;BeforeAll
 *   void setupConnection() {
 *     connectionManager = new JDWPConnectionManager();
 *   }
 *
 *   &#64;BeforeEach
 *   void setupSession() {
 *     service = new DebuggerService(connectionManager);
 *     service.start();
 *   }
 *
 *   &#64;AfterEach
 *   void cleanupSession() {
 *     service.stop(); // Calls reset()
 *     assertNoSuspendedThreads();
 *   }
 *
 *   &#64;AfterAll
 *   void shutdownConnection() {
 *     connectionManager.shutdown();
 *   }
 * }
 * </pre>
 *
 * <h2>Usage Example - Production</h2>
 *
 * <pre>
 * // Spring/DI managed lifecycle
 * &#64;Component
 * class DebuggerConfig {
 *   &#64;Bean
 *   &#64;PreDestroy
 *   JDWPConnectionManager connectionManager() {
 *     JDWPConnectionManager manager = new JDWPConnectionManager();
 *     Runtime.getRuntime().addShutdownHook(new Thread(manager::shutdown));
 *     return manager;
 *   }
 * }
 * </pre>
 *
 * @see DebuggerService
 * @see JDWPConnector
 */
public class JDWPConnectionManager {
  private static final Logger logger = LoggerFactory.getLogger(JDWPConnectionManager.class);

  // Connection state
  private volatile VirtualMachine vm;
  private volatile boolean shutdown = false;
  private final String jdwpHost; // Optional: host to connect to (null implies localhost/auto-detect)
  private final Integer jdwpPort; // Optional: if set, connects to this port; if null, auto-detects

  // Metrics
  private final ConnectionMetrics metrics = new ConnectionMetrics();

  /**
   * Creates a new connection manager with auto-detected JDWP port.
   * <p>
   * The connection is not established until the first call to
   * {@link #getOrCreateConnection(int)}. This constructor will detect the JDWP
   * port from JVM arguments (self-attach mode).
   */
  public JDWPConnectionManager() {
    this(null, null);
  }

  /**
   * Creates a new connection manager that connects to a specific JDWP port.
   * <p>
   * The connection is not established until the first call to
   * {@link #getOrCreateConnection(int)}. This constructor is used for external
   * debuggee processes where the JDWP port is known in advance.
   *
   * @param jdwpPort the JDWP port to connect to
   */
  public JDWPConnectionManager(int jdwpPort) {
    this(null, jdwpPort);
  }

  public JDWPConnectionManager(String jdwpHost, int jdwpPort) {
    this(jdwpHost, Integer.valueOf(jdwpPort));
  }

  /**
   * Private constructor for shared initialization.
   */
  private JDWPConnectionManager(String jdwpHost, Integer jdwpPort) {
    this.jdwpHost = (jdwpHost != null && !jdwpHost.isBlank()) ? jdwpHost.trim() : null;
    this.jdwpPort = jdwpPort;
    if (jdwpPort == null) {
      logger.debug("JDWPConnectionManager created (auto-detect port)");
    } else if (this.jdwpHost != null) {
      logger.debug("JDWPConnectionManager created (host: {}, port: {})", this.jdwpHost, jdwpPort);
    } else {
      logger.debug("JDWPConnectionManager created (port: {})", jdwpPort);
    }
  }

  /**
   * Gets the existing connection or creates a new one if none exists.
   *
   * <p>
   * This method is idempotent - if a healthy connection already exists, it
   * returns that connection. If the connection is unhealthy or doesn't exist, it
   * establishes a new one.
   *
   * <p>
   * Thread-safe: Synchronized to prevent concurrent connection attempts.
   *
   * @param timeoutMs timeout for connection establishment in milliseconds
   * @return the VirtualMachine instance
   * @throws DebuggerException                  if connection fails
   * @throws IllegalStateException              if manager has been shut down
   * @throws VMDisconnectedException            if VM disconnects during
   *                                            connection
   * @throws IllegalConnectorArgumentsException if JDWP connector args invalid
   * @throws IOException                        if network communication fails
   */
  public synchronized VirtualMachine getOrCreateConnection(int timeoutMs) throws DebuggerException {
    if (shutdown) {
      throw new IllegalStateException("Connection manager has been shut down");
    }

    // Check if we need to establish a new connection
    if (vm == null || !isHealthy()) {
      if (vm != null) {
        logger.info("Existing connection unhealthy, establishing new connection");
      } else {
        logger.info("No connection exists, establishing new connection");
      }

      vm = establishConnection(timeoutMs);
      metrics.recordConnection();
    }

    return vm;
  }

  /**
   * Resets the session state while keeping the connection alive.
   *
   * <p>
   * <b>CRITICAL ORDERING:</b> This method must be called AFTER unsubscribing from
   * events to prevent late-arriving events from re-suspending threads after
   * reset.
   *
   * <p>
   * Reset operations performed:
   * <ol>
   * <li>Resume all suspended threads (including virtual threads on JDK 21+)</li>
   * <li>Resume VM global suspend state</li>
   * <li>Clear all EventRequest types (breakpoints, steps, watches, etc.)</li>
   * <li>Verify no suspended threads remain</li>
   * <li>Verify no EventRequests remain</li>
   * </ol>
   *
   * <p>
   * Thread-safe: Synchronized to prevent concurrent reset operations.
   *
   * @throws DebuggerException     if reset fails
   * @throws IllegalStateException if connection is unhealthy
   */
  public synchronized void reset() throws DebuggerException {
    Instant start = Instant.now();
    logger.debug("Starting connection reset");

    validateConnection();

    try {
      // Step 1: Resume all threads (CRITICAL - must happen before clearing requests)
      int threadsResumed = resumeAllThreads();
      logger.debug("Resumed {} thread suspension counts", threadsResumed);

      // Step 2: Resume VM global suspend
      vm.resume();
      logger.debug("VM resumed");

      // Step 3: Clear all EventRequests
      int requestsCleared = clearAllEventRequests();
      logger.debug("Cleared {} event requests", requestsCleared);

      // Step 4: Verify clean state
      verifyNoSuspendedThreads();
      verifyNoEventRequests();
      logger.debug("State verification passed");

      // Step 5: Record metrics
      Duration resetDuration = Duration.between(start, Instant.now());
      metrics.recordReset(requestsCleared, threadsResumed, resetDuration);

      logger.info("Connection reset complete: {} requests cleared, {} threads resumed, duration: {}ms", requestsCleared,
          threadsResumed, resetDuration.toMillis());

      // Warn on slow reset
      if (resetDuration.toMillis() > 500) {
        logger.warn("Slow reset detected: {}ms (cleared {} requests, resumed {} threads)", resetDuration.toMillis(),
            requestsCleared, threadsResumed);
      }

    } catch (VMDisconnectedException e) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, "VM disconnected during reset", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR,
          "Failed to reset connection state: " + e.getMessage(), e);
    }
  }

  /**
   * Permanently shuts down the connection and releases all resources.
   *
   * <p>
   * After calling this method, the manager cannot be used again. Any subsequent
   * calls to {@link #getOrCreateConnection(int)} will throw
   * IllegalStateException.
   *
   * <p>
   * Thread-safe: Synchronized to prevent concurrent shutdown operations.
   */
  public synchronized void shutdown() {
    if (shutdown) {
      logger.debug("Connection manager already shut down");
      return;
    }

    logger.info("Shutting down connection manager");
    shutdown = true;

    if (vm != null) {
      try {
        logger.info("Disposing VirtualMachine");
        vm.dispose();

        // Small delay to allow port cleanup
        Thread.sleep(100);
      } catch (Exception e) {
        logger.warn("Error disposing VirtualMachine during shutdown: {}", e.getMessage());
      } finally {
        vm = null;
        JDWPConnector.clearPortCache();
      }
    }

    logger.info("Connection manager shut down. {}", metrics.getSummary());
  }

  /**
   * Checks if the connection is healthy.
   *
   * <p>
   * Performs multi-level validation:
   * <ol>
   * <li>Level 1 (cheap): Check VM object not disposed</li>
   * <li>Level 2 (cheap): Check target process still alive</li>
   * <li>Level 3 (network): Check JDWP transport responds</li>
   * </ol>
   *
   * <p>
   * If health check fails, the connection is invalidated and next
   * getOrCreateConnection() will reconnect automatically.
   *
   * @return true if connection is healthy, false otherwise
   */
  public synchronized boolean isHealthy() {
    if (vm == null) {
      return false;
    }

    try {
      // Level 1: Check VM name (cheap check that VM object is valid)
      // This will throw VMDisconnectedException if VM is disposed
      String vmName = vm.name();
      logger.trace("VM name: {}", vmName);

      // Level 2: Check target process still alive (cheap, local)
      Process process = vm.process();
      if (process != null && !process.isAlive()) {
        logger.warn("Target VM process terminated (PID: {})", process.pid());
        invalidateConnection();
        return false;
      }

      // Level 3: Check JDWP transport alive (requires network round-trip)
      String version = vm.version();
      logger.trace("JDWP transport check OK (version: {})", version);

      return true;

    } catch (VMDisconnectedException e) {
      // JDWP transport disconnected - invalidate connection
      logger.warn("VM disconnected during health check", e);
      invalidateConnection();
      return false;

    } catch (Exception e) {
      logger.warn("Health check failed: {}", e.getMessage(), e);
      return false;
    }
  }

  /**
   * Gets the current VirtualMachine instance without creating a new connection.
   *
   * @return the current VM, or null if no connection exists
   */
  public synchronized VirtualMachine getCurrentConnection() {
    return vm;
  }

  /**
   * Gets connection metrics for diagnostics and monitoring.
   *
   * @return connection metrics
   */
  public ConnectionMetrics getMetrics() {
    return metrics;
  }

  /**
   * Checks if the VM has any active EventRequests. Centralized check for "dirty
   * state" detection.
   *
   * @return true if any EventRequests are active
   * @throws DebuggerException if VM is not connected
   */
  public synchronized boolean hasActiveRequests() throws DebuggerException {
    validateConnection();

    try {
      EventRequestManager erm = vm.eventRequestManager();

      // Check all 13+ EventRequest types
      boolean hasRequests = !erm.breakpointRequests().isEmpty() || !erm.stepRequests().isEmpty()
          || !erm.accessWatchpointRequests().isEmpty() || !erm.modificationWatchpointRequests().isEmpty()
          || !erm.methodEntryRequests().isEmpty() || !erm.methodExitRequests().isEmpty()
          || !erm.exceptionRequests().isEmpty() || !erm.threadStartRequests().isEmpty()
          || !erm.threadDeathRequests().isEmpty() || !erm.classPrepareRequests().isEmpty()
          || !erm.classUnloadRequests().isEmpty() || !erm.monitorContendedEnterRequests().isEmpty()
          || !erm.monitorContendedEnteredRequests().isEmpty() || !erm.monitorWaitRequests().isEmpty()
          || !erm.monitorWaitedRequests().isEmpty() || !erm.vmDeathRequests().isEmpty();

      return hasRequests;

    } catch (VMDisconnectedException e) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, "VM disconnected", e);
    }
  }

  /**
   * Checks if the VM has any suspended threads. Centralized check for "dirty
   * state" detection.
   *
   * @return true if any threads are suspended
   * @throws DebuggerException if VM is not connected
   */
  public synchronized boolean hasSuspendedThreads() throws DebuggerException {
    validateConnection();

    try {
      long suspendedCount = vm.allThreads().stream().filter(ThreadReference::isSuspended).count();

      return suspendedCount > 0;

    } catch (VMDisconnectedException e) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, "VM disconnected", e);
    }
  }

  /**
   * Gets a human-readable report of any dirty state in the VM. Useful for logging
   * and diagnostics.
   *
   * @return report string, or null if VM is clean
   * @throws DebuggerException if VM is not connected
   */
  public synchronized String getDirtyStateReport() throws DebuggerException {
    validateConnection();

    try {
      EventRequestManager erm = vm.eventRequestManager();
      StringBuilder report = new StringBuilder();

      // Count all EventRequest types
      int breakpoints = erm.breakpointRequests().size();
      int steps = erm.stepRequests().size();
      int accessWatchpoints = erm.accessWatchpointRequests().size();
      int modificationWatchpoints = erm.modificationWatchpointRequests().size();
      int methodEntries = erm.methodEntryRequests().size();
      int methodExits = erm.methodExitRequests().size();
      int exceptions = erm.exceptionRequests().size();
      int threadStarts = erm.threadStartRequests().size();
      int threadDeaths = erm.threadDeathRequests().size();
      int classPrepares = erm.classPrepareRequests().size();
      int classUnloads = erm.classUnloadRequests().size();
      int monitorContendedEnters = erm.monitorContendedEnterRequests().size();
      int monitorContendedEntered = erm.monitorContendedEnteredRequests().size();
      int monitorWaits = erm.monitorWaitRequests().size();
      int monitorWaited = erm.monitorWaitedRequests().size();
      int vmDeaths = erm.vmDeathRequests().size();

      int totalRequests = breakpoints + steps + accessWatchpoints + modificationWatchpoints + methodEntries
          + methodExits + exceptions + threadStarts + threadDeaths + classPrepares + classUnloads
          + monitorContendedEnters + monitorContendedEntered + monitorWaits + monitorWaited + vmDeaths;

      // Count suspended threads
      long suspendedThreads = vm.allThreads().stream().filter(ThreadReference::isSuspended).count();

      // Build report if dirty
      if (totalRequests > 0 || suspendedThreads > 0) {
        report.append("Dirty VM state detected: ");

        if (suspendedThreads > 0) {
          report.append(suspendedThreads).append(" suspended thread(s)");
        }

        if (totalRequests > 0) {
          if (suspendedThreads > 0)
            report.append(", ");
          report.append(totalRequests).append(" active EventRequest(s) [");

          List<String> requestTypes = new ArrayList<>();
          if (breakpoints > 0)
            requestTypes.add(breakpoints + " breakpoint");
          if (steps > 0)
            requestTypes.add(steps + " step");
          if (accessWatchpoints > 0)
            requestTypes.add(accessWatchpoints + " accessWatch");
          if (modificationWatchpoints > 0)
            requestTypes.add(modificationWatchpoints + " modificationWatch");
          if (methodEntries > 0)
            requestTypes.add(methodEntries + " methodEntry");
          if (methodExits > 0)
            requestTypes.add(methodExits + " methodExit");
          if (exceptions > 0)
            requestTypes.add(exceptions + " exception");
          if (threadStarts > 0)
            requestTypes.add(threadStarts + " threadStart");
          if (threadDeaths > 0)
            requestTypes.add(threadDeaths + " threadDeath");
          if (classPrepares > 0)
            requestTypes.add(classPrepares + " classPrepare");
          if (classUnloads > 0)
            requestTypes.add(classUnloads + " classUnload");
          if (monitorContendedEnters > 0)
            requestTypes.add(monitorContendedEnters + " monitorContendedEnter");
          if (monitorContendedEntered > 0)
            requestTypes.add(monitorContendedEntered + " monitorContendedEntered");
          if (monitorWaits > 0)
            requestTypes.add(monitorWaits + " monitorWait");
          if (monitorWaited > 0)
            requestTypes.add(monitorWaited + " monitorWaited");
          if (vmDeaths > 0)
            requestTypes.add(vmDeaths + " vmDeath");

          report.append(String.join(", ", requestTypes));
          report.append("]");
        }

        return report.toString();
      }

      return null; // Clean

    } catch (VMDisconnectedException e) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, "VM disconnected", e);
    }
  }

  // ========== Internal Methods ==========

  /**
   * Establishes a new JDWP connection.
   */
  private VirtualMachine establishConnection(int timeoutMs) throws DebuggerException {
    try {
      VirtualMachine newVm;
      if (jdwpPort != null) {
        // External debuggee mode: connect to specified host/port
        String host = jdwpHost != null ? jdwpHost : "127.0.0.1";
        logger.debug("Connecting to external debuggee on {}:{}", host, jdwpPort);
        newVm = JDWPConnector.attachToAddress(host, jdwpPort, timeoutMs);
      } else {
        // Self-attach mode: detect port from JVM arguments
        logger.debug("Self-attach mode: detecting JDWP port from JVM arguments");
        int detectedPort = JDWPConnector.getExistingJDWPPort();
        if (detectedPort == -1) {
          // Document why we fail fast: the VM must have started with -agentlib
          throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
              "No JDWP port detected. Ensure JVM was started with -agentlib:jdwp");
        }
        newVm = JDWPConnector.attachToPort(detectedPort, timeoutMs);
      }
      logger.info("JDWP connection established: {}", newVm.version());
      return newVm;
    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
          "Failed to establish JDWP connection: " + e.getMessage(), e);
    }
  }

  /**
   * Invalidates the current connection (for auto-recovery).
   */
  private void invalidateConnection() {
    logger.info("Invalidating connection - will reconnect on next request");
    vm = null;
  }

  /**
   * Validates that the connection is healthy, throws if not.
   */
  private void validateConnection() throws IllegalStateException {
    if (vm == null) {
      throw new IllegalStateException("No connection established");
    }
    if (!isHealthy()) {
      throw new IllegalStateException(
          "Connection unhealthy - VM disconnected or process died. Call getOrCreateConnection() to reconnect.");
    }
  }

  /**
   * Resumes all threads including virtual threads (JDK 21+).
   *
   * <p>
   * Handles threads with multiple suspend counts by calling resume() multiple
   * times until suspendCount reaches 0.
   *
   * @return total number of resume() calls made (not number of threads)
   */
  private int resumeAllThreads() {
    int resumeCount = 0;

    for (ThreadReference thread : vm.allThreads()) {
      if (thread.isSuspended()) {
        int suspendCount = thread.suspendCount();

        // Log virtual thread handling (JDK 21+)
        boolean isVirtual = false;
        try {
          // ThreadReference.isVirtual() available in JDK 21+
          isVirtual = thread.isVirtual();
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
          // Method not available (JDK < 21) - not virtual
        }

        if (isVirtual) {
          logger.trace("Resuming virtual thread: {} (suspend count: {})", thread.name(), suspendCount);
        }

        // Resume multiple times if suspended multiple times
        for (int i = 0; i < suspendCount; i++) {
          try {
            thread.resume();
            resumeCount++;
          } catch (Exception e) {
            logger.warn("Failed to resume thread {} (attempt {}/{}): {}", thread.name(), i + 1, suspendCount,
                e.getMessage());
          }
        }
      }
    }

    return resumeCount;
  }

  /**
   * Clears all EventRequest types.
   *
   * <p>
   * Comprehensive coverage of all 13+ EventRequest types to prevent state leakage
   * between sessions.
   *
   * @return total number of requests cleared
   */
  private int clearAllEventRequests() {
    EventRequestManager erm = vm.eventRequestManager();
    int totalCleared = 0;

    // Breakpoint requests
    List<BreakpointRequest> breakpoints = erm.breakpointRequests();
    totalCleared += breakpoints.size();
    if (!breakpoints.isEmpty()) {
      erm.deleteEventRequests(breakpoints);
      logger.trace("Cleared {} breakpoint requests", breakpoints.size());
    }

    // Step requests
    List<StepRequest> steps = erm.stepRequests();
    totalCleared += steps.size();
    if (!steps.isEmpty()) {
      erm.deleteEventRequests(steps);
      logger.trace("Cleared {} step requests", steps.size());
    }

    // Access watchpoint requests (field access)
    List<AccessWatchpointRequest> accessWatchpoints = erm.accessWatchpointRequests();
    totalCleared += accessWatchpoints.size();
    if (!accessWatchpoints.isEmpty()) {
      erm.deleteEventRequests(accessWatchpoints);
      logger.trace("Cleared {} access watchpoint requests", accessWatchpoints.size());
    }

    // Modification watchpoint requests (field modification)
    List<ModificationWatchpointRequest> modificationWatchpoints = erm.modificationWatchpointRequests();
    totalCleared += modificationWatchpoints.size();
    if (!modificationWatchpoints.isEmpty()) {
      erm.deleteEventRequests(modificationWatchpoints);
      logger.trace("Cleared {} modification watchpoint requests", modificationWatchpoints.size());
    }

    // Method entry requests
    List<MethodEntryRequest> methodEntries = erm.methodEntryRequests();
    totalCleared += methodEntries.size();
    if (!methodEntries.isEmpty()) {
      erm.deleteEventRequests(methodEntries);
      logger.trace("Cleared {} method entry requests", methodEntries.size());
    }

    // Method exit requests
    List<MethodExitRequest> methodExits = erm.methodExitRequests();
    totalCleared += methodExits.size();
    if (!methodExits.isEmpty()) {
      erm.deleteEventRequests(methodExits);
      logger.trace("Cleared {} method exit requests", methodExits.size());
    }

    // Exception requests
    List<ExceptionRequest> exceptions = erm.exceptionRequests();
    totalCleared += exceptions.size();
    if (!exceptions.isEmpty()) {
      erm.deleteEventRequests(exceptions);
      logger.trace("Cleared {} exception requests", exceptions.size());
    }

    // Thread start/death requests
    List<ThreadStartRequest> threadStarts = erm.threadStartRequests();
    totalCleared += threadStarts.size();
    if (!threadStarts.isEmpty()) {
      erm.deleteEventRequests(threadStarts);
      logger.trace("Cleared {} thread start requests", threadStarts.size());
    }

    List<ThreadDeathRequest> threadDeaths = erm.threadDeathRequests();
    totalCleared += threadDeaths.size();
    if (!threadDeaths.isEmpty()) {
      erm.deleteEventRequests(threadDeaths);
      logger.trace("Cleared {} thread death requests", threadDeaths.size());
    }

    // Class prepare/unload requests
    List<ClassPrepareRequest> classPrepares = erm.classPrepareRequests();
    totalCleared += classPrepares.size();
    if (!classPrepares.isEmpty()) {
      erm.deleteEventRequests(classPrepares);
      logger.trace("Cleared {} class prepare requests", classPrepares.size());
    }

    List<ClassUnloadRequest> classUnloads = erm.classUnloadRequests();
    totalCleared += classUnloads.size();
    if (!classUnloads.isEmpty()) {
      erm.deleteEventRequests(classUnloads);
      logger.trace("Cleared {} class unload requests", classUnloads.size());
    }

    // Monitor requests (JDK 6+)
    List<MonitorContendedEnterRequest> monitorEnters = erm.monitorContendedEnterRequests();
    totalCleared += monitorEnters.size();
    if (!monitorEnters.isEmpty()) {
      erm.deleteEventRequests(monitorEnters);
      logger.trace("Cleared {} monitor contended enter requests", monitorEnters.size());
    }

    List<MonitorContendedEnteredRequest> monitorEntered = erm.monitorContendedEnteredRequests();
    totalCleared += monitorEntered.size();
    if (!monitorEntered.isEmpty()) {
      erm.deleteEventRequests(monitorEntered);
      logger.trace("Cleared {} monitor contended entered requests", monitorEntered.size());
    }

    List<MonitorWaitRequest> monitorWaits = erm.monitorWaitRequests();
    totalCleared += monitorWaits.size();
    if (!monitorWaits.isEmpty()) {
      erm.deleteEventRequests(monitorWaits);
      logger.trace("Cleared {} monitor wait requests", monitorWaits.size());
    }

    List<MonitorWaitedRequest> monitorWaited = erm.monitorWaitedRequests();
    totalCleared += monitorWaited.size();
    if (!monitorWaited.isEmpty()) {
      erm.deleteEventRequests(monitorWaited);
      logger.trace("Cleared {} monitor waited requests", monitorWaited.size());
    }

    // VM Death requests (rare, but should be cleared for consistency)
    List<VMDeathRequest> vmDeaths = erm.vmDeathRequests();
    totalCleared += vmDeaths.size();
    if (!vmDeaths.isEmpty()) {
      erm.deleteEventRequests(vmDeaths);
      logger.trace("Cleared {} VM death requests", vmDeaths.size());
    }

    return totalCleared;
  }

  /**
   * Verifies no suspended threads remain after reset.
   *
   * @throws IllegalStateException if any suspended threads found
   */
  private void verifyNoSuspendedThreads() {
    List<ThreadReference> suspendedThreads = vm.allThreads().stream().filter(ThreadReference::isSuspended).toList();

    if (!suspendedThreads.isEmpty()) {
      String threadNames = suspendedThreads.stream().map(ThreadReference::name).collect(Collectors.joining(", "));
      throw new IllegalStateException(
          "Reset incomplete: " + suspendedThreads.size() + " threads still suspended: " + threadNames);
    }
  }

  /**
   * Verifies no EventRequests remain after reset.
   *
   * @throws IllegalStateException if any requests found
   */
  private void verifyNoEventRequests() {
    EventRequestManager erm = vm.eventRequestManager();

    // Check all request types are empty
    int totalRequests = erm.breakpointRequests().size() + erm.stepRequests().size()
        + erm.accessWatchpointRequests().size() + erm.modificationWatchpointRequests().size()
        + erm.methodEntryRequests().size() + erm.methodExitRequests().size() + erm.exceptionRequests().size()
        + erm.threadStartRequests().size() + erm.threadDeathRequests().size() + erm.classPrepareRequests().size()
        + erm.classUnloadRequests().size() + erm.monitorContendedEnterRequests().size()
        + erm.monitorContendedEnteredRequests().size() + erm.monitorWaitRequests().size()
        + erm.monitorWaitedRequests().size();

    if (totalRequests > 0) {
      throw new IllegalStateException("Reset incomplete: " + totalRequests + " event requests still active");
    }
  }

  /**
   * Connection metrics for diagnostics and monitoring.
   */
  public static class ConnectionMetrics {
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private final AtomicInteger resetCount = new AtomicInteger(0);
    private final AtomicLong totalThreadsResumed = new AtomicLong(0);
    private final AtomicLong totalRequestsCleared = new AtomicLong(0);
    private volatile Instant firstConnectionTime;
    private volatile Duration lastResetDuration = Duration.ZERO;

    void recordConnection() {
      connectionCount.incrementAndGet();
      if (firstConnectionTime == null) {
        firstConnectionTime = Instant.now();
      }
    }

    void recordReset(int requestsCleared, int threadsResumed, Duration duration) {
      resetCount.incrementAndGet();
      totalRequestsCleared.addAndGet(requestsCleared);
      totalThreadsResumed.addAndGet(threadsResumed);
      lastResetDuration = duration;
    }

    /**
     * Gets a human-readable summary of connection metrics.
     *
     * @return metrics summary
     */
    public String getSummary() {
      Duration uptime = firstConnectionTime != null ? Duration.between(firstConnectionTime, Instant.now())
          : Duration.ZERO;

      return String.format(
          "Connections: %d, Resets: %d, Total threads resumed: %d, Total requests cleared: %d, Uptime: %ds, Last reset: %dms",
          connectionCount.get(), resetCount.get(), totalThreadsResumed.get(), totalRequestsCleared.get(),
          uptime.getSeconds(), lastResetDuration.toMillis());
    }

    // Getters for programmatic access
    public int getConnectionCount() {
      return connectionCount.get();
    }

    public int getResetCount() {
      return resetCount.get();
    }

    public long getTotalThreadsResumed() {
      return totalThreadsResumed.get();
    }

    public long getTotalRequestsCleared() {
      return totalRequestsCleared.get();
    }

    public Duration getLastResetDuration() {
      return lastResetDuration;
    }

    public Duration getUptime() {
      return firstConnectionTime != null ? Duration.between(firstConnectionTime, Instant.now()) : Duration.ZERO;
    }
  }
}
