package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.bitsapplied.descartes.settings.Setting;
import com.bitsapplied.descartes.settings.Settings;
import com.bitsapplied.descartes.util.EvalResult;
import com.bitsapplied.descartes.util.JShellSession;
import com.bitsapplied.descartes.util.JShellSessionManager;
import com.bitsapplied.descartes.util.JShellSessionManagers;
import com.bitsapplied.descartes.util.ParameterUtils;
import com.bitsapplied.descartes.util.SessionEvalResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool that provides JShell REPL functionality with session management.
 * Each conversation can have its own JShell session for isolated execution.
 */
public class JShellTool implements MCPTool, AutoCloseable {

  private static final String TOOL_NAME = "jshell_repl";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  protected final Map<String, Object> context;
  protected final JShellSessionManager sessionManager;
  private final long timeoutSeconds;

  public JShellTool(Map<String, Object> context) {
    this(context, getDefaultTimeout(context));
  }

  public JShellTool(Map<String, Object> context, long timeoutSeconds) {
    this.context = Objects.requireNonNull(context, "context");
    this.sessionManager = JShellSessionManagers.getOrCreate(this.context);
    this.timeoutSeconds = timeoutSeconds;
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
    Map<String, Object> properties = new HashMap<>();
    properties.put("code", Map.of("type", "string", "description",
        "Java code to evaluate. Context variables may be available depending on configuration."));
    properties.put("session_id", Map.of("type", "string", "description",
        "Optional session identifier to maintain state across calls. If omitted, a new session is created."));
    properties.put("reset",
        Map.of("type", "boolean", "description", "Reset the session before executing the code.", "default", false));
    properties.put("close_session",
        Map.of("type", "boolean", "description", "Close the session after executing the code.", "default", false));
    properties.put("extend_expiry_minutes", Map.of("type", "integer", "minimum", 1, "description",
        "Extend session expiry by this many minutes. Uses default timeout when omitted."));
    properties.put("timeout_seconds", Map.of("type", "integer", "minimum", 1, "maximum", 600, "description",
        "Maximum execution time in seconds (prevents infinite loops).", "default", 30));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("description",
        "Run Java snippets inside an embedded JShell with session management. Captures stdout/stderr per call.");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("code"));
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    // Extract timeout from arguments or use default
    Integer timeoutParam = ParameterUtils.getInt(arguments, "timeout_seconds", null);
    long requestedTimeout = timeoutParam != null ? timeoutParam.longValue() : timeoutSeconds;
    if (requestedTimeout <= 0) {
      return CompletableFuture.completedFuture(ToolResponse.invalidParameter("timeout_seconds", " must be positive"));
    }
    final long effectiveTimeout = Math.min(requestedTimeout, 600);

    // Create dedicated executor for this evaluation (allows interrupt on timeout)
    // Using daemon thread so it doesn't prevent JVM shutdown
    ExecutorService evalExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "JShell-Eval-" + System.currentTimeMillis());
      t.setDaemon(true);
      return t;
    });

    // Create scheduled executor for timeout mechanism
    ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "JShell-Timeout-" + System.currentTimeMillis());
      t.setDaemon(true);
      return t;
    });

    // Reference to hold the session ID once known, and the timeout task
    final AtomicReference<String> actualSessionId = new AtomicReference<>();
    final AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();

    CompletableFuture<ToolResponse> future = CompletableFuture.supplyAsync(() -> {
      try {
        Objects.requireNonNull(arguments, "arguments");
        String code = ParameterUtils.getString(arguments, "code", null);
        if (code == null || code.trim().isEmpty()) {
          throw new IllegalArgumentException("'code' is required and cannot be empty");
        }
        String sessionId = ParameterUtils.getString(arguments, "session_id", null);
        boolean reset = ParameterUtils.getBoolean(arguments, "reset", false);
        boolean closeSession = ParameterUtils.getBoolean(arguments, "close_session", false);
        Integer extendExpiryMinutes = ParameterUtils.getInt(arguments, "extend_expiry_minutes", null);

        if (reset && sessionId != null) {
          sessionManager.resetSession(sessionId);
        }

        // FIX: Eagerly get/create session BEFORE scheduling timeout to avoid race
        // condition.
        // This ensures the timeout handler has the correct session ID even if
        // auto-generated.
        // Without this, if sessionId is null and timeout fires before eval completes,
        // the timeout task would read null from actualSessionId and fail to stop the
        // session.
        JShellSession session = sessionManager.getOrCreateSession(sessionId);
        String determinedSessionId = session.getSessionId();
        actualSessionId.set(determinedSessionId);

        // Schedule timeout task with the known session ID
        // This task will be cancelled if evaluation completes successfully
        ScheduledFuture<?> task = timeoutExecutor.schedule(() -> {
          sessionManager.stopSession(determinedSessionId);
        }, effectiveTimeout, TimeUnit.SECONDS);
        timeoutTask.set(task);

        // Use the already-retrieved session for evaluation
        SessionEvalResult sessionResult = sessionManager.evalWithSession(session, code);
        EvalResult evalResult = sessionResult.getEvalResult();

        // Cancel timeout task since evaluation completed successfully
        ScheduledFuture<?> scheduledTask = timeoutTask.get();
        if (scheduledTask != null && !scheduledTask.isDone()) {
          scheduledTask.cancel(false);
        }

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

        Map<String, Object> response = OBJECT_MAPPER.convertValue(resultWithSession, new TypeReference<>() {
        });
        return ToolResponse.successJson(response);
      } catch (IllegalArgumentException e) {
        return ToolResponse.validationError(e.getMessage());
      } catch (Exception e) {
        return ToolResponse.executionFailed("JShell execution failed: " + e.getMessage());
      }
    }, evalExecutor);

    // Apply timeout and cleanup executors
    return future.orTimeout(effectiveTimeout, TimeUnit.SECONDS).whenComplete((_result, throwable) -> {
      // Cancel timeout task if still pending
      ScheduledFuture<?> scheduledTask = timeoutTask.get();
      if (scheduledTask != null && !scheduledTask.isDone()) {
        scheduledTask.cancel(false);
      }

      // Always shutdown the executors when done (success or failure)
      if (throwable instanceof TimeoutException
          || (throwable != null && throwable.getCause() instanceof TimeoutException)) {
        // On timeout: interrupt the eval thread as fallback (JShell.stop() was already
        // called)
        evalExecutor.shutdownNow();
      } else {
        // Normal shutdown for successful/failed evaluations
        evalExecutor.shutdown();
      }
      timeoutExecutor.shutdown();
    }).exceptionally(throwable -> {
      Throwable cause = throwable instanceof TimeoutException ? throwable
          : throwable != null && throwable.getCause() != null ? throwable.getCause() : throwable;
      if (cause instanceof TimeoutException) {
        return ToolResponse
            .timeout(String.format("JShell execution timeout - code ran for more than %d seconds", effectiveTimeout));
      }
      String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "Unknown error";
      return ToolResponse.executionFailed("JShell execution failed: " + message);
    });
  }

  /**
   * Gets the default timeout from Settings if available in context, otherwise
   * uses Setting enum default.
   */
  private static long getDefaultTimeout(Map<String, Object> context) {
    Object settingsObj = context.get("settings");
    if (settingsObj instanceof Settings settings) {
      return settings.getInt(Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS);
    }
    return Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS.defaultValue(Integer.class);
  }

  @Override
  public void close() {
    // Lifecycle handled by JShellSessionManagers
  }
}
