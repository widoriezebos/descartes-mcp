package com.bitsapplied.descartes.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.util.JShellAsyncTaskManager;
import com.bitsapplied.descartes.util.JShellAsyncTaskManager.JShellAsyncTask;
import com.bitsapplied.descartes.util.JShellAsyncTaskManager.Request;
import com.bitsapplied.descartes.util.JShellAsyncTaskManagers;
import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Asynchronous JShell execution tool. Launches code snippets on a background
 * executor and allows clients to poll for completion without blocking the MCP
 * request.
 */
public class JShellAsyncTool implements MCPTool {

  private final Map<String, Object> context;

  public JShellAsyncTool(Map<String, Object> context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  @Override
  public String getToolName() {
    return "jshell_async";
  }

  @Override
  public String getToolDescription() {
    return "Runs JShell snippets asynchronously. Start a task, poll for status/results, or cancel running evaluations.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("operation", Map.of("type", "string", "enum", List.of("start", "status", "cancel")));
    properties.put("code", Map.of("type", "string", "description", "Java code to evaluate (start operation only)"));
    properties.put("session_id",
        Map.of("type", "string", "description", "Optional session identifier; auto-generated when omitted"));
    properties.put("timeout_seconds",
        Map.of("type", "integer", "minimum", 1, "description", "Optional timeout for the evaluation"));
    properties.put("close_session",
        Map.of("type", "boolean", "description", "Close the JShell session after completion", "default", false));
    properties.put("extend_expiry_minutes",
        Map.of("type", "integer", "minimum", 1, "description", "Extend session expiry on success"));
    properties.put("task_id",
        Map.of("type", "string", "description", "Async task identifier (status/cancel operations)"));
    properties.put("include_result",
        Map.of("type", "boolean", "description", "Include result payload when polling status", "default", true));

    List<Map<String, Object>> constraints = new ArrayList<>();
    constraints.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "start")), "required", List.of("operation")), "then",
        Map.of("required", List.of("code"))));
    constraints.add(Map.of("if", Map.of("properties", Map.of("operation", Map.of("enum", List.of("status", "cancel"))),
        "required", List.of("operation")), "then", Map.of("required", List.of("task_id"))));

    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("allOf", constraints);
    schema.put("description",
        "Asynchronous JShell execution. start => submit code, status => poll for completion, cancel => abort running task.");
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> executeInternal(arguments));
  }

  private ToolResponse executeInternal(Map<String, Object> arguments) {
    String operation = ParameterUtils.getString(arguments, "operation", null);
    if (operation == null) {
      return ToolResponse.missingParameter("operation");
    }

    return switch (operation) {
    case "start" -> handleStart(arguments);
    case "status" -> handleStatus(arguments);
    case "cancel" -> handleCancel(arguments);
    default -> ToolResponse.unsupportedOperation(operation, "start, status, cancel");
    };
  }

  private ToolResponse handleStart(Map<String, Object> arguments) {
    String code;
    try {
      code = ParameterUtils.getRequiredString(arguments, "code");
    } catch (IllegalArgumentException e) {
      return ToolResponse.validationError(e.getMessage());
    }

    if (code.isBlank()) {
      return ToolResponse.invalidParameter("code", "must not be blank");
    }

    String sessionId = ParameterUtils.getString(arguments, "session_id", null);
    Long timeoutSeconds = ParameterUtils.getLong(arguments, "timeout_seconds", null);
    if (timeoutSeconds != null && timeoutSeconds <= 0) {
      return ToolResponse.invalidParameter("timeout_seconds", "must be greater than zero");
    }

    Integer extendExpiry = ParameterUtils.getInt(arguments, "extend_expiry_minutes", null);
    if (extendExpiry != null && extendExpiry <= 0) {
      return ToolResponse.invalidParameter("extend_expiry_minutes", "must be greater than zero");
    }

    boolean closeSession = ParameterUtils.getBoolean(arguments, "close_session", Boolean.FALSE);

    JShellAsyncTaskManager manager = JShellAsyncTaskManagers.getOrCreate(context);
    Request request = new Request(sessionId, code, timeoutSeconds, closeSession, extendExpiry);
    JShellAsyncTask task = manager.startTask(request);

    Map<String, Object> payload = new LinkedHashMap<>(task.toSummary(false));
    payload.put("message", "JShell async task started");
    return ToolResponse.successJson(payload);
  }

  private ToolResponse handleStatus(Map<String, Object> arguments) {
    String taskId;
    try {
      taskId = ParameterUtils.getRequiredString(arguments, "task_id");
    } catch (IllegalArgumentException e) {
      return ToolResponse.validationError(e.getMessage());
    }

    boolean includeResult = ParameterUtils.getBoolean(arguments, "include_result", Boolean.TRUE);
    JShellAsyncTaskManager manager = JShellAsyncTaskManagers.getOrCreate(context);
    Optional<JShellAsyncTask> task = manager.getTask(taskId);
    if (task.isEmpty()) {
      return ToolResponse.error(ToolErrorCode.VALIDATION_FAILED, "Unknown JShell async task id: " + taskId);
    }

    Map<String, Object> payload = task.get().toSummary(includeResult);
    return ToolResponse.successJson(payload);
  }

  private ToolResponse handleCancel(Map<String, Object> arguments) {
    String taskId;
    try {
      taskId = ParameterUtils.getRequiredString(arguments, "task_id");
    } catch (IllegalArgumentException e) {
      return ToolResponse.validationError(e.getMessage());
    }

    JShellAsyncTaskManager manager = JShellAsyncTaskManagers.getOrCreate(context);
    Optional<JShellAsyncTask> task = manager.cancelTask(taskId, "Cancelled on request");
    if (task.isEmpty()) {
      return ToolResponse.error(ToolErrorCode.VALIDATION_FAILED, "Unknown JShell async task id: " + taskId);
    }

    Map<String, Object> payload = task.get().toSummary(true);
    payload.put("message", "JShell async task cancellation requested");
    return ToolResponse.successJson(payload);
  }
}
