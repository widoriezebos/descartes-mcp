package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.integration.MCPEventBridge.DebuggerNotification;
import com.bitsapplied.descartes.util.DebuggerEventQueue;
import com.bitsapplied.descartes.util.DebuggerEventQueues;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link DebuggerEventsTool}.
 */
public class DebuggerEventsToolTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void testWaitWithTimeout() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      Map<String, Object> result = exec(tool, Map.of("operation", "wait", "timeout_ms", 50));
      assertTrue((Boolean) result.get("timed_out"));
      assertEquals(0, result.get("pending_events"));
    }
  }

  @Test
  public void testWaitReceivesEvent() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      var waitFuture = tool.executeAsync(Map.of("operation", "wait", "timeout_ms", 1000));
      Thread.sleep(25);

      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 77L, "line", 12)));

      Map<String, Object> result = parse(waitFuture.join());
      assertFalse((Boolean) result.get("timed_out"));
      @SuppressWarnings("unchecked")
      Map<String, Object> event = (Map<String, Object>) result.get("event");
      assertNotNull(event);
      assertEquals("debugger.breakpoint_hit", event.get("type"));
      assertEquals(0, result.get("pending_events"));
    }
  }

  @Test
  public void testFetchReturnsQueuedEvents() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      queue.addNotification(new DebuggerNotification("debugger.step_complete", Map.of("thread_id", 1L)));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 2L)));

      Map<String, Object> result = exec(tool, Map.of("operation", "fetch", "max_events", 5));
      assertEquals(2, result.get("count"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
      assertEquals(2, events.size());
      assertEquals("debugger.step_complete", events.get(0).get("type"));
      assertEquals("debugger.breakpoint_hit", events.get(1).get("type"));
      assertEquals(0, result.get("pending_events"));
    }
  }

  @Test
  public void testFetchWithFilters() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      queue.addNotification(new DebuggerNotification("debugger.step_complete", Map.of("thread_id", 1L)));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 1L)));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 2L)));

      Map<String, Object> result = exec(tool,
          Map.of("operation", "fetch", "types", List.of("debugger.breakpoint_hit"), "thread_id", 1));
      assertEquals(1, result.get("count"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
      assertEquals(1, events.size());
      assertEquals(1, ((Number) ((Map<?, ?>) events.get(0).get("payload")).get("thread_id")).intValue());
    }
  }

  @Test
  public void testClearRemovesBufferedEvents() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      queue.addNotification(new DebuggerNotification("debugger.step_complete", Map.of()));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of()));

      Map<String, Object> result = exec(tool, Map.of("operation", "clear"));
      assertEquals(2, result.get("cleared"));
      assertEquals(0, result.get("pending_events"));
    }
  }

  private Map<String, Object> exec(MCPTool tool, Map<String, Object> args) throws Exception {
    return parse(tool.executeAsync(args).join());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(ToolResponse response) throws Exception {
    if (!(response instanceof ToolResponse.Success success)) {
      throw new AssertionError("Expected success response but got " + response);
    }
    return objectMapper.readValue(success.content(), Map.class);
  }
}
