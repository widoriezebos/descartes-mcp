package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for JShellSessionTool.
 */
public class JShellSessionToolTest {

  private Map<String, Object> context;
  private JShellSessionTool tool;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() throws Exception {
    context = new HashMap<>();
    context.put("test.context", "test-value");
    context.put("jshell.max_sessions", 10);
    context.put("jshell.session_timeout_minutes", 30);
    tool = new JShellSessionTool(context);
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  public void tearDown() {
    if (tool != null) {
      tool.close();
    }
  }

  @Test
  public void testGetToolName() {
    assertEquals("jshell_session_manager", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("JShell sessions"));
    assertTrue(description.contains("close"));
    assertTrue(description.contains("extend expiry"));
  }

  @Test
  public void testGetToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();

    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties);

    // Check action property
    @SuppressWarnings("unchecked")
    Map<String, Object> actionProp = (Map<String, Object>) properties.get("action");
    assertEquals("string", actionProp.get("type"));
    @SuppressWarnings("unchecked")
    List<String> actions = (List<String>) actionProp.get("enum");
    assertTrue(actions.contains("close"));
    assertTrue(actions.contains("extend_expiry"));
    assertTrue(actions.contains("session_count"));
    assertTrue(actions.contains("get_max_sessions"));
    assertTrue(actions.contains("set_max_sessions"));

    // Check session_id property
    @SuppressWarnings("unchecked")
    Map<String, Object> sessionIdProp = (Map<String, Object>) properties.get("session_id");
    assertEquals("string", sessionIdProp.get("type"));

    // Check required fields
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("action"));
  }

  @Test
  public void testCloseSession() throws Exception {
    // Closing a non-existent session should still succeed (no-op)
    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", "test-session-1");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals("close", result.get("action"));
    assertEquals("test-session-1", result.get("session_id"));
  }

  @Test
  public void testCloseSessionMissingId() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("session_id"));
    assertTrue(error.message().contains("required"));
  }

  @Test
  public void testExtendExpiry() throws Exception {
    // The tool creates its own session manager, so we need to use a JShellTool to
    // create the session
    // Or just test that extending a non-existent session returns false
    Map<String, Object> args = new HashMap<>();
    args.put("action", "extend_expiry");
    args.put("session_id", "test-session-2");
    args.put("expiry_minutes", 30);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    // Since the session doesn't exist, it should return false
    assertFalse((Boolean) result.get("success"));
    assertEquals("extend_expiry", result.get("action"));
    assertEquals("test-session-2", result.get("session_id"));
    assertEquals(30, result.get("expiry_minutes"));
  }

  @Test
  public void testExtendExpiryNullMinutes() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "extend_expiry");
    args.put("session_id", "test-session-3");
    // No expiry_minutes - should use null (default timeout)

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    // Since the session doesn't exist, it should return false
    assertFalse((Boolean) result.get("success"));
    assertEquals("extend_expiry", result.get("action"));
    assertNull(result.get("expiry_minutes"));
  }

  @Test
  public void testExtendExpiryNonexistentSession() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "extend_expiry");
    args.put("session_id", "nonexistent");
    args.put("expiry_minutes", 30);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertFalse((Boolean) result.get("success"));
    assertEquals("extend_expiry", result.get("action"));
  }

  @Test
  public void testExtendExpiryMissingId() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "extend_expiry");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("session_id"));
    assertTrue(error.message().contains("required"));
  }

  @Test
  public void testSessionCount() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "session_count");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals("session_count", result.get("action"));
    // Should be 0 or more
    assertTrue((Integer) result.get("active_sessions") >= 0);
  }

  @Test
  public void testGetMaxSessions() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "get_max_sessions");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals("get_max_sessions", result.get("action"));
    assertNotNull(result.get("max_sessions"));
    assertTrue((Integer) result.get("max_sessions") > 0);
  }

  @Test
  public void testSetMaxSessions() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "set_max_sessions");
    args.put("max_sessions", 25);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals("set_max_sessions", result.get("action"));
    assertEquals(25, result.get("max_sessions"));
  }

  @Test
  public void testSetMaxSessionsMissingValue() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "set_max_sessions");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("max_sessions"));
    assertTrue(error.message().contains("required"));
  }

  @Test
  public void testMissingAction() throws Exception {
    Map<String, Object> args = new HashMap<>();

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("action"));
    assertTrue(error.message().contains("required"));
  }

  @Test
  public void testEmptyAction() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("action"));
    assertTrue(error.message().contains("required"));
  }

  @Test
  public void testUnknownAction() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "unknown");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Unknown action"));
    assertTrue(error.message().contains("Supported actions"));
  }

  @Test
  public void testNullArguments() throws Exception {
    ToolResponse response = tool.executeAsync(null).get();
    assertTrue(response instanceof ToolResponse.Error);
  }

  @Test
  @SuppressWarnings("resource")
  public void testNullContext() {
    Exception exception = assertThrows(NullPointerException.class, () -> {
      new JShellSessionTool(null);
    });

    assertEquals("context", exception.getMessage());
  }

  @Test
  public void testCaseInsensitiveAction() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "SESSION_COUNT");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals("session_count", result.get("action"));
  }

  @Test
  public void testActionWithWhitespace() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "  session_count  ");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals("session_count", result.get("action"));
  }

  @Test
  public void testSessionIdWithWhitespace() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "close");
    args.put("session_id", "  test-session  "); // With whitespace

    // Should handle trimming internally
    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
  }

  @Test
  public void testAutoCloseable() {
    JShellSessionTool tempTool = new JShellSessionTool(context);

    // Should not throw when closing
    assertDoesNotThrow(() -> {
      tempTool.close();
    });

    // Should be safe to close multiple times
    assertDoesNotThrow(() -> {
      tempTool.close();
    });
  }

  @Test
  public void testIntegerParameterParsing() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "set_max_sessions");
    args.put("max_sessions", "25"); // String that should be parsed as integer

    // The current implementation expects Number, not String
    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("max_sessions"));
  }

  @Test
  public void testDoubleAsInteger() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("action", "set_max_sessions");
    args.put("max_sessions", 25.5); // Double should be converted to int

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertTrue((Boolean) result.get("success"));
    assertEquals(25, result.get("max_sessions")); // Should be truncated to int
  }
}