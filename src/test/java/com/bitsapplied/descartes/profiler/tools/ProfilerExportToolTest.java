package com.bitsapplied.descartes.profiler.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ProfilerExportTool.
 *
 * <p>
 * Tests cover:
 * </p>
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Parameter validation (profile_id, format)</li>
 * <li>All export formats (json, text, flamegraph)</li>
 * <li>Error handling (profile not found, invalid format)</li>
 * <li>Integration tests with real profiles</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class ProfilerExportToolTest extends ProfilerToolTestBase {

  private ProfilerExportTool toolWithMock;
  private ProfilerExportTool toolWithReal;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    toolWithMock = new ProfilerExportTool(mockProfilerService);
    toolWithReal = new ProfilerExportTool(realProfilerService);
    objectMapper = new ObjectMapper();
  }

  @Nested
  class ToolMetadata {

    @Test
    void hasCorrectName() {
      assertEquals("profiler_export", toolWithMock.getToolName());
    }

    @Test
    void hasDescription() {
      String description = toolWithMock.getToolDescription();
      assertNotNull(description);
      assertTrue(description.contains("Export"));
      assertTrue(description.contains("JSON"));
      assertTrue(description.contains("flame graph"));
    }

    @Test
    void hasValidSchema() {
      Map<String, Object> schema = toolWithMock.getToolSchema();
      assertNotNull(schema);

      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      assertTrue(properties.containsKey("profile_id"));
      assertTrue(properties.containsKey("format"));

      // Verify format enum
      @SuppressWarnings("unchecked")
      Map<String, Object> formatSchema = (Map<String, Object>) properties.get("format");
      @SuppressWarnings("unchecked")
      List<String> enumValues = (List<String>) formatSchema.get("enum");
      assertTrue(enumValues.contains("json"));
      assertTrue(enumValues.contains("text"));
      assertTrue(enumValues.contains("flamegraph"));
    }
  }

  @Nested
  class ParameterValidation {

    @Test
    void rejectsMissingProfileId() throws Exception {
      Map<String, Object> params = Map.of("format", "json");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id is required"));
    }

    @Test
    void rejectsEmptyProfileId() throws Exception {
      Map<String, Object> params = Map.of("profile_id", "", "format", "json");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("profile_id must be a non-empty string"));
    }

    @Test
    void rejectsInvalidFormat() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id", "format", "invalid");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(400, error.code());
      assertTrue(error.message().contains("Unsupported export format"));
    }

    @Test
    void usesTextFormatByDefault() throws Exception {
      ProfileSnapshot mockSnapshot = createMockSnapshot("test-id");
      when(mockProfilerService.getProfile(anyString())).thenReturn(mockSnapshot);

      Map<String, Object> params = Map.of("profile_id", "test-id");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      if (response instanceof ToolResponse.Error error) {
        throw new AssertionError("Expected Success but got Error: " + error.message() + " (code: " + error.code() + ")");
      }
      String content = ((ToolResponse.Success) response).content();
      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(content, Map.class);
      assertEquals("text", responseData.get("format"), "Expected format to be 'text'");
    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void returns404WhenProfileNotFound() throws Exception {
      when(mockProfilerService.getProfile(anyString())).thenReturn(null);

      Map<String, Object> params = Map.of("profile_id", "nonexistent", "format", "json");

      ToolResponse response = toolWithMock.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("Profile not found"));
    }
  }

  @Nested
  class JsonExport {

    @Test
    void exportsAsJson() throws Exception {
      // Start and complete a profile
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Export as JSON
      Map<String, Object> params = Map.of("profile_id", profileId, "format", "json");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertEquals(true, responseData.get("success"));
      assertEquals(profileId, responseData.get("profile_id"));
      assertEquals("json", responseData.get("format"));

      // Verify content is valid JSON
      String content = (String) responseData.get("content");
      assertNotNull(content);
      assertTrue(content.length() > 0);

      // Parse it to verify valid JSON
      objectMapper.readValue(content, Map.class);

      // Verify size_bytes is present
      assertNotNull(responseData.get("size_bytes"));
    }

    @Test
    void jsonExportIncludesAllFields() throws Exception {
      String profileId = startTestProfile(10); // Minimum valid duration
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "format", "json");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      String content = (String) responseData.get("content");
      @SuppressWarnings("unchecked")
      Map<String, Object> jsonContent = objectMapper.readValue(content, Map.class);

      // Verify JSON contains key fields
      assertTrue(jsonContent.containsKey("metadata"), "JSON should contain metadata");
      assertTrue(jsonContent.containsKey("total_samples"), "JSON should contain total_samples");

      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) jsonContent.get("metadata");
      assertTrue(metadata.containsKey("profile_id"), "metadata should contain profile_id");
      assertTrue(metadata.containsKey("duration_seconds"), "metadata should contain duration_seconds");
    }
  }

  @Nested
  class TextExport {

    @Test
    void exportsAsText() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "format", "text");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertEquals(true, responseData.get("success"));
      assertEquals("text", responseData.get("format"));

      String content = (String) responseData.get("content");
      assertNotNull(content);
      assertTrue(content.length() > 0);
    }

    @Test
    void textExportIsHumanReadable() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "format", "text");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      String content = (String) responseData.get("content");

      // Text format should contain some readable information
      assertTrue(content.length() > 50, "Text export should contain meaningful content");
    }
  }

  @Nested
  class FlameGraphExport {

    @Test
    void exportsAsFlameGraph() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "format", "flamegraph");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      assertEquals(true, responseData.get("success"));
      assertEquals("flamegraph", responseData.get("format"));

      String content = (String) responseData.get("content");
      assertNotNull(content);
      assertTrue(content.length() > 0);

      // Verify it contains HTML
      assertTrue(content.contains("<html") || content.contains("<!DOCTYPE"));
      assertTrue(content.contains("</html>"));

      // Verify size_bytes
      assertNotNull(responseData.get("size_bytes"));

      // Verify helpful message
      String message = (String) responseData.get("message");
      assertNotNull(message);
      assertTrue(message.contains("browser"));
    }

    @Test
    void flameGraphContainsSVG() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "format", "flamegraph");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      String content = (String) responseData.get("content");

      // Flame graph should contain SVG elements
      assertTrue(content.contains("<svg") || content.contains("svg"), "Flame graph should contain SVG elements");
    }

    @Test
    void flameGraphIsInteractive() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      Map<String, Object> params = Map.of("profile_id", profileId, "format", "flamegraph");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      @SuppressWarnings("unchecked")
      Map<String, Object> responseData = objectMapper.readValue(((ToolResponse.Success) response).content(), Map.class);

      String content = (String) responseData.get("content");

      // Flame graph should contain JavaScript for interactivity
      assertTrue(content.contains("<script") || content.contains("javascript"),
          "Flame graph should contain JavaScript for interactivity");
    }
  }

  @Nested
  class Integration {

    @Test
    void returns404ForNonExistentProfile() throws Exception {
      String fakeId = nonExistentProfileId();

      Map<String, Object> params = Map.of("profile_id", fakeId, "format", "json");

      ToolResponse response = toolWithReal.executeAsync(params).get();

      assertTrue(response instanceof ToolResponse.Error);
      ToolResponse.Error error = (ToolResponse.Error) response;
      assertEquals(404, error.code());
      assertTrue(error.message().contains("Profile not found"));
    }

    @Test
    void exportsSameProfileInDifferentFormats() throws Exception {
      String profileId = startTestProfile(10);
      runCPUWorkload(1500);
      var snapshot = waitForProfileCompletion(profileId, 15);
      assertNotNull(snapshot);

      // Export as JSON
      ToolResponse jsonResponse = toolWithReal.executeAsync(Map.of("profile_id", profileId, "format", "json")).get();
      if (jsonResponse instanceof ToolResponse.Error error) {
        throw new AssertionError("JSON export failed: " + error.message() + " (code: " + error.code() + ")");
      }
      assertTrue(jsonResponse instanceof ToolResponse.Success);

      // Export as text
      ToolResponse textResponse = toolWithReal.executeAsync(Map.of("profile_id", profileId, "format", "text")).get();
      if (textResponse instanceof ToolResponse.Error error) {
        throw new AssertionError("Text export failed: " + error.message() + " (code: " + error.code() + ")");
      }
      assertTrue(textResponse instanceof ToolResponse.Success);

      // Export as flamegraph
      ToolResponse flameResponse = toolWithReal.executeAsync(Map.of("profile_id", profileId, "format", "flamegraph"))
          .get();
      if (flameResponse instanceof ToolResponse.Error error) {
        throw new AssertionError("Flamegraph export failed: " + error.message() + " (code: " + error.code() + ")");
      }
      assertTrue(flameResponse instanceof ToolResponse.Success);

      // All should succeed - parse JSON properly instead of string matching
      @SuppressWarnings("unchecked")
      Map<String, Object> jsonData = objectMapper.readValue(((ToolResponse.Success) jsonResponse).content(), Map.class);
      assertEquals("json", jsonData.get("format"));

      @SuppressWarnings("unchecked")
      Map<String, Object> textData = objectMapper.readValue(((ToolResponse.Success) textResponse).content(), Map.class);
      assertEquals("text", textData.get("format"));

      @SuppressWarnings("unchecked")
      Map<String, Object> flameData = objectMapper.readValue(((ToolResponse.Success) flameResponse).content(), Map.class);
      assertEquals("flamegraph", flameData.get("format"));
    }
  }

}
