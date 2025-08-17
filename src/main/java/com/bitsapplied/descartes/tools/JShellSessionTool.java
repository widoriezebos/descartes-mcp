package com.bitsapplied.descartes.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.bitsapplied.descartes.util.JShellSessionManager;

/**
 * MCP tool for managing JShell sessions (close, extend expiry, list). This tool
 * provides session management operations without executing code.
 */
public class JShellSessionTool implements MCPTool, AutoCloseable {

  private static final String TOOL_NAME = "jshell_session_manager";

  protected final Map<String, Object> context;
  protected final JShellSessionManager sessionManager;

  public JShellSessionTool(Map<String, Object> context) {
    this.context = Objects.requireNonNull(context, "context");
    this.sessionManager = new JShellSessionManager(this.context);
  }

  @Override
  public String getToolName() {
    return TOOL_NAME;
  }

  @Override
  public String getToolDescription() {
    return "Manages JShell sessions: close sessions, extend expiry times, and get session information. "
        + "Use this tool for session lifecycle management without executing code.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "description",
        "Manage JShell sessions: close, extend expiry, or get session count.", "properties",
        Map.of("action", Map.of("type", "string", "enum",
            List.of("close", "extend_expiry", "session_count", "get_max_sessions", "set_max_sessions"), "description",
            "Action to perform: 'close' to close a session, 'extend_expiry' to extend session timeout, 'session_count' to get active session count, 'get_max_sessions' to get current limit, 'set_max_sessions' to change session limit."),
            "session_id", Map.of("type", "string", "description", "Session ID for close/extend_expiry actions."),
            "expiry_minutes",
            Map.of("type", "integer", "description",
                "Minutes to extend expiry for 'extend_expiry' action. Null means use default timeout."),
            "max_sessions",
            Map.of("type", "integer", "description", "New maximum number of sessions for 'set_max_sessions' action.")),
        "required", List.of("action"));
  }

  @Override
  public String executeTool(Map<String, Object> arguments) throws Exception {
    Objects.requireNonNull(arguments, "arguments");
    String action = optString(arguments, "action");
    if (action == null || action.trim().isEmpty()) {
      throw new IllegalArgumentException("'action' is required");
    }

    String sessionId = optString(arguments, "session_id");
    Integer expiryMinutes = optInteger(arguments, "expiry_minutes");
    Integer maxSessions = optInteger(arguments, "max_sessions");

    switch (action.trim().toLowerCase()) {
    case "close":
      if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("'session_id' is required for close action");
      }
      sessionManager.closeSession(sessionId);
      return "{\"success\": true, \"action\": \"close\", \"session_id\": \"" + sessionId + "\"}";

    case "extend_expiry":
      if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("'session_id' is required for extend_expiry action");
      }
      boolean extended = sessionManager.extendSessionExpiry(sessionId, expiryMinutes);
      return "{\"success\": " + extended + ", \"action\": \"extend_expiry\"" + ", \"session_id\": \"" + sessionId + "\""
          + ", \"expiry_minutes\": " + (expiryMinutes != null ? expiryMinutes : "null") + ", \"found\": " + extended
          + "}";

    case "session_count":
      int count = sessionManager.getSessionCount();
      return "{\"success\": true, \"action\": \"session_count\", \"active_sessions\": " + count + "}";

    case "get_max_sessions":
      int currentMax = sessionManager.getMaxSessions();
      return "{\"success\": true, \"action\": \"get_max_sessions\", \"max_sessions\": " + currentMax + "}";

    case "set_max_sessions":
      if (maxSessions == null) {
        throw new IllegalArgumentException("'max_sessions' is required for set_max_sessions action");
      }
      sessionManager.setMaxSessions(maxSessions);
      return "{\"success\": true, \"action\": \"set_max_sessions\", \"max_sessions\": " + maxSessions + "}";

    default:
      throw new IllegalArgumentException("Unknown action: " + action
          + ". Supported actions: close, extend_expiry, session_count, get_max_sessions, set_max_sessions");
    }
  }

  @Override
  public void close() {
    try {
      sessionManager.close();
    } catch (Exception ignored) {
    }
  }

  // ---- small helpers ----

  protected static String optString(Map<String, Object> map, String key) {
    return Optional.ofNullable(map.get(key)).filter(String.class::isInstance).map(String.class::cast).orElse(null);
  }

  protected static Integer optInteger(Map<String, Object> map, String key) {
    return Optional.ofNullable(map.get(key)).filter(Number.class::isInstance).map(n -> ((Number) n).intValue())
        .orElse(null);
  }
}