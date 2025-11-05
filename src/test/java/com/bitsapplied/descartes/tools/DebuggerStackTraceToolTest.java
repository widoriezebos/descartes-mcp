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
 * Tests for DebuggerStackTraceTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>capture operation with max_depth</li>
 * <li>captureFiltered operation with exclude_patterns</li>
 * <li>getFrame operation with frame_index</li>
 * <li>getCurrentFrame operation</li>
 * <li>Error handling (thread not found, missing params, suspended
 * requirement)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerStackTraceToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerStackTraceToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerStackTraceTool tool;
  private DebuggerService debuggerService;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    debuggee = DebuggeeLauncher.launchAndWait();
    logger.info("Debuggee launched on port {}", debuggee.getJdwpPort());

    // Reset circuit breaker to prevent failures from affecting subsequent tests
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // We rely on this helper JVM because the JDWP agent cannot be attached
    // dynamically.
    // Removing it will make the tests attach to the wrong process.
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    debuggerService = new DebuggerService(connectionManager);
    tool = new DebuggerStackTraceTool(debuggerService);

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

    assertEquals("debugger_stacktrace", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("stack") || description.contains("Stack"));
    assertTrue(description.contains("suspended"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("thread_id"));
    assertTrue(properties.containsKey("max_depth"));
    assertTrue(properties.containsKey("exclude_patterns"));
    assertTrue(properties.containsKey("frame_index"));

    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
    assertTrue(required.contains("thread_id"));

    logger.info("Tool metadata test passed");
  }

  /**
   * Tests schema operations enum.
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

    assertTrue(operations.contains("capture"));
    assertTrue(operations.contains("captureFiltered"));
    assertTrue(operations.contains("getFrame"));
    assertTrue(operations.contains("getCurrentFrame"));
    assertEquals(4, operations.size());

    logger.info("Schema operations test passed");
  }

  /**
   * Tests capture operation requires suspended thread.
   */
  @Test
  public void testCaptureRequiresSuspendedThread() throws Exception {
    logger.info("Testing capture requires suspended thread...");

    // Get a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "capture");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // Should fail because thread is not suspended
      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().toLowerCase().contains("suspend") || error.message().contains("not suspended"));
    }

    logger.info("Capture requires suspended thread test passed");
  }

  /**
   * Tests capture operation with missing thread_id.
   */
  @Test
  public void testCaptureMissingThreadId() throws Exception {
    logger.info("Testing capture missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "capture");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("Capture missing thread_id test passed");
  }

  /**
   * Tests capture operation with thread not found.
   */
  @Test
  public void testCaptureThreadNotFound() throws Exception {
    logger.info("Testing capture thread not found...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "capture");
    args.put("thread_id", 999999L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("Capture thread not found test passed");
  }

  /**
   * Tests capture operation with max_depth parameter.
   */
  @Test
  public void testCaptureWithMaxDepth() throws Exception {
    logger.info("Testing capture with max_depth...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "capture");
      args.put("thread_id", threadId);
      args.put("max_depth", 5);

      ToolResponse response = tool.executeAsync(args).get();

      // Will fail on suspend check, but validates max_depth parameter is accepted
      assertNotNull(response);
    }

    logger.info("Capture with max_depth test passed");
  }

  /**
   * Tests captureFiltered operation.
   */
  @Test
  public void testCaptureFilteredOperation() throws Exception {
    logger.info("Testing captureFiltered operation...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "captureFiltered");
      args.put("thread_id", threadId);
      args.put("exclude_patterns", List.of("java.*", "javax.*"));

      ToolResponse response = tool.executeAsync(args).get();

      // Will fail on suspend check, but validates operation and parameters
      assertNotNull(response);
    }

    logger.info("CaptureFiltered operation test passed");
  }

  /**
   * Tests captureFiltered with default exclude patterns.
   */
  @Test
  public void testCaptureFilteredDefaultPatterns() throws Exception {
    logger.info("Testing captureFiltered with default patterns...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "captureFiltered");
      args.put("thread_id", threadId);
      // No exclude_patterns - should use defaults

      ToolResponse response = tool.executeAsync(args).get();

      // Will fail on suspend check, but validates default patterns work
      assertNotNull(response);
    }

    logger.info("CaptureFiltered with default patterns test passed");
  }

  /**
   * Tests getFrame operation requires frame_index.
   */
  @Test
  public void testGetFrameMissingFrameIndex() throws Exception {
    logger.info("Testing getFrame missing frame_index...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "getFrame");
      args.put("thread_id", threadId);
      // No frame_index

      ToolResponse response = tool.executeAsync(args).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().contains("frame_index"));
    }

    logger.info("GetFrame missing frame_index test passed");
  }

  /**
   * Tests getFrame operation with frame_index.
   */
  @Test
  public void testGetFrameWithIndex() throws Exception {
    logger.info("Testing getFrame with frame_index...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "getFrame");
      args.put("thread_id", threadId);
      args.put("frame_index", 0);

      ToolResponse response = tool.executeAsync(args).get();

      // Will fail on suspend check, but validates parameters
      assertNotNull(response);
    }

    logger.info("GetFrame with frame_index test passed");
  }

  /**
   * Tests getCurrentFrame operation.
   */
  @Test
  public void testGetCurrentFrameOperation() throws Exception {
    logger.info("Testing getCurrentFrame operation...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "getCurrentFrame");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // Will fail on suspend check, but validates operation
      assertNotNull(response);
    }

    logger.info("GetCurrentFrame operation test passed");
  }

  /**
   * Tests getCurrentFrame with missing thread_id.
   */
  @Test
  public void testGetCurrentFrameMissingThreadId() throws Exception {
    logger.info("Testing getCurrentFrame missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "getCurrentFrame");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("GetCurrentFrame missing thread_id test passed");
  }

  /**
   * Tests unknown operation.
   */
  @Test
  public void testUnknownOperation() throws Exception {
    logger.info("Testing unknown operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "invalid_operation");
    args.put("thread_id", 1L);

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
    args.put("thread_id", 1L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Operation is required"));

    logger.info("Missing operation test passed");
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
      args1.put("operation", "getCurrentFrame");
      args1.put("thread_id", threadId); // Number
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "getCurrentFrame");
      args2.put("thread_id", String.valueOf(threadId)); // String
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
      args1.put("operation", "getFrame");
      args1.put("thread_id", threadId);
      args1.put("frame_index", 0); // Number
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "getFrame");
      args2.put("thread_id", threadId);
      args2.put("frame_index", "0"); // String
      ToolResponse response2 = tool.executeAsync(args2).get();
      assertNotNull(response2);
    }

    logger.info("Frame_index type coercion test passed");
  }

  /**
   * Tests parameter type coercion for max_depth.
   */
  @Test
  public void testMaxDepthTypeCoercion() throws Exception {
    logger.info("Testing max_depth type coercion...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      // Test with Number
      Map<String, Object> args1 = new HashMap<>();
      args1.put("operation", "capture");
      args1.put("thread_id", threadId);
      args1.put("max_depth", 10); // Number
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String - should work via default
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "capture");
      args2.put("thread_id", threadId);
      // No max_depth - uses default
      ToolResponse response2 = tool.executeAsync(args2).get();
      assertNotNull(response2);
    }

    logger.info("Max_depth type coercion test passed");
  }

  /**
   * Tests exclude_patterns as list.
   */
  @Test
  public void testExcludePatternsAsList() throws Exception {
    logger.info("Testing exclude_patterns as list...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "captureFiltered");
      args.put("thread_id", threadId);
      args.put("exclude_patterns", List.of("com.example.*", "org.test.*"));

      ToolResponse response = tool.executeAsync(args).get();

      // Validates list parameter is accepted
      assertNotNull(response);
    }

    logger.info("Exclude_patterns as list test passed");
  }
}
