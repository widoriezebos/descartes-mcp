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
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.JDWPConnectionManager;
import com.bitsapplied.descartes.debugger.JDWPConnector;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;

/**
 * Tests for DebuggerVariablesTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>getVariables operation</li>
 * <li>getChildVariables operation</li>
 * <li>getStaticFields operation</li>
 * <li>Error handling (thread not found, not suspended, invalid frame, missing
 * params)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerVariablesToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerVariablesToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerVariablesTool tool;
  private DebuggerService debuggerService;
  private DebuggerExecutor debuggerExecutor;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    debuggee = DebuggeeLauncher.launchAndWait();
    logger.info("Debuggee launched on port {}", debuggee.getJdwpPort());

    logger.info("Setting up JDWP connection manager (connection reuse mode)");
    // Reset circuit breaker to prevent failures from affecting subsequent tests
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // Future maintainers: keep the external launcher. Dynamic JDWP enablement is
    // not supported (no Agent_OnAttach), so we must target a JVM that already
    // loaded -agentlib.
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    // Create fresh DebuggerService instance that shares the connection
    debuggerService = new DebuggerService(connectionManager);
    debuggerExecutor = new DebuggerExecutor();
    tool = new DebuggerVariablesTool(debuggerService, debuggerExecutor);

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
      // Stop session (resets state, doesn't dispose connection)
      if (debuggerService.getState() != SessionState.CLOSED) {
        debuggerService.stop();
      }
    } catch (Exception e) {
      logger.warn("Error cleaning up: {}", e.getMessage());
    }
  }

  @AfterAll
  public void shutdownConnectionManager() throws Exception {
    logger.info("Shutting down JDWP connection manager");
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

    assertEquals("debugger_variables", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("variable") || description.contains("Variable"));
    assertTrue(description.contains("inspect"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("thread_id"));
    assertTrue(properties.containsKey("frame_index"));
    assertTrue(properties.containsKey("variable_reference"));
    assertTrue(properties.containsKey("class_name"));

    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));

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

    assertTrue(operations.contains("get_variables"));
    assertTrue(operations.contains("get_child_variables"));
    assertTrue(operations.contains("get_static_fields"));
    assertEquals(3, operations.size());

    logger.info("Schema operations test passed");
  }

  /**
   * Tests getVariables missing thread_id.
   */
  @Test
  public void testGetVariablesMissingThreadId() throws Exception {
    logger.info("Testing getVariables missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_variables");
    args.put("frame_index", 0);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("GetVariables missing thread_id test passed");
  }

  /**
   * Tests getVariables missing frame_index.
   */
  @Test
  public void testGetVariablesMissingFrameIndex() throws Exception {
    logger.info("Testing getVariables missing frame_index...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_variables");
    args.put("thread_id", 1L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("frame_index"));

    logger.info("GetVariables missing frame_index test passed");
  }

  /**
   * Tests getVariables with thread not found.
   */
  @Test
  public void testGetVariablesThreadNotFound() throws Exception {
    logger.info("Testing getVariables thread not found...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_variables");
    args.put("thread_id", 999999L);
    args.put("frame_index", 0);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("GetVariables thread not found test passed");
  }

  /**
   * Tests getVariables requires suspended thread.
   */
  @Test
  public void testGetVariablesRequiresSuspendedThread() throws Exception {
    logger.info("Testing getVariables requires suspended thread...");

    // Get a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_variables");
      args.put("thread_id", threadId);
      args.put("frame_index", 0);

      ToolResponse response = tool.executeAsync(args).get();

      // Should fail because thread is not suspended
      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertTrue(error.message().toLowerCase().contains("suspend"));
    }

    logger.info("GetVariables requires suspended thread test passed");
  }

  /**
   * Tests getChildVariables missing variable_reference.
   */
  @Test
  public void testGetChildVariablesMissingReference() throws Exception {
    logger.info("Testing getChildVariables missing reference...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_child_variables");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("variable_reference") || error.message().contains("reference"));

    logger.info("GetChildVariables missing reference test passed");
  }

  /**
   * Tests getChildVariables with invalid reference.
   */
  @Test
  public void testGetChildVariablesInvalidReference() throws Exception {
    logger.info("Testing getChildVariables invalid reference...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_child_variables");
    args.put("variable_reference", 999999);

    ToolResponse response = tool.executeAsync(args).get();
    logger.info("Response type: {}", response.getClass().getSimpleName());
    logger.info("Response: {}", response);

    assertTrue(response instanceof ToolResponse.Error,
        "Expected Error response but got: " + response.getClass().getSimpleName() + " - " + response);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found") || error.message().contains("invalid"));

    logger.info("GetChildVariables invalid reference test passed");
  }

  /**
   * Tests getStaticFields missing class_name.
   */
  @Test
  public void testGetStaticFieldsMissingClassName() throws Exception {
    logger.info("Testing getStaticFields missing class_name...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_static_fields");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("class_name"));

    logger.info("GetStaticFields missing class_name test passed");
  }

  /**
   * Tests getStaticFields with non-existent class.
   */
  @Test
  public void testGetStaticFieldsNonExistentClass() throws Exception {
    logger.info("Testing getStaticFields non-existent class...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "get_static_fields");
    args.put("class_name", "com.example.NonExistentClass999");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found") || error.message().contains("does not exist"));

    logger.info("GetStaticFields non-existent class test passed");
  }

  /**
   * Tests unknown operation.
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
   * Tests missing operation parameter.
   */
  @Test
  public void testMissingOperation() throws Exception {
    logger.info("Testing missing operation...");

    Map<String, Object> args = new HashMap<>();

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

    // Test with Number
    Map<String, Object> args1 = new HashMap<>();
    args1.put("operation", "get_variables");
    args1.put("thread_id", 1L); // Number
    args1.put("frame_index", 0);
    ToolResponse response1 = tool.executeAsync(args1).get();
    assertNotNull(response1);

    // Test with String
    Map<String, Object> args2 = new HashMap<>();
    args2.put("operation", "get_variables");
    args2.put("thread_id", "1"); // String
    args2.put("frame_index", 0);
    ToolResponse response2 = tool.executeAsync(args2).get();
    assertNotNull(response2);

    logger.info("Thread_id type coercion test passed");
  }

  /**
   * Tests parameter type coercion for frame_index.
   */
  @Test
  public void testFrameIndexTypeCoercion() throws Exception {
    logger.info("Testing frame_index type coercion...");

    // Test with Number
    Map<String, Object> args1 = new HashMap<>();
    args1.put("operation", "get_variables");
    args1.put("thread_id", 1L);
    args1.put("frame_index", 0); // Number
    ToolResponse response1 = tool.executeAsync(args1).get();
    assertNotNull(response1);

    // Test with String
    Map<String, Object> args2 = new HashMap<>();
    args2.put("operation", "get_variables");
    args2.put("thread_id", 1L);
    args2.put("frame_index", "0"); // String
    ToolResponse response2 = tool.executeAsync(args2).get();
    assertNotNull(response2);

    logger.info("Frame_index type coercion test passed");
  }

  /**
   * Tests parameter type coercion for variable_reference.
   */
  @Test
  public void testVariableReferenceTypeCoercion() throws Exception {
    logger.info("Testing variable_reference type coercion...");

    // Test with Number
    Map<String, Object> args1 = new HashMap<>();
    args1.put("operation", "get_child_variables");
    args1.put("variable_reference", 1); // Number
    ToolResponse response1 = tool.executeAsync(args1).get();
    assertNotNull(response1);

    // Test with String
    Map<String, Object> args2 = new HashMap<>();
    args2.put("operation", "get_child_variables");
    args2.put("variable_reference", "1"); // String
    ToolResponse response2 = tool.executeAsync(args2).get();
    assertNotNull(response2);

    logger.info("Variable_reference type coercion test passed");
  }

  /**
   * Tests getVariables with invalid frame index.
   */
  @Test
  public void testGetVariablesInvalidFrameIndex() throws Exception {
    logger.info("Testing getVariables invalid frame index...");

    List<ThreadInfo> threads = debuggerService.getThreads();

    // Find a thread that is NOT the current test thread to avoid deadlock
    String currentThreadName = Thread.currentThread().getName();
    ThreadInfo targetThread = threads.stream().filter(t -> !t.name().equals(currentThreadName)).findFirst()
        .orElse(null);

    if (targetThread != null) {
      long threadId = targetThread.id();

      // Suspend the thread first
      try {
        debuggerService.suspendThread(threadId);

        // Try with very large frame index
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "get_variables");
        args.put("thread_id", threadId);
        args.put("frame_index", 999999);

        ToolResponse response = tool.executeAsync(args).get();

        assertTrue(response instanceof ToolResponse.Error);
        ToolResponse.Error error = (ToolResponse.Error) response;
        assertTrue(error.message().contains("Invalid frame") || error.message().contains("frame index"));

      } finally {
        // Resume the thread
        debuggerService.resumeThread(threadId);
      }
    }

    logger.info("GetVariables invalid frame index test passed");
  }
}
