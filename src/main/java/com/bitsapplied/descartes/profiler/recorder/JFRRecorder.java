package com.bitsapplied.descartes.profiler.recorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.config.ProfilerConfig;

import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * JFR-based profiler recorder using the built-in Java Flight Recorder API.
 *
 * <p>
 * Configures and manages JFR recordings based on ProfilerConfig settings.
 * Captures CPU samples, allocations, locks, I/O, and GC events as configured.
 */
public class JFRRecorder implements Recorder {

  private static final Logger logger = LogManager.getLogger(JFRRecorder.class);

  private final ProfilerConfig config;
  private final Path outputPath;
  private Recording recording;
  private volatile boolean isRecording;

  public JFRRecorder(ProfilerConfig config, Path outputPath) {
    this.config = config;
    this.outputPath = outputPath;
    this.isRecording = false;
  }

  @Override
  public void start() {
    if (isRecording) {
      throw new ProfilerException("Recording already in progress");
    }

    try {
      // Ensure output directory exists
      if (outputPath.getParent() != null) {
        Files.createDirectories(outputPath.getParent());
      }

      // Create recording
      recording = new Recording();
      recording.setName("jfr-profile-" + UUID.randomUUID().toString().substring(0, 8));

      // Configure CPU profiling
      if (config.isCPUProfilingEnabled()) {
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(config.getSamplingIntervalMs()));
        logger.debug("Enabled CPU profiling ({}ms interval)", config.getSamplingIntervalMs());
      }

      // Configure allocation profiling
      if (config.isAllocationProfilingEnabled()) {
        recording.enable("jdk.ObjectAllocationInNewTLAB");
        recording.enable("jdk.ObjectAllocationOutsideTLAB");
        logger.debug("Enabled allocation profiling");
      }

      // Configure lock profiling
      if (config.isLockProfilingEnabled()) {
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(10));
        recording.enable("jdk.JavaMonitorWait").withThreshold(Duration.ofMillis(10));
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(10));
        logger.debug("Enabled lock profiling");
      }

      // Configure I/O profiling
      if (config.isIOProfilingEnabled()) {
        recording.enable("jdk.FileRead").withThreshold(Duration.ofMillis(10));
        recording.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(10));
        recording.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(10));
        recording.enable("jdk.SocketWrite").withThreshold(Duration.ofMillis(10));
        logger.debug("Enabled I/O profiling");
      }

      // Configure GC profiling
      if (config.isGCProfilingEnabled()) {
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.G1GarbageCollection");
        recording.enable("jdk.YoungGarbageCollection");
        recording.enable("jdk.OldGarbageCollection");
        logger.debug("Enabled GC profiling");
      }

      // Set duration and destination
      recording.setMaxAge(config.getDuration());
      recording.setDestination(outputPath);

      // Start recording
      recording.start();
      isRecording = true;

      logger.info("Started JFR recording: {} (duration={}s, output={})", recording.getName(),
          config.getDuration().getSeconds(), outputPath);

    } catch (IOException e) {
      throw new ProfilerException("Failed to start JFR recording", e);
    } catch (SecurityException e) {
      throw new ProfilerException("JFR recording failed - insufficient permissions. "
          + "Ensure JFR is available (JDK 11+) and no security manager restrictions apply", e);
    }
  }

  @Override
  public void stop() {
    if (!isRecording || recording == null) {
      logger.warn("No active recording to stop");
      return;
    }

    // Check actual JFR recording state to avoid IllegalStateException
    RecordingState state = recording.getState();
    if (state == RecordingState.STOPPED || state == RecordingState.CLOSED) {
      logger.warn("Recording already {} - skipping stop", state);
      isRecording = false;
      return;
    }

    // Stop the recording
    recording.stop();
    isRecording = false;

    // Log file size if available (don't fail if file I/O fails)
    try {
      long sizeKB = Files.size(outputPath) / 1024;
      logger.info("Stopped JFR recording: {} (size={}KB)", recording.getName(), sizeKB);
    } catch (IOException e) {
      logger.warn("Stopped JFR recording: {} (size unavailable: {})", recording.getName(), e.getMessage());
    }
  }

  @Override
  public boolean isRecording() {
    return isRecording;
  }

  @Override
  public Path getOutputPath() {
    return outputPath;
  }

  @Override
  public String getRecorderType() {
    return "JFR";
  }

  @Override
  public void close() {
    if (recording != null) {
      try {
        if (isRecording) {
          stop();
        }
        recording.close();
      } catch (Exception e) {
        logger.error("Error closing JFR recording", e);
      }
    }
  }

  /**
   * Check if JFR is available on this JVM.
   */
  public static boolean isJFRAvailable() {
    try {
      // Try to access JFR class
      Class.forName("jdk.jfr.Recording");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
