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

  private static final String OPERATION_WAIT = "wait";
  private static final String OPERATION_FETCH = "fetch";
  private static final String OPERATION_GET_EVENTS = "get_events";
  private static final String OPERATION_CLEAR = "clear";
  private static final String OPERATION_WAIT_FOR = "wait_for";
  private static final String OPERATION_WAIT_FOR_EVENT = "wait_for_event";
  private static final String SUPPORTED_OPERATIONS_WITH_ALIASES = "wait, fetch, clear (aliases: wait_for, wait_for_event -> wait; get_events -> fetch)";
  private static final int MAX_FETCH_EVENTS = 100;

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
    properties.put("operation",
        Map.of("type", "string", "enum", List.of(OPERATION_WAIT, OPERATION_FETCH, OPERATION_CLEAR), "description",
            "Operation to perform. Canonical operations: wait, fetch, clear. Compatibility aliases wait_for and "
                + "wait_for_event are accepted as wait, and get_events maps to fetch."));
    properties.put("types", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Optional list of event types to match (e.g. debugger.breakpoint_hit)"));
    properties.put("thread_id",
        Map.of("type", "integer", "description", "Optional thread id filter (matches payload thread_id)"));
    properties.put("since_sequence", Map.of("type", "integer", "minimum", 0, "description",
        "Optional sequence cursor. Only events with sequence greater than this value are matched."));
    properties.put("timeout_ms", Map.of("type", "integer", "minimum", 0, "description",
        "Timeout in milliseconds when waiting for an event (default 30000)"));
    properties.put("max_events", Map.of("type", "integer", "minimum", 1, "description",
        "Max number of events to fetch. Values above 100 are clamped to 100"));

    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("description",
        "Utility for polling debugger notifications. Use wait for blocking wait or fetch to drain queued events. "
            + "Compatibility aliases wait_for and wait_for_event map to wait; get_events maps to fetch.");
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> executeInternal(arguments));
  }

  private ToolResponse executeInternal(Map<String, Object> arguments) {
    String rawOperation = ParameterUtils.getString(arguments, "operation", null);
    if (rawOperation == null) {
      return ToolResponse.missingParameter("operation");
    }
    String operation = normalizeOperation(rawOperation);

    return switch (operation) {
    case OPERATION_WAIT -> handleWait(arguments);
    case OPERATION_FETCH -> handleFetch(arguments);
    case OPERATION_CLEAR -> handleClear(arguments);
    default -> ToolResponse.unsupportedOperation(rawOperation, SUPPORTED_OPERATIONS_WITH_ALIASES);
    };
  }

  private static String normalizeOperation(String operation) {
    if (operation == null) {
      return null;
    }
    String normalized = operation.trim();
    return switch (normalized) {
    case OPERATION_WAIT_FOR, OPERATION_WAIT_FOR_EVENT -> OPERATION_WAIT;
    case OPERATION_GET_EVENTS -> OPERATION_FETCH;
    default -> normalized;
    };
  }

  private ToolResponse handleWait(Map<String, Object> arguments) {
    long timeoutMs = ParameterUtils.getLong(arguments, "timeout_ms", 30_000L);
    if (timeoutMs < 0) {
      return ToolResponse.invalidParameter("timeout_ms", "must be zero or positive");
    }

    FilterParseResult parseResult = parseFilter(arguments);
    if (parseResult.error() != null) {
      return parseResult.error();
    }

    FilterData filterData = parseResult.filterData();
    Filter filter = filterData.filter();
    DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
    long startedAt = System.currentTimeMillis();
    Optional<EventRecord> record = queue.waitFor(filter, timeoutMs);
    long waitedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
    long adapterExtendedTimeoutMs = resolveAdapterExtendedTimeoutMs(timeoutMs);
    long effectiveTimeoutMs = timeoutMs + adapterExtendedTimeoutMs;

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("timed_out", record.isEmpty());
    payload.put("timeout_ms", timeoutMs);
    payload.put("requested_timeout_ms", timeoutMs);
    payload.put("effective_timeout_ms", effectiveTimeoutMs);
    payload.put("adapter_extended_timeout_ms", adapterExtendedTimeoutMs);
    payload.put("waited_ms", waitedMs);
    if (record.isEmpty()) {
      payload.put("timed_out_at_ms", System.currentTimeMillis());
    }
    filterData.typeFilter().ifPresent(types -> payload.put("types", types));
    filterData.threadFilter().ifPresent(id -> payload.put("thread_id", id));
    filterData.sinceSequenceFilter().ifPresent(seq -> payload.put("since_sequence", seq));
    record.ifPresent(event -> payload.put("event", event.toMap()));
    payload.put("pending_events", queue.size());
    payload.put("latest_sequence", queue.latestSequence());
    payload.put("pending_event_type_counts", queue.pendingTypeCounts());
    return ToolResponse.successJson(payload);
  }

  private ToolResponse handleFetch(Map<String, Object> arguments) {
    int requestedMaxEvents = ParameterUtils.getInt(arguments, "max_events", 10);
    if (requestedMaxEvents < 1) {
      return ToolResponse.invalidParameter("max_events", "must be at least 1");
    }
    int maxEvents = Math.min(requestedMaxEvents, MAX_FETCH_EVENTS);

    FilterParseResult parseResult = parseFilter(arguments);
    if (parseResult.error() != null) {
      return parseResult.error();
    }

    FilterData filterData = parseResult.filterData();
    Filter filter = filterData.filter();
    DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
    List<EventRecord> events = queue.fetch(filter, maxEvents);

    List<Map<String, Object>> eventMaps = events.stream().map(EventRecord::toMap).collect(Collectors.toList());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("count", eventMaps.size());
    payload.put("events", eventMaps);
    if (requestedMaxEvents > MAX_FETCH_EVENTS) {
      payload.put("clamped_from", requestedMaxEvents);
      payload.put("max_events", maxEvents);
    }
    filterData.typeFilter().ifPresent(types -> payload.put("types", types));
    filterData.threadFilter().ifPresent(id -> payload.put("thread_id", id));
    filterData.sinceSequenceFilter().ifPresent(seq -> payload.put("since_sequence", seq));
    payload.put("pending_events", queue.size());
    payload.put("latest_sequence", queue.latestSequence());
    payload.put("pending_event_type_counts", queue.pendingTypeCounts());
    return ToolResponse.successJson(payload);
  }

  private ToolResponse handleClear(Map<String, Object> arguments) {
    FilterParseResult parseResult = parseFilter(arguments);
    if (parseResult.error() != null) {
      return parseResult.error();
    }
    FilterData filterData = parseResult.filterData();
    Filter filter = filterData.filter();

    DebuggerEventQueue queue = DebuggerEventQueues.getOrCreate(context);
    List<EventRecord> drained = queue.fetch(filter, Integer.MAX_VALUE);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cleared", drained.size());
    payload.put("cleared_event_type_counts", countEventTypes(drained));
    payload.put("pending_events", queue.size());
    payload.put("latest_sequence", queue.latestSequence());
    payload.put("pending_event_type_counts", queue.pendingTypeCounts());
    filterData.typeFilter().ifPresent(types -> payload.put("types", types));
    filterData.threadFilter().ifPresent(id -> payload.put("thread_id", id));
    filterData.sinceSequenceFilter().ifPresent(seq -> payload.put("since_sequence", seq));
    return ToolResponse.successJson(payload);
  }

  private FilterParseResult parseFilter(Map<String, Object> arguments) {
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
    Long sinceSequence = ParameterUtils.getLong(arguments, "since_sequence", null);
    if (sinceSequence != null && sinceSequence < 0) {
      return new FilterParseResult(null, ToolResponse.invalidParameter("since_sequence", "must be zero or positive"));
    }

    return new FilterParseResult(
        new FilterData(new Filter(typeSet, threadId, sinceSequence), typeSet, threadId, sinceSequence), null);
  }

  private record FilterParseResult(FilterData filterData, ToolResponse error) {
  }

  private record FilterData(Filter filter, Set<String> typeSet, Long threadId, Long sinceSequence) {
    Optional<Set<String>> typeFilter() {
      return Optional.ofNullable(typeSet);
    }

    Optional<Long> threadFilter() {
      return Optional.ofNullable(threadId);
    }

    Optional<Long> sinceSequenceFilter() {
      return Optional.ofNullable(sinceSequence);
    }
  }

  private static Map<String, Integer> countEventTypes(List<EventRecord> records) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (EventRecord record : records) {
      String type = record.type() == null ? "<unknown>" : record.type();
      counts.merge(type, 1, Integer::sum);
    }
    return counts;
  }

  private long resolveAdapterExtendedTimeoutMs(long requestedTimeoutMs) {
    Object value = context.get("debugger_events.adapter_extended_timeout_ms");
    if (value instanceof Number number) {
      return Math.max(0L, number.longValue());
    }
    if (value instanceof String stringValue) {
      try {
        long parsed = Long.parseLong(stringValue.trim());
        return Math.max(0L, parsed);
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }
}
