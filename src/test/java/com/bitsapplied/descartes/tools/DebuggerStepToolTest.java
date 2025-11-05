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
 * Tests for DebuggerStepTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>stepOver operation</li>
 * <li>stepInto operation</li>
 * <li>stepOut operation</li>
 * <li>Error handling (thread not found, missing params, suspended
 * requirement)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerStepToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerStepToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerStepTool tool;
  private DebuggerService debuggerService;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    debuggee = DebuggeeLauncher.launchAndWait();
    logger.info("Debuggee launched on port {}", debuggee.getJdwpPort());

    // Reset circuit breaker to prevent failures from affecting subsequent tests
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // Keep this external launcher: dynamic JDWP enablement is unsupported (no
    // Agent_OnAttach),
    // so tests must attach to a JVM that already started with -agentlib:jdwp.
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    debuggerService = new DebuggerService(connectionManager);
    tool = new DebuggerStepTool(debuggerService);

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

    assertEquals("debugger_step", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("step") || description.contains("Step"));
    assertTrue(description.contains("suspended"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("thread_id"));

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

    assertTrue(operations.contains("stepOver"));
    assertTrue(operations.contains("stepInto"));
    assertTrue(operations.contains("stepOut"));
    assertEquals(3, operations.size());

    logger.info("Schema operations test passed");
  }

  /**
   * Tests stepOver operation requires suspended thread.
   */
  @Test
  public void testStepOverRequiresSuspendedThread() throws Exception {
    logger.info("Testing stepOver requires suspended thread...");

    // Get a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "stepOver");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // Should fail because thread is not suspended
      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().toLowerCase().contains("suspend") || error.message().contains("not suspended"));
    }

    logger.info("StepOver requires suspended thread test passed");
  }

  /**
   * Tests stepOver with missing thread_id.
   */
  @Test
  public void testStepOverMissingThreadId() throws Exception {
    logger.info("Testing stepOver missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stepOver");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("StepOver missing thread_id test passed");
  }

  /**
   * Tests stepOver with thread not found.
   */
  @Test
  public void testStepOverThreadNotFound() throws Exception {
    logger.info("Testing stepOver thread not found...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stepOver");
    args.put("thread_id", 999999L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("StepOver thread not found test passed");
  }

  /**
   * Tests stepInto operation requires suspended thread.
   */
  @Test
  public void testStepIntoRequiresSuspendedThread() throws Exception {
    logger.info("Testing stepInto requires suspended thread...");

    // Get a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "stepInto");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // Should fail because thread is not suspended
      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().toLowerCase().contains("suspend") || error.message().contains("not suspended"));
    }

    logger.info("StepInto requires suspended thread test passed");
  }

  /**
   * Tests stepInto with missing thread_id.
   */
  @Test
  public void testStepIntoMissingThreadId() throws Exception {
    logger.info("Testing stepInto missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stepInto");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("StepInto missing thread_id test passed");
  }

  /**
   * Tests stepInto with thread not found.
   */
  @Test
  public void testStepIntoThreadNotFound() throws Exception {
    logger.info("Testing stepInto thread not found...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stepInto");
    args.put("thread_id", 999999L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("StepInto thread not found test passed");
  }

  /**
   * Tests stepOut operation requires suspended thread.
   */
  @Test
  public void testStepOutRequiresSuspendedThread() throws Exception {
    logger.info("Testing stepOut requires suspended thread...");

    // Get a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "stepOut");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // Should fail because thread is not suspended
      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().toLowerCase().contains("suspend") || error.message().contains("not suspended"));
    }

    logger.info("StepOut requires suspended thread test passed");
  }

  /**
   * Tests stepOut with missing thread_id.
   */
  @Test
  public void testStepOutMissingThreadId() throws Exception {
    logger.info("Testing stepOut missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stepOut");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("StepOut missing thread_id test passed");
  }

  /**
   * Tests stepOut with thread not found.
   */
  @Test
  public void testStepOutThreadNotFound() throws Exception {
    logger.info("Testing stepOut thread not found...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stepOut");
    args.put("thread_id", 999999L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("StepOut thread not found test passed");
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
      args1.put("operation", "stepOver");
      args1.put("thread_id", threadId); // Number
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "stepOver");
      args2.put("thread_id", String.valueOf(threadId)); // String
      ToolResponse response2 = tool.executeAsync(args2).get();
      assertNotNull(response2);
    }

    logger.info("Thread_id type coercion test passed");
  }

  /**
   * Tests all step operations with same thread (validates consistent behavior).
   */
  @Test
  public void testAllStepOperations() throws Exception {
    logger.info("Testing all step operations...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      // Test stepOver
      Map<String, Object> stepOverArgs = new HashMap<>();
      stepOverArgs.put("operation", "stepOver");
      stepOverArgs.put("thread_id", threadId);
      ToolResponse stepOverResponse = tool.executeAsync(stepOverArgs).get();
      assertNotNull(stepOverResponse);

      // Test stepInto
      Map<String, Object> stepIntoArgs = new HashMap<>();
      stepIntoArgs.put("operation", "stepInto");
      stepIntoArgs.put("thread_id", threadId);
      ToolResponse stepIntoResponse = tool.executeAsync(stepIntoArgs).get();
      assertNotNull(stepIntoResponse);

      // Test stepOut
      Map<String, Object> stepOutArgs = new HashMap<>();
      stepOutArgs.put("operation", "stepOut");
      stepOutArgs.put("thread_id", threadId);
      ToolResponse stepOutResponse = tool.executeAsync(stepOutArgs).get();
      assertNotNull(stepOutResponse);

      // All should fail with same error (thread not suspended)
      assertTrue(stepOverResponse instanceof ToolResponse.Error);
      assertTrue(stepIntoResponse instanceof ToolResponse.Error);
      assertTrue(stepOutResponse instanceof ToolResponse.Error);
    }

    logger.info("All step operations test passed");
  }
}
