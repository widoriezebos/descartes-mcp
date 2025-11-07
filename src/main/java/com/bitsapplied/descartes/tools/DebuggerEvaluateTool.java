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
    Map<String, Object> properties = new HashMap<>();
    properties.put("operation",
        Map.of("type", "string", "description", "Operation to perform", "enum", List.of("evaluate")));
    properties.put("thread_id", Map.of("type", "integer", "description",
        "Thread ID (from debugger_threads/list). Provide either thread_id or thread_name."));
    properties.put("thread_name", Map.of("type", "string", "description", "Thread name (alternative to thread_id)"));
    properties.put("frame_index",
        Map.of("type", "integer", "minimum", 0, "description", "Stack frame index (0 = top frame)", "default", 0));
    properties.put("expression", Map.of("type", "string", "description", "Java expression to evaluate"));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation", "expression"));
    schema.put("anyOf", List.of(Map.of("required", List.of("thread_id")), Map.of("required", List.of("thread_name"))));
    schema.put("description",
        "Evaluate Java expressions in the context of a suspended debugger thread. Requires active debugger session.");
    return schema;
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) {
    String operation = getStringParam(arguments, "operation");
    return switch (operation) {
    case "evaluate" -> handleEvaluate(arguments);
    default -> ToolResponse.unsupportedOperation(operation, "evaluate");
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
      Map<String, Object> response = new HashMap<>();
      response.put("status", "success");
      response.put("thread_id", thread.uniqueID());
      response.put("thread_name", thread.name());
      response.put("frame_index", frameIndex);
      response.put("expression", expression);
      response.put("result", result.value());
      response.put("strategy", result.strategy().name());
      response.put("duration_ms", result.durationMs());

      return ToolResponse.successJson(response);

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
