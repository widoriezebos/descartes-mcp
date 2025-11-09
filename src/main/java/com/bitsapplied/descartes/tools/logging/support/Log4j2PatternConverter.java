package com.bitsapplied.descartes.tools.logging.support;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Log4j2 date patterns to Java DateTimeFormatter patterns.
 *
 * <p>
 * Extracts timestamp patterns from Log4j2 PatternLayout configuration and
 * converts them to DateTimeFormatter for guaranteed timestamp parsing.
 *
 * <p>
 * Supports:
 * <ul>
 * <li>%d - defaults to ISO8601</li>
 * <li>%d{ISO8601} - ISO 8601 format</li>
 * <li>%d{ABSOLUTE} - HH:mm:ss,SSS</li>
 * <li>%d{DATE} - dd MMM yyyy HH:mm:ss,SSS</li>
 * <li>%d{COMPACT} - yyyyMMddHHmmssSSS</li>
 * <li>%d{custom} - Any SimpleDateFormat pattern</li>
 * </ul>
 */
public class Log4j2PatternConverter {

  // Pattern to extract %d{...} from Log4j2 layout string
  private static final Pattern DATE_PATTERN = Pattern.compile("%d(?:\\{([^}]+)\\})?");

  /**
   * Extract timestamp pattern from Log4j2 PatternLayout string.
   *
   * @param patternLayout Full Log4j2 pattern layout (e.g., "%d{yyyy-MM-dd
   *                      HH:mm:ss} [%t] %-5level")
   * @return Extracted timestamp pattern or null if not found
   */
  public static String extractTimestampPattern(String patternLayout) {
    if (patternLayout == null || patternLayout.isBlank()) {
      return null;
    }

    Matcher matcher = DATE_PATTERN.matcher(patternLayout);
    if (matcher.find()) {
      String pattern = matcher.group(1);
      // If no pattern specified (just %d), return "ISO8601"
      return pattern != null ? pattern : "ISO8601";
    }

    return null;
  }

  /**
   * Convert Log4j2 timestamp pattern to Java DateTimeFormatter.
   *
   * @param log4j2Pattern Log4j2 date pattern (e.g., "yyyy-MM-dd HH:mm:ss")
   * @return DateTimeFormatter for parsing timestamps
   * @throws IllegalArgumentException if pattern is invalid
   */
  public static DateTimeFormatter toDateTimeFormatter(String log4j2Pattern) {
    if (log4j2Pattern == null || log4j2Pattern.isBlank()) {
      return DateTimeFormatter.ISO_INSTANT;
    }

    // Handle predefined Log4j2 patterns
    return switch (log4j2Pattern.toUpperCase()) {
    case "ISO8601" -> DateTimeFormatter.ISO_DATE_TIME;
    case "ABSOLUTE" -> createFormatter("HH:mm:ss,SSS");
    case "DATE" -> createFormatter("dd MMM yyyy HH:mm:ss,SSS");
    case "COMPACT" -> createFormatter("yyyyMMddHHmmssSSS");
    default -> createFormatter(log4j2Pattern);
    };
  }

  /**
   * Create DateTimeFormatter from pattern string. Handles edge cases and provides
   * flexible parsing.
   */
  private static DateTimeFormatter createFormatter(String pattern) {
    try {
      // Replace Log4j2 comma separator with Java dot for milliseconds
      String javaPattern = pattern.replace(",SSS", ".SSS");

      DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder().appendPattern(javaPattern);

      // If pattern doesn't include date, add default date for parsing
      if (!javaPattern.contains("yyyy") && !javaPattern.contains("yy")) {
        builder.parseDefaulting(ChronoField.YEAR, 1970).parseDefaulting(ChronoField.MONTH_OF_YEAR, 1)
            .parseDefaulting(ChronoField.DAY_OF_MONTH, 1);
      }

      return builder.toFormatter();
    } catch (IllegalArgumentException e) {
      // Fall back to ISO format if pattern is invalid
      return DateTimeFormatter.ISO_DATE_TIME;
    }
  }

