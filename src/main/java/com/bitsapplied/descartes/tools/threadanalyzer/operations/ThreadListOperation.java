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
 * Thread list operation - provides lightweight thread summaries without stack
 * traces. Supports filtering, sorting, and limiting results.
 */
public class ThreadListOperation extends AbstractThreadOperation {

  public ThreadListOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults) {
    super(threadMXBean, executor, threadListFilters, threadSearchFilters, objectMapper, maxResponseSizeBytes,
        maxThreadsPerInspect, defaultMaxResults);
  }

  @Override
  public String getOperationName() {
    return "thread_list";
  }

  @Override
  public CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args), executor);
  }

  private Map<String, Object> execute(Map<String, Object> args) {
    // Get all thread IDs
    long[] allThreadIds = threadMXBean.getAllThreadIds();

    // Get basic info WITHOUT stack traces (maxDepth=0)
    ThreadInfo[] allThreads = threadMXBean.getThreadInfo(allThreadIds, 0);

    // Apply filters
    List<ThreadInfo> filtered = applyThreadFilters(allThreads, args);

    // Sort
    String sortBy = ParameterUtils.getString(args, "sort_by", "name");
    boolean descending = ParameterUtils.getBoolean(args, "descending", true);
    filtered = sortThreads(filtered, sortBy, descending);

    // Limit results
    int maxResults = ParameterUtils.getInt(args, "max_results", defaultMaxResults);
    int totalMatched = filtered.size();
    filtered = filtered.subList(0, Math.min(filtered.size(), maxResults));

    // Build minimal response (no stacks!)
    List<Map<String, Object>> threadSummaries = new ArrayList<>();
    for (ThreadInfo info : filtered) {
      threadSummaries.add(buildThreadSummary(info));
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("total_threads", allThreadIds.length);
    result.put("matched_threads", totalMatched);
    result.put("returned_threads", threadSummaries.size());
    result.put("threads", threadSummaries);

    return result;
  }
}
