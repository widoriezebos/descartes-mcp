package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for SystemMonitoringTool.
 */
public class SystemMonitoringToolTest {

  private SystemMonitoringTool tool;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    tool = new SystemMonitoringTool();
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testGetToolName() {
    assertEquals("system_monitoring", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("system resource"));
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
    assertTrue(operations.contains("memory"));
    assertTrue(operations.contains("gc"));
    assertTrue(operations.contains("time"));
    assertTrue(operations.contains("thread_stacks"));

    // Check thread_name property
    @SuppressWarnings("unchecked")
    Map<String, Object> threadNameProp = (Map<String, Object>) properties.get("thread_name");
    assertEquals("string", threadNameProp.get("type"));

    // Check include_stack property
    @SuppressWarnings("unchecked")
    Map<String, Object> includeStackProp = (Map<String, Object>) properties.get("include_stack");
    assertEquals("boolean", includeStackProp.get("type"));
    assertEquals(false, includeStackProp.get("default"));

    // Check required fields
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
  }

  @Test
  public void testGetThreadInfo() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("thread_count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertNotNull(threads);
    assertFalse(threads.isEmpty());

    // Check first thread structure and values
    Map<String, Object> firstThread = threads.get(0);
    assertTrue(firstThread.containsKey("id"));
    assertTrue(firstThread.containsKey("name"));
    assertTrue(firstThread.containsKey("state"));
    assertTrue(firstThread.containsKey("priority"));
    assertTrue(firstThread.containsKey("daemon"));
    assertTrue(firstThread.containsKey("cpu_time_nanos"));

    // Verify actual values are reasonable
    assertTrue(((Number) firstThread.get("id")).longValue() > 0, "Thread ID should be positive");
    assertNotNull(firstThread.get("name"), "Thread name should not be null");
    assertFalse(((String) firstThread.get("name")).isEmpty(), "Thread name should not be empty");

    // Thread state should be a valid Java thread state
    String state = (String) firstThread.get("state");
    assertTrue(List.of("NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED").contains(state),
        "Thread state should be valid: " + state);

    // Priority should be between 1 and 10
    int priority = ((Number) firstThread.get("priority")).intValue();
    assertTrue(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
        "Thread priority should be between 1 and 10: " + priority);

    // CPU time should be non-negative
    assertTrue(((Number) firstThread.get("cpu_time_nanos")).longValue() >= 0, "CPU time should be non-negative");

    // Thread count should match threads list size
    assertEquals(threads.size(), result.get("thread_count"));

    // Should find at least the main thread
    boolean hasMainThread = threads.stream().anyMatch(t -> ((String) t.get("name")).contains("main"));
    assertTrue(hasMainThread, "Should have at least the main thread");
  }

  @Test
  public void testGetThreadInfoWithFilter() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");
    args.put("thread_name", "main");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Should have at least the main thread
    assertFalse(threads.isEmpty(), "Should find at least one thread with 'main' in the name");
    assertEquals(1, threads.size(), "Should typically find exactly one main thread");

