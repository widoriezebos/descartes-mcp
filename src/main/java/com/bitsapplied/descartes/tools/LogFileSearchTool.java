package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.tools.logging.support.LogFileSearchService;
import com.bitsapplied.descartes.tools.logging.support.MatchResult;
import com.bitsapplied.descartes.tools.logging.support.SearchParams;
import com.bitsapplied.descartes.tools.logging.support.SearchResult;
import com.bitsapplied.descartes.tools.logging.support.TimeRangeParser;

/**
 * MCP tool for comprehensive log file analysis with bash-parity for remote
 * scenarios.
 *
 * Agent-friendly design principles: - Simple defaults (2-3 parameters for most
 * operations) - Multi-file by default when file_path omitted - Smart context
 * (showContext flag → 3/3 lines automatically) - Bandwidth optimization (count
 * returns integer, not 1000 lines) - Operation aliases (search = grep, both
 * work)
 *
 * Operations: - search: Pattern matching with optional context - count: Count
 * matches without returning content (bandwidth saver) - tail: Last N lines -
 * head: First N lines - range: Specific line number range - time_range: Filter
 * by timestamp range - extract: Extract captured groups from patterns -
 * between: Extract content between start/end markers - timeline: Time-series
 * frequency analysis
 */
public class LogFileSearchTool implements MCPTool {

  private final LogFileSearchService searchService;

  public LogFileSearchTool() {
    this.searchService = new LogFileSearchService();
  }

  @Override
  public String getToolName() {
    return "log_file_search";
  }

