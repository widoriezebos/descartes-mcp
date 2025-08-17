package com.bitsapplied.descartes.resources;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.OperatingSystemMXBean;

/**
 * MCP Resource that provides JVM metrics including memory usage, CPU
 * utilization, thread counts, and garbage collection statistics.
 */
public class MetricsResource implements MCPResourceHandler {
  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String getUriPath() {
    return "metrics";
  }

  @Override
  public String getName() {
    return "JVM Metrics";
  }

  @Override
  public String getDescription() {
    return "Real-time JVM performance metrics dashboard providing comprehensive runtime statistics. "
        + "Monitors heap/non-heap memory usage with pool breakdowns, system and process CPU utilization percentages, "
        + "thread counts by state (runnable/blocked/waiting), garbage collection frequency and pause times, "
        + "and JIT compilation statistics. Parameters: 'type' (all/memory/cpu/threads/gc/compilation). "
        + "Perfect for performance monitoring, capacity planning, and identifying bottlenecks in production systems.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String type = queryParams.get("type", "all");

      switch (type) {
      case "all":
        return getAllMetrics();
      case "memory":
        return getMemoryMetrics();
      case "cpu":
        return getCpuMetrics();
      case "threads":
        return getThreadMetrics();
      case "gc":
        return getGcMetrics();
      case "compilation":
        return getCompilationMetrics();
      default:
        throw new MCPResource.ResourceException("Unknown metrics type: " + type);
      }
    } catch (MCPResource.ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new MCPResource.ResourceException("Error handling metrics request", e);
    }
  }

  private String getAllMetrics() throws Exception {
    ObjectNode result = mapper.createObjectNode();

    result.set("memory", getMemoryMetricsNode());
    result.set("cpu", getCpuMetricsNode());
    result.set("threads", getThreadMetricsNode());
    result.set("gc", getGcMetricsNode());
    result.set("compilation", getCompilationMetricsNode());
    result.put("timestamp", System.currentTimeMillis());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getMemoryMetrics() throws Exception {
    ObjectNode result = getMemoryMetricsNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getMemoryMetricsNode() {
    ObjectNode result = mapper.createObjectNode();
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    // Heap memory
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    ObjectNode heapNode = result.putObject("heap");
    heapNode.put("init", heapUsage.getInit());
    heapNode.put("used", heapUsage.getUsed());
    heapNode.put("committed", heapUsage.getCommitted());
    heapNode.put("max", heapUsage.getMax());
    heapNode.put("usagePercent", heapUsage.getMax() > 0 ? (double) heapUsage.getUsed() / heapUsage.getMax() * 100 : 0);

    // Non-heap memory
    MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
    ObjectNode nonHeapNode = result.putObject("nonHeap");
    nonHeapNode.put("init", nonHeapUsage.getInit());
    nonHeapNode.put("used", nonHeapUsage.getUsed());
    nonHeapNode.put("committed", nonHeapUsage.getCommitted());
    nonHeapNode.put("max", nonHeapUsage.getMax());

    // Memory pools
    ArrayNode poolsArray = result.putArray("pools");
    List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
    for (MemoryPoolMXBean pool : memoryPools) {
      ObjectNode poolNode = poolsArray.addObject();
      poolNode.put("name", pool.getName());
      poolNode.put("type", pool.getType().toString());

      MemoryUsage usage = pool.getUsage();
      poolNode.put("used", usage.getUsed());
      poolNode.put("committed", usage.getCommitted());
      poolNode.put("max", usage.getMax());
      poolNode.put("init", usage.getInit());

      if (pool.isUsageThresholdSupported() && pool.isUsageThresholdExceeded()) {
        poolNode.put("thresholdExceeded", true);
        poolNode.put("usageThreshold", pool.getUsageThreshold());
      }

      // Peak usage
      MemoryUsage peakUsage = pool.getPeakUsage();
      if (peakUsage != null) {
        ObjectNode peakNode = poolNode.putObject("peak");
        peakNode.put("used", peakUsage.getUsed());
        peakNode.put("committed", peakUsage.getCommitted());
      }
    }

    // Object pending finalization (deprecated in Java 18+, removed in Java 21+)
    try {
      // Use reflection to check if the method exists and call it
      java.lang.reflect.Method method = memoryBean.getClass().getMethod("getObjectPendingFinalizationCount");
      int count = (int) method.invoke(memoryBean);
      result.put("objectsPendingFinalization", count);
    } catch (NoSuchMethodException e) {
      // Method doesn't exist in this JVM version (Java 21+)
      result.put("objectsPendingFinalization", "Not available (removed in Java 21+)");
    } catch (Exception e) {
      // Other errors - just skip this metric
      result.put("objectsPendingFinalization", "Not available");
    }

    return result;
  }

  private String getCpuMetrics() throws Exception {
    ObjectNode result = getCpuMetricsNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getCpuMetricsNode() {
    ObjectNode result = mapper.createObjectNode();

    java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

    // Basic OS info
    result.put("availableProcessors", osBean.getAvailableProcessors());
    result.put("systemLoadAverage", osBean.getSystemLoadAverage());
    result.put("arch", osBean.getArch());
    result.put("osName", osBean.getName());
    result.put("osVersion", osBean.getVersion());

    // Try to get more detailed CPU metrics if available (Oracle JDK)
    if (osBean instanceof OperatingSystemMXBean) {
      OperatingSystemMXBean sunOsBean = (OperatingSystemMXBean) osBean;

      result.put("processCpuLoad", sunOsBean.getProcessCpuLoad() * 100);
      result.put("systemCpuLoad", sunOsBean.getCpuLoad() * 100);
      result.put("processCpuTime", sunOsBean.getProcessCpuTime());
      result.put("freePhysicalMemory", sunOsBean.getFreeMemorySize());
      result.put("totalPhysicalMemory", sunOsBean.getTotalMemorySize());
      result.put("freeSwapSpace", sunOsBean.getFreeSwapSpaceSize());
      result.put("totalSwapSpace", sunOsBean.getTotalSwapSpaceSize());
      result.put("committedVirtualMemory", sunOsBean.getCommittedVirtualMemorySize());
    }

    return result;
  }

  private String getThreadMetrics() throws Exception {
    ObjectNode result = getThreadMetricsNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getThreadMetricsNode() {
    ObjectNode result = mapper.createObjectNode();
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    // Thread counts
    result.put("threadCount", threadBean.getThreadCount());
    result.put("peakThreadCount", threadBean.getPeakThreadCount());
    result.put("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());
    result.put("daemonThreadCount", threadBean.getDaemonThreadCount());

    // Thread state distribution
    ObjectNode stateDistribution = result.putObject("stateDistribution");
    ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadBean.getAllThreadIds());

    int newCount = 0, runnableCount = 0, blockedCount = 0, waitingCount = 0, timedWaitingCount = 0, terminatedCount = 0;

    for (ThreadInfo info : threadInfos) {
      if (info != null) {
        switch (info.getThreadState()) {
        case NEW:
          newCount++;
          break;
        case RUNNABLE:
          runnableCount++;
          break;
        case BLOCKED:
          blockedCount++;
          break;
        case WAITING:
          waitingCount++;
          break;
        case TIMED_WAITING:
          timedWaitingCount++;
          break;
        case TERMINATED:
          terminatedCount++;
          break;
        }
      }
    }

    stateDistribution.put("NEW", newCount);
    stateDistribution.put("RUNNABLE", runnableCount);
    stateDistribution.put("BLOCKED", blockedCount);
    stateDistribution.put("WAITING", waitingCount);
    stateDistribution.put("TIMED_WAITING", timedWaitingCount);
    stateDistribution.put("TERMINATED", terminatedCount);

    // Deadlock detection
    long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();
    if (deadlockedThreadIds != null && deadlockedThreadIds.length > 0) {
      ArrayNode deadlocksArray = result.putArray("deadlockedThreads");
      ThreadInfo[] deadlockedThreads = threadBean.getThreadInfo(deadlockedThreadIds);
      for (ThreadInfo info : deadlockedThreads) {
        if (info != null) {
          deadlocksArray.add(info.getThreadName() + " (ID: " + info.getThreadId() + ")");
        }
      }
    } else {
      result.put("deadlockedThreads", 0);
    }

    // Monitor deadlock detection
    long[] monitorDeadlockedThreadIds = threadBean.findMonitorDeadlockedThreads();
    if (monitorDeadlockedThreadIds != null && monitorDeadlockedThreadIds.length > 0) {
      result.put("monitorDeadlockedThreadCount", monitorDeadlockedThreadIds.length);
    } else {
      result.put("monitorDeadlockedThreadCount", 0);
    }

    // CPU time support
    result.put("currentThreadCpuTimeSupported", threadBean.isCurrentThreadCpuTimeSupported());
    result.put("threadCpuTimeSupported", threadBean.isThreadCpuTimeSupported());
    result.put("threadContentionMonitoringSupported", threadBean.isThreadContentionMonitoringSupported());

    if (threadBean.isThreadContentionMonitoringSupported()) {
      result.put("threadContentionMonitoringEnabled", threadBean.isThreadContentionMonitoringEnabled());
    }

    return result;
  }

  private String getGcMetrics() throws Exception {
    ObjectNode result = getGcMetricsNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getGcMetricsNode() {
    ObjectNode result = mapper.createObjectNode();
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    ArrayNode collectorsArray = result.putArray("collectors");
    long totalCollectionCount = 0;
    long totalCollectionTime = 0;

    for (GarbageCollectorMXBean gc : gcBeans) {
      ObjectNode gcNode = collectorsArray.addObject();
      gcNode.put("name", gc.getName());
      gcNode.put("collectionCount", gc.getCollectionCount());
      gcNode.put("collectionTime", gc.getCollectionTime());

      ArrayNode poolNamesArray = gcNode.putArray("memoryPoolNames");
      for (String poolName : gc.getMemoryPoolNames()) {
        poolNamesArray.add(poolName);
      }

      // Calculate averages
      if (gc.getCollectionCount() > 0) {
        gcNode.put("averageCollectionTime", (double) gc.getCollectionTime() / gc.getCollectionCount());
      }

      totalCollectionCount += gc.getCollectionCount();
      totalCollectionTime += gc.getCollectionTime();
    }

    // Summary
    ObjectNode summaryNode = result.putObject("summary");
    summaryNode.put("totalCollectionCount", totalCollectionCount);
    summaryNode.put("totalCollectionTime", totalCollectionTime);
    summaryNode.put("collectorCount", gcBeans.size());

    if (totalCollectionCount > 0) {
      summaryNode.put("averageCollectionTime", (double) totalCollectionTime / totalCollectionCount);
    }

    return result;
  }

  private String getCompilationMetrics() throws Exception {
    ObjectNode result = getCompilationMetricsNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getCompilationMetricsNode() {
    ObjectNode result = mapper.createObjectNode();
    CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();

    if (compilationBean != null) {
      result.put("name", compilationBean.getName());
      result.put("totalCompilationTime", compilationBean.getTotalCompilationTime());
      result.put("compilationTimeMonitoringSupported", compilationBean.isCompilationTimeMonitoringSupported());
    } else {
      result.put("available", false);
    }

    return result;
  }
}