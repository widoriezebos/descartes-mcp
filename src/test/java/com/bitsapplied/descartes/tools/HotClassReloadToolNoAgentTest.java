package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test the HotClassReloadTool behavior when the agent is NOT loaded. This test
 * class runs in the normal test suite, not the hot-reload-tests profile.
 */
public class HotClassReloadToolNoAgentTest {

  private static HotClassReloadTool tool;
  private static ObjectMapper mapper;

  @BeforeAll
  static void setupClass() {
    Map<String, Object> context = new HashMap<>();
    tool = new HotClassReloadTool(context);
    mapper = new ObjectMapper();

    // Verify agent is NOT loaded for this test
    if (HotReloadAgent.isAgentLoaded()) {
      System.err.println("WARNING: Agent is loaded but this test expects it NOT to be loaded");
    }
  }

  @Test
  @DisplayName("Test execution without agent - should return error")
  void testExecutionWithoutAgent() throws Exception {
    // This test should only run when agent is NOT loaded (normal test suite)
    if (HotReloadAgent.isAgentLoaded()) {
      System.out.println("Skipping test - agent is unexpectedly loaded");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.example.*");

    String result = tool.executeTool(arguments);
    assertNotNull(result, "Result should not be null");

    JsonNode json = mapper.readTree(result);
    assertEquals("error", json.get("status").asText(), "Status should be 'error' when agent not loaded");
    assertTrue(json.get("error").asText().contains("agent not loaded"), "Error should mention agent not loaded");
    assertTrue(json.get("agentRequired").asBoolean(), "Should indicate agent is required");
  }

  @Test
  @DisplayName("Test validation mode without agent - should return error")
  void testValidationWithoutAgent() throws Exception {
    // This test should only run when agent is NOT loaded (normal test suite)
    if (HotReloadAgent.isAgentLoaded()) {
      System.out.println("Skipping test - agent is unexpectedly loaded");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.example.*");
    arguments.put("validateOnly", true);

    String result = tool.executeTool(arguments);
    assertNotNull(result, "Result should not be null");

    JsonNode json = mapper.readTree(result);
    assertEquals("error", json.get("status").asText(),
        "Status should be 'error' when agent not loaded, even in validation mode");
    assertTrue(json.get("error").asText().contains("agent not loaded"), "Error should mention agent not loaded");
  }

  @Test
  @DisplayName("Test force reload without agent - should return error")
  void testForceReloadWithoutAgent() throws Exception {
    // This test should only run when agent is NOT loaded (normal test suite)
    if (HotReloadAgent.isAgentLoaded()) {
      System.out.println("Skipping test - agent is unexpectedly loaded");
      return;
    }

    Map<String, Object> arguments = new HashMap<>();
    arguments.put("packageFilter", "com.example.*");
    arguments.put("force", true);

    String result = tool.executeTool(arguments);
    assertNotNull(result, "Result should not be null");

    JsonNode json = mapper.readTree(result);
    assertEquals("error", json.get("status").asText(),
        "Status should be 'error' when agent not loaded, even with force flag");
    assertTrue(json.get("agentRequired").asBoolean(), "Should indicate agent is required");
  }
}