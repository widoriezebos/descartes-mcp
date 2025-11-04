package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.models.StackFrameInfo;
import com.bitsapplied.descartes.debugger.stacktrace.StackTraceInspector;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public DebuggerStackTraceTool(DebuggerService debuggerService) {
    super(debuggerService);
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
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum", List.of("capture", "captureFiltered", "getFrame", "getCurrentFrame"),
                "description", "The stack trace operation to perform"),
            "thread_id", Map.of("type", "integer", "description", "Thread ID (required for all operations)"),
            "max_depth",
            Map.of("type", "integer", "description", "Maximum number of frames to capture (for capture operation)",
                "default", 100),
            "exclude_patterns",
            Map.of("type", "array", "items", Map.of("type", "string"), "description",
                "Package patterns to exclude (for captureFiltered operation)", "default",
                List.of("java.*", "javax.*", "jdk.*", "sun.*")),
            "frame_index",
            Map.of("type", "integer", "description", "Frame index (0 = top of stack, for getFrame operation)")),
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
    case "capture" -> handleCapture(threadId, arguments);
    case "captureFiltered" -> handleCaptureFiltered(threadId, arguments);
    case "getFrame" -> handleGetFrame(threadId, arguments);
    case "getCurrentFrame" -> handleGetCurrentFrame(threadId);
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Unknown operation: " + operation);
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

    StackTraceInspector inspector = debuggerService.getStackTraceInspector();
    List<StackFrameInfo> frames = inspector.captureStackTrace(thread, maxDepth);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame_count", frames.size(), "frames", frames.stream().map(this::frameToMap).toList());

    return ToolResponse.success(objectMapper.writeValueAsString(result));
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

    return ToolResponse.success(objectMapper.writeValueAsString(result));
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
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "frame_index is required for getFrame operation");
    }

    int frameIndex;
    try {
      frameIndex = frameIndexObj instanceof Number num ? num.intValue() : Integer.parseInt(frameIndexObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "frame_index must be a valid integer: " + frameIndexObj);
    }

    if (frameIndex < 0) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "frame_index must be non-negative (got: " + frameIndex + ")");
    }

    StackTraceInspector inspector = debuggerService.getStackTraceInspector();
    StackFrameInfo frame = inspector.getFrame(thread, frameIndex);

    Map<String, Object> result = Map.of("status", "success", "thread_id", threadId, "thread_name", thread.name(),
        "frame", frameToMap(frame));

    return ToolResponse.success(objectMapper.writeValueAsString(result));
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

    return ToolResponse.success(objectMapper.writeValueAsString(result));
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
