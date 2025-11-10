package com.bitsapplied.descartes.profiler.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for profiling sessions. Defines what events to capture,
 * sampling rates, and filtering options.
 *
 * <p>
 * Use the builder pattern to create configurations:
 *
 * <pre>
 * ProfilerConfig config = ProfilerConfig.builder().duration(Duration.ofSeconds(30)).cpuProfilingEnabled(true)
 *     .allocationProfilingEnabled(true).samplingInterval(10).packageFilter("com.bitsapplied").build();
 * </pre>
 *
 * <p>
 * Or use presets for common scenarios:
 *
 * <pre>
 * ProfilerConfig.lightweight(); // CPU only, low overhead
 * ProfilerConfig.comprehensive(); // All events, full analysis
 * ProfilerConfig.cpuOnly(); // CPU sampling only
 * ProfilerConfig.allocationOnly(); // Memory allocations only
 * </pre>
 */
public class ProfilerConfig {

  private final Duration duration;
  private final int samplingIntervalMs;
  private final boolean cpuProfilingEnabled;
  private final boolean allocationProfilingEnabled;
  private final boolean lockProfilingEnabled;
  private final boolean ioProfilingEnabled;
  private final boolean gcProfilingEnabled;
  private final String packageFilter;
  private final int maxHotspots;
  private final int maxCallTreeDepth;
  private final boolean threadFilterEnabled;
  private final String threadNamePattern;

  private ProfilerConfig(Builder builder) {
    this.duration = builder.duration;
    this.samplingIntervalMs = builder.samplingIntervalMs;
    this.cpuProfilingEnabled = builder.cpuProfilingEnabled;
    this.allocationProfilingEnabled = builder.allocationProfilingEnabled;
    this.lockProfilingEnabled = builder.lockProfilingEnabled;
    this.ioProfilingEnabled = builder.ioProfilingEnabled;
    this.gcProfilingEnabled = builder.gcProfilingEnabled;
    this.packageFilter = builder.packageFilter;
    this.maxHotspots = builder.maxHotspots;
    this.maxCallTreeDepth = builder.maxCallTreeDepth;
    this.threadFilterEnabled = builder.threadFilterEnabled;
    this.threadNamePattern = builder.threadNamePattern;
  }

  // Getters

  public Duration getDuration() {
    return duration;
  }

  public int getSamplingIntervalMs() {
    return samplingIntervalMs;
  }

  public boolean isCPUProfilingEnabled() {
    return cpuProfilingEnabled;
  }

  public boolean isAllocationProfilingEnabled() {
    return allocationProfilingEnabled;
  }

  public boolean isLockProfilingEnabled() {
    return lockProfilingEnabled;
  }

  public boolean isIOProfilingEnabled() {
    return ioProfilingEnabled;
  }

  public boolean isGCProfilingEnabled() {
    return gcProfilingEnabled;
  }

  public String getPackageFilter() {
    return packageFilter;
  }

  public int getMaxHotspots() {
    return maxHotspots;
  }

  public int getMaxCallTreeDepth() {
    return maxCallTreeDepth;
  }

  public boolean isThreadFilterEnabled() {
    return threadFilterEnabled;
  }

  public String getThreadNamePattern() {
    return threadNamePattern;
  }

  // Preset configurations

  /**
   * Lightweight configuration for low-overhead profiling. CPU sampling only with
   * 20ms interval (0.5% overhead).
   *
   * <p>
   * Use when: Production monitoring, always-on profiling, minimal impact
   * required.
   *
   * @return Lightweight profiler configuration
   */
  public static ProfilerConfig lightweight() {
    return builder().duration(Duration.ofSeconds(10)).cpuOnly().samplingInterval(20) // Less frequent
        .maxHotspots(20).maxCallTreeDepth(10).build();
  }

  /**
   * Comprehensive configuration capturing all event types. CPU, allocation,
   * locks, I/O, and GC events with 10ms sampling (~2% overhead).
   *
   * <p>
   * Use when: Deep investigation, full system analysis, finding any bottleneck
   * type.
   *
   * @return Comprehensive profiler configuration
   */
  public static ProfilerConfig comprehensive() {
    return builder().duration(Duration.ofSeconds(60)).allProfilingEnabled().samplingInterval(10).maxHotspots(50)
        .maxCallTreeDepth(20).build();
  }

  /**
   * CPU-only configuration with standard sampling rate. 10ms interval (~1%
   * overhead).
   *
   * <p>
   * Use when: Finding CPU hotspots, method-level performance analysis.
   *
   * @return CPU-only profiler configuration
   */
  public static ProfilerConfig cpuOnly() {
    return builder().duration(Duration.ofSeconds(30)).cpuOnly().samplingInterval(10).build();
  }

  /**
   * Allocation tracking configuration. Captures memory allocations without CPU
   * sampling.
   *
   * <p>
   * Use when: Memory leak investigation, finding allocation hotspots, GC pressure
   * analysis.
   *
   * @return Allocation-only profiler configuration
   */
  public static ProfilerConfig allocationOnly() {
    return builder().duration(Duration.ofSeconds(30)).allocationOnly().build();
  }

  /**
   * Lock contention analysis configuration. Tracks lock acquisitions and waits.
   *
   * <p>
   * Use when: Investigating thread blocking, concurrency issues, deadlock
   * conditions.
   *
   * @return Lock profiling configuration
   */
  public static ProfilerConfig lockContention() {
    return builder().duration(Duration.ofSeconds(30)).lockOnly().build();
  }

