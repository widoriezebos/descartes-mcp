package com.bitsapplied.descartes.debugger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;

/**
 * Base class for debugger integration tests.
 *
 * <p>
 * Provides common setup and teardown for debugger tests, including:
 * <ul>
 * <li>Starting/stopping debug sessions</li>
 * <li>Common assertions</li>
 * <li>Helper methods for testing</li>
 * </ul>
 *
 * <p>
 * <b>JDK Requirements:</b>
 * <ul>
 * <li>JDK 11+ required for JDWP</li>
 * <li>JDK 17+ requires --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED</li>
 * </ul>
 */
public abstract class DebuggerTestBase {
  protected static final Logger logger = LoggerFactory.getLogger(DebuggerTestBase.class);

  protected DebuggerService debuggerService;
  protected DebugSessionConfig config;

  /**
   * Sets up the debugger service before each test.
   */
  @BeforeEach
  public void setUp() {
    logger.info("Setting up debugger test...");

    // Ensure JDWP connector state is clean for every test
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // Create debugger service (singleton pattern)
    debuggerService = new DebuggerService();

    // Create default configuration
    config = new DebugSessionConfig(10000, // jdwpTimeout
        false, // stopOnEntry
        new String[] { "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*" } // skipPatterns
    );

    logger.info("Debugger test setup complete");
  }

  /**
   * Tears down the debugger service after each test.
   */
  @AfterEach
  public void tearDown() {
    logger.info("Tearing down debugger test...");

    if (debuggerService != null) {
      try {
        debuggerService.stop();
      } catch (Exception e) {
        logger.warn("Error stopping debugger service: {}", e.getMessage());
      }
    }

    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    logger.info("Debugger test teardown complete");
  }

  /**
   * Starts the debug session.
   *
   * @throws Exception if session cannot be started
   */
  protected void startDebugSession() throws Exception {
    logger.info("Starting debug session...");
    debuggerService.start(config);
    boolean ready = waitFor(() -> debuggerService.getState() == SessionState.READY, config.jdwpTimeout(), 50);
    if (!ready) {
      throw new IllegalStateException("Timed out waiting for debugger to reach READY state (current: "
          + debuggerService.getState() + ")");
    }
    logger.info("Debug session started");
  }

  /**
   * Stops the debug session.
   *
   * @throws Exception if session cannot be stopped
   */
  protected void stopDebugSession() throws Exception {
    logger.info("Stopping debug session...");
    debuggerService.stop();
    logger.info("Debug session stopped");
  }

  /**
   * Waits for a condition to be true with timeout.
   *
   * @param condition  the condition to check
   * @param timeoutMs  timeout in milliseconds
   * @param intervalMs check interval in milliseconds
   * @return true if condition became true, false if timeout
   */
  protected boolean waitFor(BooleanSupplier condition, long timeoutMs, long intervalMs) {
    long startTime = System.currentTimeMillis();

    while (System.currentTimeMillis() - startTime < timeoutMs) {
      if (condition.getAsBoolean()) {
        return true;
      }

      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    return false;
  }

  /**
   * Waits for a condition with default timeout (5 seconds).
   *
   * @param condition the condition to check
   * @return true if condition became true, false if timeout
   */
  protected boolean waitFor(BooleanSupplier condition) {
    return waitFor(condition, 5000, 100);
  }

  /**
   * Gets the fully qualified class name for SimpleTestApplication.
   *
   * @return class name
   */
  protected String getTestApplicationClassName() {
    return "com.bitsapplied.descartes.debugger.SimpleTestApplication";
  }

  /**
   * Functional interface for boolean conditions.
   */
  @FunctionalInterface
  protected interface BooleanSupplier {
    boolean getAsBoolean();
  }
}
