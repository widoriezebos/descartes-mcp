package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.debugger.watch.WatchExpressionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Creates a debugger watch tool.
   *
   * @param debuggerService the debugger service
   */
  public DebuggerWatchTool(DebuggerService debuggerService) {
    super(debuggerService);
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
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "description", "Operation to perform", "enum",
                List.of("add", "remove", "removeAll", "list", "enable", "disable", "evaluate")),
            "expression", Map.of("type", "string", "description", "Watch expression (for add operation)"),
            "display_name",
            Map.of("type", "string", "description",
                "Display name for the watch (for add operation, defaults to expression)"),
            "watch_id", Map.of("type", "number", "description", "Watch ID (for remove/enable/disable operations)"),
            "thread_id",
            Map.of("type", "number", "description",
                "Thread ID (for evaluate operation, uses first suspended thread if not specified)"),
            "thread_name",
            Map.of("type", "string", "description", "Thread name (for evaluate operation, alternative to thread_id)"),
            "frame_index", Map.of("type", "number", "description",
                "Stack frame index (for evaluate operation, default 0 = top frame)", "default", 0)),
        "required", List.of("operation"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) {
    String operation = getStringParam(arguments, "operation");
    return switch (operation) {
    case "add" -> handleAdd(arguments);
    case "remove" -> handleRemove(arguments);
    case "removeAll" -> handleRemoveAll();
    case "list" -> handleList();
    case "enable" -> handleEnable(arguments);
    case "disable" -> handleDisable(arguments);
    case "evaluate" -> handleEvaluate(arguments);
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_OPERATION.getCode(), "Unknown operation: " + operation);
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

    Map<String, Object> metadata = Map.of("watch_id", watchId, "expression", expression, "display_name",
        displayName != null ? displayName : expression);

    String content = String.format("Watch expression added: ID=%d, Expression='%s'", watchId, expression);

    return ToolResponse.success(content, metadata);
  }

  /**
   * Handles removing a watch expression.
   */
  private ToolResponse handleRemove(Map<String, Object> arguments) {
    long watchId = getIntParam(arguments, "watch_id");

    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.removeWatch(watchId);

    String content = String.format("Watch expression removed: ID=%d", watchId);
    return ToolResponse.success(content);
  }

  /**
   * Handles removing all watch expressions.
   */
  private ToolResponse handleRemoveAll() {
    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.removeAllWatches();

    return ToolResponse.success("All watch expressions removed");
  }

  /**
   * Handles listing all watch expressions.
   */
  private ToolResponse handleList() {
    try {
      WatchExpressionManager watchManager = debuggerService.getWatchManager();
      List<Map<String, Object>> watches = watchManager.listWatches();

      Map<String, Object> result = Map.of("status", "success", "watch_count", watches.size(), "watches", watches);

      return ToolResponse.success(objectMapper.writeValueAsString(result));

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

    String content = String.format("Watch expression enabled: ID=%d", watchId);
    return ToolResponse.success(content);
  }

  /**
   * Handles disabling a watch expression.
   */
  private ToolResponse handleDisable(Map<String, Object> arguments) {
    long watchId = getIntParam(arguments, "watch_id");

    WatchExpressionManager watchManager = debuggerService.getWatchManager();
    watchManager.disableWatch(watchId);

    String content = String.format("Watch expression disabled: ID=%d", watchId);
    return ToolResponse.success(content);
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

      return ToolResponse.success(objectMapper.writeValueAsString(response));

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
    // Try thread_id first
    if (arguments.containsKey("thread_id")) {
      long threadId = getIntParam(arguments, "thread_id");
      ThreadReference thread = debuggerService.getThreadById(threadId);
      if (thread == null) {
        throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread not found with ID: " + threadId);
      }
      return thread;
    }

    // Try thread_name
    if (arguments.containsKey("thread_name")) {
      String threadName = getStringParam(arguments, "thread_name");
      ThreadReference thread = debuggerService.getThreadByName(threadName);
      if (thread == null) {
        throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread not found with name: " + threadName);
      }
      return thread;
    }

    // Find first suspended thread
    ThreadReference suspendedThread = debuggerService.getThreads().stream().filter(threadInfo -> threadInfo.suspended())
        .map(threadInfo -> debuggerService.getThreadById(threadInfo.id())).filter(t -> t != null).findFirst()
        .orElse(null);

    if (suspendedThread == null) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
          "No suspended thread found. Use thread_id or thread_name to specify a suspended thread.");
    }

    return suspendedThread;
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
