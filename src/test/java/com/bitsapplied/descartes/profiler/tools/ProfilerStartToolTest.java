package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ProfilerStartTool.
 *
 * <p>
 * Tests cover:
 * </p>
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Parameter validation (duration, profile_type, package_filter)</li>
 * <li>Error handling (profiler disabled, JFR unavailable, internal errors)</li>
 * <li>Integration tests with real ProfilerService</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class ProfilerStartToolTest extends ProfilerToolTestBase {

  private ProfilerStartTool toolWithMock;
  private ProfilerStartTool toolWithReal;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    toolWithMock = new ProfilerStartTool(mockProfilerService);
    toolWithReal = new ProfilerStartTool(realProfilerService);
    objectMapper = new ObjectMapper();
  }

  @Nested
  class ToolMetadata {

    @Test
    void hasCorrectName() {
      assertEquals("profiler_start", toolWithMock.getToolName());
    }

    @Test
    void hasDescription() {
      String description = toolWithMock.getToolDescription();
      assertNotNull(description);
      assertTrue(description.contains("JFR"));
      assertTrue(description.contains("CPU"));
      assertTrue(description.contains("profile ID"));
    }

    @Test
    void hasValidSchema() {
      Map<String, Object> schema = toolWithMock.getToolSchema();
      assertNotNull(schema);
      assertEquals("object", schema.get("type"));

      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      assertNotNull(properties);
      assertTrue(properties.containsKey("duration_seconds"));
      assertTrue(properties.containsKey("profile_type"));
      assertTrue(properties.containsKey("package_filter"));

      // Verify duration_seconds schema
      @SuppressWarnings("unchecked")
      Map<String, Object> durationSchema = (Map<String, Object>) properties.get("duration_seconds");
      assertEquals("integer", durationSchema.get("type"));
      assertEquals(10, durationSchema.get("minimum"));
      assertEquals(300, durationSchema.get("maximum"));
      assertEquals(30, durationSchema.get("default"));

      // Verify profile_type schema
      @SuppressWarnings("unchecked")
      Map<String, Object> profileTypeSchema = (Map<String, Object>) properties.get("profile_type");
      assertEquals("string", profileTypeSchema.get("type"));
      assertNotNull(profileTypeSchema.get("enum"));
    }
  }

  @Nested
  class ParameterValidation {

    @Test
    void acceptsValidParametersWithCpuProfile() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 30, "profile_type", "cpu", "package_filter",
          "com.example");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("test-profile-id"));
      assertTrue(((ToolResponse.Success) response).content().contains("recording"));
    }

    @Test
    void acceptsValidParametersWithAllocationProfile() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 60, "profile_type", "allocation");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("test-profile-id"));
    }

    @Test
    void acceptsValidParametersWithComprehensiveProfile() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 45, "profile_type", "comprehensive");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void acceptsValidParametersWithLightweightProfile() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 120, "profile_type", "lightweight");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void usesDefaultDurationWhenNotProvided() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("profile_type", "cpu");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      // Verify the response mentions 30 seconds (the default)
      assertTrue(((ToolResponse.Success) response).content().contains("30"));
    }

    @Test
    void usesDefaultProfileTypeWhenNotProvided() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 30);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("cpu"));
    }

    @Test
    void rejectsDurationBelowMinimum() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 5);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("between 10 and 300"));
    }

    @Test
    void rejectsDurationAtZero() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 0);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("between 10 and 300"));
    }

    @Test
    void rejectsNegativeDuration() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", -10);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("between 10 and 300"));
    }

    @Test
    void rejectsDurationAboveMaximum() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 400);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("between 10 and 300"));
    }

    @Test
    void acceptsDurationAtMinimumBoundary() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 10);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("10"));
    }

    @Test
    void acceptsDurationAtMaximumBoundary() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 300);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("300"));
    }

    @Test
    void rejectsInvalidProfileType() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 30, "profile_type", "invalid_type");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("one of:"));
      assertTrue(error.message().contains("cpu"));
      assertTrue(error.message().contains("allocation"));
      assertTrue(error.message().contains("comprehensive"));
      assertTrue(error.message().contains("lightweight"));
    }

    @Test
    void rejectsEmptyProfileType() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 30, "profile_type", "");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("one of:"));
    }

    @Test
    void acceptsStringDuration() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", "45");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("45"));
    }

    @Test
    void rejectsNonNumericDurationString() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", "not-a-number");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("must be a number"));
    }

    @Test
    void acceptsCaseInsensitiveProfileType() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 30, "profile_type", "CPU");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void acceptsEmptyParameters() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of();

      ToolResponse response = toolWithMock.executeAsync(params).get();

      // Should use defaults
      assertTrue(((ToolResponse.Success) response).content().contains("30")); // default duration
      assertTrue(((ToolResponse.Success) response).content().contains("cpu")); // default profile type
    }

    @Test
    void acceptsCustomPackageFilter() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenReturn("test-profile-id");

      Map<String, Object> params = Map.of("duration_seconds", 30, "package_filter", "org.example.custom");

      toolWithMock.executeAsync(params).get();

    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void returns503WhenProfilerDisabled() throws Exception {
      when(mockProfilerService.isEnabled()).thenReturn(false);

      Map<String, Object> params = Map.of("duration_seconds", 30);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(503, error.code());
      assertTrue(error.message().contains("disabled"));
    }

    @Test
    void returns501WhenJFRNotAvailable() throws Exception {
      when(mockProfilerService.isJFRAvailable()).thenReturn(false);

      Map<String, Object> params = Map.of("duration_seconds", 30);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(501, error.code());
      assertTrue(error.message().contains("JFR not available"));
      assertTrue(error.message().contains("JDK 11+"));
    }

    @Test
    void returns500OnProfilerException() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenThrow(new ProfilerException("Simulated failure"));

      Map<String, Object> params = Map.of("duration_seconds", 30);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(500, error.code());
      assertTrue(error.message().contains("Failed to start profiling"));
      assertTrue(error.message().contains("Simulated failure"));
    }

    @Test
    void returns9999OnUnexpectedException() throws Exception {
      when(mockProfilerService.startProfiling(any(Duration.class), any(ProfilerConfig.class)))
          .thenThrow(new RuntimeException("Unexpected error"));

      Map<String, Object> params = Map.of("duration_seconds", 30);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(9999, error.code());
      assertTrue(error.message().contains("Profiler start failed"));
    }
  }

  @Nested
  class Integration {

    @Test
    void startsProfileSuccessfully() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 10, "profile_type", "cpu");

      // Start the profile
      ToolResponse response = toolWithReal.executeAsync(params).get();
      assertTrue(response instanceof ToolResponse.Success);

      // Parse response to get profile ID
      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);
      String profileId = (String) responseData.get("profile_id");
      assertNotNull(profileId);
      createdProfileIds.add(profileId); // Track for cleanup

      assertEquals(true, responseData.get("success"));
      assertEquals("recording", responseData.get("status"));
      assertEquals(10, responseData.get("duration_seconds"));
      assertEquals("cpu", responseData.get("profile_type"));

      // Verify JFR file is created
      assertTrue(jfrFileExists(profileId), "JFR file should exist");
    }

    @Test
    void startsAllocationProfileSuccessfully() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 10, "profile_type", "allocation");

      ToolResponse response = toolWithReal.executeAsync(params).get();
      assertTrue(response instanceof ToolResponse.Success);

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);
      String profileId = (String) responseData.get("profile_id");
      createdProfileIds.add(profileId);

      assertEquals("allocation", responseData.get("profile_type"));
      assertTrue(jfrFileExists(profileId));
    }

    @Test
    void startsComprehensiveProfileSuccessfully() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 10, "profile_type", "comprehensive");

      ToolResponse response = toolWithReal.executeAsync(params).get();
      assertTrue(response instanceof ToolResponse.Success);

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);
      String profileId = (String) responseData.get("profile_id");
      createdProfileIds.add(profileId);

      assertEquals("comprehensive", responseData.get("profile_type"));
      assertTrue(jfrFileExists(profileId));
    }

    @Test
    void profileAutoStopsAfterDuration() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 10, // Minimum valid duration
          "profile_type", "cpu");

      ToolResponse response = toolWithReal.executeAsync(params).get();
      if (response instanceof ToolResponse.Error error) {
        throw new AssertionError(
            "Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");
      }
      assertTrue(response instanceof ToolResponse.Success);

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);
      String profileId = (String) responseData.get("profile_id");
      createdProfileIds.add(profileId);

      // Run some CPU work while profiling
      runCPUWorkload(500);

      // Wait for auto-stop (10 seconds + buffer)
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot, "Profile should complete within 15 seconds");

      // Verify JFR file size increased (contains data)
      long fileSize = getJfrFileSize(profileId);
      assertTrue(fileSize > 1000, "JFR file should contain data (size=" + fileSize + ")");
    }

    @Test
    void preventsMultipleConcurrentProfiles() throws Exception {
      // Start first profile
      Map<String, Object> params1 = Map.of("duration_seconds", 10, "profile_type", "cpu");

      ToolResponse response1 = toolWithReal.executeAsync(params1).get();
      assertTrue(response1 instanceof ToolResponse.Success);

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData1 = objectMapper.readValue(((ToolResponse.Success) response1).content(),
          Map.class);
      String profileId1 = (String) responseData1.get("profile_id");
      createdProfileIds.add(profileId1);

      // Try to start second profile while first is active
      Map<String, Object> params2 = Map.of("duration_seconds", 10, "profile_type", "cpu");

      ToolResponse response2 = toolWithReal.executeAsync(params2).get();

      // Should fail with error about active session
      assertTrue(response2 instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response2;
      assertEquals(500, error.code());
      assertTrue(error.message().contains("already in progress"));
    }

    @Test
    void startsProfileWithCustomPackageFilter() throws Exception {
      Map<String, Object> params = Map.of("duration_seconds", 10, "profile_type", "cpu", "package_filter",
          "org.example.custom");

      ToolResponse response = toolWithReal.executeAsync(params).get();
      assertTrue(response instanceof ToolResponse.Success);

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);
      String profileId = (String) responseData.get("profile_id");
      createdProfileIds.add(profileId);

      assertTrue(jfrFileExists(profileId));
    }
  }
}
