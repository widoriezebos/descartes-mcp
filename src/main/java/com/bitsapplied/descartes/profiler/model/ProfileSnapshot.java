package com.bitsapplied.descartes.profiler.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Aggregated profiling data containing hotspots, call trees, and insights from
 * a profiling session.
 *
 * <p>
 * This is the main data structure returned by the profiler and consumed by MCP
 * tools, UI panels, and export functions.
 */
public class ProfileSnapshot {

  private final ProfileMetadata metadata;
  private final long totalSamples;
  private final List<Hotspot> cpuHotspots;
  private final List<Hotspot> allocationHotspots;
  private final List<Hotspot> lockHotspots;
  private final Map<String, CallTreeNode> callTrees;
  private final List<String> insights;
  private final List<String> recommendations;
  private final Map<String, Object> rawMetrics;

  private ProfileSnapshot(Builder builder) {
    this.metadata = builder.metadata;
    this.totalSamples = builder.totalSamples;
    this.cpuHotspots = Collections.unmodifiableList(new ArrayList<>(builder.cpuHotspots));
    this.allocationHotspots = Collections.unmodifiableList(new ArrayList<>(builder.allocationHotspots));
    this.lockHotspots = Collections.unmodifiableList(new ArrayList<>(builder.lockHotspots));
    this.callTrees = Collections.unmodifiableMap(new HashMap<>(builder.callTrees));
    this.insights = Collections.unmodifiableList(new ArrayList<>(builder.insights));
    this.recommendations = Collections.unmodifiableList(new ArrayList<>(builder.recommendations));
    this.rawMetrics = Collections.unmodifiableMap(new HashMap<>(builder.rawMetrics));
  }

  public ProfileMetadata getMetadata() {
    return metadata;
  }

  public long getTotalSamples() {
    return totalSamples;
  }

  public List<Hotspot> getCPUHotspots(int topN) {
    return cpuHotspots.stream().limit(topN).collect(Collectors.toList());
  }

  public List<Hotspot> getAllCPUHotspots() {
    return cpuHotspots;
  }

  public List<Hotspot> getAllocationHotspots(int topN) {
    return allocationHotspots.stream().limit(topN).collect(Collectors.toList());
  }

  public List<Hotspot> getAllAllocationHotspots() {
    return allocationHotspots;
  }

  public List<Hotspot> getLockHotspots(int topN) {
    return lockHotspots.stream().limit(topN).collect(Collectors.toList());
  }

  public List<Hotspot> getAllLockHotspots() {
    return lockHotspots;
  }

  public Map<String, CallTreeNode> getCallTrees() {
    return callTrees;
  }

  /**
   * Get call tree for a specific method.
   *
   * @param methodPattern Pattern to match (e.g., "recallSimilar",
   *                      "*.recallSimilar", "com.foo.Bar.method")
   * @return Call tree node, or null if not found
   */
  public CallTreeNode getCallTree(String methodPattern) {
    // Exact match first
    if (callTrees.containsKey(methodPattern)) {
      return callTrees.get(methodPattern);
    }

    // Pattern matching
    for (Map.Entry<String, CallTreeNode> entry : callTrees.entrySet()) {
      if (matchesPattern(entry.getKey(), methodPattern)) {
        return entry.getValue();
      }
    }

    return null;
  }

  /**
   * Find all methods matching a pattern.
   *
   * @param methodPattern Pattern to match
   * @return List of matching method signatures
   */
  public List<String> findMethods(String methodPattern) {
    return callTrees.keySet().stream().filter(sig -> matchesPattern(sig, methodPattern)).collect(Collectors.toList());
  }

  private boolean matchesPattern(String signature, String pattern) {
    // Simple pattern matching: * = wildcard
    if (pattern.contains("*")) {
      String regex = pattern.replace(".", "\\.").replace("*", ".*");
      return signature.matches(regex);
    }

    // Substring match
    return signature.contains(pattern);
  }

  public List<String> getInsights() {
    return insights;
  }

  public List<String> getRecommendations() {
    return recommendations;
  }

  public Map<String, Object> getRawMetrics() {
    return rawMetrics;
  }

  /**
   * Get duration in seconds.
   */
  public long getDurationSeconds() {
    return metadata.getDurationSeconds();
  }

