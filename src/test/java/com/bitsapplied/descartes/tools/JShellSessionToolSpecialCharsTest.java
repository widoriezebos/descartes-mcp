package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.util.JShellSessionManager;
import com.bitsapplied.descartes.util.JShellSessionManagers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests JShellSessionTool's handling of special characters and edge cases to
 * verify security improvements from ObjectMapper-based JSON construction.
 *
 * <p>
 * This test verifies that the refactored code properly escapes special
 * characters that could cause JSON injection vulnerabilities in the old manual
 * string concatenation approach.
 */
public class JShellSessionToolSpecialCharsTest {

  private JShellSessionTool tool;
  private Map<String, Object> context;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    context = new HashMap<>();
    tool = new JShellSessionTool(context);
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  public void tearDown() {
    // Clean up session manager
    JShellSessionManagers.shutdown(context);
  }

  /**
   * Test that session IDs with quotes are properly escaped.
   */
  @Test
  public void testSessionIdWithQuotes() throws Exception {
    // Create a session with quotes in the ID
    String sessionIdWithQuotes = "session-\"malicious\"-123";

    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", sessionIdWithQuotes);

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON
    JsonNode node = objectMapper.readTree(json);
    assertTrue(node.get("success").asBoolean());
    assertEquals("close", node.get("action").asText());
    assertEquals(sessionIdWithQuotes, node.get("session_id").asText());
  }

  /**
   * Test that session IDs with newlines are properly escaped.
   */
  @Test
  public void testSessionIdWithNewlines() throws Exception {
    String sessionIdWithNewlines = "session\n\r\t123";

    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", sessionIdWithNewlines);

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON
    JsonNode node = objectMapper.readTree(json);
    assertEquals(sessionIdWithNewlines, node.get("session_id").asText());
  }

  /**
   * Test that session IDs with backslashes are properly escaped.
   */
  @Test
  public void testSessionIdWithBackslashes() throws Exception {
    String sessionIdWithBackslashes = "C:\\Users\\test\\session";

    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", sessionIdWithBackslashes);

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON
    JsonNode node = objectMapper.readTree(json);
    assertEquals(sessionIdWithBackslashes, node.get("session_id").asText());
  }

  /**
   * Test that session IDs with Unicode characters are properly handled.
   */
  @Test
  public void testSessionIdWithUnicode() throws Exception {
    String sessionIdWithUnicode = "session-测试-🔥-123";

    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", sessionIdWithUnicode);

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON
    JsonNode node = objectMapper.readTree(json);
    assertEquals(sessionIdWithUnicode, node.get("session_id").asText());
  }

  /**
   * Test extend_expiry with null expiry_minutes (edge case).
   */
  @Test
  public void testExtendExpiryWithNullMinutes() throws Exception {
    // First create a session to extend
    JShellSessionManager manager = JShellSessionManagers.getOrCreate(context);
    String sessionId = "test-session-" + System.currentTimeMillis();
    manager.getOrCreateSession(sessionId);

    Map<String, Object> args = new HashMap<>();
    args.put("action", "extend_expiry");
    args.put("session_id", sessionId);
    // Don't provide expiry_minutes - should be null

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON and null is properly handled
    JsonNode node = objectMapper.readTree(json);
    assertTrue(node.get("success").asBoolean());
    assertEquals("extend_expiry", node.get("action").asText());
    assertTrue(node.has("expiry_minutes"));
    assertTrue(node.get("expiry_minutes").isNull());
  }

  /**
   * Test all actions return valid JSON structure.
   */
  @Test
  public void testAllActionsReturnValidJson() throws Exception {
    // session_count
    testActionReturnsValidJson("session_count", new HashMap<>());

    // get_max_sessions
    testActionReturnsValidJson("get_max_sessions", new HashMap<>());

    // set_max_sessions
    Map<String, Object> setMaxArgs = new HashMap<>();
    setMaxArgs.put("max_sessions", 42);
    testActionReturnsValidJson("set_max_sessions", setMaxArgs);
  }

  private void testActionReturnsValidJson(String action, Map<String, Object> extraArgs)
      throws InterruptedException, ExecutionException {
    Map<String, Object> args = new HashMap<>();
    args.put("action", action);
    args.putAll(extraArgs);

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON
    try {
      JsonNode node = objectMapper.readTree(json);
      assertNotNull(node);
      assertTrue(node.get("success").asBoolean());
      assertEquals(action, node.get("action").asText());
    } catch (Exception e) {
      throw new AssertionError("Invalid JSON for action " + action + ": " + json, e);
    }
  }

  /**
   * Test that JSON injection attempts are safely escaped.
   *
   * <p>
   * In the old implementation with manual string concatenation, this would have
   * broken the JSON structure: {"success": true, "session_id": "session",
   * "injected": "data"}"}
   */
  @Test
  public void testJsonInjectionAttempt() throws Exception {
    String maliciousSessionId = "session\", \"injected\": \"data";

    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", maliciousSessionId);

    CompletableFuture<ToolResponse> future = tool.executeAsync(args);
    ToolResponse response = future.get();

    assertTrue(response instanceof ToolResponse.Success);
    String json = ((ToolResponse.Success) response).content();

    // Verify it's valid JSON (old implementation would have broken JSON)
    JsonNode node = objectMapper.readTree(json);

    // Verify the malicious content is safely escaped in session_id
    assertEquals(maliciousSessionId, node.get("session_id").asText());

    // Verify there's no "injected" field at root level
    assertTrue(node.get("injected") == null || node.get("injected").isNull(), "JSON injection should not succeed");
  }
}
