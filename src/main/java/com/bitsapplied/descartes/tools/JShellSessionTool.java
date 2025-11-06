package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.util.JShellSessionManager;
import com.bitsapplied.descartes.util.JShellSessionManagers;

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
    this.sessionManager = JShellSessionManagers.getOrCreate(this.context);
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
    Map<String, Object> properties = new HashMap<>();
    properties.put("action",
        Map.of("type", "string", "enum",
            List.of("close", "extend_expiry", "session_count", "get_max_sessions", "set_max_sessions"), "description",
            "Session management action to perform"));
    properties.put("session_id",
        Map.of("type", "string", "description", "Session ID for close or extend_expiry actions"));
    properties.put("expiry_minutes",
        Map.of("type", "integer", "minimum", 1,
            "description", "Minutes to extend expiry for extend_expiry action (defaults to configured timeout)."));
    properties.put("max_sessions", Map.of("type", "integer", "minimum", 1,
        "description", "New maximum number of active sessions for set_max_sessions action"));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("description", "Manage JShell sessions: close sessions, extend expiry, or adjust limits.");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("action"));
    schema.put("allOf",
        List.of(Map.of("if",
            Map.of("properties",
                Map.of("action", Map.of("enum", List.of("close", "extend_expiry"))), "required", List.of("action")),
            "then", Map.of("required", List.of("session_id"))),
            Map.of("if",
                Map.of("properties", Map.of("action", Map.of("const", "extend_expiry")), "required", List.of("action")),
                "then", Map.of("required", List.of("expiry_minutes"))),
            Map.of("if",
                Map.of("properties", Map.of("action", Map.of("const", "set_max_sessions")), "required",
                    List.of("action")),
                "then", Map.of("required", List.of("max_sessions")))));
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Objects.requireNonNull(arguments, "arguments");
        String action = optString(arguments, "action");
        if (action == null || action.trim().isEmpty()) {
          throw new IllegalArgumentException("'action' is required");
        }

        String sessionId = optString(arguments, "session_id");
        Integer expiryMinutes = optInteger(arguments, "expiry_minutes");
        Integer maxSessions = optInteger(arguments, "max_sessions");

        return switch (action.trim().toLowerCase()) {
        case "close" -> {
          if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("'session_id' is required for close action");
          }
          sessionManager.closeSession(sessionId);
          yield ToolResponse.successJson(buildResponse("close", Map.of("session_id", sessionId)));
        }
        case "extend_expiry" -> {
          if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("'session_id' is required for extend_expiry action");
          }
          boolean extended = sessionManager.extendSessionExpiry(sessionId, expiryMinutes);
          Map<String, Object> response = new HashMap<>();
          response.put("success", extended); // true if session found and extended, false otherwise
          response.put("action", "extend_expiry");
          response.put("session_id", sessionId);
          response.put("expiry_minutes", expiryMinutes);
          yield ToolResponse.successJson(response);
        }
        case "session_count" -> {
          int count = sessionManager.getSessionCount();
          yield ToolResponse.successJson(buildResponse("session_count", Map.of("active_sessions", count)));
        }
        case "get_max_sessions" -> {
          int currentMax = sessionManager.getMaxSessions();
          yield ToolResponse.successJson(buildResponse("get_max_sessions", Map.of("max_sessions", currentMax)));
        }
        case "set_max_sessions" -> {
          if (maxSessions == null) {
            throw new IllegalArgumentException("'max_sessions' is required for set_max_sessions action");
          }
          sessionManager.setMaxSessions(maxSessions);
          yield ToolResponse.successJson(buildResponse("set_max_sessions", Map.of("max_sessions", maxSessions)));
        }
        default -> throw new IllegalArgumentException("Unknown action: " + action
            + ". Supported actions: close, extend_expiry, session_count, get_max_sessions, set_max_sessions");
        };
      } catch (IllegalArgumentException e) {
        return ToolResponse.validationError(e.getMessage());
      } catch (Exception e) {
        return ToolResponse.executionFailed("Session management failed: " + e.getMessage());
      }
    });
  }

  @Override
  public void close() {
    // Lifecycle handled centrally via JShellSessionManagers
  }

  // ---- small helpers ----

  protected static String optString(Map<String, Object> map, String key) {
    return Optional.ofNullable(map.get(key)).filter(String.class::isInstance).map(String.class::cast).orElse(null);
  }

  protected static Integer optInteger(Map<String, Object> map, String key) {
    return Optional.ofNullable(map.get(key)).filter(Number.class::isInstance).map(n -> ((Number) n).intValue())
        .orElse(null);
  }

  /**
   * Builds a response map with success flag and action.
   *
   * @param action the action performed
   * @param data   additional data to include in the response
   * @return a map representing the response
   */
  private static Map<String, Object> buildResponse(String action, Map<String, Object> data) {
    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("action", action);
    response.putAll(data);
    return response;
  }
}
