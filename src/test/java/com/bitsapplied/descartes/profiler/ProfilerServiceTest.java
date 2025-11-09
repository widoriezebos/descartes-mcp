package com.bitsapplied.descartes.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ProfilerService focusing on zombie recording prevention and
 * concurrent access safety.
 */
public class ProfilerServiceTest {

  @TempDir
  Path tempDir;

  private ProfilerService profilerService;
  private ProfilerSettings settings;
  private ProfilerListener mockListener;
  private MetricsCollector mockMetrics;

  @BeforeEach
  void setUp() {
    mockListener = mock(ProfilerListener.class);
    mockMetrics = mock(MetricsCollector.class);

    settings = ProfilerSettings.builder().enabled(true).storagePath(tempDir).maxStoredProfiles(10).autoExport(false)
        .packageFilter("com.bitsapplied").maxDurationSeconds(60).samplingIntervalMs(10).cpuEnabled(true)
        .allocationEnabled(false).lockEnabled(false).ioEnabled(false).gcEnabled(false).build();

    profilerService = new ProfilerService(settings, mockListener, mockMetrics);
  }

  @AfterEach
  void tearDown() {
    if (profilerService != null) {
      profilerService.shutdown();
    }
  }

  /**
   * Test that parsing failure removes recording from activeRecordings.
   *
   * <p>
   * Once the recorder is stopped successfully, the JFR recording is no longer
   * active. If parsing or storage fails afterward, we should still remove the
   * recording from activeRecordings to prevent permanent lockout.
   * </p>
   *
   * <p>
   * This test simulates parsing failure by deleting the JFR file after the
   * recorder has stopped. The recording should be removed from activeRecordings
   * even though parsing fails.
   * </p>
   */
  @Test
  void testParsingFailureRemovesRecording() throws Exception {
    // Given: Start a profiling session
    String profileId = profilerService.startProfiling(Duration.ofSeconds(5));
    assertNotNull(profileId);
    assertEquals(1, profilerService.listActiveRecordings().size());

    // When: Simulate parsing failure by deleting the JFR file before stop
    // The recorder.stop() will succeed, but parseProfile() will fail
    Path jfrFile = tempDir.resolve(profileId + ".jfr");
    Files.deleteIfExists(jfrFile);

    // Then: stopProfiling should fail with processing error
    ProfilerException exception = assertThrows(ProfilerException.class, () -> profilerService.stopProfiling(profileId));
    assertTrue(exception.getMessage().contains("processing failed"),
        "Should indicate processing (not recorder stop) failed");

    // And: Recording should be REMOVED from activeRecordings despite failure
    // This prevents permanent lockout - new profiling sessions can start
    List<String> activeRecordings = profilerService.listActiveRecordings();
    assertFalse(activeRecordings.contains(profileId),
        "Recording should be removed after parsing failure to prevent permanent lockout");
    assertEquals(0, activeRecordings.size(), "No recordings should be active");
  }

  /**
   * Test that new profiling can start after parsing failure.
   *
   * <p>
   * This is the critical test that verifies the fix for the permanent lockout
   * bug. After a parsing failure, the recording should be removed from
   * activeRecordings, allowing new profiling sessions to start.
   * </p>
   */
  @Test
  void testCanStartNewProfilingAfterParsingFailure() throws Exception {
    // Given: First profiling session with parsing failure
    String profileId1 = profilerService.startProfiling(Duration.ofSeconds(5));
    assertNotNull(profileId1);
    assertEquals(1, profilerService.listActiveRecordings().size());

    // When: Delete JFR file to simulate parsing failure
    Path jfrFile1 = tempDir.resolve(profileId1 + ".jfr");
    Files.deleteIfExists(jfrFile1);

    // And: Stop profiling (will fail during parsing)
    assertThrows(ProfilerException.class, () -> profilerService.stopProfiling(profileId1));

    // Then: Recording should be removed from activeRecordings
    assertEquals(0, profilerService.listActiveRecordings().size());

    // And: Starting a new profiling session should succeed (not permanently locked
    // out)
    String profileId2 = profilerService.startProfiling(Duration.ofSeconds(5));
    assertNotNull(profileId2, "Should be able to start new profiling after parsing failure");
    assertNotEquals(profileId1, profileId2, "Should have different profile ID");
    assertEquals(1, profilerService.listActiveRecordings().size());

    // Cleanup: Stop the second session
    profilerService.stopProfiling(profileId2);
  }

