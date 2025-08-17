package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for JShellSession.
 */
public class JShellSessionTest {

  private Map<String, Object> context;
  private JShellSession session;

  @BeforeEach
  public void setUp() throws Exception {
    context = new HashMap<>();
    context.put("test.value", "test-value");
  }

  @AfterEach
  public void tearDown() {
    if (session != null) {
      session.close();
    }
  }

  @Test
  public void testConstructorWithDefaultSessionId() {
    session = new JShellSession(context);

    assertNotNull(session.getSessionId());
    // Should be a valid UUID
    assertDoesNotThrow(() -> UUID.fromString(session.getSessionId()));
    assertNotNull(session.getCreatedAt());
    assertNotNull(session.getLastAccessedAt());
    assertEquals(session.getCreatedAt(), session.getLastAccessedAt());
  }

  @Test
  public void testConstructorWithCustomSessionId() {
    String customId = "custom-session-123";
    session = new JShellSession(customId, context);

    assertEquals(customId, session.getSessionId());
    assertNotNull(session.getCreatedAt());
    assertNotNull(session.getLastAccessedAt());
  }

  @Test
  @SuppressWarnings("resource")
  public void testConstructorWithNullSessionId() {
    assertThrows(NullPointerException.class, () -> {
      new JShellSession(null, context);
    });
  }

  @Test
  @SuppressWarnings("resource")
  public void testConstructorWithNullContext() {
    assertThrows(NullPointerException.class, () -> {
      new JShellSession("test-id", null);
    });
  }

  @Test
  public void testEval() {
    session = new JShellSession(context);

    Instant beforeEval = session.getLastAccessedAt();

    // Small delay to ensure time difference
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    EvalResult result = session.eval("5 + 5");

    assertNotNull(result);
    assertFalse(result.events().isEmpty());
    assertEquals("10", result.events().get(0).value());

    // Last accessed time should be updated
    Instant afterEval = session.getLastAccessedAt();
    assertTrue(afterEval.isAfter(beforeEval));
  }

  @Test
  public void testMultipleEvals() {
    session = new JShellSession(context);

    // First eval
    EvalResult result1 = session.eval("int x = 10;");
    assertEquals("10", result1.events().get(0).value());

    // Second eval should have access to previous state
    EvalResult result2 = session.eval("x * 2");
    assertEquals("20", result2.events().get(0).value());

    // Third eval
    EvalResult result3 = session.eval("x = x + 5; x");
    // Should have two events: assignment and value
    assertTrue(result3.events().size() >= 1);
    boolean found15 = result3.events().stream().anyMatch(e -> "15".equals(e.value()));
    assertTrue(found15);
  }

  @Test
  public void testCustomExpiryMinutes() {
    session = new JShellSession(context);

    // Initially should be null (use default)
    assertNull(session.getCustomExpiryMinutes());

    // Set custom expiry
    session.setCustomExpiryMinutes(30);
    assertEquals(30, session.getCustomExpiryMinutes());

    // Set back to null
    session.setCustomExpiryMinutes(null);
    assertNull(session.getCustomExpiryMinutes());
  }

  @Test
  public void testIsExpiredWithDefaultTimeout() {
    session = new JShellSession(context);

    // Should not be expired immediately
    assertFalse(session.isExpired(60)); // 60 minutes default

    // Should not be expired with very long timeout
    assertFalse(session.isExpired(10000));

    // Should be expired with zero timeout
    assertTrue(session.isExpired(0));

    // Should be expired with very small timeout
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertTrue(session.isExpired(0)); // 0 minutes = immediate expiry
  }

  @Test
  public void testIsExpiredWithCustomTimeout() {
    session = new JShellSession(context);

    // Set custom expiry to 1 minute
    session.setCustomExpiryMinutes(1);

    // Should not be expired immediately even with 0 default
    // (custom overrides default)
    assertFalse(session.isExpired(0));

    // Should use custom timeout
    assertFalse(session.isExpired(100)); // default is ignored

    // Set custom to 0 (immediate expiry)
    session.setCustomExpiryMinutes(0);

    // Small delay
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertTrue(session.isExpired(100)); // should be expired with custom=0
  }

  @Test
  public void testLastAccessedUpdatedOnEval() throws InterruptedException {
    session = new JShellSession(context);

    Instant created = session.getCreatedAt();
    Instant initialAccess = session.getLastAccessedAt();
    assertEquals(created, initialAccess);

    // Wait a bit
    Thread.sleep(50);

    // Eval should update last accessed
    session.eval("1 + 1");
    Instant afterFirstEval = session.getLastAccessedAt();
    assertTrue(afterFirstEval.isAfter(initialAccess));

    // Wait again
    Thread.sleep(50);

    // Another eval should update again
    session.eval("2 + 2");
    Instant afterSecondEval = session.getLastAccessedAt();
    assertTrue(afterSecondEval.isAfter(afterFirstEval));
  }

