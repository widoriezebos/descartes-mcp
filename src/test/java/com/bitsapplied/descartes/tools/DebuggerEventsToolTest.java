package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
      assertNotNull(result.get("latest_sequence"));
      assertEquals(50, ((Number) result.get("requested_timeout_ms")).intValue());
      assertEquals(50, ((Number) result.get("effective_timeout_ms")).intValue());
      assertEquals(0, ((Number) result.get("adapter_extended_timeout_ms")).intValue());
      assertNotNull(result.get("waited_ms"));
    }
  }

  @Test
  public void testWaitAliasWaitForMapsToWait() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      Map<String, Object> result = exec(tool, Map.of("operation", "wait_for", "timeout_ms", 25));
      assertTrue((Boolean) result.get("timed_out"));
      assertEquals(0, result.get("pending_events"));
    }
  }

  @Test
  public void testWaitAliasWaitForEventMapsToWait() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      Map<String, Object> result = exec(tool, Map.of("operation", "wait_for_event", "timeout_ms", 25));
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
      assertNotNull(result.get("latest_sequence"));
    }
  }

  @Test
  public void testGetEventsAliasMapsToFetch() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 9L)));

      Map<String, Object> result = exec(tool, Map.of("operation", "get_events", "max_events", 10));
      assertEquals(1, result.get("count"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
      assertEquals("debugger.breakpoint_hit", events.get(0).get("type"));
    }
  }

  @Test
  public void testFetchClampsMaxEventsAboveLimit() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      for (int i = 0; i < 110; i++) {
        queue.addNotification(new DebuggerNotification("debugger.thread_start", Map.of("thread_id", i)));
      }

      Map<String, Object> result = exec(tool, Map.of("operation", "fetch", "max_events", 200));
      assertEquals(100, result.get("count"));
      assertEquals(200, result.get("clamped_from"));
      assertEquals(100, result.get("max_events"));
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
      assertNotNull(result.get("latest_sequence"));
    }
  }

  @Test
  public void testClearWithFiltersRemovesOnlyMatchingEvents() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      queue.addNotification(new DebuggerNotification("debugger.step_complete", Map.of("thread_id", 1L)));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 2L)));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 3L)));

      Map<String, Object> clearResult = exec(tool,
          Map.of("operation", "clear", "types", List.of("debugger.breakpoint_hit"), "thread_id", 2));
      assertEquals(1, clearResult.get("cleared"));

      Map<String, Object> fetchResult = exec(tool, Map.of("operation", "fetch", "max_events", 10));
      assertEquals(2, fetchResult.get("count"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> remaining = (List<Map<String, Object>>) fetchResult.get("events");
      assertEquals("debugger.step_complete", remaining.get(0).get("type"));
      assertEquals("debugger.breakpoint_hit", remaining.get(1).get("type"));
      assertEquals(3, ((Number) ((Map<?, ?>) remaining.get(1).get("payload")).get("thread_id")).intValue());
    }
  }

  @Test
  public void testFetchWithSinceSequenceReturnsOnlyNewerEvents() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);

      queue.addNotification(new DebuggerNotification("debugger.step_complete", Map.of("thread_id", 1L)));
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 2L)));
      long baseline = queue.latestSequence();
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 3L)));

      Map<String, Object> result = exec(tool,
          Map.of("operation", "fetch", "types", List.of("debugger.breakpoint_hit"), "since_sequence", baseline));
      assertEquals(1, result.get("count"));
      assertEquals(baseline, ((Number) result.get("since_sequence")).longValue());
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
      assertEquals("debugger.breakpoint_hit", events.get(0).get("type"));
      assertEquals(3, ((Number) ((Map<?, ?>) events.get(0).get("payload")).get("thread_id")).intValue());
    }
  }

  @Test
  public void testWaitWithSinceSequenceIgnoresBacklog() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 77L)));
      long baseline = queue.latestSequence();

      Map<String, Object> timeoutResult = exec(tool,
          Map.of("operation", "wait", "types", List.of("debugger.breakpoint_hit"), "since_sequence", baseline,
              "timeout_ms", 10));
      assertTrue((Boolean) timeoutResult.get("timed_out"));
      assertEquals(baseline, ((Number) timeoutResult.get("since_sequence")).longValue());

      var waitFuture = tool.executeAsync(Map.of("operation", "wait", "types", List.of("debugger.breakpoint_hit"),
          "since_sequence", baseline, "timeout_ms", 1000));
      Thread.sleep(25);
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 88L)));

      Map<String, Object> waitResult = parse(waitFuture.join());
      assertFalse((Boolean) waitResult.get("timed_out"));
      @SuppressWarnings("unchecked")
      Map<String, Object> event = (Map<String, Object>) waitResult.get("event");
      assertEquals("debugger.breakpoint_hit", event.get("type"));
      assertEquals(88, ((Number) ((Map<?, ?>) event.get("payload")).get("thread_id")).intValue());
    }
  }

  @Test
  public void testSinceSequenceMustBePositiveOrZero() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      ToolResponse response = tool.executeAsync(Map.of("operation", "fetch", "since_sequence", -1)).join();
      ToolResponse.Error error = assertInstanceOf(ToolResponse.Error.class, response);
      assertEquals(ToolErrorCode.INVALID_PARAMETER_VALUE, error.code());
      assertTrue(error.message().contains("since_sequence"));
    }
  }

  @Test
  public void testUnsupportedOperationProvidesAliasGuidance() throws Exception {
    Map<String, Object> context = new ConcurrentHashMap<>();
    try (DebuggerEventsTool tool = new DebuggerEventsTool(context)) {
      ToolResponse response = tool.executeAsync(Map.of("operation", "wait_for_anything")).join();
      ToolResponse.Error error = assertInstanceOf(ToolResponse.Error.class, response);
      assertEquals(ToolErrorCode.UNSUPPORTED_OPERATION, error.code());
      assertTrue(error.message().contains("wait_for"));
      assertTrue(error.message().contains("wait_for_event"));
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