  /**
   * Test that concurrent startProfiling() calls are prevented.
   *
   * <p>
   * Bug: Multiple threads can call startProfiling() simultaneously without
   * synchronization, creating multiple concurrent JFR recordings that interfere
   * with each other.
   * </p>
   *
   * <p>
   * Fix: startProfiling() is now synchronized and checks for existing active
   * recordings.
   * </p>
   */
  @Test
  void testConcurrentStartPreventsDuplicates() throws Exception {
    // Given: Start first profiling session
    String profileId1 = profilerService.startProfiling(Duration.ofSeconds(5));
    assertNotNull(profileId1);
    assertEquals(1, profilerService.listActiveRecordings().size());

    // When: Attempt to start second session while first is active
    ProfilerException exception = assertThrows(ProfilerException.class,
        () -> profilerService.startProfiling(Duration.ofSeconds(5)));

    // Then: Second start should fail with clear error message
    assertTrue(exception.getMessage().contains("already in progress"));
    assertTrue(exception.getMessage().contains(profileId1));

    // And: Only one recording should be active
    assertEquals(1, profilerService.listActiveRecordings().size());
  }

  /**
   * Test successful profiling workflow: start, stop, verify cleanup.
   */
  @Test
  void testSuccessfulProfilingWorkflow() throws Exception {
    // Given: Start profiling
    String profileId = profilerService.startProfiling(Duration.ofSeconds(5));
    assertNotNull(profileId);

    // Verify started
    assertEquals(1, profilerService.listActiveRecordings().size());
    verify(mockListener).onProfilingStarted(eq(profileId));

    // Small delay to ensure recording has data
    Thread.sleep(100);

    // When: Stop profiling
    profilerService.stopProfiling(profileId);

    // Then: Recording removed from active list
    assertEquals(0, profilerService.listActiveRecordings().size());

    // And: Listener notified
    verify(mockListener).onProfilingStopped(eq(profileId), any());

    // And: Files exist
    assertTrue(Files.exists(tempDir.resolve(profileId + ".jfr")));
  }

  /**
   * Test that stopping a non-existent profile throws exception.
   */
  @Test
  void testStopNonExistentProfileThrowsException() {
    ProfilerException exception = assertThrows(ProfilerException.class,
        () -> profilerService.stopProfiling("non-existent-id"));

    assertTrue(exception.getMessage().contains("No active recording found"));
  }

  /**
   * Test that startProfiling fails when profiler is disabled.
   */
  @Test
  void testStartProfilingWhenDisabledThrowsException() throws Exception {
    // Given: Profiler is disabled
    profilerService.shutdown();
    settings = ProfilerSettings.builder().enabled(false) // Disabled
        .storagePath(tempDir).maxStoredProfiles(10).maxDurationSeconds(60).build();
    profilerService = new ProfilerService(settings, mockListener, mockMetrics);

    // When/Then: Starting profiling should fail
    ProfilerException exception = assertThrows(ProfilerException.class,
        () -> profilerService.startProfiling(Duration.ofSeconds(5)));

    assertTrue(exception.getMessage().contains("Profiler is disabled"));
  }

