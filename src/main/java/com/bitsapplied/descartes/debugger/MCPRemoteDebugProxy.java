package com.bitsapplied.descartes.debugger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.settings.DefaultSettings;

/**
 * MCP Remote Debug Proxy - Standalone application for remote Java debugging.
 *
 * <p>
 * This proxy connects to remote JVMs via JDWP (Java Debug Wire Protocol) and
 * exposes debugging capabilities through the Model Context Protocol (MCP). It
 * acts as a bridge between MCP clients (like Claude Desktop) and remote Java
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
  private volatile ScheduledFuture<?> reconnectFuture;

  private volatile boolean running = false;

  /**
   * Creates a new proxy instance with the given configuration.
   */
  public MCPRemoteDebugProxy(RemoteDebugProxyConfig config) {
    this.config = config;
    this.shutdownLatch = new CountDownLatch(1);

    logger.info("Initializing MCP Remote Debug Proxy...");
    logger.info("Configuration: {}", config);

    // Create debugger components
    this.connectionManager = new JDWPConnectionManager(config.getJdwpHost(), config.getJdwpPort());
    this.debuggerService = new DebuggerService(connectionManager);
    this.debuggerExecutor = new DebuggerExecutor();

    // Create MCP server
    DefaultSettings settings = new DefaultSettings();
    Map<String, Object> context = new HashMap<>();
    context.put("remote.debug.jdwp.host", config.getJdwpHost());
    context.put("remote.debug.jdwp.port", config.getJdwpPort());
    context.put("remote.debug.jdwp.timeout", config.getJdwpTimeout());
    this.mcpServer = new MCPServer(settings, config.getMcpPort(), context);

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
    mcpServer.start();
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
    logger.info("  Tools:       {} JDWP-compatible tools registered", RemoteToolRegistry.getToolCount());
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
   * Starts health monitoring for debugger connection.
   *
   * <p>
   * Periodically checks connection health and attempts reconnection if needed.
   */
  private void startHealthMonitoring() {
    long intervalMs = config.getHealthCheckIntervalMs();

    healthCheckScheduler.scheduleAtFixedRate(() -> {
      try {
        DebugSessionConfig activeConfig = debuggerService.getConfig();
        if (activeConfig == null) {
          logger.trace("Health check: no active debugger session; skipping");
          return;
        }

        SessionState state = debuggerService.getState();
        boolean transportHealthy = connectionManager.isHealthy();

        if (state == SessionState.READY && transportHealthy) {
          logger.trace("Health check: debugger session healthy (state=READY)");
          return;
        }

        logger.warn("Health check: debugger unhealthy (state={}, transportHealthy={})", state, transportHealthy);
        attemptReconnect();
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
  private void attemptReconnect() {
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

    if (debuggerService.getConfig() == null) {
      logger.trace("Reconnect skipped: no prior debug session to restore");
      return;
    }

    synchronized (reconnectLock) {
      if (reconnectFuture != null && !reconnectFuture.isDone()) {
        logger.trace("Reconnect already scheduled (attempt #{})", reconnectAttempts.get() + 1);
        return;
      }

      reconnectAttempts.set(0);
      long initialDelay = 0L;
      logger.info("Scheduling reconnect attempt immediately");

      try {
        reconnectFuture = reconnectScheduler.schedule(this::performReconnectAttempt, initialDelay,
            TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException e) {
        logger.warn("Failed to schedule reconnect attempt: {}", e.getMessage());
        reconnectFuture = null;
      }
    }
  }

  private void performReconnectAttempt() {
    if (!running || !config.isReconnectEnabled()) {
      clearReconnectState();
      return;
    }

    DebugSessionConfig sessionConfig = debuggerService.getConfig();
    if (sessionConfig == null) {
      logger.trace("Reconnect attempt aborted: debug session configuration unavailable");
      clearReconnectState();
      return;
    }

    int attemptNumber = reconnectAttempts.incrementAndGet();
    long startTime = System.currentTimeMillis();

    try {
      ensureSessionStopped();
      debuggerService.start(sessionConfig);
      long elapsed = System.currentTimeMillis() - startTime;
      logger.info("Reconnect succeeded on attempt {} ({} ms)", attemptNumber, elapsed);
      clearReconnectState();
    } catch (Exception e) {
      long elapsed = System.currentTimeMillis() - startTime;
      logger.warn("Reconnect attempt #{} failed after {} ms: {}", attemptNumber, elapsed, e.getMessage());
      logger.debug("Reconnect attempt #{} failure details", attemptNumber, e);

      long delay = computeBackoffDelay(attemptNumber);
      synchronized (reconnectLock) {
        if (!running || reconnectScheduler.isShutdown()) {
          reconnectFuture = null;
          return;
        }
        try {
          reconnectFuture = reconnectScheduler.schedule(this::performReconnectAttempt, delay, TimeUnit.MILLISECONDS);
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
      logger.warn("Error while stopping debugger prior to reconnect: {}", e.getMessage(), e);
    }
  }

  private long computeBackoffDelay(int attemptNumber) {
    // Always retry on the configured cadence so redeploys are detected quickly
    return Math.max(1000L, config.getReconnectIntervalMs());
  }

  private void clearReconnectState() {
    synchronized (reconnectLock) {
      reconnectAttempts.set(0);
      reconnectFuture = null;
    }
  }

  private void cancelReconnect() {
    synchronized (reconnectLock) {
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
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  /**
   * Prints startup banner.
   */
  private static void printBanner() {
    System.out.println();
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println("  Descartes MCP - Remote Debug Proxy");
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println("  Standalone proxy for remote Java debugging via JDWP");
    System.out.println("  Exposes debugging capabilities through MCP protocol");
    System.out.println("═══════════════════════════════════════════════════════════");
    System.out.println();
  }
}
