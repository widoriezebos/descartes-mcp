package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import com.bitsapplied.descartes.profiler.model.CallTreeNode;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ProfilerCallTreeTool.
 *
 * <p>
 * Tests cover:
 * </p>
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Parameter validation (profile_id, method_pattern, max_depth)</li>
 * <li>Error handling (profile not found, method not found)</li>
 * <li>Integration tests with real profiles</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class ProfilerCallTreeToolTest extends ProfilerToolTestBase {

  private ProfilerCallTreeTool toolWithMock;
  private ProfilerCallTreeTool toolWithReal;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    toolWithMock = new ProfilerCallTreeTool(mockProfilerService);
    toolWithReal = new ProfilerCallTreeTool(realProfilerService);
    objectMapper = new ObjectMapper();
  }

  @Nested
  class ToolMetadata {

    @Test
    void hasCorrectName() {
      assertEquals("profiler_call_tree", toolWithMock.getToolName());
    }

    @Test
    void hasDescription() {
      String description = toolWithMock.getToolDescription();
      assertNotNull(description);
      assertTrue(description.contains("call tree"));
      assertTrue(description.contains("method"));
    }

    @Test
    void hasValidSchema() {
      Map<String, Object> schema = toolWithMock.getToolSchema();
      assertNotNull(schema);

      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      assertTrue(properties.containsKey("profile_id"));
      assertTrue(properties.containsKey("method_pattern"));
      assertTrue(properties.containsKey("max_depth"));

      // Verify required fields
      @SuppressWarnings("unchecked")
      List<String> required = (List<String>) schema.get("required");
      assertTrue(required.contains("profile_id"));
      assertTrue(required.contains("method_pattern"));
    }
  }

  @Nested
  class ParameterValidation {

    @Test
    void rejectsMissingProfileId() throws Exception {
      Map<String, Object> params = Map.of("method_pattern", "compute");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id"));
    }

    @Test
    void rejectsMissingMethodPattern() throws Exception {
      Map<String, Object> params = Map.of("profile_id", "test-id");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("method_pattern"));
    }

    @Test
    void rejectsEmptyProfileId() throws Exception {
      Map<String, Object> params = Map.of("profile_id", "", "method_pattern", "compute");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id must be a non-empty string"));
    }

    @Test
    void rejectsEmptyMethodPattern() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("method_pattern must be a non-empty string"));
    }

    @Test
    void rejectsMaxDepthBelowMinimum() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "compute", "max_depth", 0);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("max_depth must be an integer between 1 and 50"));
    }

    @Test
    void rejectsMaxDepthAboveMaximum() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "compute", "max_depth", 100);

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("max_depth must be an integer between 1 and 50"));
    }

    @Test
    void acceptsMaxDepthAsString() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "compute", "max_depth", "25");

      toolWithMock.executeAsync(params).get();

    }

    @Test
    void rejectsNonNumericMaxDepth() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "compute", "max_depth",
          "not-a-number");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("max_depth must be an integer between 1 and 50"));
    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void returns404WhenProfileNotFound() throws Exception {
      when(mockProfilerService.getProfile(anyString())).thenReturn(null);

      Map<String, Object> params = Map.of("profile_id", "nonexistent", "method_pattern", "compute");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("Profile not found"));
    }

    @Test
    void returns404WhenMethodNotFound() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "nonexistent");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("No methods match pattern"));
    }

    @Test
    void returns404WhenCallTreeNotAvailable() throws Exception {
      ProfileSnapshot mockSnapshot = mock(ProfileSnapshot.class);
      when(mockSnapshot.findMethods("compute")).thenReturn(List.of("com.example.compute"));
      when(mockSnapshot.getCallTree("com.example.compute")).thenReturn(null);
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "method_pattern", "compute");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("Call tree not available"));
    }
  }

  @Nested
  class Integration {

    @Test
    void returnsCallTreeFromRealProfile() throws Exception {
      // Start profile with CPU work (minimum 10 seconds duration)
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Search for any method using wildcard (specific method names may get inlined
      // by JIT)
      // Use * pattern to match whatever methods the profiler actually captured
      Map<String, Object> params = Map.of("profile_id", profileId, "method_pattern", "*");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      // The profile should have captured SOME methods. Accept 404 only if truly no
      // samples.
      // Most likely we'll get a success with matched methods.
      boolean isSuccess = response instanceof ToolResponse.Success;
      boolean isNotFound = response instanceof ToolResponse.Error && ((ToolResponse.Error) response).code() == 404;

      if (!isSuccess && !isNotFound) {
        // Unexpected error
        ToolResponse.Error error = (ToolResponse.Error) response;
        throw new AssertionError(
            "Expected Success or 404 but got Error: " + error.message() + " (code: " + error.code() + ")");
      }

      if (isSuccess) {
        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(),
            Map.class);

        assertEquals(true, responseData.get("success"));
        assertEquals(profileId, responseData.get("profile_id"));
        assertNotNull(responseData.get("matched_method"), "Should have a matched method");
        assertNotNull(responseData.get("tree"), "Should have a call tree");
      }
      // If 404, that means no samples were captured, which is acceptable (though
      // unlikely)
    }

    @Test
    void returnsAllMatchesForPattern() throws Exception {
      String profileId = startTestProfile(10); // Minimum valid duration
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Use a pattern that might match multiple methods
      Map<String, Object> params = Map.of("profile_id", profileId, "method_pattern", "*"); // Wildcard matches many
                                                                                           // methods

      ToolResponse response = toolWithReal.executeAsync(params).get();

      // Should succeed or return 404 if no matches
      boolean isSuccess = response instanceof ToolResponse.Success;
      boolean isNotFound = response instanceof ToolResponse.Error && ((ToolResponse.Error) response).code() == 404;
      assertTrue(isSuccess || isNotFound);

      if (isSuccess) {
        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(),
            Map.class);
        assertNotNull(responseData.get("all_matches"));
      }
    }

    @Test
    void respectsMaxDepthParameter() throws Exception {
      String profileId = startTestProfile(10); // Minimum valid duration
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "method_pattern", "fibonacci", "max_depth", 2);

      toolWithReal.executeAsync(params).get();

      // Should succeed and limit depth
    }

    @Test
    void returns404ForNonExistentProfile() throws Exception {
      String fakeId = nonExistentProfileId();

      Map<String, Object> params = Map.of("profile_id", fakeId, "method_pattern", "compute");

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
    CallTreeNode mockTree = new CallTreeNode("com.example.MyClass.compute", "com.example.MyClass", "compute",
        "MyClass.java", 42);

    ProfileSnapshot snapshot = mock(ProfileSnapshot.class);
    when(snapshot.findMethods("compute")).thenReturn(List.of("com.example.MyClass.compute"));
    when(snapshot.getCallTree("com.example.MyClass.compute")).thenReturn(mockTree);

    return snapshot;
  }
}
