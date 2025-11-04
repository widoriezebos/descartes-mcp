package com.bitsapplied.descartes.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;
import com.sun.jdi.ThreadReference;

/**
 * Integration tests for the debugger subsystem.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Session lifecycle (start/stop/status)</li>
 * <li>Thread operations (list/suspend/resume)</li>
 * <li>Basic debugger connectivity</li>
 * </ul>
 *
 * <p>
 * <b>Requirements:</b>
 * <ul>
 * <li>JDK 11+ for JDWP support</li>
 * <li>JDK 17+ requires --add-opens flag</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerIntegrationTest extends DebuggerTestBase {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerIntegrationTest.class);

  /**
   * Tests starting and stopping a debug session.
   */
  @Test
  public void testSessionLifecycle() throws Exception {
    logger.info("Testing session lifecycle...");

    // Verify initial state
    assertEquals(SessionState.CREATED, debuggerService.getState());

    // Start session
    startDebugSession();

    // Verify session is ready
    assertEquals(SessionState.READY, debuggerService.getState());

    // Stop session
    stopDebugSession();

    // Verify session is closed
    assertEquals(SessionState.CLOSED, debuggerService.getState());

    logger.info("Session lifecycle test passed");
  }

  /**
   * Tests listing threads in the debuggee.
   */
  @Test
  public void testListThreads() throws Exception {
    logger.info("Testing thread listing...");

    // Start session
    startDebugSession();

    // Get all threads
    List<ThreadInfo> threads = debuggerService.getThreads();

    // Verify we have threads
    assertNotNull(threads);
    assertFalse(threads.isEmpty(), "Should have at least one thread");

    // Log thread information
    logger.info("Found {} threads", threads.size());
    for (ThreadInfo thread : threads) {
      logger.info("  Thread: {} (id={}, state={}, suspended={}, virtual={})", thread.name(), thread.id(),
          thread.state(), thread.suspended(), thread.isVirtual());
    }

    // Verify we can find the main thread
    boolean foundMainThread = threads.stream().anyMatch(t -> t.name().equals("main"));
    assertTrue(foundMainThread, "Should find main thread");

    logger.info("Thread listing test passed");
  }

  /**
   * Tests getting thread by ID.
   */
  @Test
  public void testGetThreadById() throws Exception {
    logger.info("Testing get thread by ID...");

    // Start session
    startDebugSession();

    // Get all threads
    List<ThreadInfo> threads = debuggerService.getThreads();
    assertFalse(threads.isEmpty());

    // Get first thread by ID
    ThreadInfo firstThread = threads.get(0);
    ThreadReference threadRef = debuggerService.getThreadById(firstThread.id());

    assertNotNull(threadRef, "Should find thread by ID");
    assertEquals(firstThread.id(), threadRef.uniqueID());
    assertEquals(firstThread.name(), threadRef.name());

    logger.info("Get thread by ID test passed");
  }

  /**
   * Tests getting thread by name.
   */
  @Test
  public void testGetThreadByName() throws Exception {
    logger.info("Testing get thread by name...");

    // Start session
    startDebugSession();

    // Get main thread by name
    ThreadReference mainThread = debuggerService.getThreadByName("main");

    assertNotNull(mainThread, "Should find main thread by name");
    assertEquals("main", mainThread.name());

    logger.info("Get thread by name test passed");
  }

  /**
   * Tests that session cannot be started twice.
   */
  @Test
  public void testCannotStartSessionTwice() throws Exception {
    logger.info("Testing cannot start session twice...");

    // Start session
    startDebugSession();
    assertEquals(SessionState.READY, debuggerService.getState());

    // Try to start again - should throw exception
    assertThrows(Exception.class, () -> debuggerService.start(config), "Should not allow starting session twice");

    logger.info("Cannot start session twice test passed");
  }

  /**
   * Tests getting session state.
   */
  @Test
  public void testGetSessionState() throws Exception {
    logger.info("Testing get session state...");

    // Initial state
    SessionState state = debuggerService.getState();
    assertEquals(SessionState.CREATED, state);

    // Start session
    startDebugSession();
    state = debuggerService.getState();
    assertEquals(SessionState.READY, state);

    // Stop session
    stopDebugSession();
    state = debuggerService.getState();
    assertEquals(SessionState.CLOSED, state);

    logger.info("Get session state test passed");
  }

  /**
   * Tests accessing debugger components before session is started.
   */
  @Test
  public void testRequiresActiveSession() {
    logger.info("Testing requires active session...");

    // Try to access components before starting session
    assertThrows(Exception.class, () -> debuggerService.getThreads(), "Should require active session for getThreads()");

    assertThrows(Exception.class, () -> debuggerService.getBreakpointManager(),
        "Should require active session for getBreakpointManager()");

    assertThrows(Exception.class, () -> debuggerService.getEvaluationProvider(),
        "Should require active session for getEvaluationProvider()");

    logger.info("Requires active session test passed");
  }

  /**
   * Tests getting debugger metrics.
   */
  @Test
  public void testGetMetrics() throws Exception {
    logger.info("Testing get metrics...");

    // Start session
    startDebugSession();

    // Get metrics
    var metrics = debuggerService.getMetrics();
    assertNotNull(metrics, "Metrics should not be null");

    // Get metrics summary
    var summary = metrics.getSummary();
    assertNotNull(summary, "Metrics summary should not be null");
    assertTrue(summary.containsKey("session_duration_seconds"));

    logger.info("Get metrics test passed");
  }

  /**
   * Tests event hub integration.
   */
  @Test
  public void testEventHubIntegration() throws Exception {
    logger.info("Testing event hub integration...");

    // Start session
    startDebugSession();

    // Get event hub
    var eventHub = debuggerService.events();
    assertNotNull(eventHub, "Event hub should not be null");

    logger.info("Event hub integration test passed");
  }

  /**
   * Tests MCP event bridge integration.
   */
  @Test
  public void testMCPEventBridgeIntegration() throws Exception {
    logger.info("Testing MCP event bridge integration...");

    // Start session
    startDebugSession();

    // Get MCP event bridge
    var bridge = debuggerService.getMcpEventBridge();
    assertNotNull(bridge, "MCP event bridge should not be null");
    assertTrue(bridge.isStarted(), "MCP event bridge should be started");

    logger.info("MCP event bridge integration test passed");
  }
}
