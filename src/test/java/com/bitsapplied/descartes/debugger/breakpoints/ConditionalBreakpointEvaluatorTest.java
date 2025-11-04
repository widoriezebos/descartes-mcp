package com.bitsapplied.descartes.debugger.breakpoints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

/**
 * Tests for ConditionalBreakpointEvaluator.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Condition evaluation (true/false results)</li>
 * <li>Boolean parsing from various formats</li>
 * <li>Condition validation</li>
 * <li>Error handling (suspend on error for safety)</li>
 * <li>Null/empty condition handling</li>
 * </ul>
 *
 * <p>
 * Note: Uses mocked HybridEvaluationProvider for unit testing. Integration
 * tests will cover end-to-end evaluation.
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.OTHER })
public class ConditionalBreakpointEvaluatorTest {
  private static final Logger logger = LoggerFactory.getLogger(ConditionalBreakpointEvaluatorTest.class);

  private ConditionalBreakpointEvaluator evaluator;
  private HybridEvaluationProvider mockProvider;
  private ThreadReference mockThread;
  private StackFrame mockFrame;

  @BeforeEach
  public void setUp() {
    mockProvider = mock(HybridEvaluationProvider.class);
    mockThread = mock(ThreadReference.class);
    mockFrame = mock(StackFrame.class);

    evaluator = new ConditionalBreakpointEvaluator(mockProvider);
  }

  /**
   * Tests that null condition returns true (unconditional).
   */
  @Test
  public void testNullConditionReturnsTrue() throws Exception {
    logger.info("Testing null condition returns true...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    boolean result = evaluator.shouldSuspend(null, mockThread);

    assertTrue(result, "Null condition should return true (unconditional)");

    logger.info("Null condition returns true test passed");
  }

  /**
   * Tests that empty condition returns true (unconditional).
   */
  @Test
  public void testEmptyConditionReturnsTrue() throws Exception {
    logger.info("Testing empty condition returns true...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    boolean result = evaluator.shouldSuspend("", mockThread);

    assertTrue(result, "Empty condition should return true (unconditional)");

    logger.info("Empty condition returns true test passed");
  }

  /**
   * Tests that blank condition returns true (unconditional).
   */
  @Test
  public void testBlankConditionReturnsTrue() throws Exception {
    logger.info("Testing blank condition returns true...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    boolean result = evaluator.shouldSuspend("   ", mockThread);

    assertTrue(result, "Blank condition should return true (unconditional)");

    logger.info("Blank condition returns true test passed");
  }

  /**
   * Tests condition that evaluates to "true".
   */
  @Test
  public void testConditionEvaluatesToTrue() throws Exception {
    logger.info("Testing condition evaluates to true...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    HybridEvaluationProvider.EvaluationResult evalResult = new HybridEvaluationProvider.EvaluationResult("true",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);

    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult);

    boolean result = evaluator.shouldSuspend("x > 10", mockThread);

    assertTrue(result, "Condition evaluating to 'true' should return true");

    logger.info("Condition evaluates to true test passed");
  }

  /**
   * Tests condition that evaluates to "false".
   */
  @Test
  public void testConditionEvaluatesToFalse() throws Exception {
    logger.info("Testing condition evaluates to false...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    HybridEvaluationProvider.EvaluationResult evalResult = new HybridEvaluationProvider.EvaluationResult("false",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);

    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult);

    boolean result = evaluator.shouldSuspend("x < 5", mockThread);

    assertFalse(result, "Condition evaluating to 'false' should return false");

    logger.info("Condition evaluates to false test passed");
  }

  /**
   * Tests parsing boolean from numeric value (non-zero = true).
   */
  @Test
  public void testParseNumericAsBoolean() throws Exception {
    logger.info("Testing parse numeric as boolean...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    // Non-zero should be true
    HybridEvaluationProvider.EvaluationResult evalResult1 = new HybridEvaluationProvider.EvaluationResult("42",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);
    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult1);

    boolean result1 = evaluator.shouldSuspend("count", mockThread);
    assertTrue(result1, "Non-zero numeric should be true");

    // Zero should be false
    HybridEvaluationProvider.EvaluationResult evalResult2 = new HybridEvaluationProvider.EvaluationResult("0",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);
    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult2);

    boolean result2 = evaluator.shouldSuspend("count", mockThread);
    assertFalse(result2, "Zero should be false");

    logger.info("Parse numeric as boolean test passed");
  }

  /**
   * Tests parsing "null" string as false.
   */
  @Test
  public void testParseNullStringAsFalse() throws Exception {
    logger.info("Testing parse 'null' string as false...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    HybridEvaluationProvider.EvaluationResult evalResult = new HybridEvaluationProvider.EvaluationResult("null",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);

    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult);

    boolean result = evaluator.shouldSuspend("obj", mockThread);

    assertFalse(result, "'null' string should be false");

    logger.info("Parse 'null' string as false test passed");
  }

  /**
   * Tests parsing quoted strings.
   */
  @Test
  public void testParseQuotedStrings() throws Exception {
    logger.info("Testing parse quoted strings...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    // Non-empty quoted string = true
    HybridEvaluationProvider.EvaluationResult evalResult1 = new HybridEvaluationProvider.EvaluationResult("\"hello\"",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);
    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult1);

    boolean result1 = evaluator.shouldSuspend("name", mockThread);
    assertTrue(result1, "Non-empty quoted string should be true");

    // Empty quoted string = false
    HybridEvaluationProvider.EvaluationResult evalResult2 = new HybridEvaluationProvider.EvaluationResult("\"\"",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);
    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult2);

    boolean result2 = evaluator.shouldSuspend("name", mockThread);
    assertFalse(result2, "Empty quoted string should be false");

    logger.info("Parse quoted strings test passed");
  }

  /**
   * Tests that evaluation error returns true (suspend for safety).
   */
  @Test
  public void testEvaluationErrorReturnsTrue() throws Exception {
    logger.info("Testing evaluation error returns true (suspend for safety)...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    when(mockProvider.evaluate(anyString(), any(StackFrame.class)))
        .thenThrow(new DebuggerException(null, "Evaluation failed"));

    boolean result = evaluator.shouldSuspend("invalid.expression", mockThread);

    assertTrue(result, "Evaluation error should return true (suspend for safety)");

    logger.info("Evaluation error returns true test passed");
  }

  /**
   * Tests that thread not suspended returns true (suspend for safety).
   */
  @Test
  public void testThreadNotSuspendedReturnsTrue() throws Exception {
    logger.info("Testing thread not suspended returns true...");

    when(mockThread.isSuspended()).thenReturn(false);
    when(mockThread.name()).thenReturn("test-thread");

    boolean result = evaluator.shouldSuspend("x > 10", mockThread);

    assertTrue(result, "Thread not suspended should return true (suspend for safety)");

    logger.info("Thread not suspended returns true test passed");
  }

  /**
   * Tests that no stack frames returns true (suspend for safety).
   */
  @Test
  public void testNoStackFramesReturnsTrue() throws Exception {
    logger.info("Testing no stack frames returns true...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(0);

    boolean result = evaluator.shouldSuspend("x > 10", mockThread);

    assertTrue(result, "No stack frames should return true (suspend for safety)");

    logger.info("No stack frames returns true test passed");
  }

  /**
   * Tests direct evaluateCondition method.
   */
  @Test
  public void testEvaluateConditionDirect() throws Exception {
    logger.info("Testing evaluate condition direct...");

    HybridEvaluationProvider.EvaluationResult evalResult = new HybridEvaluationProvider.EvaluationResult("true",
        HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);

    when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult);

    boolean result = evaluator.evaluateCondition("x > 10", mockFrame);

    assertTrue(result, "Should evaluate condition directly");

    logger.info("Evaluate condition direct test passed");
  }

  /**
   * Tests evaluateCondition throws on error.
   */
  @Test
  public void testEvaluateConditionThrowsOnError() throws Exception {
    logger.info("Testing evaluate condition throws on error...");

    when(mockProvider.evaluate(anyString(), any(StackFrame.class)))
        .thenThrow(new RuntimeException("Evaluation failed"));

    assertThrows(DebuggerException.class, () -> evaluator.evaluateCondition("invalid", mockFrame),
        "Should throw DebuggerException on evaluation error");

    logger.info("Evaluate condition throws on error test passed");
  }

  /**
   * Tests validation of valid conditions.
   */
  @Test
  public void testValidateValidConditions() {
    logger.info("Testing validate valid conditions...");

    assertTrue(evaluator.validateCondition(null), "Null condition should be valid");
    assertTrue(evaluator.validateCondition(""), "Empty condition should be valid");
    assertTrue(evaluator.validateCondition("   "), "Blank condition should be valid");
    assertTrue(evaluator.validateCondition("x > 10"), "Simple comparison should be valid");
    assertTrue(evaluator.validateCondition("x > 10 && y < 5"), "Logical AND should be valid");
    assertTrue(evaluator.validateCondition("name.equals(\"test\")"), "Method call should be valid");
    assertTrue(evaluator.validateCondition("(a + b) > (c * d)"), "Balanced parentheses should be valid");

    logger.info("Validate valid conditions test passed");
  }

  /**
   * Tests validation of invalid conditions.
   */
  @Test
  public void testValidateInvalidConditions() {
    logger.info("Testing validate invalid conditions...");

    assertFalse(evaluator.validateCondition("x > 10;;"), "Double semicolon should be invalid");
    assertFalse(evaluator.validateCondition("if (x > 10) { }"), "Statement with braces should be invalid");
    assertFalse(evaluator.validateCondition("x > 10)"), "Unbalanced right paren should be invalid");
    assertFalse(evaluator.validateCondition("(x > 10"), "Unbalanced left paren should be invalid");
    assertFalse(evaluator.validateCondition("((x > 10)"), "Unbalanced nested parens should be invalid");

    logger.info("Validate invalid conditions test passed");
  }

  /**
   * Tests case-insensitive boolean parsing.
   */
  @Test
  public void testCaseInsensitiveBooleanParsing() throws Exception {
    logger.info("Testing case-insensitive boolean parsing...");

    when(mockThread.isSuspended()).thenReturn(true);
    when(mockThread.frameCount()).thenReturn(1);
    when(mockThread.frame(0)).thenReturn(mockFrame);

    // Test various cases of "true"
    String[] trueCases = { "true", "True", "TRUE", "TrUe" };
    for (String trueCase : trueCases) {
      HybridEvaluationProvider.EvaluationResult evalResult = new HybridEvaluationProvider.EvaluationResult(trueCase,
          HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);
      when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult);

      boolean result = evaluator.shouldSuspend("condition", mockThread);
      assertTrue(result, "'" + trueCase + "' should be parsed as true");
    }

    // Test various cases of "false"
    String[] falseCases = { "false", "False", "FALSE", "FaLsE" };
    for (String falseCase : falseCases) {
      HybridEvaluationProvider.EvaluationResult evalResult = new HybridEvaluationProvider.EvaluationResult(falseCase,
          HybridEvaluationProvider.EvaluationStrategy.JANINO, 10.0);
      when(mockProvider.evaluate(anyString(), any(StackFrame.class))).thenReturn(evalResult);

      boolean result = evaluator.shouldSuspend("condition", mockThread);
      assertFalse(result, "'" + falseCase + "' should be parsed as false");
    }

    logger.info("Case-insensitive boolean parsing test passed");
  }
}
