package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.bitsapplied.descartes.util.ParameterUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thread inspect operation - provides detailed information for specific
 * threads. Supports thread_ids or thread_names parameters with full details
 * including stacks, locks, and monitors.
 */
public class ThreadInspectOperation extends AbstractThreadOperation {

  public ThreadInspectOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults) {
    super(threadMXBean, executor, threadListFilters, threadSearchFilters, objectMapper, maxResponseSizeBytes,
        maxThreadsPerInspect, defaultMaxResults);
  }

  @Override
  public String getOperationName() {
    return "thread_inspect";
  }

  @Override
  public CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args), executor);
  }

  private Map<String, Object> execute(Map<String, Object> args) {
    // Get thread IDs to inspect
    List<Long> threadIds = getThreadIdsToInspect(args);

    if (threadIds.isEmpty()) {
      // Check which parameter was provided to give specific guidance
      boolean hasThreadIds = args.containsKey("thread_ids");
      boolean hasThreadNames = args.containsKey("thread_names");

      if (hasThreadNames) {
        // thread_names was provided but no matches found
        Object namesObj = args.get("thread_names");
        throw new IllegalArgumentException(String.format("No threads found matching thread_names: %s. "
            + "Use thread_list operation to see all available thread names.", namesObj));
      } else if (hasThreadIds) {
        // Shouldn't reach here - would have thrown earlier in getThreadIdsToInspect
        throw new IllegalArgumentException("thread_ids was provided but no valid thread IDs found");
      } else {
        // Neither parameter provided
        throw new IllegalArgumentException("thread_ids or thread_names parameter required for thread_inspect. "
            + "Examples: thread_ids=[42] or thread_ids=[42,57,103] or thread_names=\"main\" or thread_names=[\"main\",\"pool-1\"]");
      }
    }

    if (threadIds.size() > maxThreadsPerInspect) {
      throw new IllegalArgumentException(String.format(
          "Too many threads requested: %d (max %d). "
              + "To inspect more threads, use thread_search with include_details=true and appropriate filtering, "
              + "or make multiple thread_inspect calls with different thread IDs.",
          threadIds.size(), maxThreadsPerInspect));
    }

    // Get parameters
    boolean includeStack = ParameterUtils.getBoolean(args, "include_stack", true);
    int maxStackDepth = ParameterUtils.getInt(args, "max_stack_depth", 20);
    boolean includeLocks = ParameterUtils.getBoolean(args, "include_locks", true);
    boolean includeMonitors = ParameterUtils.getBoolean(args, "include_monitors", true);
    boolean includeSynchronizers = ParameterUtils.getBoolean(args, "include_synchronizers", false);
    String filterStackPattern = ParameterUtils.getString(args, "filter_stack_pattern", null);

    // Get full details for specified threads only
    ThreadInfo[] threads = threadMXBean.getThreadInfo(threadIds.stream().mapToLong(Long::longValue).toArray(),
        includeStack ? maxStackDepth : 0);

    // Build detailed response with size tracking
    List<Map<String, Object>> threadDetails = new ArrayList<>();
    int approximateSize = 0;
    int included = 0;
    boolean truncated = false;

    for (ThreadInfo info : threads) {
      if (info == null)
        continue;

      Map<String, Object> threadData = buildThreadDetail(info, includeStack, maxStackDepth, includeLocks,
          includeMonitors, includeSynchronizers, filterStackPattern, this::formatStackTrace);

      // Estimate JSON size
      int threadSize = estimateJsonSize(threadData);
      if (approximateSize + threadSize > maxResponseSizeBytes) {
        truncated = true;
        break;
      }

      threadDetails.add(threadData);
      approximateSize += threadSize;
      included++;
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("requested_threads", threadIds.size());
    result.put("found_threads", (int) Arrays.stream(threads).filter(t -> t != null).count());
    result.put("returned_threads", included);
    result.put("threads", threadDetails);

    if (truncated) {
      result.put("truncated", true);
      result.put("truncation_reason", "Response size limit reached (" + maxResponseSizeBytes + " bytes)");
      result.put("suggestion",
          "Try: 1) Reducing max_stack_depth, 2) Using filter_stack_pattern to show only app frames, "
              + "3) Inspecting fewer threads per call, 4) Disabling include_locks/include_monitors");
    }

    return result;
  }
}
