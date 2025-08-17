package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    assertTrue(operations.contains("threads"));
    assertTrue(operations.contains("deadlocks"));
    assertTrue(operations.contains("locks"));
    assertTrue(operations.contains("waiting"));
    assertTrue(operations.contains("blocked"));
    assertTrue(operations.contains("thread_dump"));

    // Check thread_name property
    @SuppressWarnings("unchecked")
    Map<String, Object> threadNameProp = (Map<String, Object>) properties.get("thread_name");
    assertEquals("string", threadNameProp.get("type"));

    // Check include_stack property
    @SuppressWarnings("unchecked")
    Map<String, Object> includeStackProp = (Map<String, Object>) properties.get("include_stack");
    assertEquals("boolean", includeStackProp.get("type"));
    assertEquals(false, includeStackProp.get("default"));

    // Check max_stack_depth property
    @SuppressWarnings("unchecked")
    Map<String, Object> maxStackDepthProp = (Map<String, Object>) properties.get("max_stack_depth");
    assertEquals("integer", maxStackDepthProp.get("type"));
    assertEquals(10, maxStackDepthProp.get("default"));

    // Check required fields
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
  }

  @Test
  public void testGetThreadStates() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("thread_count"));
    assertNotNull(result.get("state_summary"));

    @SuppressWarnings("unchecked")
    Map<String, Integer> stateSummary = (Map<String, Integer>) result.get("state_summary");
    assertNotNull(stateSummary);

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
    args.put("operation", "threads");
    args.put("thread_name", "main");

    String resultJson = tool.executeTool(args);
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
    args.put("operation", "threads");
    args.put("include_stack", true);
    args.put("max_stack_depth", 5);

    String resultJson = tool.executeTool(args);
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

    String resultJson = tool.executeTool(args);
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
    args.put("operation", "locks");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("lock_holders_count"));
    assertNotNull(result.get("unique_locks_count"));
    assertNotNull(result.get("lock_holders"));
    assertNotNull(result.get("lock_to_threads"));
    assertNotNull(result.get("contested_locks"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> lockHolders = (List<Map<String, Object>>) result.get("lock_holders");
    assertNotNull(lockHolders);

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
    args.put("operation", "waiting");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("waiting_thread_count"));
    assertNotNull(result.get("wait_reason_summary"));
    assertNotNull(result.get("waiting_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> waitingThreads = (List<Map<String, Object>>) result.get("waiting_threads");

    // Should have at least our waiting thread
    boolean foundTestThread = false;
    for (Map<String, Object> thread : waitingThreads) {
      if ("TestWaitingThread".equals(thread.get("thread_name"))) {
        foundTestThread = true;
        String state = (String) thread.get("state");
        assertTrue("WAITING".equals(state) || "TIMED_WAITING".equals(state));
        assertNotNull(thread.get("wait_reason"));
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
    args.put("operation", "blocked");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("blocked_thread_count"));
    assertNotNull(result.get("blocking_locks"));
    assertNotNull(result.get("blocked_threads"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> blockedThreads = (List<Map<String, Object>>) result.get("blocked_threads");
    assertNotNull(blockedThreads);

    // Check that each blocked thread has proper structure
    for (Map<String, Object> thread : blockedThreads) {
      assertEquals("BLOCKED", thread.get("state"));
      assertTrue(thread.containsKey("thread_id"));
      assertTrue(thread.containsKey("thread_name"));
    }
  }

  @Test
  public void testThreadDump() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("max_stack_depth", 5);

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("thread_count"));
    assertNotNull(result.get("thread_dump"));
    assertNotNull(result.get("timestamp"));

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
  public void testMissingOperation() {
    Map<String, Object> args = new HashMap<>();

    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });

    assertEquals("Operation is required", exception.getMessage());
  }

  @Test
  public void testUnknownOperation() {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "unknown");

    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });

    assertTrue(exception.getMessage().contains("Unknown operation"));
  }

  @Test
  public void testNullArguments() {
    Exception exception = assertThrows(NullPointerException.class, () -> {
      tool.executeTool(null);
    });

    assertNotNull(exception);
  }

  @Test
  public void testThreadPriorities() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");

    String resultJson = tool.executeTool(args);
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
    args.put("operation", "threads");

    String resultJson = tool.executeTool(args);
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
    args.put("operation", "waiting");
    args.put("thread_name", "NonExistentThread");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("waiting_thread_count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> waitingThreads = (List<Map<String, Object>>) result.get("waiting_threads");
    assertTrue(waitingThreads.isEmpty());
  }

  @Test
  public void testMaxStackDepthAsString() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("max_stack_depth", "not a number"); // Invalid type

    // Should handle gracefully or use default
    Exception exception = assertThrows(ClassCastException.class, () -> {
      tool.executeTool(args);
    });

    assertNotNull(exception);
  }

  @Test
  public void testThreadTimingInformation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");

    String resultJson = tool.executeTool(args);
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

    String resultJson = tool.executeTool(args);
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
    args.put("operation", "locks");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> contestedLocks = (List<Map<String, Object>>) result.get("contested_locks");
    assertNotNull(contestedLocks);

    // Clean up
    holder.join();
    waiter.join();
  }
}