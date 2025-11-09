package com.bitsapplied.descartes.tools.logging.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SearchResultTest {

  @Test
  void testSuccessResultWithMatches() {
    MatchResult match1 = MatchResult.of(10, "ERROR: First error");
    MatchResult match2 = MatchResult.of(20, "ERROR: Second error");
    List<MatchResult> matches = List.of(match1, match2);

    SearchResult result = SearchResult.success("grep", "/var/log/app.log", 1024L, 100, matches, 50L, false);

    assertEquals("success", result.status());
    assertEquals("grep", result.operation());
    assertEquals("/var/log/app.log", result.filePath());
    assertEquals(1024L, result.fileSizeBytes());
    assertEquals(100, result.linesSearched());
    assertEquals(2, result.matchesFound());
    assertEquals(50L, result.searchTimeMs());
    assertEquals(2, result.matches().size());
    assertNotNull(result.lines());
    assertTrue(result.lines().isEmpty());
    assertFalse(result.truncated());
    assertNull(result.errorMessage());
  }

  @Test
  void testSuccessResultWithLines() {
    List<String> lines = List.of("line 1", "line 2", "line 3");

    SearchResult result = SearchResult.successLines("tail", "/var/log/app.log", 2048L, 3, lines, 10L);

    assertEquals("success", result.status());
    assertEquals("tail", result.operation());
    assertEquals(3, result.matchesFound());
    assertEquals(3, result.lines().size());
    assertNotNull(result.matches());
    assertTrue(result.matches().isEmpty());
    assertFalse(result.truncated());
  }

  @Test
  void testTruncatedResult() {
    List<MatchResult> matches = List.of(MatchResult.of(1, "match"));

    SearchResult result = SearchResult.success("grep", "/var/log/app.log", 1000L, 10000, matches, 100L, true);

    assertTrue(result.truncated());
  }

  @Test
  void testNoMatchesResult() {
    SearchResult result = SearchResult.noMatches("grep", "/var/log/app.log", 512L, 50, 25L);

    assertEquals("no_matches", result.status());
    assertEquals(0, result.matchesFound());
    assertTrue(result.matches().isEmpty());
    assertTrue(result.lines().isEmpty());
    assertFalse(result.truncated());
    assertNull(result.errorMessage());
  }

  @Test
  void testErrorResult() {
    SearchResult result = SearchResult.error("grep", "/var/log/app.log", "File not found");

    assertEquals("error", result.status());
    assertEquals("File not found", result.errorMessage());
    assertEquals(0, result.linesSearched());
    assertEquals(0, result.matchesFound());
    assertNull(result.fileSizeBytes());
  }

  @Test
  void testDefensiveCopyOfMatches() {
    List<MatchResult> matches = new ArrayList<>();
    matches.add(MatchResult.of(1, "test"));

    SearchResult result = SearchResult.success("grep", "/test.log", 100L, 10, matches, 5L, false);

    // Modify original list
    matches.add(MatchResult.of(2, "test2"));

    // Result should still have only one match
    assertEquals(1, result.matches().size());
  }

  @Test
  void testDefensiveCopyOfLines() {
    List<String> lines = new ArrayList<>();
    lines.add("line1");

    SearchResult result = SearchResult.successLines("tail", "/test.log", 100L, 1, lines, 5L);

    // Modify original list
    lines.add("line2");

    // Result should still have only one line
    assertEquals(1, result.lines().size());
  }

  @Test
  void testNullStatusThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchResult.builder().status(null).operation("grep").filePath("/test.log").build());
  }

  @Test
  void testBlankStatusThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchResult.builder().status("  ").operation("grep").filePath("/test.log").build());
  }

  @Test
  void testNullOperationThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchResult.builder().status("success").operation(null).filePath("/test.log").build());
  }

  @Test
  void testToStringContainsKeyInfo() {
    SearchResult result = SearchResult.success("grep", "/var/log/app.log", 1024L, 100,
        List.of(MatchResult.of(1, "test")), 50L, true);

    String str = result.toString();
    assertTrue(str.contains("status='success'"));
    assertTrue(str.contains("operation='grep'"));
    assertTrue(str.contains("filePath='/var/log/app.log'"));
    assertTrue(str.contains("fileSizeBytes=1024"));
    assertTrue(str.contains("linesSearched=100"));
    assertTrue(str.contains("matchesFound=1"));
    assertTrue(str.contains("searchTimeMs=50"));
    assertTrue(str.contains("truncated=true"));
  }

  @Test
  void testToStringWithError() {
    SearchResult result = SearchResult.error("grep", "/missing.log", "File not found");

    String str = result.toString();
    assertTrue(str.contains("status='error'"));
    assertTrue(str.contains("errorMessage='File not found'"));
  }
}
