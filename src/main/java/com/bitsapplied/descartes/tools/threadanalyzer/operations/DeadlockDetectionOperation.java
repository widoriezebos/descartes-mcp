package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.bitsapplied.descartes.util.ParameterUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deadlock detection operation - finds circular dependencies between threads.
 * Detects both monitor deadlocks and ownable synchronizer deadlocks.
 */
public class DeadlockDetectionOperation extends AbstractThreadOperation {

  public DeadlockDetectionOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults) {
    super(threadMXBean, executor, threadListFilters, threadSearchFilters, objectMapper, maxResponseSizeBytes,
        maxThreadsPerInspect, defaultMaxResults);
  }

  @Override
  public String getOperationName() {
    return "deadlocks";
  }

  @Override
  public CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args), executor);
  }

  private Map<String, Object> execute(Map<String, Object> args) {
    boolean includeStack = ParameterUtils.getBoolean(args, "include_stack", true);
    int maxStackDepth = ParameterUtils.getInt(args, "max_stack_depth", 20);

    // findDeadlockedThreads() finds deadlocks for both monitors and ownable synchronizers
    long[] deadlockedThreadIds = threadMXBean.findDeadlockedThreads();

    Set<Long> allDeadlocked = new HashSet<>();
    if (deadlockedThreadIds != null) {
      for (long id : deadlockedThreadIds) {
        allDeadlocked.add(id);
      }
    }

    if (allDeadlocked.isEmpty()) {
      return Map.of("status", "success", "deadlocks_found", false, "message", "No deadlocks detected");
    }

    // Get detailed information about deadlocked threads
    ThreadInfo[] deadlockedThreads = threadMXBean
        .getThreadInfo(allDeadlocked.stream().mapToLong(Long::longValue).toArray(), includeStack ? maxStackDepth : 0);

    List<Map<String, Object>> deadlockChains = analyzeDeadlockChains(deadlockedThreads, includeStack, maxStackDepth);

    return Map.of("status", "success", "deadlocks_found", true, "deadlocked_thread_count", allDeadlocked.size(),
        "deadlock_chains", deadlockChains, "deadlocked_thread_ids", allDeadlocked);
  }
}
