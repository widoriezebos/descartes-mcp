package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.bitsapplied.descartes.tools.threadanalyzer.builders.ThreadInfoBuilder;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Abstract base class for thread operations with common dependencies and utilities.
 */
public abstract class AbstractThreadOperation implements ThreadOperation {

  protected final ThreadMXBean threadMXBean;
  protected final ExecutorService executor;
  protected final FilterChain threadListFilters;
  protected final FilterChain threadSearchFilters;
  protected final ObjectMapper objectMapper;
  protected final int maxResponseSizeBytes;
  protected final int maxThreadsPerInspect;
  protected final int defaultMaxResults;

  /**
   * Constructor with all common dependencies.
   */
  public AbstractThreadOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults) {
    this.threadMXBean = threadMXBean;
    this.executor = executor;
    this.threadListFilters = threadListFilters;
    this.threadSearchFilters = threadSearchFilters;
    this.objectMapper = objectMapper;
    this.maxResponseSizeBytes = maxResponseSizeBytes;
    this.maxThreadsPerInspect = maxThreadsPerInspect;
    this.defaultMaxResults = defaultMaxResults;
  }

  /**
   * Filter null threads from array.
   */
  protected List<ThreadInfo> filterNullThreads(ThreadInfo[] threads) {
    List<ThreadInfo> result = new ArrayList<>();
    for (ThreadInfo info : threads) {
      if (info != null) {
        result.add(info);
      }
    }
    return result;
  }

  /**
   * Apply thread_list filters.
   */
  protected List<ThreadInfo> applyThreadFilters(ThreadInfo[] threads, Map<String, Object> args) {
    List<ThreadInfo> threadList = filterNullThreads(threads);
    return threadListFilters.apply(threadList, args);
  }

  /**
   * Apply thread_search filters.
   */
  protected List<ThreadInfo> applySearchCriteria(ThreadInfo[] threads, Map<String, Object> args) {
    List<ThreadInfo> threadList = filterNullThreads(threads);
    return threadSearchFilters.apply(threadList, args);
  }

  /**
   * Sort threads by specified field.
   */
  protected List<ThreadInfo> sortThreads(List<ThreadInfo> threads, String sortBy, boolean descending) {
    Comparator<ThreadInfo> comparator = switch (sortBy) {
    case "cpu_time" -> {
      yield (t1, t2) -> {
        if (!threadMXBean.isThreadCpuTimeSupported())
          return 0;
        long cpu1 = threadMXBean.getThreadCpuTime(t1.getThreadId());
        long cpu2 = threadMXBean.getThreadCpuTime(t2.getThreadId());
        return Long.compare(cpu1, cpu2);
      };
    }
    case "name" -> Comparator.comparing(ThreadInfo::getThreadName);
    case "id" -> Comparator.comparingLong(ThreadInfo::getThreadId);
    case "state" -> Comparator.comparing(t -> t.getThreadState().toString());
    default -> throw new IllegalArgumentException("Invalid sort_by: " + sortBy);
    };

    if (descending) {
      comparator = comparator.reversed();
    }

    return threads.stream().sorted(comparator).toList();
  }

  /**
   * Build minimal thread summary.
   */
  protected Map<String, Object> buildThreadSummary(ThreadInfo info) {
    return new ThreadInfoBuilder(info, threadMXBean).withCpuTime().build();
  }

  /**
   * Build detailed thread information.
   */
  protected Map<String, Object> buildThreadDetail(ThreadInfo info, boolean includeStack, int maxStackDepth,
      boolean includeLocks, boolean includeMonitors, boolean includeSynchronizers, String filterStackPattern,
      StackTraceFormatter stackTraceFormatter) {

    ThreadInfoBuilder builder = new ThreadInfoBuilder(info, threadMXBean).withCpuTime().withContention();

    if (includeLocks) {
      builder.withLocks();
    }

    if (includeMonitors) {
      builder.withMonitors();
    }

    if (includeSynchronizers) {
      builder.withSynchronizers();
    }

    if (includeStack && stackTraceFormatter != null) {
      builder.withStackTrace(maxStackDepth, filterStackPattern, stackTraceFormatter::format);
    }

    return builder.build();
  }

  /**
   * Estimate JSON size of a map.
   */
  protected int estimateJsonSize(Map<String, Object> data) {
    try {
      return objectMapper.writeValueAsString(data).length();
    } catch (Exception e) {
      return 1000; // Conservative estimate if serialization fails
    }
  }

  /**
   * Functional interface for formatting stack traces.
   */
  @FunctionalInterface
  public interface StackTraceFormatter {
    List<String> format(StackTraceElement[] stackTrace, int maxDepth, String filterPattern);
  }

  /**
   * Format stack trace for JSON output with optional pattern filtering.
   */
  protected List<String> formatStackTrace(StackTraceElement[] stack, int maxDepth, String filterPattern) {
    List<String> formatted = new ArrayList<>();
    Pattern pattern = filterPattern != null ? safeCompilePattern(filterPattern, "filter_stack_pattern") : null;

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
   * Safely compile a regex pattern with ReDoS protection.
   */
  protected Pattern safeCompilePattern(String patternStr, String paramName) {
    if (patternStr == null) {
      throw new IllegalArgumentException(paramName + " cannot be null");
    }

    // Limit pattern length to prevent resource exhaustion
    final int MAX_PATTERN_LENGTH = 500;
    if (patternStr.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException(String.format(
          "%s pattern too long: %d characters (max %d). " + "Complex patterns may cause performance issues.", paramName,
          patternStr.length(), MAX_PATTERN_LENGTH));
    }

    // Detect obvious ReDoS patterns (nested/overlapping quantifiers)
    if (patternStr.matches(".*\\([^)]*[+*]\\)[+*].*")) {
      throw new IllegalArgumentException(String.format("%s contains nested quantifiers (e.g., (a+)+) which can cause "
          + "catastrophic backtracking. Simplify the pattern to avoid performance issues.", paramName));
    }

    // Additional check for multiple consecutive quantifiers
    if (patternStr.matches(".*[+*]{2,}.*")) {
      throw new IllegalArgumentException(
          String.format("%s contains consecutive quantifiers (e.g., a**) which is invalid.", paramName));
    }

    try {
      return Pattern.compile(patternStr);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          String.format("%s is not a valid regex pattern: %s", paramName, e.getMessage()), e);
    }
  }

  /**
   * Extract thread IDs to inspect from arguments (thread_ids or thread_names parameter).
   */
  protected List<Long> getThreadIdsToInspect(Map<String, Object> args) {
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
        threadIds.add(((Number) idsObj).longValue());
        return threadIds;
      } else if (idsObj instanceof Object[]) {
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
   * Analyze deadlock chains to show circular dependencies.
   */
  protected List<Map<String, Object>> analyzeDeadlockChains(ThreadInfo[] deadlockedThreads, boolean includeStack,
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
          threadInfo.put("stack_trace", formatStackTrace(current.getStackTrace(), maxStackDepth, null));
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
   * Format thread information for text output with optional stack filtering.
   */
  protected String formatThreadInfo(ThreadInfo info, int maxStackDepth, String filterStackPattern) {
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
    Pattern stackPattern = filterStackPattern != null ? safeCompilePattern(filterStackPattern, "filter_stack_pattern")
        : null;

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
}
