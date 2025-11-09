package com.bitsapplied.descartes.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class SearchParamsTest {

  @Test
  void testGrepBuilder() {
    SearchParams params = SearchParams.builder().operation("grep").filePath("/var/log/app/error.log")
        .pattern("NullPointerException").caseInsensitive(true).contextBefore(3).contextAfter(3).maxResults(1000)
        .build();

    assertEquals("grep", params.operation());
    assertEquals("/var/log/app/error.log", params.filePath());
    assertEquals("NullPointerException", params.pattern());
    assertTrue(params.caseInsensitive());
    assertEquals(3, params.contextBefore());
    assertEquals(3, params.contextAfter());
    assertEquals(1000, params.maxResults());
  }

  @Test
  void testTailBuilder() {
    SearchParams params = SearchParams.builder().operation("tail").filePath("/var/log/app.log").lines(100).build();

    assertEquals("tail", params.operation());
    assertEquals(100, params.lines());
  }

  @Test
  void testHeadBuilder() {
    SearchParams params = SearchParams.builder().operation("head").filePath("/var/log/app.log").lines(50).build();

    assertEquals("head", params.operation());
    assertEquals(50, params.lines());
  }

  @Test
  void testRangeBuilder() {
    SearchParams params = SearchParams.builder().operation("range").filePath("/var/log/app.log").startLine(100)
        .endLine(200).build();

    assertEquals("range", params.operation());
    assertEquals(100, params.startLine());
    assertEquals(200, params.endLine());
  }

  @Test
  void testTimeRangeBuilder() {
    Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant end = Instant.now();

    SearchParams params = SearchParams.builder().operation("time_range").filePath("/var/log/app.log").startTime(start)
        .endTime(end).build();

    assertEquals("time_range", params.operation());
    assertEquals(start, params.startTime());
    assertEquals(end, params.endTime());
  }

  @Test
  void testBuilderDefaults() {
    SearchParams params = SearchParams.builder().operation("grep").filePath("/test.log").pattern("test").build();

    assertFalse(params.caseInsensitive());
    assertEquals(0, params.contextBefore());
    assertEquals(0, params.contextAfter());
    assertEquals(1000, params.maxResults());
    assertTrue(params.includeLineNumbers());
  }

  @Test
  void testLevelFilter() {
    SearchParams params = SearchParams.builder().operation("grep").filePath("/test.log").pattern("error")
        .levelFilter("ERROR").build();

    assertEquals("ERROR", params.levelFilter());
  }

  @Test
  void testIncludeLineNumbers() {
    SearchParams params = SearchParams.builder().operation("grep").filePath("/test.log").pattern("test")
        .includeLineNumbers(false).build();

    assertFalse(params.includeLineNumbers());
  }

  // Validation tests

  @Test
  void testNullOperationThrows() {
    assertThrows(IllegalArgumentException.class, () -> SearchParams.builder().filePath("/test.log").build());
  }

  @Test
  void testBlankOperationThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("  ").filePath("/test.log").build());
  }

  @Test
  void testNullFilePathThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").pattern("test").build());
  }

  @Test
  void testNegativeContextBeforeThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").filePath("/test.log").pattern("test").contextBefore(-1).build());
  }

  @Test
  void testNegativeContextAfterThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").filePath("/test.log").pattern("test").contextAfter(-1).build());
  }

  @Test
  void testZeroMaxResultsThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").filePath("/test.log").pattern("test").maxResults(0).build());
  }

  @Test
  void testNegativeMaxResultsThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").filePath("/test.log").pattern("test").maxResults(-100).build());
  }

  // Operation-specific validation

  @Test
  void testGrepRequiresPattern() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").filePath("/test.log").build());
  }

  @Test
  void testGrepRequiresNonBlankPattern() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("grep").filePath("/test.log").pattern("  ").build());
  }

  @Test
  void testTailRequiresLines() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("tail").filePath("/test.log").build());
  }

  @Test
  void testTailRequiresPositiveLines() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("tail").filePath("/test.log").lines(0).build());
  }

  @Test
  void testHeadRequiresLines() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("head").filePath("/test.log").build());
  }

  @Test
  void testRangeRequiresStartLine() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("range").filePath("/test.log").endLine(100).build());
  }

  @Test
  void testRangeRequiresEndLine() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("range").filePath("/test.log").startLine(1).build());
  }

  @Test
  void testRangeStartLineMustBePositive() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("range").filePath("/test.log").startLine(0).endLine(100).build());
  }

  @Test
  void testRangeEndLineMustBeGreaterThanStart() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("range").filePath("/test.log").startLine(100).endLine(50).build());
  }

  @Test
  void testTimeRangeRequiresStartTime() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("time_range").filePath("/test.log").endTime(Instant.now()).build());
  }

  @Test
  void testTimeRangeRequiresEndTime() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchParams.builder().operation("time_range").filePath("/test.log").startTime(Instant.now()).build());
  }

  @Test
  void testTimeRangeEndMustBeAfterStart() {
    Instant now = Instant.now();
    Instant past = now.minus(1, ChronoUnit.HOURS);

    assertThrows(IllegalArgumentException.class, () -> SearchParams.builder().operation("time_range")
        .filePath("/test.log").startTime(now).endTime(past).build());
  }
}