  /**
   * Test auto-stop functionality via scheduler.
   */
  @Test
  void testAutoStopAfterDuration() throws Exception {
    // Given: Start profiling with short duration
    String profileId = profilerService.startProfiling(Duration.ofSeconds(1));
    assertEquals(1, profilerService.listActiveRecordings().size());

    // When: Wait for auto-stop (with buffer for scheduler delay)
    Thread.sleep(1500);

    // Then: Recording should be auto-stopped
    assertEquals(0, profilerService.listActiveRecordings().size(),
        "Recording should be auto-stopped after duration expires");
    verify(mockListener).onProfilingStopped(eq(profileId), any());
  }

  /**
   * Test that listActiveRecordings returns correct state.
   */
  @Test
  void testListActiveRecordings() throws Exception {
    // Given: No active recordings initially
    assertTrue(profilerService.listActiveRecordings().isEmpty());

    // When: Start profiling
    String profileId = profilerService.startProfiling(Duration.ofSeconds(5));

    // Then: One active recording
    List<String> active = profilerService.listActiveRecordings();
    assertEquals(1, active.size());
    assertTrue(active.contains(profileId));

    // When: Stop profiling
    profilerService.stopProfiling(profileId);

    // Then: No active recordings
    assertTrue(profilerService.listActiveRecordings().isEmpty());
  }

  /**
   * Test that metrics are properly recorded.
   */
  @Test
  void testMetricsCollection() throws Exception {
    // When: Start and stop profiling
    String profileId = profilerService.startProfiling(Duration.ofSeconds(1));
    Thread.sleep(100);
    profilerService.stopProfiling(profileId);

    // Then: Metrics should be recorded
    verify(mockMetrics).incrementCounter("profiler.start.count");
    verify(mockMetrics).incrementCounter("profiler.stop.count");
    verify(mockMetrics, atLeastOnce()).setGauge(eq("profiler.active.recordings"), anyDouble());
    verify(mockMetrics).recordTiming(eq("profiler.recording.duration"), anyLong());
  }

  /**
   * Test that error metrics are recorded on failure.
   */
  @Test
  void testErrorMetricsOnFailure() throws Exception {
    // Given: Start profiling
    String profileId = profilerService.startProfiling(Duration.ofSeconds(5));

    // When: Delete JFR file to cause stop failure
    Files.deleteIfExists(tempDir.resolve(profileId + ".jfr"));

    // Then: Stop should fail and record error metric
    assertThrows(ProfilerException.class, () -> profilerService.stopProfiling(profileId));
    verify(mockMetrics).incrementCounter("profiler.errors");
  }

  /**
   * Test shutdown stops all active recordings.
   */
  @Test
  void testShutdownStopsActiveRecordings() throws Exception {
    // Given: Active profiling session
    profilerService.startProfiling(Duration.ofSeconds(30));
    assertEquals(1, profilerService.listActiveRecordings().size());

    // When: Shutdown
    profilerService.shutdown();

    // Then: All recordings stopped
    assertEquals(0, profilerService.listActiveRecordings().size());
  }

  /**
   * Regression test for the timestamp mismatch bug.
   *
   * <p>
   * Verifies that when a profiling session is started, stopped, and a new one is
   * started, they don't share state or timestamps.
   * </p>
   */
  @Test
  void testNoTimestampMismatchBetweenSessions() throws Exception {
    // Given: First session
    String profileId1 = profilerService.startProfiling(Duration.ofSeconds(1));
    Thread.sleep(100);
    profilerService.stopProfiling(profileId1);

    // Small delay to ensure different timestamp
    Thread.sleep(1000);

    // When: Second session
    String profileId2 = profilerService.startProfiling(Duration.ofSeconds(1));
    Thread.sleep(100);
    profilerService.stopProfiling(profileId2);

    // Then: Profile IDs should be different (different timestamps)
    assertFalse(profileId1.equals(profileId2), "Profile IDs should have different timestamps");

    // And: Both profiles should exist independently
    assertTrue(Files.exists(tempDir.resolve(profileId1 + ".jfr")));
    assertTrue(Files.exists(tempDir.resolve(profileId2 + ".jfr")));
  }
}