  @Override
  public String getToolDescription() {
    return "Search and analyze log files with comprehensive operations. "
        + "Supports pattern matching, counting, extraction, time-series analysis, and multi-file operations. "
        + "Designed for remote log analysis with bandwidth optimization. " + "Examples: "
        + "- Simple search: {\"operation\":\"search\", \"pattern\":\"ERROR\"} (searches all logs) "
        + "- Count matches: {\"operation\":\"count\", \"pattern\":\"Exception\"} (returns just the count) "
        + "- Extract values: {\"operation\":\"extract\", \"pattern\":\"user=([\\\\w]+)\"} (extracts usernames) "
        + "- Timeline analysis: {\"operation\":\"timeline\", \"pattern\":\"ERROR\", \"bucket_size\":\"5m\"} (5-minute buckets)";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");

    Map<String, Object> properties = new HashMap<>();

    // operation (required)
    properties.put("operation", Map.of("type", "string", "description",
        "Operation to perform. " + "search: pattern matching with context | "
            + "count: just count matches (bandwidth saver) | " + "tail: last N lines | " + "head: first N lines | "
            + "range: line number range | " + "time_range: timestamp filtering | "
            + "extract: extract captured groups | " + "between: content between markers | "
            + "timeline: time-series frequency",
        "enum",
        List.of("search", "grep", "count", "tail", "head", "range", "time_range", "extract", "between", "timeline")));

    // file_path (optional - multi-file by default if omitted)
    properties.put("file_path",
        Map.of("type", "string", "description",
            "Path to single log file. If omitted, searches all discovered log files automatically. "
                + "For multi-file, use file_paths or file_pattern instead."));

    // file_paths (optional - explicit multi-file)
    properties.put("file_paths", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Explicit list of file paths for multi-file operations"));

    // file_pattern (optional - glob pattern)
    properties.put("file_pattern",
        Map.of("type", "string", "description", "Glob pattern for file discovery (e.g., \"**/*.log\", \"app-*.log\")"));

    // pattern (for search/count/extract/timeline)
    properties.put("pattern", Map.of("type", "string", "description",
        "Regex pattern to search for. Supports capture groups for extract operation."));

    // patterns (optional - multi-pattern search)
    properties.put("patterns", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Multiple patterns for multi-pattern search. Use with pattern_mode."));

    // pattern_mode (optional)
    properties.put("pattern_mode",
        Map.of("type", "string", "description", "How to combine multiple patterns: 'any' (OR - default) or 'all' (AND)",
            "enum", List.of("any", "all"), "default", "any"));

    // invert_match (optional - grep -v)
    properties.put("invert_match", Map.of("type", "boolean", "description",
        "Exclude lines matching pattern (like grep -v). Default: false", "default", false));

    // show_context (optional - smart context)
    properties.put("show_context", Map.of("type", "boolean", "description",
        "Smart context: automatically add 3 lines before/after matches. Default: false", "default", false));

    // case_insensitive (optional)
    properties.put("case_insensitive",
        Map.of("type", "boolean", "description", "Case-insensitive search. Default: false", "default", false));

    // context_before/after (optional - manual context)
    properties.put("context_before", Map.of("type", "integer", "description",
        "Lines of context before match. Default: 0. Tip: Use show_context for automatic 3/3", "default", 0));
    properties.put("context_after", Map.of("type", "integer", "description",
        "Lines of context after match. Default: 0. Tip: Use show_context for automatic 3/3", "default", 0));

    // max_results (optional)
    properties.put("max_results", Map.of("type", "integer", "description",
        "Maximum number of results (per file for multi-file). Default: 1000", "default", 1000));

    // unique (optional - for extract)
    properties.put("unique", Map.of("type", "boolean", "description",
        "Deduplicate extracted values (extract operation only). Default: false", "default", false));

    // capture_group (optional - for extract)
    properties.put("capture_group", Map.of("type", "integer", "description",
        "Regex capture group number to extract (0=full match, 1=first group, etc.). Default: 1", "default", 1));

    // lines (for tail/head)
    properties.put("lines", Map.of("type", "integer", "description", "Number of lines for tail/head operations"));

    // start_line/end_line (for range)
    properties.put("start_line",
        Map.of("type", "integer", "description", "Starting line number for range operation (1-indexed)"));
    properties.put("end_line",
        Map.of("type", "integer", "description", "Ending line number for range operation (inclusive)"));

    // start_time/end_time (for time_range)
    properties.put("start_time", Map.of("type", "string", "description", "Start time (ISO 8601 format)"));
    properties.put("end_time", Map.of("type", "string", "description", "End time (ISO 8601 format)"));

    // since (optional - relative time)
    properties.put("since", Map.of("type", "string", "description",
        "Relative time string for time_range (e.g., \"1h\", \"30m\", \"2d\"). Alternative to start_time."));

    // level_filter (optional)
    properties.put("level_filter",
        Map.of("type", "string", "description", "Filter by log level (ERROR, WARN, INFO, DEBUG, TRACE)", "enum",
            List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE")));

    // start_marker/end_marker (for between)
    properties.put("start_marker",
        Map.of("type", "string", "description", "Start boundary regex for between operation"));
    properties.put("end_marker", Map.of("type", "string", "description", "End boundary regex for between operation"));

    // include_markers (optional - for between)
    properties.put("include_markers", Map.of("type", "boolean", "description",
        "Include boundary lines in between results. Default: false", "default", false));

    // max_sections (optional - for between)
    properties.put("max_sections",
        Map.of("type", "integer", "description", "Maximum number of sections for between operation"));

    // bucket_size (for timeline)
    properties.put("bucket_size", Map.of("type", "string", "description",
        "Time bucket size for timeline operation (e.g., \"5m\", \"1h\", \"30s\", \"1d\")"));

    schema.put("properties", properties);
    schema.put("required", List.of("operation"));

    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        SearchParams params = buildSearchParams(arguments);
        SearchResult result = searchService.executeSearch(params);
        return ToolResponse.successJson(searchResultToMap(result));
      } catch (IllegalArgumentException e) {
        return ToolResponse.error(ToolErrorCode.VALIDATION_FAILED, e.getMessage());
      } catch (Exception e) {
        return ToolResponse.error(ToolErrorCode.EXECUTION_FAILED, "Search failed: " + e.getMessage());
      }
    });
  }

  private SearchParams buildSearchParams(Map<String, Object> args) {
    String operation = (String) args.get("operation");
    if (operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("operation is required");
    }

    // Normalize operation (grep → search)
    if ("grep".equals(operation)) {
      operation = "search";
    }

    SearchParams.Builder builder = SearchParams.builder().operation(operation);

    // File specification (at least one must be provided or inferred)
    String filePath = (String) args.get("file_path");
    @SuppressWarnings("unchecked")
    List<String> filePaths = (List<String>) args.get("file_paths");
    String filePattern = (String) args.get("file_pattern");

    if (filePath != null && !filePath.isBlank()) {
      builder.filePath(filePath);
    } else if (filePaths != null && !filePaths.isEmpty()) {
      builder.filePaths(filePaths);
    } else if (filePattern != null && !filePattern.isBlank()) {
      builder.filePattern(filePattern);
    } else {
      // Default: search all log files
      builder.filePattern("**/*.log");
    }

    // Pattern(s)
    if (args.containsKey("pattern")) {
      builder.pattern((String) args.get("pattern"));
    }
    if (args.containsKey("patterns")) {
      @SuppressWarnings("unchecked")
      List<String> patterns = (List<String>) args.get("patterns");
      builder.patterns(patterns);
    }
    if (args.containsKey("pattern_mode")) {
      builder.patternMode((String) args.get("pattern_mode"));
    }

    // Flags
    if (args.containsKey("case_insensitive")) {
      builder.caseInsensitive(getBooleanValue(args, "case_insensitive"));
    }
    if (args.containsKey("invert_match")) {
      builder.invertMatch(getBooleanValue(args, "invert_match"));
    }
    if (args.containsKey("show_context")) {
      builder.showContext(getBooleanValue(args, "show_context"));
    }
    if (args.containsKey("unique")) {
      builder.unique(getBooleanValue(args, "unique"));
    }
    if (args.containsKey("include_markers")) {
      builder.includeMarkers(getBooleanValue(args, "include_markers"));
    }

    // Context lines
    if (args.containsKey("context_before")) {
      builder.contextBefore(getIntValue(args, "context_before"));
    }
    if (args.containsKey("context_after")) {
      builder.contextAfter(getIntValue(args, "context_after"));
    }

    // Limits
    if (args.containsKey("max_results")) {
      builder.maxResults(getIntValue(args, "max_results"));
    }
    if (args.containsKey("max_sections")) {
      builder.maxSections(getIntValue(args, "max_sections"));
    }

    // Extract parameters
    if (args.containsKey("capture_group")) {
      builder.captureGroup(getIntValue(args, "capture_group"));
    }

    // Line operations
    if (args.containsKey("lines")) {
      builder.lines(getIntValue(args, "lines"));
    }
    if (args.containsKey("start_line")) {
      builder.startLine(getIntValue(args, "start_line"));
    }
    if (args.containsKey("end_line")) {
      builder.endLine(getIntValue(args, "end_line"));
    }

    // Time operations
    if (args.containsKey("start_time")) {
      builder.startTime(TimeRangeParser.parse((String) args.get("start_time")));
    }
    if (args.containsKey("end_time")) {
      builder.endTime(TimeRangeParser.parse((String) args.get("end_time")));
    }
    if (args.containsKey("since")) {
      builder.since((String) args.get("since"));
    }

    // Filtering
    if (args.containsKey("level_filter")) {
      builder.levelFilter((String) args.get("level_filter"));
    }

    // Between operation
    if (args.containsKey("start_marker")) {
      builder.startMarker((String) args.get("start_marker"));
    }
    if (args.containsKey("end_marker")) {
      builder.endMarker((String) args.get("end_marker"));
    }

    // Timeline operation
    if (args.containsKey("bucket_size")) {
      builder.bucketSize((String) args.get("bucket_size"));
    }

    return builder.build();
  }

  private int getIntValue(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value instanceof Integer) {
      return (Integer) value;
    } else if (value instanceof Long) {
      return ((Long) value).intValue();
    } else if (value instanceof String) {
      return Integer.parseInt((String) value);
    }
    return 0;
  }

