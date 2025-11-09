package com.bitsapplied.descartes.tools.logging.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

/**
 * Tests for Log4j2PatternConverter.
 */
class Log4j2PatternConverterTest {

  @Test
  void testExtractTimestampPattern_ISO8601() {
    String layout = "%d{ISO8601} [%t] %-5level %logger{36} - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("ISO8601", pattern);
  }

  @Test
  void testExtractTimestampPattern_NoPattern() {
    String layout = "%d [%t] %-5level %logger{36} - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("ISO8601", pattern); // Default when %d has no pattern
  }

  @Test
  void testExtractTimestampPattern_CustomPattern() {
    String layout = "%d{yyyy-MM-dd HH:mm:ss} [%t] %-5level %logger{36} - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("yyyy-MM-dd HH:mm:ss", pattern);
  }

  @Test
  void testExtractTimestampPattern_ABSOLUTE() {
    String layout = "%d{ABSOLUTE} [%t] %-5level %logger - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("ABSOLUTE", pattern);
  }

  @Test
  void testExtractTimestampPattern_DATE() {
    String layout = "%d{DATE} %-5level - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("DATE", pattern);
  }

  @Test
  void testExtractTimestampPattern_COMPACT() {
    String layout = "%d{COMPACT} %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("COMPACT", pattern);
  }

  @Test
  void testExtractTimestampPattern_NotFound() {
    String layout = "[%t] %-5level %logger{36} - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertNull(pattern);
  }

  @Test
  void testExtractTimestampPattern_NullOrBlank() {
    assertNull(Log4j2PatternConverter.extractTimestampPattern(null));
    assertNull(Log4j2PatternConverter.extractTimestampPattern(""));
    assertNull(Log4j2PatternConverter.extractTimestampPattern("   "));
  }

  @Test
  void testToDateTimeFormatter_ISO8601() {
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter("ISO8601");
    assertNotNull(formatter);
    assertEquals(DateTimeFormatter.ISO_DATE_TIME, formatter);
  }

  @Test
  void testToDateTimeFormatter_ABSOLUTE() {
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter("ABSOLUTE");
    assertNotNull(formatter);

    // Test parsing HH:mm:ss,SSS format (comma replaced with dot)
    String timestamp = "10:30:45.123";
    assertDoesNotThrow(() -> formatter.parse(timestamp));
  }

  @Test
  void testToDateTimeFormatter_DATE() {
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter("DATE");
    assertNotNull(formatter);

    // Test parsing dd MMM yyyy HH:mm:ss,SSS format
    String timestamp = "01 Jan 2024 10:30:45.123";
    assertDoesNotThrow(() -> formatter.parse(timestamp));
  }

  @Test
  void testToDateTimeFormatter_COMPACT() {
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter("COMPACT");
    assertNotNull(formatter);

    // Test parsing yyyyMMddHHmmssSSS format
    String timestamp = "20240101103045123";
    assertDoesNotThrow(() -> formatter.parse(timestamp));
  }

  @Test
  void testToDateTimeFormatter_CustomPattern() {
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter("yyyy-MM-dd HH:mm:ss");
    assertNotNull(formatter);

    // Test parsing custom format
    String timestamp = "2024-01-01 10:30:45";
    assertDoesNotThrow(() -> formatter.parse(timestamp));
  }

  @Test
  void testToDateTimeFormatter_NullOrBlank() {
    DateTimeFormatter formatter1 = Log4j2PatternConverter.toDateTimeFormatter(null);
    assertNotNull(formatter1);
    assertEquals(DateTimeFormatter.ISO_INSTANT, formatter1);

    DateTimeFormatter formatter2 = Log4j2PatternConverter.toDateTimeFormatter("");
    assertNotNull(formatter2);
    assertEquals(DateTimeFormatter.ISO_INSTANT, formatter2);
  }

  @Test
  void testToDateTimeFormatter_InvalidPattern() {
    // Invalid pattern should fall back to ISO_DATE_TIME
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter("invalid{pattern");
    assertNotNull(formatter);
    assertEquals(DateTimeFormatter.ISO_DATE_TIME, formatter);
  }

  @Test
  void testExtractTimestampString_ISO8601() {
    String logLine = "2024-01-01T10:30:45.123Z [main] INFO  com.example.Test - Message";
    String pattern = "ISO8601";

    String timestamp = Log4j2PatternConverter.extractTimestampString(logLine, pattern);
    assertNotNull(timestamp);
    assertTrue(timestamp.contains("2024-01-01"));
  }

  @Test
  void testExtractTimestampString_CustomPattern() {
    String logLine = "2024-01-01 10:30:45 [main] INFO  com.example.Test - Message";
    String pattern = "yyyy-MM-dd HH:mm:ss";

    String timestamp = Log4j2PatternConverter.extractTimestampString(logLine, pattern);
    assertNotNull(timestamp);
    assertEquals("2024-01-01 10:30:45", timestamp.trim());
  }

  @Test
  void testExtractTimestampString_ABSOLUTE() {
    String logLine = "10:30:45,123 INFO  com.example.Test - Message";
    String pattern = "ABSOLUTE";

    String timestamp = Log4j2PatternConverter.extractTimestampString(logLine, pattern);
    assertNotNull(timestamp);
    assertTrue(timestamp.contains("10:30:45"));
  }

