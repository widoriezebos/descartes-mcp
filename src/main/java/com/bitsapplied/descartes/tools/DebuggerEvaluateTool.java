package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

/**
 * MCP tool for evaluating expressions in debugger context.
 *
 * <p>
 * This tool enables expression evaluation using a hybrid approach:
 * <ul>
 * <li>Janino for simple expressions (fast, lightweight)</li>
 * <li>JShell for complex expressions (lambdas, method references)</li>
 * </ul>
 *
 * <p>
 * <b>Operations:</b>
 * <ul>
 * <li>evaluate - Evaluate expression in frame context</li>
 * </ul>
 *
 * <p>
 * <b>Security Warning:</b> Expression evaluation can execute arbitrary code in
 * the debuggee JVM. Only use in trusted development environments.
 */
public class DebuggerEvaluateTool extends AbstractDebuggerTool {

  /**
   * Creates a debugger evaluate tool.
   *
   * @param debuggerService  the debugger service
   * @param debuggerExecutor the debugger executor
   */
  public DebuggerEvaluateTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_evaluate";
  }

  @Override
  public String getToolDescription() {
    return "Evaluate Java expressions in debugger context using hybrid Janino/JShell evaluation";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "description", "Operation to perform", "enum", List.of("evaluate")), "thread_id",
            Map.of("type", "number", "description", "Thread ID (use either thread_id or thread_name)"), "thread_name",
            Map.of("type", "string", "description", "Thread name (use either thread_id or thread_name)"), "frame_index",
            Map.of("type", "number", "description", "Stack frame index (0 = top frame)", "default", 0), "expression",
            Map.of("type", "string", "description", "Java expression to evaluate")),
        "required", List.of("operation", "expression"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) {
    String operation = getStringParam(arguments, "operation");
    return switch (operation) {
    case "evaluate" -> handleEvaluate(arguments);
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_OPERATION.getCode(), "Unknown operation: " + operation,
        "Supported operations: evaluate");
    };
  }

  // ========== Operation Handlers ==========

  /**
   * Handles expression evaluation.
   */
  private ToolResponse handleEvaluate(Map<String, Object> arguments) {
    String expression = getStringParam(arguments, "expression");
    int frameIndex = getIntParamWithDefault(arguments, "frame_index", 0);

    // Get thread reference
    ThreadReference thread = resolveThread(arguments);

    try {
      // Check if thread is still alive (not garbage collected or terminated)
      try {
        int status = thread.status();
        if (status == ThreadReference.THREAD_STATUS_ZOMBIE) {
          throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread has exited: " + thread.name());
        }
      } catch (ObjectCollectedException e) {
        throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND,
            "Thread has been garbage collected: " + thread.name());
      }

      // Validate thread is suspended
      if (!thread.isSuspended()) {
        throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
            "Thread must be suspended to evaluate expressions: " + thread.name());
      }

      // Get stack frame
      List<StackFrame> frames = thread.frames();
      if (frameIndex < 0 || frameIndex >= frames.size()) {
        throw new DebuggerException(DebuggerErrorCode.INVALID_FRAME,
            String.format("Invalid frame index %d (thread has %d frames)", frameIndex, frames.size()));
      }

      StackFrame frame = frames.get(frameIndex);

      // Evaluate expression
      HybridEvaluationProvider evaluator = debuggerService.getEvaluationProvider();
      HybridEvaluationProvider.EvaluationResult result = evaluator.evaluate(expression, frame);

      // Build response
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("thread_id", thread.uniqueID());
      metadata.put("thread_name", thread.name());
      metadata.put("frame_index", frameIndex);
      metadata.put("expression", expression);
      metadata.put("strategy", result.strategy().name());
      metadata.put("duration_ms", result.durationMs());

      String content = String.format("Expression: %s%nResult: %s%nEvaluation Strategy: %s%nDuration: %.2f ms",
          expression, result.value(), result.strategy(), result.durationMs());

      return ToolResponse.success(content, metadata);

    } catch (IncompatibleThreadStateException e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
          "Cannot access thread frames: " + e.getMessage(), e);
    }
  }

  // ========== Helper Methods ==========

  /**
   * Resolves thread from thread_id or thread_name parameter.
   */
  private ThreadReference resolveThread(Map<String, Object> arguments) {
    // Try thread_id first
    if (arguments.containsKey("thread_id")) {
      long threadId = getLongParam(arguments, "thread_id");
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

    throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS,
        "Either thread_id or thread_name must be provided");
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
  private int getIntParamWithDefault(Map<String, Object> arguments, String name, int defaultValue) {
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
   * Gets a long integer parameter from arguments (required).
   */
  private long getLongParam(Map<String, Object> arguments, String name) {
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
