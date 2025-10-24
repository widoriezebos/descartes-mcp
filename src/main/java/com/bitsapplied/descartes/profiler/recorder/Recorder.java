package com.bitsapplied.descartes.profiler.recorder;

import java.nio.file.Path;

/**
 * Interface for profiling recorders. Implementations may use JFR, sampling, or
 * async-profiler.
 */
public interface Recorder extends AutoCloseable {

  /**
   * Start recording with the configured settings.
   */
  void start();

  /**
   * Stop recording and finalize the output file.
   */
  void stop();

  /**
   * Check if recording is currently active.
   */
  boolean isRecording();

  /**
   * Get the output file path where profile data will be written.
   */
  Path getOutputPath();

  /**
   * Get the recorder type (JFR, Sampling, AsyncProfiler).
   */
  String getRecorderType();

  /**
   * Close and cleanup resources.
   */
  @Override
  void close();
}
