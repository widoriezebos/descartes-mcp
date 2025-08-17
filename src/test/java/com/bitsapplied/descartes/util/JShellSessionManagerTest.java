package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for JShellSessionManager.
 */
public class JShellSessionManagerTest {

  private Map<String, Object> context;
  private JShellSessionManager sessionManager;

  @BeforeEach
  public void setUp() throws Exception {
    context = new HashMap<>();
    context.put("test.value", "test-context-value");
    context.put("jshell.max_sessions", 10);
    context.put("jshell.session_timeout_minutes", 30);
    sessionManager = new JShellSessionManager(context);
  }

  @AfterEach
  public void tearDown() {
    if (sessionManager != null) {
      sessionManager.close();
    }
  }

  @Test
  public void testSessionCreation() {
    // Test that a new session is created when no ID is provided
    SessionEvalResult result1 = sessionManager.eval(null, "int x = 42;");
    assertNotNull(result1.getSessionId());
    assertNotNull(result1.getEvalResult());

    // Test that same session is reused with the same ID
    String sessionId = result1.getSessionId();
    SessionEvalResult result2 = sessionManager.eval(sessionId, "System.out.println(x);");
    assertEquals(sessionId, result2.getSessionId());
    assertTrue(result2.getEvalResult().out().contains("42"));
  }

  @Test
  public void testMultipleSessions() {
    // Create first session
    SessionEvalResult result1 = sessionManager.eval("session1", "int x = 100;");
    assertEquals("session1", result1.getSessionId());

    // Create second session
    SessionEvalResult result2 = sessionManager.eval("session2", "int x = 200;");
    assertEquals("session2", result2.getSessionId());

    // Verify sessions are isolated
    SessionEvalResult result3 = sessionManager.eval("session1", "System.out.println(x);");
    assertTrue(result3.getEvalResult().out().contains("100"));

    SessionEvalResult result4 = sessionManager.eval("session2", "System.out.println(x);");
    assertTrue(result4.getEvalResult().out().contains("200"));
  }

  @Test
  public void testSessionReset() {
    // Create session with variable
    SessionEvalResult result1 = sessionManager.eval("test", "String msg = \"original\";");
    assertEquals("test", result1.getSessionId());

    // Reset the session
    sessionManager.resetSession("test");

    // Verify variable is gone after reset
    SessionEvalResult result2 = sessionManager.eval("test",
        "try { System.out.println(msg); } catch(Exception e) { System.out.println(\"Variable not found\"); }");
    // After reset, msg should not be defined, so we expect "Variable not found" or
    // a compilation error
    // The actual behavior is that it will fail to compile, resulting in REJECTED
    // status
    boolean hasError = result2.getEvalResult().events().stream().anyMatch(e -> e.status() != null
        && (e.status().contains("REJECTED") || e.status().contains("ERROR") || e.exceptionMessage() != null));
    if (!hasError) {
      // If no error in events, check if output contains error indication
      hasError = result2.getEvalResult().err() != null && !result2.getEvalResult().err().isEmpty();
    }
    assertTrue(hasError || result2.getEvalResult().out().contains("Variable not found"));
  }

  @Test
  public void testSessionCount() {
    assertEquals(0, sessionManager.getSessionCount());

    sessionManager.eval("session1", "1+1");
    assertEquals(1, sessionManager.getSessionCount());

    sessionManager.eval("session2", "2+2");
    assertEquals(2, sessionManager.getSessionCount());

    sessionManager.closeSession("session1");
    assertEquals(1, sessionManager.getSessionCount());
  }

  @Test
  public void testSessionIdInResult() {
    // Test that session ID is properly included in the result
    SessionEvalResult result = sessionManager.eval("mySession", "System.out.println(\"test\");");
    assertEquals("mySession", result.getSessionId());

    EvalResult evalResult = result.getEvalResult();
    assertNotNull(evalResult);

    // Create a result with session ID
    EvalResult withSession = evalResult.withSessionId(result.getSessionId());
    assertEquals("mySession", withSession.sessionId());
  }

  @Test
  public void testSessionTimestampRefreshOnEval() throws InterruptedException {
    // Create a session
    SessionEvalResult result1 = sessionManager.eval("timestamp-test", "int x = 1;");
    assertEquals("timestamp-test", result1.getSessionId());

    // Get the initial timestamp by accessing the session directly
    JShellSession session = sessionManager.getOrCreateSession("timestamp-test");
    java.time.Instant initialTimestamp = session.getLastAccessedAt();

    // Wait a small amount to ensure timestamp difference
    Thread.sleep(10);

    // Perform another eval - this should refresh the timestamp
    SessionEvalResult result2 = sessionManager.eval("timestamp-test", "x += 1; x");
    assertEquals("timestamp-test", result2.getSessionId());

    // Check that the timestamp was updated
    java.time.Instant updatedTimestamp = session.getLastAccessedAt();
    assertTrue(updatedTimestamp.isAfter(initialTimestamp), "Session timestamp should be refreshed after eval. Initial: "
        + initialTimestamp + ", Updated: " + updatedTimestamp);

    // Verify the eval worked correctly
    assertTrue(result2.getEvalResult().events().stream().anyMatch(e -> "2".equals(e.value())),
        "Expected result value of 2");
  }
}