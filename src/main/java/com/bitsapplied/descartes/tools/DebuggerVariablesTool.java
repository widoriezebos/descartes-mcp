package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.models.VariableInfo;
import com.bitsapplied.descartes.debugger.variables.VariableExtractor;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;

/**
 * MCP tool for variable inspection during debugging.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@code getVariables} - Get variables visible in a stack frame</li>
 * <li>{@code getChildVariables} - Get child properties of an expandable
 * variable</li>
 * <li>{@code getStaticFields} - Get static fields of a class</li>
 * </ul>
 *
 * <p>
 * Variables support lazy loading through variable references.
 */
public class DebuggerVariablesTool extends AbstractDebuggerTool {

  public DebuggerVariablesTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_variables";
  }

  @Override
  public String getToolDescription() {
    return "Variable inspection for debugging. Get variables from stack frames, expand object "
        + "properties hierarchically, inspect static fields. Supports lazy loading for "
        + "efficient inspection of complex objects.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum", List.of("get_variables", "get_child_variables", "get_static_fields"),
                "description", "The variable operation to perform"),
            "thread_id", Map.of("type", "integer", "description", "Thread ID (for getVariables operation)"),
            "frame_index", Map.of("type", "integer", "description", "Stack frame index (for getVariables operation)"),
            "variable_reference",
            Map.of("type", "integer", "description", "Variable reference ID (for getChildVariables operation)"),
            "class_name",
            Map.of("type", "string", "description", "Fully qualified class name (for getStaticFields operation)")),
        "required", List.of("operation"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Operation is required");
    }

    return switch (operation) {
    case "get_variables" -> handleGetVariables(arguments);
    case "get_child_variables" -> handleGetChildVariables(arguments);
    case "get_static_fields" -> handleGetStaticFields(arguments);
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Unknown operation: " + operation);
    };
  }

  /**
   * Handles the 'getVariables' operation.
   */
  private ToolResponse handleGetVariables(Map<String, Object> arguments) throws Exception {
    Object threadIdObj = arguments.get("thread_id");
    Object frameIndexObj = arguments.get("frame_index");

    if (threadIdObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id is required for getVariables operation");
    }

    if (frameIndexObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "frame_index is required for getVariables operation");
    }

    long threadId;
    try {
      threadId = threadIdObj instanceof Number num ? num.longValue() : Long.parseLong(threadIdObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id must be a valid number: " + threadIdObj);
    }

    int frameIndex;
    try {
      frameIndex = frameIndexObj instanceof Number num ? num.intValue() : Integer.parseInt(frameIndexObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "frame_index must be a valid integer: " + frameIndexObj);
    }

    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    if (!thread.isSuspended()) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_SUSPENDED.getCode(),
          "Thread is not suspended: " + thread.name());
    }

    List<StackFrame> frames = thread.frames();
    if (frameIndex < 0 || frameIndex >= frames.size()) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          String.format("Invalid frame index %d (valid range: 0-%d)", frameIndex, frames.size() - 1));
    }

    StackFrame frame = frames.get(frameIndex);

    VariableExtractor extractor = debuggerService.getVariableExtractor();
    List<VariableInfo> variables = extractor.extractVariables(frame);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame_index", frameIndex, "variable_count", variables.size(), "variables",
        variables.stream().map(this::variableToMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'getChildVariables' operation.
   */
  private ToolResponse handleGetChildVariables(Map<String, Object> arguments) throws Exception {
    Object varRefObj = arguments.get("variable_reference");

    if (varRefObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "variable_reference is required for getChildVariables operation");
    }

    int variableReference;
    try {
      variableReference = varRefObj instanceof Number num ? num.intValue() : Integer.parseInt(varRefObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "variable_reference must be a valid integer: " + varRefObj);
    }

    if (variableReference <= 0) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "Invalid variable reference: " + variableReference);
    }

    // Validate reference exists before extracting children
    VariableExtractor extractor = debuggerService.getVariableExtractor();
    if (!extractor.getReferenceManager().isValidReference(variableReference)) {
      return ToolResponse.error(DebuggerErrorCode.VARIABLE_NOT_FOUND.getCode(),
          "Variable reference not found: " + variableReference);
    }

    List<VariableInfo> children = extractor.extractChildVariables(variableReference);

    Map<String, Object> result = Map.of("status", "success", "variable_reference", variableReference, "child_count",
        children.size(), "children", children.stream().map(this::variableToMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'getStaticFields' operation.
   */
  private ToolResponse handleGetStaticFields(Map<String, Object> arguments) throws Exception {
    String className = (String) arguments.get("class_name");

    if (className == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "class_name is required for getStaticFields operation");
    }

    VirtualMachine vm = debuggerService.getVirtualMachine();
    List<ReferenceType> classes = vm.classesByName(className);

    if (classes.isEmpty()) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Class not found: " + className);
    }

    ReferenceType classType = classes.get(0);

    VariableExtractor extractor = debuggerService.getVariableExtractor();
    List<VariableInfo> staticFields = extractor.extractStaticFields(classType);

    Map<String, Object> result = Map.of("status", "success", "class_name", className, "static_field_count",
        staticFields.size(), "static_fields", staticFields.stream().map(this::variableToMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Finds a thread by ID.
   */
  private ThreadReference findThread(long threadId) {
    return debuggerService.getVirtualMachine().allThreads().stream().filter(t -> t.uniqueID() == threadId).findFirst()
        .orElse(null);
  }

  /**
   * Converts VariableInfo to a map for JSON serialization.
   */
  private Map<String, Object> variableToMap(VariableInfo var) {
    Map<String, Object> map = new HashMap<>();
    map.put("name", var.name());
    map.put("type", var.type());
    map.put("value", var.value());
    map.put("variable_reference", var.variableReference());
    map.put("scope", var.scope());
    map.put("expandable", var.isExpandable());
    map.put("primitive", var.isPrimitive());

    return map;
  }
}
