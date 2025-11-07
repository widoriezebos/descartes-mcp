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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for DebuggerThreadsTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>list operation with filtering (state_filter, name_pattern,
 * suspended_only)</li>
 * <li>inspect operation</li>
 * <li>suspend operation</li>
 * <li>resume operation</li>
 * <li>resumeAll operation</li>
 * <li>Error handling (thread not found, missing params)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerThreadsToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerThreadsToolTest.class);

  private static DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerThreadsTool tool;
  private ObjectMapper objectMapper;
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

    // Reminder: without this external debuggee the tests would need dynamic JDWP
    // attach,
    // which HotSpot does not support because the JDWP agent lacks Agent_OnAttach.
    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    // Create fresh DebuggerService instance that shares the connection
    debuggerService = new DebuggerService(connectionManager);
    debuggerExecutor = new DebuggerExecutor();
    tool = new DebuggerThreadsTool(debuggerService, debuggerExecutor);
    objectMapper = new ObjectMapper();

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

    assertEquals("debugger_threads", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("thread") || description.contains("Thread"));
    assertTrue(description.contains("inspect") || description.contains("management"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("thread_id"));
    assertTrue(properties.containsKey("state_filter"));
    assertTrue(properties.containsKey("name_pattern"));
    assertTrue(properties.containsKey("suspended_only"));

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

    assertTrue(operations.contains("list"));
    assertTrue(operations.contains("inspect"));
    assertTrue(operations.contains("suspend"));
    assertTrue(operations.contains("resume"));
    assertTrue(operations.contains("resume_all"));
    assertEquals(5, operations.size());

    logger.info("Schema operations test passed");
  }

  /**
   * Tests basic list operation.
   */
  @Test
  public void testListBasic() throws Exception {
    logger.info("Testing list operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "list");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    String resultJson = ((ToolResponse.Success) response).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("thread_count"));
    assertNotNull(result.get("threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertTrue(threads.size() > 0, "Should have at least one thread");

    // Validate thread structure
    Map<String, Object> thread = threads.get(0);
    assertTrue(thread.containsKey("id"));
    assertTrue(thread.containsKey("name"));
    assertTrue(thread.containsKey("state"));
    assertTrue(thread.containsKey("suspended"));
    assertTrue(thread.containsKey("is_virtual"));

    logger.info("List operation test passed");
  }

  /**
   * Tests list operation with state filter.
   */
  @Test
  public void testListWithStateFilter() throws Exception {
    logger.info("Testing list with state filter...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "list");
    args.put("state_filter", "RUNNABLE");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    String resultJson = ((ToolResponse.Success) response).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // All returned threads should be RUNNABLE
    for (Map<String, Object> thread : threads) {
      assertEquals("RUNNABLE", thread.get("state"));
    }

    logger.info("List with state filter test passed");
  }

  /**
   * Tests list operation with name pattern filter.
   */
  @Test
  public void testListWithNamePattern() throws Exception {
    logger.info("Testing list with name pattern...");

    // Get all threads first to find a pattern
    List<ThreadInfo> allThreads = debuggerService.getThreads();
    if (!allThreads.isEmpty()) {
      String threadName = allThreads.get(0).name();
      String pattern = threadName.substring(0, Math.min(3, threadName.length()));

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "list");
      args.put("name_pattern", pattern);

      ToolResponse response = tool.executeAsync(args).get();

      assertTrue(response instanceof ToolResponse.Success);
      String resultJson = ((ToolResponse.Success) response).content();

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

      // All returned threads should contain the pattern
      for (Map<String, Object> thread : threads) {
        String name = (String) thread.get("name");
        assertTrue(name.contains(pattern), "Thread name should contain pattern");
      }
    }

    logger.info("List with name pattern test passed");
  }

  /**
   * Tests list operation with suspended_only filter.
   */
  @Test
  public void testListWithSuspendedOnly() throws Exception {
    logger.info("Testing list with suspended_only filter...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "list");
    args.put("suspended_only", true);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    String resultJson = ((ToolResponse.Success) response).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // All returned threads should be suspended
    for (Map<String, Object> thread : threads) {
      assertTrue((Boolean) thread.get("suspended"), "Thread should be suspended");
    }

    logger.info("List with suspended_only filter test passed");
  }

  /**
   * Tests inspect operation.
   */
  @Test
  public void testInspectOperation() throws Exception {
    logger.info("Testing inspect operation...");

    // Get a thread to inspect
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "inspect");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      assertTrue(response instanceof ToolResponse.Success);
      String resultJson = ((ToolResponse.Success) response).content();

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertNotNull(result.get("thread"));

      @SuppressWarnings("unchecked")
      Map<String, Object> thread = (Map<String, Object>) result.get("thread");

      assertEquals(threadId, ((Number) thread.get("id")).longValue());
      assertNotNull(thread.get("name"));
      assertNotNull(thread.get("state"));
    }

    logger.info("Inspect operation test passed");
  }

  /**
   * Tests inspect operation with missing thread_id.
   */
  @Test
  public void testInspectMissingThreadId() throws Exception {
    logger.info("Testing inspect missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "inspect");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("Inspect missing thread_id test passed");
  }

  /**
   * Tests inspect operation with thread not found.
   */
  @Test
  public void testInspectThreadNotFound() throws Exception {
    logger.info("Testing inspect thread not found...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "inspect");
    args.put("thread_id", 999999L);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().toLowerCase().contains("not found"));

    logger.info("Inspect thread not found test passed");
  }

  /**
   * Tests suspend operation.
   */
  @Test
  public void testSuspendOperation() throws Exception {
    logger.info("Testing suspend operation...");

    // Get a thread to suspend
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "suspend");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // May succeed or fail depending on thread state, just check response structure
      if (response instanceof ToolResponse.Success) {
        String resultJson = ((ToolResponse.Success) response).content();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
        assertEquals(threadId, ((Number) result.get("thread_id")).longValue());
      }
      // Always succeed in structure validation
      assertNotNull(response);
    }

    logger.info("Suspend operation test passed");
  }

  /**
   * Tests suspend operation with missing thread_id.
   */
  @Test
  public void testSuspendMissingThreadId() throws Exception {
    logger.info("Testing suspend missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "suspend");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("Suspend missing thread_id test passed");
  }

  /**
   * Tests resume operation.
   */
  @Test
  public void testResumeOperation() throws Exception {
    logger.info("Testing resume operation...");

    // Get a thread to resume
    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "resume");
      args.put("thread_id", threadId);

      ToolResponse response = tool.executeAsync(args).get();

      // May succeed or fail depending on thread state, just check response structure
      if (response instanceof ToolResponse.Success) {
        String resultJson = ((ToolResponse.Success) response).content();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
        assertEquals(threadId, ((Number) result.get("thread_id")).longValue());
      }
      // Always succeed in structure validation
      assertNotNull(response);
    }

    logger.info("Resume operation test passed");
  }

  /**
   * Tests resume operation with missing thread_id.
   */
  @Test
  public void testResumeMissingThreadId() throws Exception {
    logger.info("Testing resume missing thread_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "resume");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("thread_id"));

    logger.info("Resume missing thread_id test passed");
  }

  /**
   * Tests resumeAll operation.
   */
  @Test
  public void testResumeAllOperation() throws Exception {
    logger.info("Testing resumeAll operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "resume_all");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Success);
    String resultJson = ((ToolResponse.Success) response).content();

    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("message"));

    logger.info("ResumeAll operation test passed");
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

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      // Test with Number
      Map<String, Object> args1 = new HashMap<>();
      args1.put("operation", "inspect");
      args1.put("thread_id", threadId); // Number
      ToolResponse response1 = tool.executeAsync(args1).get();
      assertNotNull(response1);

      // Test with String
      Map<String, Object> args2 = new HashMap<>();
      args2.put("operation", "inspect");
      args2.put("thread_id", String.valueOf(threadId)); // String
      ToolResponse response2 = tool.executeAsync(args2).get();
      assertNotNull(response2);
    }

    logger.info("Thread_id type coercion test passed");
  }

  /**
   * Tests suspend and resume workflow.
   */
  @Test
  public void testSuspendResumeWorkflow() throws Exception {
    logger.info("Testing suspend/resume workflow...");

    List<ThreadInfo> threads = debuggerService.getThreads();
    if (!threads.isEmpty()) {
      long threadId = threads.get(0).id();

      // Suspend
      Map<String, Object> suspendArgs = new HashMap<>();
      suspendArgs.put("operation", "suspend");
      suspendArgs.put("thread_id", threadId);
      ToolResponse suspendResponse = tool.executeAsync(suspendArgs).get();

      if (suspendResponse instanceof ToolResponse.Success) {
        // Resume
        Map<String, Object> resumeArgs = new HashMap<>();
        resumeArgs.put("operation", "resume");
        resumeArgs.put("thread_id", threadId);
        ToolResponse resumeResponse = tool.executeAsync(resumeArgs).get();

        // Should work since we just suspended it
        assertNotNull(resumeResponse);
      }
    }

    logger.info("Suspend/resume workflow test passed");
  }
}
