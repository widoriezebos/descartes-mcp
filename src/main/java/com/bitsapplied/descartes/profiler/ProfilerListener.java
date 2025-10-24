package com.bitsapplied.descartes.profiler;

import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;

/**
 * Callback interface for profiler lifecycle events.
 * <p>
 * Implement this interface to receive notifications about profiling sessions.
 */
public interface ProfilerListener {

  /**
   * Called when a profiling session starts.
   *
   * @param profileId the unique profile identifier
   */
  void onProfilingStarted(String profileId);

  /**
   * Called when a profiling session stops successfully.
   *
   * @param profileId the unique profile identifier
   * @param snapshot  the parsed profile snapshot
   */
  void onProfilingStopped(String profileId, ProfileSnapshot snapshot);

  /**
   * Called when a profiling error occurs.
   *
   * @param profileId the unique profile identifier (may be null if error during
   *                  start)
   * @param error     the error that occurred
   */
  void onProfilingError(String profileId, Exception error);

  /**
   * No-op implementation for convenience.
   */
  public static ProfilerListener NOOP = new ProfilerListener() {
    @Override
    public void onProfilingStarted(String profileId) {
    }

    @Override
    public void onProfilingStopped(String profileId, ProfileSnapshot snapshot) {
    }

    @Override
    public void onProfilingError(String profileId, Exception error) {
    }
  };
}
