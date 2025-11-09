package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.integration.MCPEventBridge.DebuggerNotification;
import com.bitsapplied.descartes.util.DebuggerEventQueue;
import com.bitsapplied.descartes.util.DebuggerEventQueues;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for {@link JShellAsyncTool}. Verifies interaction with the
 * event polling tool to model the debugger workflow (start async workload →
 * wait for breakpoint → inspect completion).
 */
public class JShellAsyncToolTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void testAsyncSnippetCompletesAndEventDelivered() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (JShellAsyncTool asyncTool = new JShellAsyncTool(context);
        DebuggerEventsTool eventsTool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      String code = """
          try {
            Thread.sleep(200);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          "async-result";
          """;

      Map<String, Object> startResponse = exec(asyncTool,
          Map.of("operation", "start", "code", code, "timeout_seconds", 5));
      String taskId = (String) startResponse.get("task_id");
      assertNotNull(taskId, "task id should be assigned");

      // Issue wait before emitting event to ensure blocking path works
      var waitFuture = eventsTool.executeAsync(Map.of("operation", "wait", "timeout_ms", 2000));
      // Allow waiter to start
      Thread.sleep(50);

      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit",
          Map.of("thread_id", 123L, "thread_name", "main", "class", "Example", "line", 42)));

      Map<String, Object> waitResult = parse(waitFuture.join());
      assertEquals(false, waitResult.get("timed_out"));
      @SuppressWarnings("unchecked")
      Map<String, Object> event = (Map<String, Object>) waitResult.get("event");
      assertNotNull(event);
      assertEquals("debugger.breakpoint_hit", event.get("type"));

      Map<String, Object> statusRunning = exec(asyncTool,
          Map.of("operation", "status", "task_id", taskId, "include_result", false));
      assertTrue(statusRunning.containsKey("status"));

      Map<String, Object> finalStatus = awaitStatus(asyncTool, taskId, "success", 5, TimeUnit.SECONDS);
      assertEquals("success", finalStatus.get("status"));
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) finalStatus.get("result");
      assertNotNull(result);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
      assertNotNull(events);
      assertFalse(events.isEmpty());
      assertEquals("\"async-result\"", events.get(events.size() - 1).get("value"));
    }
  }

  @Test
  public void testCancelAsyncSnippet() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (JShellAsyncTool asyncTool = new JShellAsyncTool(context)) {
      String code = """
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          "never-completes";
          """;

      Map<String, Object> startResponse = exec(asyncTool,
          Map.of("operation", "start", "code", code, "timeout_seconds", 10));
      String taskId = (String) startResponse.get("task_id");
      assertNotNull(taskId);

      Map<String, Object> cancelResponse = exec(asyncTool, Map.of("operation", "cancel", "task_id", taskId));
      assertEquals("cancelled", cancelResponse.get("status"));

      Map<String, Object> finalStatus = awaitStatus(asyncTool, taskId, "cancelled", 5, TimeUnit.SECONDS);
      assertEquals("cancelled", finalStatus.get("status"));
      assertFalse(finalStatus.containsKey("result"));
      @SuppressWarnings("unchecked")
      Map<String, Object> error = (Map<String, Object>) finalStatus.get("error");
      assertNotNull(error);
    }
  }

  @Test
  public void testStatusWithoutResult() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (JShellAsyncTool asyncTool = new JShellAsyncTool(context)) {
      String code = "\"complete\";";

      Map<String, Object> startResponse = exec(asyncTool,
          Map.of("operation", "start", "code", code, "session_id", "session-A"));
      String taskId = (String) startResponse.get("task_id");

      awaitStatus(asyncTool, taskId, "success", 2, TimeUnit.SECONDS);

      Map<String, Object> status = exec(asyncTool,
          Map.of("operation", "status", "task_id", taskId, "include_result", false));
      assertEquals("success", status.get("status"));
      assertFalse(status.containsKey("result"));
    }
  }

  @Test
  public void testTimeoutSurfaceInStatus() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (JShellAsyncTool asyncTool = new JShellAsyncTool(context)) {
      String code = """
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          "slow";
          """;

      Map<String, Object> startResponse = exec(asyncTool,
          Map.of("operation", "start", "code", code, "timeout_seconds", 1));
      String taskId = (String) startResponse.get("task_id");

      Map<String, Object> status = awaitStatus(asyncTool, taskId, "timeout", 3, TimeUnit.SECONDS);
      assertEquals("timeout", status.get("status"));
      @SuppressWarnings("unchecked")
      Map<String, Object> error = (Map<String, Object>) status.get("error");
      assertNotNull(error);
    }
  }

  private Map<String, Object> exec(MCPTool tool, Map<String, Object> arguments) throws Exception {
    return parse(tool.executeAsync(arguments).join());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(ToolResponse response) throws Exception {
    if (!(response instanceof ToolResponse.Success success)) {
      throw new AssertionError("Expected success response but got: " + response);
    }
    return objectMapper.readValue(success.content(), Map.class);
  }

  private Map<String, Object> awaitStatus(JShellAsyncTool tool, String taskId, String expectedStatus, long timeout,
      TimeUnit unit) throws Exception {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    Map<String, Object> status = null;
    while (System.nanoTime() < deadline) {
      status = exec(tool, Map.of("operation", "status", "task_id", taskId));
      if (expectedStatus.equals(status.get("status"))) {
        return status;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Task " + taskId + " did not reach status " + expectedStatus + " within timeout");
  }
}
