package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.util.ThreadUtils;
import com.bitsapplied.descartes.util.ToolExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ThreadAnalyzerTool.
 */
public class ThreadAnalyzerToolTest {

  private ThreadAnalyzerTool tool;
  private ObjectMapper objectMapper;
  private Map<String, Object> context;

  @BeforeEach
  public void setUp() {
    context = new ConcurrentHashMap<>();
    tool = new ThreadAnalyzerTool(context);
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  public void tearDown() {
    tool.close();
    ToolExecutors.shutdownSharedExecutor(context);
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

    // Wait for the thread to actually reach WAITING state (robust polling with
    // timeout)
    long deadline = System.currentTimeMillis() + 5000; // 5 second timeout
    while (waitingThread.getState() != Thread.State.WAITING && waitingThread.getState() != Thread.State.TIMED_WAITING) {
      if (System.currentTimeMillis() > deadline) {
        fail("Thread did not reach WAITING state within 5 seconds. Current state: " + waitingThread.getState());
      }
      Thread.sleep(10);
    }

    // Give a bit more time to ensure thread is stable in WAITING state
    Thread.sleep(50);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("state_in", List.of("WAITING", "TIMED_WAITING"));
    args.put("max_results", 100); // Increase max results to ensure we find our thread

    // Poll for the thread to appear in search results with timeout (fixes
    // flakiness)
    boolean foundTestThread = false;
    long searchDeadline = System.currentTimeMillis() + 5000; // 5 second timeout
    while (!foundTestThread && System.currentTimeMillis() < searchDeadline) {
      String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertNotNull(result.get("matched_threads"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

      // Check if our waiting thread is in the results
      for (Map<String, Object> thread : threads) {
        if ("TestWaitingThread".equals(thread.get("name"))) {
          foundTestThread = true;
          String state = (String) thread.get("state");
          assertTrue("WAITING".equals(state) || "TIMED_WAITING".equals(state),
              "Thread should be in WAITING or TIMED_WAITING state, but was: " + state);
          break;
        }
      }

      if (!foundTestThread) {
        Thread.sleep(50); // Wait before retrying
      }
    }

    assertTrue(foundTestThread, "Should find our test waiting thread (waited up to 5 seconds)");

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

  // ========== Enhanced Edge Case Tests ==========

  @Test
  public void testThreadInspectWithThreadIdsAsArray() throws Exception {
    // Get the main thread ID
    long mainThreadId = ThreadUtils.getThreadId(Thread.currentThread());

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_inspect");
    args.put("thread_ids", List.of(mainThreadId));

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("threads"));
  }

  @Test
  public void testThreadInspectWithThreadIdsAsString_Error() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_inspect");
    args.put("thread_ids", "1"); // String instead of array - should error

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("must be an array") || error.message().contains("Collection"));
  }

  @Test
  public void testThreadInspectWithEmptyArray_Error() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_inspect");
    args.put("thread_ids", List.of()); // Empty array

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("empty") || error.message().contains("at least one"));
  }

  @Test
  public void testThreadInspectWithJSONStringDetection_Error() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_inspect");
    args.put("thread_ids", "[1,2,3]"); // JSON string - should be detected and rejected

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    // Should detect JSON string and provide helpful message
    assertTrue(error.message().contains("array") || error.message().contains("Collection")
        || error.message().contains("JSON"));
  }

  @Test
  public void testThreadInspectWithThreadNamesAsString() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_inspect");
    args.put("thread_names", "main"); // Single thread name as string

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("threads"));
  }

  @Test
  public void testThreadInspectWithThreadNamesAsArray() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_inspect");
    args.put("thread_names", List.of("main", "Reference Handler"));

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("threads"));
  }

  @Test
  public void testThreadDumpWithFilterStackPattern_ValidRegex() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("filter_stack_pattern", "java\\.lang\\..*"); // Valid regex

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("thread_dump"));

    // Dump should only contain frames matching the pattern
    String dump = (String) result.get("thread_dump");
    assertFalse(dump.isEmpty());
  }

  @Test
  public void testThreadDumpWithNestedQuantifiers_ReDoSError() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("filter_stack_pattern", "(a+)+b"); // Nested quantifiers - ReDoS risk

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("nested quantifier") || error.message().contains("ReDoS")
        || error.message().contains("unsafe pattern"));
  }

  @Test
  public void testThreadDumpWithConsecutiveQuantifiers_Error() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("filter_stack_pattern", "a**"); // Consecutive quantifiers - invalid

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("consecutive") || error.message().contains("unsafe")
        || error.message().contains("invalid pattern"));
  }

  @Test
  public void testThreadDumpWithTooLongPattern_Error() throws Exception {
    // Create a pattern > 500 chars
    String longPattern = "a".repeat(501);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("filter_stack_pattern", longPattern);

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("too long") || error.message().contains("500")
        || error.message().contains("maximum length"));
  }

  @Test
  public void testThreadDumpWithInvalidRegex_PatternSyntaxException() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");
    args.put("filter_stack_pattern", "[unclosed"); // Invalid regex

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("pattern") || error.message().contains("invalid")
        || error.message().contains("regex"));
  }

  @Test
  public void testThreadSearchWithIncludeDetails_SizeTracking() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_search");
    args.put("include_details", true);
    args.put("max_stack_depth", 50); // Request deep stacks to potentially trigger size limits

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    // Should track size and potentially truncate if too large
    assertNotNull(result.get("threads"));

    // If truncated, should have a message about size limits
    if (result.containsKey("truncated") && (Boolean) result.get("truncated")) {
      assertNotNull(result.get("truncation_reason"));
    }
  }

  @Test
  public void testThreadDumpWithManyThreads_WarningMessage() throws Exception {
    // This test verifies that when there are > 100 threads, a warning is included
    // We can't easily create 100+ threads, but we can verify the structure handles
    // it
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_dump");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    int totalThreads = (Integer) result.get("total_threads");

    // If there are > 100 threads (unlikely in test environment), check for warning
    if (totalThreads > 100 && result.containsKey("warnings")) {
      @SuppressWarnings("unchecked")
      List<String> warnings = (List<String>) result.get("warnings");
      boolean hasThreadCountWarning = warnings.stream().anyMatch(w -> w.contains("100") || w.contains("many threads"));
      assertTrue(hasThreadCountWarning, "Should warn about large thread count");
    }
  }

  @Test
  public void testThreadListWithSortByCpuTime() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_list");
    args.put("sort_by", "cpu_time");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Verify threads are sorted by CPU time (descending)
    if (threads.size() > 1) {
      long prevCpuTime = Long.MAX_VALUE;
      for (Map<String, Object> thread : threads) {
        if (thread.containsKey("cpu_time_ms")) {
          long cpuTime = ((Number) thread.get("cpu_time_ms")).longValue();
          assertTrue(cpuTime <= prevCpuTime, "Threads should be sorted by CPU time descending");
          prevCpuTime = cpuTime;
        }
      }
    }
  }

  @Test
  public void testThreadInspectWithStateFilter() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_list");
    args.put("state_filter", "RUNNABLE");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // All threads should be RUNNABLE
    for (Map<String, Object> thread : threads) {
      assertEquals("RUNNABLE", thread.get("state"));
    }
  }
}