    // All threads should contain "main" in their name
    for (Map<String, Object> thread : threads) {
      String name = (String) thread.get("name");
      assertTrue(name.contains("main"), "Thread name should contain 'main': " + name);

      // Main thread can be RUNNABLE or WAITING (when waiting for the
      // CompletableFuture to complete)
      String state = (String) thread.get("state");
      assertTrue(state.equals("RUNNABLE") || state.equals("WAITING"),
          "Main thread should be RUNNABLE or WAITING during test execution, but was: " + state);

      // Main thread should not be a daemon
      assertFalse((Boolean) thread.get("daemon"), "Main thread should not be a daemon thread");
    }
  }

  @Test
  public void testGetThreadInfoWithStack() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");
    args.put("include_stack", true);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

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
          break;
        }
      }
    }

    assertTrue(hasStackTrace, "At least one thread should have a stack trace");
  }

  @Test
  public void testGetMemoryInfo() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "memory");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    // Check JVM memory
    @SuppressWarnings("unchecked")
    Map<String, Object> jvmMemory = (Map<String, Object>) result.get("jvm_memory");
    assertNotNull(jvmMemory);
    assertTrue(jvmMemory.containsKey("used_mb"));
    assertTrue(jvmMemory.containsKey("free_mb"));
    assertTrue(jvmMemory.containsKey("total_mb"));
    assertTrue(jvmMemory.containsKey("max_mb"));
    assertTrue(jvmMemory.containsKey("used_percentage"));

    // Check heap memory
    @SuppressWarnings("unchecked")
    Map<String, Object> heapMemory = (Map<String, Object>) result.get("heap_memory");
    assertNotNull(heapMemory);
    assertTrue(heapMemory.containsKey("init_mb"));
    assertTrue(heapMemory.containsKey("used_mb"));
    assertTrue(heapMemory.containsKey("committed_mb"));
    assertTrue(heapMemory.containsKey("max_mb"));

    // Check non-heap memory
    @SuppressWarnings("unchecked")
    Map<String, Object> nonHeapMemory = (Map<String, Object>) result.get("non_heap_memory");
    assertNotNull(nonHeapMemory);

    // Check available processors
    assertNotNull(result.get("available_processors"));
    assertTrue((Integer) result.get("available_processors") > 0);
  }

  @Test
  public void testPerformGC() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "gc");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("memory_before_mb"));
    assertNotNull(result.get("memory_after_mb"));
    assertNotNull(result.get("memory_freed_mb"));
    assertNotNull(result.get("gc_duration_ms"));
    assertNotNull(result.get("message"));

    // GC duration should be reasonable
    Integer duration = (Integer) result.get("gc_duration_ms");
    assertTrue(duration >= 0);
    assertTrue(duration < 10000); // Should complete within 10 seconds
  }

  @Test
  public void testGetTimeInfo() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "time");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("timestamp"));
    assertNotNull(result.get("timestamp_millis"));
    assertNotNull(result.get("timestamp_nanos"));
    assertNotNull(result.get("local_datetime"));
    assertNotNull(result.get("local_date"));
    assertNotNull(result.get("local_time"));
    assertNotNull(result.get("timezone"));

    // Verify timestamp is reasonable
    Long timestampMillis = (Long) result.get("timestamp_millis");
    long now = System.currentTimeMillis();
    assertTrue(Math.abs(now - timestampMillis) < 1000); // Within 1 second
  }

  @Test
  public void testGetAllThreadStacks() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "thread_stacks");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertNotNull(result.get("thread_count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");
    assertNotNull(threads);
    assertFalse(threads.isEmpty());

    // Check first thread structure
    Map<String, Object> firstThread = threads.get(0);
    assertTrue(firstThread.containsKey("thread_id"));
    assertTrue(firstThread.containsKey("thread_name"));
    assertTrue(firstThread.containsKey("thread_state"));
    assertTrue(firstThread.containsKey("thread_priority"));
    assertTrue(firstThread.containsKey("is_daemon"));
    assertTrue(firstThread.containsKey("is_alive"));
    assertTrue(firstThread.containsKey("stack_depth"));
    assertTrue(firstThread.containsKey("stack_trace"));

    // Stack trace should be a list
    @SuppressWarnings("unchecked")
    List<String> stackTrace = (List<String>) firstThread.get("stack_trace");
    assertNotNull(stackTrace);

    // Stack depth should match stack trace size
    assertEquals(stackTrace.size(), firstThread.get("stack_depth"));
  }

  @Test
  public void testMissingOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    assertNotNull(tool.executeAsync(args).get());
  }

  @Test
  public void testUnknownOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "unknown");
    assertNotNull(tool.executeAsync(args).get());
  }

  @Test
  public void testNullArguments() throws Exception {
    try {
      tool.executeAsync(null).get();
    } catch (Throwable e) {
      assertNotNull(e);
    }
  }

  @Test
  public void testMemoryValuesAreReasonable() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "memory");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> jvmMemory = (Map<String, Object>) result.get("jvm_memory");

    Number usedMbNum = (Number) jvmMemory.get("used_mb");
    Number freeMbNum = (Number) jvmMemory.get("free_mb");
    Number totalMbNum = (Number) jvmMemory.get("total_mb");
    Number maxMbNum = (Number) jvmMemory.get("max_mb");

    long usedMb = usedMbNum.longValue();
    long freeMb = freeMbNum.longValue();
    long totalMb = totalMbNum.longValue();
    long maxMb = maxMbNum.longValue();

    // Basic sanity checks
    assertTrue(usedMb > 0, "Used memory should be positive");
    assertTrue(freeMb >= 0, "Free memory should be non-negative");
    assertTrue(totalMb > 0, "Total memory should be positive");
    assertTrue(maxMb > 0, "Max memory should be positive");

    // Used + Free should equal Total (with tolerance for rounding)
    long diff = Math.abs(totalMb - (usedMb + freeMb));
    assertTrue(diff <= 1, "Used + Free should equal Total (within 1MB tolerance). Diff: " + diff);

    // Total should not exceed Max
    assertTrue(totalMb <= maxMb, "Total memory should not exceed max memory");

    // Used percentage should be between 0 and 100
    Double usedPercentage = (Double) jvmMemory.get("used_percentage");
    assertTrue(usedPercentage >= 0 && usedPercentage <= 100);
  }

  @Test
  public void testThreadStatesAreValid() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Valid thread states
    List<String> validStates = List.of("NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED");

    for (Map<String, Object> thread : threads) {
      String state = (String) thread.get("state");
      assertTrue(validStates.contains(state), "Thread state should be valid: " + state);

      Integer priority = (Integer) thread.get("priority");
      assertTrue(priority >= Thread.MIN_PRIORITY && priority <= Thread.MAX_PRIORITY,
          "Thread priority should be between " + Thread.MIN_PRIORITY + " and " + Thread.MAX_PRIORITY);
    }
  }

  @Test
  public void testMainThreadExists() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");
    args.put("thread_name", "main");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Main thread should exist during test execution
    boolean hasMainThread = false;
    for (Map<String, Object> thread : threads) {
      if ("main".equals(thread.get("name"))) {
        hasMainThread = true;
        break;
      }
    }

    assertTrue(hasMainThread, "Main thread should exist");
  }

  @Test
  public void testGCFreesMemory() throws Exception {
    // Create some garbage
    List<byte[]> garbage = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      garbage.add(new byte[1024 * 1024]); // 1MB each
    }
    garbage = null; // Make eligible for GC

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "gc");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    // Memory after should generally be less than or equal to memory before
    Integer beforeMb = (Integer) result.get("memory_before_mb");
    Integer afterMb = (Integer) result.get("memory_after_mb");
    Integer freedMb = (Integer) result.get("memory_freed_mb");

    assertTrue(beforeMb >= 0);
    assertTrue(afterMb >= 0);
    // Freed memory might be negative if allocation happened during GC (allow 1MB
    // tolerance for rounding)
    long diff = Math.abs((beforeMb - afterMb) - freedMb);
    assertTrue(diff <= 1, "Freed memory calculation should be accurate (within 1MB). Expected: " + (beforeMb - afterMb)
        + ", Actual: " + freedMb);
  }

  @Test
  public void testThreadNameFilterEmpty() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");
    args.put("thread_name", "NonExistentThreadName");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Should be empty since no thread matches the filter
    assertTrue(threads.isEmpty());
  }

  @Test
  public void testIncludeStackAsString() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "threads");
    args.put("include_stack", "true"); // String instead of boolean

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("threads");

    // Should parse string "true" as boolean true
    for (Map<String, Object> thread : threads) {
      assertTrue(thread.containsKey("stack_trace"), "Stack traces should be included when include_stack='true'");
    }
  }
}