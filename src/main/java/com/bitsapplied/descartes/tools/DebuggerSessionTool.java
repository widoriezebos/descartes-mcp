package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;

/**
 * MCP tool for managing debug sessions.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@code start} - Start a debug session with optional configuration</li>
 * <li>{@code stop} - Stop the active debug session</li>
 * <li>{@code status} - Get current session status</li>
 * <li>{@code threads} - List all threads in the debuggee</li>
 * <li>{@code suspend} - Suspend a specific thread</li>
 * <li>{@code resume} - Resume a specific thread</li>
 * <li>{@code resumeAll} - Resume all suspended threads</li>
 * </ul>
 *
 * <p>
 * The debugger service is injected via constructor to enable test isolation and
 * independent session management.
 */
public class DebuggerSessionTool extends AbstractDebuggerTool {

  /**
   * Creates a debugger session tool with the specified debugger service.
   *
   * @param debuggerService  the debugger service to use
   * @param debuggerExecutor the debugger executor
   */
  public DebuggerSessionTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_session";
  }

  @Override
  public String getToolDescription() {
    return "Manages debug sessions for runtime debugging. Supports starting/stopping sessions, "
        + "listing threads, suspending/resuming execution. Enables runtime inspection and "
        + "debugging of the JVM process. Requires JDK 11+ (JDK 17+ needs --add-opens flag).";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum",
                List.of("start", "stop", "status", "threads", "suspend", "resume", "resume_all"), "description",
                "The session operation to perform"),
            "jdwp_timeout",
            Map.of("type", "integer", "description", "JDWP connection timeout in milliseconds (for start operation)",
                "default", 5000),
            "stop_on_entry",
            Map.of("type", "boolean", "description", "Stop on entry point (for start operation)", "default", false),
            "skip_patterns",
            Map.of("type", "array", "items", Map.of("type", "string"), "description",
                "Class patterns to skip when stepping (for start operation)", "default",
                List.of("java.*", "javax.*", "jdk.*", "sun.*")),
            "thread_id", Map.of("type", "integer", "description", "Thread ID for suspend/resume operations")),
        "required", List.of("operation"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Operation is required");
    }

    return switch (operation) {
    case "start" -> handleStart(arguments);
    case "stop" -> handleStop();
    case "status" -> handleStatus();
    case "threads" -> handleThreads();
    case "suspend" -> handleSuspend(arguments);
    case "resume" -> handleResume(arguments);
    case "resume_all" -> handleResumeAll();
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Unknown operation: " + operation);
    };
  }

  /**
   * Handles the 'start' operation.
   */
  private ToolResponse handleStart(Map<String, Object> arguments) throws Exception {
    int jdwpTimeout = getIntParam(arguments, "jdwp_timeout", 5000);
    boolean stopOnEntry = getBooleanParam(arguments, "stop_on_entry", false);
    String[] skipPatterns = getStringArrayParam(arguments, "skip_patterns",
        new String[] { "java.*", "javax.*", "jdk.*", "sun.*" });

    DebugSessionConfig config = new DebugSessionConfig(jdwpTimeout, stopOnEntry, skipPatterns);

    debuggerService.start(config);

    Map<String, Object> result = Map.of("status", "success", "message", "Debug session started successfully", "state",
        debuggerService.getState().toString(), "config",
        Map.of("jdwp_timeout", jdwpTimeout, "stop_on_entry", stopOnEntry, "skip_patterns", skipPatterns));

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'stop' operation.
   */
  private ToolResponse handleStop() throws Exception {
    debuggerService.stop();

    Map<String, Object> result = Map.of("status", "success", "message", "Debug session stopped", "state",
        debuggerService.getState().toString());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'status' operation.
   */
  private ToolResponse handleStatus() throws Exception {
    Map<String, Object> result = new HashMap<>();
    result.put("state", debuggerService.getState().toString());
    result.put("active", debuggerService.isActive());

    if (debuggerService.getConfig() != null) {
      result.put("config", Map.of("jdwp_timeout", debuggerService.getConfig().jdwpTimeout(), "stop_on_entry",
          debuggerService.getConfig().stopOnEntry(), "skip_patterns", debuggerService.getConfig().skipPatterns()));
    }

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'threads' operation.
   */
  private ToolResponse handleThreads() throws Exception {
    List<ThreadInfo> threads = debuggerService.getThreads();

    Map<String, Object> result = Map.of("status", "success", "thread_count", threads.size(), "threads",
        threads.stream().map(this::threadInfoToMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'suspend' operation.
   */
  private ToolResponse handleSuspend(Map<String, Object> arguments) throws Exception {
    long threadId = getIntParam(arguments, "thread_id", -1);
    if (threadId == -1) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id is required for suspend operation");
    }

    debuggerService.suspendThread(threadId);

    Map<String, Object> result = Map.of("status", "success", "message", "Thread suspended", "thread_id", threadId);

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'resume' operation.
   */
  private ToolResponse handleResume(Map<String, Object> arguments) throws Exception {
    long threadId = getIntParam(arguments, "thread_id", -1);
    if (threadId == -1) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id is required for resume operation");
    }

    debuggerService.resumeThread(threadId);

    Map<String, Object> result = Map.of("status", "success", "message", "Thread resumed", "thread_id", threadId);

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'resumeAll' operation.
   */
  private ToolResponse handleResumeAll() throws Exception {
    debuggerService.resumeAll();

    Map<String, Object> result = Map.of("status", "success", "message", "All threads resumed");

    return ToolResponse.successJson(result);
  }

  // ========== Helper Methods ==========

  /**
   * Converts ThreadInfo to a map for JSON serialization.
   */
  private Map<String, Object> threadInfoToMap(ThreadInfo info) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", info.id());
    map.put("name", info.name());
    map.put("state", info.state());
    map.put("suspended", info.suspended());
    map.put("is_virtual", info.isVirtual());

    if (info.suspendedReason() != null) {
      map.put("suspended_reason", info.suspendedReason());
    }

    if (info.suspendedLocation() != null) {
      map.put("suspended_location", Map.of("class", info.suspendedLocation().declaringType().name(), "method",
          info.suspendedLocation().method().name(), "line", info.suspendedLocation().lineNumber()));
    }

    return map;
  }

  /**
   * Gets an integer parameter with a default value. Handles both Number and
   * String types from MCP layer.
   */
  private int getIntParam(Map<String, Object> args, String key, int defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number num) {
      return num.intValue();
    }
    if (value instanceof String str) {
      try {
        return Integer.parseInt(str);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  /**
   * Gets a boolean parameter with a default value.
   */
  private boolean getBooleanParam(Map<String, Object> args, String key, boolean defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String str) {
      return Boolean.parseBoolean(str);
    }
    return defaultValue;
  }

  /**
   * Gets a string array parameter with a default value.
   */
  private String[] getStringArrayParam(Map<String, Object> args, String key, String[] defaultValue) {
    Object value = args.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(Object::toString).toArray(String[]::new);
    }
    if (value instanceof String[] arr) {
      return arr;
    }
    return defaultValue;
  }

}
