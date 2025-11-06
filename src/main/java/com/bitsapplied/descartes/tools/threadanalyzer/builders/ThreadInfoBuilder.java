package com.bitsapplied.descartes.tools.threadanalyzer.builders;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for constructing thread information maps.
 * Supports building both minimal summaries and detailed thread info with optional fields.
 */
public class ThreadInfoBuilder {

  private final ThreadInfo threadInfo;
  private final ThreadMXBean threadMXBean;
  private final Map<String, Object> result = new HashMap<>();

  // Optional configuration
  private boolean includeCpuTime = false;
  private boolean includeLocks = false;
  private boolean includeContention = false;
  private boolean includeMonitors = false;
  private boolean includeSynchronizers = false;
  private boolean includeStackTrace = false;
  private int maxStackDepth = Integer.MAX_VALUE;
  private String filterStackPattern = null;
  private StackTraceFormatter stackTraceFormatter = null;

  /**
   * Functional interface for formatting stack traces.
   */
  @FunctionalInterface
  public interface StackTraceFormatter {
    List<String> format(StackTraceElement[] stackTrace, int maxDepth, String filterPattern);
  }

  /**
   * Create a builder for the given thread info.
   *
   * @param threadInfo the thread info to build from
   * @param threadMXBean the ThreadMXBean for querying CPU time and contention
   */
  public ThreadInfoBuilder(ThreadInfo threadInfo, ThreadMXBean threadMXBean) {
    this.threadInfo = threadInfo;
    this.threadMXBean = threadMXBean;
  }

  /**
   * Include CPU time information (cpu_time_ms, user_time_ms).
   *
   * @return this builder for chaining
   */
  public ThreadInfoBuilder withCpuTime() {
    this.includeCpuTime = true;
    return this;
  }

  /**
   * Include lock information (waiting_on_lock, lock_owner_id, lock_owner_name).
   *
   * @return this builder for chaining
   */
  public ThreadInfoBuilder withLocks() {
    this.includeLocks = true;
    return this;
  }

  /**
   * Include contention information (blocked_count, blocked_time_ms, waited_count, waited_time_ms).
   *
   * @return this builder for chaining
   */
  public ThreadInfoBuilder withContention() {
    this.includeContention = true;
    return this;
  }

  /**
   * Include locked monitors information.
   *
   * @return this builder for chaining
   */
  public ThreadInfoBuilder withMonitors() {
    this.includeMonitors = true;
    return this;
  }

  /**
   * Include owned synchronizers information.
   *
   * @return this builder for chaining
   */
  public ThreadInfoBuilder withSynchronizers() {
    this.includeSynchronizers = true;
    return this;
  }

  /**
   * Include stack trace with optional depth limit and filtering.
   *
   * @param maxDepth the maximum stack depth to include
   * @param filterPattern optional regex pattern to filter stack frames
   * @param formatter function to format the stack trace
   * @return this builder for chaining
   */
  public ThreadInfoBuilder withStackTrace(int maxDepth, String filterPattern, StackTraceFormatter formatter) {
    this.includeStackTrace = true;
    this.maxStackDepth = maxDepth;
    this.filterStackPattern = filterPattern;
    this.stackTraceFormatter = formatter;
    return this;
  }

  /**
   * Build the thread info map with all configured options.
   *
   * @return the thread info map
   */
  public Map<String, Object> build() {
    // Basic info (always included)
    result.put("id", threadInfo.getThreadId());
    result.put("name", threadInfo.getThreadName());
    result.put("state", threadInfo.getThreadState().toString());
    result.put("priority", threadInfo.getPriority());
    result.put("daemon", threadInfo.isDaemon());

    // CPU time (if enabled and supported)
    if (includeCpuTime && threadMXBean.isThreadCpuTimeSupported()) {
      addCpuTimeInfo();
    }

    // Lock information
    if (includeLocks) {
      addLockInfo();
    }

    // Contention information
    if (includeContention && threadMXBean.isThreadContentionMonitoringSupported()) {
      addContentionInfo();
    }

    // Monitors
    if (includeMonitors) {
      addMonitorInfo();
    }

    // Synchronizers
    if (includeSynchronizers) {
      addSynchronizerInfo();
    }

    // Stack trace
    if (includeStackTrace && stackTraceFormatter != null) {
      addStackTraceInfo();
    }

    return result;
  }

  private void addCpuTimeInfo() {
    long cpuTime = threadMXBean.getThreadCpuTime(threadInfo.getThreadId());
    long userTime = threadMXBean.getThreadUserTime(threadInfo.getThreadId());
    if (cpuTime >= 0) {
      result.put("cpu_time_ms", cpuTime / 1_000_000);
      result.put("user_time_ms", userTime / 1_000_000);
    }
  }

  private void addLockInfo() {
    LockInfo lockInfo = threadInfo.getLockInfo();
    if (lockInfo != null) {
      result.put("waiting_on_lock",
          Map.of("class_name", lockInfo.getClassName(), "identity_hash",
              Integer.toHexString(lockInfo.getIdentityHashCode())));
      result.put("lock_owner_id", threadInfo.getLockOwnerId());
      result.put("lock_owner_name", threadInfo.getLockOwnerName());
    }
  }

  private void addContentionInfo() {
    result.put("blocked_count", threadInfo.getBlockedCount());
    result.put("blocked_time_ms", threadInfo.getBlockedTime());
    result.put("waited_count", threadInfo.getWaitedCount());
    result.put("waited_time_ms", threadInfo.getWaitedTime());
  }

  private void addMonitorInfo() {
    MonitorInfo[] monitors = threadInfo.getLockedMonitors();
    if (monitors.length > 0) {
      List<Map<String, Object>> monitorList = new ArrayList<>();
      for (MonitorInfo monitor : monitors) {
        monitorList.add(Map.of("class_name", monitor.getClassName(), "identity_hash",
            Integer.toHexString(monitor.getIdentityHashCode()), "stack_depth", monitor.getLockedStackDepth()));
      }
      result.put("locked_monitors", monitorList);
    }
  }

  private void addSynchronizerInfo() {
    LockInfo[] synchronizers = threadInfo.getLockedSynchronizers();
    if (synchronizers.length > 0) {
      List<Map<String, Object>> syncList = new ArrayList<>();
      for (LockInfo sync : synchronizers) {
        syncList.add(
            Map.of("class_name", sync.getClassName(), "identity_hash", Integer.toHexString(sync.getIdentityHashCode())));
      }
      result.put("owned_synchronizers", syncList);
    }
  }

  private void addStackTraceInfo() {
    List<String> stackTrace = stackTraceFormatter.format(threadInfo.getStackTrace(), maxStackDepth, filterStackPattern);
    result.put("stack_trace", stackTrace);
    result.put("stack_depth", stackTrace.size());
  }
}
