package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;

/**
 * Comprehensive tests for EvalResult and SnippetResult.
 */
public class EvalResultTest {

  private ObjectMapper objectMapper;
  private Instant startTime;
  private Instant endTime;

  @BeforeEach
  public void setUp() {
    objectMapper = new ObjectMapper();
    startTime = Instant.now();
    endTime = startTime.plusMillis(100);
  }

  @Test
  public void testSnippetResultConstructorAndGetters() {
    EvalResult.SnippetResult result = new EvalResult.SnippetResult("int x = 5;", "5", "VALID", null, null);

    // Test plain API
    assertEquals("int x = 5;", result.source());
    assertEquals("5", result.value());
    assertEquals("VALID", result.status());
    assertNull(result.exceptionType());
    assertNull(result.exceptionMessage());

    // Test bean getters for Jackson
    assertEquals("int x = 5;", result.getSource());
    assertEquals("5", result.getValue());
    assertEquals("VALID", result.getStatus());
    assertNull(result.getExceptionType());
    assertNull(result.getExceptionMessage());
  }

  @Test
  public void testSnippetResultWithException() {
    EvalResult.SnippetResult result = new EvalResult.SnippetResult("int x = 10/0;", null, "RECOVERABLE_DEFINED",
        "ArithmeticException", "/ by zero");

    assertEquals("int x = 10/0;", result.source());
    assertNull(result.value());
    assertEquals("RECOVERABLE_DEFINED", result.status());
    assertEquals("ArithmeticException", result.exceptionType());
    assertEquals("/ by zero", result.exceptionMessage());
  }

  @Test
  public void testEvalResultBasicConstructor() {
    List<SnippetEvent> events = new ArrayList<>();
    SnippetEvent event = createMockSnippetEvent("2 + 2", "4", Status.VALID, null);
    events.add(event);

    EvalResult result = new EvalResult("output", "error", events, startTime, endTime);

    assertEquals("output", result.out());
    assertEquals("error", result.err());
    assertEquals(1, result.events().size());
    assertEquals(startTime, result.startedAt());
    assertEquals(endTime, result.finishedAt());
    assertNull(result.sessionId());
  }

  @Test
  public void testEvalResultWithSessionId() {
    List<SnippetEvent> events = new ArrayList<>();
    SnippetEvent event = createMockSnippetEvent("3 * 3", "9", Status.VALID, null);
    events.add(event);

    EvalResult result = new EvalResult("stdout", "stderr", events, startTime, endTime, "session-123");

    assertEquals("stdout", result.out());
    assertEquals("stderr", result.err());
    assertEquals(1, result.events().size());
    assertEquals(startTime, result.startedAt());
    assertEquals(endTime, result.finishedAt());
    assertEquals("session-123", result.sessionId());
  }

  @Test
  public void testWithSessionId() {
    List<SnippetEvent> events = new ArrayList<>();
    SnippetEvent event = createMockSnippetEvent("1 + 1", "2", Status.VALID, null);
    events.add(event);

    EvalResult original = new EvalResult("out", "err", events, startTime, endTime);
    assertNull(original.sessionId());

    EvalResult modified = original.withSessionId("new-session");
    assertEquals("new-session", modified.sessionId());

    // Original should be unchanged
    assertNull(original.sessionId());

    // Other fields should be preserved
    assertEquals(original.out(), modified.out());
    assertEquals(original.err(), modified.err());
    assertEquals(original.events().size(), modified.events().size());
    assertEquals(original.startedAt(), modified.startedAt());
    assertEquals(original.finishedAt(), modified.finishedAt());
  }

  @Test
  public void testMultipleEvents() {
    List<SnippetEvent> events = Arrays.asList(createMockSnippetEvent("int a = 10;", "10", Status.VALID, null),
        createMockSnippetEvent("int b = 20;", "20", Status.VALID, null),
        createMockSnippetEvent("a + b", "30", Status.VALID, null));

    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    assertEquals(3, result.events().size());

    EvalResult.SnippetResult event1 = result.events().get(0);
    assertEquals("int a = 10;", event1.source());
    assertEquals("10", event1.value());

    EvalResult.SnippetResult event2 = result.events().get(1);
    assertEquals("int b = 20;", event2.source());
    assertEquals("20", event2.value());

    EvalResult.SnippetResult event3 = result.events().get(2);
    assertEquals("a + b", event3.source());
    assertEquals("30", event3.value());
  }

