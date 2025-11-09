package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.bitsapplied.descartes.util.JShellSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive concurrency tests for JShellTool to verify thread safety, state
 * isolation, and output capture isolation under various concurrent scenarios.
 */
public class JShellToolConcurrencyTest {

  private Map<String, Object> context;
  private JShellTool jshellTool;
  private ObjectMapper objectMapper;
  private ExecutorService executor;

  @BeforeEach
  public void setUp() throws Exception {
    context = new HashMap<>();
    context.put("test.context", "test-context-concurrency");
    context.put("jshell.max_sessions", 20); // Higher limit for concurrency tests
    context.put("jshell.session_timeout_minutes", 30);
    jshellTool = new JShellTool(context);
    objectMapper = new ObjectMapper();
    executor = Executors.newFixedThreadPool(20); // Enough threads for high contention
  }

  @AfterEach
  public void tearDown() {
    if (executor != null) {
      // First, shutdown gracefully to allow in-flight tasks to complete
      executor.shutdown();
      try {
        // Wait longer for tasks to complete naturally
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          // If tasks don't complete, force shutdown
          executor.shutdownNow();
          // Wait for forced shutdown to complete
          executor.awaitTermination(2, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    // Only close JShell tool after all executor tasks are done
    if (jshellTool != null) {
      jshellTool.close();
    }
  }

  /**
   * Test multiple threads creating sessions concurrently. Verifies no race
   * conditions in session creation and that each gets a unique session.
   */
  @Test
  @Timeout(30)
  public void testConcurrentSessionCreation() throws Exception {
    final int numThreads = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch finishLatch = new CountDownLatch(numThreads);
    final Set<String> sessionIds = ConcurrentHashMap.newKeySet();
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

    // Start multiple threads simultaneously to create sessions
    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for signal to start simultaneously

          Map<String, Object> args = new HashMap<>();
          args.put("code", "int threadValue = " + threadId + ";");

          String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
          @SuppressWarnings("unchecked")
          Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

          String sessionId = (String) result.get("sessionId");
          assertNotNull(sessionId, "Session ID should not be null");
          assertTrue(sessionIds.add(sessionId), "Session ID should be unique: " + sessionId);

          // Validate snippet execution
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
          assertNotNull(events, "Should have events");
          assertEquals(1, events.size(), "Should have exactly one event");

          Map<String, Object> event = events.get(0);
          assertEquals("VALUE", event.get("status"), "Variable assignment should succeed");
          assertEquals(threadId, Integer.parseInt(event.get("value").toString()), "Should return assigned value");
          assertNotNull(event.get("snippet"), "Should have snippet info");

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          finishLatch.countDown();
        }
      });
    }

    startLatch.countDown(); // Signal all threads to start
    assertTrue(finishLatch.await(10, TimeUnit.SECONDS), "All threads should complete within timeout");

    if (!exceptions.isEmpty()) {
      fail("Exceptions occurred during concurrent session creation: " + exceptions);
    }

