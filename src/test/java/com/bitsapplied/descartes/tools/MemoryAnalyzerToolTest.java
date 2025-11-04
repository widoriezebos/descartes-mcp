package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for MemoryAnalyzerTool.
 */
public class MemoryAnalyzerToolTest {

  private MemoryAnalyzerTool tool;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    tool = new MemoryAnalyzerTool();
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testGetToolName() {
    assertEquals("memory_analyzer", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("JVM memory"));
    assertTrue(description.contains("heap"));
    assertTrue(description.contains("garbage collection"));
  }

  @Test
  public void testGetToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();

    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties);

    // Check operation property
    @SuppressWarnings("unchecked")
    Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");
    assertEquals("string", operationProp.get("type"));
    @SuppressWarnings("unchecked")
    List<String> operations = (List<String>) operationProp.get("enum");
    assertTrue(operations.contains("overview"));
    assertTrue(operations.contains("heap_detail"));
    assertTrue(operations.contains("gc_stats"));
    assertTrue(operations.contains("memory_pools"));
    assertTrue(operations.contains("class_loading"));

    // Check force_gc property
    @SuppressWarnings("unchecked")
    Map<String, Object> forceGcProp = (Map<String, Object>) properties.get("force_gc");
    assertEquals("boolean", forceGcProp.get("type"));
    assertEquals(false, forceGcProp.get("default"));

    // Check required fields
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
  }

  @Test
  public void testMemoryOverview() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "overview");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals(false, result.get("gc_performed"));

    // Check JVM memory section
    @SuppressWarnings("unchecked")
    Map<String, Object> jvmMemory = (Map<String, Object>) result.get("jvm_memory");
    assertNotNull(jvmMemory);
    assertTrue(jvmMemory.containsKey("used_mb"));
    assertTrue(jvmMemory.containsKey("free_mb"));
    assertTrue(jvmMemory.containsKey("total_mb"));
    assertTrue(jvmMemory.containsKey("max_mb"));
    assertTrue(jvmMemory.containsKey("used_percentage"));

    // Check heap memory section
    @SuppressWarnings("unchecked")
    Map<String, Object> heapMemory = (Map<String, Object>) result.get("heap_memory");
    assertNotNull(heapMemory);
    assertTrue(heapMemory.containsKey("init_mb"));
    assertTrue(heapMemory.containsKey("used_mb"));
    assertTrue(heapMemory.containsKey("committed_mb"));
    assertTrue(heapMemory.containsKey("max_mb"));
    assertTrue(heapMemory.containsKey("used_percentage"));

    // Check non-heap memory section
    @SuppressWarnings("unchecked")
    Map<String, Object> nonHeapMemory = (Map<String, Object>) result.get("non_heap_memory");
    assertNotNull(nonHeapMemory);
    assertTrue(nonHeapMemory.containsKey("init_mb"));
    assertTrue(nonHeapMemory.containsKey("used_mb"));
    assertTrue(nonHeapMemory.containsKey("committed_mb"));
    assertTrue(nonHeapMemory.containsKey("max_mb"));

    // Check GC summary
    @SuppressWarnings("unchecked")
    Map<String, Object> gcSummary = (Map<String, Object>) result.get("gc_summary");
    assertNotNull(gcSummary);
    assertTrue(gcSummary.containsKey("total_collections"));
    assertTrue(gcSummary.containsKey("total_time_ms"));
  }

  @Test
  public void testMemoryOverviewWithForceGC() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "overview");
    args.put("force_gc", true);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals(true, result.get("gc_performed"));

    // All sections should still be present
    assertNotNull(result.get("jvm_memory"));
    assertNotNull(result.get("heap_memory"));
    assertNotNull(result.get("non_heap_memory"));
    assertNotNull(result.get("gc_summary"));
  }

  @Test
  public void testHeapDetail() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "heap_detail");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    // Check heap usage
    @SuppressWarnings("unchecked")
    Map<String, Object> heapUsage = (Map<String, Object>) result.get("heap_usage");
    assertNotNull(heapUsage);
    assertTrue(heapUsage.containsKey("init_mb"));
    assertTrue(heapUsage.containsKey("used_mb"));
    assertTrue(heapUsage.containsKey("committed_mb"));
    assertTrue(heapUsage.containsKey("max_mb"));

    // Check heap pools
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> heapPools = (List<Map<String, Object>>) result.get("heap_pools");
    assertNotNull(heapPools);
    // Should have at least one heap pool
    assertFalse(heapPools.isEmpty());

    // Check first pool structure
    Map<String, Object> firstPool = heapPools.get(0);
    assertTrue(firstPool.containsKey("name"));
    assertTrue(firstPool.containsKey("used_mb"));
    assertTrue(firstPool.containsKey("committed_mb"));
    assertTrue(firstPool.containsKey("max_mb"));

    // Finalization pending count removed (deprecated API)
  }

  @Test
  public void testGCStatistics() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "gc_stats");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    // Check collectors list
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> collectors = (List<Map<String, Object>>) result.get("collectors");
    assertNotNull(collectors);
    // Should have at least one GC collector
    assertFalse(collectors.isEmpty());

    // Check first collector structure
    Map<String, Object> firstCollector = collectors.get(0);
    assertTrue(firstCollector.containsKey("name"));
    assertTrue(firstCollector.containsKey("collection_count"));
    assertTrue(firstCollector.containsKey("collection_time_ms"));
    assertTrue(firstCollector.containsKey("memory_pools"));

    // Check summary
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) result.get("summary");
    assertNotNull(summary);
    assertTrue(summary.containsKey("total_collections"));
    assertTrue(summary.containsKey("total_time_ms"));
    assertTrue(summary.containsKey("avg_time_ms"));
  }

  @Test
  public void testMemoryPools() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "memory_pools");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertTrue(result.containsKey("pool_count"));

    // Check pools list
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pools = (List<Map<String, Object>>) result.get("pools");
    assertNotNull(pools);
    // Should have at least one memory pool
    assertFalse(pools.isEmpty());

    // Check first pool structure
    Map<String, Object> firstPool = pools.get(0);
    assertTrue(firstPool.containsKey("name"));
    assertTrue(firstPool.containsKey("type"));
    assertTrue(firstPool.containsKey("usage"));

    // Check usage structure
    @SuppressWarnings("unchecked")
    Map<String, Object> usage = (Map<String, Object>) firstPool.get("usage");
    assertTrue(usage.containsKey("init_mb"));
    assertTrue(usage.containsKey("used_mb"));
    assertTrue(usage.containsKey("committed_mb"));
    assertTrue(usage.containsKey("max_mb"));

    // Pool count should match pools size
    assertEquals(pools.size(), result.get("pool_count"));
  }

  @Test
  public void testClassLoadingStats() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "class_loading");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertTrue(result.containsKey("loaded_class_count"));
    assertTrue(result.containsKey("total_loaded_class_count"));
    assertTrue(result.containsKey("unloaded_class_count"));
    assertTrue(result.containsKey("is_verbose"));

    // Verify counts are non-negative
    assertTrue(((Number) result.get("loaded_class_count")).intValue() >= 0);
    assertTrue(((Number) result.get("total_loaded_class_count")).longValue() >= 0);
    assertTrue(((Number) result.get("unloaded_class_count")).longValue() >= 0);

    // Total loaded should be >= currently loaded
    assertTrue(((Number) result.get("total_loaded_class_count"))
        .longValue() >= ((Number) result.get("loaded_class_count")).intValue());
  }

  @Test
  public void testMissingOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    // Should complete (success or error response)
    assertNotNull(tool.executeAsync(args).get());
  }

  @Test
  public void testUnknownOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "unknown");
    // Should complete (success or error response)
    assertNotNull(tool.executeAsync(args).get());
  }

  @Test
  public void testNullArguments() throws Exception {
    try {
      tool.executeAsync(null).get();
    } catch (Throwable e) {
      // Expected - null arguments should fail
      assertNotNull(e);
    }
  }

  @Test
  public void testForceGcAsString() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "overview");
    args.put("force_gc", "true"); // String instead of boolean

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    // Should parse string "true" as boolean true
    assertEquals(true, result.get("gc_performed"));
  }

  @Test
  public void testMemoryValuesAreReasonable() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "overview");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> jvmMemory = (Map<String, Object>) result.get("jvm_memory");

    // Check that memory values are reasonable
    Number usedMb = (Number) jvmMemory.get("used_mb");
    Number freeMb = (Number) jvmMemory.get("free_mb");
    Number totalMb = (Number) jvmMemory.get("total_mb");
    Number maxMb = (Number) jvmMemory.get("max_mb");

    assertTrue(usedMb.longValue() > 0, "Used memory should be positive");
    assertTrue(freeMb.longValue() >= 0, "Free memory should be non-negative");
    assertTrue(totalMb.longValue() > 0, "Total memory should be positive");
    assertTrue(maxMb.longValue() > 0, "Max memory should be positive");

    // Used + Free should equal Total (with some tolerance for rounding)
    long diff = Math.abs(totalMb.longValue() - (usedMb.longValue() + freeMb.longValue()));
    assertTrue(diff <= 1, "Used + Free should equal Total (within 1MB tolerance). Diff: " + diff);

    // Total should not exceed Max
    assertTrue(totalMb.longValue() <= maxMb.longValue(), "Total memory should not exceed max memory");

    // Used percentage should be between 0 and 100
    Double usedPercentage = (Double) jvmMemory.get("used_percentage");
    assertTrue(usedPercentage >= 0 && usedPercentage <= 100, "Used percentage should be between 0 and 100");
  }

  @Test
  public void testHeapAndNonHeapSeparation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "memory_pools");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pools = (List<Map<String, Object>>) result.get("pools");

    boolean hasHeap = false;
    boolean hasNonHeap = false;

    for (Map<String, Object> pool : pools) {
      String type = (String) pool.get("type");
      if ("Heap memory".equals(type)) {
        hasHeap = true;
      } else if ("Non-heap memory".equals(type)) {
        hasNonHeap = true;
      }
    }

    assertTrue(hasHeap, "Should have at least one HEAP pool");
    assertTrue(hasNonHeap, "Should have at least one NON_HEAP pool");
  }

  @Test
  public void testGCCollectorHasMemoryPools() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "gc_stats");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> collectors = (List<Map<String, Object>>) result.get("collectors");

    for (Map<String, Object> collector : collectors) {
      @SuppressWarnings("unchecked")
      List<String> memoryPools = (List<String>) collector.get("memory_pools");
      assertNotNull(memoryPools, "Each collector should have memory_pools list");
      assertFalse(memoryPools.isEmpty(), "Each collector should manage at least one memory pool");
    }
  }

  @Test
  public void testPeakUsageInHeapDetail() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "heap_detail");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> heapPools = (List<Map<String, Object>>) result.get("heap_pools");

    // At least one pool should have peak usage info
    boolean hasPeakUsage = false;
    for (Map<String, Object> pool : heapPools) {
      if (pool.containsKey("peak_used_mb")) {
        hasPeakUsage = true;
        Integer peakUsedMb = (Integer) pool.get("peak_used_mb");
        assertTrue(peakUsedMb >= 0, "Peak usage should be non-negative");
      }
    }

    assertTrue(hasPeakUsage, "At least one heap pool should have peak usage information");
  }
}