  @Test
  void testExtractTimestampString_NullOrBlank() {
    assertNull(Log4j2PatternConverter.extractTimestampString(null, "ISO8601"));
    assertNull(Log4j2PatternConverter.extractTimestampString("", "ISO8601"));
    assertNull(Log4j2PatternConverter.extractTimestampString("logline", null));
  }

  @Test
  void testIsValidPattern_ValidPatterns() {
    assertTrue(Log4j2PatternConverter.isValidPattern("ISO8601"));
    assertTrue(Log4j2PatternConverter.isValidPattern("ABSOLUTE"));
    assertTrue(Log4j2PatternConverter.isValidPattern("DATE"));
    assertTrue(Log4j2PatternConverter.isValidPattern("COMPACT"));
    assertTrue(Log4j2PatternConverter.isValidPattern("yyyy-MM-dd HH:mm:ss"));
  }

  @Test
  void testIsValidPattern_InvalidPatterns() {
    assertFalse(Log4j2PatternConverter.isValidPattern(null));
    assertFalse(Log4j2PatternConverter.isValidPattern(""));
    assertFalse(Log4j2PatternConverter.isValidPattern("   "));
  }

  @Test
  void testDescribePattern_PredefinedPatterns() {
    assertEquals("ISO 8601 format (2024-01-01T10:30:45.123Z)", Log4j2PatternConverter.describePattern("ISO8601"));
    assertEquals("Absolute time (HH:mm:ss,SSS)", Log4j2PatternConverter.describePattern("ABSOLUTE"));
    assertEquals("Date format (dd MMM yyyy HH:mm:ss,SSS)", Log4j2PatternConverter.describePattern("DATE"));
    assertEquals("Compact format (yyyyMMddHHmmssSSS)", Log4j2PatternConverter.describePattern("COMPACT"));
  }

  @Test
  void testDescribePattern_CustomPattern() {
    String pattern = "yyyy-MM-dd HH:mm:ss";
    String description = Log4j2PatternConverter.describePattern(pattern);
    assertEquals("Custom format: " + pattern, description);
  }

  @Test
  void testDescribePattern_Null() {
    String description = Log4j2PatternConverter.describePattern(null);
    assertEquals("No pattern (using fallback regex)", description);
  }

  @Test
  void testEndToEnd_ISO8601() {
    // Extract pattern from layout
    String layout = "%d{ISO8601} [%t] %-5level %logger - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("ISO8601", pattern);

    // Convert to formatter
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter(pattern);
    assertNotNull(formatter);

    // Parse log line
    String logLine = "2024-01-01T10:30:45.123Z [main] INFO  com.example.Test - Message";
    String timestampStr = Log4j2PatternConverter.extractTimestampString(logLine, pattern);
    assertNotNull(timestampStr);

    // Verify we can parse it
    Instant instant = Instant.parse(timestampStr.trim());
    assertNotNull(instant);
  }

  @Test
  void testEndToEnd_CustomPattern() {
    // Extract pattern from layout
    String layout = "%d{yyyy-MM-dd HH:mm:ss} [%t] %-5level %logger - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("yyyy-MM-dd HH:mm:ss", pattern);

    // Convert to formatter
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter(pattern);
    assertNotNull(formatter);

    // Parse log line
    String logLine = "2024-01-01 10:30:45 [main] INFO  com.example.Test - Message";
    String timestampStr = Log4j2PatternConverter.extractTimestampString(logLine, pattern);
    assertNotNull(timestampStr);
    assertEquals("2024-01-01 10:30:45", timestampStr.trim());

    // Verify we can parse it
    LocalDateTime ldt = LocalDateTime.parse(timestampStr.trim(), formatter);
    assertNotNull(ldt);
  }

  @Test
  void testEndToEnd_ABSOLUTE() {
    // Extract pattern from layout
    String layout = "%d{ABSOLUTE} %-5level - %msg%n";
    String pattern = Log4j2PatternConverter.extractTimestampPattern(layout);
    assertEquals("ABSOLUTE", pattern);

    // Convert to formatter
    DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter(pattern);
    assertNotNull(formatter);

    // Parse log line (note: comma in log becomes dot in Java pattern)
    String logLine = "10:30:45,123 INFO  - Message";
    String timestampStr = Log4j2PatternConverter.extractTimestampString(logLine, pattern);
    assertNotNull(timestampStr);

    // Verify we can parse it (replace comma with dot for Java formatter)
    String normalized = timestampStr.trim().replace(",", ".");
    assertDoesNotThrow(() -> formatter.parse(normalized));
  }

  @Test
  void testCaseInsensitivePatterns() {
    // Patterns should be case-insensitive
    assertEquals("ISO 8601 format (2024-01-01T10:30:45.123Z)", Log4j2PatternConverter.describePattern("iso8601"));
    assertEquals("Absolute time (HH:mm:ss,SSS)", Log4j2PatternConverter.describePattern("absolute"));
    assertEquals("Date format (dd MMM yyyy HH:mm:ss,SSS)", Log4j2PatternConverter.describePattern("date"));
    assertEquals("Compact format (yyyyMMddHHmmssSSS)", Log4j2PatternConverter.describePattern("compact"));
  }
}
