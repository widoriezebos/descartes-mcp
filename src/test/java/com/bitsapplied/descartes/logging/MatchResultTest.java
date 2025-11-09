package com.bitsapplied.descartes.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MatchResultTest {

  @Test
  void testBasicConstruction() {
    MatchResult match = MatchResult.of(42, "ERROR: Something went wrong");

    assertEquals(42, match.lineNumber());
    assertEquals("ERROR: Something went wrong", match.content());
    assertNull(match.timestamp());
    assertNull(match.level());
    assertNull(match.logger());
    assertTrue(match.contextBefore().isEmpty());
    assertTrue(match.contextAfter().isEmpty());
  }

  @Test
  void testConstructionWithMetadata() {
    Instant now = Instant.now();
    MatchResult match = MatchResult.of(100, "WARN: Low memory", now, "WARN", "com.example.MemoryMonitor");

    assertEquals(100, match.lineNumber());
    assertEquals("WARN: Low memory", match.content());
    assertEquals(now, match.timestamp());
    assertEquals("WARN", match.level());
    assertEquals("com.example.MemoryMonitor", match.logger());
  }

  @Test
  void testMatchWithContext() {
    List<String> before = List.of("line before 1", "line before 2");
    List<String> after = List.of("line after 1");

    MatchResult match = new MatchResult(12345, "ERROR: NullPointerException", null, "ERROR", null, before, after);

    assertEquals(12345, match.lineNumber());
    assertEquals("ERROR: NullPointerException", match.content());
    assertEquals(2, match.contextBefore().size());
    assertEquals(1, match.contextAfter().size());
    assertEquals("line before 1", match.contextBefore().get(0));
    assertEquals("line after 1", match.contextAfter().get(0));
  }

  @Test
  void testDefensiveCopyOfLists() {
    List<String> before = new ArrayList<>();
    before.add("original");

    MatchResult match = new MatchResult(1, "test", null, null, null, before, null);

    // Modify original list
    before.add("modified");

    // Match should still have only one element
    assertEquals(1, match.contextBefore().size());
    assertEquals("original", match.contextBefore().get(0));
  }

  @Test
  void testNullContextListsConvertedToEmpty() {
    MatchResult match = new MatchResult(1, "test", null, null, null, null, null);

    assertNotNull(match.contextBefore());
    assertNotNull(match.contextAfter());
    assertTrue(match.contextBefore().isEmpty());
    assertTrue(match.contextAfter().isEmpty());
  }

  @Test
  void testInvalidLineNumber() {
    assertThrows(IllegalArgumentException.class, () -> new MatchResult(-1, "test", null, null, null, null, null));
  }

  @Test
  void testNullContentThrows() {
    assertThrows(IllegalArgumentException.class, () -> new MatchResult(1, null, null, null, null, null, null));
  }

  @Test
  void testToStringWithShortContent() {
    MatchResult match = MatchResult.of(10, "Short line");
    String str = match.toString();

    assertTrue(str.contains("lineNumber=10"));
    assertTrue(str.contains("Short line"));
    assertFalse(str.contains("..."));
  }

  @Test
  void testToStringWithLongContent() {
    String longContent = "A".repeat(150);
    MatchResult match = MatchResult.of(20, longContent);
    String str = match.toString();

    assertTrue(str.contains("lineNumber=20"));
    assertTrue(str.contains("..."));
    assertTrue(str.length() < longContent.length() + 100); // Truncated
  }

  @Test
  void testToStringIncludesMetadata() {
    Instant now = Instant.now();
    List<String> before = List.of("1", "2", "3");
    List<String> after = List.of("4");

    MatchResult match = new MatchResult(50, "test content", now, "ERROR", "com.example.Logger", before, after);

    String str = match.toString();
    assertTrue(str.contains("lineNumber=50"));
    assertTrue(str.contains("level=ERROR"));
    assertTrue(str.contains("logger=com.example.Logger"));
    assertTrue(str.contains("contextBefore=3 lines"));
    assertTrue(str.contains("contextAfter=1 lines"));
  }
}
