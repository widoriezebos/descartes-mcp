package com.bitsapplied.descartes.logging;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Parses relative and absolute time specifications for log filtering.
 *
 * Supports: - Relative: "1h ago", "30m ago", "2d ago" - Named: "today",
 * "yesterday" - ISO 8601: "2024-01-01T10:00:00Z"
 */
public class TimeRangeParser {

  private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

  /**
   * Parse a time string into an Instant.
   *
   * @param timeStr Time string to parse
   * @return Parsed Instant
   * @throws IllegalArgumentException if format is invalid
   */
  public static Instant parse(String timeStr) {
    if (timeStr == null || timeStr.isBlank()) {
      throw new IllegalArgumentException("Time string cannot be null or blank");
    }

    String normalized = timeStr.trim().toLowerCase();

    // Relative time patterns
    if (normalized.endsWith(" ago")) {
      return parseRelative(normalized.substring(0, normalized.length() - 4).trim());
    }

    // Named patterns
    switch (normalized) {
    case "now":
      return Instant.now();
    case "today":
      return LocalDate.now().atStartOfDay(DEFAULT_ZONE).toInstant();
    case "yesterday":
      return LocalDate.now().minusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant();
    case "week ago":
      return Instant.now().minus(7, ChronoUnit.DAYS);
    case "month ago":
      return Instant.now().minus(30, ChronoUnit.DAYS);
    }

    // ISO 8601 format
    try {
      return Instant.parse(timeStr);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to parse time: " + timeStr, e);
    }
  }

  /**
   * Parse relative time like "1h", "30m", "2d".
   */
  private static Instant parseRelative(String relative) {
    if (relative.isEmpty()) {
      throw new IllegalArgumentException("Empty relative time");
    }

    // Extract number and unit
    int i = 0;
    while (i < relative.length() && (Character.isDigit(relative.charAt(i)) || relative.charAt(i) == '.')) {
      i++;
    }

    if (i == 0) {
      throw new IllegalArgumentException("No number found in relative time: " + relative);
    }

    String numberStr = relative.substring(0, i);
    String unit = relative.substring(i).trim();

    long amount;
    try {
      amount = Long.parseLong(numberStr);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid number in relative time: " + numberStr, e);
    }

    ChronoUnit chronoUnit = parseUnit(unit);
    return Instant.now().minus(amount, chronoUnit);
  }

  /**
   * Parse time unit abbreviation.
   */
  private static ChronoUnit parseUnit(String unit) {
    return switch (unit) {
    case "s", "sec", "second", "seconds" -> ChronoUnit.SECONDS;
    case "m", "min", "minute", "minutes" -> ChronoUnit.MINUTES;
    case "h", "hr", "hour", "hours" -> ChronoUnit.HOURS;
    case "d", "day", "days" -> ChronoUnit.DAYS;
    case "w", "week", "weeks" -> ChronoUnit.WEEKS;
    default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
    };
  }

  /**
   * Parse a time range specification like "last 1h" or "today".
   *
   * @param rangeSpec Range specification
   * @return Array of [startTime, endTime]
   */
  public static Instant[] parseRange(String rangeSpec) {
    String normalized = rangeSpec.trim().toLowerCase();

    if (normalized.startsWith("last ")) {
      String duration = normalized.substring(5).trim();
      Instant start = parse(duration + " ago");
      Instant end = Instant.now();
      return new Instant[] { start, end };
    }

    if (normalized.equals("today")) {
      Instant start = LocalDate.now().atStartOfDay(DEFAULT_ZONE).toInstant();
      Instant end = Instant.now();
      return new Instant[] { start, end };
    }

    if (normalized.equals("yesterday")) {
      LocalDate yesterday = LocalDate.now().minusDays(1);
      Instant start = yesterday.atStartOfDay(DEFAULT_ZONE).toInstant();
      Instant end = yesterday.atTime(LocalTime.MAX).atZone(DEFAULT_ZONE).toInstant();
      return new Instant[] { start, end };
    }

    throw new IllegalArgumentException("Unknown range spec: " + rangeSpec);
  }
}
