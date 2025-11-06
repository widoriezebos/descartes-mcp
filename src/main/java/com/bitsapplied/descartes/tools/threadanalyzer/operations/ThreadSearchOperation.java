package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.bitsapplied.descartes.util.ParameterUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thread search operation - find threads by criteria with optional detailed information.
 * Supports name_contains, state_in, daemon, min_cpu_time_ms filters.
 */
public class ThreadSearchOperation extends AbstractThreadOperation {

  public ThreadSearchOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults) {
    super(threadMXBean, executor, threadListFilters, threadSearchFilters, objectMapper, maxResponseSizeBytes,
        maxThreadsPerInspect, defaultMaxResults);
  }

  @Override
  public String getOperationName() {
    return "thread_search";
  }

  @Override
  public CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args), executor);
  }

  private Map<String, Object> execute(Map<String, Object> args) {
    // Get all threads
    long[] allThreadIds = threadMXBean.getAllThreadIds();

    boolean includeDetails = ParameterUtils.getBoolean(args, "include_details", false);
    int maxStackDepth = ParameterUtils.getInt(args, "max_stack_depth", 10);

    // Get thread info (with or without stacks based on includeDetails)
    ThreadInfo[] allThreads = threadMXBean.getThreadInfo(allThreadIds, includeDetails ? maxStackDepth : 0);

    // Apply search criteria
    List<ThreadInfo> matched = applySearchCriteria(allThreads, args);

    // Limit results
    int maxResults = ParameterUtils.getInt(args, "max_results", 20);
    int totalMatched = matched.size();
    matched = matched.subList(0, Math.min(matched.size(), maxResults));

    // Build response with size protection
    List<Map<String, Object>> results = new ArrayList<>();
    int approximateSize = 0;
    boolean truncated = false;

    if (includeDetails) {
      // Return full details with size tracking
      for (ThreadInfo info : matched) {
        Map<String, Object> threadData = buildThreadDetail(info, true, maxStackDepth, true, true, false, null,
            this::formatStackTrace);

        // Estimate JSON size
        int threadSize = estimateJsonSize(threadData);
        if (approximateSize + threadSize > maxResponseSizeBytes) {
          truncated = true;
          break;
        }

        results.add(threadData);
        approximateSize += threadSize;
      }
    } else {
      // Return summary (no size concerns)
      for (ThreadInfo info : matched) {
        results.add(buildThreadSummary(info));
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("total_threads", allThreadIds.length);
    result.put("matched_threads", totalMatched);
    result.put("returned_threads", results.size());
    result.put("include_details", includeDetails);
    result.put("threads", results);

    if (truncated) {
      result.put("truncated", true);
      result.put("truncation_reason", "Response size limit reached (" + maxResponseSizeBytes + " bytes)");
      result.put("suggestion",
          "Try reducing max_results, max_stack_depth, or use filter_stack_pattern to reduce response size");
    }

    return result;
  }
}
