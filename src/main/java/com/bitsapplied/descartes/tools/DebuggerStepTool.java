package com.bitsapplied.descartes.tools;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.stepping.SteppingController;
import com.bitsapplied.descartes.debugger.sync.DebuggerSyncCoordinator;
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

  private static final long DEFAULT_STEP_TIMEOUT_MS = 10_000L;
  private static final long MIN_STEP_TIMEOUT_MS = 100L;
  private static final long MAX_STEP_TIMEOUT_MS = 60_000L;

  private enum StepOperation {
    STEP_OVER("step_over") {
      @Override
      void invoke(SteppingController controller, ThreadReference thread) {
        controller.stepOver(thread);
      }
    },
    STEP_INTO("step_into") {
      @Override
      void invoke(SteppingController controller, ThreadReference thread) {
        controller.stepInto(thread);
      }
    },
    STEP_OUT("step_out") {
      @Override
      void invoke(SteppingController controller, ThreadReference thread) {
        controller.stepOut(thread);
      }
    };

    private final String schemaValue;

    StepOperation(String schemaValue) {
      this.schemaValue = schemaValue;
    }

    String schemaValue() {
      return schemaValue;
    }

    abstract void invoke(SteppingController controller, ThreadReference thread);

    static StepOperation fromSchemaValue(String value) {
      return Arrays.stream(values()).filter(op -> op.schemaValue.equals(value)).findFirst().orElse(null);
    }

    static String supportedOperations() {
      return String.join(", ", Arrays.stream(values()).map(StepOperation::schemaValue).toList());
    }
  }

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
        + "suspended to perform step operations. The tool blocks until the debugger reports the "
        + "new execution location and returns the resolved source position.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("operation", Map.of("type", "string", "enum", List.of("step_over", "step_into", "step_out"),
        "description", "Stepping operation to perform"));
    properties.put("thread_id",
        Map.of("type", "integer", "description", "Thread ID to step (must be suspended before stepping)"));

    Map<String, Object> timeoutSchema = new HashMap<>();
    timeoutSchema.put("type", "integer");
    timeoutSchema.put("minimum", MIN_STEP_TIMEOUT_MS);
    timeoutSchema.put("maximum", MAX_STEP_TIMEOUT_MS);
    timeoutSchema.put("default", DEFAULT_STEP_TIMEOUT_MS);
    timeoutSchema.put("description",
        "Optional timeout in milliseconds to wait for the debugger to report step completion. " + "Defaults to "
            + DEFAULT_STEP_TIMEOUT_MS + " ms and clamps at " + MAX_STEP_TIMEOUT_MS + " ms.");
    properties.put("timeout_ms", timeoutSchema);

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation", "thread_id"));
    schema.put("description",
        "Control execution flow for a suspended thread. Requires active debugger session and suspended thread. "
            + "The tool waits for the new location and returns the resolved source position.");
    return schema;
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operationValue = (String) arguments.get("operation");
    Object threadIdObj = arguments.get("thread_id");

    if (operationValue == null || operationValue.isBlank()) {
      return ToolResponse.missingParameter("operation");
    }

    StepOperation operation = StepOperation.fromSchemaValue(operationValue);
    if (operation == null) {
      return ToolResponse.unsupportedOperation(operationValue, StepOperation.supportedOperations());
    }

    if (threadIdObj == null) {
      return ToolResponse.missingParameter("thread_id");
    }

    long threadId;
    try {
      threadId = threadIdObj instanceof Number num ? num.longValue() : Long.parseLong(threadIdObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("thread_id", " must be a valid integer");
    }

    return performStep(operation, threadId, arguments);
  }

  private ToolResponse performStep(StepOperation operation, long threadId, Map<String, Object> arguments)
      throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    Duration timeout;
    try {
      timeout = resolveTimeout(arguments);
    } catch (IllegalArgumentException ex) {
      return ToolResponse.invalidParameter("timeout_ms", ex.getMessage());
    }

    DebuggerSyncCoordinator coordinator = debuggerService.getSyncCoordinator();
    if (coordinator == null) {
      return ToolResponse.error(DebuggerErrorCode.SESSION_NOT_ACTIVE.getCode(),
          "Debugger session is not active; unable to await step completion");
    }

    SteppingController steppingController = debuggerService.getSteppingController();
    long startNanos = System.nanoTime();
    CompletableFuture<DebuggerSyncCoordinator.StepResult> stepFuture = coordinator
        .awaitStepCompletion(thread.uniqueID(), timeout);

    try {
      operation.invoke(steppingController, thread);
    } catch (Exception ex) {
      stepFuture.cancel(true);
      throw ex;
    }

    try {
      DebuggerSyncCoordinator.StepResult result = stepFuture.join();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("status", "success");
      response.put("message", "Step operation completed");
      response.put("operation", operation.schemaValue());
      response.put("thread_id", result.threadId());
      response.put("thread_name", result.threadName());
      response.put("duration_ms", durationMs);
      response.put("timeout_ms", timeout.toMillis());
      response.put("completed_at", result.receivedAt());
      response.put("location", result.locationMap());
      response.put("event_payload", result.payload());

      return ToolResponse.successJson(response);
    } catch (CancellationException ex) {
      return ToolResponse.error(DebuggerErrorCode.OPERATION_TIMEOUT.getCode(),
          "Step operation was cancelled (debugger session closed)");
    } catch (CompletionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof TimeoutException) {
        return ToolResponse.error(DebuggerErrorCode.OPERATION_TIMEOUT.getCode(),
            "Step operation timed out after " + timeout.toMillis() + " ms without receiving debugger.step_complete");
      }
      if (cause instanceof CancellationException cancellation) {
        // Coordinator closes futures with completeExceptionally, which shows up here
        // as CompletionException wrapping that CancellationException.
        return ToolResponse.error(DebuggerErrorCode.OPERATION_TIMEOUT.getCode(),
            "Step operation cancelled: " + cancellation.getMessage());
      }
      String message = cause != null ? cause.getMessage() : ex.getMessage();
      return ToolResponse.error(DebuggerErrorCode.INTERNAL_ERROR.getCode(),
          "Failed to await step completion: " + message);
    }
  }

  private Duration resolveTimeout(Map<String, Object> arguments) {
    Object timeoutObj = arguments.get("timeout_ms");
    if (timeoutObj == null) {
      return Duration.ofMillis(DEFAULT_STEP_TIMEOUT_MS);
    }

    long timeoutMs;
    if (timeoutObj instanceof Number num) {
      timeoutMs = num.longValue();
    } else {
      try {
        timeoutMs = Long.parseLong(timeoutObj.toString());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("must be a numeric value (received: " + timeoutObj + ")");
      }
    }

    if (timeoutMs < MIN_STEP_TIMEOUT_MS) {
      throw new IllegalArgumentException("must be at least " + MIN_STEP_TIMEOUT_MS + " ms");
    }

    timeoutMs = Math.min(timeoutMs, MAX_STEP_TIMEOUT_MS);
    return Duration.ofMillis(timeoutMs);
  }

  /**
   * Finds a thread by ID.
   */
  private ThreadReference findThread(long threadId) {
    return debuggerService.getVirtualMachine().allThreads().stream().filter(t -> t.uniqueID() == threadId).findFirst()
        .orElse(null);
  }
}
