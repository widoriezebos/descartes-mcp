package com.bitsapplied.descartes.profiler;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration settings for the profiler service.
 * <p>
 * Immutable configuration object created via builder pattern.
 */
public class ProfilerSettings {

  private final boolean enabled;
  private final Path storagePath;
  private final int maxStoredProfiles;
  private final boolean autoExport;
  private final String packageFilter;
  private final int maxDurationSeconds;
  private final int samplingIntervalMs;

  // Event type defaults
  private final boolean cpuEnabled;
  private final boolean allocationEnabled;
  private final boolean lockEnabled;
  private final boolean ioEnabled;
  private final boolean gcEnabled;

  private ProfilerSettings(Builder builder) {
    this.enabled = builder.enabled;
    this.storagePath = builder.storagePath;
    this.maxStoredProfiles = builder.maxStoredProfiles;
    this.autoExport = builder.autoExport;
    this.packageFilter = builder.packageFilter;
    this.maxDurationSeconds = builder.maxDurationSeconds;
    this.samplingIntervalMs = builder.samplingIntervalMs;
    this.cpuEnabled = builder.cpuEnabled;
    this.allocationEnabled = builder.allocationEnabled;
    this.lockEnabled = builder.lockEnabled;
    this.ioEnabled = builder.ioEnabled;
    this.gcEnabled = builder.gcEnabled;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Path getStoragePath() {
    return storagePath;
  }

  public int getMaxStoredProfiles() {
    return maxStoredProfiles;
  }

  public boolean isAutoExport() {
    return autoExport;
  }

  public String getPackageFilter() {
    return packageFilter;
  }

  public int getMaxDurationSeconds() {
    return maxDurationSeconds;
  }

  public int getSamplingIntervalMs() {
    return samplingIntervalMs;
  }

  public boolean isCpuEnabled() {
    return cpuEnabled;
  }

  public boolean isAllocationEnabled() {
    return allocationEnabled;
  }

  public boolean isLockEnabled() {
    return lockEnabled;
  }

  public boolean isIoEnabled() {
    return ioEnabled;
  }

  public boolean isGcEnabled() {
    return gcEnabled;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private boolean enabled = false;
    private Path storagePath = Paths.get("logs/profiles");
    private int maxStoredProfiles = 100;
    private boolean autoExport = true;
    private String packageFilter = "";
    private int maxDurationSeconds = 300;
    private int samplingIntervalMs = 10;
    private boolean cpuEnabled = true;
    private boolean allocationEnabled = false;
    private boolean lockEnabled = false;
    private boolean ioEnabled = false;
    private boolean gcEnabled = false;

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder storagePath(Path storagePath) {
      this.storagePath = storagePath;
      return this;
    }

    public Builder maxStoredProfiles(int maxStoredProfiles) {
      this.maxStoredProfiles = maxStoredProfiles;
      return this;
    }

    public Builder autoExport(boolean autoExport) {
      this.autoExport = autoExport;
      return this;
    }

    public Builder packageFilter(String packageFilter) {
      this.packageFilter = packageFilter;
      return this;
    }

    public Builder maxDurationSeconds(int maxDurationSeconds) {
      this.maxDurationSeconds = maxDurationSeconds;
      return this;
    }

    public Builder samplingIntervalMs(int samplingIntervalMs) {
      this.samplingIntervalMs = samplingIntervalMs;
      return this;
    }

    public Builder cpuEnabled(boolean cpuEnabled) {
      this.cpuEnabled = cpuEnabled;
      return this;
    }

    public Builder allocationEnabled(boolean allocationEnabled) {
      this.allocationEnabled = allocationEnabled;
      return this;
    }

    public Builder lockEnabled(boolean lockEnabled) {
      this.lockEnabled = lockEnabled;
      return this;
    }

    public Builder ioEnabled(boolean ioEnabled) {
      this.ioEnabled = ioEnabled;
      return this;
    }

    public Builder gcEnabled(boolean gcEnabled) {
      this.gcEnabled = gcEnabled;
      return this;
    }

    public ProfilerSettings build() {
      return new ProfilerSettings(this);
    }
  }

  @Override
  public String toString() {
    return "ProfilerSettings[enabled=" + enabled + ", storagePath=" + storagePath + ", maxStoredProfiles="
        + maxStoredProfiles + ", packageFilter=" + packageFilter + "]";
  }
}
