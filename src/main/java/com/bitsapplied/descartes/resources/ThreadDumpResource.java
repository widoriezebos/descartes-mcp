package com.bitsapplied.descartes.resources;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Resource that generates comprehensive thread dumps with stack traces,
 * lock information, and deadlock detection.
 */
public class ThreadDumpResource implements MCPResourceHandler {
  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String getUriPath() {
    return "threads/dump";
  }

  @Override
  public String getName() {
    return "Thread Dump";
  }

  @Override
  public String getDescription() {
    return "Comprehensive JVM thread dump generator providing full visibility into application threading state. "
        + "Captures all thread stack traces with method calls and line numbers, monitors and lock information, "
        + "synchronization state, and automatic deadlock detection with circular dependency analysis. "
        + "Supports JSON and text output formats. Parameters: 'stackTrace' (include full traces), "
        + "'monitors' (include lock info), 'synchronizers' (include synchronization state), 'format' (json/text). "
        + "Critical for debugging thread hangs, deadlocks, and performance bottlenecks.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String format = queryParams.get("format", "json");
      boolean includeStackTrace = getBooleanParam(queryParams, "stackTrace", true);
      boolean includeLocks = getBooleanParam(queryParams, "locks", true);
      boolean includeMonitors = getBooleanParam(queryParams, "monitors", true);
      int maxDepth = getIntParam(queryParams, "maxDepth", Integer.MAX_VALUE);
      String stateFilter = queryParams.get("state", "");
      String nameFilter = queryParams.get("name", "");

      switch (format) {
      case "json":
        return getJsonThreadDump(includeStackTrace, includeLocks, includeMonitors, maxDepth, stateFilter, nameFilter);
      case "text":
        return getTextThreadDump(includeStackTrace, includeLocks, includeMonitors, maxDepth);
      case "summary":
        return getThreadSummary();
      default:
        throw new MCPResource.ResourceException("Unknown format: " + format);
      }
    } catch (MCPResource.ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new MCPResource.ResourceException("Error generating thread dump", e);
    }
  }

  @Override
  public String resolveMimeType(QueryParams queryParams) {
    String format = queryParams.get("format", "json");
    return switch (format) {
    case "text" -> "text/plain";
    case "summary" -> "application/json";
    default -> getMimeType();
    };
  }

  private String getJsonThreadDump(boolean includeStackTrace, boolean includeLocks, boolean includeMonitors,
      int maxDepth, String stateFilter, String nameFilter) throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    result.put("timestamp", System.currentTimeMillis());
    result.put("threadCount", threadBean.getThreadCount());

    // Check for deadlocks
    long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();
    if (deadlockedThreadIds != null && deadlockedThreadIds.length > 0) {
      ArrayNode deadlocksArray = result.putArray("deadlocks");
      ThreadInfo[] deadlockedThreads = threadBean.getThreadInfo(deadlockedThreadIds, includeStackTrace ? maxDepth : 0);
      for (ThreadInfo info : deadlockedThreads) {
        if (info != null) {
          ObjectNode deadlockNode = deadlocksArray.addObject();
          deadlockNode.put("threadName", info.getThreadName());
          deadlockNode.put("threadId", info.getThreadId());
          deadlockNode.put("lockName", info.getLockName());
          deadlockNode.put("lockOwnerName", info.getLockOwnerName());
          deadlockNode.put("lockOwnerId", info.getLockOwnerId());
        }
      }
    }

    // Get all thread information
    ThreadInfo[] allThreads;
    if (includeMonitors && includeLocks) {
      allThreads = threadBean.dumpAllThreads(includeMonitors, includeLocks);
    } else {
      long[] threadIds = threadBean.getAllThreadIds();
      allThreads = threadBean.getThreadInfo(threadIds, includeStackTrace ? maxDepth : 0);
    }

    // Filter and process threads
    ArrayNode threadsArray = result.putArray("threads");
    for (ThreadInfo info : allThreads) {
      if (info != null) {
        // Apply filters
        if (!stateFilter.isEmpty() && !info.getThreadState().toString().equalsIgnoreCase(stateFilter)) {
          continue;
        }
        if (!nameFilter.isEmpty() && !info.getThreadName().toLowerCase().contains(nameFilter.toLowerCase())) {
          continue;
        }

        ObjectNode threadNode = threadsArray.addObject();
        addThreadInfo(threadNode, info, includeStackTrace, includeLocks, includeMonitors, maxDepth);
      }
    }

    result.put("filteredThreadCount", threadsArray.size());

    // Add thread state summary
    ObjectNode stateSummary = result.putObject("stateSummary");
    Map<Thread.State, Long> stateCount = Arrays.stream(allThreads).filter(Objects::nonNull)
        .collect(Collectors.groupingBy(ThreadInfo::getThreadState, Collectors.counting()));

    for (Map.Entry<Thread.State, Long> entry : stateCount.entrySet()) {
      stateSummary.put(entry.getKey().toString(), entry.getValue());
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private void addThreadInfo(ObjectNode threadNode, ThreadInfo info, boolean includeStackTrace, boolean includeLocks,
      boolean includeMonitors, int maxDepth) {
    threadNode.put("threadName", info.getThreadName());
    threadNode.put("threadId", info.getThreadId());
    threadNode.put("threadState", info.getThreadState().toString());
    threadNode.put("daemon", info.isDaemon());
    threadNode.put("priority", info.getPriority());
    threadNode.put("suspended", info.isSuspended());
    threadNode.put("inNative", info.isInNative());

    // Blocking information
    if (info.getBlockedCount() > 0) {
      threadNode.put("blockedCount", info.getBlockedCount());
      threadNode.put("blockedTime", info.getBlockedTime());
    }

    if (info.getWaitedCount() > 0) {
      threadNode.put("waitedCount", info.getWaitedCount());
      threadNode.put("waitedTime", info.getWaitedTime());
    }

    // Lock information
    if (includeLocks) {
      if (info.getLockName() != null) {
        threadNode.put("lockName", info.getLockName());
      }
      if (info.getLockOwnerName() != null) {
        threadNode.put("lockOwnerName", info.getLockOwnerName());
        threadNode.put("lockOwnerId", info.getLockOwnerId());
      }

      LockInfo lockInfo = info.getLockInfo();
      if (lockInfo != null) {
        ObjectNode lockNode = threadNode.putObject("lockInfo");
        lockNode.put("className", lockInfo.getClassName());
        lockNode.put("identityHashCode", lockInfo.getIdentityHashCode());
      }
    }

    // Monitor information
    if (includeMonitors) {
      MonitorInfo[] monitors = info.getLockedMonitors();
      if (monitors != null && monitors.length > 0) {
        ArrayNode monitorsArray = threadNode.putArray("lockedMonitors");
        for (MonitorInfo monitor : monitors) {
          ObjectNode monitorNode = monitorsArray.addObject();
          monitorNode.put("className", monitor.getClassName());
          monitorNode.put("identityHashCode", monitor.getIdentityHashCode());
          monitorNode.put("lockedStackDepth", monitor.getLockedStackDepth());
        }
      }

      LockInfo[] synchronizers = info.getLockedSynchronizers();
      if (synchronizers != null && synchronizers.length > 0) {
        ArrayNode syncsArray = threadNode.putArray("lockedSynchronizers");
        for (LockInfo sync : synchronizers) {
          ObjectNode syncNode = syncsArray.addObject();
          syncNode.put("className", sync.getClassName());
          syncNode.put("identityHashCode", sync.getIdentityHashCode());
        }
      }
    }

    // Stack trace
    if (includeStackTrace) {
      StackTraceElement[] stackTrace = info.getStackTrace();
      if (stackTrace != null && stackTrace.length > 0) {
        ArrayNode stackArray = threadNode.putArray("stackTrace");
        int depth = Math.min(stackTrace.length, maxDepth);
        for (int i = 0; i < depth; i++) {
          StackTraceElement element = stackTrace[i];
          ObjectNode frameNode = stackArray.addObject();
          frameNode.put("className", element.getClassName());
          frameNode.put("methodName", element.getMethodName());
          frameNode.put("fileName", element.getFileName());
          frameNode.put("lineNumber", element.getLineNumber());
          frameNode.put("nativeMethod", element.isNativeMethod());
        }

        if (stackTrace.length > maxDepth) {
          threadNode.put("stackTraceTruncated", true);
          threadNode.put("fullStackTraceLength", stackTrace.length);
        }
      }
    }
  }

  private String getTextThreadDump(boolean includeStackTrace, boolean includeLocks, boolean includeMonitors,
      int maxDepth) throws Exception {
    StringBuilder sb = new StringBuilder();
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    sb.append("Full thread dump ").append(System.getProperty("java.vm.name"));
    sb.append(" (").append(System.getProperty("java.vm.version")).append("):\n\n");

    // Check for deadlocks
    long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();
    if (deadlockedThreadIds != null && deadlockedThreadIds.length > 0) {
      sb.append("Found ").append(deadlockedThreadIds.length).append(" deadlocked threads!\n\n");
    }

    ThreadInfo[] threads;
    if (includeMonitors && includeLocks) {
      threads = threadBean.dumpAllThreads(includeMonitors, includeLocks);
    } else {
      long[] threadIds = threadBean.getAllThreadIds();
      threads = threadBean.getThreadInfo(threadIds, includeStackTrace ? maxDepth : 0);
    }

    for (ThreadInfo info : threads) {
      if (info != null) {
        sb.append('"').append(info.getThreadName()).append('"');
        sb.append(" Id=").append(info.getThreadId());
        sb.append(" ").append(info.getThreadState());

        if (info.getLockName() != null) {
          sb.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
          sb.append(" owned by \"").append(info.getLockOwnerName());
          sb.append("\" Id=").append(info.getLockOwnerId());
        }
        if (info.isSuspended()) {
          sb.append(" (suspended)");
        }
        if (info.isInNative()) {
          sb.append(" (in native)");
        }
        sb.append("\n");

        if (includeStackTrace) {
          StackTraceElement[] stackTrace = info.getStackTrace();
          int depth = Math.min(stackTrace.length, maxDepth);
          for (int i = 0; i < depth; i++) {
            StackTraceElement element = stackTrace[i];
            sb.append("\tat ").append(element.toString()).append("\n");

            if (includeMonitors) {
              MonitorInfo[] monitors = info.getLockedMonitors();
              for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) {
                  sb.append("\t- locked ").append(monitor).append("\n");
                }
              }
            }
          }

          if (stackTrace.length > maxDepth) {
            sb.append("\t... ").append(stackTrace.length - maxDepth).append(" more\n");
          }
        }

        if (includeLocks) {
          LockInfo[] synchronizers = info.getLockedSynchronizers();
          if (synchronizers.length > 0) {
            sb.append("\n\tLocked synchronizers:\n");
            for (LockInfo sync : synchronizers) {
              sb.append("\t- ").append(sync).append("\n");
            }
          }
        }

        sb.append("\n");
      }
    }

    return sb.toString();
  }

  private String getThreadSummary() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    result.put("threadCount", threadBean.getThreadCount());
    result.put("peakThreadCount", threadBean.getPeakThreadCount());
    result.put("daemonThreadCount", threadBean.getDaemonThreadCount());
    result.put("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());

    // Get thread info for summary
    long[] threadIds = threadBean.getAllThreadIds();
    ThreadInfo[] threads = threadBean.getThreadInfo(threadIds, 0);

    // Group by state
    Map<Thread.State, List<ThreadInfo>> byState = Arrays.stream(threads).filter(Objects::nonNull)
        .collect(Collectors.groupingBy(ThreadInfo::getThreadState));

    ObjectNode stateBreakdown = result.putObject("stateBreakdown");
    for (Map.Entry<Thread.State, List<ThreadInfo>> entry : byState.entrySet()) {
      ArrayNode stateThreads = stateBreakdown.putArray(entry.getKey().toString());
      for (ThreadInfo info : entry.getValue()) {
        ObjectNode threadSummary = stateThreads.addObject();
        threadSummary.put("name", info.getThreadName());
        threadSummary.put("id", info.getThreadId());
        threadSummary.put("daemon", info.isDaemon());
      }
    }

    // Deadlock detection
    long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();
    if (deadlockedThreadIds != null && deadlockedThreadIds.length > 0) {
      result.put("deadlockedThreadCount", deadlockedThreadIds.length);
      ArrayNode deadlocks = result.putArray("deadlockedThreadIds");
      for (long id : deadlockedThreadIds) {
        deadlocks.add(id);
      }
    } else {
      result.put("deadlockedThreadCount", 0);
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private boolean getBooleanParam(QueryParams params, String key, boolean defaultValue) {
    String value = params.get(key);
    if (value != null) {
      return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
    return defaultValue;
  }

  private int getIntParam(QueryParams params, String key, int defaultValue) {
    String value = params.get(key);
    if (value != null) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        // Fall through to default
      }
    }
    return defaultValue;
  }
}