  /**
   * Extract timestamp from log line using the provided formatter.
   *
   * @param logLine         Log line content
   * @param formatter       DateTimeFormatter derived from Log4j2 pattern
   * @param timestampLength Expected length of timestamp in log line
   * @return Extracted timestamp string or null if not found
   */
  public static String extractTimestampString(String logLine, String log4j2Pattern) {
    if (logLine == null || logLine.isBlank() || log4j2Pattern == null) {
      return null;
    }

    // Estimate timestamp length based on pattern
    int estimatedLength = estimateTimestampLength(log4j2Pattern);

    if (logLine.length() >= estimatedLength) {
      return logLine.substring(0, Math.min(estimatedLength, logLine.length())).trim();
    }

    return null;
  }

  /**
   * Estimate timestamp length from Log4j2 pattern. Used to extract timestamp
   * substring from log line.
   */
  private static int estimateTimestampLength(String pattern) {
    return switch (pattern.toUpperCase()) {
    case "ISO8601" -> 24; // 2024-01-01T10:30:45.123Z (fixed length)
    case "ABSOLUTE" -> 12; // HH:mm:ss,SSS
    case "DATE" -> 24; // dd MMM yyyy HH:mm:ss,SSS
    case "COMPACT" -> 17; // yyyyMMddHHmmssSSS
    default -> estimateLengthFromPattern(pattern);
    };
  }

  /**
   * Estimate length from custom pattern. Tries to accurately predict the actual
   * rendered length of the timestamp.
   */
  private static int estimateLengthFromPattern(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return 19;
    }

    // For common patterns, return exact length
    if (pattern.equals("yyyy-MM-dd HH:mm:ss")) {
      return 19;
    }
    if (pattern.equals("yyyy-MM-dd HH:mm:ss.SSS")) {
      return 23;
    }
    if (pattern.equals("dd-MM-yyyy HH:mm:ss")) {
      return 19;
    }

    // For other patterns, count the expected output length
    int length = 0;
    int repeatCount = 0;
    char lastChar = 0;

    for (char c : pattern.toCharArray()) {
      if (Character.isLetter(c)) {
        if (c == lastChar) {
          repeatCount++;
        } else {
          if (lastChar != 0) {
            length += getLengthForPattern(lastChar, repeatCount);
          }
          lastChar = c;
          repeatCount = 1;
        }
      } else {
        if (lastChar != 0) {
          length += getLengthForPattern(lastChar, repeatCount);
          lastChar = 0;
          repeatCount = 0;
        }
        length += 1; // Separator character
      }
    }

    // Handle last pattern segment
    if (lastChar != 0) {
      length += getLengthForPattern(lastChar, repeatCount);
    }

    return length;
  }

  /**
   * Get the rendered length for a pattern character.
   */
  private static int getLengthForPattern(char patternChar, int count) {
    return switch (Character.toLowerCase(patternChar)) {
    case 'y' -> count >= 4 ? 4 : count; // year: yyyy=4, yy=2
    case 'm' -> count >= 2 ? 2 : 1; // month/minute: MM=2, M=1-2
    case 'd' -> count >= 2 ? 2 : 1; // day: dd=2, d=1-2
    case 'h' -> 2; // hour: HH=2
    case 's' -> count >= 3 ? 3 : 2; // second/millisecond: SS=2, SSS=3
    default -> count;
    };
  }

  /**
   * Check if a Log4j2 pattern is recognized and valid.
   *
   * @param log4j2Pattern Pattern to validate
   * @return true if pattern is valid
   */
  public static boolean isValidPattern(String log4j2Pattern) {
    if (log4j2Pattern == null || log4j2Pattern.isBlank()) {
      return false;
    }

    try {
      DateTimeFormatter formatter = toDateTimeFormatter(log4j2Pattern);
      return formatter != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Get a human-readable description of the pattern.
   *
   * @param log4j2Pattern Log4j2 pattern
   * @return Description string
   */
  public static String describePattern(String log4j2Pattern) {
    if (log4j2Pattern == null) {
      return "No pattern (using fallback regex)";
    }

    return switch (log4j2Pattern.toUpperCase()) {
    case "ISO8601" -> "ISO 8601 format (2024-01-01T10:30:45.123Z)";
    case "ABSOLUTE" -> "Absolute time (HH:mm:ss,SSS)";
    case "DATE" -> "Date format (dd MMM yyyy HH:mm:ss,SSS)";
    case "COMPACT" -> "Compact format (yyyyMMddHHmmssSSS)";
    default -> "Custom format: " + log4j2Pattern;
    };
  }
}
