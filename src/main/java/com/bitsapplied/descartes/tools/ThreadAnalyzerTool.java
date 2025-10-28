package com.bitsapplied.descartes.tools;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for comprehensive thread analysis including deadlock detection, lock
 * analysis, and thread state monitoring.
 */
public class ThreadAnalyzerTool implements MCPTool {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  public ThreadAnalyzerTool() {
    // Enable thread contention monitoring if available
    if (threadMXBean.isThreadContentionMonitoringSupported()) {
      threadMXBean.setThreadContentionMonitoringEnabled(true);
    }
    // Enable CPU time monitoring if available
    if (threadMXBean.isThreadCpuTimeSupported()) {
      threadMXBean.setThreadCpuTimeEnabled(true);
    }
  }

  @Override
  public String getToolName() {
    return "thread_analyzer";
  }

  @Override
  public String getToolDescription() {
    return "Advanced thread analysis and deadlock detection tool for JVM applications. "
        + "Monitors thread states (RUNNABLE, BLOCKED, WAITING), detects circular dependencies causing deadlocks, "
        + "analyzes lock contention and synchronization issues, tracks CPU time per thread, and identifies performance bottlenecks. "
        + "Essential for debugging concurrency issues, optimizing thread pool sizes, and ensuring application responsiveness. "
        + "Includes thread contention monitoring and CPU time tracking when supported by JVM.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();

    // Operation
    properties.put("operation", Map.of("type", "string", "enum",
        List.of("thread_list", "thread_inspect", "thread_search", "deadlocks", "thread_dump"), "description",
        "Operation: thread_list (lightweight summary), thread_inspect (detailed view), thread_search (find+inspect), deadlocks (detect deadlocks), thread_dump (full text dump)"));

    // thread_list parameters
    properties.put("state_filter", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Filter by thread states: RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, NEW, TERMINATED"));
    properties.put("name_pattern", Map.of("type", "string", "description", "Filter by thread name regex pattern"));
    properties.put("min_cpu_time_ms",
        Map.of("type", "integer", "description", "Filter threads by minimum CPU time (milliseconds)"));
    properties.put("sort_by", Map.of("type", "string", "enum", List.of("cpu_time", "name", "id", "state"),
        "description", "Sort field", "default", "cpu_time"));
    properties.put("descending", Map.of("type", "boolean", "description", "Sort in descending order", "default", true));
    properties.put("max_results", Map.of("type", "integer", "description", "Maximum threads to return", "default", 50));

    // thread_inspect parameters
    properties.put("thread_ids", Map.of("type", "array", "items", Map.of("type", "integer"), "description",
        "Thread IDs to inspect (required for thread_inspect)"));
    properties.put("thread_names", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Thread names to inspect (alternative to thread_ids)"));
    properties.put("include_stack", Map.of("type", "boolean", "description", "Include stack traces", "default", true));
    properties.put("max_stack_depth",
        Map.of("type", "integer", "description", "Maximum stack trace depth", "default", 20));
    properties.put("include_locks",
        Map.of("type", "boolean", "description", "Include lock information", "default", true));
    properties.put("include_monitors",
        Map.of("type", "boolean", "description", "Include monitor information", "default", true));
    properties.put("include_synchronizers",
        Map.of("type", "boolean", "description", "Include synchronizers", "default", false));
    properties.put("filter_stack_pattern",
        Map.of("type", "string", "description", "Regex to filter stack frames (only matching frames)"));

    // thread_search parameters
    properties.put("name_contains", Map.of("type", "string", "description", "Thread name substring match"));
    properties.put("state_in",
        Map.of("type", "array", "items", Map.of("type", "string"), "description", "Thread states to match"));
    properties.put("daemon", Map.of("type", "boolean", "description", "Filter by daemon flag"));
    properties.put("include_details",
        Map.of("type", "boolean", "description", "Return full details instead of summary", "default", false));

    return Map.of("type", "object", "properties", properties, "required", List.of("operation"));
  }

  @Override
  public String executeTool(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      throw new IllegalArgumentException("Operation is required");
    }

    Map<String, Object> result = switch (operation) {
    case "thread_list" -> handleThreadList(arguments);
    case "thread_inspect" -> handleThreadInspect(arguments);
    case "thread_search" -> handleThreadSearch(arguments);
    case "deadlocks" -> handleDeadlocks(arguments);
    case "thread_dump" -> handleThreadDump(arguments);
    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
    };

    return objectMapper.writeValueAsString(result);
  }

  /**
   * Handle deadlocks operation - detect circular dependencies.
   */
  private Map<String, Object> handleDeadlocks(Map<String, Object> args) {
    boolean includeStack = getBooleanParam(args, "include_stack", true);
    int maxStackDepth = getIntParam(args, "max_stack_depth", 20);
    // findDeadlockedThreads() finds deadlocks for both monitors and ownable
    // synchronizers
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

  /**
   * Analyze deadlock chains to show circular dependencies.
   */
  private List<Map<String, Object>> analyzeDeadlockChains(ThreadInfo[] deadlockedThreads, boolean includeStack,
      int maxStackDepth) {
    List<Map<String, Object>> chains = new ArrayList<>();
    Set<Long> processed = new HashSet<>();

    for (ThreadInfo thread : deadlockedThreads) {
      if (thread == null || processed.contains(thread.getThreadId())) {
        continue;
      }

      List<Map<String, Object>> chain = new ArrayList<>();
      ThreadInfo current = thread;
      Set<Long> chainIds = new HashSet<>();

      while (current != null && !chainIds.contains(current.getThreadId())) {
        chainIds.add(current.getThreadId());
        processed.add(current.getThreadId());

        Map<String, Object> threadInfo = new HashMap<>();
        threadInfo.put("thread_id", current.getThreadId());
        threadInfo.put("thread_name", current.getThreadName());
        threadInfo.put("thread_state", current.getThreadState().toString());

        LockInfo lockInfo = current.getLockInfo();
        if (lockInfo != null) {
          threadInfo.put("waiting_on", Map.of("class_name", lockInfo.getClassName(), "identity_hash",
              Integer.toHexString(lockInfo.getIdentityHashCode())));
          threadInfo.put("waiting_for_thread_id", current.getLockOwnerId());
          threadInfo.put("waiting_for_thread_name", current.getLockOwnerName());
        }

        if (includeStack) {
          threadInfo.put("stack_trace", formatStackTrace(current.getStackTrace(), maxStackDepth));
        }

        chain.add(threadInfo);

        // Move to the thread that owns the lock we're waiting for
        if (current.getLockOwnerId() > 0) {
          current = threadMXBean.getThreadInfo(current.getLockOwnerId());
        } else {
          break;
        }
      }

      if (!chain.isEmpty()) {
        chains.add(Map.of("chain_length", chain.size(), "is_circular",
            chainIds.contains(current != null ? current.getThreadId() : -1), "threads", chain));
      }
    }

    return chains;
  }

  /**
   * Handle thread_dump operation - full text dump for offline analysis with
   * optional filtering.
   */
  private Map<String, Object> handleThreadDump(Map<String, Object> args) {
    int maxStackDepth = getIntParam(args, "max_stack_depth", 50);
    String filterStackPattern = getStringParam(args, "filter_stack_pattern", null);
    String namePattern = getStringParam(args, "name_pattern", null);
    List<String> stateFilter = getListParam(args, "state_filter");

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
    Pattern nameRegex = namePattern != null ? Pattern.compile(namePattern) : null;
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

  /**
   * Format thread information for text output with optional stack filtering.
   */
  private String formatThreadInfo(ThreadInfo info, int maxStackDepth, String filterStackPattern) {
    StringBuilder sb = new StringBuilder();

    sb.append(String.format("\"%s\" #%d %s prio=%d tid=0x%x state=%s", info.getThreadName(), info.getThreadId(),
        info.isDaemon() ? "daemon" : "", info.getPriority(), info.getThreadId(), info.getThreadState()));

    LockInfo lockInfo = info.getLockInfo();
    if (lockInfo != null) {
      sb.append(String.format("\n   waiting on %s@%s", lockInfo.getClassName(),
          Integer.toHexString(lockInfo.getIdentityHashCode())));

      if (info.getLockOwnerName() != null) {
        sb.append(String.format(" owned by \"%s\" id=%d", info.getLockOwnerName(), info.getLockOwnerId()));
      }
    }

    sb.append("\n");

    // Stack trace with optional filtering
    StackTraceElement[] stack = info.getStackTrace();
    Pattern stackPattern = filterStackPattern != null ? Pattern.compile(filterStackPattern) : null;

    int included = 0;
    int skipped = 0;
    for (int i = 0; i < stack.length && included < maxStackDepth; i++) {
      String frame = stack[i].toString();

      // Apply stack frame filter if specified
      if (stackPattern == null || stackPattern.matcher(frame).find()) {
        sb.append("\tat ").append(frame).append("\n");
        included++;

        // Show monitors at this stack depth
        for (MonitorInfo monitor : info.getLockedMonitors()) {
          if (monitor.getLockedStackDepth() == i) {
            sb.append(String.format("\t- locked %s@%s\n", monitor.getClassName(),
                Integer.toHexString(monitor.getIdentityHashCode())));
          }
        }
      } else {
        skipped++;
      }
    }

    if (filterStackPattern != null && skipped > 0) {
      sb.append(String.format("\t... %d frame%s filtered out\n", skipped, skipped == 1 ? "" : "s"));
    }

    if (stack.length > included + skipped) {
      sb.append(String.format("\t... %d more\n", stack.length - included - skipped));
    }

    // Locked synchronizers
    LockInfo[] synchronizers = info.getLockedSynchronizers();
    if (synchronizers.length > 0) {
      sb.append("\n   Locked synchronizers:\n");
      for (LockInfo sync : synchronizers) {
        sb.append(String.format("\t- %s@%s\n", sync.getClassName(), Integer.toHexString(sync.getIdentityHashCode())));
      }
    }

    return sb.toString();
  }

  // ========== NEW: Progressive Disclosure Operations ==========

  /**
   * Constants for response size limits.
   */
  private static final int MAX_RESPONSE_SIZE_BYTES = 200_000; // 200KB max
  private static final int MAX_THREADS_PER_INSPECT = 50;
  private static final int DEFAULT_MAX_RESULTS = 100;

  /**
   * Handle thread_list operation - lightweight summary.
   */
  private Map<String, Object> handleThreadList(Map<String, Object> args) {
    // Get all thread IDs
    long[] allThreadIds = threadMXBean.getAllThreadIds();

    // Get basic info WITHOUT stack traces (maxDepth=0)
    ThreadInfo[] allThreads = threadMXBean.getThreadInfo(allThreadIds, 0);

    // Apply filters
    List<ThreadInfo> filtered = applyThreadFilters(allThreads, args);

    // Sort
    String sortBy = getStringParam(args, "sort_by", "name");
    boolean descending = getBooleanParam(args, "descending", true);
    filtered = sortThreads(filtered, sortBy, descending);

    // Limit results
    int maxResults = getIntParam(args, "max_results", DEFAULT_MAX_RESULTS);
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

  /**
   * Handle thread_inspect operation - detailed view of specific threads.
   */
  private Map<String, Object> handleThreadInspect(Map<String, Object> args) {
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

    if (threadIds.size() > MAX_THREADS_PER_INSPECT) {
      throw new IllegalArgumentException(String.format(
          "Too many threads requested: %d (max %d). "
              + "To inspect more threads, use thread_search with include_details=true and appropriate filtering, "
              + "or make multiple thread_inspect calls with different thread IDs.",
          threadIds.size(), MAX_THREADS_PER_INSPECT));
    }

    // Get parameters
    boolean includeStack = getBooleanParam(args, "include_stack", true);
    int maxStackDepth = getIntParam(args, "max_stack_depth", 20);
    boolean includeLocks = getBooleanParam(args, "include_locks", true);
    boolean includeMonitors = getBooleanParam(args, "include_monitors", true);
    boolean includeSynchronizers = getBooleanParam(args, "include_synchronizers", false);
    String filterStackPattern = getStringParam(args, "filter_stack_pattern", null);

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
          includeMonitors, includeSynchronizers, filterStackPattern);

      // Estimate JSON size
      int threadSize = estimateJsonSize(threadData);
      if (approximateSize + threadSize > MAX_RESPONSE_SIZE_BYTES) {
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
      result.put("truncation_reason", "Response size limit reached (" + MAX_RESPONSE_SIZE_BYTES + " bytes)");
      result.put("suggestion",
          "Try: 1) Reducing max_stack_depth, 2) Using filter_stack_pattern to show only app frames, "
              + "3) Inspecting fewer threads per call, 4) Disabling include_locks/include_monitors");
    }

    return result;
  }

  /**
   * Handle thread_search operation - find threads by criteria and optionally
   * return details.
   */
  private Map<String, Object> handleThreadSearch(Map<String, Object> args) {
    // Get all threads
    long[] allThreadIds = threadMXBean.getAllThreadIds();

    boolean includeDetails = getBooleanParam(args, "include_details", false);
    int maxStackDepth = getIntParam(args, "max_stack_depth", 10);

    // Get thread info (with or without stacks based on includeDetails)
    ThreadInfo[] allThreads = threadMXBean.getThreadInfo(allThreadIds, includeDetails ? maxStackDepth : 0);

    // Apply search criteria
    List<ThreadInfo> matched = applySearchCriteria(allThreads, args);

    // Limit results
    int maxResults = getIntParam(args, "max_results", 20);
    int totalMatched = matched.size();
    matched = matched.subList(0, Math.min(matched.size(), maxResults));

    // Build response with size protection
    List<Map<String, Object>> results = new ArrayList<>();
    int approximateSize = 0;
    boolean truncated = false;

    if (includeDetails) {
      // Return full details with size tracking
      for (ThreadInfo info : matched) {
        Map<String, Object> threadData = buildThreadDetail(info, true, maxStackDepth, true, true, false, null);

        // Estimate JSON size
        int threadSize = estimateJsonSize(threadData);
        if (approximateSize + threadSize > MAX_RESPONSE_SIZE_BYTES) {
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
      result.put("truncation_reason", "Response size limit reached (" + MAX_RESPONSE_SIZE_BYTES + " bytes)");
      result.put("suggestion",
          "Try reducing max_results, max_stack_depth, or use filter_stack_pattern to reduce response size");
    }

    return result;
  }

  // ========== Utility Methods ==========

  /**
   * Build minimal thread summary (for thread_list).
   */
  private Map<String, Object> buildThreadSummary(ThreadInfo info) {
    Map<String, Object> summary = new HashMap<>();
    summary.put("id", info.getThreadId());
    summary.put("name", info.getThreadName());
    summary.put("state", info.getThreadState().toString());
    summary.put("priority", info.getPriority());
    summary.put("daemon", info.isDaemon());

    // CPU time if available
    if (threadMXBean.isThreadCpuTimeSupported()) {
      long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
      long userTime = threadMXBean.getThreadUserTime(info.getThreadId());
      if (cpuTime >= 0) {
        summary.put("cpu_time_ms", cpuTime / 1_000_000);
        summary.put("user_time_ms", userTime / 1_000_000);
      }
    }

    return summary;
  }

  /**
   * Build detailed thread information (for thread_inspect).
   */
  private Map<String, Object> buildThreadDetail(ThreadInfo info, boolean includeStack, int maxStackDepth,
      boolean includeLocks, boolean includeMonitors, boolean includeSynchronizers, String filterStackPattern) {

    Map<String, Object> detail = new HashMap<>();
    detail.put("id", info.getThreadId());
    detail.put("name", info.getThreadName());
    detail.put("state", info.getThreadState().toString());
    detail.put("priority", info.getPriority());
    detail.put("daemon", info.isDaemon());

    // CPU and timing information
    if (threadMXBean.isThreadCpuTimeSupported()) {
      long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
      long userTime = threadMXBean.getThreadUserTime(info.getThreadId());
      if (cpuTime >= 0) {
        detail.put("cpu_time_ms", cpuTime / 1_000_000);
        detail.put("user_time_ms", userTime / 1_000_000);
      }
    }

    // Lock information
    if (includeLocks) {
      LockInfo lockInfo = info.getLockInfo();
      if (lockInfo != null) {
        detail.put("waiting_on_lock", Map.of("class_name", lockInfo.getClassName(), "identity_hash",
            Integer.toHexString(lockInfo.getIdentityHashCode())));
        detail.put("lock_owner_id", info.getLockOwnerId());
        detail.put("lock_owner_name", info.getLockOwnerName());
      }
    }

    // Contention information
    if (threadMXBean.isThreadContentionMonitoringSupported()) {
      detail.put("blocked_count", info.getBlockedCount());
      detail.put("blocked_time_ms", info.getBlockedTime());
      detail.put("waited_count", info.getWaitedCount());
      detail.put("waited_time_ms", info.getWaitedTime());
    }

    // Monitors
    if (includeMonitors) {
      MonitorInfo[] monitors = info.getLockedMonitors();
      if (monitors.length > 0) {
        List<Map<String, Object>> monitorList = new ArrayList<>();
        for (MonitorInfo monitor : monitors) {
          monitorList.add(Map.of("class_name", monitor.getClassName(), "identity_hash",
              Integer.toHexString(monitor.getIdentityHashCode()), "stack_depth", monitor.getLockedStackDepth()));
        }
        detail.put("locked_monitors", monitorList);
      }
    }

    // Synchronizers
    if (includeSynchronizers) {
      LockInfo[] synchronizers = info.getLockedSynchronizers();
      if (synchronizers.length > 0) {
        List<Map<String, Object>> syncList = new ArrayList<>();
        for (LockInfo sync : synchronizers) {
          syncList.add(Map.of("class_name", sync.getClassName(), "identity_hash",
              Integer.toHexString(sync.getIdentityHashCode())));
        }
        detail.put("owned_synchronizers", syncList);
      }
    }

    // Stack trace
    if (includeStack) {
      List<String> stackTrace = formatStackTrace(info.getStackTrace(), maxStackDepth, filterStackPattern);
      detail.put("stack_trace", stackTrace);
      detail.put("stack_depth", stackTrace.size());
    }

    return detail;
  }

  /**
   * Apply filters to thread list.
   */
  private List<ThreadInfo> applyThreadFilters(ThreadInfo[] threads, Map<String, Object> args) {
    List<ThreadInfo> result = new ArrayList<>();

    for (ThreadInfo info : threads) {
      if (info == null)
        continue;
      result.add(info);
    }

    // State filter
    List<String> stateFilter = getListParam(args, "state_filter");
    if (stateFilter != null && !stateFilter.isEmpty()) {
      Set<Thread.State> states = stateFilter.stream().map(Thread.State::valueOf).collect(Collectors.toSet());
      result = result.stream().filter(t -> states.contains(t.getThreadState())).collect(Collectors.toList());
    }

    // Name pattern filter
    String namePattern = getStringParam(args, "name_pattern", null);
    if (namePattern != null) {
      Pattern pattern = Pattern.compile(namePattern);
      result = result.stream().filter(t -> pattern.matcher(t.getThreadName()).find()).collect(Collectors.toList());
    }

    // CPU time filter
    Integer minCpuTime = getIntParam(args, "min_cpu_time_ms", null);
    if (minCpuTime != null && threadMXBean.isThreadCpuTimeSupported()) {
      result = result.stream().filter(t -> threadMXBean.getThreadCpuTime(t.getThreadId()) / 1_000_000 >= minCpuTime)
          .collect(Collectors.toList());
    }

    return result;
  }

  /**
   * Apply search criteria (for thread_search).
   */
  private List<ThreadInfo> applySearchCriteria(ThreadInfo[] threads, Map<String, Object> args) {
    List<ThreadInfo> result = new ArrayList<>();

    for (ThreadInfo info : threads) {
      if (info == null)
        continue;
      result.add(info);
    }

    // Name contains
    String nameContains = getStringParam(args, "name_contains", null);
    if (nameContains != null) {
      result = result.stream().filter(t -> t.getThreadName().contains(nameContains)).collect(Collectors.toList());
    }

    // State in
    List<String> stateIn = getListParam(args, "state_in");
    if (stateIn != null && !stateIn.isEmpty()) {
      Set<Thread.State> states = stateIn.stream().map(Thread.State::valueOf).collect(Collectors.toSet());
      result = result.stream().filter(t -> states.contains(t.getThreadState())).collect(Collectors.toList());
    }

    // Daemon filter
    Boolean daemon = (Boolean) args.get("daemon");
    if (daemon != null) {
      result = result.stream().filter(t -> t.isDaemon() == daemon).collect(Collectors.toList());
    }

    // Min CPU time
    Integer minCpuTime = getIntParam(args, "min_cpu_time_ms", null);
    if (minCpuTime != null && threadMXBean.isThreadCpuTimeSupported()) {
      result = result.stream().filter(t -> threadMXBean.getThreadCpuTime(t.getThreadId()) / 1_000_000 >= minCpuTime)
          .collect(Collectors.toList());
    }

    return result;
  }

  /**
   * Sort threads by specified field.
   */
  private List<ThreadInfo> sortThreads(List<ThreadInfo> threads, String sortBy, boolean descending) {
    Comparator<ThreadInfo> comparator = switch (sortBy) {
    case "cpu_time" -> (t1, t2) -> {
      if (!threadMXBean.isThreadCpuTimeSupported())
        return 0;
      long cpu1 = threadMXBean.getThreadCpuTime(t1.getThreadId());
      long cpu2 = threadMXBean.getThreadCpuTime(t2.getThreadId());
      return Long.compare(cpu1, cpu2);
    };
    case "name" -> (t1, t2) -> t1.getThreadName().compareTo(t2.getThreadName());
    case "id" -> (t1, t2) -> Long.compare(t1.getThreadId(), t2.getThreadId());
    case "state" -> (t1, t2) -> t1.getThreadState().compareTo(t2.getThreadState());
    default -> (t1, t2) -> t1.getThreadName().compareTo(t2.getThreadName());
    };

    if (descending) {
      comparator = comparator.reversed();
    }

    threads.sort(comparator);
    return threads;
  }

  /**
   * Get thread IDs to inspect from arguments.
   */
  private List<Long> getThreadIdsToInspect(Map<String, Object> args) {
    List<Long> threadIds = new ArrayList<>();

    // Try thread_ids parameter
    Object idsObj = args.get("thread_ids");
    if (idsObj != null) {
      if (idsObj instanceof Collection) {
        Collection<?> collection = (Collection<?>) idsObj;
        if (collection.isEmpty()) {
          throw new IllegalArgumentException("thread_ids array is empty - must contain at least one thread ID");
        }
        for (Object obj : collection) {
          if (obj instanceof Number) {
            threadIds.add(((Number) obj).longValue());
          } else {
            throw new IllegalArgumentException(
                String.format("thread_ids array must contain numbers (integers), but found %s: '%s'",
                    obj.getClass().getSimpleName(), obj));
          }
        }
        return threadIds;
      } else if (idsObj instanceof Number) {
        // Single ID
        threadIds.add(((Number) idsObj).longValue());
        return threadIds;
      } else if (idsObj instanceof Object[]) {
        // Array of IDs
        Object[] array = (Object[]) idsObj;
        if (array.length == 0) {
          throw new IllegalArgumentException("thread_ids array is empty - must contain at least one thread ID");
        }
        for (Object obj : array) {
          if (obj instanceof Number) {
            threadIds.add(((Number) obj).longValue());
          } else {
            throw new IllegalArgumentException(
                String.format("thread_ids array must contain numbers (integers), but found %s: '%s'",
                    obj.getClass().getSimpleName(), obj));
          }
        }
        return threadIds;
      } else if (idsObj instanceof String) {
        // Common mistake: passing string instead of array
        String strValue = (String) idsObj;
        if (strValue.startsWith("[") && strValue.endsWith("]")) {
          throw new IllegalArgumentException(String.format(
              "thread_ids must be an array of integers, not a JSON string. "
                  + "Received string '%s'. Pass as array: thread_ids=[%s] not thread_ids='%s'",
              strValue, strValue.substring(1, strValue.length() - 1), strValue));
        } else {
          throw new IllegalArgumentException(String.format(
              "thread_ids must be an array of integers, not a string. "
                  + "Received: '%s'. Use thread_ids=[%s] or thread_names=\"%s\" to match by name",
              strValue, strValue, strValue));
        }
      } else {
        // Unexpected type
        throw new IllegalArgumentException(
            String.format("thread_ids must be an array of integers or a single integer, but got %s: '%s'",
                idsObj.getClass().getSimpleName(), idsObj));
      }
    }

    // Try thread_names parameter
    Object namesObj = args.get("thread_names");
    if (namesObj != null) {
      List<String> nameList = null;

      if (namesObj instanceof String) {
        // Single name
        nameList = List.of((String) namesObj);
      } else if (namesObj instanceof Collection) {
        nameList = new ArrayList<>();
        for (Object obj : (Collection<?>) namesObj) {
          if (obj instanceof String) {
            nameList.add((String) obj);
          } else {
            throw new IllegalArgumentException(String.format("thread_names must contain strings, but found %s: '%s'",
                obj.getClass().getSimpleName(), obj));
          }
        }
      } else if (namesObj instanceof Object[]) {
        nameList = new ArrayList<>();
        for (Object obj : (Object[]) namesObj) {
          if (obj instanceof String) {
            nameList.add((String) obj);
          } else {
            throw new IllegalArgumentException(String.format("thread_names must contain strings, but found %s: '%s'",
                obj.getClass().getSimpleName(), obj));
          }
        }
      } else {
        throw new IllegalArgumentException(
            String.format("thread_names must be a string or array of strings, but got %s: '%s'",
                namesObj.getClass().getSimpleName(), namesObj));
      }

      if (nameList != null && !nameList.isEmpty()) {
        long[] allIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] allThreads = threadMXBean.getThreadInfo(allIds, 0);

        for (ThreadInfo info : allThreads) {
          if (info != null && nameList.contains(info.getThreadName())) {
            threadIds.add(info.getThreadId());
          }
        }
      }
    }

    return threadIds;
  }

  /**
   * Estimate JSON size of a map (rough approximation).
   */
  private int estimateJsonSize(Map<String, Object> data) {
    try {
      // Simple estimation: serialize and measure
      return objectMapper.writeValueAsString(data).length();
    } catch (Exception e) {
      // Fallback: rough estimate
      return 1000; // Default to 1KB per thread
    }
  }

  /**
   * Get string parameter with default.
   */
  private String getStringParam(Map<String, Object> args, String key, String defaultValue) {
    Object value = args.get(key);
    return value != null ? value.toString() : defaultValue;
  }

  /**
   * Get integer parameter with default. Throws ClassCastException if value is
   * present but not a Number.
   */
  private Integer getIntParam(Map<String, Object> args, String key, Integer defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    // Throw ClassCastException for invalid types (e.g., String)
    throw new ClassCastException(
        "Parameter '" + key + "' must be a number, but got " + value.getClass().getSimpleName());
  }

  /**
   * Get boolean parameter with default.
   */
  private boolean getBooleanParam(Map<String, Object> args, String key, boolean defaultValue) {
    Object value = args.get(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return defaultValue;
  }

  /**
   * Get list parameter safely (handles both List and Collection types).
   */
  @SuppressWarnings("unchecked")
  private List<String> getListParam(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof List) {
      return (List<String>) value;
    }
    if (value instanceof Collection) {
      return new ArrayList<>((Collection<String>) value);
    }
    // Single string value - wrap in list
    if (value instanceof String) {
      return List.of((String) value);
    }
    return null;
  }

  // ========== Modified Format Methods ==========

  /**
   * Format stack trace for JSON output with optional pattern filtering.
   */
  private List<String> formatStackTrace(StackTraceElement[] stack, int maxDepth, String filterPattern) {
    List<String> formatted = new ArrayList<>();
    Pattern pattern = filterPattern != null ? Pattern.compile(filterPattern) : null;

    int included = 0;
    for (int i = 0; i < stack.length && included < maxDepth; i++) {
      String frame = stack[i].toString();

      // If filter specified, only include matching frames
      if (pattern == null || pattern.matcher(frame).find()) {
        formatted.add(frame);
        included++;
      }
    }

    if (included < stack.length) {
      int remaining = stack.length - included;
      formatted.add(String.format("... %d more frame%s", remaining, remaining == 1 ? "" : "s"));
    }

    return formatted;
  }

  /**
   * Format stack trace for JSON output (backward compatibility).
   */
  private List<String> formatStackTrace(StackTraceElement[] stack, int maxDepth) {
    return formatStackTrace(stack, maxDepth, null);
  }
}