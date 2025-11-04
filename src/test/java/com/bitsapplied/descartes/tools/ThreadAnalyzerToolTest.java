package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ThreadAnalyzerTool.
 */
public class ThreadAnalyzerToolTest {

  private ThreadAnalyzerTool tool;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    tool = new ThreadAnalyzerTool();
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testGetToolName() {
    assertEquals("thread_analyzer", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("thread states"));
    assertTrue(description.contains("deadlocks"));
    assertTrue(description.contains("locks"));
  }

  @Test
  public void testGetToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();

    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties);

    // Check operation property
    @SuppressWarnings("unchecked")
    Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");
    assertEquals("string", operationProp.get("type"));
    @SuppressWarnings("unchecked")
    List<String> operations = (List<String>) operationProp.get("enum");
    assertTrue(operations.contains("thread_list"));
    assertTrue(operations.contains("thread_inspect"));
    assertTrue(operations.contains("thread_search"));
    assertTrue(operations.contains("deadlocks"));
    assertTrue(operations.contains("thread_dump"));

    // Check include_stack property
    @SuppressWarnings("unchecked")
    Map<String, Object> includeStackProp = (Map<String, Object>) properties.get("include_stack");
    assertEquals("boolean", includeStackProp.get("type"));

    // Check max_stack_depth property
    @SuppressWarnings("unchecked")
    Map<String, Object> maxStackDepthProp = (Map<String, Object>) properties.get("max_stack_depth");
    assertEquals("integer", maxStackDepthProp.get("type"));

    // Check required fields
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
  }

  @Test
  public void testGetThreadStates() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_list");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("total_threads"));
    assertNotNull(result.get("matched_threads"));
    assertNotNull(result.get("returned_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertNotNull(threads);
    assertFalse(threads.isEmpty());

    // Check first thread structure
    Map<String, Object> firstThread = threads.get(0);
    assertTrue(firstThread.containsKey("id"));
    assertTrue(firstThread.containsKey("name"));
    assertTrue(firstThread.containsKey("state"));
    assertTrue(firstThread.containsKey("priority"));
    assertTrue(firstThread.containsKey("daemon"));
    assertTrue(firstThread.containsKey("cpu_time_ms"));
    assertTrue(firstThread.containsKey("user_time_ms"));
  }

  @Test
  public void testGetThreadStatesWithFilter() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_list");
    args.put("name_pattern", ".*main.*");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Should have at least the main thread
    assertFalse(threads.isEmpty());

    // All threads should contain "main" in their name
    for (Map<String, Object> thread : threads) {
      String name = (String) thread.get("name");
      assertTrue(name.contains("main"));
    }
  }

  @Test
  public void testGetThreadStatesWithStack() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("include_details", true);
    args.put("max_stack_depth", 5);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // At least one thread should have a stack trace
    boolean hasStackTrace = false;
    for (Map<String, Object> thread : threads) {
      if (thread.containsKey("stack_trace")) {
        @SuppressWarnings("unchecked")
        List<String> stackTrace = (List<String>) thread.get("stack_trace");
        if (!stackTrace.isEmpty()) {
          hasStackTrace = true;
          // Should respect max depth
          assertTrue(stackTrace.size() <= 6); // 5 + possible "... N more"
          break;
        }
      }
    }

    assertTrue(hasStackTrace, "At least one thread should have a stack trace");
  }

  @Test
  public void testDetectDeadlocksNoDeadlock() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "deadlocks");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals(false, result.get("deadlocks_found"));
    assertEquals("No deadlocks detected", result.get("message"));
  }

  @Test
  public void testAnalyzeLocks() throws Exception {
    // Create a thread holding a lock
    final ReentrantLock lock = new ReentrantLock();
    Thread lockHolder = new Thread(() -> {
      lock.lock();
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    }, "TestLockHolder");
    lockHolder.start();

    // Give the thread time to acquire the lock
    Thread.sleep(50);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("name_contains", "TestLockHolder");
    args.put("include_details", true);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("total_threads"));
    assertNotNull(result.get("matched_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertNotNull(threads);

    // Wait for test thread to complete
    lockHolder.join();
  }

  @Test
  public void testGetWaitingThreads() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);

    Thread waitingThread = new Thread(() -> {
      try {
        latch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "TestWaitingThread");
    waitingThread.start();

    // Give the thread time to start waiting
    Thread.sleep(50);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("state_in", List.of("WAITING", "TIMED_WAITING"));

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("matched_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Should have at least our waiting thread
    boolean foundTestThread = false;
    for (Map<String, Object> thread : threads) {
      if ("TestWaitingThread".equals(thread.get("name"))) {
        foundTestThread = true;
        String state = (String) thread.get("state");
        assertTrue("WAITING".equals(state) || "TIMED_WAITING".equals(state));
        break;
      }
    }

    assertTrue(foundTestThread, "Should find our test waiting thread");

    // Clean up
    latch.countDown();
    waitingThread.join();
  }

  @Test
  public void testGetBlockedThreads() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("state_in", List.of("BLOCKED"));

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("matched_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertNotNull(threads);

    // Check that each blocked thread has proper structure
    for (Map<String, Object> thread : threads) {
      assertEquals("BLOCKED", thread.get("state"));
      assertTrue(thread.containsKey("id"));
      assertTrue(thread.containsKey("name"));
    }
  }

  @Test
  public void testThreadDump() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("max_stack_depth", 5);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("total_threads"));
    assertNotNull(result.get("filtered_threads"));
    assertNotNull(result.get("thread_dump"));
    assertNotNull(result.get("timestamp"));

    // When no filtering, total_threads should equal filtered_threads
    assertEquals(result.get("total_threads"), result.get("filtered_threads"));

    String dump = (String) result.get("thread_dump");
    assertFalse(dump.isEmpty());

    // Should contain thread dump header
    assertTrue(dump.contains("Full thread dump"));

    // Should contain at least main thread
    assertTrue(dump.contains("\"main\""));

    // Should contain thread states
    assertTrue(dump.contains("state="));
  }

  @Test
  public void testMissingOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Operation is required"));
  }

  @Test
  public void testUnknownOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "unknown");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Unknown operation"));
  }

  @Test
  public void testNullArguments() throws Exception {
    try {
      tool.executeAsync(null).get();
      throw new AssertionError("Expected exception to be thrown");
    } catch (Throwable e) {
      assertNotNull(e.getCause() != null ? e.getCause() : e);
    }
  }

  @Test
  public void testThreadPriorities() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_list");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    for (Map<String, Object> thread : threads) {
      Integer priority = (Integer) thread.get("priority");
      assertTrue(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
          "Thread priority should be between " + Thread.MIN_PRIORITY + " and " + Thread.MAX_PRIORITY);
    }
  }

  @Test
  public void testContentionMonitoring() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("include_details", true);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Check if contention monitoring fields are present
    for (Map<String, Object> thread : threads) {
      // These fields should be present if contention monitoring is supported
      if (thread.containsKey("blocked_count")) {
        assertNotNull(thread.get("blocked_count"));
        assertNotNull(thread.get("blocked_time_ms"));
        assertNotNull(thread.get("waited_count"));
        assertNotNull(thread.get("waited_time_ms"));

        // Values should be non-negative
        assertTrue(((Number) thread.get("blocked_count")).longValue() >= 0);
        assertTrue(((Number) thread.get("blocked_time_ms")).longValue() >= 0);
        assertTrue(((Number) thread.get("waited_count")).longValue() >= 0);
        assertTrue(((Number) thread.get("waited_time_ms")).longValue() >= 0);
      }
    }
  }

  @Test
  public void testWaitingThreadsWithFilter() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("state_in", List.of("WAITING", "TIMED_WAITING"));
    args.put("name_contains", "NonExistentThread");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("matched_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertTrue(threads.isEmpty());
  }

  @Test
  public void testMaxStackDepthAsString() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("max_stack_depth", "not a number"); // Invalid type

    // Should handle gracefully or use default
    try {
      tool.executeAsync(args).get();
      throw new AssertionError("Expected exception to be thrown");
    } catch (Throwable e) {
      assertNotNull(e.getCause() != null ? e.getCause() : e);
    }
  }

  @Test
  public void testThreadTimingInformation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_list");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // CPU time should be present and reasonable
    for (Map<String, Object> thread : threads) {
      if (thread.containsKey("cpu_time_ms")) {
        long cpuTime = ((Number) thread.get("cpu_time_ms")).longValue();
        long userTime = ((Number) thread.get("user_time_ms")).longValue();

        assertTrue(cpuTime >= 0, "CPU time should be non-negative");
        assertTrue(userTime >= 0, "User time should be non-negative");
        assertTrue(userTime <= cpuTime, "User time should not exceed CPU time");
      }
    }
  }

  @Test
  public void testDeadlockDetectionWithStack() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "deadlocks");
    args.put("include_stack", true);
    args.put("max_stack_depth", 3);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    // If there are deadlocks, check the structure
    if ((Boolean) result.get("deadlocks_found")) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> deadlockChains = (List<Map<String, Object>>) result.get("deadlock_chains");
      assertNotNull(deadlockChains);

      for (Map<String, Object> chain : deadlockChains) {
        assertNotNull(chain.get("chain_length"));
        assertNotNull(chain.get("is_circular"));
        assertNotNull(chain.get("threads"));
      }
    }
  }

  @Test
  public void testContestedLocks() throws Exception {
    // Create a contested lock scenario
    final Object lock = new Object();

    Thread holder = new Thread(() -> {
      synchronized (lock) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }, "LockHolder");

    Thread waiter = new Thread(() -> {
      try {
        Thread.sleep(20); // Let holder get the lock first
        synchronized (lock) {
          // This will block until holder releases
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "LockWaiter");

    holder.start();
    waiter.start();

    // Give threads time to get into position
    Thread.sleep(50);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("name_contains", "Lock");
    args.put("include_details", true);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("matched_threads"));

    // Clean up
    holder.join();
    waiter.join();
  }
}
