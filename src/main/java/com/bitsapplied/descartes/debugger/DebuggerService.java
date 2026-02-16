package com.bitsapplied.descartes.debugger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointResolution;
import com.bitsapplied.descartes.debugger.breakpoints.ConditionalBreakpointEvaluator;
import com.bitsapplied.descartes.debugger.breakpoints.MethodBreakpointManager;
import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.events.DebugEvent;
import com.bitsapplied.descartes.debugger.events.StreamEvent;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.debugger.integration.DebuggerMetrics;
import com.bitsapplied.descartes.debugger.integration.DebuggerNotificationBroadcaster;
import com.bitsapplied.descartes.debugger.integration.MCPEventBridge;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;
import com.bitsapplied.descartes.debugger.stacktrace.StackTraceInspector;
import com.bitsapplied.descartes.debugger.stepping.SteppingController;
import com.bitsapplied.descartes.debugger.sync.DebuggerSyncCoordinator;
import com.bitsapplied.descartes.debugger.variables.VariableExtractor;
import com.bitsapplied.descartes.debugger.variables.VariableReferenceManager;
import com.bitsapplied.descartes.debugger.watch.WatchExpressionManager;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ThreadDeathRequest;
import com.sun.jdi.request.ThreadStartRequest;

import io.reactivex.rxjava3.core.Observable;

/**
 * Core debugger service orchestrating debug session lifecycle and operations.
 *
 * <p>
 * This service provides a high-level API for runtime debugging of Java
 * applications using the Java Debug Interface (JDI). It manages the entire
 * debugger lifecycle from connection establishment to cleanup.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 * <li><b>Session Lifecycle</b>: Start/stop debug sessions with state
 * validation</li>
 * <li><b>State Machine</b>: Enforce valid {@link SessionState} transitions
 * (CLOSED -> CONNECTING -> READY -> DISCONNECTING -> CLOSED)</li>
 * <li><b>Component Coordination</b>: Initialize and manage EventHub,
 * BreakpointManager, SteppingController, etc.</li>
 * <li><b>Thread Operations</b>: Suspend/resume threads, step execution</li>
 * <li><b>Event Publishing</b>: Reactive streams for breakpoints, steps, and
 * other debug events</li>
 * <li><b>Resource Cleanup</b>: Shutdown hook for emergency cleanup on JVM
 * termination</li>
 * </ul>
 *
 * <h2>Thread Safety Model</h2>
 * <p>
 * <b>Critical Design Decision:</b> All debugger operations execute on a
 * single-threaded {@code debuggerExecutor} to ensure thread-safe access to JDI
 * objects. The JDI API is not thread-safe, so this serialization prevents race
 * conditions and corruption.
 * </p>
 * <ul>
 * <li>State transitions use atomic {@code compareAndSet} to prevent TOCTOU
 * races</li>
 * <li>Executor recreation protected by synchronized blocks with post-sync
 * validation</li>
 * <li>Event subscriptions managed thread-safely with CopyOnWriteArrayList</li>
 * <li>VM disposal on failure handled with try-finally patterns</li>
 * </ul>
 *
 * <h2>Lifecycle Phases</h2>
 * <ol>
 * <li><b>Initialization</b>: Create executor and initialize state to
 * CLOSED</li>
 * <li><b>Connection</b>: Attach to JVM via JDWP, transition to CONNECTING</li>
 * <li><b>Component Setup</b>: Initialize EventHub, managers, evaluators</li>
 * <li><b>Ready State</b>: Register shutdown hook, transition to READY</li>
 * <li><b>Operation</b>: Process breakpoints, steps, variable inspection</li>
 * <li><b>Disconnection</b>: Stop components, dispose VM, transition to
 * DISCONNECTING</li>
 * <li><b>Cleanup</b>: Remove shutdown hook, shutdown executor, transition to
 * CLOSED</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * // Create and start debugger
 * DebuggerService service = new DebuggerService();
 * DebugSessionConfig config = DebugSessionConfig.defaults();
 * service.start(config);
 *
 * // Subscribe to breakpoint events
 * service.events().filter(DebugEvent::isBreakpointEvent).subscribe(event -> {
 *   System.out.println("Breakpoint hit: " + event.getLocation());
 *   // Important: Must resume VM to prevent deadlock
 *   service.resumeAll();
 * });
 *
 * // Set breakpoint
 * BreakpointManager bpm = service.getBreakpointManager();
 * bpm.setBreakpoint("com.example.MyClass", 42);
 *
 * // Cleanup
 * service.stop();
 * </pre>
 *
 * <p>
 * <b>Important Notes:</b>
 * <ul>
 * <li>Breakpoint events suspend the VM - subscribers MUST call resume()</li>
 * <li>State transitions validated - IllegalStateException thrown on invalid
 * transitions</li>
 * <li>Shutdown hook ensures cleanup even on unexpected JVM termination</li>
 * <li>Executor operations have 5-second timeouts to prevent hangs</li>
 * </ul>
 *
 * @see SessionState
 * @see DebugSessionConfig
 * @see EventHub
 * @see BreakpointManager
 *
 *      // Perform operations List&lt;ThreadInfo&gt; threads =
 *      service.getThreads();
 *
 *      service.stop();
 *      </pre>
 */
