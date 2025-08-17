package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;

/**
 * Comprehensive tests for SessionEvalResult wrapper class.
 */
public class SessionEvalResultTest {

  private Instant startTime;
  private Instant endTime;

  @BeforeEach
  public void setUp() {
    startTime = Instant.now();
    endTime = startTime.plusMillis(100);
  }

  @Test
  public void testConstructorAndGetters() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("output", "error", events, startTime, endTime);
    String sessionId = "test-session-123";

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, sessionId);

    assertNotNull(sessionResult.getEvalResult());
    assertSame(evalResult, sessionResult.getEvalResult());
    assertEquals(sessionId, sessionResult.getSessionId());
  }

  @Test
  public void testWithNullEvalResult() {
    SessionEvalResult sessionResult = new SessionEvalResult(null, "session-id");

    assertNull(sessionResult.getEvalResult());
    assertEquals("session-id", sessionResult.getSessionId());
  }

  @Test
  public void testWithNullSessionId() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("", "", events, startTime, endTime);

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, null);

    assertNotNull(sessionResult.getEvalResult());
    assertNull(sessionResult.getSessionId());
  }

  @Test
  public void testWithBothNull() {
    SessionEvalResult sessionResult = new SessionEvalResult(null, null);

    assertNull(sessionResult.getEvalResult());
    assertNull(sessionResult.getSessionId());
  }

  @Test
  public void testWithCompleteEvalResult() {
    // Create a complete EvalResult with events
    List<SnippetEvent> events = new ArrayList<>();
    events.add(createMockSnippetEvent("int x = 5;", "5", Status.VALID, null));
    events.add(createMockSnippetEvent("x * 2", "10", Status.VALID, null));

    EvalResult evalResult = new EvalResult("Output text\n", "Error text\n", events, startTime, endTime,
        "embedded-session");
    String sessionId = "wrapper-session";

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, sessionId);

    // Verify wrapper preserves all data
    assertSame(evalResult, sessionResult.getEvalResult());
    assertEquals("wrapper-session", sessionResult.getSessionId());

    // Verify we can access nested data
    assertEquals("Output text\n", sessionResult.getEvalResult().out());
    assertEquals("Error text\n", sessionResult.getEvalResult().err());
    assertEquals(2, sessionResult.getEvalResult().events().size());
    assertEquals("embedded-session", sessionResult.getEvalResult().sessionId());
  }

  @Test
  public void testWithEmptySessionId() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("", "", events, startTime, endTime);

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, "");

    assertNotNull(sessionResult.getEvalResult());
    assertEquals("", sessionResult.getSessionId());
  }

  @Test
  public void testWithLongSessionId() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("", "", events, startTime, endTime);

    StringBuilder longId = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      longId.append("session-");
    }

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, longId.toString());

    assertNotNull(sessionResult.getEvalResult());
    assertEquals(longId.toString(), sessionResult.getSessionId());
    assertEquals(8000, sessionResult.getSessionId().length()); // "session-" = 8 chars * 1000
  }

  @Test
  public void testWithUnicodeSessionId() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("", "", events, startTime, endTime);

    String unicodeId = "session-你好-🎉-مرحبا";

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, unicodeId);

    assertNotNull(sessionResult.getEvalResult());
    assertEquals(unicodeId, sessionResult.getSessionId());
  }

  @Test
  public void testMultipleWrappersForSameEvalResult() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("shared", "", events, startTime, endTime);

    SessionEvalResult result1 = new SessionEvalResult(evalResult, "session-1");
    SessionEvalResult result2 = new SessionEvalResult(evalResult, "session-2");
    SessionEvalResult result3 = new SessionEvalResult(evalResult, "session-3");

    // All should share the same EvalResult instance
    assertSame(evalResult, result1.getEvalResult());
    assertSame(evalResult, result2.getEvalResult());
    assertSame(evalResult, result3.getEvalResult());

    // But have different session IDs
    assertEquals("session-1", result1.getSessionId());
    assertEquals("session-2", result2.getSessionId());
    assertEquals("session-3", result3.getSessionId());
  }

  @Test
  public void testWithEvalResultContainingErrors() {
    JShellException exception = mock(JShellException.class);
    when(exception.getMessage()).thenReturn("Compilation error");

    List<SnippetEvent> events = new ArrayList<>();
    events.add(createMockSnippetEvent("invalid code", null, Status.REJECTED, exception));

    EvalResult evalResult = new EvalResult("", "Error output", events, startTime, endTime);

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, "error-session");

    assertNotNull(sessionResult.getEvalResult());
    assertEquals("error-session", sessionResult.getSessionId());
    assertEquals("Error output", sessionResult.getEvalResult().err());
    assertEquals(1, sessionResult.getEvalResult().events().size());
    assertEquals("REJECTED", sessionResult.getEvalResult().events().get(0).status());
  }

  @Test
  public void testImmutability() {
    List<SnippetEvent> events = new ArrayList<>();
    events.add(createMockSnippetEvent("test", "result", Status.VALID, null));

    EvalResult evalResult = new EvalResult("out", "err", events, startTime, endTime);
    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, "immutable-session");

    // Original references should be preserved
    assertSame(evalResult, sessionResult.getEvalResult());
    assertEquals("immutable-session", sessionResult.getSessionId());

    // No setters exist, so the object is effectively immutable
    // The only way to change would be through reflection, which we don't test
  }

  @Test
  public void testTypicalUsageScenario() {
    // Simulate typical usage: wrapping an eval result with session context

    // Execute some code
    List<SnippetEvent> events = new ArrayList<>();
    events.add(createMockSnippetEvent("List<String> list = new ArrayList<>();", "[]", Status.VALID, null));
    events.add(createMockSnippetEvent("list.add(\"hello\");", "true", Status.VALID, null));
    events.add(createMockSnippetEvent("list.size()", "1", Status.VALID, null));

    EvalResult evalResult = new EvalResult("List initialized\n", "", events, startTime, endTime,
        "jshell-internal-session");

    // Wrap with external session ID
    String externalSessionId = "user-session-" + System.currentTimeMillis();
    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, externalSessionId);

    // Verify the wrapper maintains both session contexts
    assertEquals(externalSessionId, sessionResult.getSessionId());
    assertEquals("jshell-internal-session", sessionResult.getEvalResult().sessionId());

    // Verify all data is accessible
    assertEquals("List initialized\n", sessionResult.getEvalResult().out());
    assertEquals(3, sessionResult.getEvalResult().events().size());
    assertEquals("1", sessionResult.getEvalResult().events().get(2).value());
  }

  @Test
  public void testSpecialCharactersInSessionId() {
    List<SnippetEvent> events = Collections.emptyList();
    EvalResult evalResult = new EvalResult("", "", events, startTime, endTime);

    String specialId = "session!@#$%^&*()_+-=[]{}|;':\",./<>?";

    SessionEvalResult sessionResult = new SessionEvalResult(evalResult, specialId);

    assertEquals(specialId, sessionResult.getSessionId());
  }

  @Test
  public void testWithMockedEvalResult() {
    EvalResult mockResult = mock(EvalResult.class);
    when(mockResult.out()).thenReturn("mocked output");
    when(mockResult.err()).thenReturn("mocked error");
    when(mockResult.sessionId()).thenReturn("mocked-eval-session");

    SessionEvalResult sessionResult = new SessionEvalResult(mockResult, "wrapper-session");

    assertSame(mockResult, sessionResult.getEvalResult());
    assertEquals("wrapper-session", sessionResult.getSessionId());

    // Verify mock behavior
    assertEquals("mocked output", sessionResult.getEvalResult().out());
    assertEquals("mocked error", sessionResult.getEvalResult().err());
    assertEquals("mocked-eval-session", sessionResult.getEvalResult().sessionId());
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