package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Filters threads by minimum CPU time.
 * Only applies if CPU time monitoring is supported by the JVM.
 */
public class CpuTimeFilter implements ThreadFilter {

  private final ThreadMXBean threadMXBean;

  /**
   * Create a CpuTimeFilter with the specified ThreadMXBean.
   *
   * @param threadMXBean the ThreadMXBean to query for CPU time
   */
  public CpuTimeFilter(ThreadMXBean threadMXBean) {
    this.threadMXBean = threadMXBean;
  }

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    Integer minCpuTime = ParameterUtils.getInt(args, "min_cpu_time_ms", null);
    return minCpuTime != null && threadMXBean.isThreadCpuTimeSupported();
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    Integer minCpuTime = ParameterUtils.getInt(args, "min_cpu_time_ms", null);
    if (minCpuTime == null || !threadMXBean.isThreadCpuTimeSupported()) {
      return threads;
    }

    return threads.stream()
        .filter(t -> threadMXBean.getThreadCpuTime(t.getThreadId()) / 1_000_000 >= minCpuTime)
        .collect(Collectors.toList());
  }
}
