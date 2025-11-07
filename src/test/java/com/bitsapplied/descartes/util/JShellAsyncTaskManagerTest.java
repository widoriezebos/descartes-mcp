package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link JShellAsyncTaskManager}.
 */
public class JShellAsyncTaskManagerTest {

  private Map<String, Object> context;
  private JShellAsyncTaskManager manager;

  @BeforeEach
  void setUp() {
    context = new ConcurrentHashMap<>();
    manager = JShellAsyncTaskManagers.getOrCreate(context);
  }

  @AfterEach
  void tearDown() {
    JShellAsyncTaskManagers.shutdown(context);
    JShellSessionManagers.shutdown(context);
  }

  @Test
  void taskCompletesSuccessfully() throws Exception {
    String code = """
        int x = 40 + 2;
        x;
        """;

    JShellAsyncTaskManager.JShellAsyncTask task = manager
        .startTask(new JShellAsyncTaskManager.Request(null, code, 5L, false, null));

    EvalResult result = task.future().get(2, TimeUnit.SECONDS);
    assertNotNull(result);
    assertEquals("42", result.events().get(result.events().size() - 1).value());
    assertEquals("success", task.toSummary(true).get("status"));
  }

  @Test
  void taskTimeoutTriggersCancellation() throws Exception {
    String code = """
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
        1;
        """;

    JShellAsyncTaskManager.JShellAsyncTask task = manager
        .startTask(new JShellAsyncTaskManager.Request(null, code, 1L, false, null));

    ExecutionException ex = assertThrows(ExecutionException.class, () -> task.future().get(2, TimeUnit.SECONDS));
    assertTrue(ex.getCause() instanceof TimeoutException);

    Map<String, Object> summary = task.toSummary(true);
    assertEquals("timeout", summary.get("status"));
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) summary.get("error");
    assertNotNull(error);
    assertTrue(((String) error.get("message")).contains("timed out"));
  }

  @Test
  void cancellationStopsRunningTask() throws Exception {
    String code = """
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
        1;
        """;

    JShellAsyncTaskManager.JShellAsyncTask task = manager
        .startTask(new JShellAsyncTaskManager.Request(null, code, 10L, false, null));

    // Allow task to start running
    Thread.sleep(50);
    manager.cancelTask(task.taskId(), "cancel test");

    Map<String, Object> summary = task.toSummary(true);
    assertEquals("cancelled", summary.get("status"));
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) summary.get("error");
    assertNotNull(error);
    assertEquals("Cancelled", error.get("type"));
  }

  @Test
  void closeSessionRemovesSession() throws Exception {
    String code = "\"done\";";
    JShellAsyncTaskManager.JShellAsyncTask task = manager
        .startTask(new JShellAsyncTaskManager.Request(null, code, 5L, true, null));

    task.future().get(1, TimeUnit.SECONDS);

    JShellSessionManager sessionManager = JShellSessionManagers.getOrCreate(context);
    waitUntil(() -> sessionManager.getSession(task.sessionId()) == null, 1000);
    assertNull(sessionManager.getSession(task.sessionId()), "Session should be closed after completion");
  }

  @Test
  void extendExpiryUpdatesSession() throws Exception {
    String code = "\"ok\";";
    JShellAsyncTaskManager.JShellAsyncTask task = manager
        .startTask(new JShellAsyncTaskManager.Request(null, code, 5L, false, 60));

    task.future().get(1, TimeUnit.SECONDS);

    JShellSessionManager sessionManager = JShellSessionManagers.getOrCreate(context);
    waitUntil(() -> sessionManager.getSession(task.sessionId()) != null, 500);
    JShellSession session = sessionManager.getSession(task.sessionId());
    assertNotNull(session, "Session should remain open");
    assertEquals(60, session.getCustomExpiryMinutes());
  }

  @Test
  void toSummaryIncludesTiming() throws Exception {
    String code = "1+1;";
    JShellAsyncTaskManager.JShellAsyncTask task = manager
        .startTask(new JShellAsyncTaskManager.Request(null, code, 5L, false, null));

    task.future().get(1, TimeUnit.SECONDS);
    Map<String, Object> summary = task.toSummary(false);
    assertEquals("success", summary.get("status"));
    assertTrue(summary.containsKey("started_at"));
    assertTrue(summary.containsKey("completed_at"));
    assertTrue(summary.containsKey("created_at"));
  }

  private void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
  }
}
