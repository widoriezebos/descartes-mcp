package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.profiler.model.Hotspot;
import com.bitsapplied.descartes.profiler.model.ProfileMetadata;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ProfilerHotspotsTool.
 *
 * <p>
 * Tests cover:
 * </p>
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Parameter validation (profile_id, hotspot_type, top_n,
 * min_percentage)</li>
 * <li>Error handling (profile not found, invalid parameters)</li>
 * <li>Integration tests with real profiles</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class ProfilerHotspotsToolTest extends ProfilerToolTestBase {

  private ProfilerHotspotsTool toolWithMock;
  private ProfilerHotspotsTool toolWithReal;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    toolWithMock = new ProfilerHotspotsTool(mockProfilerService);
    toolWithReal = new ProfilerHotspotsTool(realProfilerService);
    objectMapper = new ObjectMapper();
  }

  @Nested
  class ToolMetadata {

    @Test
    void hasCorrectName() {
      assertEquals("profiler_hotspots", toolWithMock.getToolName());
    }

    @Test
    void hasDescription() {
      String description = toolWithMock.getToolDescription();
      assertNotNull(description);
      assertTrue(description.contains("hotspots"));
      assertTrue(description.contains("CPU"));
      assertTrue(description.contains("memory"));
    }

    @Test
    void hasValidSchema() {
      Map<String, Object> schema = toolWithMock.getToolSchema();
      assertNotNull(schema);

      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      assertTrue(properties.containsKey("profile_id"));
      assertTrue(properties.containsKey("hotspot_type"));
      assertTrue(properties.containsKey("top_n"));
      assertTrue(properties.containsKey("min_percentage"));

      // Verify hotspot_type enum
      @SuppressWarnings("unchecked")
      Map<String, Object> hotspotTypeSchema = (Map<String, Object>) properties.get("hotspot_type");
      @SuppressWarnings("unchecked")
      List<String> enumValues = (List<String>) hotspotTypeSchema.get("enum");
      assertTrue(enumValues.contains("cpu"));
      assertTrue(enumValues.contains("allocation"));
      assertTrue(enumValues.contains("lock"));
    }
  }

  @Nested
  class ParameterValidation {

    @Test
    void rejectsMissingProfileId() throws Exception {
      Map<String, Object> params = Map.of("hotspot_type", "cpu");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id is required"));
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
    void rejectsInvalidHotspotType() throws Exception {
      // Create mock snapshot
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "hotspot_type", "invalid");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("Unknown hotspot type"));
    }

    @Test
    void acceptsCpuHotspotType() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "hotspot_type", "cpu");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void acceptsAllocationHotspotType() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "hotspot_type", "allocation");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void acceptsLockHotspotType() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "hotspot_type", "lock");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void rejectsTopNBelowMinimum() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "top_n", 0);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("top_n must be between 1 and 100"));
    }

    @Test
    void rejectsTopNAboveMaximum() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "top_n", 150);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("top_n must be between 1 and 100"));
    }

    @Test
    void acceptsTopNAsString() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "top_n", "50");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void rejectsNonNumericTopN() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "top_n", "not-a-number");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("top_n must be between 1 and 100"));
    }

    @Test
    void rejectsMinPercentageBelowZero() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "min_percentage", -5.0);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("min_percentage must be between 0 and 100"));
    }

    @Test
    void rejectsMinPercentageAboveHundred() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "min_percentage", 150.0);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("min_percentage must be between 0 and 100"));
    }

    @Test
    void acceptsMinPercentageAsString() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "min_percentage", "5.5");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void usesDefaultsWhenOptionalParamsOmitted() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      // Should use defaults: hotspot_type=cpu, top_n=20, min_percentage=1.0
      assertTrue(((ToolResponse.Success) response).content().contains("cpu"));
    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void returns404WhenProfileNotFound() throws Exception {
      when(mockProfilerService.getProfile(anyString())).thenReturn(null);

      Map<String, Object> params = Map.of("profile_id", "nonexistent");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("Profile not found"));
    }
  }

  @Nested
  class Integration {

    @Test
    void returnsHotspotsFromRealProfile() throws Exception {
      // Start a profile with CPU work
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);

      // Wait for completion
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Get CPU hotspots
      Map<String, Object> params = Map.of("profile_id", profileId, "hotspot_type", "cpu");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertEquals(true, responseData.get("success"));
      assertEquals(profileId, responseData.get("profile_id"));
      assertEquals("cpu", responseData.get("hotspot_type"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> hotspots = (List<Map<String, Object>>) responseData.get("hotspots");
      assertNotNull(hotspots);
      assertFalse(hotspots.isEmpty(), "Should have some CPU hotspots");
    }

    @Test
    void filtersHotspotsByMinPercentage() throws Exception {
      // Start and complete a profile
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Get hotspots with high min_percentage (should return fewer results)
      Map<String, Object> params = Map.of("profile_id", profileId, "hotspot_type", "cpu", "min_percentage", 10.0);

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> hotspots = (List<Map<String, Object>>) responseData.get("hotspots");

      // All hotspots should be >= 10%
      for (Map<String, Object> hotspot : hotspots) {
        Object percentageObj = hotspot.get("percentage");
        double percentage;
        if (percentageObj instanceof Number) {
          percentage = ((Number) percentageObj).doubleValue();
        } else {
          // Handle string formats: "98.28", "98,28", "98.28%", "98,28%"
          String percentageStr = percentageObj.toString()
              .replace("%", "")  // Remove percent sign
              .replace(",", ".") // Replace comma with period for parsing
              .trim();
          percentage = Double.parseDouble(percentageStr);
        }
        assertTrue(percentage >= 10.0, "Hotspot percentage should be >= 10%");
      }
    }

    @Test
    void limitsResultsByTopN() throws Exception {
      // Start and complete a profile
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Get only top 5
      Map<String, Object> params = Map.of("profile_id", profileId, "hotspot_type", "cpu", "top_n", 5, "min_percentage",
          0.0);

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> hotspots = (List<Map<String, Object>>) responseData.get("hotspots");

      assertTrue(hotspots.size() <= 5, "Should return at most 5 hotspots");
    }

    @Test
    void includesInsightsAndRecommendations() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "hotspot_type", "cpu");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      // Verify insights and recommendations are present
      assertNotNull(responseData.get("insights"));
      assertNotNull(responseData.get("recommendations"));
    }

    @Test
    void returns404ForNonExistentProfile() throws Exception {
      String fakeId = nonExistentProfileId();

      Map<String, Object> params = Map.of("profile_id", fakeId, "hotspot_type", "cpu");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("Profile not found"));
    }
  }

  /**
   * Create a mock ProfileSnapshot for validation tests.
   */
  @Override
  protected ProfileSnapshot createMockSnapshot(String profileId) {
    return ProfileSnapshot.builder()
        .metadata(ProfileMetadata.builder().profileId(profileId).startTime(Instant.now().minusSeconds(10))
            .endTime(Instant.now()).config(ProfilerConfig.cpuOnly()).build())
        .totalSamples(1000)
        .cpuHotspots(List.of(Hotspot.builder().methodName("compute").className("com.example.MyClass").percentage(45.5)
            .sampleCount(455).sourceFile("MyClass.java").lineNumber(42).build()))
        .allocationHotspots(List.of()).lockHotspots(List.of()).insights(List.of("Test insight"))
        .recommendations(List.of("Test recommendation")).build();
  }
}
