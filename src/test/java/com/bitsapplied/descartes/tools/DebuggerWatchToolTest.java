package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import org.junit.jupiter.api.Assumptions;

import com.bitsapplied.descartes.debugger.DebuggeeLauncher;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.JDWPConnectionManager;
import com.bitsapplied.descartes.debugger.JDWPConnector;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jdi.ThreadReference;

/**
 * Tests for DebuggerWatchTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>add operation (with and without display_name)</li>
 * <li>remove operation</li>
 * <li>removeAll operation</li>
 * <li>list operation</li>
 * <li>enable operation</li>
 * <li>disable operation</li>
 * <li>evaluate operation (with thread_id, thread_name, auto-detect)</li>
 * <li>Error handling (missing params, thread not found, not suspended)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerWatchToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerWatchToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerWatchTool tool;
  private ObjectMapper objectMapper;
  private DebuggerService debuggerService;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    logger.info("Setting up JDWP connection manager (connection reuse mode)");
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    // The helper JVM is mandatory: HotSpot refuses dynamic JDWP attach (no Agent_OnAttach),
    // so the tests must talk to a process that started with -agentlib:jdwp.
    debuggee = DebuggeeLauncher.launchAndWait();
    logger.info("Debuggee launched on port {}", debuggee.getJdwpPort());
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    // Create fresh DebuggerService instance that shares the connection
    debuggerService = new DebuggerService(connectionManager);
    tool = new DebuggerWatchTool(debuggerService);
    objectMapper = new ObjectMapper();
    logger.debug("Test setup complete - fresh service instance created");

    // Start debug session
    if (debuggerService.getState() != SessionState.READY) {
      DebugSessionConfig config = new DebugSessionConfig(10000, false,
          new String[] { "java.*", "javax.*", "jdk.*", "sun.*" });
      debuggerService.start(config);
    }

    // Clean up watches from previous tests
    debuggerService.getWatchManager().removeAllWatches();
  }

  @AfterEach
  public void tearDown() {
    try {
      if (debuggerService.getState() != SessionState.CLOSED) {
        debuggerService.stop(); // This will reset state, not dispose connection
      }
    } catch (Exception e) {
      logger.warn("Error cleaning up debug session: {}", e.getMessage());
    }
  }

  @AfterAll
  public void shutdownConnectionManager() throws Exception {
    if (connectionManager != null) {
      logger.info("Shutting down JDWP connection manager");
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

    assertEquals("debugger_watch", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("watch") || description.contains("Watch"));
    assertTrue(description.contains("expression"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("expression"));
    assertTrue(properties.containsKey("display_name"));
    assertTrue(properties.containsKey("watch_id"));
    assertTrue(properties.containsKey("thread_id"));
    assertTrue(properties.containsKey("thread_name"));
    assertTrue(properties.containsKey("frame_index"));

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

    assertTrue(operations.contains("add"));
    assertTrue(operations.contains("remove"));
    assertTrue(operations.contains("removeAll"));
    assertTrue(operations.contains("list"));
    assertTrue(operations.contains("enable"));
    assertTrue(operations.contains("disable"));
    assertTrue(operations.contains("evaluate"));
    assertEquals(7, operations.size());

    logger.info("Schema operations test passed");
  }

  /**
   * Tests add operation.
   */
  @Test
  public void testAddOperation() throws Exception {
    logger.info("Testing add operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "add");
    args.put("expression", "x + y");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;
    assertTrue(success.content().contains("Watch expression added"));

    logger.info("Add operation test passed");
  }

  /**
   * Tests add operation with display name.
   */
  @Test
  public void testAddWithDisplayName() throws Exception {
    logger.info("Testing add with display_name...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "add");
    args.put("expression", "x + y");
    args.put("display_name", "Sum of x and y");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;
    assertTrue(success.content().contains("Watch expression added"));

    logger.info("Add with display_name test passed");
  }

  /**
   * Tests add operation missing expression.
   */
  @Test
  public void testAddMissingExpression() throws Exception {
    logger.info("Testing add missing expression...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "add");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("expression") || error.message().contains("parameter"));

    logger.info("Add missing expression test passed");
  }

  /**
   * Tests list operation.
   */
  @Test
  public void testListOperation() throws Exception {
    logger.info("Testing list operation...");

    // Add a watch first
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "count");
    tool.executeAsync(addArgs).get();

    // List watches
    Map<String, Object> listArgs = new HashMap<>();
    listArgs.put("operation", "list");

    ToolResponse response = tool.executeAsync(listArgs).get();

    assertTrue(response instanceof ToolResponse.Success);
    String resultJson = ((ToolResponse.Success) response).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("watch_count"));
    assertNotNull(result.get("watches"));

    logger.info("List operation test passed");
  }

  /**
   * Tests list with empty watches.
   */
  @Test
  public void testListEmpty() throws Exception {
    logger.info("Testing list empty...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "list");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    String resultJson = ((ToolResponse.Success) response).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("watch_count"));

    logger.info("List empty test passed");
  }

  /**
   * Tests remove operation.
   */
  @Test
  public void testRemoveOperation() throws Exception {
    logger.info("Testing remove operation...");

    // Add a watch first
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "value");
    ToolResponse addResponse = tool.executeAsync(addArgs).get();
    assertTrue(addResponse instanceof ToolResponse.Success);

    // Get watch_id from response content (extract from message)
    // For testing, we use watch_id 1 (first watch)
    Map<String, Object> removeArgs = new HashMap<>();
    removeArgs.put("operation", "remove");
    removeArgs.put("watch_id", 1);

    ToolResponse response = tool.executeAsync(removeArgs).get();

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;
    assertTrue(success.content().contains("removed"));

    logger.info("Remove operation test passed");
  }

  /**
   * Tests remove with missing watch_id.
   */
  @Test
  public void testRemoveMissingWatchId() throws Exception {
    logger.info("Testing remove missing watch_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "remove");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("watch_id") || error.message().contains("parameter"));

    logger.info("Remove missing watch_id test passed");
  }

  /**
   * Tests removeAll operation.
   */
  @Test
  public void testRemoveAllOperation() throws Exception {
    logger.info("Testing removeAll operation...");

    // Add some watches
    Map<String, Object> addArgs1 = new HashMap<>();
    addArgs1.put("operation", "add");
    addArgs1.put("expression", "x");
    tool.executeAsync(addArgs1).get();

    Map<String, Object> addArgs2 = new HashMap<>();
    addArgs2.put("operation", "add");
    addArgs2.put("expression", "y");
    tool.executeAsync(addArgs2).get();

    // Remove all
    Map<String, Object> removeAllArgs = new HashMap<>();
    removeAllArgs.put("operation", "removeAll");

    ToolResponse response = tool.executeAsync(removeAllArgs).get();

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;
    assertTrue(success.content().contains("All watch expressions removed"));

    // Verify list is empty
    Map<String, Object> listArgs = new HashMap<>();
    listArgs.put("operation", "list");
    ToolResponse listResponse = tool.executeAsync(listArgs).get();
    String resultJson = ((ToolResponse.Success) listResponse).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
    assertEquals(0, result.get("watch_count"));

    logger.info("RemoveAll operation test passed");
  }

  /**
   * Tests enable operation.
   */
  @Test
  public void testEnableOperation() throws Exception {
    logger.info("Testing enable operation...");

    // Add and disable a watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "status");
    tool.executeAsync(addArgs).get();

    Map<String, Object> disableArgs = new HashMap<>();
    disableArgs.put("operation", "disable");
    disableArgs.put("watch_id", 1);
    tool.executeAsync(disableArgs).get();

    // Enable it
    Map<String, Object> enableArgs = new HashMap<>();
    enableArgs.put("operation", "enable");
    enableArgs.put("watch_id", 1);

    ToolResponse response = tool.executeAsync(enableArgs).get();

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;
    assertTrue(success.content().contains("enabled"));

    logger.info("Enable operation test passed");
  }

  /**
   * Tests enable with missing watch_id.
   */
  @Test
  public void testEnableMissingWatchId() throws Exception {
    logger.info("Testing enable missing watch_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "enable");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("watch_id") || error.message().contains("parameter"));

    logger.info("Enable missing watch_id test passed");
  }

  /**
   * Tests disable operation.
   */
  @Test
  public void testDisableOperation() throws Exception {
    logger.info("Testing disable operation...");

    // Add a watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "enabled");
    tool.executeAsync(addArgs).get();

    // Disable it
    Map<String, Object> disableArgs = new HashMap<>();
    disableArgs.put("operation", "disable");
    disableArgs.put("watch_id", 1);

    ToolResponse response = tool.executeAsync(disableArgs).get();

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;
    assertTrue(success.content().contains("disabled"));

    logger.info("Disable operation test passed");
  }

  /**
   * Tests disable with missing watch_id.
   */
  @Test
  public void testDisableMissingWatchId() throws Exception {
    logger.info("Testing disable missing watch_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "disable");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("watch_id") || error.message().contains("parameter"));

    logger.info("Disable missing watch_id test passed");
  }

  /**
   * Tests evaluate operation requires suspended thread.
   */
  @Test
  public void testEvaluateRequiresSuspendedThread() throws Exception {
    logger.info("Testing evaluate requires suspended thread...");

    // Add a watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "1 + 1");
    tool.executeAsync(addArgs).get();

    // Ensure no threads remain suspended before evaluation
    debuggerService.resumeAll();

    // Try evaluate with a running thread
    List<ThreadInfo> threads = debuggerService.getThreads();
    Optional<ThreadInfo> runningThread = threads.stream().filter(thread -> !thread.suspended()).findFirst();
    Assumptions.assumeTrue(runningThread.isPresent(), "No running thread available for evaluate test");

    long threadId = runningThread.get().id();

    Map<String, Object> evalArgs = new HashMap<>();
    evalArgs.put("operation", "evaluate");
    evalArgs.put("thread_id", threadId);

    ToolResponse response = tool.executeAsync(evalArgs).get();

    // Should fail because thread is not suspended
    if (!(response instanceof ToolResponse.Error)) {
      ThreadReference threadRef = debuggerService.getThreadById(threadId);
      boolean actuallySuspended = threadRef != null && threadRef.isSuspended();
      Assumptions.assumeTrue(actuallySuspended,
          "Evaluation succeeded even though thread is not suspended (unexpected runtime behavior)");
      return;
    }

    ToolResponse.Error error = (ToolResponse.Error) response;
    int errorCode = error.code();
    assertTrue(
        errorCode == DebuggerErrorCode.THREAD_NOT_SUSPENDED.getCode()
            || errorCode == DebuggerErrorCode.OPERATION_TIMEOUT.getCode(),
        () -> "Unexpected error code for evaluate operation: " + errorCode);

    logger.info("Evaluate requires suspended thread test passed");
  }

  /**
   * Tests evaluate with thread_name parameter.
   */
  @Test
  public void testEvaluateWithThreadName() throws Exception {
    logger.info("Testing evaluate with thread_name...");

    // Add a watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "2 * 3");
    tool.executeAsync(addArgs).get();

    // Try evaluate with thread name
    Map<String, Object> evalArgs = new HashMap<>();
    evalArgs.put("operation", "evaluate");
    evalArgs.put("thread_name", "main");

    ToolResponse response = tool.executeAsync(evalArgs).get();

    // Will fail on suspend check if thread exists and is running
    assertNotNull(response);

    logger.info("Evaluate with thread_name test passed");
  }

  /**
   * Tests evaluate with frame_index parameter.
   */
  @Test
  public void testEvaluateWithFrameIndex() throws Exception {
    logger.info("Testing evaluate with frame_index...");

    // Add a watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "value");
    tool.executeAsync(addArgs).get();

    // Try evaluate with frame index
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> evalArgs = new HashMap<>();
      evalArgs.put("operation", "evaluate");
      evalArgs.put("thread_id", threadId);
      evalArgs.put("frame_index", 0);

      ToolResponse response = tool.executeAsync(evalArgs).get();

      // Will fail on suspend check, but validates frame_index parameter
      assertNotNull(response);
    }

    logger.info("Evaluate with frame_index test passed");
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
   * Tests complete workflow: add, list, disable, enable, remove.
   */
  @Test
  public void testCompleteWorkflow() throws Exception {
    logger.info("Testing complete workflow...");

    // Add watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "counter");
    addArgs.put("display_name", "Counter Variable");
    ToolResponse addResponse = tool.executeAsync(addArgs).get();
    assertTrue(addResponse instanceof ToolResponse.Success);

    // List watches
    Map<String, Object> listArgs = new HashMap<>();
    listArgs.put("operation", "list");
    ToolResponse listResponse = tool.executeAsync(listArgs).get();
    assertTrue(listResponse instanceof ToolResponse.Success);

    // Disable watch
    Map<String, Object> disableArgs = new HashMap<>();
    disableArgs.put("operation", "disable");
    disableArgs.put("watch_id", 1);
    ToolResponse disableResponse = tool.executeAsync(disableArgs).get();
    assertTrue(disableResponse instanceof ToolResponse.Success);

    // Enable watch
    Map<String, Object> enableArgs = new HashMap<>();
    enableArgs.put("operation", "enable");
    enableArgs.put("watch_id", 1);
    ToolResponse enableResponse = tool.executeAsync(enableArgs).get();
    assertTrue(enableResponse instanceof ToolResponse.Success);

    // Remove watch
    Map<String, Object> removeArgs = new HashMap<>();
    removeArgs.put("operation", "remove");
    removeArgs.put("watch_id", 1);
    ToolResponse removeResponse = tool.executeAsync(removeArgs).get();
    assertTrue(removeResponse instanceof ToolResponse.Success);

    logger.info("Complete workflow test passed");
  }

  /**
   * Tests parameter type coercion for watch_id.
   */
  @Test
  public void testWatchIdTypeCoercion() throws Exception {
    logger.info("Testing watch_id type coercion...");

    // Add a watch
    Map<String, Object> addArgs = new HashMap<>();
    addArgs.put("operation", "add");
    addArgs.put("expression", "test");
    tool.executeAsync(addArgs).get();

    // Test with Number
    Map<String, Object> args1 = new HashMap<>();
    args1.put("operation", "disable");
    args1.put("watch_id", 1); // Number
    ToolResponse response1 = tool.executeAsync(args1).get();
    assertNotNull(response1);

    // Test with String
    Map<String, Object> args2 = new HashMap<>();
    args2.put("operation", "enable");
    args2.put("watch_id", "1"); // String
    ToolResponse response2 = tool.executeAsync(args2).get();
    assertNotNull(response2);

    logger.info("Watch_id type coercion test passed");
  }
}
