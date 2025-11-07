package com.bitsapplied.descartes.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import com.bitsapplied.descartes.util.InMemoryAppender;
import com.bitsapplied.descartes.util.LoggerControl;

/**
 * MCP tool for comprehensive logging integration including tailing logs,
 * adjusting log levels, searching logs, and analyzing log patterns.
 */
public class LoggingIntegrationTool implements MCPTool {

  @Override
  public String getToolName() {
    return "logging_integration";
  }

  @Override
  public String getToolDescription() {
    return "Real-time logging control and analysis tool for managing application log output and debugging. "
        + "Provides live log tailing from in-memory buffer, dynamic log level adjustment per logger/package without restart, "
        + "regex-based log searching for error investigation, statistical analysis of log patterns and frequencies, "
        + "and logger hierarchy management. Supports Log4j2 configuration manipulation for fine-grained control over logging behavior.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("operation",
        Map.of("type", "string", "enum", List.of("tail", "level", "grep", "stats", "clear", "list_loggers", "filters"),
            "description", "Logging operation to perform"));
    properties.put("logger", Map.of("type", "string", "description",
        "Logger name or package (required for level operation, use 'ROOT' for root logger)"));
    properties.put("new_level",
        Map.of("type", "string", "enum", List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF"),
            "description", "New level when operation is 'level'"));
    properties.put("pattern",
        Map.of("type", "string", "description", "Regex pattern to search within log buffer (grep operation)"));
    properties.put("lines", Map.of("type", "integer", "minimum", 1, "maximum", 500, "description",
        "Number of recent log lines to return (tail/grep operations)", "default", 50));
    properties.put("case_insensitive",
        Map.of("type", "boolean", "description", "Use case-insensitive regex for grep", "default", false));
    properties.put("include_exceptions", Map.of("type", "boolean", "description",
        "Include exception buffer entries in tail/grep output", "default", false));
    properties.put("filter_action", Map.of("type", "string", "enum", List.of("add", "remove", "list"), "description",
        "Action for managing logger filters (filters operation)"));
    properties.put("filter_prefix",
        Map.of("type", "string", "description", "Logger prefix to add/remove from filters"));

