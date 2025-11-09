package com.bitsapplied.descartes.logging;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses log lines to extract structured information like timestamps, levels,
 * and logger names.
 *
 * Supports two parsing modes: 1. Guaranteed parsing: When Log4j2 timestamp
 * pattern is available, extracts exact timestamp 2. Fallback parsing: Uses
 * regex patterns to guess timestamp format
 *
 * Supports common log formats: - ISO 8601: "2024-01-01T10:00:00Z" - Custom:
 * "01-01-2024 10:00:00" - Log4j pattern: "%d{ISO8601} [%t] %-5level %logger{36}
 * - %msg%n"
 */
public class LogLineParser {

  // Common timestamp patterns
  private static final Pattern ISO8601_PATTERN = Pattern
      .compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?)");

  private static final Pattern CUSTOM_PATTERN = Pattern.compile("(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2})");

  // Log level pattern
  private static final Pattern LEVEL_PATTERN = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\b");

  // Logger name pattern (Java package notation)
  private static final Pattern LOGGER_PATTERN = Pattern.compile("\\b([a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+)\\b");

  /**
   * Parse timestamp from log line using guaranteed Log4j2 pattern and formatter.
   *
   * @param line          Log line
   * @param log4j2Pattern Log4j2 timestamp pattern (e.g., "yyyy-MM-dd HH:mm:ss")
   * @param formatter     DateTimeFormatter for parsing
   * @return Parsed Instant or null if parsing fails
   */
  public static Instant parseTimestamp(String line, String log4j2Pattern, DateTimeFormatter formatter) {
    if (line == null || line.isBlank() || log4j2Pattern == null || formatter == null) {
      return parseTimestamp(line); // Fall back to regex
    }

    try {
      // Extract timestamp substring from beginning of line
      String timestampStr = Log4j2PatternConverter.extractTimestampString(line, log4j2Pattern);
      if (timestampStr == null) {
        return parseTimestamp(line); // Fall back to regex
      }

      // Parse using the guaranteed formatter
      TemporalAccessor temporal = formatter.parse(timestampStr);

      // Try to extract Instant directly
      try {
        return Instant.from(temporal);
      } catch (DateTimeParseException e) {
        // Pattern doesn't include timezone, convert from LocalDateTime
        LocalDateTime ldt = LocalDateTime.from(temporal);
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
      }
    } catch (Exception e) {
      // Guaranteed parsing failed, fall back to regex
      return parseTimestamp(line);
    }
  }

  /**
   * Parse timestamp from log line using regex patterns (fallback method).
   *
   * @param line Log line
   * @return Parsed Instant or null if not found
   */
  public static Instant parseTimestamp(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }

    // Try ISO 8601 first
    Matcher iso = ISO8601_PATTERN.matcher(line);
    if (iso.find()) {
      try {
        return Instant.parse(iso.group(1));
      } catch (DateTimeParseException e) {
        // Fall through to next pattern
      }
    }

    // Try custom format
    Matcher custom = CUSTOM_PATTERN.matcher(line);
    if (custom.find()) {
      try {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        return LocalDateTime.parse(custom.group(1), formatter).atZone(ZoneId.systemDefault()).toInstant();
      } catch (DateTimeParseException e) {
        // Fall through
      }
    }

    return null;
  }

  /**
   * Parse log level from log line.
   *
   * @param line Log line
   * @return Log level or null if not found
   */
  public static String parseLevel(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }

    Matcher matcher = LEVEL_PATTERN.matcher(line);
    if (matcher.find()) {
      return matcher.group(1);
    }

    return null;
  }

  /**
   * Parse logger name from log line.
   *
   * @param line Log line
   * @return Logger name or null if not found
   */
  public static String parseLogger(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }

    Matcher matcher = LOGGER_PATTERN.matcher(line);
    if (matcher.find()) {
      return matcher.group(1);
    }

    return null;
  }

  /**
   * Parse all structured data from a log line using guaranteed timestamp parsing.
   *
   * @param line          Log line
   * @param log4j2Pattern Log4j2 timestamp pattern
   * @param formatter     DateTimeFormatter for guaranteed parsing
   * @return ParsedLogLine with extracted data
   */
  public static ParsedLogLine parse(String line, String log4j2Pattern, DateTimeFormatter formatter) {
    return new ParsedLogLine(parseTimestamp(line, log4j2Pattern, formatter), parseLevel(line), parseLogger(line), line);
  }

  /**
   * Parse all structured data from a log line using regex fallback.
   *
   * @param line Log line
   * @return ParsedLogLine with extracted data
   */
  public static ParsedLogLine parse(String line) {
    return new ParsedLogLine(parseTimestamp(line), parseLevel(line), parseLogger(line), line);
  }

  /**
   * Parsed log line data.
   */
  public record ParsedLogLine(Instant timestamp, String level, String logger, String originalLine) {
    /**
     * Check if this line matches a time range.
     */
    public boolean inTimeRange(Instant start, Instant end) {
      if (timestamp == null) {
        return false;
      }
      return !timestamp.isBefore(start) && !timestamp.isAfter(end);
    }

    /**
     * Check if this line matches a level filter. Level hierarchy: ERROR > WARN >
     * INFO > DEBUG > TRACE
     */
    public boolean matchesLevel(String levelFilter) {
      if (level == null || levelFilter == null) {
        return true;
      }
      return getLevelPriority(level) >= getLevelPriority(levelFilter);
    }

    private int getLevelPriority(String level) {
      return switch (level.toUpperCase()) {
      case "FATAL", "ERROR" -> 5;
      case "WARN" -> 4;
      case "INFO" -> 3;
      case "DEBUG" -> 2;
      case "TRACE" -> 1;
      default -> 0;
      };
    }
  }
}
