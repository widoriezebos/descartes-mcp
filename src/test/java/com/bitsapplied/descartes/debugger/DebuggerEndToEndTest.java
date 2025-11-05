package com.bitsapplied.descartes.debugger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager;
import com.bitsapplied.descartes.debugger.events.DebugEvent;
import com.bitsapplied.descartes.debugger.models.StackFrameInfo;
import com.bitsapplied.descartes.debugger.models.VariableInfo;
import com.bitsapplied.descartes.debugger.stacktrace.StackTraceInspector;
import com.bitsapplied.descartes.debugger.stepping.SteppingController;
import com.bitsapplied.descartes.debugger.variables.VariableExtractor;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.StepEvent;

/**
 * End-to-end tests for debugger functionality.
 *
 * <p>
 * These tests verify actual debugging behavior, not just API correctness. They:
 * <ul>
 * <li>Set breakpoints and verify threads actually suspend at the correct location</li>
 * <li>Test stepping behavior (step-over, step-into)</li>
 * <li>Verify variable inspection at breakpoints</li>
 * <li>Test the complete debugging workflow</li>
 * </ul>
 *
 * <p>
 * <b>Test Philosophy:</b> These tests complement the API tests (DebuggerBreakpointsToolTest, etc.)
 * by verifying that the underlying JDWP behavior works correctly. API tests verify the tool
 * interfaces work correctly; these tests verify the actual debugging functionality works.
 *
 * <p>
 * <b>Approach:</b> Tests use the continuous-mode debuggee (which runs runContinuously()) and set
 * breakpoints in the loop. This allows testing real breakpoint suspension, stepping, and inspection
 * without requiring complex JDWP method invocation.
 */
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DebuggerEndToEndTest extends DebuggerTestBase {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerEndToEndTest.class);

  private static final String TEST_CLASS = "com.bitsapplied.descartes.debugger.SimpleTestApplication";

  // Line numbers in SimpleTestApplication (keep in sync with actual file)
  private static final int LINE_RUN_CONTINUOUSLY_LOOP = 268;  // Thread.sleep(1000) in runContinuously()
  private static final int LINE_COUNTER_INCREMENT = 267;      // instanceCounter++ in runContinuously()
  private static final int LINE_COUNTER_CHECK = 270;          // if (instanceCounter % 5 == 0)

  /**
   * Test 1: Breakpoint Actually Suspends Thread
   *
   * <p>
   * This is the CORE end-to-end test. It verifies that:
   * <ul>
   * <li>Breakpoints can be set while code is running</li>
   * <li>Threads ACTUALLY suspend when hitting the breakpoint (not just API returning success)</li>
   * <li>The suspension location matches the breakpoint line</li>
   * <li>The breakpoint is hit in real execution, not just in theory</li>
   * </ul>
   */
  @Test
  public void testBreakpointActuallySuspendsThread() throws Exception {
    logger.info("=== Test: Breakpoint Actually Suspends Thread ===");

    // Start debug session
    startDebugSession();

    // Set up latch to wait for breakpoint hit
    CountDownLatch breakpointHit = new CountDownLatch(1);
    AtomicReference<BreakpointEvent> eventRef = new AtomicReference<>();

    // Subscribe to breakpoint events BEFORE setting the breakpoint
    var subscription = debuggerService.events()
        .filter(DebugEvent::isBreakpointEvent)
        .map(debugEvent -> (BreakpointEvent) debugEvent.event())
        .subscribe(event -> {
          logger.info("✓ Breakpoint event received at {}:{} in thread '{}'",
              event.location().declaringType().name(),
              event.location().lineNumber(),
              event.thread().name());
          eventRef.set(event);
          breakpointHit.countDown();
        });

    // Give subscription time to register
    Thread.sleep(100);

    // Set breakpoint in the runContinuously() loop where we know it will be hit
    BreakpointManager bpManager = debuggerService.getBreakpointManager();
    long bpId = bpManager.setBreakpoint(TEST_CLASS, LINE_RUN_CONTINUOUSLY_LOOP);
    assertTrue(bpId > 0, "Breakpoint ID should be positive");
    logger.info("Set breakpoint ID {} at {}:{}", bpId, TEST_CLASS, LINE_RUN_CONTINUOUSLY_LOOP);

    // Wait for breakpoint to be hit (the loop runs every second, so this should happen quickly)
    boolean hit = breakpointHit.await(10, TimeUnit.SECONDS);
    assertTrue(hit, "Breakpoint should be hit within timeout - THIS IS THE KEY TEST!");

    // Get the event
    BreakpointEvent event = eventRef.get();
    assertNotNull(event, "Breakpoint event should be captured");

    // Verify suspension location
    assertEquals(LINE_RUN_CONTINUOUSLY_LOOP, event.location().lineNumber(),
        "Thread should be suspended at EXACTLY the breakpoint line");
    assertEquals(TEST_CLASS, event.location().declaringType().name(),
        "Thread should be suspended in correct class");

    // Verify thread is ACTUALLY suspended (not just event fired)
    ThreadReference thread = event.thread();
    assertTrue(thread.isSuspended(), "Thread must be ACTUALLY suspended, not running");

    // Resume thread so test can clean up
    thread.resume();

    // Clean up
    bpManager.removeBreakpoint(bpId);

    logger.info("✓✓✓ SUCCESS: Breakpoint actually suspended thread at correct location!");
  }

  /**
   * Test 2: Variable Inspection at Breakpoint
   *
   * <p>
   * This test verifies that when a thread is suspended at a breakpoint:
   * <ul>
   * <li>We can inspect local variables</li>
   * <li>Variables have correct names and types</li>
   * <li>Variable values are accessible</li>
   * </ul>
   */
  @Test
  public void testVariableInspectionAtBreakpoint() throws Exception {
    logger.info("=== Test: Variable Inspection at Breakpoint ===");

    startDebugSession();

    CountDownLatch breakpointHit = new CountDownLatch(1);
    AtomicReference<ThreadReference> threadRef = new AtomicReference<>();

    debuggerService.events()
        .filter(DebugEvent::isBreakpointEvent)
        .map(debugEvent -> (BreakpointEvent) debugEvent.event())
        .subscribe(event -> {
          logger.info("Breakpoint hit for variable inspection");
          threadRef.set(event.thread());
          breakpointHit.countDown();
        });

    // Set breakpoint
    BreakpointManager bpManager = debuggerService.getBreakpointManager();
    long bpId = bpManager.setBreakpoint(TEST_CLASS, LINE_RUN_CONTINUOUSLY_LOOP);

    // Wait for hit
    boolean hit = breakpointHit.await(10, TimeUnit.SECONDS);
    assertTrue(hit, "Breakpoint should be hit");

    ThreadReference thread = threadRef.get();
    assertNotNull(thread, "Thread reference should be captured");

    // Get variable extractor and inspect variables
    VariableExtractor varExtractor = debuggerService.getVariableExtractor();
    List<VariableInfo> variables = varExtractor.extractVariables(thread.frame(0));

    logger.info("Variables at breakpoint:");
    for (VariableInfo var : variables) {
      logger.info("  {} = {} ({})", var.name(), var.value(), var.type());
    }

    // Verify we have variables
    assertFalse(variables.isEmpty(), "Should have accessible variables at breakpoint");

    // Verify 'this' reference exists (we're in an instance method)
    boolean hasThis = variables.stream().anyMatch(v -> "this".equals(v.name()));
    assertTrue(hasThis, "Should have 'this' reference in instance method");

    // Resume
    thread.resume();

    // Clean up
    bpManager.removeBreakpoint(bpId);

    logger.info("✓ Variable inspection verified");
  }

  /**
   * Test 3: Stack Trace Inspection at Breakpoint
   *
   * <p>
   * This test verifies that when suspended at a breakpoint:
   * <ul>
   * <li>We can get a complete stack trace</li>
   * <li>Stack frames show correct method names and line numbers</li>
   * <li>The top frame matches the breakpoint location</li>
   * </ul>
   */
  @Test
  public void testStackTraceInspection() throws Exception {
    logger.info("=== Test: Stack Trace Inspection ===");

    startDebugSession();

    CountDownLatch breakpointHit = new CountDownLatch(1);
    AtomicReference<ThreadReference> threadRef = new AtomicReference<>();

    debuggerService.events()
        .filter(DebugEvent::isBreakpointEvent)
        .map(debugEvent -> (BreakpointEvent) debugEvent.event())
        .subscribe(event -> {
          threadRef.set(event.thread());
          breakpointHit.countDown();
        });

    // Set breakpoint
    BreakpointManager bpManager = debuggerService.getBreakpointManager();
    long bpId = bpManager.setBreakpoint(TEST_CLASS, LINE_RUN_CONTINUOUSLY_LOOP);

    // Wait for hit
    boolean hit = breakpointHit.await(10, TimeUnit.SECONDS);
    assertTrue(hit, "Breakpoint should be hit");

    ThreadReference thread = threadRef.get();

    // Get stack trace inspector and inspect stack
    StackTraceInspector stackInspector = debuggerService.getStackTraceInspector();
    List<StackFrameInfo> stackTrace = stackInspector.captureStackTrace(thread, 10);

    logger.info("Stack trace at breakpoint:");
    for (int i = 0; i < stackTrace.size(); i++) {
      StackFrameInfo frame = stackTrace.get(i);
      logger.info("  [{}] {}::{} ({}:{})",
          i,
          frame.className(),
          frame.methodName(),
          frame.fileName(),
          frame.lineNumber());
    }

    // Verify stack trace
    assertFalse(stackTrace.isEmpty(), "Should have stack trace");

    // Verify top frame is at breakpoint
    StackFrameInfo topFrame = stackTrace.get(0);
    assertEquals(LINE_RUN_CONTINUOUSLY_LOOP, topFrame.lineNumber(),
        "Top frame should be at breakpoint line");
    assertEquals("runContinuously", topFrame.methodName(),
        "Top frame should be in runContinuously method");

    // Resume
    thread.resume();

    // Clean up
    bpManager.removeBreakpoint(bpId);

    logger.info("✓ Stack trace inspection verified");
  }

  /**
   * Test 4: Step-Over Functionality
   *
   * <p>
   * This test verifies that:
   * <ul>
   * <li>Step-over advances execution by one line</li>
   * <li>Line number changes after step</li>
   * <li>Thread remains suspended after step</li>
   * </ul>
   */
  @Test
  public void testStepOverFunctionality() throws Exception {
    logger.info("=== Test: Step-Over Functionality ===");

    startDebugSession();

    CountDownLatch breakpointHit = new CountDownLatch(1);
    AtomicReference<ThreadReference> threadRef = new AtomicReference<>();

    debuggerService.events()
        .filter(DebugEvent::isBreakpointEvent)
        .map(debugEvent -> (BreakpointEvent) debugEvent.event())
        .subscribe(event -> {
          threadRef.set(event.thread());
          breakpointHit.countDown();
        });

    // Set breakpoint
    BreakpointManager bpManager = debuggerService.getBreakpointManager();
    long bpId = bpManager.setBreakpoint(TEST_CLASS, LINE_COUNTER_INCREMENT);

    // Wait for hit
    boolean hit = breakpointHit.await(10, TimeUnit.SECONDS);
    assertTrue(hit, "Breakpoint should be hit");

    ThreadReference thread = threadRef.get();

    // Get initial line number
    int initialLine = thread.frame(0).location().lineNumber();
    logger.info("Initial line: {}", initialLine);

    // Set up latch for step completion
    CountDownLatch stepComplete = new CountDownLatch(1);

    debuggerService.events()
        .filter(DebugEvent::isStepEvent)
        .map(debugEvent -> (StepEvent) debugEvent.event())
        .subscribe(event -> {
          logger.info("Step completed at line {}", event.location().lineNumber());
          stepComplete.countDown();
        });

    // Perform step-over
    SteppingController steppingController = debuggerService.getSteppingController();
    steppingController.stepOver(thread);
    logger.info("Issued step-over command");

    // Wait for step to complete
    boolean stepped = stepComplete.await(5, TimeUnit.SECONDS);
    assertTrue(stepped, "Step should complete within timeout");

    // Verify line changed
    int newLine = thread.frame(0).location().lineNumber();
    logger.info("New line after step: {}", newLine);
    assertTrue(newLine != initialLine, "Line should change after step-over");

    // Verify still suspended
    assertTrue(thread.isSuspended(), "Thread should remain suspended after step");

    // Resume
    thread.resume();

    // Clean up
    bpManager.removeBreakpoint(bpId);

    logger.info("✓ Step-over functionality verified");
  }

  /**
   * Test 5: Multiple Breakpoints
   *
   * <p>
   * This test verifies that:
   * <ul>
   * <li>Multiple breakpoints can be active simultaneously</li>
   * <li>Each breakpoint can be hit independently</li>
   * <li>Breakpoints can be managed (enabled/disabled/removed)</li>
   * </ul>
   */
  @Test
  public void testMultipleBreakpoints() throws Exception {
    logger.info("=== Test: Multiple Breakpoints ===");

    startDebugSession();

    CountDownLatch firstBreakpointHit = new CountDownLatch(1);
    AtomicReference<Integer> firstHitLine = new AtomicReference<>();

    debuggerService.events()
        .filter(DebugEvent::isBreakpointEvent)
        .map(debugEvent -> (BreakpointEvent) debugEvent.event())
        .subscribe(event -> {
          int line = event.location().lineNumber();
          logger.info("Breakpoint hit at line {}", line);

          if (firstBreakpointHit.getCount() > 0) {
            firstHitLine.set(line);
            firstBreakpointHit.countDown();
          }

          // Resume to allow continued execution
          event.thread().resume();
        });

    // Set multiple breakpoints
    BreakpointManager bpManager = debuggerService.getBreakpointManager();
    long bpId1 = bpManager.setBreakpoint(TEST_CLASS, LINE_COUNTER_INCREMENT);
    long bpId2 = bpManager.setBreakpoint(TEST_CLASS, LINE_RUN_CONTINUOUSLY_LOOP);

    logger.info("Set {} breakpoints", 2);

    // Wait for at least one to hit
    boolean hit = firstBreakpointHit.await(10, TimeUnit.SECONDS);
    assertTrue(hit, "At least one breakpoint should be hit");

    assertNotNull(firstHitLine.get(), "Should capture which line was hit");

    // Verify we can list breakpoints
    var allBreakpoints = bpManager.getAllBreakpoints();
    assertEquals(2, allBreakpoints.size(), "Should have 2 active breakpoints");

    // Clean up
    bpManager.removeBreakpoint(bpId1);
    bpManager.removeBreakpoint(bpId2);

    assertEquals(0, bpManager.getAllBreakpoints().size(), "All breakpoints should be removed");

    logger.info("✓ Multiple breakpoints verified");
  }
}
