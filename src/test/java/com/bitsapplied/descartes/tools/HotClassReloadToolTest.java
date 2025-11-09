package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;
import com.bitsapplied.descartes.util.ToolExecutors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test the HotClassReloadTool MCP interface.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HotClassReloadToolTest {

  private static HotClassReloadTool tool;
  private static ObjectMapper mapper;
  private static boolean agentAvailable;
  private static Map<String, Object> context;

  @BeforeAll
  static void setupClass() {
    context = new ConcurrentHashMap<>();
    tool = new HotClassReloadTool(context);
    mapper = new ObjectMapper();
    agentAvailable = HotReloadAgent.isAgentLoaded();

    if (!agentAvailable) {
      System.err.println("WARNING: Hot reload agent not loaded. Some tests will be skipped.");
    }
  }

  @AfterAll
  static void tearDownClass() {
    tool.close();
    ToolExecutors.shutdownSharedExecutor(context);
  }

  @Test
  @Order(1)
  @DisplayName("Test tool name and description")
  void testToolNameAndDescription() {
    assertEquals("hot_reload_classes", tool.getToolName(), "Tool name should be 'hot_reload_classes'");

    String description = tool.getToolDescription();
    assertNotNull(description, "Description should not be null");
    assertTrue(description.contains("Hot reload"), "Description should mention hot reload");
    assertTrue(description.contains("runtime"), "Description should mention runtime");
  }

  @Test
  @Order(2)
  @DisplayName("Test tool schema")
  void testToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();

    assertNotNull(schema, "Schema should not be null");
    assertEquals("object", schema.get("type"), "Schema type should be 'object'");

    // Check properties
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties, "Properties should be defined");

    // Check packageFilter property
    assertTrue(properties.containsKey("packageFilter"), "Should have packageFilter property");
    @SuppressWarnings("unchecked")
    Map<String, Object> packageFilter = (Map<String, Object>) properties.get("packageFilter");
    assertEquals("string", packageFilter.get("type"), "packageFilter should be string type");

    // Check force property
    assertTrue(properties.containsKey("force"), "Should have force property");
    @SuppressWarnings("unchecked")
    Map<String, Object> force = (Map<String, Object>) properties.get("force");
    assertEquals("boolean", force.get("type"), "force should be boolean type");

    // Check validateOnly property
    assertTrue(properties.containsKey("validateOnly"), "Should have validateOnly property");
    @SuppressWarnings("unchecked")
    Map<String, Object> validateOnly = (Map<String, Object>) properties.get("validateOnly");
    assertEquals("boolean", validateOnly.get("type"), "validateOnly should be boolean type");

    // Check required fields
    String[] required = (String[]) schema.get("required");
    assertNotNull(required, "Required fields should be defined");
    assertEquals(1, required.length, "Should have one required field");
    assertEquals("packageFilter", required[0], "packageFilter should be required");
  }

  @Test
  @Order(3)
  @DisplayName("Test execution with agent - validation mode")
  void testExecutionWithAgentValidation() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.bitsapplied.descartes.hotreload.test.*");
    arguments.put("validateOnly", true);

    String result = ((ToolResponse.Success) tool.executeAsync(arguments).get()).content();
    assertNotNull(result, "Result should not be null");

    JsonNode json = mapper.readTree(result);
    assertNotNull(json.get("status"), "Should have status field");

    String status = json.get("status").asText();
    assertTrue("success".equals(status) || "failed".equals(status), "Status should be 'success' or 'failed'");

    assertTrue(json.has("classesAnalyzed"), "Should have classesAnalyzed field");
    assertTrue(json.has("classesChanged"), "Should have classesChanged field");
    assertTrue(json.has("classesReloaded"), "Should have classesReloaded field");

    if ("success".equals(status)) {
      assertNotNull(json.get("message"), "Should have message on success");
    } else {
      assertNotNull(json.get("error"), "Should have error message on failure");
    }
  }

  @Test
  @Order(4)
  @DisplayName("Test execution with force reload")
  void testExecutionWithForceReload() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.bitsapplied.descartes.hotreload.test.*");
    arguments.put("force", true);

    String result = ((ToolResponse.Success) tool.executeAsync(arguments).get()).content();
    assertNotNull(result, "Result should not be null");

    JsonNode json = mapper.readTree(result);
    assertNotNull(json.get("status"), "Should have status field");

    // Check timing information
    if (json.has("reloadTimeMs")) {
      assertTrue(json.get("reloadTimeMs").asLong() >= 0, "Reload time should be non-negative");
    }
  }

  @Test
  @Order(5)
  @DisplayName("Test execution with non-existent package")
  void testExecutionWithNonExistentPackage() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.nonexistent.package.*");

    ToolResponse response = tool.executeAsync(arguments).get();
    assertTrue(response instanceof ToolResponse.Error, "Expected error response for non-existent package");
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("No classes"), "Error message should mention no classes found");
  }

  @Test
  @Order(6)
  @DisplayName("Test missing required parameter")
  void testMissingRequiredParameter() throws Exception {
    Map<String, Object> arguments = new HashMap<>();
    // packageFilter is missing

    ToolResponse response = tool.executeAsync(arguments).get();
    assertTrue(response instanceof ToolResponse.Error, "Expected error response when packageFilter missing");
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("packageFilter") || error.message().contains("agent not loaded"),
        "Error message should mention the missing packageFilter or agent requirement");
  }

  @Test
  @Order(7)
  @DisplayName("Test result structure")
  void testResultStructure() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.bitsapplied.descartes.hotreload.test.*");
    arguments.put("validateOnly", true);

    String result = ((ToolResponse.Success) tool.executeAsync(arguments).get()).content();
    JsonNode json = mapper.readTree(result);

    // Check all expected fields in result
    assertTrue(json.has("status"), "Should have status field");
    assertTrue(json.has("classesAnalyzed"), "Should have classesAnalyzed field");
    assertTrue(json.has("classesChanged"), "Should have classesChanged field");
    assertTrue(json.has("classesReloaded"), "Should have classesReloaded field");

    // Check optional fields
    if (json.get("status").asText().equals("success")) {
      assertTrue(json.has("message"), "Success should have message");
    }

    if (json.get("status").asText().equals("failed")) {
      assertTrue(json.has("error"), "Failure should have error");
    }

    // Check arrays if present
    if (json.has("reloadedClasses")) {
      assertTrue(json.get("reloadedClasses").isArray(), "reloadedClasses should be an array");
    }

    if (json.has("errors")) {
      assertTrue(json.get("errors").isArray(), "errors should be an array");
    }

    if (json.has("skippedClasses")) {
      assertTrue(json.get("skippedClasses").isObject(), "skippedClasses should be an object");
    }
  }
}
