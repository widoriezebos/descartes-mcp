package com.bitsapplied.descartes.debugger;

import java.net.BindException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.settings.DefaultSettings;
import com.bitsapplied.descartes.util.BuildInfo;

/**
 * MCP Remote Debug Proxy - Standalone application for remote Java debugging.
 *
 * <p>
 * This proxy connects to remote JVMs via JDWP (Java Debug Wire Protocol) and
 * exposes debugging capabilities through the Model Context Protocol (MCP). It
 * acts as a bridge between MCP clients (like Claude Code) and remote Java
 * applications.
 *
 * <h2>Key Features:</h2>
 * <ul>
 * <li>Standalone deployment (no dependencies in target application)
 * <li>JDWP-compatible tools only (11 tools: debugger_*, thread_analyzer,
 * object_inspector)
 * <li>Multi-source configuration (CLI > file > env > defaults)
 * <li>Health monitoring and auto-reconnect
 * <li>Graceful shutdown with resource cleanup
 * </ul>
 *
 * <h2>Usage:</h2>
 * 
 * <pre>
 * # Basic usage (local debugging)
 * java -jar descartes-mcp.jar --jdwp-port 5005
 *
 * # Remote debugging
 * java -jar descartes-mcp.jar --jdwp-host staging.example.com --jdwp-port 5005
 *
 * # With config file
 * java -jar descartes-mcp.jar --config proxy-config.json
 * </pre>
 *
 * <p>
 * See doc/MCPRemoteDebugProxy.md for comprehensive documentation.
 *
 * @see RemoteDebugProxyConfig
 * @see ConfigLoader
 * @see RemoteToolRegistry
 */
public class MCPRemoteDebugProxy {

  private static final Logger logger = LoggerFactory.getLogger(MCPRemoteDebugProxy.class);
  public static final String CONTEXT_RECONNECT_CONTROL = "remote.debug.reconnect.control";
  private final RemoteDebugProxyConfig config;
  private final MCPServer mcpServer;
  private final JDWPConnectionManager connectionManager;
  private final DebuggerService debuggerService;
  private final DebuggerExecutor debuggerExecutor;
  private final ScheduledExecutorService healthCheckScheduler;
  private final ScheduledExecutorService reconnectScheduler;
  private final CountDownLatch shutdownLatch;
  private final Object reconnectLock = new Object();
  private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
  private final AtomicLong reconnectGeneration = new AtomicLong(0);
  private final AtomicInteger manualReconnectPauseCount = new AtomicInteger(0);
  private final ReconnectControl reconnectControl;
  private volatile ScheduledFuture<?> reconnectFuture;
  private final AtomicBoolean sessionObserved = new AtomicBoolean(false);

  private volatile boolean running = false;
  private volatile boolean pidFileWritten = false;

