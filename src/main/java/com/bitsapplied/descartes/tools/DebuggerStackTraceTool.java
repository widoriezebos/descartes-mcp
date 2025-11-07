package com.bitsapplied.descartes.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.models.StackFrameInfo;
import com.bitsapplied.descartes.debugger.stacktrace.StackTraceInspector;
import com.sun.jdi.ThreadReference;

/**
 * MCP tool for stack trace inspection.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@code capture} - Capture full stack trace from a suspended thread</li>
 * <li>{@code captureFiltered} - Capture filtered stack trace excluding certain
 * packages</li>
 * <li>{@code getFrame} - Get a specific stack frame by index</li>
 * <li>{@code getCurrentFrame} - Get the current (top) stack frame</li>
 * </ul>
 *
 * <p>
 * All operations require the thread to be suspended.
 */
public class DebuggerStackTraceTool extends AbstractDebuggerTool {

  public DebuggerStackTraceTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_stacktrace";
  }

  @Override
  public String getToolDescription() {
    return "Stack trace inspection for suspended threads. Captures call stacks, supports filtering "
        + "by package patterns, and provides detailed frame information including source locations. "
        + "Thread must be suspended to capture stack traces.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("operation",
        Map.of("type", "string", "enum", List.of("capture", "capture_filtered", "get_frame", "get_current_frame"),
            "description", "Stack trace operation to perform"));
    properties.put("thread_id",
        Map.of("type", "integer", "description", "Thread ID from debugger_threads/list (required for all operations)"));
    properties.put("max_depth", Map.of("type", "integer", "minimum", 1, "maximum", 500, "description",
        "Maximum number of frames to capture (capture operations only)", "default", 100));
    properties.put("exclude_patterns", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Package glob patterns to exclude from capture_filtered (default filters out JDK packages)"));
    properties.put("frame_index", Map.of("type", "integer", "minimum", 0, "description",
        "Frame index (0 = top of stack, required for get_frame)"));

    List<Map<String, Object>> operationRequirements = new ArrayList<>();
    operationRequirements.add(
        Map.of("if", Map.of("properties", Map.of("operation", Map.of("enum", List.of("capture", "capture_filtered"))),
            "required", List.of("operation")), "then", Map.of("required", List.of("thread_id"))));
    operationRequirements.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "get_frame")), "required", List.of("operation")),
        "then", Map.of("required", List.of("thread_id", "frame_index"))));
    operationRequirements
        .add(Map.of("if", Map.of("properties", Map.of("operation", Map.of("const", "get_current_frame")), "required",
            List.of("operation")), "then", Map.of("required", List.of("thread_id"))));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("allOf", operationRequirements);
    schema.put("description",
        "Capture stack traces for suspended threads. Requires an active debugger session and suspended thread.");
    return schema;
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");
    Object threadIdObj = arguments.get("thread_id");

    if (operation == null) {
      return ToolResponse.missingParameter("operation");
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

    return switch (operation) {
    case "capture" -> handleCapture(threadId, arguments);
    case "capture_filtered" -> handleCaptureFiltered(threadId, arguments);
    case "get_frame" -> handleGetFrame(threadId, arguments);
    case "get_current_frame" -> handleGetCurrentFrame(threadId);
    default -> ToolResponse.unsupportedOperation(operation, "capture, capture_filtered, get_frame, get_current_frame");
    };
  }

  /**
   * Handles the 'capture' operation.
   */
  private ToolResponse handleCapture(long threadId, Map<String, Object> arguments) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    Object maxDepthObj = arguments.get("max_depth");
    int maxDepth = maxDepthObj instanceof Number num ? num.intValue() : 100;
    if (maxDepth <= 0) {
      return ToolResponse.invalidParameter("max_depth", " must be a positive integer");
    }

    StackTraceInspector inspector = debuggerService.getStackTraceInspector();
    List<StackFrameInfo> frames = inspector.captureStackTrace(thread, maxDepth);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame_count", frames.size(), "frames", frames.stream().map(this::frameToMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'captureFiltered' operation.
   */
  private ToolResponse handleCaptureFiltered(long threadId, Map<String, Object> arguments) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    Object excludePatternsObj = arguments.get("exclude_patterns");
    String[] excludePatterns;

    if (excludePatternsObj instanceof List<?> list) {
      excludePatterns = list.stream().map(Object::toString).toArray(String[]::new);
    } else {
      excludePatterns = new String[] { "java.*", "javax.*", "jdk.*", "sun.*" };
    }

    StackTraceInspector inspector = debuggerService.getStackTraceInspector();
    List<StackFrameInfo> frames = inspector.captureFilteredStackTrace(thread, excludePatterns);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame_count", frames.size(), "exclude_patterns", List.of(excludePatterns), "frames",
        frames.stream().map(this::frameToMap).toList());

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'getFrame' operation.
   */
  private ToolResponse handleGetFrame(long threadId, Map<String, Object> arguments) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    Object frameIndexObj = arguments.get("frame_index");
    if (frameIndexObj == null) {
      return ToolResponse.missingParameter("frame_index");
    }

    int frameIndex;
    try {
      frameIndex = frameIndexObj instanceof Number num ? num.intValue() : Integer.parseInt(frameIndexObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.invalidParameter("frame_index", " must be a valid integer");
    }

    if (frameIndex < 0) {
      return ToolResponse.invalidParameter("frame_index", " must be non-negative (got " + frameIndex + ")");
    }

    StackTraceInspector inspector = debuggerService.getStackTraceInspector();
    StackFrameInfo frame = inspector.getFrame(thread, frameIndex);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame", frameToMap(frame));

    return ToolResponse.successJson(result);
  }

  /**
   * Handles the 'getCurrentFrame' operation.
   */
  private ToolResponse handleGetCurrentFrame(long threadId) throws Exception {
    ThreadReference thread = findThread(threadId);
    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    StackTraceInspector inspector = debuggerService.getStackTraceInspector();
    StackFrameInfo frame = inspector.getCurrentFrame(thread);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame", frameToMap(frame));

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
   * Converts StackFrameInfo to a map for JSON serialization.
   */
  private Map<String, Object> frameToMap(StackFrameInfo frame) {
    Map<String, Object> map = new HashMap<>();
    map.put("frame_id", frame.frameId());
    map.put("method_name", frame.methodName());
    map.put("class_name", frame.className());
    map.put("line_number", frame.lineNumber());
    map.put("is_native", frame.isNative());

    if (frame.fileName() != null) {
      map.put("file_name", frame.fileName());
    }

    map.put("has_source_location", frame.hasSourceLocation());
    map.put("full_method_name", frame.getFullMethodName());

    return map;
  }
}
