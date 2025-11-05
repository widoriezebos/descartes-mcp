package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.models.ThreadInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for thread inspection and management.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>{@code list} - List all threads with filtering options</li>
 * <li>{@code inspect} - Get detailed information about a specific thread</li>
 * <li>{@code suspend} - Suspend a specific thread</li>
 * <li>{@code resume} - Resume a specific thread</li>
 * <li>{@code resumeAll} - Resume all suspended threads</li>
 * </ul>
 */
public class DebuggerThreadsTool extends AbstractDebuggerTool {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public DebuggerThreadsTool(DebuggerService debuggerService, DebuggerExecutor debuggerExecutor) {
    super(debuggerService, debuggerExecutor);
  }

  @Override
  public String getToolName() {
    return "debugger_threads";
  }

  @Override
  public String getToolDescription() {
    return "Thread inspection and management for debugging. Lists threads with filtering by state, "
        + "inspects specific thread details, and controls thread suspension/resumption.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("operation",
            Map.of("type", "string", "enum", List.of("list", "inspect", "suspend", "resume", "resume_all"),
                "description", "The thread operation to perform"),
            "thread_id", Map.of("type", "integer", "description", "Thread ID (for inspect/suspend/resume operations)"),
            "state_filter", Map.of("type", "string", "description", "Filter threads by state (for list operation)"),
            "name_pattern",
            Map.of("type", "string", "description", "Filter threads by name pattern (for list operation)"),
            "suspended_only", Map.of("type", "boolean", "description",
                "Only show suspended threads (for list operation)", "default", false)),
        "required", List.of("operation"));
  }

  @Override
  protected ToolResponse executeInternal(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Operation is required");
    }

    return switch (operation) {
    case "list" -> handleList(arguments);
    case "inspect" -> handleInspect(arguments);
    case "suspend" -> handleSuspend(arguments);
    case "resume" -> handleResume(arguments);
    case "resume_all" -> handleResumeAll();
    default -> ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(), "Unknown operation: " + operation);
    };
  }

  /**
   * Handles the 'list' operation.
   */
  private ToolResponse handleList(Map<String, Object> arguments) throws Exception {
    List<ThreadInfo> threads = debuggerService.getThreads();

    String stateFilter = (String) arguments.get("state_filter");
    String namePattern = (String) arguments.get("name_pattern");
    Object suspendedOnlyObj = arguments.get("suspended_only");
    boolean suspendedOnly = suspendedOnlyObj instanceof Boolean bool && bool;

    if (stateFilter != null) {
      threads = threads.stream().filter(t -> t.state().equalsIgnoreCase(stateFilter)).toList();
    }

    if (namePattern != null) {
      threads = threads.stream().filter(t -> t.name().contains(namePattern)).toList();
    }

    if (suspendedOnly) {
      threads = threads.stream().filter(ThreadInfo::suspended).toList();
    }

    Map<String, Object> result = Map.of("status", "success", "thread_count", threads.size(), "threads",
        threads.stream().map(this::threadInfoToMap).toList());

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Handles the 'inspect' operation.
   */
  private ToolResponse handleInspect(Map<String, Object> arguments) throws Exception {
    Object threadIdObj = arguments.get("thread_id");

    if (threadIdObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id is required for inspect operation");
    }

    long threadId;
    try {
      threadId = threadIdObj instanceof Number num ? num.longValue() : Long.parseLong(threadIdObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id must be a valid number: " + threadIdObj);
    }

    List<ThreadInfo> threads = debuggerService.getThreads();
    ThreadInfo thread = threads.stream().filter(t -> t.id() == threadId).findFirst().orElse(null);

    if (thread == null) {
      return ToolResponse.error(DebuggerErrorCode.THREAD_NOT_FOUND.getCode(), "Thread not found: " + threadId);
    }

    Map<String, Object> result = Map.of("status", "success", "thread", threadInfoToMap(thread));

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Handles the 'suspend' operation.
   */
  private ToolResponse handleSuspend(Map<String, Object> arguments) throws Exception {
    Object threadIdObj = arguments.get("thread_id");

    if (threadIdObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id is required for suspend operation");
    }

    long threadId;
    try {
      threadId = threadIdObj instanceof Number num ? num.longValue() : Long.parseLong(threadIdObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id must be a valid number: " + threadIdObj);
    }

    debuggerService.suspendThread(threadId);

    Map<String, Object> result = Map.of("status", "success", "message", "Thread suspended", "thread_id", threadId);

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Handles the 'resume' operation.
   */
  private ToolResponse handleResume(Map<String, Object> arguments) throws Exception {
    Object threadIdObj = arguments.get("thread_id");

    if (threadIdObj == null) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id is required for resume operation");
    }

    long threadId;
    try {
      threadId = threadIdObj instanceof Number num ? num.longValue() : Long.parseLong(threadIdObj.toString());
    } catch (NumberFormatException e) {
      return ToolResponse.error(DebuggerErrorCode.INVALID_PARAMETERS.getCode(),
          "thread_id must be a valid number: " + threadIdObj);
    }

    debuggerService.resumeThread(threadId);

    Map<String, Object> result = Map.of("status", "success", "message", "Thread resumed", "thread_id", threadId);

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Handles the 'resumeAll' operation.
   */
  private ToolResponse handleResumeAll() throws Exception {
    debuggerService.resumeAll();

    Map<String, Object> result = Map.of("status", "success", "message", "All threads resumed");

    return ToolResponse.success(objectMapper.writeValueAsString(result));
  }

  /**
   * Converts ThreadInfo to a map for JSON serialization.
   */
  private Map<String, Object> threadInfoToMap(ThreadInfo info) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", info.id());
    map.put("name", info.name());
    map.put("state", info.state());
    map.put("suspended", info.suspended());
    map.put("is_virtual", info.isVirtual());

    if (info.suspendedReason() != null) {
      map.put("suspended_reason", info.suspendedReason());
    }

    if (info.suspendedLocation() != null) {
      map.put("suspended_location", Map.of("class", info.suspendedLocation().declaringType().name(), "method",
          info.suspendedLocation().method().name(), "line", info.suspendedLocation().lineNumber()));
    }

    return map;
  }
}
