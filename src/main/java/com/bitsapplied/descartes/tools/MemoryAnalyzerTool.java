package com.bitsapplied.descartes.tools;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP tool for detailed JVM memory analysis including heap, garbage collection,
 * and memory pool statistics.
 */
public class MemoryAnalyzerTool implements MCPTool {
  private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
  private final ExecutorService executor;

  public MemoryAnalyzerTool() {
    this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, "MemoryAnalyzerTool-" + THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
      }
    });
  }

  @Override
  public String getToolName() {
    return "memory_analyzer";
  }

  @Override
  public String getToolDescription() {
    return "Comprehensive JVM memory analysis tool for monitoring and debugging memory usage. "
        + "Provides detailed insights into heap/non-heap memory consumption, individual memory pool usage (Eden, Survivor, Old Gen), "
        + "garbage collection performance metrics, and class loading statistics. Supports forced GC for accurate measurements. "
        + "Essential for identifying memory leaks, optimizing heap settings, and understanding GC behavior in production applications.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties", Map.of("operation", Map.of("type", "string", "enum",
        List.of("overview", "heap_detail", "gc_stats", "memory_pools", "class_loading"), "description",
        "Analysis operation: 'overview' for general memory status with heap/non-heap usage, "
            + "'heap_detail' for detailed heap pool breakdown, 'gc_stats' for garbage collector performance metrics, "
            + "'memory_pools' for all memory pool details including thresholds, 'class_loading' for loaded class statistics"),
        "force_gc",
        Map.of("type", "boolean", "description",
            "Force full garbage collection before analysis to get accurate 'used' memory readings. "
                + "Only applies to 'overview' operation. May cause brief application pause",
            "default", false)),
        "required", List.of("operation"));
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        String operation = (String) arguments.get("operation");
        Object forceGcObj = arguments.getOrDefault("force_gc", false);
        boolean forceGc = false;
        if (forceGcObj instanceof Boolean) {
          forceGc = (Boolean) forceGcObj;
        } else if (forceGcObj instanceof String) {
          forceGc = Boolean.parseBoolean((String) forceGcObj);
        }

        if (operation == null) {
          throw new IllegalArgumentException("Operation is required");
        }

        Map<String, Object> result = switch (operation) {
        case "overview" -> getMemoryOverview(forceGc);
        case "heap_detail" -> getHeapDetail();
        case "gc_stats" -> getGCStatistics();
        case "memory_pools" -> getMemoryPools();
        case "class_loading" -> getClassLoadingStats();
        default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };

        return ToolResponse.successJson(result);
      } catch (Exception e) {
        return ToolResponse.error(9999, "Memory analysis failed: " + e.getMessage());
      }
    }, executor);
  }

  /**
   * Get comprehensive memory overview.
   */
  private Map<String, Object> getMemoryOverview(boolean forceGc) {
    if (forceGc) {
      System.gc();
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    Runtime runtime = Runtime.getRuntime();
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = runtime.maxMemory();

    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

    // Calculate percentages with divide-by-zero protection
    double heapUsedPercent = (heapUsage.getMax() > 0) ? (double) heapUsage.getUsed() / heapUsage.getMax() * 100 : 0.0;
    double jvmUsedPercent = (maxMemory > 0) ? (double) usedMemory / maxMemory * 100 : 0.0;

    Map<String, Object> overview = new HashMap<>();
    overview.put("status", "success");
    overview.put("gc_performed", forceGc);

    overview.put("jvm_memory",
        Map.of("used_mb", usedMemory / (1024 * 1024), "free_mb", freeMemory / (1024 * 1024), "total_mb",
            totalMemory / (1024 * 1024), "max_mb", maxMemory / (1024 * 1024), "used_percentage", jvmUsedPercent));

    overview.put("heap_memory",
        Map.of("init_mb", heapUsage.getInit() / (1024 * 1024), "used_mb", heapUsage.getUsed() / (1024 * 1024),
            "committed_mb", heapUsage.getCommitted() / (1024 * 1024), "max_mb", heapUsage.getMax() / (1024 * 1024),
            "used_percentage", heapUsedPercent));

    overview.put("non_heap_memory",
        Map.of("init_mb", nonHeapUsage.getInit() / (1024 * 1024), "used_mb", nonHeapUsage.getUsed() / (1024 * 1024),
            "committed_mb", nonHeapUsage.getCommitted() / (1024 * 1024), "max_mb",
            nonHeapUsage.getMax() == -1 ? "unlimited" : nonHeapUsage.getMax() / (1024 * 1024)));

    // Add quick GC stats
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    long totalGcCount = 0;
    long totalGcTime = 0;
    for (GarbageCollectorMXBean gcBean : gcBeans) {
      totalGcCount += gcBean.getCollectionCount();
      totalGcTime += gcBean.getCollectionTime();
    }

    overview.put("gc_summary", Map.of("total_collections", totalGcCount, "total_time_ms", totalGcTime));

    return overview;
  }

  /**
   * Get detailed heap analysis.
   */
  private Map<String, Object> getHeapDetail() {
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

    // Get memory pools for heap
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
    List<Map<String, Object>> heapPools = new ArrayList<>();

    for (MemoryPoolMXBean pool : pools) {
      if (pool.getType() == java.lang.management.MemoryType.HEAP) {
        MemoryUsage usage = pool.getUsage();
        Map<String, Object> poolInfo = new HashMap<>();
        poolInfo.put("name", pool.getName());
        poolInfo.put("used_mb", usage.getUsed() / (1024 * 1024));
        poolInfo.put("committed_mb", usage.getCommitted() / (1024 * 1024));
        poolInfo.put("max_mb", usage.getMax() == -1 ? "unlimited" : usage.getMax() / (1024 * 1024));

        if (usage.getMax() > 0) {
          poolInfo.put("used_percentage", (double) usage.getUsed() / usage.getMax() * 100);
        }

        // Peak usage
        MemoryUsage peakUsage = pool.getPeakUsage();
        if (peakUsage != null) {
          poolInfo.put("peak_used_mb", peakUsage.getUsed() / (1024 * 1024));
        }

        heapPools.add(poolInfo);
      }
    }

    return Map.of("status", "success", "heap_usage",
        Map.of("init_mb", heapUsage.getInit() / (1024 * 1024), "used_mb", heapUsage.getUsed() / (1024 * 1024),
            "committed_mb", heapUsage.getCommitted() / (1024 * 1024), "max_mb", heapUsage.getMax() / (1024 * 1024)),
        "heap_pools", heapPools);
  }

  /**
   * Get garbage collection statistics.
   */
  private Map<String, Object> getGCStatistics() {
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    List<Map<String, Object>> gcStats = new ArrayList<>();

    long totalCollections = 0;
    long totalTime = 0;

    for (GarbageCollectorMXBean gcBean : gcBeans) {
      Map<String, Object> gcInfo = new HashMap<>();
      gcInfo.put("name", gcBean.getName());
      gcInfo.put("collection_count", gcBean.getCollectionCount());
      gcInfo.put("collection_time_ms", gcBean.getCollectionTime());

      if (gcBean.getCollectionCount() > 0) {
        gcInfo.put("avg_time_ms", gcBean.getCollectionTime() / gcBean.getCollectionCount());
      }

      // Memory pool names this GC manages
      String[] poolNames = gcBean.getMemoryPoolNames();
      gcInfo.put("memory_pools", List.of(poolNames));

      gcStats.add(gcInfo);

      totalCollections += gcBean.getCollectionCount();
      totalTime += gcBean.getCollectionTime();
    }

    return Map.of("status", "success", "collectors", gcStats, "summary", Map.of("total_collections", totalCollections,
        "total_time_ms", totalTime, "avg_time_ms", totalCollections > 0 ? totalTime / totalCollections : 0));
  }

  /**
   * Get detailed memory pool information.
   */
  private Map<String, Object> getMemoryPools() {
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
    List<Map<String, Object>> poolList = new ArrayList<>();

    for (MemoryPoolMXBean pool : pools) {
      Map<String, Object> poolInfo = new HashMap<>();
      poolInfo.put("name", pool.getName());
      poolInfo.put("type", pool.getType().toString());

      MemoryUsage usage = pool.getUsage();
      poolInfo.put("usage",
          Map.of("init_mb", usage.getInit() / (1024 * 1024), "used_mb", usage.getUsed() / (1024 * 1024), "committed_mb",
              usage.getCommitted() / (1024 * 1024), "max_mb",
              usage.getMax() == -1 ? "unlimited" : usage.getMax() / (1024 * 1024)));

      // Peak usage
      MemoryUsage peakUsage = pool.getPeakUsage();
      if (peakUsage != null) {
        poolInfo.put("peak_usage_mb", peakUsage.getUsed() / (1024 * 1024));
      }

      // Thresholds
      if (pool.isUsageThresholdSupported()) {
        poolInfo.put("usage_threshold_supported", true);
        poolInfo.put("usage_threshold_exceeded", pool.isUsageThresholdExceeded());
        if (pool.getUsageThreshold() > 0) {
          poolInfo.put("usage_threshold_mb", pool.getUsageThreshold() / (1024 * 1024));
        }
      }

      if (pool.isCollectionUsageThresholdSupported()) {
        poolInfo.put("collection_threshold_supported", true);
        poolInfo.put("collection_threshold_exceeded", pool.isCollectionUsageThresholdExceeded());
      }

      poolList.add(poolInfo);
    }

    return Map.of("status", "success", "pool_count", poolList.size(), "pools", poolList);
  }

  /**
   * Get class loading statistics.
   */
  private Map<String, Object> getClassLoadingStats() {
    ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();

    return Map.of("status", "success", "loaded_class_count", classLoadingMXBean.getLoadedClassCount(),
        "total_loaded_class_count", classLoadingMXBean.getTotalLoadedClassCount(), "unloaded_class_count",
        classLoadingMXBean.getUnloadedClassCount(), "is_verbose", classLoadingMXBean.isVerbose());
  }
}
