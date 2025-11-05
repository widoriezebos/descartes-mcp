package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.profiler.MetricsCollector;
import com.bitsapplied.descartes.profiler.ProfilerListener;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.profiler.model.ProfileMetadata;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;

/**
 * Base class for profiler tool tests. Provides common setup, teardown, and
 * utility methods.
 *
 * <p>
 * Supports both mock-based tests (for parameter validation and error handling)
 * and integration tests (with real ProfilerService and JFR recordings).
 * </p>
 *
 * <p>
 * Usage:
 * </p>
 *
 * <pre>
 * &#64;Nested
 * class ParameterValidation extends ProfilerToolTestBase {
 *   &#64;Test
 *   void testValidation() {
 *     // Use mockProfilerService for fast validation tests
 *   }
 * }
 *
 * &#64;Nested
 * class Integration extends ProfilerToolTestBase {
 *   &#64;Test
 *   void testRealProfiling() {
 *     // Use realProfilerService for actual JFR integration tests
 *   }
 * }
 * </pre>
 */
public abstract class ProfilerToolTestBase {

  protected static final Logger logger = LoggerFactory.getLogger(ProfilerToolTestBase.class);

  /** Temporary directory for profiler storage (automatically cleaned up) */
  @TempDir
  protected Path tempStoragePath;

  /** Mock ProfilerService for fast parameter validation tests */
  protected ProfilerService mockProfilerService;

  /** Real ProfilerService for integration tests (uses actual JFR) */
  protected ProfilerService realProfilerService;

  /** Profile IDs created during tests (for cleanup) */
  protected List<String> createdProfileIds;

  @BeforeEach
  public void setUpProfilerTests() {
    createdProfileIds = new ArrayList<>();

    // Create mock ProfilerService for validation tests
    mockProfilerService = mock(ProfilerService.class);
    when(mockProfilerService.isEnabled()).thenReturn(true);
    when(mockProfilerService.isJFRAvailable()).thenReturn(true);

    // Create real ProfilerService for integration tests
    ProfilerSettings settings = ProfilerSettings.builder().enabled(true).storagePath(tempStoragePath)
        .maxStoredProfiles(10).build();

    realProfilerService = new ProfilerService(settings, ProfilerListener.NOOP, MetricsCollector.NOOP);

    logger.debug("Test setup complete - storage: {}", tempStoragePath);
  }

  @AfterEach
  public void tearDownProfilerTests() {
    // Stop any active recordings
    if (realProfilerService != null) {
      for (String profileId : createdProfileIds) {
        try {
          realProfilerService.stopProfiling(profileId);
          logger.debug("Stopped profile: {}", profileId);
        } catch (Exception e) {
          // Ignore - may already be stopped
        }
      }
      realProfilerService.shutdown();
    }

    logger.debug("Test cleanup complete");
  }

  /**
   * Start a test profile with CPU workload and track it for cleanup.
   *
   * @param durationSeconds Duration in seconds
   * @return Profile ID
   */
  protected String startTestProfile(int durationSeconds) {
    return startTestProfile(durationSeconds, ProfilerConfig.cpuOnly());
  }

  /**
   * Start a test profile with custom config and track it for cleanup.
   *
   * @param durationSeconds Duration in seconds
   * @param config          Profiler configuration
   * @return Profile ID
   */
  protected String startTestProfile(int durationSeconds, ProfilerConfig config) {
    assertNotNull(realProfilerService, "Real profiler service not initialized");

    String profileId = realProfilerService.startProfiling(Duration.ofSeconds(durationSeconds), config);
    createdProfileIds.add(profileId);
    logger.debug("Started test profile: {} (duration={}s)", profileId, durationSeconds);

    return profileId;
  }

  /**
   * Wait for a profile to complete (auto-stop).
   *
   * @param profileId      Profile ID
   * @param timeoutSeconds Maximum time to wait
   * @return Profile snapshot, or null if timeout
   */
  protected ProfileSnapshot waitForProfileCompletion(String profileId, int timeoutSeconds) {
    long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);

    while (System.currentTimeMillis() < endTime) {
      ProfileSnapshot snapshot = realProfilerService.getProfile(profileId);
      if (snapshot != null) {
        logger.debug("Profile completed: {}", profileId);
        return snapshot;
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

    logger.warn("Profile did not complete within {} seconds: {}", timeoutSeconds, profileId);
    return null;
  }

  /**
   * Run a CPU-intensive workload for testing.
   *
   * @param durationMs Duration in milliseconds
   */
  protected void runCPUWorkload(int durationMs) {
    long endTime = System.currentTimeMillis() + durationMs;
    long sum = 0;

    while (System.currentTimeMillis() < endTime) {
      // Simple CPU work: compute Fibonacci
      sum += fibonacci(20);
    }

    // Prevent optimization
    if (sum < 0) {
      System.out.println("Unreachable");
    }
  }

  /**
   * Run a memory allocation workload for testing.
   *
   * @param durationMs Duration in milliseconds
   */
  protected void runAllocationWorkload(int durationMs) {
    long endTime = System.currentTimeMillis() + durationMs;
    List<String> garbage = new ArrayList<>();

    while (System.currentTimeMillis() < endTime) {
      // Allocate strings and discard
      for (int i = 0; i < 1000; i++) {
        garbage.add("String_" + i);
      }
      garbage.clear();
    }
  }

  /**
   * Run mixed workload (CPU + allocation) for testing.
   *
   * @param durationMs Duration in milliseconds
   */
  protected void runMixedWorkload(int durationMs) {
    long endTime = System.currentTimeMillis() + durationMs;

    while (System.currentTimeMillis() < endTime) {
      // Mix of CPU and allocation
      fibonacci(15);
      new ArrayList<>(List.of("a", "b", "c"));
    }
  }

  /**
   * Simple fibonacci implementation for CPU workload.
   */
  private long fibonacci(int n) {
    if (n <= 1) {
      return n;
    }
    return fibonacci(n - 1) + fibonacci(n - 2);
  }

  /**
   * Verify that a JFR file exists for a profile.
   *
   * @param profileId Profile ID
   * @return true if JFR file exists
   */
  protected boolean jfrFileExists(String profileId) {
    Path jfrPath = tempStoragePath.resolve(profileId + ".jfr");
    return Files.exists(jfrPath);
  }

  /**
   * Get the size of a JFR file.
   *
   * @param profileId Profile ID
   * @return File size in bytes, or -1 if file doesn't exist
   */
  protected long getJfrFileSize(String profileId) {
    Path jfrPath = tempStoragePath.resolve(profileId + ".jfr");
    try {
      return Files.size(jfrPath);
    } catch (IOException e) {
      return -1;
    }
  }

  /**
   * Create a minimal mock ProfileSnapshot for validation tests.
   *
   * @param profileId Profile ID
   * @return Mock ProfileSnapshot with minimal valid data
   */
  protected ProfileSnapshot createMockSnapshot(String profileId) {
    return ProfileSnapshot.builder()
        .metadata(ProfileMetadata.builder().profileId(profileId).startTime(Instant.now().minusSeconds(10))
            .endTime(Instant.now()).config(ProfilerConfig.cpuOnly()).build())
        .totalSamples(1000).cpuHotspots(List.of()).allocationHotspots(List.of()).lockHotspots(List.of())
        .insights(List.of("Test insight")).recommendations(List.of("Test recommendation")).build();
  }

  /**
   * Create a profile ID that looks real but doesn't exist (for 404 tests).
   */
  protected String nonExistentProfileId() {
    return "01-01-2025_00.00.00-profile-fake1234";
  }
}