  /**
   * Convert to JSON-serializable map.
   *
   * @return Map representation suitable for JSON export
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();

    map.put("metadata", metadata.toMap());
    map.put("total_samples", totalSamples);

    // Hotspots
    map.put("cpu_hotspots", cpuHotspots.stream().limit(50).map(Hotspot::toMap).collect(Collectors.toList()));
    map.put("allocation_hotspots",
        allocationHotspots.stream().limit(50).map(Hotspot::toMap).collect(Collectors.toList()));
    map.put("lock_hotspots", lockHotspots.stream().limit(50).map(Hotspot::toMap).collect(Collectors.toList()));

    // Call trees (top 20 most expensive)
    List<Map<String, Object>> callTreeMaps = callTrees.values().stream()
        .sorted((a, b) -> Long.compare(b.getCumulativeTime(), a.getCumulativeTime())).limit(20)
        .map(tree -> tree.toMap(totalSamples, 10)).collect(Collectors.toList());
    map.put("call_trees", callTreeMaps);

    // Insights and recommendations
    map.put("insights", insights);
    map.put("recommendations", recommendations);

    return map;
  }

  /**
   * Generate human-readable summary.
   */
  public String getSummary() {
    StringBuilder sb = new StringBuilder();
    sb.append("Profile Summary\n");
    sb.append("==============\n\n");

    sb.append("Profile ID: ").append(metadata.getProfileId()).append("\n");
    sb.append("Duration: ").append(getDurationSeconds()).append(" seconds\n");
    sb.append("Total Samples: ").append(totalSamples).append("\n");
    sb.append("Sampling Interval: ").append(metadata.getConfig().getSamplingIntervalMs()).append(" ms\n\n");

    if (!cpuHotspots.isEmpty()) {
      sb.append("Top CPU Hotspots:\n");
      for (Hotspot hotspot : getCPUHotspots(10)) {
        sb.append(String.format("  %.2f%% - %s.%s (%s)\n", hotspot.getPercentage(), hotspot.getClassName(),
            hotspot.getMethodName(), hotspot.getSourceLocation()));
      }
      sb.append("\n");
    }

    if (!allocationHotspots.isEmpty()) {
      sb.append("Top Allocation Hotspots:\n");
      for (Hotspot hotspot : getAllocationHotspots(10)) {
        sb.append(String.format("  %.2f%% - %s.%s (%s)\n", hotspot.getPercentage(), hotspot.getClassName(),
            hotspot.getMethodName(), hotspot.getSourceLocation()));
      }
      sb.append("\n");
    }

    if (!insights.isEmpty()) {
      sb.append("Insights:\n");
      for (String insight : insights) {
        sb.append("  - ").append(insight).append("\n");
      }
      sb.append("\n");
    }

    if (!recommendations.isEmpty()) {
      sb.append("Recommendations:\n");
      for (String rec : recommendations) {
        sb.append("  - ").append(rec).append("\n");
      }
    }

    return sb.toString();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private ProfileMetadata metadata;
    private long totalSamples;
    private List<Hotspot> cpuHotspots = new ArrayList<>();
    private List<Hotspot> allocationHotspots = new ArrayList<>();
    private List<Hotspot> lockHotspots = new ArrayList<>();
    private Map<String, CallTreeNode> callTrees = new HashMap<>();
    private List<String> insights = new ArrayList<>();
    private List<String> recommendations = new ArrayList<>();
    private Map<String, Object> rawMetrics = new HashMap<>();

    public Builder metadata(ProfileMetadata metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder totalSamples(long totalSamples) {
      this.totalSamples = totalSamples;
      return this;
    }

    public Builder cpuHotspots(List<Hotspot> hotspots) {
      this.cpuHotspots = new ArrayList<>(hotspots);
      return this;
    }

    public Builder addCPUHotspot(Hotspot hotspot) {
      this.cpuHotspots.add(hotspot);
      return this;
    }

    public Builder allocationHotspots(List<Hotspot> hotspots) {
      this.allocationHotspots = new ArrayList<>(hotspots);
      return this;
    }

    public Builder addAllocationHotspot(Hotspot hotspot) {
      this.allocationHotspots.add(hotspot);
      return this;
    }

    public Builder lockHotspots(List<Hotspot> hotspots) {
      this.lockHotspots = new ArrayList<>(hotspots);
      return this;
    }

    public Builder addLockHotspot(Hotspot hotspot) {
      this.lockHotspots.add(hotspot);
      return this;
    }

    public Builder callTrees(Map<String, CallTreeNode> trees) {
      this.callTrees = new HashMap<>(trees);
      return this;
    }

    public Builder addCallTree(String signature, CallTreeNode tree) {
      this.callTrees.put(signature, tree);
      return this;
    }

    public Builder insights(List<String> insights) {
      this.insights = new ArrayList<>(insights);
      return this;
    }

    public Builder addInsight(String insight) {
      this.insights.add(insight);
      return this;
    }

    public Builder recommendations(List<String> recommendations) {
      this.recommendations = new ArrayList<>(recommendations);
      return this;
    }

    public Builder addRecommendation(String recommendation) {
      this.recommendations.add(recommendation);
      return this;
    }

    public Builder rawMetrics(Map<String, Object> metrics) {
      this.rawMetrics = new HashMap<>(metrics);
      return this;
    }

    public Builder addRawMetric(String key, Object value) {
      this.rawMetrics.put(key, value);
      return this;
    }

    public ProfileSnapshot build() {
      Objects.requireNonNull(metadata, "metadata");

      // Sort hotspots by percentage descending
      cpuHotspots.sort((a, b) -> Double.compare(b.getPercentage(), a.getPercentage()));
      allocationHotspots.sort((a, b) -> Double.compare(b.getPercentage(), a.getPercentage()));
      lockHotspots.sort((a, b) -> Double.compare(b.getPercentage(), a.getPercentage()));

      return new ProfileSnapshot(this);
    }
  }

  @Override
  public String toString() {
    return String.format("ProfileSnapshot{id='%s', samples=%d, cpuHotspots=%d, duration=%ds}", metadata.getProfileId(),
        totalSamples, cpuHotspots.size(), getDurationSeconds());
  }
}