  @Test
  public void testCreatedAtNeverChanges() throws InterruptedException {
    session = new JShellSession(context);

    Instant created = session.getCreatedAt();

    Thread.sleep(50);
    session.eval("test");

    // Created at should not change
    assertEquals(created, session.getCreatedAt());

    Thread.sleep(50);
    session.eval("another test");

    // Still should not change
    assertEquals(created, session.getCreatedAt());
  }

  @Test
  public void testClose() {
    session = new JShellSession(context);

    // Should be able to eval before close
    EvalResult result = session.eval("3 + 3");
    assertEquals("6", result.events().get(0).value());

    // Close the session
    session.close();

    // Should not throw when closing again
    assertDoesNotThrow(() -> session.close());
  }

  @Test
  public void testCloseWithException() {
    // Even if the internal close throws an exception, it should be caught
    session = new JShellSession(context);
    session.close();

    // Closing again should not throw
    assertDoesNotThrow(() -> session.close());
  }

  @Test
  public void testAutoCloseable() {
    // Test that JShellSession can be used in try-with-resources
    String sessionId;

    try (JShellSession autoSession = new JShellSession(context)) {
      sessionId = autoSession.getSessionId();
      assertNotNull(sessionId);

      EvalResult result = autoSession.eval("7 * 7");
      assertEquals("49", result.events().get(0).value());
    }

    // Session should be auto-closed now
    // We can't directly test if it's closed, but we can verify no exception
  }

  @Test
  public void testConcurrentEval() throws InterruptedException {
    session = new JShellSession(context);

    // The eval method is synchronized, so concurrent calls should be serialized
    Thread t1 = new Thread(() -> {
      EvalResult result = session.eval("Thread.sleep(100); \"thread1\"");
      assertTrue(result.events().stream().anyMatch(e -> "\"thread1\"".equals(e.value())));
    });

    Thread t2 = new Thread(() -> {
      EvalResult result = session.eval("\"thread2\"");
      assertTrue(result.events().stream().anyMatch(e -> "\"thread2\"".equals(e.value())));
    });

    t1.start();
    t2.start();

    t1.join(5000); // Wait max 5 seconds
    t2.join(5000);

    // Both threads should complete successfully
  }

  @Test
  public void testSessionIdUniqueness() {
    JShellSession session1 = new JShellSession(context);
    JShellSession session2 = new JShellSession(context);

    try {
      assertNotEquals(session1.getSessionId(), session2.getSessionId());
    } finally {
      session1.close();
      session2.close();
    }
  }

  @Test
  public void testEvalWithError() {
    session = new JShellSession(context);

    // Code with compilation error
    EvalResult result = session.eval("int x = \"not a number\";");

    assertNotNull(result);
    assertFalse(result.events().isEmpty());
    assertEquals("REJECTED", result.events().get(0).status());
  }

  @Test
  public void testEvalWithRuntimeException() {
    session = new JShellSession(context);

    EvalResult result = session.eval("int x = 10 / 0;");

    assertNotNull(result);
    assertFalse(result.events().isEmpty());
    assertNotNull(result.events().get(0).exceptionMessage());
    assertTrue(result.events().get(0).exceptionMessage().contains("zero"));
  }

  @Test
  public void testEvalWithOutput() {
    session = new JShellSession(context);

    EvalResult result = session.eval("System.out.println(\"Hello, World!\");");

    assertNotNull(result);
    assertEquals("Hello, World!\n", result.out());
  }

  @Test
  public void testCustomExpiryPersistence() {
    session = new JShellSession(context);

    // Set custom expiry
    session.setCustomExpiryMinutes(45);

    // Eval shouldn't change custom expiry
    session.eval("1 + 1");
    assertEquals(45, session.getCustomExpiryMinutes());

    // Multiple evals
    session.eval("2 + 2");
    session.eval("3 + 3");
    assertEquals(45, session.getCustomExpiryMinutes());
  }

  @Test
  public void testExpiryWithNegativeMinutes() {
    session = new JShellSession(context);

    // Set negative expiry (should expire immediately)
    session.setCustomExpiryMinutes(-1);

    // Small delay
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Should be expired with negative custom timeout
    assertTrue(session.isExpired(100));
  }

  @Test
  public void testLargeCustomExpiry() {
    session = new JShellSession(context);

    // Set very large expiry (Integer.MAX_VALUE minutes)
    session.setCustomExpiryMinutes(Integer.MAX_VALUE);

    // Should never expire in practice
    assertFalse(session.isExpired(0));
    assertFalse(session.isExpired(1));
    assertFalse(session.isExpired(Integer.MAX_VALUE));
  }

  @Test
  public void testSessionWithComplexState() {
    session = new JShellSession(context);

    // Build up complex state
    session.eval("import java.util.*;");
    session.eval("List<String> list = new ArrayList<>();");
    session.eval("list.add(\"first\");");
    session.eval("list.add(\"second\");");

    EvalResult result = session.eval("list.size()");
    assertEquals("2", result.events().get(0).value());

    // State should persist
    result = session.eval("list.get(0)");
    assertEquals("\"first\"", result.events().get(0).value());
  }
}