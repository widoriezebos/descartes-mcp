package com.bitsapplied.descartes.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.debugger.watch.WatchExpressionManager;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

/**
 * MCP tool for managing watch expressions.
 *
 * <p>
 * Watch expressions are automatically evaluated when execution suspends,
 * allowing tracking of variable values and expressions over time.
 *
 * <p>
 * <b>Operations:</b>
 * <ul>
 * <li>add - Add a watch expression</li>
 * <li>remove - Remove a watch by ID</li>
 * <li>removeAll - Remove all watches</li>
 * <li>list - List all watches</li>
 * <li>enable - Enable a watch</li>
 * <li>disable - Disable a watch</li>
 * <li>evaluate - Evaluate all watches in current context</li>
 * </ul>
 */
public class DebuggerWatchTool extends AbstractDebuggerTool {

  /**
   * Creates a debugger watch tool.
   *
   * @param debuggerService  the debugger service
   * @param debuggerExecutor the debugger executor
   */
  public DebuggerWatchTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_watch";
  }

  @Override
  public String getToolDescription() {
    return "Manage watch expressions for debugging. Watches are automatically evaluated when "
        + "execution suspends, allowing tracking of variable values over time.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("operation", Map.of("type", "string", "description", "Operation to perform", "enum",
        List.of("add", "remove", "remove_all", "list", "enable", "disable", "evaluate")));
    properties.put("expression",
        Map.of("type", "string", "description", "Watch expression to register (required for add)"));
    properties.put("display_name",
        Map.of("type", "string", "description", "Friendly display name for the watch (defaults to expression)"));
    properties.put("watch_id",
        Map.of("type", "integer", "minimum", 1, "description", "Watch identifier from add/list"));
    properties.put("thread_id", Map.of("type", "integer", "minimum", 1, "description",
        "Thread ID for evaluate operation (must refer to a suspended thread)"));
    properties.put("thread_name",
        Map.of("type", "string", "description", "Thread name for evaluate operation (alternative to thread_id)"));
    properties.put("frame_index",
        Map.of("type", "integer", "minimum", 0, "description", "Stack frame index for evaluation", "default", 0));

    List<Map<String, Object>> constraints = new ArrayList<>();
    constraints.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "add")), "required", List.of("operation")), "then",
        Map.of("required", List.of("expression"))));
    constraints.add(
        Map.of("if", Map.of("properties", Map.of("operation", Map.of("enum", List.of("remove", "enable", "disable"))),
            "required", List.of("operation")), "then", Map.of("required", List.of("watch_id"))));
    constraints.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "evaluate")), "required", List.of("operation")),
        "then", Map.of("anyOf",
            List.of(Map.of("required", List.of("thread_id")), Map.of("required", List.of("thread_name"))))));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("allOf", constraints);
    schema.put("description",
        "Manage watch expressions that auto-evaluate when execution is suspended. Requires active debugger session.");
    return schema;
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) {
    String operation = getStringParam(arguments, "operation");
    return switch (operation) {
    case "add" -> handleAdd(arguments);
    case "remove" -> handleRemove(arguments);
    case "remove_all" -> handleRemoveAll();
    case "list" -> handleList();
    case "enable" -> handleEnable(arguments);
    case "disable" -> handleDisable(arguments);
    case "evaluate" -> handleEvaluate(arguments);
    default -> ToolResponse.unsupportedOperation(operation, "add, remove, remove_all, list, enable, disable, evaluate");
    };
  }

  // ========== Operation Handlers ==========

  /**
   * Handles adding a watch expression.
   */
  private ToolResponse handleAdd(Map<String, Object> arguments) {
    String expression = getStringParam(arguments, "expression");
    String displayName = (String) arguments.get("display_name");

    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    long watchId = watchManager.addWatch(expression, displayName);

    Map<String, Object> result = new HashMap<>();
    result.put("action", "add");
    result.put("watch_id", watchId);
    result.put("expression", expression);
    result.put("display_name", displayName != null ? displayName : expression);
    result.put("message", "Watch expression added");

    return ToolResponse.successJson(result);
  }

  /**
   * Handles removing a watch expression.
   */
  private ToolResponse handleRemove(Map<String, Object> arguments) {
    long watchId = getIntParam(arguments, "watch_id");

    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.removeWatch(watchId);

    Map<String, Object> result = new HashMap<>();
    result.put("action", "remove");
    result.put("watch_id", watchId);
    result.put("message", "Watch expression removed");
    return ToolResponse.successJson(result);
  }

  /**
   * Handles removing all watch expressions.
   */
  private ToolResponse handleRemoveAll() {
    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.removeAllWatches();

    return ToolResponse.successJson(
        Map.of("status", "success", "action", "remove_all", "message", "All watch expressions removed"));
  }

  /**
   * Handles listing all watch expressions.
   */
  private ToolResponse handleList() {
    try {
      WatchExpressionManager watchManager = debuggerService.getWatchManager();
      List<Map<String, Object>> watches = watchManager.listWatches();

      Map<String, Object> result = Map.of("status", "success", "watch_count", watches.size(), "watches", watches);

      return ToolResponse.successJson(result);

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Failed to list watches: " + e.getMessage(), e);
    }
  }

  /**
   * Handles enabling a watch expression.
   */
  private ToolResponse handleEnable(Map<String, Object> arguments) {
    long watchId = getIntParam(arguments, "watch_id");

    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.enableWatch(watchId);

    return ToolResponse
        .successJson(Map.of("status", "success", "action", "enable", "watch_id", watchId, "message",
            "Watch expression enabled"));
  }

  /**
   * Handles disabling a watch expression.
   */
  private ToolResponse handleDisable(Map<String, Object> arguments) {
    long watchId = getIntParam(arguments, "watch_id");

    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.disableWatch(watchId);

    return ToolResponse
        .successJson(Map.of("status", "success", "action", "disable", "watch_id", watchId, "message",
            "Watch expression disabled"));
  }

  /**
   * Handles evaluating all watch expressions.
   */
  private ToolResponse handleEvaluate(Map<String, Object> arguments) {
    try {
      // Resolve thread
      ThreadReference thread = resolveThread(arguments);

      int frameIndex = getIntParam(arguments, "frame_index", 0);

      // Validate thread is suspended
      if (!thread.isSuspended()) {
        throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
            "Thread must be suspended to evaluate watches: " + thread.name());
      }

      // Get stack frame
      if (thread.frameCount() == 0) {
        throw new DebuggerException(DebuggerErrorCode.INVALID_FRAME, "Thread has no stack frames");
      }

      if (frameIndex < 0 || frameIndex >= thread.frameCount()) {
        throw new DebuggerException(DebuggerErrorCode.INVALID_FRAME,
            String.format("Invalid frame index %d (thread has %d frames)", frameIndex, thread.frameCount()));
      }

      StackFrame frame = thread.frame(frameIndex);

      // Evaluate watches
      WatchExpressionManager watchManager = debuggerService.getWatchManager();
      List<WatchExpressionManager.WatchResult> results = watchManager.evaluateAll(frame);

      // Format results
      Map<String, Object> response = Map.of("status", "success", "thread_id", thread.uniqueID(), "thread_name",
          thread.name(), "frame_index", frameIndex, "watch_count", results.size(), "results",
          results.stream().map(this::watchResultToMap).toList());

      return ToolResponse.successJson(response);

    } catch (IncompatibleThreadStateException e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
          "Cannot access thread frames: " + e.getMessage(), e);
    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.INTERNAL_ERROR, "Failed to evaluate watches: " + e.getMessage(), e);
    }
  }

  // ========== Helper Methods ==========

  /**
   * Resolves thread from thread_id or thread_name parameter. If neither is
   * specified, uses first suspended thread.
   */
  private ThreadReference resolveThread(Map<String, Object> arguments) {
    boolean hasThreadId = arguments.containsKey("thread_id");
    boolean hasThreadName = arguments.containsKey("thread_name");

    if (!hasThreadId && !hasThreadName) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS,
          "thread_id or thread_name is required for evaluate operation");
    }

    if (hasThreadId) {
      long threadId = getIntParam(arguments, "thread_id");
      ThreadReference thread = debuggerService.getThreadById(threadId);
      if (thread == null) {
        throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread not found with ID: " + threadId);
      }
      return thread;
    }

    String threadName = getStringParam(arguments, "thread_name");
    ThreadReference thread = debuggerService.getThreadByName(threadName);
    if (thread == null) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread not found with name: " + threadName);
    }
    return thread;
  }

  /**
   * Converts WatchResult to a map.
   */
  private Map<String, Object> watchResultToMap(WatchExpressionManager.WatchResult result) {
    Map<String, Object> map = new HashMap<>();
    map.put("watch_id", result.watchId());
    map.put("expression", result.expression());
    map.put("display_name", result.displayName());
    map.put("value", result.value());
    map.put("value_changed", result.valueChanged());
    map.put("strategy", result.strategy());
    map.put("duration_ms", result.durationMs());
    map.put("success", result.isSuccess());
    if (result.error() != null) {
      map.put("error", result.error());
    }
    return map;
  }

  /**
   * Gets a string parameter from arguments.
   */
  private String getStringParam(Map<String, Object> arguments, String name) {
    Object value = arguments.get(name);
    if (value == null) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, "Required parameter missing: " + name);
    }
    return value.toString();
  }

  /**
   * Gets an integer parameter from arguments with default value.
   */
  private int getIntParam(Map<String, Object> arguments, String name, int defaultValue) {
    Object value = arguments.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number num) {
      return num.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS,
          "Parameter '" + name + "' must be a number, but got: " + value);
    }
  }

  /**
   * Gets an integer parameter from arguments (required).
   */
  private long getIntParam(Map<String, Object> arguments, String name) {
    Object value = arguments.get(name);
    if (value == null) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, "Required parameter missing: " + name);
    }
    if (value instanceof Number num) {
      return num.longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS,
          "Parameter '" + name + "' must be a number, but got: " + value);
    }
  }
}
