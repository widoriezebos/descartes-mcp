package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ProfilerListTool.
 *
 * <p>
 * Tests cover:
 * </p>
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Empty lists (no profiles)</li>
 * <li>Active recordings</li>
 * <li>Completed profiles</li>
 * <li>Integration tests</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class ProfilerListToolTest extends ProfilerToolTestBase {

  private ProfilerListTool toolWithMock;
  private ProfilerListTool toolWithReal;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    toolWithMock = new ProfilerListTool(mockProfilerService);
    toolWithReal = new ProfilerListTool(realProfilerService);
    objectMapper = new ObjectMapper();
  }

  @Nested
  class ToolMetadata {

    @Test
    void hasCorrectName() {
      assertEquals("profiler_list", toolWithMock.getToolName());
    }

    @Test
    void hasDescription() {
      String description = toolWithMock.getToolDescription();
      assertNotNull(description);
      assertTrue(description.contains("List"));
      assertTrue(description.contains("profiles"));
    }

    @Test
    void hasValidSchema() {
      Map<String, Object> schema = toolWithMock.getToolSchema();
      assertNotNull(schema);
      assertEquals("object", schema.get("type"));
    }
  }

  @Nested
  class EmptyLists {

    @Test
    void returnsEmptyListWhenNoProfiles() throws Exception {
      when(mockProfilerService.listActiveRecordings()).thenReturn(List.of());
      when(mockProfilerService.listStoredProfiles()).thenReturn(List.of());

      Map<String, Object> params = Map.of();

      ToolResponse response = toolWithMock.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertEquals(true, responseData.get("success"));
      @SuppressWarnings("unchecked")
      List<String> activeRecordings = (List<String>) responseData.get("active_recordings");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> storedProfiles = (List<Map<String, Object>>) responseData.get("stored_profiles");

      assertTrue(activeRecordings.isEmpty());
      assertTrue(storedProfiles.isEmpty());
      assertEquals(0, responseData.get("total_stored"));
    }
  }

  @Nested
  class Integration {

    @Test
    void listsActiveRecording() throws Exception {
      // Start a profile
      String profileId = startTestProfile(10);

      // List profiles
      Map<String, Object> params = Map.of();
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      @SuppressWarnings("unchecked")
      List<String> activeRecordings = (List<String>) responseData.get("active_recordings");

      assertTrue(activeRecordings.contains(profileId), "Should contain active profile");
    }

    @Test
    void listsCompletedProfile() throws Exception {
      // Start and complete a profile
      String profileId = startTestProfile(10); // Minimum valid duration
      runCPUWorkload(500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // List profiles
      Map<String, Object> params = Map.of();
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> storedProfiles = (List<Map<String, Object>>) responseData.get("stored_profiles");

      // Find our profile in the list
      boolean found = storedProfiles.stream()
          .anyMatch(p -> profileId.equals(p.get("profile_id")) && "completed".equals(p.get("status")));

      assertTrue(found, "Should contain completed profile with status=completed");
    }

    @Test
    void listsMultipleProfiles() throws Exception {
      // Start profile 1 and let it complete
      String profileId1 = startTestProfile(10); // Minimum valid duration
      runCPUWorkload(500);
      var snapshot1 = waitForProfileCompletion(profileId1, 15);
      assertNotNull(snapshot1);

      // Start profile 2 and leave it active
      String profileId2 = startTestProfile(10);

      // List profiles
      Map<String, Object> params = Map.of();
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      @SuppressWarnings("unchecked")
      List<String> activeRecordings = (List<String>) responseData.get("active_recordings");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> storedProfiles = (List<Map<String, Object>>) responseData.get("stored_profiles");

      // Should have at least 2 stored profiles (may have more from previous tests)
      assertTrue(storedProfiles.size() >= 2, "Should have at least 2 profiles");

      // Profile 2 should be active
      assertTrue(activeRecordings.contains(profileId2), "Profile 2 should be active");

      // Total stored count should match list size
      assertEquals(storedProfiles.size(), responseData.get("total_stored"));
    }

    @Test
    void includesProfileMetadata() throws Exception {
      // Start and complete a profile
      String profileId = startTestProfile(10);
      runCPUWorkload(500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // List profiles
      Map<String, Object> params = Map.of();
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> storedProfiles = (List<Map<String, Object>>) responseData.get("stored_profiles");

      // Find our profile
      Map<String, Object> profile = storedProfiles.stream().filter(p -> profileId.equals(p.get("profile_id")))
          .findFirst().orElse(null);

      assertNotNull(profile, "Should find our profile");
      assertNotNull(profile.get("start_time"));
      assertNotNull(profile.get("duration_seconds"));
      assertNotNull(profile.get("total_samples"));
      assertEquals("completed", profile.get("status"));
    }

    @Test
    void acceptsNullParams() throws Exception {
      ToolResponse response = toolWithReal.executeAsync(null).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertNotNull(responseData.get("active_recordings"));
      assertNotNull(responseData.get("stored_profiles"));
    }

    @Test
    void acceptsEmptyParams() throws Exception {
      Map<String, Object> params = Map.of();
      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertNotNull(responseData.get("active_recordings"));
      assertNotNull(responseData.get("stored_profiles"));
    }
  }
}
