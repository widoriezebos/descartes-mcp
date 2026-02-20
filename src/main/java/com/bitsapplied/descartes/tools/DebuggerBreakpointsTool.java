package com.bitsapplied.descartes.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointInfo;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointLineMode;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointLineResolution;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointState;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointUpsertAction;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointUpsertResult;
import com.sun.jdi.request.EventRequest;

/**
 * MCP tool for managing breakpoints.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@code set} - Set a breakpoint at specified class and line</li>
 * <li>{@code remove} - Remove a breakpoint by ID</li>
 * <li>{@code removeAll} - Remove all breakpoints</li>
 * <li>{@code list} - List all active breakpoints</li>
 * <li>{@code enable} - Enable a disabled breakpoint</li>
 * <li>{@code disable} - Disable an enabled breakpoint</li>
 * </ul>
 */
public class DebuggerBreakpointsTool extends AbstractDebuggerTool {

  public DebuggerBreakpointsTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_breakpoints";
  }

  @Override
  public String getToolDescription() {
    return "Manages breakpoints for runtime debugging. Supports setting breakpoints at specific "
        + "class/line locations, listing active breakpoints, enabling/disabling breakpoints, "
        + "and removing breakpoints. Breakpoints suspend execution when hit.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("operation",
        Map.of("type", "string", "enum",
            List.of("set", "upsert", "resolve_line", "remove", "remove_all", "list", "enable", "disable"),
            "description", "Breakpoint operation to perform"));
    properties.put("class_name",
        Map.of("type", "string", "description", "Fully qualified class name (required for operation 'set')"));
    properties.put("line_number",
        Map.of("type", "integer", "minimum", 1, "description", "Line number for breakpoint (required for 'set')"));
    properties.put("condition", Map.of("type", "string", "description",
        "Optional breakpoint condition expression evaluated in the debuggee JVM"));
    properties.put("suspend_policy",
        Map.of("type", "string", "enum", List.of("thread", "all", "none"), "default", "thread", "description",
            "Suspension behavior when breakpoint is hit: 'thread' suspends only the triggering thread (default, "
                + "recommended), 'all' suspends entire VM (may freeze unrelated operations), 'none' does not suspend "
                + "(logging/metrics only)"));
    properties.put("defer_if_unloaded",
        Map.of("type", "boolean", "default", true, "description",
            "When true (default), stores breakpoint as pending if target class is not loaded and resolves it on class "
                + "prepare. When false, setting a breakpoint on an unloaded class fails immediately."));
    properties.put("enabled", Map.of("type", "boolean", "default", true, "description",
        "Whether the resulting breakpoint should be enabled"));
    properties.put("line_mode",
        Map.of("type", "string", "enum", List.of("exact", "closest"), "default", "closest", "description",
            "Line resolution strategy. 'exact' requires the provided line to be executable. 'closest' snaps to the "
                + "nearest executable line with validation guards."));
    properties.put("strict_same_method", Map.of("type", "boolean", "default", true, "description",
        "When line_mode='closest', reject snapping to a line outside the requested method range."));
    properties.put("max_line_delta", Map.of("type", "integer", "minimum", 0, "default", 3, "description",
        "Maximum allowed absolute line delta when line_mode='closest'."));
    properties.put("breakpoint_id", Map.of("type", "integer", "minimum", 1, "description",
        "Breakpoint identifier returned from 'set'/'upsert' (required for remove/enable/disable)"));

    List<Map<String, Object>> operationRequirements = new ArrayList<>();
    operationRequirements
        .add(
            Map.of("if",
                Map.of("properties", Map.of("operation", Map.of("enum", List.of("set", "upsert", "resolve_line"))),
                    "required", List.of("operation")),
                "then", Map.of("required", List.of("class_name", "line_number"))));
    operationRequirements.add(
        Map.of("if", Map.of("properties", Map.of("operation", Map.of("enum", List.of("remove", "enable", "disable"))),
            "required", List.of("operation")), "then", Map.of("required", List.of("breakpoint_id"))));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("description",
        "Manage JVM breakpoints. Requires an active debugger session before using any operation.");
    return schema;
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      return ToolResponse.missingParameter("operation");
    }

    return switch (operation) {
    case "set", "upsert" -> handleSetOrUpsert(arguments, operation);
    case "resolve_line" -> handleResolveLine(arguments);
    case "remove" -> handleRemove(arguments);
    case "remove_all" -> handleRemoveAll();
    case "list" -> handleList();
    case "enable" -> handleEnable(arguments);
    case "disable" -> handleDisable(arguments);
    default -> ToolResponse.unsupportedOperation(operation,
        "set, upsert, resolve_line, remove, remove_all, list, enable, disable");
    };
  }

  /**
   * Handles the 'set' and 'upsert' operations.
   */
  private ToolResponse handleSetOrUpsert(Map<String, Object> arguments, String operation) throws Exception {
    String className = (String) arguments.get("class_name");
    Object lineNumberObj = arguments.get("line_number");
    String condition = (String) arguments.get("condition");
    String suspendPolicyStr = (String) arguments.get("suspend_policy");
    Boolean deferIfUnloaded = parseBooleanArgument(arguments.get("defer_if_unloaded"));
    Boolean enabled = parseBooleanArgument(arguments.get("enabled"));
    Boolean strictSameMethod = parseBooleanArgument(arguments.get("strict_same_method"));
    BreakpointLineMode lineMode = parseLineMode(arguments.get("line_mode"));
    Integer maxLineDelta = parseNonNegativeInt(arguments.get("max_line_delta"), 3);

    if (className == null || className.trim().isEmpty()) {
      return ToolResponse.missingParameter("class_name");
    }

    if (lineNumberObj == null) {
      return ToolResponse.missingParameter("line_number");
    }

    int lineNumber;
    try {
      lineNumber = lineNumberObj instanceof Number num ? num.intValue() : Integer.parseInt(lineNumberObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("line_number", " must be a valid integer");
    }

    if (lineNumber <= 0) {
      return ToolResponse.invalidParameter("line_number", " must be a positive integer (got " + lineNumber + ")");
    }
    if (deferIfUnloaded == null) {
      return ToolResponse.invalidParameter("defer_if_unloaded", " must be a boolean");
    }
    if (enabled == null) {
      return ToolResponse.invalidParameter("enabled", " must be a boolean");
    }
    if (strictSameMethod == null) {
      return ToolResponse.invalidParameter("strict_same_method", " must be a boolean");
    }
    if (lineMode == null) {
      return ToolResponse.invalidParameter("line_mode", " must be one of: exact, closest");
    }
    if (maxLineDelta == null) {
      return ToolResponse.invalidParameter("max_line_delta", " must be a non-negative integer");
    }

    // Parse suspend policy (default to SUSPEND_EVENT_THREAD)
    int suspendPolicy = parseSuspendPolicy(suspendPolicyStr);

    BreakpointManager bpm = debuggerService.getBreakpointManager();
    BreakpointUpsertResult upsert = bpm.upsertBreakpoint(className, lineNumber, condition, suspendPolicy,
        deferIfUnloaded, enabled, lineMode, strictSameMethod, maxLineDelta);
    BreakpointInfo info = upsert.breakpoint();
    BreakpointUpsertAction action = upsert.action();
    BreakpointLineResolution lineResolution = upsert.lineResolution();

    String message;
    if (action == BreakpointUpsertAction.UNCHANGED) {
      message = "Breakpoint unchanged";
    } else if (action == BreakpointUpsertAction.UPDATED) {
      message = "Breakpoint updated successfully";
    } else if (info.state() == BreakpointState.PENDING) {
      message = "Breakpoint registered (pending class load)";
    } else {
      message = "Breakpoint set successfully";
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("message", message);
    result.put("operation", operation);
    result.put("status_detail", action.toApiValue());
    result.put("breakpoint", info.toMap());
    if (lineResolution != null) {
      result.putAll(lineResolution.toMap());
    }

    return ToolResponse.successJson(result);
  }

  private ToolResponse handleResolveLine(Map<String, Object> arguments) {
    String className = (String) arguments.get("class_name");
    Object lineNumberObj = arguments.get("line_number");
    Boolean deferIfUnloaded = parseBooleanArgument(arguments.get("defer_if_unloaded"));
    Boolean strictSameMethod = parseBooleanArgument(arguments.get("strict_same_method"));
    BreakpointLineMode lineMode = parseLineMode(arguments.get("line_mode"));
    Integer maxLineDelta = parseNonNegativeInt(arguments.get("max_line_delta"), 3);

    if (className == null || className.trim().isEmpty()) {
      return ToolResponse.missingParameter("class_name");
    }
    if (lineNumberObj == null) {
      return ToolResponse.missingParameter("line_number");
    }

    int lineNumber;
    try {
      lineNumber = lineNumberObj instanceof Number num ? num.intValue() : Integer.parseInt(lineNumberObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("line_number", " must be a valid integer");
    }
    if (lineNumber <= 0) {
      return ToolResponse.invalidParameter("line_number", " must be a positive integer (got " + lineNumber + ")");
    }
    if (deferIfUnloaded == null) {
      return ToolResponse.invalidParameter("defer_if_unloaded", " must be a boolean");
    }
    if (strictSameMethod == null) {
      return ToolResponse.invalidParameter("strict_same_method", " must be a boolean");
    }
    if (lineMode == null) {
      return ToolResponse.invalidParameter("line_mode", " must be one of: exact, closest");
    }
    if (maxLineDelta == null) {
      return ToolResponse.invalidParameter("max_line_delta", " must be a non-negative integer");
    }

    BreakpointManager bpm = debuggerService.getBreakpointManager();
    BreakpointLineResolution resolution = bpm.resolveLine(className, lineNumber, lineMode, strictSameMethod,
        maxLineDelta, deferIfUnloaded);

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("operation", "resolve_line");
    result.put("message",
        resolution.pendingClassLoad() ? "Line resolution deferred (pending class load)" : "Line resolved");
    result.put("class_name", className);
    result.putAll(resolution.toMap());
    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'remove' operation.
   */
  private ToolResponse handleRemove(Map<String, Object> arguments) throws Exception {
    Object idObj = arguments.get("breakpoint_id");

    if (idObj == null) {
      return ToolResponse.missingParameter("breakpoint_id");
    }

    long breakpointId;
    try {
      breakpointId = idObj instanceof Number num ? num.longValue() : Long.parseLong(idObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("breakpoint_id", " must be a valid integer");
    }

    BreakpointManager bpm = debuggerService.getBreakpointManager();
    bpm.removeBreakpoint(breakpointId);

    Map<String, Object> result = Map.of("status", "success", "message", "Breakpoint removed", "breakpoint_id",
        breakpointId);

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'removeAll' operation.
   */
  private ToolResponse handleRemoveAll() throws Exception {
    BreakpointManager bpm = debuggerService.getBreakpointManager();
    int count = bpm.getBreakpointCount();
    bpm.removeAllBreakpoints();

    Map<String, Object> result = Map.of("status", "success", "message", "All breakpoints removed", "removed_count",
        count);

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'list' operation.
   */
  private ToolResponse handleList() throws Exception {
    BreakpointManager bpm = debuggerService.getBreakpointManager();
    List<BreakpointInfo> breakpoints = bpm.getAllBreakpoints();

    Map<String, Object> result = Map.of("status", "success", "breakpoint_count", breakpoints.size(), "breakpoints",
        breakpoints.stream().map(BreakpointInfo::toMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'enable' operation.
   */
  private ToolResponse handleEnable(Map<String, Object> arguments) throws Exception {
    Object idObj = arguments.get("breakpoint_id");

    if (idObj == null) {
      return ToolResponse.missingParameter("breakpoint_id");
    }

    long breakpointId;
    try {
      breakpointId = idObj instanceof Number num ? num.longValue() : Long.parseLong(idObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("breakpoint_id", " must be a valid integer");
    }

    BreakpointManager bpm = debuggerService.getBreakpointManager();
    bpm.enableBreakpoint(breakpointId);

    Map<String, Object> result = Map.of("status", "success", "message", "Breakpoint enabled", "breakpoint_id",
        breakpointId);

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'disable' operation.
   */
  private ToolResponse handleDisable(Map<String, Object> arguments) throws Exception {
    Object idObj = arguments.get("breakpoint_id");

    if (idObj == null) {
      return ToolResponse.missingParameter("breakpoint_id");
    }

    long breakpointId;
    try {
      breakpointId = idObj instanceof Number num ? num.longValue() : Long.parseLong(idObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("breakpoint_id", " must be a valid integer");
    }

    BreakpointManager bpm = debuggerService.getBreakpointManager();
    bpm.disableBreakpoint(breakpointId);

    Map<String, Object> result = Map.of("status", "success", "message", "Breakpoint disabled", "breakpoint_id",
        breakpointId);

    return ToolResponse.successJson(result);
  }

  /**
   * Parses suspend policy string to JDI constant.
   *
   * @param policyStr the policy string ("thread", "all", "none", or null)
   * @return the EventRequest suspend policy constant
   */
  private int parseSuspendPolicy(String policyStr) {
    if (policyStr == null || policyStr.trim().isEmpty()) {
      return EventRequest.SUSPEND_EVENT_THREAD; // Default
    }

    return switch (policyStr.toLowerCase().trim()) {
    case "thread" -> EventRequest.SUSPEND_EVENT_THREAD;
    case "all" -> EventRequest.SUSPEND_ALL;
    case "none" -> EventRequest.SUSPEND_NONE;
    default -> EventRequest.SUSPEND_EVENT_THREAD; // Default for invalid values
    };
  }

  private Boolean parseBooleanArgument(Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof Boolean boolValue) {
      return boolValue;
    }
    if (value instanceof String stringValue) {
      String normalized = stringValue.trim().toLowerCase();
      if ("true".equals(normalized)) {
        return true;
      }
      if ("false".equals(normalized)) {
        return false;
      }
      return null;
    }
    return null;
  }

  private BreakpointLineMode parseLineMode(Object value) {
    if (value == null) {
      return BreakpointLineMode.CLOSEST;
    }
    String raw = value.toString().trim().toLowerCase();
    return switch (raw) {
    case "exact" -> BreakpointLineMode.EXACT;
    case "closest" -> BreakpointLineMode.CLOSEST;
    default -> null;
    };
  }

  private Integer parseNonNegativeInt(Object value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    int parsed;
    try {
      parsed = value instanceof Number num ? num.intValue() : Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
    return parsed < 0 ? null : parsed;
  }
}