    List<Map<String, Object>> constraints = new ArrayList<>();
    constraints.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "level")), "required", List.of("operation")), "then",
        Map.of("required", List.of("logger", "new_level"))));
    constraints.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "filters")), "required", List.of("operation")), "then",
        Map.of("required", List.of("filter_action"))));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("allOf", constraints);
    schema.put("description",
        "Real-time logging control. Tail buffered logs, adjust levels, run regex searches, or manage logger filters.");
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        String operation = arguments != null ? (String) arguments.get("operation") : null;

        if (operation == null || operation.isBlank()) {
          return ToolResponse.missingParameter("operation");
        }

        return switch (operation) {
        case "tail" -> ToolResponse.successJson(tailLogs(arguments));
        case "level" -> ToolResponse.successJson(setLogLevel(arguments));
        case "grep" -> ToolResponse.successJson(grepLogs(arguments));
        case "stats" -> ToolResponse.successJson(getLogStats(arguments));
        case "clear" -> ToolResponse.successJson(clearLogs(arguments));
        case "list_loggers" -> ToolResponse.successJson(listLoggers());
        case "filters" -> ToolResponse.successJson(manageFilters(arguments));
        default ->
          ToolResponse.unsupportedOperation(operation, "tail, level, grep, stats, clear, list_loggers, filters");
        };
      } catch (IllegalArgumentException e) {
        return ToolResponse.validationError(e.getMessage());
      } catch (Exception e) {
        return ToolResponse.executionFailed("Logging operation failed: " + e.getMessage());
      }
    });
  }

  /**
   * Tail recent log entries.
   */
  private Map<String, Object> tailLogs(Map<String, Object> arguments) {
    Integer lines = ((Number) arguments.getOrDefault("lines", 50)).intValue();
    lines = Math.max(1, Math.min(lines, 500));
    Boolean includeExceptions = (Boolean) arguments.getOrDefault("include_exceptions", false);
    String loggerFilter = (String) arguments.get("logger");

    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not found in Log4j2 configuration");
    }

    List<String> logs = appender.getLogBuffer();

    // Filter by logger if specified
    if (loggerFilter != null && !loggerFilter.isEmpty()) {
      logs = filterLogsByLogger(logs, loggerFilter);
    }

    // Get the requested number of recent lines
    int totalLines = logs.size();
    List<String> recentLogs;
    if (lines >= totalLines) {
      recentLogs = logs;
    } else {
      recentLogs = logs.subList(totalLines - lines, totalLines);
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("total_buffered", totalLines);
    result.put("lines_returned", recentLogs.size());
    result.put("logs", recentLogs);

    // Include exception logs if requested
    if (includeExceptions) {
      List<String> exceptions = appender.getLastExceptions(Math.min(lines, 10));
      result.put("exceptions", exceptions);
      result.put("exception_count", exceptions.size());
    }

    // Add buffer configuration info
    result.put("buffer_config", Map.of("max_buffer_size", appender.getMaxBufferSize(), "truncate_back_to",
        appender.getTruncateBackTo(), "logger_filters", appender.getLoggerFilters()));

    return result;
  }

  /**
   * Set log level for a logger.
   */
  private Map<String, Object> setLogLevel(Map<String, Object> arguments) {
    String logger = (String) arguments.get("logger");
    String newLevel = (String) arguments.get("new_level");

    if (logger == null) {
      throw new IllegalArgumentException("Logger name is required for level operation");
    }

    // Empty string means ROOT logger
    if (logger.isEmpty()) {
      logger = "ROOT";
    }

    if (newLevel == null || newLevel.isEmpty()) {
      throw new IllegalArgumentException("New level is required for level operation");
    }

    Level level;
    try {
      level = Level.valueOf(newLevel.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid log level: " + newLevel);
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("logger", logger);
    result.put("new_level", newLevel);

    // Get the old level for reporting
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();

    if (logger.equalsIgnoreCase("ROOT") || logger.equals("*")) {
      // Set global level for all loggers
      Level oldLevel = config.getRootLogger().getLevel();
      LoggerControl.setGlobalLevel(level);
      result.put("action", "global_level_change");
      result.put("old_level", oldLevel.toString());
      result.put("affected", "all loggers");
    } else if (logger.contains(".")) {
      // Set package level
      LoggerConfig loggerConfig = config.getLoggerConfig(logger);
      Level oldLevel = loggerConfig.getLevel();
      LoggerControl.setPackageLevel(logger, level);
      result.put("action", "package_level_change");
      result.put("old_level", oldLevel.toString());
      result.put("affected", "package and sub-packages");
    } else {
      // Set specific logger level
      LoggerConfig loggerConfig = config.getLoggerConfig(logger);
      if (loggerConfig == null || loggerConfig.getName().equals("")) {
        // Create a new logger config if it doesn't exist
        LoggerConfig newLoggerConfig = new LoggerConfig(logger, level, true);
        config.addLogger(logger, newLoggerConfig);
        ctx.updateLoggers();
        result.put("action", "created_logger_config");
        result.put("old_level", "inherited");
      } else {
        Level oldLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(level);
        ctx.updateLoggers();
        result.put("action", "logger_level_change");
        result.put("old_level", oldLevel.toString());
      }
    }

    return result;
  }

  /**
   * Search logs for patterns.
   */
  private Map<String, Object> grepLogs(Map<String, Object> arguments) {
    String patternStr = (String) arguments.get("pattern");
    Boolean caseInsensitive = (Boolean) arguments.getOrDefault("case_insensitive", false);
    Boolean includeExceptions = (Boolean) arguments.getOrDefault("include_exceptions", false);
    Integer maxResults = ((Number) arguments.getOrDefault("lines", 100)).intValue();
    maxResults = Math.max(1, Math.min(maxResults, 500));

    if (patternStr == null || patternStr.isEmpty()) {
      throw new IllegalArgumentException("Pattern is required for grep operation");
    }

    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not found in Log4j2 configuration");
    }

    // Compile the pattern
    Pattern pattern;
    try {
      int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
      pattern = Pattern.compile(patternStr, flags);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
    }

    // Search through logs
    List<String> logs = appender.getLogBuffer();
    List<Map<String, Object>> matches = new ArrayList<>();
    int lineNumber = 0;

    for (String log : logs) {
      lineNumber++;
      Matcher matcher = pattern.matcher(log);
      if (matcher.find()) {
        Map<String, Object> match = new HashMap<>();
        match.put("line_number", lineNumber);
        match.put("content", log);

        // Extract matched groups if any
        if (matcher.groupCount() > 0) {
          List<String> groups = new ArrayList<>();
          for (int i = 1; i <= matcher.groupCount(); i++) {
            groups.add(matcher.group(i));
          }
          match.put("captured_groups", groups);
        }

        matches.add(match);

        if (matches.size() >= maxResults) {
          break;
        }
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("pattern", patternStr);
    result.put("case_insensitive", caseInsensitive);
    result.put("total_searched", lineNumber);
    result.put("matches_found", matches.size());
    result.put("matches", matches);

    // Search in exceptions if requested
    if (includeExceptions) {
      List<String> exceptions = appender.getExceptionBuffer();
      List<Map<String, Object>> exceptionMatches = new ArrayList<>();

      for (int i = 0; i < exceptions.size(); i++) {
        Matcher matcher = pattern.matcher(exceptions.get(i));
        if (matcher.find()) {
          exceptionMatches.add(Map.of("index", i, "content",
              exceptions.get(i).substring(0, Math.min(500, exceptions.get(i).length())) + "..."));
        }
      }

      result.put("exception_matches", exceptionMatches);
      result.put("exception_matches_count", exceptionMatches.size());
    }

    return result;
  }

  /**
   * Get logging statistics.
   */
  private Map<String, Object> getLogStats(Map<String, Object> arguments) {
    Boolean includeExceptions = (Boolean) arguments.getOrDefault("include_exceptions", false);

    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not found in Log4j2 configuration");
    }

    List<String> logs = appender.getLogBuffer();

    // Count log levels
    Map<String, Integer> levelCounts = new HashMap<>();
    Map<String, Integer> loggerCounts = new HashMap<>();
    Map<String, String> recentByLevel = new HashMap<>();

    for (String log : logs) {
      // Extract log level (assuming pattern like "2024-01-15 10:30:45.123 INFO
      // [thread] logger - message")
      String level = extractLogLevel(log);
      if (level != null) {
        levelCounts.put(level, levelCounts.getOrDefault(level, 0) + 1);
        recentByLevel.put(level, log); // Keep the most recent for each level
      }

      // Extract logger name
      String loggerName = extractLoggerName(log);
      if (loggerName != null) {
        loggerCounts.put(loggerName, loggerCounts.getOrDefault(loggerName, 0) + 1);
      }
    }

    // Get top loggers
    List<Map<String, Object>> topLoggers = loggerCounts.entrySet().stream()
        .sorted((a, b) -> b.getValue().compareTo(a.getValue())).limit(10)
        .map(e -> Map.of("logger", (Object) e.getKey(), "count", e.getValue())).collect(Collectors.toList());

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("total_logs", logs.size());
    result.put("buffer_utilization", String.format("%.1f%%", (double) logs.size() / appender.getMaxBufferSize() * 100));
    result.put("level_distribution", levelCounts);
    result.put("top_loggers", topLoggers);
    result.put("recent_by_level", recentByLevel);

    // Configuration details
    result.put("configuration", Map.of("max_buffer_size", appender.getMaxBufferSize(), "truncate_back_to",
        appender.getTruncateBackTo(), "active_filters", appender.getLoggerFilters()));

    // Exception statistics if requested
    if (includeExceptions) {
      List<String> exceptions = appender.getExceptionBuffer();
      Map<String, Integer> exceptionTypes = new HashMap<>();

      for (String exc : exceptions) {
        String type = extractExceptionType(exc);
        if (type != null) {
          exceptionTypes.put(type, exceptionTypes.getOrDefault(type, 0) + 1);
        }
      }

      result.put("exception_stats",
          Map.of("total_exceptions", exceptions.size(), "buffer_utilization",
              String.format("%.1f%%", (double) exceptions.size() / appender.getMaxExceptionBufferSize() * 100),
              "exception_types", exceptionTypes));
    }

    return result;
  }

  /**
   * Clear log buffers.
   */
  private Map<String, Object> clearLogs(Map<String, Object> arguments) {
    Boolean clearExceptions = (Boolean) arguments.getOrDefault("include_exceptions", false);

    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not found in Log4j2 configuration");
    }

    // Get current counts before clearing
    int logCount = appender.getLogBuffer().size();
    int exceptionCount = 0;

    // Clear the main log buffer
    appender.getLogBuffer().clear();

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("logs_cleared", logCount);

    if (clearExceptions) {
      exceptionCount = appender.getExceptionBuffer().size();
      appender.clearExceptionBuffer();
      result.put("exceptions_cleared", exceptionCount);
    }

    return result;
  }

  /**
   * List all configured loggers.
   */
  private Map<String, Object> listLoggers() {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();

    List<Map<String, Object>> loggers = new ArrayList<>();

    // Add root logger
    LoggerConfig rootLogger = config.getRootLogger();
    loggers.add(Map.of("name", "ROOT", "level", rootLogger.getLevel().toString(), "additive", true));

    // Add all configured loggers
    for (Map.Entry<String, LoggerConfig> entry : config.getLoggers().entrySet()) {
      LoggerConfig loggerConfig = entry.getValue();
      loggers.add(Map.of("name", entry.getKey(), "level", loggerConfig.getLevel().toString(), "additive",
          loggerConfig.isAdditive()));
    }

    // Sort by name
    loggers.sort((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")));

    return Map.of("status", "success", "logger_count", loggers.size(), "loggers", loggers);
  }

  /**
   * Manage logger filters in InMemoryAppender.
   */
  private Map<String, Object> manageFilters(Map<String, Object> arguments) {
    String action = (String) arguments.get("filter_action");
    String prefix = (String) arguments.get("filter_prefix");

    if (action == null) {
      action = "list";
    }

    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not found in Log4j2 configuration");
    }

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("action", action);

    switch (action) {
    case "add":
      if (prefix == null || prefix.isEmpty()) {
        throw new IllegalArgumentException("Filter prefix is required for add action");
      }
      appender.addLoggerFilter(prefix);
      result.put("added", prefix);
      result.put("current_filters", appender.getLoggerFilters());
      break;

    case "remove":
      if (prefix == null || prefix.isEmpty()) {
        throw new IllegalArgumentException("Filter prefix is required for remove action");
      }
      boolean removed = appender.removeLoggerFilter(prefix);
      result.put("removed", removed);
      result.put("filter", prefix);
      result.put("current_filters", appender.getLoggerFilters());
      break;

    case "list":
    default:
      result.put("filters", appender.getLoggerFilters());
      break;
    }

    return result;
  }

  /**
   * Filter logs by logger name.
   */
  private List<String> filterLogsByLogger(List<String> logs, String loggerFilter) {
    return logs.stream().filter(log -> {
      String logger = extractLoggerName(log);
      return logger != null && logger.contains(loggerFilter);
    }).collect(Collectors.toList());
  }

  /**
   * Extract log level from a log line.
   */
  private String extractLogLevel(String log) {
    // Pattern for typical log format: "timestamp LEVEL [thread] logger - message"
    Pattern pattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+(\\w+)\\s+");
    Matcher matcher = pattern.matcher(log);
    if (matcher.find()) {
      return matcher.group(1);
    }

    // Fallback: look for common level keywords
    if (log.contains(" ERROR "))
      return "ERROR";
    if (log.contains(" WARN "))
      return "WARN";
    if (log.contains(" INFO "))
      return "INFO";
    if (log.contains(" DEBUG "))
      return "DEBUG";
    if (log.contains(" TRACE "))
      return "TRACE";

    return null;
  }

  /**
   * Extract logger name from a log line.
   */
  private String extractLoggerName(String log) {
    // Pattern for typical log format: "timestamp LEVEL [thread] logger - message"
    // Logger name is typically after thread info and before the dash
    Pattern pattern = Pattern.compile("\\[.*?\\]\\s+([\\w\\.]+)\\s+-");
    Matcher matcher = pattern.matcher(log);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  /**
   * Extract exception type from an exception log.
   */
  private String extractExceptionType(String exceptionLog) {
    // Look for exception class name pattern
    Pattern pattern = Pattern.compile("([\\w\\.]+Exception|[\\w\\.]+Error):");
    Matcher matcher = pattern.matcher(exceptionLog);
    if (matcher.find()) {
      String fullName = matcher.group(1);
      // Return just the simple name
      int lastDot = fullName.lastIndexOf('.');
      return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
    return null;
  }
}
