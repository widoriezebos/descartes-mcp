package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ProfilerStopTool.
 *
 * <p>
 * Tests cover:
 * </p>
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Parameter validation (profile_id required, non-empty)</li>
 * <li>Error handling (404 for not found, 500 for internal errors)</li>
 * <li>Integration tests with real ProfilerService</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class ProfilerStopToolTest extends ProfilerToolTestBase {

  private ProfilerStopTool toolWithMock;
  private ProfilerStopTool toolWithReal;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    toolWithMock = new ProfilerStopTool(mockProfilerService);
    toolWithReal = new ProfilerStopTool(realProfilerService);
    objectMapper = new ObjectMapper();
  }

  @Nested
  class ToolMetadata {

    @Test
    void hasCorrectName() {
      assertEquals("profiler_stop", toolWithMock.getToolName());
    }

    @Test
    void hasDescription() {
      String description = toolWithMock.getToolDescription();
      assertNotNull(description);
      assertTrue(description.contains("Force-stop"));
      assertTrue(description.contains("profile"));
    }

    @Test
    void hasValidSchema() {
      Map<String, Object> schema = toolWithMock.getToolSchema();
      assertNotNull(schema);
      assertEquals("object", schema.get("type"));

      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      assertNotNull(properties);
      assertTrue(properties.containsKey("profile_id"));

      // Verify profile_id is required
      @SuppressWarnings("unchecked")
      var required = (List<String>) schema.get("required");
      assertNotNull(required);
      assertTrue(required.contains("profile_id"));

      // Verify profile_id schema
      @SuppressWarnings("unchecked")
      Map<String, Object> profileIdSchema = (Map<String, Object>) properties.get("profile_id");
      assertEquals("string", profileIdSchema.get("type"));
    }
  }

  @Nested
  class ParameterValidation {

    @Test
    void rejectsNullParams() throws Exception {
      ToolResponse response = toolWithMock.executeAsync(null).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id is required"));
    }

    @Test
    void rejectsMissingProfileId() throws Exception {
      Map<String, Object> params = Map.of();

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id is required"));
    }

    @Test
    void rejectsNullProfileId() throws Exception {
      Map<String, Object> params = new HashMap<>();
      params.put("profile_id", null);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("must be a non-empty string"));
    }

    @Test
    void rejectsEmptyProfileId() throws Exception {
      Map<String, Object> params = Map.of("profile_id", "");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("must be a non-empty string"));
    }

    @Test
    void rejectsWhitespaceOnlyProfileId() throws Exception {
      Map<String, Object> params = Map.of("profile_id", "   ");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("must be a non-empty string"));
    }

    @Test
    void rejectsNonStringProfileId() throws Exception {
      Map<String, Object> params = Map.of("profile_id", 12345);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("must be a non-empty string"));
    }

    @Test
    void trimsProfileId() throws Exception {
      // Create a mock snapshot
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");

      when(mockProfilerService.stopProfiling(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "  test-id  ");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(((ToolResponse.Success) response).content().contains("test-id"));
    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void returns404WhenProfileNotFound() throws Exception {
      when(mockProfilerService.stopProfiling(anyString()))
          .thenThrow(new ProfilerException("No active recording found with ID: test-id"));

      Map<String, Object> params = Map.of("profile_id", "test-id");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("No active recording"));
    }

    @Test
    void returns500OnOtherProfilerException() throws Exception {
      when(mockProfilerService.stopProfiling(anyString())).thenThrow(new ProfilerException("Internal profiler error"));

      Map<String, Object> params = Map.of("profile_id", "test-id");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(500, error.code());
      assertTrue(error.message().contains("Internal profiler error"));
    }

    @Test
    void returns9999OnUnexpectedException() throws Exception {
      when(mockProfilerService.stopProfiling(anyString())).thenThrow(new RuntimeException("Unexpected error"));

      Map<String, Object> params = Map.of("profile_id", "test-id");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(9999, error.code());
      assertTrue(error.message().contains("Profiler stop failed"));
    }
  }

  @Nested
  class Integration {

    @Test
    void stopsActiveProfileSuccessfully() throws Exception {
      // Start a profile
      String profileId = startTestProfile(10);

      // Run some work
      runCPUWorkload(500);

      // Stop the profile
      Map<String, Object> params = Map.of("profile_id", profileId);
      ToolResponse response = toolWithReal.executeAsync(params).get();

      // Parse response
      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertEquals(true, responseData.get("success"));
      assertEquals(profileId, responseData.get("profile_id"));
      assertEquals("stopped", responseData.get("status"));
      assertNotNull(responseData.get("total_samples"));
      assertNotNull(responseData.get("duration_seconds"));

      // Verify message contains helpful info
      String message = (String) responseData.get("message");
      assertTrue(message.contains("stopped"));
      assertTrue(message.contains("profiler_hotspots"));
    }

    @Test
    void returns404ForNonExistentProfile() throws Exception {
      String fakeProfileId = nonExistentProfileId();

      Map<String, Object> params = Map.of("profile_id", fakeProfileId);
      ToolResponse response = toolWithReal.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("No active recording"));
    }

    @Test
    void returns404ForAlreadyStoppedProfile() throws Exception {
      // Start and stop a profile
      String profileId = startTestProfile(10);
      runCPUWorkload(500);

      Map<String, Object> params = Map.of("profile_id", profileId);
      ToolResponse response1 = toolWithReal.executeAsync(params).get();
      assertTrue(response1 instanceof ToolResponse.Success);

      // Try to stop again
      ToolResponse response2 = toolWithReal.executeAsync(params).get();

      assertTrue(response2 instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response2;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("No active recording"));
    }

    @Test
    void stopsProfileAndReturnsSampleCount() throws Exception {
      // Start a profile
      String profileId = startTestProfile(10);

      // Run substantial CPU work to generate samples
      runCPUWorkload(1000);

      // Stop and check samples
      Map<String, Object> params = Map.of("profile_id", profileId);
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      int totalSamples = (Integer) responseData.get("total_samples");
      assertTrue(totalSamples > 0, "Should have captured some samples (got " + totalSamples + ")");
    }

    @Test
    void stopsProfileEarlyBeforeAutoStop() throws Exception {
      // Start a long profile
      String profileId = startTestProfile(300);

      // Run brief work
      runCPUWorkload(500);

      // Stop early (should work)
      Map<String, Object> params = Map.of("profile_id", profileId);
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      int durationSeconds = (Integer) responseData.get("duration_seconds");
      assertTrue(durationSeconds < 300, "Should stop early, not run full 300 seconds");
    }

    @Test
    void returnsValidSnapshotMetadata() throws Exception {
      // Start a profile
      String profileId = startTestProfile(10);
      runCPUWorkload(500);

      // Stop and verify metadata
      Map<String, Object> params = Map.of("profile_id", profileId);
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      // Verify all expected fields present
      assertNotNull(responseData.get("success"));
      assertNotNull(responseData.get("profile_id"));
      assertNotNull(responseData.get("status"));
      assertNotNull(responseData.get("total_samples"));
      assertNotNull(responseData.get("duration_seconds"));
      assertNotNull(responseData.get("message"));

      // Verify profile_id matches
      assertEquals(profileId, responseData.get("profile_id"));
    }
  }
}
