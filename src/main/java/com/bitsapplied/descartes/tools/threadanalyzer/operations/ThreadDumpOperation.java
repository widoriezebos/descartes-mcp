package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.bitsapplied.descartes.util.ParameterUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thread dump operation - produces full text-based thread dumps for offline
 * analysis. Supports filtering by name_pattern, state_filter, and
 * filter_stack_pattern.
 */
public class ThreadDumpOperation extends AbstractThreadOperation {

  public ThreadDumpOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults) {
    super(threadMXBean, executor, threadListFilters, threadSearchFilters, objectMapper, maxResponseSizeBytes,
        maxThreadsPerInspect, defaultMaxResults);
  }

  @Override
  public String getOperationName() {
    return "thread_dump";
  }

  @Override
  public CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args), executor);
  }

  private Map<String, Object> execute(Map<String, Object> args) {
    int maxStackDepth = ParameterUtils.getInt(args, "max_stack_depth", 50);
    String filterStackPattern = ParameterUtils.getString(args, "filter_stack_pattern", null);
    String namePattern = ParameterUtils.getString(args, "name_pattern", null);
    List<String> stateFilter = ParameterUtils.getStringList(args, "state_filter");

    ThreadInfo[] allThreadInfos = threadMXBean.dumpAllThreads(true, true);

    // Warn about large dumps
    boolean hasFiltering = namePattern != null || (stateFilter != null && !stateFilter.isEmpty())
        || filterStackPattern != null;
    String sizeWarning = null;

    if (!hasFiltering && allThreadInfos.length > 100) {
      sizeWarning = String.format(
          "Warning: Dumping %d threads without filtering may produce very large output (500KB+). "
              + "Consider using name_pattern, state_filter, or filter_stack_pattern to reduce size.",
          allThreadInfos.length);
    }

    // Apply thread filters
    List<ThreadInfo> filteredThreads = new ArrayList<>();
    Pattern nameRegex = namePattern != null ? safeCompilePattern(namePattern, "name_pattern") : null;
    Set<Thread.State> stateSet = stateFilter != null && !stateFilter.isEmpty()
        ? stateFilter.stream().map(Thread.State::valueOf).collect(Collectors.toSet())
        : null;

    for (ThreadInfo info : allThreadInfos) {
      if (info == null)
        continue;

      // Apply name filter
      if (nameRegex != null && !nameRegex.matcher(info.getThreadName()).find()) {
        continue;
      }

      // Apply state filter
      if (stateSet != null && !stateSet.contains(info.getThreadState())) {
        continue;
      }

      filteredThreads.add(info);
    }

    StringBuilder dump = new StringBuilder();
    dump.append(String.format("Full thread dump %s (%s %s):\n", System.getProperty("java.vm.name"),
        System.getProperty("java.vm.version"), System.getProperty("java.vm.info")));

    if (namePattern != null || stateFilter != null) {
      dump.append(
          String.format("Filtered: %d threads (from %d total)\n", filteredThreads.size(), allThreadInfos.length));
      if (namePattern != null) {
        dump.append(String.format("  name_pattern: %s\n", namePattern));
      }
      if (stateFilter != null && !stateFilter.isEmpty()) {
        dump.append(String.format("  state_filter: %s\n", stateFilter));
      }
      if (filterStackPattern != null) {
        dump.append(String.format("  filter_stack_pattern: %s\n", filterStackPattern));
      }
    }
    dump.append("\n");

    for (ThreadInfo info : filteredThreads) {
      dump.append(formatThreadInfo(info, maxStackDepth, filterStackPattern));
      dump.append("\n");
    }

    // Add deadlock information (always include, not filtered)
    long[] deadlocked = threadMXBean.findDeadlockedThreads();
    if (deadlocked != null && deadlocked.length > 0) {
      dump.append("\n===== DEADLOCK DETECTED =====\n");
      ThreadInfo[] deadlockedThreads = threadMXBean.getThreadInfo(deadlocked, maxStackDepth);
      for (ThreadInfo info : deadlockedThreads) {
        if (info != null) {
          dump.append(formatThreadInfo(info, maxStackDepth, filterStackPattern));
          dump.append("\n");
        }
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("total_threads", allThreadInfos.length);
    result.put("filtered_threads", filteredThreads.size());
    result.put("thread_dump", dump.toString());
    result.put("timestamp", System.currentTimeMillis());

    if (sizeWarning != null) {
      result.put("size_warning", sizeWarning);
    }

    return result;
  }
}
