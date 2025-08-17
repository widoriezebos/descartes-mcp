package com.bitsapplied.descartes.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.bitsapplied.descartes.util.EvalResult;
import com.bitsapplied.descartes.util.JShellSessionManager;
import com.bitsapplied.descartes.util.SessionEvalResult;

/**
 * MCP tool that provides JShell REPL functionality with session management.
 * Each conversation can have its own JShell session for isolated execution.
 */
public class JShellTool implements MCPTool, AutoCloseable {

  private static final String TOOL_NAME = "jshell_repl";

  protected final Map<String, Object> context;
  protected final JShellSessionManager sessionManager;

  public JShellTool(Map<String, Object> context) {
    this.context = Objects.requireNonNull(context, "context");
    this.sessionManager = new JShellSessionManager(this.context);
  }

  @Override
  public String getToolName() {
    return TOOL_NAME;
  }

  @Override
  public String getToolDescription() {
    return "Executes Java code snippets using JShell in-process with session management. "
        + "Each session maintains its own state. Captures System.out/err per evaluation and exposes context variables if referenced.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "description",
        "Run Java code in an embedded JShell with session management. Output is captured per call.", "properties",
        Map.of("code",
            Map.of("type", "string", "description",
                "Java code to evaluate. Context variables may be available depending on configuration."),
            "session_id",
            Map.of("type", "string", "description",
                "Optional session identifier to maintain state across calls. If not provided, a new session is created."),
            "reset",
            Map.of("type", "boolean", "description", "Reset the session before executing the code.", "default", false),
            "close_session",
            Map.of("type", "boolean", "description", "Close the session after executing the code.", "default", false),
            "extend_expiry_minutes",
            Map.of("type", "integer", "description",
                "Extend session expiry to this many minutes from now. If not provided, uses default timeout.")),
        "required", List.of("code"));
  }

  @Override
  public String executeTool(Map<String, Object> arguments) throws Exception {
    Objects.requireNonNull(arguments, "arguments");
    String code = optString(arguments, "code");
    if (code == null || code.trim().isEmpty()) {
      throw new IllegalArgumentException("'code' is required and cannot be empty");
    }
    String sessionId = optString(arguments, "session_id");
    boolean reset = optBoolean(arguments, "reset", false);
    boolean closeSession = optBoolean(arguments, "close_session", false);
    Integer extendExpiryMinutes = optInteger(arguments, "extend_expiry_minutes");

    if (reset && sessionId != null) {
      sessionManager.resetSession(sessionId);
    }

    SessionEvalResult sessionResult = sessionManager.eval(sessionId, code);
    EvalResult evalResult = sessionResult.getEvalResult();

    // Handle session expiry extension
    if (extendExpiryMinutes != null) {
      sessionManager.extendSessionExpiry(sessionResult.getSessionId(), extendExpiryMinutes);
    }

    // Handle session closure
    if (closeSession) {
      sessionManager.closeSession(sessionResult.getSessionId());
    }

    // Add session ID to the result
    EvalResult resultWithSession = evalResult.withSessionId(sessionResult.getSessionId());

    return resultWithSession.toString(); // JSON via EvalResult#toString()
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

  protected static boolean optBoolean(Map<String, Object> map, String key, boolean def) {
    return Optional.ofNullable(map.get(key)).filter(Boolean.class::isInstance).map(Boolean.class::cast).orElse(def);
  }

  protected static Integer optInteger(Map<String, Object> map, String key) {
    return Optional.ofNullable(map.get(key)).filter(Number.class::isInstance).map(n -> ((Number) n).intValue())
        .orElse(null);
  }
}