  @Test
  public void testEventsWithException() {
    JShellException testException = mock(JShellException.class);
    when(testException.getMessage()).thenReturn("Test error");

    List<SnippetEvent> events = Arrays.asList(
        createMockSnippetEvent("String s = \"ok\";", "\"ok\"", Status.VALID, null),
        createMockSnippetEvent("throw new RuntimeException(\"Test error\");", null, Status.REJECTED, testException));

    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    assertEquals(2, result.events().size());

    EvalResult.SnippetResult validEvent = result.events().get(0);
    assertEquals("VALID", validEvent.status());
    assertNull(validEvent.exceptionType());

    EvalResult.SnippetResult errorEvent = result.events().get(1);
    assertEquals("REJECTED", errorEvent.status());
    assertEquals("JShellException", errorEvent.exceptionType());
    assertEquals("Test error", errorEvent.exceptionMessage());
  }

  @Test
  public void testEmptyEvents() {
    EvalResult result = new EvalResult("", "", Collections.emptyList(), startTime, endTime);

    assertNotNull(result.events());
    assertTrue(result.events().isEmpty());
  }

  @Test
  public void testEmptyOutput() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    assertEquals("", result.out());
    assertEquals("", result.err());
  }

  @Test
  public void testLargeOutput() {
    StringBuilder largeOut = new StringBuilder();
    StringBuilder largeErr = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      largeOut.append("Line ").append(i).append("\n");
      largeErr.append("Error ").append(i).append("\n");
    }

    List<SnippetEvent> events = Collections.emptyList();
    EvalResult result = new EvalResult(largeOut.toString(), largeErr.toString(), events, startTime, endTime);

    assertTrue(result.out().contains("Line 0"));
    assertTrue(result.out().contains("Line 999"));
    assertTrue(result.err().contains("Error 0"));
    assertTrue(result.err().contains("Error 999"));
  }

  @Test
  public void testBeanGetters() {
    List<SnippetEvent> events = Arrays.asList(createMockSnippetEvent("1", "1", Status.VALID, null));

    EvalResult result = new EvalResult("stdout", "stderr", events, startTime, endTime, "sess-1");

    // Test all bean getters
    assertEquals("stdout", result.getOut());
    assertEquals("stderr", result.getErr());
    assertEquals(1, result.getEvents().size());
    assertEquals("sess-1", result.getSessionId());
    assertEquals(startTime, result.getStartedAt());
    assertEquals(endTime, result.getFinishedAt());

    // Test ISO string conversion
    assertNotNull(result.getStartedAtIso());
    assertNotNull(result.getFinishedAtIso());
    assertTrue(result.getStartedAtIso().contains("T")); // ISO format check
    assertTrue(result.getFinishedAtIso().contains("T"));
  }

  @Test
  public void testNullTimestamps() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult result = new EvalResult("", "", events, null, null);

    assertNull(result.startedAt());
    assertNull(result.finishedAt());
    assertNull(result.getStartedAtIso());
    assertNull(result.getFinishedAtIso());
  }

  @Test
  public void testToString() {
    List<SnippetEvent> events = Arrays.asList(createMockSnippetEvent("5 + 5", "10", Status.VALID, null));

    EvalResult result = new EvalResult("output", "error", events, startTime, endTime, "test-session");

    String json = result.toString();
    assertNotNull(json);

    // Should be valid JSON
    assertTrue(json.contains("\"out\""));
    // err might be excluded when empty due to
    // @JsonInclude(JsonInclude.Include.NON_EMPTY)
    assertTrue(json.contains("\"events\""));
    assertTrue(json.contains("\"sessionId\""));
    assertTrue(json.contains("\"startedAt\""));
    assertTrue(json.contains("\"finishedAt\""));
  }

  @Test
  public void testJsonSerialization() throws Exception {
    List<SnippetEvent> events = Arrays
        .asList(createMockSnippetEvent("Math.PI", "3.141592653589793", Status.VALID, null));

    EvalResult result = new EvalResult("pi output", "", events, startTime, endTime, "math-session");

    // Convert to JSON string
    String json = JsonUtils.toJSON(result);
    assertNotNull(json);

    // Parse JSON to verify structure
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

    assertEquals("pi output", parsed.get("out"));
    // err might be excluded from JSON when empty due to
    // @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Object errValue = parsed.get("err");
    assertTrue(errValue == null || "".equals(errValue), "err should be null or empty");
    assertEquals("math-session", parsed.get("sessionId"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parsedEvents = (List<Map<String, Object>>) parsed.get("events");
    assertEquals(1, parsedEvents.size());

    Map<String, Object> firstEvent = parsedEvents.get(0);
    assertEquals("Math.PI", firstEvent.get("source"));
    assertEquals("3.141592653589793", firstEvent.get("value"));
    assertEquals("VALID", firstEvent.get("status"));
  }

  @Test
  public void testEventWithBlankValue() {
    // Test that blank values are treated as null
    SnippetEvent event = createMockSnippetEvent("void method()", "   ", Status.VALID, null);
    List<SnippetEvent> events = Arrays.asList(event);

    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    EvalResult.SnippetResult snippetResult = result.events().get(0);
    assertNull(snippetResult.value()); // Blank should become null
  }

  @Test
  public void testEventWithNullSnippet() {
    // Create a mock event with null snippet
    SnippetEvent event = mock(SnippetEvent.class);
    when(event.snippet()).thenReturn(null);
    when(event.value()).thenReturn("value");
    when(event.status()).thenReturn(Status.VALID);
    when(event.exception()).thenReturn(null);

    List<SnippetEvent> events = Arrays.asList(event);
    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    EvalResult.SnippetResult snippetResult = result.events().get(0);
    assertNull(snippetResult.source());
    assertEquals("value", snippetResult.value());
  }

  @Test
  public void testEventWithNullStatus() {
    Snippet snippet = mock(Snippet.class);
    when(snippet.source()).thenReturn("code");

    SnippetEvent event = mock(SnippetEvent.class);
    when(event.snippet()).thenReturn(snippet);
    when(event.value()).thenReturn("result");
    when(event.status()).thenReturn(null);
    when(event.exception()).thenReturn(null);

    List<SnippetEvent> events = Arrays.asList(event);
    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    EvalResult.SnippetResult snippetResult = result.events().get(0);
    assertNull(snippetResult.status());
  }

  @Test
  public void testEventsListIsImmutable() {
    List<SnippetEvent> events = new ArrayList<>();
    events.add(createMockSnippetEvent("1", "1", Status.VALID, null));

    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    // Should not be able to modify the returned list
    List<EvalResult.SnippetResult> resultEvents = result.events();
    assertNotNull(resultEvents);

    boolean thrown = false;
    try {
      resultEvents.add(new EvalResult.SnippetResult("", "", "", null, null));
    } catch (UnsupportedOperationException e) {
      thrown = true;
    }
    assertTrue(thrown, "Events list should be immutable");
  }

  @Test
  public void testVariousStatusTypes() {
    List<SnippetEvent> events = Arrays.asList(createMockSnippetEvent("valid", "ok", Status.VALID, null),
        createMockSnippetEvent("error", null, Status.REJECTED, null),
        createMockSnippetEvent("dropped", null, Status.DROPPED, null),
        createMockSnippetEvent("overwritten", "old", Status.OVERWRITTEN, null),
        createMockSnippetEvent("recoverable", "rec", Status.RECOVERABLE_DEFINED, null),
        createMockSnippetEvent("recoverable_ne", "ne", Status.RECOVERABLE_NOT_DEFINED, null));

    EvalResult result = new EvalResult("", "", events, startTime, endTime);

    assertEquals(6, result.events().size());
    assertEquals("VALID", result.events().get(0).status());
    assertEquals("REJECTED", result.events().get(1).status());
    assertEquals("DROPPED", result.events().get(2).status());
    assertEquals("OVERWRITTEN", result.events().get(3).status());
    assertEquals("RECOVERABLE_DEFINED", result.events().get(4).status());
    assertEquals("RECOVERABLE_NOT_DEFINED", result.events().get(5).status());
  }

  @Test
  public void testUnicodeContent() {
    List<SnippetEvent> events = Arrays.asList(createMockSnippetEvent("\"你好世界\"", "\"你好世界\"", Status.VALID, null));

    EvalResult result = new EvalResult("Unicode: 🎉 émojis", "Error: ∑∏∫", events, startTime, endTime);

    assertEquals("Unicode: 🎉 émojis", result.out());
    assertEquals("Error: ∑∏∫", result.err());
    assertEquals("\"你好世界\"", result.events().get(0).value());
  }

  // Helper method to create mock SnippetEvent
  private SnippetEvent createMockSnippetEvent(String source, String value, Status status, JShellException exception) {
    Snippet snippet = mock(Snippet.class);
    when(snippet.source()).thenReturn(source);

    SnippetEvent event = mock(SnippetEvent.class);
    when(event.snippet()).thenReturn(snippet);
    when(event.value()).thenReturn(value);
    when(event.status()).thenReturn(status);
    when(event.exception()).thenReturn(exception);

    return event;
  }
}