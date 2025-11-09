package com.bitsapplied.descartes.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointInfo;
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
        Map.of("type", "string", "enum", List.of("set", "remove", "remove_all", "list", "enable", "disable"),
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
    properties.put("breakpoint_id", Map.of("type", "integer", "minimum", 1, "description",
        "Breakpoint identifier returned from 'set' (required for remove/enable/disable)"));

    List<Map<String, Object>> operationRequirements = new ArrayList<>();
    operationRequirements.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "set")), "required", List.of("operation")), "then",
        Map.of("required", List.of("class_name", "line_number"))));
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
    case "set" -> handleSet(arguments);
    case "remove" -> handleRemove(arguments);
    case "remove_all" -> handleRemoveAll();
    case "list" -> handleList();
    case "enable" -> handleEnable(arguments);
    case "disable" -> handleDisable(arguments);
    default -> ToolResponse.unsupportedOperation(operation, "set, remove, remove_all, list, enable, disable");
    };
  }

  /**
   * Handles the 'set' operation.
   */
  private ToolResponse handleSet(Map<String, Object> arguments) throws Exception {
    String className = (String) arguments.get("class_name");
    Object lineNumberObj = arguments.get("line_number");
    String condition = (String) arguments.get("condition");
    String suspendPolicyStr = (String) arguments.get("suspend_policy");

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

    // Parse suspend policy (default to SUSPEND_EVENT_THREAD)
    int suspendPolicy = parseSuspendPolicy(suspendPolicyStr);

    BreakpointManager bpm = debuggerService.getBreakpointManager();

    long breakpointId = bpm.setBreakpoint(className, lineNumber, condition, suspendPolicy);

    BreakpointInfo info = bpm.getBreakpoint(breakpointId);

    Map<String, Object> result = Map.of("status", "success", "message", "Breakpoint set successfully", "breakpoint",
        info.toMap());

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
}
