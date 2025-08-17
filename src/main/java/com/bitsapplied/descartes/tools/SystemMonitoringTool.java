package com.bitsapplied.descartes.tools;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for system monitoring and diagnostics. Provides access to thread
 * information, memory statistics, and system time.
 */
public class SystemMonitoringTool implements MCPTool {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getToolName() {
    return "system_monitoring";
  }

  @Override
  public String getToolDescription() {
    return "Real-time JVM system resource monitoring tool for tracking application health and performance. "
        + "Monitors active thread counts and states, heap/non-heap memory consumption with usage percentages, "
        + "triggers manual garbage collection for memory management, provides system time and uptime information, "
        + "and captures thread stack traces for debugging. Essential for identifying resource constraints, "
        + "monitoring application health, and troubleshooting performance issues in production environments.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum", List.of("threads", "memory", "gc", "time", "thread_stacks"), "description",
                "The monitoring operation to perform"),
            "thread_name",
            Map.of("type", "string", "description", "Thread name pattern for filtering (for threads operation)"),
            "include_stack", Map.of("type", "boolean", "description",
                "Include stack traces for threads (default false)", "default", false)),
        "required", List.of("operation"));
  }

  @Override
  public String executeTool(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      throw new IllegalArgumentException("Operation is required");
    }

    Map<String, Object> result = switch (operation) {
    case "threads" -> {
      String threadName = (String) arguments.get("thread_name");
      Object includeStackObj = arguments.getOrDefault("include_stack", false);
      boolean includeStack = false;
      if (includeStackObj instanceof Boolean) {
        includeStack = (Boolean) includeStackObj;
      } else if (includeStackObj instanceof String) {
        includeStack = Boolean.parseBoolean((String) includeStackObj);
      }
      yield getThreadInfo(threadName, includeStack);
    }
    case "memory" -> getMemoryInfo();
    case "gc" -> performGC();
    case "time" -> getTimeInfo();
    case "thread_stacks" -> getAllThreadStacks();
    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
    };

    return objectMapper.writeValueAsString(result);
  }

  /**
   * Get information about system threads.
   */
  private Map<String, Object> getThreadInfo(String nameFilter, boolean includeStack) {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);

    List<Map<String, Object>> threads = new ArrayList<>();

    for (ThreadInfo info : threadInfos) {
      String threadName = info.getThreadName();

      // Apply filter if provided
      if (nameFilter != null && !threadName.contains(nameFilter)) {
        continue;
      }

      Map<String, Object> threadData = new HashMap<>();
      threadData.put("id", info.getThreadId());
      threadData.put("name", threadName);
      threadData.put("state", info.getThreadState().toString());
      threadData.put("priority", info.getPriority());
      threadData.put("daemon", info.isDaemon());
      threadData.put("cpu_time_nanos", threadMXBean.getThreadCpuTime(info.getThreadId()));

      if (info.getLockName() != null) {
        threadData.put("lock_name", info.getLockName());
      }

      if (info.getLockOwnerName() != null) {
        threadData.put("lock_owner", info.getLockOwnerName());
      }

      if (includeStack) {
        StackTraceElement[] stack = info.getStackTrace();
        List<String> stackLines = new ArrayList<>();
        for (StackTraceElement element : stack) {
          stackLines.add(element.toString());
        }
        threadData.put("stack_trace", stackLines);
      }

      threads.add(threadData);
    }

    return Map.of("status", "success", "thread_count", threads.size(), "threads", threads);
  }

  /**
   * Get memory usage information.
   */
  private Map<String, Object> getMemoryInfo() {
    Runtime runtime = Runtime.getRuntime();
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = runtime.maxMemory();

    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

    return Map.of("status", "success", "jvm_memory",
        Map.of("used_mb", usedMemory / (1024 * 1024), "free_mb", freeMemory / (1024 * 1024), "total_mb",
            totalMemory / (1024 * 1024), "max_mb", maxMemory / (1024 * 1024), "used_percentage",
            (double) usedMemory / totalMemory * 100),
        "heap_memory",
        Map.of("init_mb", heapUsage.getInit() / (1024 * 1024), "used_mb", heapUsage.getUsed() / (1024 * 1024),
            "committed_mb", heapUsage.getCommitted() / (1024 * 1024), "max_mb", heapUsage.getMax() / (1024 * 1024)),
        "non_heap_memory",
        Map.of("init_mb", nonHeapUsage.getInit() / (1024 * 1024), "used_mb", nonHeapUsage.getUsed() / (1024 * 1024),
            "committed_mb", nonHeapUsage.getCommitted() / (1024 * 1024), "max_mb",
            nonHeapUsage.getMax() == -1 ? "unlimited" : nonHeapUsage.getMax() / (1024 * 1024)),
        "available_processors", runtime.availableProcessors());
  }

  /**
   * Perform garbage collection.
   */
  private Map<String, Object> performGC() {
    Runtime runtime = Runtime.getRuntime();

    long beforeUsed = runtime.totalMemory() - runtime.freeMemory();
    long beforeTime = System.currentTimeMillis();

    System.gc();

    // Wait a bit for GC to complete
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    long afterUsed = runtime.totalMemory() - runtime.freeMemory();
    long afterTime = System.currentTimeMillis();

    long freedMemory = beforeUsed - afterUsed;

    return Map.of("status", "success", "memory_before_mb", beforeUsed / (1024 * 1024), "memory_after_mb",
        afterUsed / (1024 * 1024), "memory_freed_mb", freedMemory / (1024 * 1024), "gc_duration_ms",
        afterTime - beforeTime, "message",
        String.format("Garbage collection completed. Freed %d MB", freedMemory / (1024 * 1024)));
  }

  /**
   * Get current system time information.
   */
  private Map<String, Object> getTimeInfo() {
    Instant now = Instant.now();
    LocalDateTime localDateTime = LocalDateTime.ofInstant(now, ZoneId.systemDefault());

    return Map.of("status", "success", "timestamp", now.toString(), "timestamp_millis", System.currentTimeMillis(),
        "timestamp_nanos", System.nanoTime(), "local_datetime", localDateTime.toString(), "local_date",
        localDateTime.toLocalDate().toString(), "local_time", localDateTime.toLocalTime().toString(), "timezone",
        ZoneId.systemDefault().toString());
  }

  /**
   * Get stack traces for all threads.
   */
  private Map<String, Object> getAllThreadStacks() {
    Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
    List<Map<String, Object>> threadStacks = new ArrayList<>();

    for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
      Thread thread = entry.getKey();
      StackTraceElement[] stack = entry.getValue();

      List<String> stackLines = new ArrayList<>();
      for (StackTraceElement element : stack) {
        stackLines.add(element.toString());
      }

      threadStacks.add(Map.of("thread_id", thread.threadId(), "thread_name", thread.getName(), "thread_state",
          thread.getState().toString(), "thread_priority", thread.getPriority(), "is_daemon", thread.isDaemon(),
          "is_alive", thread.isAlive(), "stack_depth", stack.length, "stack_trace", stackLines));
    }

    return Map.of("status", "success", "thread_count", threadStacks.size(), "threads", threadStacks);
  }
}