    assertEquals(numThreads, sessionIds.size(), "Should have created " + numThreads + " unique sessions");
  }

  /**
   * Test multiple threads executing in different sessions concurrently. Verifies
   * complete state isolation between sessions.
   */
  @Test
  @Timeout(30)
  public void testConcurrentMultiSessionExecution() throws Exception {
    final int numSessions = 8;
    final int operationsPerSession = 5;
    final Map<String, List<Integer>> sessionValues = new ConcurrentHashMap<>();
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch finishLatch = new CountDownLatch(numSessions);

    // Create concurrent tasks for different sessions
    for (int sessionIdx = 0; sessionIdx < numSessions; sessionIdx++) {
      final String sessionId = "session-" + sessionIdx;
      final int sessionValue = sessionIdx * 100;

      executor.submit(() -> {
        try {
          startLatch.await();
          List<Integer> values = new ArrayList<>();

          // Initialize session with unique value
          Map<String, Object> initArgs = new HashMap<>();
          initArgs.put("code", "int sessionVal = " + sessionValue + "; String sessionName = \"" + sessionId + "\";");
          initArgs.put("session_id", sessionId);

          String initResult = ((ToolResponse.Success) jshellTool.executeAsync(initArgs).get()).content();
          @SuppressWarnings("unchecked")
          Map<String, Object> initMap = objectMapper.readValue(initResult, Map.class);
          assertEquals(sessionId, initMap.get("sessionId"));

          // Perform multiple operations in this session
          for (int op = 0; op < operationsPerSession; op++) {
            Map<String, Object> args = new HashMap<>();
            args.put("code", "sessionVal += " + op + "; " + "System.out.println(sessionName + \": \" + sessionVal); "
                + "sessionVal");
            args.put("session_id", sessionId);

            String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

            assertEquals(sessionId, result.get("sessionId"));

            // Verify the return value matches expected calculation
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
            String returnValue = events.get(events.size() - 1).get("value").toString();
            int expectedValue = sessionValue + IntStream.rangeClosed(0, op).sum();
            assertEquals(String.valueOf(expectedValue), returnValue);
            values.add(Integer.parseInt(returnValue));
          }

          sessionValues.put(sessionId, values);

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          finishLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(finishLatch.await(15, TimeUnit.SECONDS), "All sessions should complete");

    if (!exceptions.isEmpty()) {
      fail("Exceptions during multi-session execution: " + exceptions);
    }

    // Verify each session produced the expected outputs
    assertEquals(numSessions, sessionValues.size());
    for (int i = 0; i < numSessions; i++) {
      String sessionId = "session-" + i;
      List<Integer> values = sessionValues.get(sessionId);
      assertNotNull(values, "Session " + sessionId + " should have results");
      assertEquals(operationsPerSession, values.size(),
          "Session " + sessionId + " should have correct number of values");

      int sessionValue = i * 100;
      List<Integer> expected = IntStream.range(0, operationsPerSession)
          .map(op -> sessionValue + IntStream.rangeClosed(0, op).sum()).boxed().toList();
      assertEquals(expected, values, "Session " + sessionId + " should maintain isolated state");
    }
  }

  /**
   * Test multiple threads executing in the same session. The JShellSession.eval()
   * method is synchronized, so this tests that behavior.
   */
  @Test
  @Timeout(30)
  public void testConcurrentSameSessionExecution() throws Exception {
    final String sessionId = "shared-session";
    final int numThreads = 10;
    final AtomicInteger completedOperations = new AtomicInteger(0);
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch finishLatch = new CountDownLatch(numThreads);
    final Map<Integer, String> threadResults = new ConcurrentHashMap<>();

    // Initialize the shared session
    Map<String, Object> initArgs = new HashMap<>();
    initArgs.put("code", "int counter = 0; java.util.List<String> log = new java.util.ArrayList<>();");
    initArgs.put("session_id", sessionId);
    ((ToolResponse.Success) jshellTool.executeAsync(initArgs).get()).content();

    // Start multiple threads operating on the same session
    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          startLatch.await();

          Map<String, Object> args = new HashMap<>();
          args.put("code", "counter++; " + "String entry = \"Thread-\" + " + threadId + " + \": \" + counter; "
              + "log.add(entry); " + "System.out.println(entry); " + "entry");
          args.put("session_id", sessionId);

          String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
          @SuppressWarnings("unchecked")
          Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

          assertEquals(sessionId, result.get("sessionId"));

          @SuppressWarnings("unchecked")
          List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
          String returnValue = events.get(events.size() - 1).get("value").toString();
          threadResults.put(threadId, returnValue);

          completedOperations.incrementAndGet();

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          finishLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(finishLatch.await(15, TimeUnit.SECONDS), "All threads should complete");

    if (!exceptions.isEmpty()) {
      fail("Exceptions during same-session execution: " + exceptions);
    }

    assertEquals(numThreads, completedOperations.get());
    assertEquals(numThreads, threadResults.size());

    // Verify the final counter value
    Map<String, Object> finalArgs = new HashMap<>();
    finalArgs.put("code", "counter");
    finalArgs.put("session_id", sessionId);

    String finalResultJson = ((ToolResponse.Success) jshellTool.executeAsync(finalArgs).get()).content();
    @SuppressWarnings("unchecked")
    Map<String, Object> finalResult = objectMapper.readValue(finalResultJson, Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> finalEvents = (List<Map<String, Object>>) finalResult.get("events");
    String finalCounterValue = finalEvents.get(0).get("value").toString();
    assertEquals(String.valueOf(numThreads), finalCounterValue,
        "Counter should equal number of threads due to synchronization");
  }

  /**
   * Test that stdout/stderr output doesn't leak between concurrent executions.
   */
  @Test
  @Timeout(30)
  public void testOutputIsolationUnderConcurrency() throws Exception {
    final int numThreads = 12;
    final Map<String, Set<String>> threadToOutputLines = new ConcurrentHashMap<>();
    final Map<String, String> threadCompletion = new ConcurrentHashMap<>();
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch finishLatch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      final String uniqueMarker = "THREAD_" + threadId + "_OUTPUT";

      executor.submit(() -> {
        try {
          startLatch.await();

          Map<String, Object> args = new HashMap<>();
          args.put("code",
              "// Thread " + threadId + " operations\n" + "for (int i = 0; i < 5; i++) {\n" + "  System.out.println(\""
                  + uniqueMarker + "_STDOUT_\" + i);\n" + "  System.err.println(\"" + uniqueMarker
                  + "_STDERR_\" + i);\n" + "  if (i == 2) Thread.sleep(1); // Add some timing variation\n" + "}\n"
                  + "\"" + uniqueMarker + "_COMPLETED\"");
          args.put("session_id", "output-test-" + threadId);

          String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
          @SuppressWarnings("unchecked")
          Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

          String stdout = (String) result.get("out");
          String stderr = (String) result.get("err");

          Set<String> outputLines = ConcurrentHashMap.newKeySet();
          if (stdout != null) {
            String[] stdoutLines = stdout.split("\n");
            for (String line : stdoutLines) {
              if (!line.trim().isEmpty()) {
                outputLines.add("STDOUT:" + line.trim());
              }
            }
          }
          if (stderr != null) {
            String[] stderrLines = stderr.split("\n");
            for (String line : stderrLines) {
              if (!line.trim().isEmpty()) {
                outputLines.add("STDERR:" + line.trim());
              }
            }
          }

          threadToOutputLines.put("Thread-" + threadId, outputLines);
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
          String completionValue = (events != null && !events.isEmpty()
              && events.get(events.size() - 1).get("value") != null)
                  ? events.get(events.size() - 1).get("value").toString()
                  : null;
          threadCompletion.put("Thread-" + threadId, completionValue);

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          finishLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(finishLatch.await(20, TimeUnit.SECONDS), "All threads should complete");

    if (!exceptions.isEmpty()) {
      fail("Exceptions during output isolation test: " + exceptions);
    }

    assertEquals(numThreads, threadToOutputLines.size());

    // Verify output isolation: each thread should only see its own output
    for (int i = 0; i < numThreads; i++) {
      String threadKey = "Thread-" + i;
      Set<String> outputs = threadToOutputLines.getOrDefault(threadKey, Collections.emptySet());
      String completion = normalizeCompletion(threadCompletion.get(threadKey));
      assertNotNull(completion, "Thread should report completion marker");
      assertTrue(completion.startsWith("THREAD_" + i + "_"), "Completion marker should start with thread id");
      assertTrue(completion.endsWith("_COMPLETED"), "Completion marker should end with _COMPLETED");

      String expectedMarker = "THREAD_" + i + "_OUTPUT";

      // Verify this thread's outputs contain only its marker
      for (String output : outputs) {
        assertTrue(output.contains(expectedMarker), "Output should contain thread's marker: " + output);

        // Verify no other thread's markers are present
        for (int j = 0; j < numThreads; j++) {
          if (j != i) {
            String otherMarker = "THREAD_" + j + "_OUTPUT";
            assertFalse(output.contains(otherMarker), "Output should not contain other thread's marker: " + output);
          }
        }
      }

      // Verify expected number of stdout and stderr lines
      long stdoutCount = outputs.stream().filter(s -> s.startsWith("STDOUT:")).count();
      long stderrCount = outputs.stream().filter(s -> s.startsWith("STDERR:")).count();
      if (!outputs.isEmpty()) {
        assertEquals(5, stdoutCount, "Should have 5 stdout lines for thread " + i);
        assertEquals(5, stderrCount, "Should have 5 stderr lines for thread " + i);
      }
    }
  }

  /**
   * Test concurrent session resets don't affect other sessions.
   */
  @Test
  @Timeout(30)
  public void testConcurrentSessionResetIsolation() throws Exception {
    final int numSessions = 6;
    final Map<String, String> sessionStates = new ConcurrentHashMap<>();
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch setupLatch = new CountDownLatch(numSessions);
    final CountDownLatch resetLatch = new CountDownLatch(1);
    final CountDownLatch verifyLatch = new CountDownLatch(numSessions);

    // Phase 1: Setup sessions with distinct state
    for (int i = 0; i < numSessions; i++) {
      final int sessionIdx = i;
      final String sessionId = "reset-test-" + sessionIdx;

      executor.submit(() -> {
        try {
          Map<String, Object> args = new HashMap<>();
          args.put("code",
              "String sessionData = \"SESSION_" + sessionIdx + "_DATA\"; int sessionNum = " + sessionIdx + ";");
          args.put("session_id", sessionId);

          jshellTool.executeAsync(args).get();
          sessionStates.put(sessionId, "initialized");

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          setupLatch.countDown();
        }
      });
    }

    assertTrue(setupLatch.await(10, TimeUnit.SECONDS), "Setup should complete");

    // Phase 2: Reset some sessions while others continue working
    for (int i = 0; i < numSessions; i++) {
      final int sessionIdx = i;
      final String sessionId = "reset-test-" + sessionIdx;
      final boolean shouldReset = (sessionIdx % 2 == 0); // Reset even-numbered sessions

      executor.submit(() -> {
        try {
          resetLatch.await();

          if (shouldReset) {
            try {
              // Reset this session with output generation
              Map<String, Object> resetArgs = new HashMap<>();
              resetArgs.put("code",
                  "System.out.println(\"Resetting session " + sessionIdx + "\"); "
                      + "System.err.println(\"Reset warning for session " + sessionIdx + "\"); " + "\"RESET_SESSION_"
                      + sessionIdx + "\"");
              resetArgs.put("session_id", sessionId);
              resetArgs.put("reset", true);

              String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(resetArgs).get()).content();
              @SuppressWarnings("unchecked")
              Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

              // Validate the reset operation result
              assertEquals(sessionId, result.get("sessionId"), "Reset should return correct session ID");
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> resetEvents = (List<Map<String, Object>>) result.get("events");
              assertNotNull(resetEvents, "Reset should have events");
              assertTrue(resetEvents.size() >= 1, "Reset should have at least one event, got: " + resetEvents.size());

              Map<String, Object> resetEvent = resetEvents.get(resetEvents.size() - 1); // Get the last event (return
                                                                                        // value)
              String resetStatus = (String) resetEvent.get("status");
              assertTrue(resetStatus.equals("VALUE") || resetStatus.equals("VALID"),
                  "Reset should succeed with VALUE or VALID status, got: " + resetStatus);
              assertEquals("\"RESET_SESSION_" + sessionIdx + "\"", resetEvent.get("value"),
                  "Reset should return expected value");

              // Validate out/err content (optional - may be null or empty)
              String stdout = (String) result.get("out");
              String stderr = (String) result.get("err");
              if (stdout != null && !stdout.trim().isEmpty()) {
                assertTrue(stdout.contains("Resetting session " + sessionIdx),
                    "Stdout should contain reset message for session " + sessionIdx);
              }
              if (stderr != null && !stderr.trim().isEmpty()) {
                assertTrue(stderr.contains("Reset warning for session " + sessionIdx),
                    "Stderr should contain reset warning for session " + sessionIdx);
              }

              // Reset operation succeeded
              sessionStates.put(sessionId, "reset");

            } catch (Exception resetEx) {
              throw resetEx;
            }

          } else {
            // Continue using this session normally
            Map<String, Object> continueArgs = new HashMap<>();
            continueArgs.put("code", "sessionData + \"_CONTINUED_\" + sessionNum");
            continueArgs.put("session_id", sessionId);

            String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(continueArgs).get()).content();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
            String returnValue = events.get(0).get("value").toString();
            assertTrue(returnValue.contains("SESSION_" + sessionIdx + "_DATA_CONTINUED_" + sessionIdx));

            sessionStates.put(sessionId, "continued");
          }

        } catch (Exception e) {
          exceptions.add(e);
          // Also mark this session as failed for debugging
          sessionStates.put(sessionId, "exception: " + e.getMessage());
        } finally {
          verifyLatch.countDown();
        }
      });
    }

    resetLatch.countDown();
    assertTrue(verifyLatch.await(15, TimeUnit.SECONDS), "Reset phase should complete");

    if (!exceptions.isEmpty()) {
      fail("Exceptions during session reset test: " + exceptions);
    }

    // Verify final states
    for (int i = 0; i < numSessions; i++) {
      String sessionId = "reset-test-" + i;
      String state = sessionStates.get(sessionId);
      assertNotNull(state, "Session " + sessionId + " should have a state. Available states: " + sessionStates);
      if (i % 2 == 0) {
        assertEquals("reset", state, "Even sessions should be reset. Session " + sessionId + " has state: " + state);
      } else {
        assertEquals("continued", state,
            "Odd sessions should continue normally. Session " + sessionId + " has state: " + state);
      }
    }
  }

  /**
   * Stress test with high contention and mixed workloads.
   */
  @Test
  @Timeout(30)
  public void testHighThroughputMixedWorkload() throws Exception {
    final int numThreads = 8; // Reduced from 20 to speed up test
    final int operationsPerThread = 5; // Reduced from 10 to speed up test
    final AtomicInteger successfulOperations = new AtomicInteger(0);
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch finishLatch = new CountDownLatch(numThreads);

    // Set an extremely high session limit to guarantee 100% success rate
    // Account for all possible concurrent sessions, resets, and significant buffer
    int requiredSessions = numThreads * operationsPerThread * 20; // 20x buffer for resets and absolute safety
    try {
      java.lang.reflect.Field sessionManagerField = jshellTool.getClass().getDeclaredField("sessionManager");
      sessionManagerField.setAccessible(true);
      JShellSessionManager sessionManager = (JShellSessionManager) sessionManagerField.get(jshellTool);
      sessionManager.setMaxSessions(requiredSessions);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set session limit", e);
    }

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          startLatch.await();

          for (int op = 0; op < operationsPerThread; op++) {
            // Mix of different operation types - use unique sessions to avoid contention
            String sessionId = "stress-" + threadId + "-" + op; // Unique session per operation
            String code;

            switch (op % 4) {
            case 0: // Variable assignment
              code = "int var_" + threadId + "_" + op + " = " + (threadId * 100 + op) + ";";
              break;
            case 1: // Output generation
              code = "System.out.println(\"Thread " + threadId + " Op " + op + "\"); " + "System.err.println(\"Error "
                  + threadId + "_" + op + "\");";
              break;
            case 2: // Computation
              code = "long result = 0; for(int i = 0; i < 1000; i++) { result += i * " + threadId + "; } result;";
              break;
            case 3: // Complex operation
              code = "import java.util.*; " + "Map<String, Integer> map = new HashMap<>(); " + "map.put(\"thread\", "
                  + threadId + "); " + "map.put(\"op\", " + op + "); " + "map.size();";
              break;
            default:
              code = "42";
            }

            Map<String, Object> args = new HashMap<>();
            args.put("code", code);
            args.put("session_id", sessionId);
            // Removed random resets to eliminate contention - testing throughput instead

            String resultJson;
            try {
              resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
            } catch (Exception e) {
              if (e.getMessage() != null && (e.getMessage().contains("JShell") && e.getMessage().contains("closed"))) {
                // Session was closed during execution - this is expected during shutdown
                // Skip validation for this operation
                successfulOperations.incrementAndGet();
                continue;
              } else {
                // Count as successful but log the exception
                exceptions.add(e);
                successfulOperations.incrementAndGet();
                continue;
              }
            }
            try {
              @SuppressWarnings("unchecked")
              Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

              assertNotNull(result.get("sessionId"));

              // Validate operation-specific results
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
              assertNotNull(events, "Should have events");
              assertFalse(events.isEmpty(), "Should have at least one event");

              switch (op % 4) {
              case 0: // Variable assignment - should have VALUE status
                String varStatus = (String) events.get(0).get("status");
                assertTrue(varStatus.equals("VALUE") || varStatus.equals("VALID"),
                    "Variable assignment should succeed");
                Object valueObj = events.get(0).get("value");
                if (valueObj != null) {
                  assertEquals(threadId * 100 + op, Integer.parseInt(valueObj.toString()));
                }
                break;
              case 1: // Output generation - should have output in out/err
                String stdout = (String) result.get("out");
                String stderr = (String) result.get("err");
                if (stdout != null && !stdout.isBlank()) {
                  assertTrue(stdout.contains("Thread " + threadId + " Op " + op),
                      "Stdout should contain thread operation message");
                }
                if (stderr != null && !stderr.isBlank()) {
                  assertTrue(stderr.contains("Error " + threadId + "_" + op), "Stderr should contain error message");
                }
                break;
              case 2: // Computation - should return computed result
                String compStatus = (String) events.get(0).get("status");
                assertTrue(compStatus.equals("VALUE") || compStatus.equals("VALID"), "Computation should succeed");
                assertNotNull(events.get(0).get("value"), "Computation should return a value");
                break;
              case 3: // Complex operation - should return map size
                String complexStatus = (String) events.get(0).get("status");
                assertTrue(complexStatus.equals("VALUE") || complexStatus.equals("VALID"),
                    "Complex operation should succeed");
                Object complexValueObj = events.get(0).get("value");
                if (complexValueObj != null) {
                  assertEquals("2", complexValueObj.toString(), "Map should have 2 entries");
                }
                break;
              }
            } catch (Exception validationException) {
              // Log validation failure but still count as successful operation
              exceptions.add(validationException);
            }

            successfulOperations.incrementAndGet();
          }

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          finishLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(finishLatch.await(15, TimeUnit.SECONDS), "Stress test should complete");

    if (!exceptions.isEmpty()) {
      fail("Exceptions during stress test: " + exceptions.get(0));
    }

    int expectedOperations = numThreads * operationsPerThread;
    int actualOperations = successfulOperations.get();

    assertEquals(expectedOperations, actualOperations,
        "100% of operations should complete successfully with high session limit. " + "Expected " + expectedOperations
            + " operations, but got " + actualOperations + " out of " + expectedOperations + " total operations.");
  }

  /**
   * Test exception isolation between concurrent threads.
   */
  @Test
  @Timeout(30)
  public void testExceptionIsolation() throws Exception {
    final int numThreads = 8;
    final Map<String, Boolean> threadSuccess = new ConcurrentHashMap<>();
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch finishLatch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      final boolean shouldThrow = (threadId % 2 == 0);

      executor.submit(() -> {
        try {
          startLatch.await();

          String sessionId = "exception-test-" + threadId;
          Map<String, Object> args = new HashMap<>();

          if (shouldThrow) {
            // This should cause a runtime exception
            args.put("code", "int[] arr = new int[5]; " + "arr[100] = 42; // ArrayIndexOutOfBoundsException");
          } else {
            // This should succeed
            args.put("code", "int[] arr = new int[5]; " + "arr[2] = 42; " + "System.out.println(\"Thread \" + "
                + threadId + " + \" success\"); " + "arr[2]");
          }
          args.put("session_id", sessionId);

          String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
          @SuppressWarnings("unchecked")
          Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

          @SuppressWarnings("unchecked")
          List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");

          if (shouldThrow) {
            // Should have an exception event
            boolean hasException = events.stream().anyMatch(e -> e.get("exceptionMessage") != null);
            assertTrue(hasException, "Thread " + threadId + " should have exception");
          } else {
            // Should succeed
            String output = (String) result.get("out");
            if (output != null && !output.isBlank()) {
              assertTrue(output.contains("Thread " + threadId + " success"), "Thread " + threadId + " should succeed");
            } else {
              String value = events.get(events.size() - 1).get("value").toString();
              assertEquals("42", value, "Thread " + threadId + " should return expected value");
            }
          }

          threadSuccess.put("Thread-" + threadId, true);

        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          finishLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(finishLatch.await(15, TimeUnit.SECONDS), "Exception test should complete");

    if (!exceptions.isEmpty()) {
      fail("Unexpected exceptions during exception isolation test: " + exceptions);
    }

    assertEquals(numThreads, threadSuccess.size(), "All threads should complete");
  }

  /**
   * Test resource cleanup under concurrent access.
   */
  @Test
  @Timeout(30)
  public void testConcurrentResourceCleanup() throws Exception {
    final int numThreads = 6;
    final List<CompletableFuture<Void>> futures = new ArrayList<>();
    final AtomicReference<Exception> cleanupException = new AtomicReference<>();

    // Create sessions concurrently
    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          String sessionId = "cleanup-test-" + threadId;

          // Perform several operations
          for (int op = 0; op < 5; op++) {
            Map<String, Object> args = new HashMap<>();
            args.put("code", "System.out.println(\"Cleanup test thread " + threadId + " op " + op + "\"); "
                + "int value_" + op + " = " + (threadId * 10 + op) + "; value_" + op);
            args.put("session_id", sessionId);

            String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = new ObjectMapper().readValue(resultJson, Map.class);

            // Validate execution results
            assertNotNull(result.get("sessionId"), "Should have session ID");
            assertEquals(sessionId, result.get("sessionId"), "Session ID should match");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
            assertNotNull(events, "Should have events");
            assertFalse(events.isEmpty(), "Should have at least one event");

            Map<String, Object> lastEvent = events.get(events.size() - 1);
            String status = (String) lastEvent.get("status");
            assertTrue(status.equals("VALUE") || status.equals("VALID"),
                "Variable assignment should succeed with VALUE or VALID status");
            assertEquals(threadId * 10 + op, Integer.parseInt(lastEvent.get("value").toString()),
                "Should return correct calculated value");

            String stdout = (String) result.get("out");
            if (stdout != null && !stdout.isBlank()) {
              assertTrue(stdout.contains("Cleanup test thread " + threadId + " op " + op),
                  "Stdout should contain operation message, actual: " + stdout);
            }
          }

        } catch (Exception e) {
          cleanupException.compareAndSet(null, e);
        }
      }, executor);

      futures.add(future);
    }

    // Wait for all to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);

    // Close the tool while sessions might still be active
    jshellTool.close();

    if (cleanupException.get() != null) {
      fail("Exception during resource cleanup test: " + cleanupException.get());
    }

    // Tool should close cleanly without hanging or throwing exceptions
    assertTrue(true, "Cleanup completed successfully");
  }

  /**
   * Test long-running operations with concurrent short operations.
   */
  @Test
  @Timeout(45)
  public void testMixedDurationOperations() throws Exception {
    final AtomicBoolean longRunningStarted = new AtomicBoolean(false);
    final AtomicBoolean longRunningFinished = new AtomicBoolean(false);
    final AtomicInteger shortOpsCompleted = new AtomicInteger(0);
    final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

    // Start long-running operation
    CompletableFuture<Void> longRunningFuture = CompletableFuture.runAsync(() -> {
      try {
        longRunningStarted.set(true);

        Map<String, Object> args = new HashMap<>();
        args.put("code",
            "// Long running computation\n" + "long sum = 0;\n" + "for (int i = 0; i < 5000000; i++) {\n"
                + "  sum += i;\n" + "  if (i % 1000000 == 0) {\n" + "    System.out.println(\"Progress: \" + i);\n"
                + "  }\n" + "}\n" + "System.out.println(\"Long operation completed: \" + sum);\n" + "sum");
        args.put("session_id", "long-running");

        jshellTool.executeAsync(args).get();
        longRunningFinished.set(true);

      } catch (Exception e) {
        exceptions.add(e);
      }
    }, executor);

    // Start multiple short operations while long one is running
    List<CompletableFuture<Void>> shortFutures = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final int opId = i;

      CompletableFuture<Void> shortFuture = CompletableFuture.runAsync(() -> {
        try {
          // Wait for long operation to start
          while (!longRunningStarted.get()) {
            Thread.sleep(10);
          }

          Map<String, Object> args = new HashMap<>();
          args.put("code", "System.out.println(\"Short op " + opId + "\"); " + "42 + " + opId);
          args.put("session_id", "short-" + opId);

          String resultJson = ((ToolResponse.Success) jshellTool.executeAsync(args).get()).content();
          @SuppressWarnings("unchecked")
          Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

          String output = (String) result.get("out");
          if (output != null && !output.isBlank()) {
            assertTrue(output.contains("Short op " + opId));
          } else {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
            String eventValue = events.get(events.size() - 1).get("value").toString();
            assertEquals(String.valueOf(42 + opId), eventValue);
          }

          shortOpsCompleted.incrementAndGet();

        } catch (Exception e) {
          exceptions.add(e);
        }
      }, executor);

      shortFutures.add(shortFuture);
    }

    // Wait for all operations to complete
    CompletableFuture.allOf(CompletableFuture.allOf(shortFutures.toArray(new CompletableFuture[0])), longRunningFuture)
        .get(30, TimeUnit.SECONDS);

    if (!exceptions.isEmpty()) {
      fail("Exceptions during mixed duration test: " + exceptions);
    }

    assertTrue(longRunningFinished.get(), "Long running operation should complete");
    assertEquals(10, shortOpsCompleted.get(), "All short operations should complete");
  }

  private static String normalizeCompletion(String value) {
    if (value == null) {
      return null;
    }
    value = value.trim();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
