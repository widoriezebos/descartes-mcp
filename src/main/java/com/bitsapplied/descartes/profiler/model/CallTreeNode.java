package com.bitsapplied.descartes.profiler.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a node in a call tree, showing the calling relationships between
 * methods and their relative time consumption.
 *
 * <p>
 * Each node tracks:
 * <ul>
 * <li>Self time: Time spent directly in this method</li>
 * <li>Cumulative time: Time spent in this method + all callees</li>
 * <li>Hit count: Number of times this method appeared in samples</li>
 * <li>Children: Methods called by this method</li>
 * </ul>
 */
public class CallTreeNode {

  private final String methodSignature; // "com.foo.Bar.method(Bar.java:123)"
  private final String className;
  private final String methodName;
  private final String sourceFile;
  private final int lineNumber;

  private final AtomicLong hitCount = new AtomicLong(0);
  private final Map<String, CallTreeNode> children = new HashMap<>();

  public CallTreeNode(String methodSignature, String className, String methodName, String sourceFile, int lineNumber) {
    this.methodSignature = Objects.requireNonNull(methodSignature);
    this.className = className;
    this.methodName = methodName;
    this.sourceFile = sourceFile;
    this.lineNumber = lineNumber;
  }

  public String getMethodSignature() {
    return methodSignature;
  }

  public String getClassName() {
    return className;
  }

  public String getMethodName() {
    return methodName;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public int getLineNumber() {
    return lineNumber;
  }

  public long getHitCount() {
    return hitCount.get();
  }

  public void incrementHitCount() {
    hitCount.incrementAndGet();
  }

  public void incrementHitCount(long delta) {
    hitCount.addAndGet(delta);
  }

  /**
   * Get or create a child node for the given method.
   */
  public CallTreeNode getOrCreateChild(String signature, String className, String methodName, String sourceFile,
      int lineNumber) {
    return children.computeIfAbsent(signature,
        _key -> new CallTreeNode(signature, className, methodName, sourceFile, lineNumber));
  }

  public List<CallTreeNode> getChildren() {
    List<CallTreeNode> childList = new ArrayList<>(children.values());
    // Sort by hit count descending
    childList.sort((a, b) -> Long.compare(b.getHitCount(), a.getHitCount()));
    return Collections.unmodifiableList(childList);
  }

  /**
   * Calculate self time percentage (time spent directly in this method).
   *
   * @param totalSamples Total samples in profile
   * @return Percentage (0-100)
   */
  public double getSelfTimePercent(long totalSamples) {
    if (totalSamples == 0)
      return 0.0;
    return (hitCount.get() * 100.0) / totalSamples;
  }

  /**
   * Calculate cumulative time (self + all descendants).
   *
   * @return Total hit count including all children
   */
  public long getCumulativeTime() {
    long cumulative = hitCount.get();
    for (CallTreeNode child : children.values()) {
      cumulative += child.getCumulativeTime();
    }
    return cumulative;
  }

  /**
   * Calculate cumulative time percentage.
   *
   * @param totalSamples Total samples in profile
   * @return Percentage (0-100)
   */
  public double getCumulativeTimePercent(long totalSamples) {
    if (totalSamples == 0)
      return 0.0;
    return (getCumulativeTime() * 100.0) / totalSamples;
  }

  /**
   * Convert to map for JSON serialization.
   *
   * @param totalSamples Total samples for percentage calculation
   * @param maxDepth     Maximum depth to serialize (prevent infinite recursion)
   * @return Map representation
   */
  public Map<String, Object> toMap(long totalSamples, int maxDepth) {
    Map<String, Object> map = new HashMap<>();
    map.put("method", methodName);
    map.put("class", className);
    map.put("file", sourceFile != null ? sourceFile : "unknown");
    map.put("line", lineNumber);
    map.put("signature", methodSignature);
    map.put("self_percentage", String.format("%.2f%%", getSelfTimePercent(totalSamples)));
    map.put("self_percentage_value", getSelfTimePercent(totalSamples));
    map.put("cumulative_percentage", String.format("%.2f%%", getCumulativeTimePercent(totalSamples)));
    map.put("cumulative_percentage_value", getCumulativeTimePercent(totalSamples));
    map.put("hit_count", hitCount.get());

    if (maxDepth > 0 && !children.isEmpty()) {
      List<Map<String, Object>> childrenMaps = new ArrayList<>();
      for (CallTreeNode child : getChildren()) {
        childrenMaps.add(child.toMap(totalSamples, maxDepth - 1));
      }
      map.put("children", childrenMaps);
    } else {
      map.put("children", Collections.emptyList());
    }

    return map;
  }

  /**
   * Convert to map without depth limit.
   */
  public Map<String, Object> toMap(long totalSamples) {
    return toMap(totalSamples, 10); // Default depth
  }

  @Override
  public String toString() {
    return String.format("%s (hits: %d, self: %.2f%%)", methodSignature, hitCount.get(), 0.0 // Would need
                                                                                             // totalSamples
    );
  }

  /**
   * Print tree structure as text.
   *
   * @param totalSamples Total samples for percentage calculation
   * @param indent       Current indentation level
   * @return Text representation
   */
  public String toTreeString(long totalSamples, String indent) {
    StringBuilder sb = new StringBuilder();
    sb.append(indent).append(
        String.format("%.2f%% (%d) - %s\n", getCumulativeTimePercent(totalSamples), hitCount.get(), methodSignature));

    for (CallTreeNode child : getChildren()) {
      sb.append(child.toTreeString(totalSamples, indent + "  "));
    }

    return sb.toString();
  }

  public String toTreeString(long totalSamples) {
    return toTreeString(totalSamples, "");
  }
}
