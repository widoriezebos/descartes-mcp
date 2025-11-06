package com.bitsapplied.descartes.tools;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointInfo;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;

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
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum", List.of("set", "remove", "remove_all", "list", "enable", "disable"),
                "description", "The breakpoint operation to perform"),
            "class_name", Map.of("type", "string", "description", "Fully qualified class name (for set operation)"),
            "line_number", Map.of("type", "integer", "description", "Line number (for set operation)"), "condition",
            Map.of("type", "string", "description", "Optional breakpoint condition expression (for set operation)"),
            "breakpoint_id",
            Map.of("type", "integer", "description", "Breakpoint ID (for remove/enable/disable operations)")),
        "required", List.of("operation"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Operation is required");
    }

    return switch (operation) {
    case "set" -> handleSet(arguments);
    case "remove" -> handleRemove(arguments);
    case "remove_all" -> handleRemoveAll();
    case "list" -> handleList();
    case "enable" -> handleEnable(arguments);
    case "disable" -> handleDisable(arguments);
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Unknown operation: " + operation);
    };
  }

  /**
   * Handles the 'set' operation.
   */
  private ToolResponse handleSet(Map<String, Object> arguments) throws Exception {
    String className = (String) arguments.get("class_name");
    Object lineNumberObj = arguments.get("line_number");
    String condition = (String) arguments.get("condition");

    if (className == null || className.trim().isEmpty()) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "class_name is required and must not be empty");
    }

    if (lineNumberObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "line_number is required for set operation");
    }

    int lineNumber;
    try {
      lineNumber = lineNumberObj instanceof Number num ? num.intValue() : Integer.parseInt(lineNumberObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "line_number must be a valid integer: " + lineNumberObj);
    }

    if (lineNumber <= 0) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "line_number must be positive (got: " + lineNumber + ")");
    }

    BreakpointManager bpm = debuggerService.getBreakpointManager();

    long breakpointId = condition != null ? bpm.setBreakpoint(className, lineNumber, condition)
        : bpm.setBreakpoint(className, lineNumber);

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
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "breakpoint_id is required for remove operation");
    }

    long breakpointId;
    try {
      breakpointId = idObj instanceof Number num ? num.longValue() : Long.parseLong(idObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "breakpoint_id must be a valid number: " + idObj);
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
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "breakpoint_id is required for enable operation");
    }

    long breakpointId;
    try {
      breakpointId = idObj instanceof Number num ? num.longValue() : Long.parseLong(idObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "breakpoint_id must be a valid number: " + idObj);
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
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "breakpoint_id is required for disable operation");
    }

    long breakpointId;
    try {
      breakpointId = idObj instanceof Number num ? num.longValue() : Long.parseLong(idObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "breakpoint_id must be a valid number: " + idObj);
    }

    BreakpointManager bpm = debuggerService.getBreakpointManager();
    bpm.disableBreakpoint(breakpointId);

    Map<String, Object> result = Map.of("status", "success", "message", "Breakpoint disabled", "breakpoint_id",
        breakpointId);

    return ToolResponse.successJson(result);
  }
}