  private boolean getBooleanValue(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    } else if (value instanceof String) {
      return Boolean.parseBoolean((String) value);
    }
    return false;
  }

  private Map<String, Object> searchResultToMap(SearchResult result) {
    Map<String, Object> map = new HashMap<>();
    map.put("status", result.status());
    map.put("operation", result.operation());

    if (result.filePath() != null) {
      map.put("file_path", result.filePath());
    }
    if (result.fileSizeBytes() != null) {
      map.put("file_size_bytes", result.fileSizeBytes());
    }
    if (result.linesSearched() != null) {
      map.put("lines_searched", result.linesSearched());
    }
    if (result.matchesFound() != null) {
      map.put("matches_found", result.matchesFound());
    }
    if (result.searchTimeMs() != null) {
      map.put("search_time_ms", result.searchTimeMs());
    }

    // Matches (search/time_range)
    if (!result.matches().isEmpty()) {
      map.put("matches", result.matches().stream().map(this::matchResultToMap).toList());
    }

    // Lines (tail/head/range)
    if (!result.lines().isEmpty()) {
      map.put("lines", result.lines());
    }

    // Count (count operation)
    if (result.count() != null) {
      map.put("count", result.count());
    }

    // Extracted (extract operation)
    if (!result.extracted().isEmpty()) {
      map.put("extracted", result.extracted());
    }
    if (result.uniqueCount() != null) {
      map.put("unique_count", result.uniqueCount());
    }

    // Sections (between operation)
    if (!result.sections().isEmpty()) {
      map.put("sections", result.sections().stream().map(this::sectionToMap).toList());
    }

    // Timeline (timeline operation)
    if (!result.timeline().isEmpty()) {
      map.put("timeline", result.timeline());
    }

    // Multi-file results
    if (!result.fileResults().isEmpty()) {
      Map<String, Object> fileResultsMap = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : result.fileResults().entrySet()) {
        if (entry.getValue() instanceof SearchResult) {
          fileResultsMap.put(entry.getKey(), searchResultToMap((SearchResult) entry.getValue()));
        } else {
          fileResultsMap.put(entry.getKey(), entry.getValue());
        }
      }
      map.put("file_results", fileResultsMap);
    }

    if (result.truncated()) {
      map.put("truncated", true);
    }
    if (result.errorMessage() != null) {
      map.put("error_message", result.errorMessage());
    }

    return map;
  }

  private Map<String, Object> matchResultToMap(MatchResult match) {
    Map<String, Object> map = new HashMap<>();
    map.put("line_number", match.lineNumber());
    map.put("content", match.content());

    if (match.timestamp() != null) {
      map.put("timestamp", match.timestamp().toString());
    }
    if (match.level() != null) {
      map.put("level", match.level());
    }
    if (match.logger() != null) {
      map.put("logger", match.logger());
    }
    if (!match.contextBefore().isEmpty()) {
      map.put("context_before", match.contextBefore());
    }
    if (!match.contextAfter().isEmpty()) {
      map.put("context_after", match.contextAfter());
    }

    return map;
  }

  private Map<String, Object> sectionToMap(SearchResult.Section section) {
    Map<String, Object> map = new HashMap<>();
    map.put("section_number", section.sectionNumber());
    map.put("start_marker", section.startMarker());
    map.put("end_marker", section.endMarker());
    map.put("content", section.content());
    map.put("start_line", section.startLine());
    map.put("end_line", section.endLine());
    return map;
  }
}
