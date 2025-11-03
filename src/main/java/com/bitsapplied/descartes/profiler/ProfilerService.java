package com.bitsapplied.descartes.profiler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.profiler.parser.JFRParser;
import com.bitsapplied.descartes.profiler.recorder.JFRRecorder;
import com.bitsapplied.descartes.profiler.recorder.Recorder;
import com.bitsapplied.descartes.profiler.storage.ProfileStore;

/**
 * Main profiling service. Manages JFR recordings, parsing, and storage.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * ProfilerSettings settings = ProfilerSettings.builder().enabled(true).storagePath(Paths.get("logs/profiles")).build();
 *
 * ProfilerService profiler = new ProfilerService(settings, ProfilerListener.NOOP, MetricsCollector.NOOP);
 *
 * // Start profiling
 * String profileId = profiler.startProfiling(Duration.ofSeconds(30), ProfilerConfig.cpuOnly());
 *
 * // ... do work ...
 *
 * // Get results (will auto-stop after duration)
 * ProfileSnapshot snapshot = profiler.getProfile(profileId);
 * </pre>
 */
public class ProfilerService {

  private static final Logger logger = LogManager.getLogger(ProfilerService.class);

  private final ProfilerSettings settings;
  private final ProfilerListener listener;
  private final MetricsCollector metricsCollector;
  private final ProfileStore profileStore;
  private final Map<String, ActiveRecording> activeRecordings = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

  public ProfilerService(ProfilerSettings settings, ProfilerListener listener, MetricsCollector metricsCollector) {
    this.settings = settings;
    this.listener = listener != null ? listener : ProfilerListener.NOOP;
    this.metricsCollector = metricsCollector != null ? metricsCollector : MetricsCollector.NOOP;

    // Initialize storage
    this.profileStore = new ProfileStore(settings.getStoragePath(), settings.getMaxStoredProfiles());

    logger.info("ProfilerService initialized (enabled={}, storage={})", isEnabled(), settings.getStoragePath());
  }

  /**
   * Start a profiling session with the given configuration.
   *
   * <p>
   * This method is synchronized to prevent race conditions when multiple threads
   * (e.g., UI and MCP) attempt to start profiling simultaneously.
   * </p>
   *
   * @param duration Recording duration
   * @param config   Profiler configuration
   * @return Profile ID for later retrieval
   * @throws ProfilerException if profiling cannot be started
   */
  public synchronized String startProfiling(Duration duration, ProfilerConfig config) {
    if (!isEnabled()) {
      throw new ProfilerException("Profiler is disabled");
    }

    if (!isJFRAvailable()) {
      throw new ProfilerException("JFR not available. Requires JDK 11+");
    }

    // Check for existing active recordings to prevent concurrent profiling
    if (!activeRecordings.isEmpty()) {
      String activeIds = String.join(", ", activeRecordings.keySet());
      throw new ProfilerException(
          "Profiling session already in progress: " + activeIds + ". Stop current session before starting a new one.");
    }

    // Generate profile ID with timestamp prefix (dd-MM-yyyy_HH.mm.ss-profile-uuid)
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH.mm.ss").withZone(ZoneId.systemDefault());
    String timestamp = formatter.format(Instant.now());
    String profileId = timestamp + "-profile-" + UUID.randomUUID().toString().substring(0, 8);
    Path jfrPath = profileStore.getJFRPath(profileId);

    logger.info("Starting profiling session: {} (duration={}s)", profileId, duration.getSeconds());
    metricsCollector.incrementCounter("profiler.start.count");

    try {
      // Create and start recorder
      Recorder recorder = new JFRRecorder(config, jfrPath);
      recorder.start();

      // Track active recording
      ActiveRecording active = new ActiveRecording(recorder, config);
      activeRecordings.put(profileId, active);
      metricsCollector.setGauge("profiler.active.recordings", activeRecordings.size());

      // Schedule auto-stop
      scheduler.schedule(() -> autoStopRecording(profileId), duration.toMillis(), TimeUnit.MILLISECONDS);

      // Notify listeners
      listener.onProfilingStarted(profileId);

      return profileId;

    } catch (Exception e) {
      metricsCollector.incrementCounter("profiler.errors");
      logger.error("Failed to start profiling: {}", profileId, e);
      throw new ProfilerException("Failed to start profiling", e);
    }
  }

  /**
   * Start profiling with settings-based configuration.
   *
   * @param duration Recording duration
   * @return Profile ID
   */
  public String startProfiling(Duration duration) {
    ProfilerConfig config = createConfigFromSettings(duration);
    return startProfiling(duration, config);
  }

  /**
   * Create profiler configuration from current settings.
   *
   * @param duration Recording duration
   * @return Profiler configuration based on settings
   */
  private ProfilerConfig createConfigFromSettings(Duration duration) {
    ProfilerConfig.Builder builder = ProfilerConfig.builder().duration(duration)
        .samplingInterval(settings.getSamplingIntervalMs()).packageFilter(settings.getPackageFilter());

    // Apply event type settings
    builder.cpuProfilingEnabled(settings.isCpuEnabled());
    builder.allocationProfilingEnabled(settings.isAllocationEnabled());
    builder.lockProfilingEnabled(settings.isLockEnabled());
    builder.ioProfilingEnabled(settings.isIoEnabled());
    builder.gcProfilingEnabled(settings.isGcEnabled());

    return builder.build();
  }

