package com.bitsapplied.descartes.tools;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.util.InMemoryAppender;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for analyzing exceptions from the log buffer. This provides
 * stateless access to exception history without requiring REPL state.
 */
public class ExceptionAnalysisTool implements MCPTool {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getToolName() {
    return "exception_analysis";
  }

  @Override
  public String getToolDescription() {
    return "Exception tracking and analysis tool that captures and analyzes runtime exceptions from the application log buffer. "
        + "Maintains a history of exceptions with full stack traces, timestamps, and error messages. "
        + "Supports retrieving recent exceptions for debugging, viewing exception statistics to identify patterns, "
        + "and clearing the buffer to reset tracking. Invaluable for post-mortem debugging and identifying recurring issues in production.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties", Map.of("operation",
        Map.of("type", "string", "enum", List.of("get_recent", "get_last", "clear", "stats"), "description",
            "The operation to perform"),
        "count",
        Map.of("type", "integer", "description", "Number of exceptions to retrieve (for get_recent operation, max 50)",
            "minimum", 1, "maximum", 50, "default", 10)),
        "required", List.of("operation"));
  }

  @Override
  public String executeTool(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      throw new IllegalArgumentException("Operation is required");
    }

    Map<String, Object> result = switch (operation) {
    case "get_recent" -> {
      Integer count = null;
      if (arguments.containsKey("count")) {
        Object countObj = arguments.get("count");
        if (countObj instanceof Number) {
          count = ((Number) countObj).intValue();
        }
      }
      yield getRecentExceptions(count);
    }
    case "get_last" -> getLastException();
    case "clear" -> clearExceptions();
    case "stats" -> getExceptionStats();
    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
    };

    return objectMapper.writeValueAsString(result);
  }

  /**
   * Get recent exceptions from the system log buffer.
   * 
   * @param count Number of exceptions to retrieve (default 10, max 50)
   * @return Map containing exception information
   */
  public Map<String, Object> getRecentExceptions(Integer count) {
    int limit = count != null ? Math.min(Math.max(count, 1), 50) : 10;

    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not available");
    }

    List<String> exceptions = appender.getLastExceptions(limit);

    if (exceptions.isEmpty()) {
      return Map.of("status", "success", "count", 0, "message", "No exceptions found in log buffer");
    }

    return Map.of("status", "success", "count", exceptions.size(), "exceptions", exceptions);
  }

  /**
   * Get the most recent exception from the log buffer.
   * 
   * @return Map containing the last exception or status message
   */
  public Map<String, Object> getLastException() {
    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not available");
    }

    String lastException = appender.getLastException();

    if (lastException == null) {
      return Map.of("status", "success", "found", false, "message", "No exceptions in log buffer");
    }

    // Parse the exception to extract key information
    String[] lines = lastException.split("\n");

    // Try to extract exception class and message
    String exceptionClass = "Unknown";
    String exceptionMessage = "";

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.contains("Exception:") || line.contains("Error:")) {
        // Found the actual exception line
        int colonIndex = line.indexOf(':');
        if (colonIndex > 0) {
          String beforeColon = line.substring(0, colonIndex);
          // Extract class name (last word before colon)
          String[] parts = beforeColon.split("\\s+");
          if (parts.length > 0) {
            exceptionClass = parts[parts.length - 1];
          }
          if (colonIndex < line.length() - 1) {
            exceptionMessage = line.substring(colonIndex + 1).trim();
          }
        }
        break;
      }
    }

    return Map.of("status", "success", "found", true, "exceptionClass", exceptionClass, "message", exceptionMessage,
        "fullText", lastException, "timestamp", lines[0] // Usually contains timestamp
    );
  }

  /**
   * Clear all exceptions from the log buffer.
   * 
   * @return Map containing operation status
   */
  public Map<String, Object> clearExceptions() {
    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not available");
    }

    int countBefore = appender.getExceptionBuffer().size();
    appender.clearExceptionBuffer();

    return Map.of("status", "success", "clearedCount", countBefore, "message",
        String.format("Cleared %d exception(s) from buffer", countBefore));
  }

  /**
   * Get statistics about exceptions in the buffer.
   * 
   * @return Map containing exception statistics
   */
  public Map<String, Object> getExceptionStats() {
    InMemoryAppender appender = InMemoryAppender.getInstance();
    if (appender == null) {
      return Map.of("status", "error", "message", "InMemoryAppender not available");
    }

    List<String> exceptions = appender.getExceptionBuffer();

    // Count exception types
    Map<String, Integer> exceptionTypes = new java.util.HashMap<>();
    for (String exception : exceptions) {
      String type = extractExceptionType(exception);
      exceptionTypes.put(type, exceptionTypes.getOrDefault(type, 0) + 1);
    }

    return Map.of("status", "success", "totalCount", exceptions.size(), "maxBufferSize",
        appender.getMaxExceptionBufferSize(), "truncateBackTo", appender.getTruncateExceptionBackTo(), "exceptionTypes",
        exceptionTypes);
  }

  private String extractExceptionType(String exceptionText) {
    String[] lines = exceptionText.split("\n");
    for (String line : lines) {
      if (line.contains("Exception:") || line.contains("Error:")) {
        // Try to extract the exception class name
        int colonIndex = line.indexOf(':');
        if (colonIndex > 0) {
          String beforeColon = line.substring(0, colonIndex);
          String[] parts = beforeColon.split("\\s+");
          if (parts.length > 0) {
            String className = parts[parts.length - 1];
            // Get simple name if it's a full class name
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < className.length() - 1) {
              return className.substring(lastDot + 1);
            }
            return className;
          }
        }
      }
      // Also check for patterns like "java.lang.NullPointerException"
      if (line.trim().startsWith("java.") || line.trim().startsWith("javax.") || line.trim().startsWith("com.")) {
        String[] parts = line.trim().split(":");
        if (parts.length > 0) {
          String className = parts[0].trim();
          int lastDot = className.lastIndexOf('.');
          if (lastDot >= 0 && lastDot < className.length() - 1) {
            return className.substring(lastDot + 1);
          }
        }
      }
    }
    return "Unknown";
  }
}