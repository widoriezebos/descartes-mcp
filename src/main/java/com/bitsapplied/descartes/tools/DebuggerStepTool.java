package com.bitsapplied.descartes.tools;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.stepping.SteppingController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jdi.ThreadReference;

/**
 * MCP tool for stepping operations during debugging.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@code stepOver} - Execute the next line in the current method</li>
 * <li>{@code stepInto} - Step into method calls</li>
 * <li>{@code stepOut} - Step out of the current method to its caller</li>
 * </ul>
 *
 * <p>
 * All stepping operations require the thread to be suspended.
 */
public class DebuggerStepTool extends AbstractDebuggerTool {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public DebuggerStepTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_step";
  }

  @Override
  public String getToolDescription() {
    return "Controls stepping operations during debugging. Supports step over (next line), "
        + "step into (enter methods), and step out (exit current method). Thread must be "
        + "suspended to perform step operations.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum", List.of("step_over", "step_into", "step_out"), "description",
                "The stepping operation to perform"),
            "thread_id", Map.of("type", "integer", "description", "Thread ID to step (required)")),
        "required", List.of("operation", "thread_id"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");
    Object threadIdObj = arguments.get("thread_id");

    if (operation == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Operation is required");
    }

    if (threadIdObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "thread_id is required");
    }

    long threadId;
    try {
      threadId = threadIdObj instanceof Number num ? num.longValue() : Long.parseLong(threadIdObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id must be a valid number: " + threadIdObj);
    }

    return switch (operation) {
    case "step_over" -> handleStepOver(threadId);
    case "step_into" -> handleStepInto(threadId);
    case "step_out" -> handleStepOut(threadId);
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Unknown operation: " + operation);
    };
  }

  /**
   * Handles the 'stepOver' operation.
   */
  private ToolResponse handleStepOver(long threadId) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    SteppingController steppingController = debuggerService.getSteppingController();
    steppingController.stepOver(thread);

    Map<String, Object> result = Map.of("status", "success", "message", "Step over initiated", "thread_id", threadId,
        "thread_name", thread.name());

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Handles the 'stepInto' operation.
   */
  private ToolResponse handleStepInto(long threadId) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    SteppingController steppingController = debuggerService.getSteppingController();
    steppingController.stepInto(thread);

    Map<String, Object> result = Map.of("status", "success", "message", "Step into initiated", "thread_id", threadId,
        "thread_name", thread.name());

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Handles the 'stepOut' operation.
   */
  private ToolResponse handleStepOut(long threadId) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    SteppingController steppingController = debuggerService.getSteppingController();
    steppingController.stepOut(thread);

    Map<String, Object> result = Map.of("status", "success", "message", "Step out initiated", "thread_id", threadId,
        "thread_name", thread.name());

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Finds a thread by ID.
   */
  private ThreadReference findThread(long threadId) {
    return debuggerService.getVirtualMachine().allThreads().stream().filter(t -> t.uniqueID() == threadId).findFirst()
        .orElse(null);
  }
}
