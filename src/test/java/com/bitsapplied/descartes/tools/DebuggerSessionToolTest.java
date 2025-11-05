package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggeeLauncher;
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.JDWPConnectionManager;
import com.bitsapplied.descartes.debugger.JDWPConnectionManager.ConnectionMetrics;
import com.bitsapplied.descartes.debugger.JDWPConnector;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for DebuggerSessionTool.
 *
 * <p>
 * <b>Test Lifecycle:</b> Uses connection reuse mode
 * with @TestInstance(PER_CLASS) to share a single JDWP connection across all
 * tests. This eliminates ~10s reconnection overhead per test.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Start operation with various configurations</li>
 * <li>Stop operation</li>
 * <li>Status operation</li>
 * <li>Threads operation</li>
 * <li>Suspend/resume operations</li>
 * <li>Error handling</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerSessionToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerSessionToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  // Shared connection manager for all tests in this class
  private JDWPConnectionManager connectionManager;

  // Per-test instances
  private DebuggerSessionTool tool;
  private ObjectMapper objectMapper;
  private DebuggerService debuggerService;
  private DebuggerExecutor debuggerExecutor;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    // IMPORTANT: HotSpot cannot enable the JDWP agent dynamically (Agent_OnAttach
    // is missing on all
    // tested releases). Do not remove this launcher or re-enable -agentlib on
    // Surefire—
    // tests will silently attach to the wrong process and become flaky.
    debuggee = DebuggeeLauncher.launchAndWait();
    logger.info("Debuggee launched on port {}", debuggee.getJdwpPort());

    logger.info("Setting up JDWP connection manager (connection reuse mode)");

    // Reset circuit breaker for clean start
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // Create connection manager for reuse across all tests
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() {
    // Create fresh DebuggerService instance that shares the connection
    debuggerService = new DebuggerService(connectionManager);
    debuggerExecutor = new DebuggerExecutor();
    tool = new DebuggerSessionTool(debuggerService, debuggerExecutor);
    objectMapper = new ObjectMapper();

    logger.debug("Test setup complete - fresh service instance created");
  }

  @AfterEach
  public void tearDown() {
    try {
      // Ensure session is stopped after each test
      if (debuggerService.getState() != SessionState.CLOSED) {
        debuggerService.stop(); // This will reset state, not dispose connection
      }

      // Verify clean state (paranoid check)
      verifyCleanState();

    } catch (Exception e) {
      logger.warn("Error cleaning up debug session: {}", e.getMessage());
    }
  }

  @AfterAll
  public void shutdownConnectionManager() throws Exception {
    if (connectionManager != null) {
      logger.info("Shutting down JDWP connection manager");

      // Print metrics before shutdown
      ConnectionMetrics metrics = connectionManager.getMetrics();
      logger.info("=== Connection Manager Metrics ===");
      logger.info(metrics.getSummary());

      connectionManager.shutdown();
    }
    if (debuggee != null) {
      logger.info("Terminating debuggee process...");
      debuggee.terminate();
    }
  }

  /**
   * Verifies that no state leakage occurred between tests.
   */
  private void verifyCleanState() {
    // CRITICAL: Assert clean state after each test to catch regressions
    // If these assertions fail, the connection was not properly reset
    if (connectionManager != null && connectionManager.getCurrentConnection() != null) {
      try {
        // Assert no suspended threads
        assertFalse(connectionManager.hasSuspendedThreads(),
            "VM has suspended threads after reset - state leak detected!");

        // Assert no active EventRequests
        assertFalse(connectionManager.hasActiveRequests(),
            "VM has active EventRequests after reset - state leak detected!");

        // Assert connection health
        assertTrue(connectionManager.isHealthy(), "Connection health check failed after reset");

        logger.debug("State verification passed: VM is clean");

      } catch (Exception e) {
        fail("State verification failed with exception: " + e.getMessage() + " - " + e.getClass().getName());
      }
    }
  }

  /**
   * Tests tool metadata.
   */
  @Test
  public void testToolMetadata() {
    logger.info("Testing tool metadata...");

    assertEquals("debugger_session", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("debug"));
    assertTrue(description.contains("session"));
    assertTrue(description.contains("JDK 11+"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties);
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("jdwp_timeout"));
    assertTrue(properties.containsKey("stop_on_entry"));
    assertTrue(properties.containsKey("skip_patterns"));
    assertTrue(properties.containsKey("thread_id"));

    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));

    logger.info("Tool metadata test passed");
  }

  /**
   * Tests schema includes all operations in enum.
   */
  @Test
  public void testSchemaOperations() throws Exception {
    logger.info("Testing schema operations...");

    Map<String, Object> schema = tool.getToolSchema();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

    @SuppressWarnings("unchecked")
    Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");

    @SuppressWarnings("unchecked")
    List<String> operations = (List<String>) operationProp.get("enum");

    assertTrue(operations.contains("start"));
    assertTrue(operations.contains("stop"));
    assertTrue(operations.contains("status"));
    assertTrue(operations.contains("threads"));
    assertTrue(operations.contains("suspend"));
    assertTrue(operations.contains("resume"));
    assertTrue(operations.contains("resume_all"));

    logger.info("Schema operations test passed");
  }

  /**
   * Tests start operation with default config.
   */
  @Test
  public void testStartOperation() throws Exception {
    logger.info("Testing start operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "start");

    ToolResponse response = tool.executeAsync(args).get();
    if (response instanceof ToolResponse.Error error) {
      logger.error("Start operation failed: {}", error.message());
      throw new AssertionError("Expected Success but got Error: " + error.message());
    }

    String resultJson = ((ToolResponse.Success) response).content();
    assertNotNull(resultJson);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("message"));
    assertEquals("READY", result.get("state"));

    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) result.get("config");
    assertNotNull(config);

    logger.info("Start operation test passed");
  }

  /**
   * Tests start operation with custom configuration.
   */
  @Test
  public void testStartOperationWithCustomConfig() throws Exception {
    logger.info("Testing start operation with custom config...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "start");
    args.put("jdwp_timeout", 10000);
    args.put("stop_on_entry", true);
    args.put("skip_patterns", List.of("java.*", "com.example.*"));

    ToolResponse resp1 = tool.executeAsync(args).get();


    if (resp1 instanceof ToolResponse.Error error) {


      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


    }


    String resultJson = ((ToolResponse.Success) resp1).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) result.get("config");
    assertEquals(10000, config.get("jdwp_timeout"));
    assertEquals(true, config.get("stop_on_entry"));

    logger.info("Start operation with custom config test passed");
  }

  /**
   * Tests stop operation.
   */
  @Test
  public void testStopOperation() throws Exception {
    logger.info("Testing stop operation...");

    // Start first
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Then stop
    Map<String, Object> stopArgs = new HashMap<>();
    stopArgs.put("operation", "stop");

    ToolResponse resp2 = tool.executeAsync(stopArgs).get();


    if (resp2 instanceof ToolResponse.Error error) {


      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


    }


    String resultJson = ((ToolResponse.Success) resp2).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals("CLOSED", result.get("state"));

    logger.info("Stop operation test passed");
  }

  /**
   * Tests status operation when session is not started.
   */
  @Test
  public void testStatusOperationNotStarted() throws Exception {
    logger.info("Testing status operation not started...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "status");

    ToolResponse resp3 = tool.executeAsync(args).get();


    if (resp3 instanceof ToolResponse.Error error) {


      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


    }


    String resultJson = ((ToolResponse.Success) resp3).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("state"));
    assertFalse((Boolean) result.get("active"));

    logger.info("Status operation not started test passed");
  }

  /**
   * Tests status operation when session is active.
   */
  @Test
  public void testStatusOperationActive() throws Exception {
    logger.info("Testing status operation active...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Get status
    Map<String, Object> statusArgs = new HashMap<>();
    statusArgs.put("operation", "status");

    ToolResponse resp4 = tool.executeAsync(statusArgs).get();


    if (resp4 instanceof ToolResponse.Error error) {


      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


    }


    String resultJson = ((ToolResponse.Success) resp4).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("READY", result.get("state"));
    assertTrue((Boolean) result.get("active"));
    assertNotNull(result.get("config"));

    logger.info("Status operation active test passed");
  }

  /**
   * Tests threads operation.
   */
  @Test
  public void testThreadsOperation() throws Exception {
    logger.info("Testing threads operation...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // List threads
    Map<String, Object> threadsArgs = new HashMap<>();
    threadsArgs.put("operation", "threads");

    ToolResponse resp5 = tool.executeAsync(threadsArgs).get();


    if (resp5 instanceof ToolResponse.Error error) {


      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


    }


    String resultJson = ((ToolResponse.Success) resp5).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("thread_count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertNotNull(threads);
    assertFalse(threads.isEmpty(), "Should have at least one thread");

    // Verify thread structure
    Map<String, Object> firstThread = threads.get(0);
    assertNotNull(firstThread.get("id"));
    assertNotNull(firstThread.get("name"));
    assertNotNull(firstThread.get("state"));
    assertNotNull(firstThread.get("suspended"));

    logger.info("Threads operation test passed");
  }

  /**
   * Tests suspend operation.
   */
  @Test
  public void testSuspendOperation() throws Exception {
    logger.info("Testing suspend operation...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Get threads to find a thread ID
    Map<String, Object> threadsArgs = new HashMap<>();
    threadsArgs.put("operation", "threads");
    ToolResponse resp6 = tool.executeAsync(threadsArgs).get();

    if (resp6 instanceof ToolResponse.Error error) {

      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");

    }

    String threadsJson = ((ToolResponse.Success) resp6).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> threadsResult = objectMapper.readValue(threadsJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) threadsResult.get("threads");

    if (!threads.isEmpty()) {
      long threadId = ((Number) threads.get(0).get("id")).longValue();

      // Suspend thread
      Map<String, Object> suspendArgs = new HashMap<>();
      suspendArgs.put("operation", "suspend");
      suspendArgs.put("thread_id", threadId);

      ToolResponse resp7 = tool.executeAsync(suspendArgs).get();


      if (resp7 instanceof ToolResponse.Error error) {


        throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


      }


      String resultJson = ((ToolResponse.Success) resp7).content();

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(threadId, ((Number) result.get("thread_id")).longValue());
    }

    logger.info("Suspend operation test passed");
  }

  /**
   * Tests resume operation.
   */
  @Test
  public void testResumeOperation() throws Exception {
    logger.info("Testing resume operation...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Get threads
    Map<String, Object> threadsArgs = new HashMap<>();
    threadsArgs.put("operation", "threads");
    ToolResponse resp8 = tool.executeAsync(threadsArgs).get();

    if (resp8 instanceof ToolResponse.Error error) {

      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");

    }

    String threadsJson = ((ToolResponse.Success) resp8).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> threadsResult = objectMapper.readValue(threadsJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) threadsResult.get("threads");

    if (!threads.isEmpty()) {
      long threadId = ((Number) threads.get(0).get("id")).longValue();

      // Suspend first
      Map<String, Object> suspendArgs = new HashMap<>();
      suspendArgs.put("operation", "suspend");
      suspendArgs.put("thread_id", threadId);
      tool.executeAsync(suspendArgs).get();

      // Then resume
      Map<String, Object> resumeArgs = new HashMap<>();
      resumeArgs.put("operation", "resume");
      resumeArgs.put("thread_id", threadId);

      ToolResponse resp9 = tool.executeAsync(resumeArgs).get();


      if (resp9 instanceof ToolResponse.Error error) {


        throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


      }


      String resultJson = ((ToolResponse.Success) resp9).content();

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(threadId, ((Number) result.get("thread_id")).longValue());
    }

    logger.info("Resume operation test passed");
  }

  /**
   * Tests resumeAll operation.
   */
  @Test
  public void testResumeAllOperation() throws Exception {
    logger.info("Testing resumeAll operation...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Resume all
    Map<String, Object> resumeAllArgs = new HashMap<>();
    resumeAllArgs.put("operation", "resume_all");

    ToolResponse resp10 = tool.executeAsync(resumeAllArgs).get();


    if (resp10 instanceof ToolResponse.Error error) {


      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");


    }


    String resultJson = ((ToolResponse.Success) resp10).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("message"));

    logger.info("ResumeAll operation test passed");
  }

  /**
   * Tests missing operation parameter returns error.
   */
  @Test
  public void testMissingOperationParameter() throws Exception {
    logger.info("Testing missing operation parameter...");

    Map<String, Object> args = new HashMap<>();
    // No operation specified

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Operation is required"));

    logger.info("Missing operation parameter test passed");
  }

  /**
   * Tests unknown operation returns error.
   */
  @Test
  public void testUnknownOperation() throws Exception {
    logger.info("Testing unknown operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "invalid_operation");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Unknown operation"));

    logger.info("Unknown operation test passed");
  }

  /**
   * Tests suspend without thread_id returns error.
   */
  @Test
  public void testSuspendMissingThreadId() throws Exception {
    logger.info("Testing suspend missing thread_id...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Suspend without thread_id
    Map<String, Object> suspendArgs = new HashMap<>();
    suspendArgs.put("operation", "suspend");

    ToolResponse response = tool.executeAsync(suspendArgs).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id is required"));

    logger.info("Suspend missing thread_id test passed");
  }

  /**
   * Tests resume without thread_id returns error.
   */
  @Test
  public void testResumeMissingThreadId() throws Exception {
    logger.info("Testing resume missing thread_id...");

    // Start session
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Resume without thread_id
    Map<String, Object> resumeArgs = new HashMap<>();
    resumeArgs.put("operation", "resume");

    ToolResponse response = tool.executeAsync(resumeArgs).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id is required"));

    logger.info("Resume missing thread_id test passed");
  }

  /**
   * Tests cannot start session twice.
   */
  @Test
  public void testCannotStartSessionTwice() throws Exception {
    logger.info("Testing cannot start session twice...");

    // Start first time
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    tool.executeAsync(startArgs).get();

    // Try to start again
    ToolResponse response = tool.executeAsync(startArgs).get();

    assertTrue(response instanceof ToolResponse.Error);

    logger.info("Cannot start session twice test passed");
  }

  /**
   * Tests session lifecycle (start -> status -> stop).
   */
  @Test
  public void testSessionLifecycle() throws Exception {
    logger.info("Testing session lifecycle...");

    // Start
    Map<String, Object> startArgs = new HashMap<>();
    startArgs.put("operation", "start");
    ToolResponse resp11 = tool.executeAsync(startArgs).get();

    if (resp11 instanceof ToolResponse.Error error) {

      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");

    }

    String startJson = ((ToolResponse.Success) resp11).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> startResult = objectMapper.readValue(startJson, Map.class);
    assertEquals("READY", startResult.get("state"));

    // Status
    Map<String, Object> statusArgs = new HashMap<>();
    statusArgs.put("operation", "status");
    ToolResponse resp12 = tool.executeAsync(statusArgs).get();

    if (resp12 instanceof ToolResponse.Error error) {

      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");

    }

    String statusJson = ((ToolResponse.Success) resp12).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> statusResult = objectMapper.readValue(statusJson, Map.class);
    assertEquals("READY", statusResult.get("state"));
    assertTrue((Boolean) statusResult.get("active"));

    // Stop
    Map<String, Object> stopArgs = new HashMap<>();
    stopArgs.put("operation", "stop");
    ToolResponse resp13 = tool.executeAsync(stopArgs).get();

    if (resp13 instanceof ToolResponse.Error error) {

      throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");

    }

    String stopJson = ((ToolResponse.Success) resp13).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> stopResult = objectMapper.readValue(stopJson, Map.class);
    assertEquals("CLOSED", stopResult.get("state"));

    logger.info("Session lifecycle test passed");
  }
}
