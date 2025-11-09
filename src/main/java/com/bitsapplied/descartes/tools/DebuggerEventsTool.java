package com.bitsapplied.descartes.tools;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.DebuggerEventQueue;
import com.bitsapplied.descartes.util.DebuggerEventQueue.EventRecord;
import com.bitsapplied.descartes.util.DebuggerEventQueue.Filter;
import com.bitsapplied.descartes.util.DebuggerEventQueues;
import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Tool that exposes debugger event polling support for MCP clients. Since MCP
 * has no server-to-client callbacks, clients can use this tool to wait for or
 * fetch breakpoint/step notifications that were buffered by the server.
 */
public class DebuggerEventsTool implements MCPTool {

  private final Map<String, Object> context;

  public DebuggerEventsTool(Map<String, Object> context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  @Override
  public String getToolName() {
    return "debugger_events";
  }

  @Override
  public String getToolDescription() {
    return "Polls buffered debugger notifications. Supports waiting for the next event or fetching queued events.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("operation", Map.of("type", "string", "enum", List.of("wait", "fetch", "clear")));
    properties.put("types", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Optional list of event types to match (e.g. debugger.breakpoint_hit)"));
    properties.put("thread_id",
        Map.of("type", "integer", "description", "Optional thread id filter (matches payload thread_id)"));
    properties.put("timeout_ms", Map.of("type", "integer", "minimum", 0, "description",
        "Timeout in milliseconds when waiting for an event (default 30000)"));
    properties.put("max_events",
        Map.of("type", "integer", "minimum", 1, "maximum", 100, "description", "Max number of events to fetch"));

    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("description",
        "Utility for polling debugger notifications. Use wait for blocking wait or fetch to drain queued events.");
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> executeInternal(arguments));
  }

  private ToolResponse executeInternal(Map<String, Object> arguments) {
    String operation = ParameterUtils.getString(arguments, "operation", null);
    if (operation == null) {
      return ToolResponse.missingParameter("operation");
    }

    return switch (operation) {
    case "wait" -> handleWait(arguments);
    case "fetch" -> handleFetch(arguments);
    case "clear" -> handleClear();
    default -> ToolResponse.unsupportedOperation(operation, "wait, fetch, clear");
    };
  }

  private ToolResponse handleWait(Map<String, Object> arguments) {
    long timeoutMs = ParameterUtils.getLong(arguments, "timeout_ms", 30_000L);
    if (timeoutMs < 0) {
      return ToolResponse.invalidParameter("timeout_ms", "must be zero or positive");
    }

    FilterData filterData = parseFilter(arguments);
    Filter filter = filterData.filter();
    DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
    Optional<EventRecord> record = queue.waitFor(filter, timeoutMs);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("timed_out", record.isEmpty());
    payload.put("timeout_ms", timeoutMs);
    filterData.typeFilter().ifPresent(types -> payload.put("types", types));
    filterData.threadFilter().ifPresent(id -> payload.put("thread_id", id));
    record.ifPresent(event -> payload.put("event", event.toMap()));
    payload.put("pending_events", queue.size());
    return ToolResponse.successJson(payload);
  }

  private ToolResponse handleFetch(Map<String, Object> arguments) {
    int maxEvents = ParameterUtils.getInt(arguments, "max_events", 10);
    if (maxEvents < 1) {
      return ToolResponse.invalidParameter("max_events", "must be at least 1");
    }
    if (maxEvents > 100) {
      return ToolResponse.invalidParameter("max_events", "must be <= 100");
    }

    FilterData filterData = parseFilter(arguments);
    Filter filter = filterData.filter();
    DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
    List<EventRecord> events = queue.fetch(filter, maxEvents);

    List<Map<String, Object>> eventMaps = events.stream().map(EventRecord::toMap).collect(Collectors.toList());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("count", eventMaps.size());
    payload.put("events", eventMaps);
    filterData.typeFilter().ifPresent(types -> payload.put("types", types));
    filterData.threadFilter().ifPresent(id -> payload.put("thread_id", id));
    payload.put("pending_events", queue.size());
    return ToolResponse.successJson(payload);
  }

  private ToolResponse handleClear() {
    DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
    List<EventRecord> drained = queue.fetch(new Filter(null, null), Integer.MAX_VALUE);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cleared", drained.size());
    payload.put("pending_events", queue.size());
    return ToolResponse.successJson(payload);
  }

  private FilterData parseFilter(Map<String, Object> arguments) {
    String[] typeArray = ParameterUtils.getStringArray(arguments, "types", null);
    Set<String> typeSet = null;
    if (typeArray != null) {
      typeSet = Arrays.stream(typeArray).filter(s -> s != null && !s.isBlank()).map(String::trim)
          .collect(Collectors.toUnmodifiableSet());
      if (typeSet.isEmpty()) {
        typeSet = null;
      }
    }

    Long threadId = ParameterUtils.getLong(arguments, "thread_id", null);
    return new FilterData(new Filter(typeSet, threadId), typeSet, threadId);
  }

  private record FilterData(Filter filter, Set<String> typeSet, Long threadId) {
    Optional<Set<String>> typeFilter() {
      return Optional.ofNullable(typeSet);
    }

    Optional<Long> threadFilter() {
      return Optional.ofNullable(threadId);
    }
  }
}
