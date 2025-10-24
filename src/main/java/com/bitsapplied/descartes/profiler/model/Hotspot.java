package com.bitsapplied.descartes.profiler.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a performance hotspot - a method or location consuming significant
 * CPU time, memory allocations, or lock contention.
 */
public class Hotspot implements Comparable<Hotspot> {

  private final int rank;
  private final String methodName;
  private final String className;
  private final String sourceFile;
  private final int lineNumber;
  private final double percentage;
  private final long sampleCount;
  private final HotspotType type;
  private final String description;

  public enum HotspotType {
    CPU, ALLOCATION, LOCK, IO
  }

  private Hotspot(Builder builder) {
    this.rank = builder.rank;
    this.methodName = builder.methodName;
    this.className = builder.className;
    this.sourceFile = builder.sourceFile;
    this.lineNumber = builder.lineNumber;
    this.percentage = builder.percentage;
    this.sampleCount = builder.sampleCount;
    this.type = builder.type;
    this.description = builder.description;
  }

  public int getRank() {
    return rank;
  }

  public String getMethodName() {
    return methodName;
  }

  public String getClassName() {
    return className;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public int getLineNumber() {
    return lineNumber;
  }

  public double getPercentage() {
    return percentage;
  }

  public long getSampleCount() {
    return sampleCount;
  }

  public HotspotType getType() {
    return type;
  }

  public String getDescription() {
    return description;
  }

  /**
   * Get fully qualified method signature (class + method).
   */
  public String getFullMethodName() {
    return className + "." + methodName;
  }

  /**
   * Get source location (file:line).
   */
  public String getSourceLocation() {
    if (sourceFile != null && lineNumber > 0) {
      return sourceFile + ":" + lineNumber;
    } else if (sourceFile != null) {
      return sourceFile;
    } else {
      return "unknown";
    }
  }

  /**
   * Convert to map for JSON serialization.
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("rank", rank);
    map.put("method", methodName);
    map.put("class", className);
    map.put("file", sourceFile != null ? sourceFile : "unknown");
    map.put("line", lineNumber);
    map.put("percentage", String.format("%.2f%%", percentage));
    map.put("percentage_value", percentage);
    map.put("samples", sampleCount);
    map.put("type", type.name());
    if (description != null) {
      map.put("description", description);
    }
    return map;
  }

  @Override
  public int compareTo(Hotspot other) {
    // Sort by percentage descending
    return Double.compare(other.percentage, this.percentage);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    Hotspot hotspot = (Hotspot) o;
    return rank == hotspot.rank && Objects.equals(className, hotspot.className)
        && Objects.equals(methodName, hotspot.methodName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rank, className, methodName);
  }

  @Override
  public String toString() {
    return String.format("#%d: %s.%s (%s) - %.2f%% (%d samples)", rank, className, methodName, getSourceLocation(),
        percentage, sampleCount);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private int rank;
    private String methodName;
    private String className;
    private String sourceFile;
    private int lineNumber = -1;
    private double percentage;
    private long sampleCount;
    private HotspotType type = HotspotType.CPU;
    private String description;

    public Builder rank(int rank) {
      this.rank = rank;
      return this;
    }

    public Builder methodName(String methodName) {
      this.methodName = methodName;
      return this;
    }

    public Builder className(String className) {
      this.className = className;
      return this;
    }

    public Builder sourceFile(String sourceFile) {
      this.sourceFile = sourceFile;
      return this;
    }

    public Builder lineNumber(int lineNumber) {
      this.lineNumber = lineNumber;
      return this;
    }

    public Builder percentage(double percentage) {
      this.percentage = percentage;
      return this;
    }

    public Builder sampleCount(long sampleCount) {
      this.sampleCount = sampleCount;
      return this;
    }

    public Builder type(HotspotType type) {
      this.type = type;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Hotspot build() {
      Objects.requireNonNull(methodName, "methodName");
      Objects.requireNonNull(className, "className");
      return new Hotspot(this);
    }
  }
}
