package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;

/**
 * Test the HotClassReloadTool behavior when the agent is NOT loaded. This test
 * class runs in the normal test suite, not the hot-reload-tests profile.
 */
public class HotClassReloadToolNoAgentTest {

  private static HotClassReloadTool tool;
  @BeforeAll
  static void setupClass() {
    Map<String, Object> context = new HashMap<>();
    tool = new HotClassReloadTool(context);

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

    ToolResponse response = tool.executeAsync(arguments).get();
    assertTrue(response instanceof ToolResponse.Error, "Response should be an error when agent is missing");
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("agent not loaded"), "Error message should mention agent not loaded");
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

    ToolResponse response = tool.executeAsync(arguments).get();
    assertTrue(response instanceof ToolResponse.Error, "Response should be an error when agent is missing");
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("agent not loaded"), "Error message should mention agent not loaded");
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

    ToolResponse response = tool.executeAsync(arguments).get();
    assertTrue(response instanceof ToolResponse.Error, "Response should be an error when agent is missing");
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("agent not loaded"), "Error message should mention agent not loaded");
  }
}