  // Builder

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private Duration duration = Duration.ofSeconds(30);
    private int samplingIntervalMs = 10;
    private boolean cpuProfilingEnabled = true;
    private boolean allocationProfilingEnabled = false;
    private boolean lockProfilingEnabled = false;
    private boolean ioProfilingEnabled = false;
    private boolean gcProfilingEnabled = false;
    private String packageFilter = "";
    private int maxHotspots = 50;
    private int maxCallTreeDepth = 20;
    private boolean threadFilterEnabled = false;
    private String threadNamePattern = ".*";

    public Builder duration(Duration duration) {
      this.duration = Objects.requireNonNull(duration, "duration");
      return this;
    }

    public Builder samplingInterval(int intervalMs) {
      if (intervalMs < 1 || intervalMs > 1000) {
        throw new IllegalArgumentException("Sampling interval must be 1-1000ms");
      }
      this.samplingIntervalMs = intervalMs;
      return this;
    }

    public Builder cpuProfilingEnabled(boolean enabled) {
      this.cpuProfilingEnabled = enabled;
      return this;
    }

    public Builder allocationProfilingEnabled(boolean enabled) {
      this.allocationProfilingEnabled = enabled;
      return this;
    }

    public Builder lockProfilingEnabled(boolean enabled) {
      this.lockProfilingEnabled = enabled;
      return this;
    }

    public Builder ioProfilingEnabled(boolean enabled) {
      this.ioProfilingEnabled = enabled;
      return this;
    }

    public Builder gcProfilingEnabled(boolean enabled) {
      this.gcProfilingEnabled = enabled;
      return this;
    }

    public Builder packageFilter(String packageFilter) {
      this.packageFilter = Objects.requireNonNull(packageFilter, "packageFilter");
      return this;
    }

    public Builder maxHotspots(int maxHotspots) {
      if (maxHotspots < 1 || maxHotspots > 1000) {
        throw new IllegalArgumentException("maxHotspots must be 1-1000");
      }
      this.maxHotspots = maxHotspots;
      return this;
    }

    public Builder maxCallTreeDepth(int maxCallTreeDepth) {
      if (maxCallTreeDepth < 1 || maxCallTreeDepth > 100) {
        throw new IllegalArgumentException("maxCallTreeDepth must be 1-100");
      }
      this.maxCallTreeDepth = maxCallTreeDepth;
      return this;
    }

    public Builder threadFilter(String threadNamePattern) {
      this.threadFilterEnabled = true;
      this.threadNamePattern = Objects.requireNonNull(threadNamePattern, "threadNamePattern");
      return this;
    }

    // Convenience methods for enabling all/specific event types

    public Builder allProfilingEnabled() {
      this.cpuProfilingEnabled = true;
      this.allocationProfilingEnabled = true;
      this.lockProfilingEnabled = true;
      this.ioProfilingEnabled = true;
      this.gcProfilingEnabled = true;
      return this;
    }

    public Builder cpuOnly() {
      this.cpuProfilingEnabled = true;
      this.allocationProfilingEnabled = false;
      this.lockProfilingEnabled = false;
      this.ioProfilingEnabled = false;
      this.gcProfilingEnabled = false;
      return this;
    }

    public Builder allocationOnly() {
      this.cpuProfilingEnabled = false;
      this.allocationProfilingEnabled = true;
      this.lockProfilingEnabled = false;
      this.ioProfilingEnabled = false;
      this.gcProfilingEnabled = false;
      return this;
    }

    public Builder lockOnly() {
      this.cpuProfilingEnabled = false;
      this.allocationProfilingEnabled = false;
      this.lockProfilingEnabled = true;
      this.ioProfilingEnabled = false;
      this.gcProfilingEnabled = false;
      return this;
    }

    public ProfilerConfig build() {
      // Validation
      if (duration.isNegative() || duration.isZero()) {
        throw new IllegalArgumentException("Duration must be positive");
      }

      if (!cpuProfilingEnabled && !allocationProfilingEnabled && !lockProfilingEnabled && !ioProfilingEnabled
          && !gcProfilingEnabled) {
        throw new IllegalArgumentException("At least one profiling type must be enabled");
      }

      return new ProfilerConfig(this);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ProfilerConfig{");
    sb.append("duration=").append(duration.getSeconds()).append("s");
    sb.append(", samplingInterval=").append(samplingIntervalMs).append("ms");
    sb.append(", events=[");

    boolean first = true;
    if (cpuProfilingEnabled) {
      sb.append("CPU");
      first = false;
    }
    if (allocationProfilingEnabled) {
      if (!first)
        sb.append(", ");
      sb.append("ALLOCATION");
      first = false;
    }
    if (lockProfilingEnabled) {
      if (!first)
        sb.append(", ");
      sb.append("LOCK");
      first = false;
    }
    if (ioProfilingEnabled) {
      if (!first)
        sb.append(", ");
      sb.append("IO");
      first = false;
    }
    if (gcProfilingEnabled) {
      if (!first)
        sb.append(", ");
      sb.append("GC");
    }

    sb.append("], packageFilter='").append(packageFilter).append("'");
    sb.append('}');
    return sb.toString();
  }
}
