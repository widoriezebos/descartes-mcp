package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggerTestBase;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager;
import com.bitsapplied.descartes.debugger.events.DebugEvent;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;

/**
 * Integration tests for {@link JdiRemoteEvaluator} using a real debuggee
 * process.
 *
 * <p>
 * Extends {@link DebuggerTestBase} and uses {@code DebuggeeLauncher.launchAndWait()}
 * to launch a {@code SimpleTestApplication} with JDWP in continuous mode. Tests
 * set breakpoints in methods that are periodically called from the continuous
 * loop.
 *
 * <p>
 * Available breakpoint targets in continuous mode:
 * <ul>
 * <li>{@code runContinuously} — runs every iteration (~1s). Has {@code this}
 * but no interesting local variables.</li>
 * <li>{@code methodA} — called every 10 iterations (~10s). Has locals
 * {@code value=10} and {@code doubled=20}.</li>
 * <li>{@code determineGrade} — called every 15 iterations (~15s). Has local
 * {@code score} (85 or 95).</li>
 * </ul>
 */
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
class JdiRemoteEvaluatorIntegrationTest extends DebuggerTestBase {
  private static final Logger logger = LoggerFactory.getLogger(JdiRemoteEvaluatorIntegrationTest.class);

  private static final String TEST_CLASS = "com.bitsapplied.descartes.debugger.SimpleTestApplication";

  private final JdiRemoteEvaluator evaluator = new JdiRemoteEvaluator();

  // ========== Tests using runContinuously (hit every ~1s) ==========