  /**
   * Creates a new proxy instance with the given configuration.
   */
  public MCPRemoteDebugProxy(RemoteDebugProxyConfig config) {
    this.config = config;
    this.shutdownLatch = new CountDownLatch(1);

    logger.info("Initializing MCP Remote Debug Proxy...");
    logger.info("Build: {}", BuildInfo.describe());
    logger.info("Configuration: {}", config);

    // Create debugger components
    this.connectionManager = new JDWPConnectionManager(config.getJdwpHost(), config.getJdwpPort());
    this.debuggerService = new DebuggerService(connectionManager, true);
    this.debuggerExecutor = new DebuggerExecutor();

    // Create MCP server
    DefaultSettings settings = new DefaultSettings();
    Map<String, Object> context = new HashMap<>();
    context.put("remote.debug.jdwp.host", config.getJdwpHost());
    context.put("remote.debug.jdwp.port", config.getJdwpPort());
    context.put("remote.debug.jdwp.timeout", config.getJdwpTimeout());
    this.reconnectControl = new ReconnectControl() {
      @Override
      public void pauseAutoReconnect(String reason) {
        if (!config.isReconnectEnabled()) {
          return;
        }
        int holds = manualReconnectPauseCount.incrementAndGet();
        logger.info("Pausing auto-reconnect for manual debugger operation (reason='{}', holds={})", reason, holds);
        cancelReconnect();
      }

      @Override
      public void resumeAutoReconnect(String reason) {
        if (!config.isReconnectEnabled()) {
          return;
        }
        int holds = manualReconnectPauseCount.updateAndGet(v -> v > 0 ? v - 1 : 0);
        logger.info("Resuming auto-reconnect after manual debugger operation (reason='{}', holds={})", reason, holds);
      }

      @Override
      public boolean isManualSessionInProgress() {
        return manualReconnectPauseCount.get() > 0;
      }
    };
    context.put(CONTEXT_RECONNECT_CONTROL, reconnectControl);
    this.mcpServer = new MCPServer(settings, config.getMcpPort(), context);
    this.mcpServer.setServerVersion(BuildInfo.describe());

    // Register JDWP-compatible tools
    RemoteToolRegistry.registerTools(mcpServer, context, debuggerService, debuggerExecutor);

    // Create health check scheduler if reconnect enabled
    if (config.isReconnectEnabled()) {
      this.healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "descartes-health-monitor");
        thread.setDaemon(true);
        return thread;
      });
      this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "descartes-reconnect");
        thread.setDaemon(true);
        return thread;
      });
      logger.info("Health monitoring enabled (interval: {}ms)", config.getHealthCheckIntervalMs());
      logger.info("Auto-reconnect enabled (base interval: {}ms)", config.getReconnectIntervalMs());
    } else {
      this.healthCheckScheduler = null;
      this.reconnectScheduler = null;
      logger.info("Health monitoring disabled");
      logger.info("Auto-reconnect disabled");
    }

    // Register shutdown hook
    registerShutdownHook();

    logger.info("MCP Remote Debug Proxy initialized successfully");
  }

  /**
   * Starts the proxy server.
   *
   * <p>
   * This will:
   * <ol>
   * <li>Start the MCP server on configured port
   * <li>Connect to target JVM via JDWP (auto-detect or explicit host/port)
   * <li>Start health monitoring (if enabled)
   * <li>Block until shutdown
   * </ol>
   *
   * @throws Exception if startup fails
   */
  public void start() throws Exception {
    logger.info("Starting MCP Remote Debug Proxy...");

    // Start MCP server
    logger.info("Starting MCP server on port {}...", config.getMcpPort());
    try {
      mcpServer.start();
    } catch (BindException e) {
      resolvePortConflictAndStart(e);
    }
    ProxyInstanceGuard.writePidFile(config.getMcpPort(), BuildInfo.describe());
    pidFileWritten = true;
    logger.info("MCP server started successfully on port {}", config.getMcpPort());

    // Note: We don't automatically connect to JDWP here - that happens when
    // the MCP client calls debugger_session.start with explicit host/port.
    // This allows the proxy to connect to different targets without restart.

    logger.info("Ready to accept MCP connections on port {}", config.getMcpPort());
    logger.info("To start debugging, use: debugger_session start with host={}, port={}", config.getJdwpHost(),
        config.getJdwpPort());

    // Start health monitoring if enabled
    if (healthCheckScheduler != null) {
      startHealthMonitoring();
    }

    running = true;

    logger.info("═══════════════════════════════════════════════════════════");
    logger.info("MCP Remote Debug Proxy is running");
    logger.info("═══════════════════════════════════════════════════════════");
    logger.info("  MCP Port:    {}", config.getMcpPort());
    logger.info("  JDWP Target: {}:{}", config.getJdwpHost(), config.getJdwpPort());
    logger.info("  Tools:       All JDWP-compatible tools registered");
    logger.info("═══════════════════════════════════════════════════════════");
    logger.info("Press Ctrl+C to shut down gracefully");
    logger.info("═══════════════════════════════════════════════════════════");

    // Wait for shutdown signal
    try {
      shutdownLatch.await();
    } catch (InterruptedException e) {
      logger.warn("Interrupted while waiting for shutdown", e);
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Handles an MCP-port bind conflict: identifies the owning instance and
   * either terminates it (with --replace) and retries the bind, or fails with
   * an actionable message.
   */
  private void resolvePortConflictAndStart(BindException cause) throws Exception {
    Optional<ProxyInstanceGuard.ExistingInstance> existing = ProxyInstanceGuard.findExisting(config.getMcpPort());
    String owner = existing.map(ProxyInstanceGuard.ExistingInstance::describe)
        .orElse("unknown owner; try: lsof -i tcp:" + config.getMcpPort());

    if (!config.isReplaceExisting()) {
      throw new IllegalStateException(String.format(
          "MCP port %d is already in use by another proxy instance (%s). "
              + "Stop that instance, or relaunch with --replace to take over the port.",
          config.getMcpPort(), owner), cause);
    }

    if (existing.isEmpty()) {
      throw new IllegalStateException(String.format(
          "MCP port %d is already in use but the owner could not be identified (%s); not replacing.",
          config.getMcpPort(), owner), cause);
    }

    logger.warn("Replacing running proxy instance ({}) on MCP port {}", owner, config.getMcpPort());
    if (!ProxyInstanceGuard.terminate(existing.get().pid(), Duration.ofSeconds(10))) {
      throw new IllegalStateException(
          "Could not terminate existing proxy instance (" + owner + ") to free MCP port " + config.getMcpPort());
    }

    // The listener socket is released when the old process exits; allow a
    // short grace period for the kernel to finish tearing it down.
    BindException lastFailure = cause;
    for (int attempt = 0; attempt < 20; attempt++) {
      try {
        mcpServer.start();
        return;
      } catch (BindException retry) {
        lastFailure = retry;
        Thread.sleep(250);
      }
    }
    throw lastFailure;
  }

  /**
   * Starts health monitoring for debugger connection.
   *
   * <p>
   * Periodically checks connection health and attempts reconnection if needed.
   */
  private void startHealthMonitoring() {
    long intervalMs = config.getHealthCheckIntervalMs();

    healthCheckScheduler.scheduleAtFixedRate(() -> {
      try {
        if (isManualSessionInProgress()) {
          logger.trace("Health check: manual session operation in progress; skipping reconnect checks");
          return;
        }

        DebugSessionConfig activeConfig = debuggerService.getConfig();
        if (activeConfig == null) {
          if (sessionObserved.compareAndSet(true, false)) {
            logger.info("No active debugger session; auto-reconnect is disarmed until the next debugger_session start");
          }
          logger.trace("Health check: no active debugger session; skipping");
          return;
        }
        sessionObserved.set(true);

        SessionState state = debuggerService.getState();
        boolean transportHealthy = connectionManager.isHealthy();

        if (!needsReconnect(state, transportHealthy)) {
          logger.trace("Health check: debugger session healthy (state={})", state);
          return;
        }

        logger.warn("Health check: debugger unhealthy (state={}, transportHealthy={})", state, transportHealthy);
        attemptReconnect(activeConfig);
      } catch (Exception e) {
        logger.error("Health check failed", e);
      }
    }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

    logger.info("Health monitoring started");
  }

  /**
   * Attempts to reconnect to JDWP target.
   *
   * <p>
   * Uses fixed-interval retries based on configured reconnect interval.
   */
  private void attemptReconnect(DebugSessionConfig sessionConfig) {
    if (!config.isReconnectEnabled()) {
      logger.trace("Reconnect requested but auto-reconnect is disabled");
      return;
    }

    if (!running) {
      logger.trace("Reconnect requested while proxy is stopped");
      return;
    }

    if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
      logger.warn("Reconnect scheduler not available; cannot schedule reconnection");
      return;
    }

    if (sessionConfig == null) {
      logger.trace("Reconnect skipped: no prior debug session to restore");
      return;
    }
    if (isManualSessionInProgress()) {
      logger.trace("Reconnect skipped: manual debugger session operation in progress");
      return;
    }
    SessionState currentState = debuggerService.getState();
    if (currentState == SessionState.CONNECTING) {
      logger.trace("Reconnect skipped: debugger already in CONNECTING state");
      return;
    }

    synchronized (reconnectLock) {
      if (reconnectFuture != null && !reconnectFuture.isDone()) {
        logger.trace("Reconnect already scheduled (attempt #{})", reconnectAttempts.get() + 1);
        return;
      }

      reconnectAttempts.set(0);
      long generation = reconnectGeneration.incrementAndGet();
      long initialDelay = 0L;
      logger.info("Scheduling reconnect attempt immediately");

      try {
        reconnectFuture = reconnectScheduler.schedule(() -> performReconnectAttempt(sessionConfig, generation),
            initialDelay, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException e) {
        logger.warn("Failed to schedule reconnect attempt: {}", e.getMessage());
        reconnectFuture = null;
      }
    }
  }

  private void performReconnectAttempt(DebugSessionConfig sessionConfig, long generation) {
    if (!isCurrentReconnectGeneration(generation)) {
      return;
    }
    if (!running || !config.isReconnectEnabled()) {
      clearReconnectState(generation);
      return;
    }
    if (isManualSessionInProgress()) {
      logger.trace("Reconnect attempt aborted: manual debugger session operation in progress");
      clearReconnectState(generation);
      return;
    }

    SessionState currentState = debuggerService.getState();
    boolean transportHealthy = connectionManager.isHealthy();
    if (!isCurrentReconnectGeneration(generation)) {
      return;
    }
    if (!needsReconnect(currentState, transportHealthy)) {
      logger.info("Reconnect no longer needed; debugger is healthy (state={})", currentState);
      clearReconnectState(generation);
      return;
    }

    int attemptNumber = reconnectAttempts.incrementAndGet();
    long startTime = System.currentTimeMillis();

    try {
      if (!isCurrentReconnectGeneration(generation) || isManualSessionInProgress()) {
        logger.trace("Reconnect attempt aborted before start: manual operation hold detected");
        clearReconnectState(generation);
        return;
      }
      ensureSessionStopped();
      if (!isCurrentReconnectGeneration(generation) || isManualSessionInProgress()) {
        logger.trace("Reconnect attempt aborted after stop: manual operation hold detected");
        clearReconnectState(generation);
        return;
      }
      // A reconnect must never reuse the JDI handle that was associated with the
      // unhealthy session. JDI caches some metadata, so a disconnected handle can
      // otherwise appear healthy until the first real round trip.
      synchronized (reconnectLock) {
        // Linearize destructive connection invalidation with cancelReconnect(). A
        // manual operation that acquires its pause first fences this task out; if
        // this task gets here first, invalidation completes before that pause
        // returns to the caller.
        if (!isCurrentReconnectGeneration(generation) || isManualSessionInProgress()) {
          logger.trace("Reconnect attempt canceled before connection invalidation");
          clearReconnectState(generation);
          return;
        }
        connectionManager.invalidateForReconnect("automatic reconnect attempt " + attemptNumber);
      }
      if (!isCurrentReconnectGeneration(generation) || isManualSessionInProgress()) {
        logger.trace("Reconnect attempt canceled before debugger start");
        clearReconnectState(generation);
        return;
      }
      debuggerService.start(sessionConfig);
      if (!isCurrentReconnectGeneration(generation)) {
        logger.debug("Discarding success from canceled reconnect generation {}", generation);
        return;
      }
      long elapsed = System.currentTimeMillis() - startTime;
      logger.info("Reconnect succeeded on attempt {} ({} ms)", attemptNumber, elapsed);
      clearReconnectState(generation);
    } catch (Exception e) {
      if (!isCurrentReconnectGeneration(generation)) {
        logger.debug("Discarding failure from canceled reconnect generation {}", generation);
        return;
      }
      long elapsed = System.currentTimeMillis() - startTime;
      logger.warn("Reconnect attempt #{} failed after {} ms: {}", attemptNumber, elapsed, e.getMessage());
      logger.debug("Reconnect attempt #{} failure details", attemptNumber, e);

      long delay = computeBackoffDelay(attemptNumber);
      synchronized (reconnectLock) {
        if (!isCurrentReconnectGeneration(generation) || !running || reconnectScheduler.isShutdown()
            || isManualSessionInProgress()) {
          reconnectFuture = null;
          return;
        }
        try {
          // Keep the configuration captured from the last healthy session. A failed
          // DebuggerService.start() clears its active config, but that must not stop
          // the proxy's indefinite reconnect loop.
          reconnectFuture = reconnectScheduler.schedule(() -> performReconnectAttempt(sessionConfig, generation),
              delay, TimeUnit.MILLISECONDS);
          logger.info("Scheduled reconnect attempt #{} in {} ms", attemptNumber + 1, delay);
        } catch (RejectedExecutionException ex) {
          logger.warn("Failed to schedule next reconnect attempt: {}", ex.getMessage());
          reconnectFuture = null;
        }
      }
    }
  }

  private void ensureSessionStopped() {
    SessionState state = debuggerService.getState();
    if (state == SessionState.CREATED || state == SessionState.CLOSED) {
      return;
    }
    try {
      debuggerService.stop();
    } catch (Exception e) {
      logger.warn("Could not stop debugger cleanly before reconnect: {}", e.getMessage());
      logger.debug("Pre-reconnect debugger stop failure details", e);
    }
  }

  private long computeBackoffDelay(int attemptNumber) {
    // Always retry on the configured cadence so redeploys are detected quickly
    return Math.max(1000L, config.getReconnectIntervalMs());
  }

  static boolean needsReconnect(SessionState state, boolean transportHealthy) {
    return state == null || !state.isOperational() || !transportHealthy;
  }

  private boolean isCurrentReconnectGeneration(long generation) {
    return reconnectGeneration.get() == generation;
  }

  private void clearReconnectState(long generation) {
    synchronized (reconnectLock) {
      if (!isCurrentReconnectGeneration(generation)) {
        return;
      }
      reconnectGeneration.incrementAndGet();
      reconnectAttempts.set(0);
      reconnectFuture = null;
    }
  }

  private boolean isManualSessionInProgress() {
    return reconnectControl.isManualSessionInProgress();
  }

  private void cancelReconnect() {
    synchronized (reconnectLock) {
      // Fence out a task that is already running. JDI socket calls may not observe
      // Future.cancel(true) until after the manual operation has completed.
      reconnectGeneration.incrementAndGet();
      if (reconnectFuture != null && !reconnectFuture.isDone()) {
        reconnectFuture.cancel(true);
      }
      reconnectFuture = null;
      reconnectAttempts.set(0);
    }
  }

  /**
   * Stops the proxy server gracefully.
   */
  public void stop() {
    if (!running) {
      logger.warn("Proxy is not running");
      return;
    }

    logger.info("Shutting down MCP Remote Debug Proxy...");
    running = false;

    try {
      cancelReconnect();

      // Stop health monitoring
      if (healthCheckScheduler != null) {
        logger.info("Stopping health monitoring...");
        healthCheckScheduler.shutdown();
        if (!healthCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.warn("Health check scheduler did not terminate in time, forcing shutdown");
          healthCheckScheduler.shutdownNow();
        }
      }

      if (reconnectScheduler != null) {
        logger.info("Stopping reconnect scheduler...");
        reconnectScheduler.shutdown();
        if (!reconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.warn("Reconnect scheduler did not terminate in time, forcing shutdown");
          reconnectScheduler.shutdownNow();
        }
      }

      try {
        debuggerService.stop();
      } catch (Exception e) {
        logger.warn("Error stopping debugger service during shutdown", e);
      }

      // Stop debugger executor
      logger.info("Stopping debugger executor...");
      debuggerExecutor.shutdown();

      // Stop MCP server
      logger.info("Stopping MCP server...");
      mcpServer.stop();

      logger.info("Shutting down JDWP connection manager...");
      connectionManager.shutdown();

      logger.info("MCP Remote Debug Proxy stopped successfully");

    } catch (Exception e) {
      logger.error("Error during shutdown", e);
    } finally {
      if (pidFileWritten) {
        ProxyInstanceGuard.deletePidFile(config.getMcpPort());
        pidFileWritten = false;
      }
      shutdownLatch.countDown();
    }
  }

  /**
   * Registers JVM shutdown hook for graceful cleanup.
   */
  private void registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      logger.info("Shutdown signal received");
      stop();
    }, "shutdown-hook"));

    logger.debug("Shutdown hook registered");
  }

  /**
   * Main entry point for the remote debug proxy.
   *
   * @param args command-line arguments (see ConfigLoader for supported args)
   */
  public static void main(String[] args) {
    // Set conservative logging defaults before any heavy startup work.
    ProxyLogLevel activeLogLevel = resolveBootstrapLogLevel(args);
    ProxyLoggingConfigurator.configure(activeLogLevel);

    // Print banner
    printBanner();

    // Handle help flag
    if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
      ConfigLoader.printUsage();
      System.exit(0);
    }

    try {
      // Load configuration from all sources
      RemoteDebugProxyConfig config = ConfigLoader.load(args);
      activeLogLevel = config.getLogLevel();
      ProxyLoggingConfigurator.configure(activeLogLevel);

      // Create and start proxy
      MCPRemoteDebugProxy proxy = new MCPRemoteDebugProxy(config);
      proxy.start();

    } catch (IllegalArgumentException e) {
      logger.error("Configuration error: {}", e.getMessage());
      System.err.println("\nConfiguration error: " + e.getMessage());
      System.err.println("\nUse --help for usage information");
      System.exit(1);

    } catch (Exception e) {
      logger.error("Fatal error starting proxy", e);
      System.err.println("\nFatal error: " + e.getMessage());
      if (activeLogLevel == ProxyLogLevel.DEBUG) {
        e.printStackTrace(System.err);
      }
      System.exit(1);
    }
  }

  private static ProxyLogLevel resolveBootstrapLogLevel(String[] args) {
    String cliValue = null;
    for (int i = 0; i < args.length - 1; i++) {
      if ("--log-level".equals(args[i])) {
        String candidate = args[i + 1];
        if (!candidate.startsWith("--")) {
          cliValue = candidate;
        }
        break;
      }
    }

    String value = cliValue != null ? cliValue : System.getenv("DESCARTES_LOG_LEVEL");
    if (value == null || value.isBlank()) {
      return ProxyLogLevel.INFO;
    }
    try {
      return ProxyLogLevel.parse(value);
    } catch (IllegalArgumentException e) {
      System.err.println("Invalid --log-level value '" + value + "'. Falling back to INFO for bootstrap logging.");
      return ProxyLogLevel.INFO;
    }
  }

  /**
   * Prints startup banner.
   */
  private static void printBanner() {
    System.out.println();
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println("  Descartes MCP - Remote Debug Proxy");
    System.out.println("  Build: " + BuildInfo.describe());
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println("  Standalone proxy for remote Java debugging via JDWP");
    System.out.println("  Exposes debugging capabilities through MCP protocol");
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println();
  }
}
