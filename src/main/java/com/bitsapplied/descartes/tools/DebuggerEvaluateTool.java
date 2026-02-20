package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
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
  private static final Logger logger = LoggerFactory.getLogger(DebuggerEvaluateTool.class);
  private static final Pattern UNRESOLVED_IDENTIFIER_PATTERN =
      Pattern.compile("cannot find symbol\\s+symbol:\\s+variable\\s+([A-Za-z_$][A-Za-z\\d_$]*)", Pattern.MULTILINE);
  private static final String FRAME_VARS_PREFIX = "Frame variables unavailable in JShell context:";

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
    return "Evaluate Java expressions in debugger context using hybrid JDI/Janino/JShell evaluation";
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

      // Detect native top frame — JDI cannot invoke methods on such threads.
      // Always check frame 0 regardless of requested frameIndex because JDI
      // method invocation operates on the thread's current execution point.
      try {
        StackFrame topFrame = frames.get(0);
        Location topLocation = topFrame.location();
        if (topLocation != null && topLocation.method().isNative()) {
          String nativeClass = topLocation.declaringType().name();
          String nativeMethod = topLocation.method().name();
          throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
              String.format("Cannot evaluate: thread '%s' is suspended in native method %s.%s(). "
                  + "JDI cannot invoke methods when the top frame is native. "
                  + "Use debugger_variables to inspect local state, "
                  + "or set a breakpoint at a Java frame and resume.",
                  thread.name(), nativeClass, nativeMethod));
        }
      } catch (DebuggerException e) {
        throw e;
      } catch (Exception e) {
        // If native check fails (e.g., frame invalidated), proceed with evaluation
        logger.debug("Could not check for native frame: {}", e.getMessage());
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

    } catch (DebuggerException e) {
      int code = e.getErrorCode().getCode();
      if (code >= 1400 && code < 1500) {
        return ToolResponse.error(code, e.getMessage(), buildEvaluationFailureDetails(expression, frameIndex, e));
      }
      throw e;
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

  private String buildEvaluationFailureDetails(String expression, int frameIndex, DebuggerException error) {
    Map<String, Object> details = new HashMap<>();
    details.put("expression", expression);
    details.put("frame_index", frameIndex);
    details.put("attempts", resolveAttemptedStrategies());
    details.put("recommended_fallback", "debugger_variables");
    details.put("error_code", error.getErrorCode().getCode());
    details.put("error_category", "evaluation");

    List<String> unresolvedIdentifiers = extractUnresolvedIdentifiers(error.getMessage());
    if (!unresolvedIdentifiers.isEmpty()) {
      details.put("unresolved_identifiers", unresolvedIdentifiers);
    }

    List<String> failedVariableInjections = extractFailedVariableInjections(error.getMessage());
    if (!failedVariableInjections.isEmpty()) {
      details.put("failed_variable_injections", failedVariableInjections);
    }

    try {
      return ToolResponse.OBJECT_MAPPER.writeValueAsString(details);
    } catch (Exception serializationError) {
      return error.getMessage();
    }
  }

  private List<String> resolveAttemptedStrategies() {
    HybridEvaluationProvider evaluator = debuggerService.getEvaluationProvider();
    if (evaluator == null) {
      return List.of("JANINO", "JSHELL");
    }
    return evaluator.getSupportedStrategies().stream().map(Enum::name).toList();
  }

  private List<String> extractUnresolvedIdentifiers(String message) {
    if (message == null || message.isBlank()) {
      return List.of();
    }
    Matcher matcher = UNRESOLVED_IDENTIFIER_PATTERN.matcher(message);
    List<String> identifiers = new java.util.ArrayList<>();
    while (matcher.find()) {
      identifiers.add(matcher.group(1));
    }
    return identifiers;
  }

  private List<String> extractFailedVariableInjections(String message) {
    if (message == null || message.isBlank()) {
      return List.of();
    }
    int marker = message.indexOf(FRAME_VARS_PREFIX);
    if (marker < 0) {
      return List.of();
    }
    String tail = message.substring(marker + FRAME_VARS_PREFIX.length()).trim();
    if (tail.isEmpty()) {
      return List.of();
    }
    String[] tokens = tail.split(",");
    List<String> variables = new java.util.ArrayList<>();
    for (String token : tokens) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        variables.add(trimmed);
      }
    }
    return variables;
  }
}