  @Test
  void evaluatesIntegerArithmetic() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("1 + 1", frame);
      assertEquals("2", result);
    });
  }

  @Test
  void evaluatesStringLiteral() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("\"hello\"", frame);
      assertEquals("\"hello\"", result);
    });
  }

  @Test
  void evaluatesNullLiteral() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("null", frame);
      assertEquals("null", result);
    });
  }

  @Test
  void evaluatesBooleanLiteral() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("true", frame);
      assertEquals("true", result);
    });
  }

  @Test
  void evaluatesNullComparison() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("null == null", frame);
      assertEquals("true", result);
    });
  }

  @Test
  void evaluatesThisReference() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("this", frame);
      assertNotNull(result);
      assertTrue(result.contains("SimpleTestApplication"), "this should be a SimpleTestApplication: " + result);
    });
  }

  @Test
  void evaluatesInstanceFieldAccess() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("this.instanceCounter", frame);
      assertNotNull(result);
      int counter = Integer.parseInt(result);
      assertTrue(counter >= 0, "instanceCounter should be non-negative: " + counter);
    });
  }

  @Test
  void evaluatesStringConcat() throws Exception {
    withBreakpointInLoop((frame, thread) -> {
      String result = evaluator.evaluate("\"hello\" + \" world\"", frame);
      assertEquals("\"hello world\"", result);
    });
  }

  // ========== Tests using methodA (hit every ~10s, has locals value=10, doubled=20) ==========

  @Test
  void evaluatesLocalVariableArithmetic() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      // methodA(10): value=10, doubled=20
      String result = evaluator.evaluate("value + doubled", frame);
      assertEquals("30", result);
    });
  }

  @Test
  void evaluatesComparison() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("value > 0", frame);
      assertEquals("true", result);
    });
  }

  @Test
  void evaluatesTernaryExpression() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("value > 0 ? value : doubled", frame);
      assertEquals("10", result);
    });
  }

  @Test
  void evaluatesUnaryNegation() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("-value", frame);
      assertEquals("-10", result);
    });
  }

  @Test
  void evaluatesParenthesizedExpression() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("(value + doubled) * 2", frame);
      assertEquals("60", result);
    });
  }

  @Test
  void evaluatesShortCircuitAnd() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("value > 0 && doubled > 0", frame);
      assertEquals("true", result);
    });
  }

  @Test
  void evaluatesShortCircuitOr() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("value < 0 || doubled > 0", frame);
      assertEquals("true", result);
    });
  }

  @Test
  void evaluatesBooleanNegation() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      String result = evaluator.evaluate("!(value > 0)", frame);
      assertEquals("false", result);
    });
  }

  @Test
  void evaluatesViaHybridProvider() throws Exception {
    withBreakpointAt("methodA", 179, 20, (frame, thread) -> {
      HybridEvaluationProvider provider = debuggerService.getEvaluationProvider();
      HybridEvaluationProvider.EvaluationResult result = provider.evaluate("value + doubled", frame);
      assertNotNull(result);
      assertEquals("30", result.value());
      assertEquals(HybridEvaluationProvider.EvaluationStrategy.JDI, result.strategy());
    });
  }

  // ========== Helper Methods ==========

  @FunctionalInterface
  private interface BreakpointAction {
    void execute(StackFrame frame, ThreadReference thread) throws Exception;
  }

  /**
   * Convenience: breakpoint in the runContinuously loop (hit every ~1s).
   */
  private void withBreakpointInLoop(BreakpointAction action) throws Exception {
    withBreakpointAt("runContinuously", 267, 15, action);
  }

  /**
   * Sets a breakpoint, waits for it to be hit, runs the action with the
   * suspended frame, then cleans up.
   */
  private void withBreakpointAt(String methodName, int approxLine, int timeoutSec, BreakpointAction action)
      throws Exception {
    startDebugSession();

    CountDownLatch breakpointHit = new CountDownLatch(1);
    AtomicReference<BreakpointEvent> eventRef = new AtomicReference<>();

    debuggerService.events().filter(DebugEvent::isBreakpointEvent)
        .map(debugEvent -> (BreakpointEvent) debugEvent.event()).subscribe(event -> {
          eventRef.set(event);
          breakpointHit.countDown();
        });

    Thread.sleep(100);

    BreakpointManager bpManager = debuggerService.getBreakpointManager();
    int resolvedLine = resolveLine(methodName, approxLine);
    long bpId = bpManager.setBreakpoint(TEST_CLASS, resolvedLine);
    assertTrue(bpId > 0, "Breakpoint ID should be positive");

    logger.info("Set breakpoint at {}:{}:{} (bpId={})", TEST_CLASS, methodName, resolvedLine, bpId);

    boolean hit = breakpointHit.await(timeoutSec, TimeUnit.SECONDS);
    assertTrue(hit, "Breakpoint should be hit within timeout (" + timeoutSec + "s)");

    BreakpointEvent event = eventRef.get();
    assertNotNull(event, "Breakpoint event should not be null");

    ThreadReference thread = event.thread();
    assertTrue(thread.isSuspended(), "Thread should be suspended");

    try {
      StackFrame frame = thread.frame(0);
      assertNotNull(frame, "Stack frame should be available");

      action.execute(frame, thread);
    } finally {
      thread.resume();
      bpManager.removeBreakpoint(bpId);
    }
  }

  private int resolveLine(String methodName, int approxLine) {
    try {
      VirtualMachine vm = connectionManager.getCurrentConnection();
      if (vm == null) {
        vm = debuggerService.getVirtualMachine();
      }

      List<ReferenceType> types = vm.classesByName(TEST_CLASS);
      if (types.isEmpty()) {
        throw new IllegalStateException("Class not loaded: " + TEST_CLASS);
      }

      Method method = types.get(0).methodsByName(methodName).stream().findFirst()
          .orElseThrow(() -> new IllegalStateException("Method not found: " + methodName));

      List<Integer> distinctLines = method.allLineLocations().stream().map(Location::lineNumber).filter(n -> n >= 0)
          .distinct().sorted().collect(Collectors.toList());

      if (distinctLines.isEmpty()) {
        throw new IllegalStateException("No line information for method: " + methodName);
      }

      return distinctLines.stream().min(Comparator.comparingInt(line -> Math.abs(line - approxLine)))
          .orElseThrow(() -> new IllegalStateException("Unable to resolve line for " + methodName));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to resolve line for " + methodName + ":" + approxLine, e);
    }
  }
}
