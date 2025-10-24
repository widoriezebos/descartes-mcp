package com.bitsapplied.descartes.profiler.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.bitsapplied.descartes.profiler.config.ProfilerConfig;

/**
 * Metadata about a profiling session, including configuration, timestamps, and
 * system information.
 */
public class ProfileMetadata {

  private final String profileId;
  private final Instant startTime;
  private final Instant endTime;
  private final ProfilerConfig config;
  private final String jvmVersion;
  private final String osName;
  private final String osVersion;
  private final int availableProcessors;
  private final long totalMemoryMB;
  private final String recordingSource; // "JFR", "Sampling", "AsyncProfiler"

  private ProfileMetadata(Builder builder) {
    this.profileId = builder.profileId;
    this.startTime = builder.startTime;
    this.endTime = builder.endTime;
    this.config = builder.config;
    this.jvmVersion = builder.jvmVersion;
    this.osName = builder.osName;
    this.osVersion = builder.osVersion;
    this.availableProcessors = builder.availableProcessors;
    this.totalMemoryMB = builder.totalMemoryMB;
    this.recordingSource = builder.recordingSource;
  }

  public String getProfileId() {
    return profileId;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public Instant getEndTime() {
    return endTime;
  }

  public ProfilerConfig getConfig() {
    return config;
  }

  public String getJvmVersion() {
    return jvmVersion;
  }

  public String getOsName() {
    return osName;
  }

  public String getOsVersion() {
    return osVersion;
  }

  public int getAvailableProcessors() {
    return availableProcessors;
  }

  public long getTotalMemoryMB() {
    return totalMemoryMB;
  }

  public String getRecordingSource() {
    return recordingSource;
  }

  /**
   * Get duration in seconds.
   */
  public long getDurationSeconds() {
    if (endTime == null)
      return 0;
    return Duration.between(startTime, endTime).getSeconds();
  }

  /**
   * Convert to map for JSON serialization.
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("profile_id", profileId);
    map.put("start_time", startTime.toString());
    map.put("end_time", endTime != null ? endTime.toString() : null);
    map.put("duration_seconds", getDurationSeconds());
    map.put("recording_source", recordingSource);
    map.put("sampling_interval_ms", config.getSamplingIntervalMs());

    map.put("system", Map.of("jvm_version", jvmVersion, "os", osName + " " + osVersion, "processors",
        availableProcessors, "total_memory_mb", totalMemoryMB));

    map.put("configuration",
        Map.of("cpu_profiling", config.isCPUProfilingEnabled(), "allocation_profiling",
            config.isAllocationProfilingEnabled(), "lock_profiling", config.isLockProfilingEnabled(), "io_profiling",
            config.isIOProfilingEnabled(), "gc_profiling", config.isGCProfilingEnabled(), "package_filter",
            config.getPackageFilter()));

    return map;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String profileId;
    private Instant startTime;
    private Instant endTime;
    private ProfilerConfig config;
    private String jvmVersion = System.getProperty("java.version", "unknown");
    private String osName = System.getProperty("os.name", "unknown");
    private String osVersion = System.getProperty("os.version", "unknown");
    private int availableProcessors = Runtime.getRuntime().availableProcessors();
    private long totalMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
    private String recordingSource = "JFR";

    public Builder profileId(String profileId) {
      this.profileId = profileId;
      return this;
    }

    public Builder startTime(Instant startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder endTime(Instant endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder config(ProfilerConfig config) {
      this.config = config;
      return this;
    }

    public Builder jvmVersion(String jvmVersion) {
      this.jvmVersion = jvmVersion;
      return this;
    }

    public Builder osName(String osName) {
      this.osName = osName;
      return this;
    }

    public Builder osVersion(String osVersion) {
      this.osVersion = osVersion;
      return this;
    }

    public Builder availableProcessors(int availableProcessors) {
      this.availableProcessors = availableProcessors;
      return this;
    }

    public Builder totalMemoryMB(long totalMemoryMB) {
      this.totalMemoryMB = totalMemoryMB;
      return this;
    }

    public Builder recordingSource(String recordingSource) {
      this.recordingSource = recordingSource;
      return this;
    }

    public ProfileMetadata build() {
      Objects.requireNonNull(profileId, "profileId");
      Objects.requireNonNull(startTime, "startTime");
      Objects.requireNonNull(config, "config");
      return new ProfileMetadata(this);
    }
  }

  @Override
  public String toString() {
    return String.format("ProfileMetadata{id='%s', start=%s, duration=%ds, source=%s}", profileId, startTime,
        getDurationSeconds(), recordingSource);
  }
}