  /**
   * Stop an active profiling session.
   *
   * @param profileId Profile ID
   * @return Parsed profile snapshot
   * @throws ProfilerException if profile not found or stop fails
   */
  public ProfileSnapshot stopProfiling(String profileId) {
    // Get recording but don't remove yet - prevents zombie recordings if stop fails
    ActiveRecording active = activeRecordings.get(profileId);
    if (active == null) {
      throw new ProfilerException("No active recording found: " + profileId);
    }

    metricsCollector.incrementCounter("profiler.stop.count");

    long startTime = System.currentTimeMillis();
    try {
      // Stop recorder
      active.recorder.stop();
      logger.info("Stopped profiling session: {}", profileId);

      // Parse JFR file
      ProfileSnapshot snapshot = parseProfile(profileId, active.config);

      // Store snapshot
      profileStore.store(snapshot);

      // Remove from active recordings AFTER successful stop
      activeRecordings.remove(profileId);
      metricsCollector.setGauge("profiler.active.recordings", activeRecordings.size());

      // Record duration
      long duration = System.currentTimeMillis() - startTime;
      metricsCollector.recordTiming("profiler.recording.duration", duration);

      // Notify listeners
      listener.onProfilingStopped(profileId, snapshot);

      return snapshot;

    } catch (Exception e) {
      // Recording remains in activeRecordings to prevent zombie state
      // This allows retry or manual cleanup
      metricsCollector.incrementCounter("profiler.errors");
      logger.error("Failed to stop profiling: {} - recording remains active to prevent zombie state", profileId, e);
      listener.onProfilingError(profileId, e);
      throw new ProfilerException("Failed to stop profiling", e);
    }
  }

  /**
   * Get a stored profile snapshot.
   *
   * @param profileId Profile ID
   * @return Profile snapshot, or null if not found
   */
  public ProfileSnapshot getProfile(String profileId) {
    return profileStore.get(profileId);
  }

  /**
   * List all active recording IDs.
   */
  public List<String> listActiveRecordings() {
    return List.copyOf(activeRecordings.keySet());
  }

  /**
   * List all stored profile IDs.
   */
  public List<String> listStoredProfiles() {
    return profileStore.listProfileIds();
  }

  /**
   * List all stored profiles.
   */
  public List<ProfileSnapshot> listProfiles() {
    return profileStore.listProfiles();
  }

  /**
   * Delete a stored profile.
   *
   * @param profileId Profile ID
   * @return true if deleted, false if not found
   */
  public boolean deleteProfile(String profileId) {
    return profileStore.delete(profileId);
  }

  /**
   * Check if profiler is enabled.
   */
  public boolean isEnabled() {
    return settings.isEnabled();
  }

  /**
   * Check if JFR is available on this JVM.
   */
  public boolean isJFRAvailable() {
    return JFRRecorder.isJFRAvailable();
  }

  /**
   * Get default profiler configuration from settings.
   */
  public ProfilerConfig getDefaultConfig() {
    return createConfigFromSettings(Duration.ofSeconds(settings.getMaxDurationSeconds()));
  }

  /**
   * Shutdown profiler service and stop all active recordings.
   */
  public void shutdown() {
    logger.info("Shutting down profiler service ({} active recordings)", activeRecordings.size());

    // Stop all active recordings
    for (String profileId : List.copyOf(activeRecordings.keySet())) {
      try {
        stopProfiling(profileId);
      } catch (Exception e) {
        logger.error("Error stopping recording during shutdown: {}", profileId, e);
      }
    }

    // Shutdown scheduler
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void autoStopRecording(String profileId) {
    // Guard: Early exit if already stopped (prevents spurious ERROR logs)
    if (!activeRecordings.containsKey(profileId)) {
      logger.debug("Auto-stop skipped - profiling session already stopped: {}", profileId);
      return;
    }

    try {
      logger.debug("Auto-stopping profiling session: {}", profileId);
      stopProfiling(profileId);
    } catch (ProfilerException e) {
      // Distinguish concurrent stop (benign) vs. genuine error
      if (e.getMessage() != null && e.getMessage().contains("No active recording found")) {
        // TOCTOU race: recording removed between containsKey and stopProfiling
        logger.debug("Auto-stop skipped - profiling session stopped concurrently: {}", profileId);
      } else {
        // Genuine error (recorder.stop() failed, parsing failed, etc.)
        logger.error("Error auto-stopping recording: {}", profileId, e);
      }
    } catch (Exception e) {
      // Unexpected exception type
      logger.error("Unexpected error auto-stopping recording: {}", profileId, e);
    }
  }

  private ProfileSnapshot parseProfile(String profileId, ProfilerConfig config) {
    Path jfrPath = profileStore.getJFRPath(profileId);

    long startTime = System.currentTimeMillis();
    try {
      JFRParser parser = new JFRParser(settings.getPackageFilter());
      ProfileSnapshot snapshot = parser.parse(jfrPath, profileId, config);

      long duration = System.currentTimeMillis() - startTime;
      metricsCollector.recordTiming("profiler.parsing.duration", duration);

      return snapshot;
    } catch (Exception e) {
      throw new ProfilerException("Failed to parse profile: " + profileId, e);
    }
  }

  private static class ActiveRecording {
    final Recorder recorder;
    final ProfilerConfig config;

    ActiveRecording(Recorder recorder, ProfilerConfig config) {
      this.recorder = recorder;
      this.config = config;
    }
  }
}
