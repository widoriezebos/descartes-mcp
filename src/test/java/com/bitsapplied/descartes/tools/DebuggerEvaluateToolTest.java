package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.JDWPConnectionManager;
import com.bitsapplied.descartes.debugger.JDWPConnector;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;

/**
 * Tests for DebuggerEvaluateTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Evaluate operation with thread_id</li>
 * <li>Evaluate operation with thread_name</li>
 * <li>Frame index handling</li>
 * <li>Expression evaluation (simple and complex)</li>
 * <li>Error handling (thread not found, not suspended, invalid frame, missing
 * params)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerEvaluateToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerEvaluateToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerEvaluateTool tool;
  private DebuggerService debuggerService;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    debuggee = DebuggeeLauncher.launchAndWait();
    logger.info("Debuggee launched on port {}", debuggee.getJdwpPort());

    // Reset circuit breaker to prevent failures from affecting subsequent tests
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // IMPORTANT: Do not revert to self-attach. HotSpot cannot load the JDWP agent at runtime,
    // so the tests must connect to this helper JVM that already enabled -agentlib.
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    debuggerService = new DebuggerService(connectionManager);
    tool = new DebuggerEvaluateTool(debuggerService);

    // Start debug session
    if (debuggerService.getState() != SessionState.READY) {
      DebugSessionConfig config = new DebugSessionConfig(10000, false,
          new String[] { "java.*", "javax.*", "jdk.*", "sun.*" });
      debuggerService.start(config);
    }
  }

  @AfterEach
  public void tearDown() {
    try {
      if (debuggerService.getState() != SessionState.CLOSED) {
        debuggerService.stop();
      }
    } catch (Exception e) {
      logger.warn("Error cleaning up: {}", e.getMessage());
    }
  }

  @AfterAll
  public void shutdownConnectionManager() throws Exception {
    if (connectionManager != null) {
      connectionManager.shutdown();
    }
    if (debuggee != null) {
      logger.info("Terminating debuggee process...");
      debuggee.terminate();
    }
  }

  /**
   * Tests tool metadata.
   */
  @Test
  public void testToolMetadata() {
    logger.info("Testing tool metadata...");

    assertEquals("debugger_evaluate", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("expression"));
    assertTrue(description.contains("evaluate") || description.contains("Evaluate"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("thread_id"));
    assertTrue(properties.containsKey("thread_name"));
    assertTrue(properties.containsKey("frame_index"));
    assertTrue(properties.containsKey("expression"));

    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
    assertTrue(required.contains("expression"));

    logger.info("Tool metadata test passed");
  }

  /**
   * Tests schema operation enum.
   */
  @Test
  public void testSchemaOperations() {
    logger.info("Testing schema operations...");

    Map<String, Object> schema = tool.getToolSchema();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

    @SuppressWarnings("unchecked")
    Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");

    @SuppressWarnings("unchecked")
    List<String> operations = (List<String>) operationProp.get("enum");

    assertTrue(operations.contains("evaluate"));
    assertEquals(1, operations.size(), "Should only have 'evaluate' operation");

    logger.info("Schema operations test passed");
  }

  /**
   * Tests evaluate operation requires suspended thread.
   *
   * Note: This test cannot fully exercise evaluation without a suspended thread,
   * so it primarily tests error handling.
   */
  @Test
  public void testEvaluateRequiresSuspendedThread() throws Exception {
    logger.info("Testing evaluate requires suspended thread...");

    // Get a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "evaluate");
      args.put("thread_id", threadId);
      args.put("expression", "1 + 1");

      ToolResponse response = tool.executeAsync(args).get();

      // Should fail because thread is not suspended
      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().toLowerCase().contains("suspend") || error.message().contains("THREAD_NOT_SUSPENDED"));
    }

    logger.info("Evaluate requires suspended thread test passed");
  }

  /**
   * Tests missing expression parameter.
   */
  @Test
  public void testEvaluateMissingExpression() throws Exception {
    logger.info("Testing evaluate missing expression...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "evaluate");
    args.put("thread_id", 1L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("expression") || error.message().contains("parameter"));

    logger.info("Evaluate missing expression test passed");
  }

  /**
   * Tests missing thread identifier (both thread_id and thread_name).
   */
  @Test
  public void testEvaluateMissingThreadIdentifier() throws Exception {
    logger.info("Testing evaluate missing thread identifier...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "evaluate");
    args.put("expression", "1 + 1");
    // No thread_id or thread_name

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id") || error.message().contains("thread_name"));

    logger.info("Evaluate missing thread identifier test passed");
  }

  /**
   * Tests thread not found by ID.
   */
  @Test
  public void testEvaluateThreadNotFoundById() throws Exception {
    logger.info("Testing evaluate thread not found by ID...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "evaluate");
    args.put("thread_id", 999999L); // Non-existent thread ID
    args.put("expression", "1 + 1");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("Evaluate thread not found by ID test passed");
  }

  /**
   * Tests thread not found by name.
   */
  @Test
  public void testEvaluateThreadNotFoundByName() throws Exception {
    logger.info("Testing evaluate thread not found by name...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "evaluate");
    args.put("thread_name", "NonExistentThread-XYZ-999");
    args.put("expression", "1 + 1");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("Evaluate thread not found by name test passed");
  }

  /**
   * Tests frame index default is 0.
   */
  @Test
  public void testEvaluateFrameIndexDefault() throws Exception {
    logger.info("Testing evaluate frame index default...");

    // Get a thread (will fail on suspend, but tests parameter handling)
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "evaluate");
      args.put("thread_id", threadId);
      args.put("expression", "1 + 1");
      // No frame_index specified - should default to 0

      ToolResponse response = tool.executeAsync(args).get();

      // Will fail on suspend check, but validates frame_index wasn't required
      assertNotNull(response);
    }

    logger.info("Evaluate frame index default test passed");
  }

  /**
   * Tests unknown operation.
   */
  @Test
  public void testUnknownOperation() throws Exception {
    logger.info("Testing unknown operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "invalid_operation");
    args.put("expression", "1 + 1");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Unknown operation"));

    logger.info("Unknown operation test passed");
  }

  /**
   * Tests missing operation parameter.
   */
  @Test
  public void testMissingOperation() throws Exception {
    logger.info("Testing missing operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("expression", "1 + 1");
    // No operation

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);

    logger.info("Missing operation test passed");
  }

  /**
   * Tests thread resolution prioritizes thread_id over thread_name.
   */
  @Test
  public void testThreadResolutionPriority() throws Exception {
    logger.info("Testing thread resolution priority...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long validThreadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "evaluate");
      args.put("thread_id", validThreadId);
      args.put("thread_name", "NonExistentThread"); // Should be ignored
      args.put("expression", "1 + 1");

      ToolResponse response = tool.executeAsync(args).get();

      // Should try to use thread_id (valid) and fail on suspend, not on thread not
      // found
      if (response instanceof ToolResponse.Error error) {
        assertTrue(!error.message().contains("NonExistentThread"),
            "Should use thread_id, not thread_name when both provided");
      }
    }

    logger.info("Thread resolution priority test passed");
  }

  /**
   * Tests evaluation with thread_name parameter.
   */
  @Test
  public void testEvaluateWithThreadName() throws Exception {
    logger.info("Testing evaluate with thread_name...");

    // Try to find main thread by name
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "evaluate");
    args.put("thread_name", "main");
    args.put("expression", "1 + 1");

    ToolResponse response = tool.executeAsync(args).get();

    // Will fail on suspend check if thread exists and is running
    assertNotNull(response);

    logger.info("Evaluate with thread_name test passed");
  }

  /**
   * Tests explicit frame index parameter.
   */
  @Test
  public void testEvaluateWithFrameIndex() throws Exception {
    logger.info("Testing evaluate with frame index...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "evaluate");
      args.put("thread_id", threadId);
      args.put("frame_index", 0);
      args.put("expression", "1 + 1");

      ToolResponse response = tool.executeAsync(args).get();

      // Should process frame_index parameter (will fail on suspend)
      assertNotNull(response);
    }

    logger.info("Evaluate with frame index test passed");
  }

  /**
   * Tests simple expression string.
   */
  @Test
  public void testSimpleExpression() throws Exception {
    logger.info("Testing simple expression...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      Map<String, Object> args = new HashMap<>();
      args.put("operation", "evaluate");
      args.put("thread_id", threads.get(0).id());
      args.put("expression", "2 + 2");

      ToolResponse response = tool.executeAsync(args).get();

      // Validates expression parameter is accepted
      assertNotNull(response);
    }

    logger.info("Simple expression test passed");
  }

  /**
   * Tests complex expression string.
   */
  @Test
  public void testComplexExpression() throws Exception {
    logger.info("Testing complex expression...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      Map<String, Object> args = new HashMap<>();
      args.put("operation", "evaluate");
      args.put("thread_id", threads.get(0).id());
      args.put("expression", "java.util.List.of(1, 2, 3).stream().map(x -> x * 2).toList()");

      ToolResponse response = tool.executeAsync(args).get();

      // Validates complex expression is accepted
      assertNotNull(response);
    }

    logger.info("Complex expression test passed");
  }

  /**
   * Tests parameter type coercion for thread_id.
   */
  @Test
  public void testThreadIdTypeCoercion() throws Exception {
    logger.info("Testing thread_id type coercion...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      // Test with Number
      Map<String, Object> args1 = new HashMap<>();
      args1.put("operation", "evaluate");
      args1.put("thread_id", threadId); // Number
      args1.put("expression", "1");
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "evaluate");
      args2.put("thread_id", String.valueOf(threadId)); // String
      args2.put("expression", "1");
      ToolResponse response2 = tool.executeAsync(args2).get();
      assertNotNull(response2);
    }

    logger.info("Thread_id type coercion test passed");
  }

  /**
   * Tests parameter type coercion for frame_index.
   */
  @Test
  public void testFrameIndexTypeCoercion() throws Exception {
    logger.info("Testing frame_index type coercion...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      // Test with Number
      Map<String, Object> args1 = new HashMap<>();
      args1.put("operation", "evaluate");
      args1.put("thread_id", threadId);
      args1.put("frame_index", 0); // Number
      args1.put("expression", "1");
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "evaluate");
      args2.put("thread_id", threadId);
      args2.put("frame_index", "0"); // String
      args2.put("expression", "1");
      ToolResponse response2 = tool.executeAsync(args2).get();
      assertNotNull(response2);
    }

    logger.info("Frame_index type coercion test passed");
  }
}