public class DebuggerService {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerService.class);

  // Connection manager (optional - for reuse mode)
  private final JDWPConnectionManager connectionManager;
  private final boolean reuseConnection;

  // Single-threaded executor for all debugger operations
  private ExecutorService debuggerExecutor;

  // Session state
  private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
  private final AtomicLong sessionIdGenerator = new AtomicLong();
  private final AtomicBoolean startInProgress = new AtomicBoolean(false);
  private final AtomicReference<CompletableFuture<Void>> inFlightStartFuture = new AtomicReference<>();
  private final AtomicReference<Thread> inFlightStartThread = new AtomicReference<>();
  private VirtualMachine vm;
  private EventHub eventHub;
  private DebugSessionConfig config;
  private volatile long activeSessionId;
  private volatile String activeVmFingerprint;
  private volatile String activeAttachedHost;
  private volatile Integer activeAttachedPort;

  // Phase 2 components
  private BreakpointManager breakpointManager;
  private SteppingController steppingController;
  private StackTraceInspector stackTraceInspector;

  // Phase 3 components
  private VariableReferenceManager variableReferenceManager;
  private VariableExtractor variableExtractor;

  // Phase 4 components
  private HybridEvaluationProvider evaluationProvider;

  // Phase 5 components
  private ConditionalBreakpointEvaluator conditionalBreakpointEvaluator;
  private MethodBreakpointManager methodBreakpointManager;
  private WatchExpressionManager watchExpressionManager;

  // Phase 6 components
  private MCPEventBridge mcpEventBridge;
  private DebuggerMetrics metrics;
  private volatile DebuggerSyncCoordinator syncCoordinator;

  // Shutdown hook for cleanup
  private Thread shutdownHook;

  /**
   * Creates a debugger service with default configuration (no connection reuse).
   *
   * <p>
   * This constructor creates a new DebuggerService instance that will establish a
   * fresh JDWP connection for each session. After stop() is called, the
   * connection is disposed.
   *
   * <p>
   * <b>Use Case:</b> Production environments where each client should have an
   * isolated debug session.
   */
  public DebuggerService() {
    this(null);
  }

  /**
   * Creates a debugger service with connection reuse via a shared connection
   * manager.
   *
   * <p>
   * This constructor enables connection reuse mode where multiple DebuggerService
   * instances (or the same instance across start/stop cycles) share a single
   * VirtualMachine connection. Between sessions, the connection manager performs
   * comprehensive state reset to prevent leakage.
   *
   * <p>
   * <b>Use Case:</b> Test suites where multiple test methods need debug sessions.
   * Eliminates ~10s reconnection overhead per test.
   *
   * <p>
   * <b>Lifecycle Pattern:</b>
   *
   * <pre>
   * // Test class setup
   * &#64;BeforeAll
   * static void setupConnectionManager() {
   *   connectionManager = new JDWPConnectionManager();
   * }
   *
   * &#64;BeforeEach
   * void setupSession() {
   *   service = new DebuggerService(connectionManager);
   *   service.start();
   * }
   *
   * &#64;AfterEach
   * void cleanupSession() {
   *   service.stop(); // Resets state, keeps connection
   * }
   *
   * &#64;AfterAll
   * static void shutdownConnection() {
   *   connectionManager.shutdown(); // Disposes connection
   * }
   * </pre>
   *
   * @param connectionManager the connection manager to use, or null for no reuse
   */
  public DebuggerService(JDWPConnectionManager connectionManager) {
    this.connectionManager = connectionManager;
    this.reuseConnection = (connectionManager != null);
    this.debuggerExecutor = createExecutor();

    logger.debug("DebuggerService created (connection reuse: {})", reuseConnection);
  }

  private ExecutorService createExecutor() {
    return Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "Debugger-Executor");
      t.setDaemon(true);
      return t;
    });
  }

  private ExecutorService ensureExecutor() {
    ExecutorService executor;

    // Synchronized block for executor creation/retrieval
    synchronized (this) {
      if (debuggerExecutor == null || debuggerExecutor.isShutdown()) {
        debuggerExecutor = createExecutor();
      }
      executor = debuggerExecutor;
    }

    // Validate executor state after exiting synchronized block (race protection)
    if (executor == null || executor.isShutdown()) {
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR,
          "Debugger executor is not available (may have been shut down concurrently)");
    }

    return executor;
  }

  private synchronized void shutdownExecutor() {
    if (debuggerExecutor != null) {
      debuggerExecutor.shutdownNow();
      debuggerExecutor = null;
    }
  }

  private void safeDisposeVm(VirtualMachine vmToDispose) {
    if (vmToDispose == null) {
      return;
    }
    try {
      logger.info("Disposing VM {}", vmToDispose);
      vmToDispose.dispose();
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception disposeEx) {
      logger.debug("Error disposing VM during cleanup: {}", disposeEx.getMessage());
    } finally {
      JDWPConnector.clearPortCache();
    }
  }

  /**
   * Starts a debug session by attaching to the current JVM.
   *
   * @param config session configuration
   * @throws DebuggerException if session cannot be started
   */
  public void start(DebugSessionConfig config) {
    if (config == null) {
      config = DebugSessionConfig.defaults();
    }

    DebugSessionConfig finalConfig = config;

    if (!startInProgress.compareAndSet(false, true)) {
      SessionState currentState = state.get();
      throw new DebuggerException(DebuggerErrorCode.SESSION_ALREADY_ACTIVE,
          "Debug session already active (current state: " + currentState + ")");
    }

    SessionState current = state.get();
    try {
      if (current != SessionState.CLOSED && current != SessionState.CREATED) {
        throw new DebuggerException(DebuggerErrorCode.SESSION_ALREADY_ACTIVE,
            "Debug session already active (current state: " + current + ")");
      }

      int maxAttempts = reuseConnection ? 2 : 1;
      DebuggerException lastFailure = null;
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
          startOnce(finalConfig);
          return;
        } catch (DebuggerException e) {
          lastFailure = e;
          boolean retryable = isRetryableStartFailure(e);
          if (!retryable || attempt >= maxAttempts) {
            throw e;
          }

          logger.warn("Retryable debug session start failure on attempt {}/{}: {}. Retrying...", attempt, maxAttempts,
              e.getMessage(), e);
          prepareForStartRetry(e);
        }
      }

      if (lastFailure != null) {
        throw lastFailure;
      }
    } finally {
      startInProgress.set(false);
    }
  }

  private void startOnce(DebugSessionConfig finalConfig) {
    CompletableFuture<Void> future = null;
    try {
      // Transition to CONNECTING state
      transitionTo(SessionState.CONNECTING);
      logger.info("State after CONNECTING transition: {}", state.get());

      // Execute connection on debugger thread
      ExecutorService executor = ensureExecutor();

      future = CompletableFuture.runAsync(() -> {
        inFlightStartThread.set(Thread.currentThread());
        VirtualMachine vmToDispose = null;
        boolean needsCleanupOnFailure = false;
        String attachedHost = "127.0.0.1";
        Integer attachedPort = null;
        try {
          // Connect to JDWP - use connection manager if available
          if (reuseConnection) {
            logger.info("Getting JDWP connection from connection manager (reuse mode)...");
            this.vm = connectionManager.getOrCreateConnection(finalConfig.jdwpTimeout());
            needsCleanupOnFailure = true; // Mark that we need to reset on failure
            attachedHost = connectionManager.getConfiguredHost().orElse(attachedHost);
            attachedPort = connectionManager.getConfiguredPort().orElse(null);
            if (attachedPort == null) {
              int detectedPort = JDWPConnector.getExistingJDWPPort();
              if (detectedPort > 0) {
                attachedPort = detectedPort;
              }
            }

            // CRITICAL: Guard against dirty VM from previous failed start
            // Use centralized helpers to check for ANY type of dirty state
            try {
              if (connectionManager.hasSuspendedThreads() || connectionManager.hasActiveRequests()) {
                String dirtyReport = connectionManager.getDirtyStateReport();
                logger.warn("Dirty VM detected: {}. Resetting...", dirtyReport);
                connectionManager.reset();
                logger.info("VM reset successful - proceeding with clean state");
              }
            } catch (Exception guardEx) {
              // Guard failure means VM is in unknown/dirty state - CANNOT CONTINUE
              logger.error("FATAL: VM dirty state guard failed - cannot start session", guardEx);

              // Keep manager reusable; just invalidate so next start can reconnect
              try {
                connectionManager.invalidateForReconnect("guard failure: " + guardEx.getMessage());
              } catch (Exception invalidateEx) {
                logger.error("Failed to invalidate connection manager after guard failure", invalidateEx);
              }

              // Fail startup - don't start session with dirty VM
              throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED,
                  "VM in dirty state and reset failed: " + guardEx.getMessage(), guardEx);
            }

          } else {
            logger.info("Connecting to JDWP (fresh connection mode)...");
            // Detect JDWP port from JVM arguments
            int jdwpPort = JDWPConnector.getExistingJDWPPort();
            if (jdwpPort == -1) {
              throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
                  "No JDWP port detected. Ensure JVM was started with -agentlib:jdwp");
            }
            vmToDispose = JDWPConnector.attachToPort(jdwpPort, finalConfig.jdwpTimeout());
            this.vm = vmToDispose;
            attachedPort = jdwpPort;
          }
          initializeSessionIdentity(attachedHost, attachedPort);

          // Initialize EventHub
          this.eventHub = new EventHub(vm, executor);
          this.eventHub.start();

          // Initialize Phase 2 components
          this.breakpointManager = new BreakpointManager(vm);
          this.steppingController = new SteppingController(vm, finalConfig.skipPatterns());
          this.stackTraceInspector = new StackTraceInspector();

          // Initialize Phase 3 components
          this.variableReferenceManager = new VariableReferenceManager();
          this.variableExtractor = new VariableExtractor(variableReferenceManager);

          // Initialize Phase 4 components
          this.evaluationProvider = new HybridEvaluationProvider();

          // Initialize Phase 5 components
          this.conditionalBreakpointEvaluator = new ConditionalBreakpointEvaluator(evaluationProvider);
          this.methodBreakpointManager = new MethodBreakpointManager(vm);
          this.watchExpressionManager = new WatchExpressionManager(evaluationProvider);

          // Initialize Phase 6 components
          this.metrics = new DebuggerMetrics();
          if (this.syncCoordinator != null) {
            try {
              this.syncCoordinator.close();
            } catch (Exception ignore) {
              logger.debug("Error closing existing sync coordinator during start: {}", ignore.getMessage());
            }
          }
          this.syncCoordinator = new DebuggerSyncCoordinator();
          this.mcpEventBridge = new MCPEventBridge(eventHub);
          this.mcpEventBridge.onNotification(syncCoordinator::handleNotification);
          this.mcpEventBridge.onNotification(DebuggerNotificationBroadcaster.getInstance()::broadcast);
          this.mcpEventBridge.start();

          // Enable basic event requests
          enableBasicEvents();

          logger.info("State before READY transition: {}", state.get());
          // Transition to READY
          transitionTo(SessionState.READY);
          logger.info("State after READY transition: {}", state.get());
          this.config = finalConfig;

          // Set up event monitoring after reaching READY to avoid stale events
          setupEventMonitoring();

          // Register shutdown hook for cleanup
          registerShutdownHook();

          logger.info("Debug session started successfully");

          // Success - don't dispose VM
          vmToDispose = null;

        } catch (Exception e) {
          logger.error("Failed to start debug session", e);

          // Stop event hub before disposing VM to drain pending events
          if (eventHub != null) {
            try {
              eventHub.stop();
            } catch (Exception hubStopEx) {
              logger.debug("Error stopping EventHub during startup cleanup: {}", hubStopEx.getMessage());
            } finally {
              eventHub = null;
            }
          }

          if (syncCoordinator != null) {
            try {
              syncCoordinator.close();
            } catch (Exception coordinatorEx) {
              logger.debug("Error closing sync coordinator during startup cleanup: {}", coordinatorEx.getMessage());
            } finally {
              syncCoordinator = null;
            }
          }

          // CRITICAL: Reset connection if start failed in reuse mode
          // Otherwise the next test inherits dirty state (suspended threads, active
          // EventRequests)
          if (needsCleanupOnFailure && connectionManager != null) {
            try {
              logger.warn("Start failed in reuse mode - resetting connection to prevent state leakage");
              connectionManager.reset();
            } catch (Exception resetEx) {
              logger.error("Failed to reset connection after start failure: {}", resetEx.getMessage());
              // Keep manager reusable; invalidate connection to force reconnect next time
              try {
                connectionManager.invalidateForReconnect("startup cleanup reset failure: " + resetEx.getMessage());
              } catch (Exception invalidateEx) {
                logger.error("Failed to invalidate connection manager after startup cleanup failure", invalidateEx);
              }
            }
          }

          // Dispose VM if it was created (only in non-reuse mode)
          if (!reuseConnection && vmToDispose != null) {
            safeDisposeVm(vmToDispose);
          }

          clearSessionIdentity();
          this.config = null;
          forceClosedState("startup failure");
          throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED,
              "Failed to start debug session: " + e.getMessage(), e);
        } finally {
          inFlightStartThread.set(null);
        }
      }, executor);
      inFlightStartFuture.set(future);

      // Wait for completion with timeout
      future.get(finalConfig.jdwpTimeout() + 5000, TimeUnit.MILLISECONDS);

    } catch (CancellationException e) {
      cancelInFlightStart("startup canceled");
      this.config = null;
      forceClosedState("startup canceled");
      throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED, "Debug session startup canceled", e);
    } catch (TimeoutException e) {
      cancelInFlightStart("startup timeout");
      this.config = null;
      forceClosedState("startup timeout");
      throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED, "Debug session startup timeout", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      cancelInFlightStart("startup interrupted");
      this.config = null;
      forceClosedState("startup interrupted");
      throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED, "Debug session startup interrupted", e);
    } catch (Exception e) {
      this.config = null;
      forceClosedState("startup failed");
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED,
          "Failed to start debug session: " + e.getMessage(), e);
    } finally {
      if (future != null) {
        inFlightStartFuture.compareAndSet(future, null);
      } else {
        inFlightStartFuture.set(null);
      }
    }
  }

  private boolean isRetryableStartFailure(DebuggerException exception) {
    if (exception == null) {
      return false;
    }
    if (exception.getErrorCode() == DebuggerErrorCode.JDWP_CONNECTION_FAILED) {
      return true;
    }

    String message = exception.getMessage();
    if (message != null) {
      String normalized = message.toLowerCase();
      if (normalized.contains("vm disconnected") || normalized.contains("dirty state")
          || normalized.contains("failed to connect to jdwp")) {
        return true;
      }
    }

    Throwable cause = exception.getCause();
    while (cause != null) {
      String causeMessage = cause.getMessage();
      if (causeMessage != null) {
        String normalized = causeMessage.toLowerCase();
        if (normalized.contains("vm disconnected") || normalized.contains("failed to connect to jdwp")
            || normalized.contains("dirty state")) {
          return true;
        }
      }
      cause = cause.getCause();
    }

    return false;
  }

  private void prepareForStartRetry(DebuggerException failure) {
    try {
      if (state.get() != SessionState.CLOSED) {
        transitionTo(SessionState.CLOSED);
      }
    } catch (Exception transitionEx) {
      logger.debug("Failed to force CLOSED state before retry: {}", transitionEx.getMessage());
      state.set(SessionState.CLOSED);
    }

    clearStartComponents();

    if (reuseConnection && connectionManager != null) {
      try {
        connectionManager.invalidateForReconnect("start retry after failure: " + failure.getMessage());
      } catch (Exception invalidateEx) {
        logger.error("Failed to invalidate connection manager before retry", invalidateEx);
      }
    } else if (vm != null) {
      safeDisposeVm(vm);
      vm = null;
    }

    try {
      Thread.sleep(150);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void cancelInFlightStart(String reason) {
    CompletableFuture<Void> inFlight = inFlightStartFuture.getAndSet(null);
    if (inFlight != null && !inFlight.isDone()) {
      boolean cancelled = inFlight.cancel(true);
      logger.info("Cancelling in-flight debugger start (reason='{}', cancelled={})", reason, cancelled);
    }

    Thread startThread = inFlightStartThread.getAndSet(null);
    if (startThread != null && startThread.isAlive()) {
      logger.info("Interrupting debugger startup thread '{}' (reason='{}')", startThread.getName(), reason);
      startThread.interrupt();
    }
  }

  private void forceClosedState(String reason) {
    SessionState current = state.get();
    if (current == SessionState.CLOSED) {
      return;
    }

    try {
      transitionTo(SessionState.CLOSED);
    } catch (Exception transitionEx) {
      logger.warn("Failed to transition debugger state to CLOSED (reason='{}', currentState={}); forcing CLOSED",
          reason, current, transitionEx);
      state.set(SessionState.CLOSED);
    }
  }

  private void clearStartComponents() {
    if (eventHub != null) {
      try {
        eventHub.stop();
      } catch (Exception e) {
        logger.debug("Failed to stop event hub during retry cleanup: {}", e.getMessage());
      } finally {
        eventHub = null;
      }
    }

    if (syncCoordinator != null) {
      try {
        syncCoordinator.close();
      } catch (Exception e) {
        logger.debug("Failed to close sync coordinator during retry cleanup: {}", e.getMessage());
      } finally {
        syncCoordinator = null;
      }
    }

    if (mcpEventBridge != null) {
      try {
        mcpEventBridge.stop();
      } catch (Exception e) {
        logger.debug("Failed to stop MCP event bridge during retry cleanup: {}", e.getMessage());
      } finally {
        mcpEventBridge = null;
      }
    }

    breakpointManager = null;
    steppingController = null;
    stackTraceInspector = null;
    variableReferenceManager = null;
    variableExtractor = null;
    conditionalBreakpointEvaluator = null;
    methodBreakpointManager = null;
    watchExpressionManager = null;
    metrics = null;
    evaluationProvider = null;
    config = null;
    clearSessionIdentity();
  }

  private void initializeSessionIdentity(String attachedHost, Integer attachedPort) {
    this.activeSessionId = sessionIdGenerator.incrementAndGet();
    this.activeAttachedHost = attachedHost;
    this.activeAttachedPort = attachedPort;
    this.activeVmFingerprint = buildVmFingerprint(attachedHost, attachedPort);
  }

  private String buildVmFingerprint(String attachedHost, Integer attachedPort) {
    String vmName = "<unknown>";
    String vmVersion = "<unknown>";
    String vmDescription = "<unknown>";
    if (vm != null) {
      try {
        vmName = vm.name();
      } catch (Exception ignored) {
        // Keep unknown marker
      }
      try {
        vmVersion = vm.version();
      } catch (Exception ignored) {
        // Keep unknown marker
      }
      try {
        vmDescription = vm.description();
      } catch (Exception ignored) {
        // Keep unknown marker
      }
    }
    String host = attachedHost == null || attachedHost.isBlank() ? "<unknown>" : attachedHost;
    String port = attachedPort == null ? "<unknown>" : String.valueOf(attachedPort);
    String payload = String.join("|", host, port, vmName, vmVersion, vmDescription);
    int digest = payload.hashCode();
    return String.format("vm-%08x", digest);
  }

  private void clearSessionIdentity() {
    activeSessionId = 0L;
    activeVmFingerprint = null;
    activeAttachedHost = null;
    activeAttachedPort = null;
  }

  /**
   * Stops the debug session and releases all resources.
   *
   * <p>
   * <b>Connection Reuse Mode:</b> If using a JDWPConnectionManager, this method
   * performs a comprehensive state reset but keeps the JDWP connection alive for
   * reuse by the next session. The connection manager handles thread resumption,
   * EventRequest cleanup, and state verification.
   *
   * <p>
   * <b>Fresh Connection Mode:</b> If not using a connection manager, this method
   * disposes the VirtualMachine and clears the port cache, requiring a full
   * reconnection for the next session.
   */
  public void stop() {
    SessionState currentState = state.get();
    if (currentState == SessionState.CONNECTING || inFlightStartFuture.get() != null) {
      cancelInFlightStart("stop requested");
      currentState = state.get();
    }

    if (currentState == SessionState.CREATED) {
      logger.debug("Skip stopping debugger service; session never started.");
      config = null;
      clearSessionIdentity();
      removeShutdownHook();
      shutdownExecutor();
      return;
    }
    if (currentState == SessionState.CLOSED) {
      logger.debug("Debugger service already closed.");
      config = null;
      clearSessionIdentity();
      removeShutdownHook();
      shutdownExecutor();
      return;
    }

    if (!currentState.canTransitionTo(SessionState.DISCONNECTING)) {
      logger.warn("Cannot transition from {} to DISCONNECTING. Forcing CLOSED state.", currentState);
      forceClosedState("stop invalid transition");
      config = null;
      clearSessionIdentity();
      removeShutdownHook();
      shutdownExecutor();
      return;
    }

    try {
      transitionTo(SessionState.DISCONNECTING);

      ExecutorService executor = ensureExecutor();

      CompletableFuture.runAsync(() -> {
        try {
          // Stop Phase 6 components
          DebuggerSyncCoordinator coordinator = syncCoordinator;
          syncCoordinator = null;
          if (coordinator != null) {
            try {
              coordinator.close();
            } catch (Exception coordinatorEx) {
              logger.debug("Error closing debugger sync coordinator during stop: {}", coordinatorEx.getMessage());
            }
          }

          if (mcpEventBridge != null) {
            mcpEventBridge.stop();
          }
          if (metrics != null) {
            metrics.endSession();
          }

          // Different cleanup based on connection mode
          if (reuseConnection) {
            // Reuse mode: Reset state but keep connection
            logger.info("Resetting session state (connection reuse mode)");

            // Reset session state (includes stopping EventHub)
            try {
              resetSessionState();
            } catch (Exception resetEx) {
              // Reset failed - connection is dirty and unusable
              logger.error("CRITICAL: Reset failed - invalidating connection manager", resetEx);

              // Keep manager reusable; invalidate so the next session forces reconnect
              if (connectionManager != null) {
                try {
                  connectionManager.invalidateForReconnect("stop/reset failure: " + resetEx.getMessage());
                } catch (Exception invalidateEx) {
                  logger.error("Failed to invalidate dirty connection manager", invalidateEx);
                }
              }

              // Re-throw to fail the stop operation
              throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR,
                  "Reset failed - connection dirty: " + resetEx.getMessage(), resetEx);
            }

            // Clear EventHub reference (already stopped by resetSessionState)
            eventHub = null;

            logger.info("Session state reset complete - connection available for reuse");
          } else {
            // Fresh connection mode: Dispose VM
            logger.info("Disposing connection (fresh connection mode)");

            // Stop EventHub
            if (eventHub != null) {
              try {
                eventHub.stop();
              } catch (Exception hubStopEx) {
                logger.debug("Error stopping EventHub during shutdown: {}", hubStopEx.getMessage());
              } finally {
                eventHub = null;
              }
            }

            // Resume all suspended threads before disposing VM
            // Critical for self-debugging scenarios where suspended threads prevent JVM
            // exit
            if (vm != null) {
              try {
                vm.resume();
                logger.debug("Resumed all threads before VM disposal");
              } catch (Exception resumeEx) {
                logger.debug("Error resuming threads during shutdown: {}", resumeEx.getMessage());
              }
            }

            // Dispose VM
            if (vm != null) {
              safeDisposeVm(vm);
              vm = null;
            }

            JDWPConnector.clearPortCache();
            logger.info("Connection disposed");
          }

          // Remove shutdown hook
          removeShutdownHook();

          transitionTo(SessionState.CLOSED);
          logger.info("Debug session stopped");

        } catch (Exception e) {
          logger.error("Error stopping debug session", e);
          forceClosedState("stop async failure");
        }
      }, executor).get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      logger.error("Timed out while stopping debug session", e);
      cancelInFlightStart("stop timeout");
      forceClosedState("stop timeout");
      throw new DebuggerException(DebuggerErrorCode.SESSION_DISCONNECT_FAILED, "Timeout stopping debug session", e);
    } catch (Exception e) {
      logger.error("Error stopping debug session", e);
      forceClosedState("stop exception");
    } finally {
      // Remove shutdown hook if still registered
      config = null;
      clearSessionIdentity();
      removeShutdownHook();
      shutdownExecutor();
    }
  }

  /**
   * Gets the current session state.
   *
   * @return current state
   */
  public SessionState getState() {
    return state.get();
  }

  /**
   * Gets the current logical debugger session identifier.
   *
   * @return session id, or 0 when no active session metadata is available
   */
  public long getSessionId() {
    return activeSessionId;
  }

  /**
   * Gets the fingerprint of the currently attached VM.
   *
   * @return VM fingerprint, or null when unavailable
   */
  public String getVmFingerprint() {
    return activeVmFingerprint;
  }

  /**
   * Gets the host used for the current debugger attachment.
   *
   * @return attached host, or null when unavailable
   */
  public String getAttachedHost() {
    return activeAttachedHost;
  }

  /**
   * Gets the port used for the current debugger attachment.
   *
   * @return attached port, or null when unavailable
   */
  public Integer getAttachedPort() {
    return activeAttachedPort;
  }

  /**
   * Gets the session configuration.
   *
   * @return session config, or null if not started
   */
  public DebugSessionConfig getConfig() {
    return config;
  }

  /**
   * Checks if a debug session is active and operational.
   *
   * @return true if operational
   */
  public boolean isActive() {
    return state.get().isOperational();
  }

  /**
   * Gets the observable stream of debug events.
   *
   * <p>
   * This returns only {@link DebugEvent} instances (JDWP events like breakpoints,
   * steps, etc.). To receive error events as well, use {@link #allEvents()}
   * instead.
   *
   * @return observable of debug events
   * @throws DebuggerException if no active session
   */
  public Observable<DebugEvent> events() {
    requireActive();
    return eventHub.eventsOfType(DebugEvent.class);
  }

  /**
   * Gets the observable stream for all events (debug events and error events).
   *
   * <p>
   * Use this method if you want to observe both successful debug events and error
   * events from event processing.
   *
   * @return observable of all stream events
   * @throws DebuggerException if no active session
   */
  public Observable<StreamEvent> allEvents() {
    requireActive();
    return eventHub.events();
  }

  /**
   * Gets all threads in the debuggee.
   *
   * @return list of thread information
   * @throws DebuggerException if operation fails
   */
  public List<ThreadInfo> getThreads() {
    requireActive();

    try {
      CompletableFuture<List<ThreadInfo>> future = CompletableFuture.supplyAsync(() -> {
        List<ThreadInfo> threads = new ArrayList<>();

        for (ThreadReference thread : vm.allThreads()) {
          threads.add(createThreadInfo(thread));
        }

        return threads;
      }, ensureExecutor());

      return future.get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      throw new DebuggerException(DebuggerErrorCode.OPERATION_TIMEOUT, "Timed out retrieving threads", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Failed to get threads: " + e.getMessage(),
          e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Thread retrieval interrupted", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Failed to get threads: " + e.getMessage(), e);
    }
  }

  /**
   * Gets a thread by its unique ID.
   *
   * @param threadId the thread ID
   * @return the thread reference, or null if not found
   * @throws DebuggerException if operation fails
   */
  public ThreadReference getThreadById(long threadId) {
    requireActive();

    try {
      CompletableFuture<ThreadReference> future = CompletableFuture.supplyAsync(() -> findThread(threadId),
          ensureExecutor());

      return future.get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      throw new DebuggerException(DebuggerErrorCode.OPERATION_TIMEOUT,
          "Timed out retrieving thread with ID: " + threadId, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Failed to get thread by ID: " + e.getMessage(),
          e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Thread lookup interrupted", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Failed to get thread by ID: " + e.getMessage(),
          e);
    }
  }

  /**
   * Gets a thread by its name.
   *
   * @param threadName the thread name
   * @return the thread reference, or null if not found
   * @throws DebuggerException if operation fails
   */
  public ThreadReference getThreadByName(String threadName) {
    requireActive();

    try {
      CompletableFuture<ThreadReference> future = CompletableFuture.supplyAsync(
          () -> vm.allThreads().stream().filter(t -> t.name().equals(threadName)).findFirst().orElse(null),
          ensureExecutor());

      return future.get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      throw new DebuggerException(DebuggerErrorCode.OPERATION_TIMEOUT, "Timed out retrieving thread: " + threadName, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Failed to get thread by name: " + e.getMessage(),
          e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Thread lookup interrupted", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Failed to get thread by name: " + e.getMessage(),
          e);
    }
  }

  /**
   * Suspends a specific thread.
   *
   * @param threadId the thread ID
   * @throws DebuggerException if thread not found or operation fails
   */
  public void suspendThread(long threadId) {
    requireActive();

    try {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        ThreadReference thread = findThread(threadId);
        if (thread == null) {
          throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread not found: " + threadId);
        }

        if (thread.isSuspended()) {
          throw new DebuggerException(DebuggerErrorCode.THREAD_ALREADY_SUSPENDED,
              "Thread already suspended: " + thread.name());
        }

        thread.suspend();
        logger.debug("Suspended thread: {} (ID: {})", thread.name(), threadId);

      }, ensureExecutor());

      future.get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      throw new DebuggerException(DebuggerErrorCode.OPERATION_TIMEOUT, "Timed out suspending thread: " + threadId, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.THREAD_SUSPEND_FAILED,
          "Failed to suspend thread: " + e.getMessage(), e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Thread suspension interrupted", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_SUSPEND_FAILED,
          "Failed to suspend thread: " + e.getMessage(), e);
    }
  }

  /**
   * Resumes a specific thread.
   *
   * @param threadId the thread ID
   * @throws DebuggerException if thread not found or operation fails
   */
  public void resumeThread(long threadId) {
    requireActive();

    try {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        ThreadReference thread = findThread(threadId);
        if (thread == null) {
          throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread not found: " + threadId);
        }

        if (!thread.isSuspended()) {
          throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED, "Thread not suspended: " + thread.name());
        }

        thread.resume();
        logger.debug("Resumed thread: {} (ID: {})", thread.name(), threadId);

      }, ensureExecutor());

      future.get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      throw new DebuggerException(DebuggerErrorCode.OPERATION_TIMEOUT, "Timed out resuming thread: " + threadId, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.THREAD_RESUME_FAILED, "Failed to resume thread: " + e.getMessage(),
          e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Thread resume interrupted", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_RESUME_FAILED, "Failed to resume thread: " + e.getMessage(),
          e);
    }
  }

  /**
   * Resumes all suspended threads.
   */
  public void resumeAll() {
    requireActive();

    try {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        vm.resume();
        logger.debug("Resumed all threads");
      }, ensureExecutor());

      future.get(5, TimeUnit.SECONDS);

    } catch (TimeoutException e) {
      throw new DebuggerException(DebuggerErrorCode.OPERATION_TIMEOUT, "Timed out resuming all threads", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DebuggerException de) {
        throw de;
      }
      throw new DebuggerException(DebuggerErrorCode.THREAD_RESUME_FAILED,
          "Failed to resume all threads: " + e.getMessage(), e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Resume all interrupted", e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_RESUME_FAILED,
          "Failed to resume all threads: " + e.getMessage(), e);
    }
  }

  /**
   * Gets the VirtualMachine instance (for internal use by other debugger
   * components).
   *
   * @return the VirtualMachine
   */
  public VirtualMachine getVirtualMachine() {
    requireActive();
    return vm;
  }

  /**
   * Gets the debugger executor (for internal use by other debugger components).
   *
   * @return the executor
   */
  public ExecutorService getDebuggerExecutor() {
    return ensureExecutor();
  }

  /**
   * Gets the breakpoint manager (for internal use by debugger tools).
   *
   * @return the breakpoint manager
   */
  public BreakpointManager getBreakpointManager() {
    requireActive();
    return breakpointManager;
  }

  /**
   * Gets the stepping controller (for internal use by debugger tools).
   *
   * @return the stepping controller
   */
  public SteppingController getSteppingController() {
    requireActive();
    return steppingController;
  }

  /**
   * Gets the stack trace inspector (for internal use by debugger tools).
   *
   * @return the stack trace inspector
   */
  public StackTraceInspector getStackTraceInspector() {
    requireActive();
    return stackTraceInspector;
  }

  /**
   * Gets the variable reference manager (for internal use by debugger tools).
   *
   * @return the variable reference manager
   */
  public VariableReferenceManager getVariableReferenceManager() {
    requireActive();
    return variableReferenceManager;
  }

  /**
   * Gets the variable extractor (for internal use by debugger tools).
   *
   * @return the variable extractor
   */
  public VariableExtractor getVariableExtractor() {
    requireActive();
    return variableExtractor;
  }

  /**
   * Gets the evaluation provider (for internal use by debugger tools).
   *
   * @return the evaluation provider
   */
  public HybridEvaluationProvider getEvaluationProvider() {
    requireActive();
    return evaluationProvider;
  }

  /**
   * Gets the conditional breakpoint evaluator.
   *
   * @return the conditional breakpoint evaluator
   * @throws DebuggerException if session is not active
   */
  public ConditionalBreakpointEvaluator getConditionalBreakpointEvaluator() {
    requireActive();
    return conditionalBreakpointEvaluator;
  }

  /**
   * Gets the method breakpoint manager.
   *
   * @return the method breakpoint manager
   * @throws DebuggerException if session is not active
   */
  public MethodBreakpointManager getMethodBreakpointManager() {
    requireActive();
    return methodBreakpointManager;
  }

  /**
   * Gets the watch expression manager.
   *
   * @return the watch expression manager
   * @throws DebuggerException if session is not active
   */
  public WatchExpressionManager getWatchManager() {
    requireActive();
    return watchExpressionManager;
  }

  /**
   * Gets the MCP event bridge.
   *
   * @return the MCP event bridge
   * @throws DebuggerException if session is not active
   */
  public MCPEventBridge getMcpEventBridge() {
    requireActive();
    return mcpEventBridge;
  }

  /**
   * Gets the debugger metrics.
   *
   * @return the debugger metrics
   * @throws DebuggerException if session is not active
   */
  public DebuggerMetrics getMetrics() {
    requireActive();
    return metrics;
  }

  /**
   * Gets the synchronization coordinator used to await debugger notifications.
   *
   * @return the sync coordinator
   * @throws DebuggerException if the session is not active
   */
  public DebuggerSyncCoordinator getSyncCoordinator() {
    requireActive();
    return syncCoordinator;
  }

  // ========== Internal Methods ==========

  /**
   * Transitions to a new state with validation.
   *
   * <p>
   * Uses atomic compareAndSet to prevent race conditions (TOCTOU). If the state
   * changes between validation and update, the operation retries.
   */
  private void transitionTo(SessionState newState) {
    SessionState currentState;
    SessionState expectedState;

    do {
      currentState = state.get();
      if (currentState == newState) {
        logger.debug("State transition noop: {} -> {}", currentState, newState);
        return;
      }
      currentState.validateTransition(newState);
      expectedState = currentState;
      // Retry if state changed between validation and update (TOCTOU protection)
    } while (!state.compareAndSet(expectedState, newState));

    logger.debug("State transition: {} -> {}", currentState, newState);
    if (newState == SessionState.CLOSED) {
      logger.info("State transition to CLOSED triggered from {}", Thread.currentThread().getStackTrace()[2]);
    }
  }

  /**
   * Requires an active session, throws if not operational.
   */
  private void requireActive() {
    if (!isActive()) {
      throw new DebuggerException(DebuggerErrorCode.SESSION_NOT_ACTIVE,
          "No active debug session (current state: " + state.get() + ")");
    }
  }

  /**
   * Resets session state while keeping the connection alive (reuse mode only).
   *
   * <p>
   * <b>CRITICAL ORDERING:</b> This method implements a strict sequence to prevent
   * late-arriving events from re-establishing state after reset:
   * <ol>
   * <li><b>Step 1:</b> Unsubscribe all events (prevents late delivery to
   * handlers)</li>
   * <li><b>Step 2:</b> STOP EventHub (prevents new events from being
   * processed)</li>
   * <li><b>Step 3:</b> Small delay to drain debuggerExecutor in-flight tasks</li>
   * <li><b>Step 4:</b> Reset connection state (resume threads, clear
   * requests)</li>
   * <li><b>Step 5:</b> Clear tool-specific state</li>
   * <li><b>Step 6:</b> Verify clean state</li>
   * </ol>
   *
   * <p>
   * <b>Why This Ordering Matters:</b> EventHub must be stopped BEFORE resetting
   * connection state. If EventHub is still running, a late-arriving
   * BreakpointEvent could re-suspend a thread AFTER connectionManager.reset() has
   * resumed it, causing the next session to inherit the suspended thread.
   *
   * @throws DebuggerException if reset fails
   */
  private void resetSessionState() throws DebuggerException {
    logger.debug("Resetting session state - Step 1: Unsubscribing events");

    try {
      // Step 1: Unsubscribe ALL events FIRST (prevents late delivery to handlers)
      if (eventHub != null) {
        eventHub.unsubscribeAll(this);
      }

      logger.debug("Resetting session state - Step 2: Stopping EventHub");

      // Step 2: STOP EventHub to prevent new events from being processed
      // This is CRITICAL - must stop event-loop thread before resetting connection
      if (eventHub != null) {
        try {
          eventHub.stop();
        } catch (Exception hubStopEx) {
          logger.warn("Error stopping EventHub during reset: {}", hubStopEx.getMessage());
          // Continue - we still need to reset connection even if hub stop failed
        }
      }

      logger.debug("Resetting session state - Step 3: Draining in-flight events");

      // Step 3: Small delay to let debuggerExecutor finish in-flight events
      // (events already submitted but not yet processed)
      Thread.sleep(100);

      logger.debug("Resetting session state - Step 4: Resetting connection state");

      // Step 4: Reset connection state (resumes threads, clears EventRequests)
      if (connectionManager != null) {
        connectionManager.reset();
      }

      logger.debug("Resetting session state - Step 5: Clearing tool state");

      // Step 5: Clear tool-specific state
      clearToolState();

      logger.debug("Resetting session state - Step 6: Verifying clean state");

      // Step 6: Verify clean state (paranoid check)
      verifyCleanState();

      logger.info("Session state reset complete");

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Reset interrupted", e);
    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Failed to reset session state: " + e.getMessage(),
          e);
    }
  }

  /**
   * Clears state in tool components (breakpoints, watches, etc.).
   */
  private void clearToolState() {
    // Most tool state is cleared via EventRequests in connectionManager.reset()
    // (breakpoints, steps, watches, etc.)

    // Clear variable reference manager (non-EventRequest state)
    if (variableReferenceManager != null) {
      variableReferenceManager.clear();
    }

    // Note: WatchExpressionManager state is cleared when its EventRequests are
    // deleted in connectionManager.reset()
    // Note: BreakpointManager state is cleared when its EventRequests are deleted
    // in connectionManager.reset()
    // Note: SteppingController state is cleared when its EventRequests are deleted
    // in connectionManager.reset()

    logger.trace("Tool state cleared");
  }

  /**
   * Verifies that session state is clean after reset.
   */
  private void verifyCleanState() {
    if (vm == null) {
      logger.trace("No VM to verify (null)");
      return;
    }

    try {
      // Verify no suspended threads (paranoid check - connectionManager.reset()
      // should have done this)
      long suspendedCount = vm.allThreads().stream().filter(ThreadReference::isSuspended).count();

      if (suspendedCount > 0) {
        List<String> suspendedThreadNames = vm.allThreads().stream().filter(ThreadReference::isSuspended)
            .map(ThreadReference::name).toList();
        logger.warn("State verification failed: {} threads still suspended: {}", suspendedCount, suspendedThreadNames);
        throw new IllegalStateException(
            "Reset incomplete: " + suspendedCount + " threads still suspended: " + suspendedThreadNames);
      }

      logger.trace("State verification passed: no suspended threads");

    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      logger.warn("Error during state verification (non-critical): {}", e.getMessage());
    }
  }

  /**
   * Sets up event monitoring for critical events.
   *
   * <p>
   * Uses owner-tracked subscriptions for automatic cleanup via
   * eventHub.unsubscribeAll(this).
   */
  private void setupEventMonitoring() {
    // Monitor for VM disconnect using owner-tracked subscription
    // This will be automatically cleaned up by eventHub.unsubscribeAll(this) in
    // resetSessionState()
    eventHub.subscribe(this, VMDisconnectEvent.class, event -> {
      VirtualMachine currentVm = this.vm;
      VirtualMachine eventVm = event.virtualMachine();
      SessionState currentState = state.get();

      // Ignore disconnect events during CONNECTING or for wrong VM
      if (currentState == SessionState.CONNECTING || currentVm == null || currentVm != eventVm) {
        logger.debug("Ignoring VMDisconnectEvent during state {} for VM {}", currentState, eventVm);
        return;
      }

      logger.info("Processing VMDisconnectEvent for active VM {}", eventVm);
      transitionTo(SessionState.CLOSED);
    });

    eventHub.subscribe(this, ClassPrepareEvent.class, event -> {
      try {
        VirtualMachine currentVm = this.vm;
        SessionState currentState = state.get();
        VirtualMachine eventVm = event.virtualMachine();

        if (currentState != SessionState.READY || currentVm == null || currentVm != eventVm) {
          logger.debug("Ignoring ClassPrepareEvent during state {} for VM {}", currentState, eventVm);
          return;
        }

        handleClassPrepareEvent(event);
      } catch (Exception e) {
        logger.error("Failed to process ClassPrepareEvent", e);
      }
    });

    logger.debug("Event monitoring setup complete (owner-tracked subscription)");
  }

  private void handleClassPrepareEvent(ClassPrepareEvent event) {
    if (breakpointManager == null) {
      return;
    }

    List<BreakpointResolution> resolutions = breakpointManager.resolvePendingBreakpoints(event.referenceType());
    if (resolutions.isEmpty()) {
      return;
    }

    for (BreakpointResolution resolution : resolutions) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("breakpoint_id", resolution.breakpointId());
      payload.put("class_name", resolution.className());
      payload.put("line_number", resolution.lineNumber());
      payload.put("state", resolution.state().toApiValue());
      if (resolution.reason() != null) {
        payload.put("reason", resolution.reason());
      }
      DebuggerNotificationBroadcaster.getInstance()
          .broadcast(new MCPEventBridge.DebuggerNotification("debugger.breakpoint_resolved", payload));
    }

    logger.debug("Processed ClassPrepareEvent for {} with {} breakpoint resolution(s)", event.referenceType().name(),
        resolutions.size());
  }

  /**
   * Enables basic event requests (thread start/death for monitoring).
   */
  private void enableBasicEvents() {
    EventRequestManager erm = vm.eventRequestManager();

    // Enable thread start events
    ThreadStartRequest threadStartReq = erm.createThreadStartRequest();
    threadStartReq.setSuspendPolicy(EventRequest.SUSPEND_NONE);
    threadStartReq.enable();

    // Enable thread death events
    ThreadDeathRequest threadDeathReq = erm.createThreadDeathRequest();
    threadDeathReq.setSuspendPolicy(EventRequest.SUSPEND_NONE);
    threadDeathReq.enable();

    logger.debug("Basic event requests enabled");
  }

  /**
   * Finds a thread by ID.
   */
  private ThreadReference findThread(long threadId) {
    return vm.allThreads().stream().filter(t -> t.uniqueID() == threadId).findFirst().orElse(null);
  }

  /**
   * Creates ThreadInfo from ThreadReference.
   */
  private ThreadInfo createThreadInfo(ThreadReference thread) {
    try {
      boolean isVirtual = false;
      try {
        // JDK 21+ virtual thread support
        isVirtual = thread.isVirtual();
      } catch (UnsupportedOperationException e) {
        // JDK < 21 - no virtual thread support
      }

      String suspendReason = null;
      Location suspendLocation = null;

      // Wrap frame access in try-catch to handle race where thread state changes
      if (thread.isSuspended()) {
        try {
          if (thread.frameCount() > 0) {
            suspendLocation = thread.frame(0).location();
            suspendReason = "User suspended";
          }
        } catch (IncompatibleThreadStateException e) {
          // Thread state changed between isSuspended() check and frame access
          // This is a normal race condition - leave suspendLocation as null
          logger.trace("Thread state changed during frame access: {}", thread.name());
        }
      }

      return new ThreadInfo(thread.uniqueID(), thread.name(),
          thread.status() == ThreadReference.THREAD_STATUS_RUNNING ? "RUNNABLE"
              : thread.status() == ThreadReference.THREAD_STATUS_SLEEPING ? "TIMED_WAITING"
                  : thread.status() == ThreadReference.THREAD_STATUS_WAIT ? "WAITING"
                      : thread.status() == ThreadReference.THREAD_STATUS_MONITOR ? "BLOCKED" : "UNKNOWN",
          thread.isSuspended(), suspendReason, suspendLocation, isVirtual);
    } catch (Exception e) {
      logger.warn("Error creating ThreadInfo for thread {}: {}", thread.name(), e.getMessage());
      return new ThreadInfo(thread.uniqueID(), thread.name(), "UNKNOWN", false, null, null, false);
    }
  }

  /**
   * Registers a JVM shutdown hook to clean up debug sessions on JVM shutdown.
   *
   * <p>
   * This ensures that debug resources are properly released if the JVM terminates
   * unexpectedly (e.g., Ctrl+C, kill signal, System.exit()).
   */
  private synchronized void registerShutdownHook() {
    if (shutdownHook == null) {
      shutdownHook = new Thread(() -> {
        logger.info("JVM shutting down - cleaning up debug session");
        try {
          // Don't call stop() to avoid waiting on executor - just cleanup directly
          if (vm != null) {
            try {
              vm.resume();
              logger.debug("Resumed all threads in shutdown hook");
            } catch (Exception resumeEx) {
              logger.debug("Error resuming threads in shutdown hook: {}", resumeEx.getMessage());
            }
            vm.dispose();
          }
          if (eventHub != null) {
            eventHub.stop();
          }
          transitionTo(SessionState.CLOSED);
        } catch (Exception e) {
          logger.warn("Error during shutdown hook cleanup", e);
        }
      }, "DebuggerService-ShutdownHook");

      try {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        logger.debug("Shutdown hook registered");
      } catch (IllegalStateException e) {
        // JVM is already shutting down - ignore
        logger.debug("Could not register shutdown hook - JVM is shutting down");
        shutdownHook = null;
      }
    }
  }

  /**
   * Removes the shutdown hook if it was registered.
   *
   * <p>
   * Should be called during normal stop() to avoid unnecessary cleanup during JVM
   * shutdown.
   */
  private synchronized void removeShutdownHook() {
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        logger.debug("Shutdown hook removed");
      } catch (IllegalStateException e) {
        // JVM is already shutting down - hook will run anyway
        logger.debug("Could not remove shutdown hook - JVM is shutting down");
      }
      shutdownHook = null;
    }
  }
}
