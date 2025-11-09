package com.bitsapplied.descartes.debugger.breakpoints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

/**
 * Evaluates conditional breakpoint expressions.
 *
 * <p>
 * When a breakpoint is hit, this evaluator checks if the condition (if
 * specified) evaluates to true. Only then should the execution be suspended.
 *
 * <p>
 * Supported condition types:
 * <ul>
 * <li>Boolean expressions (e.g., "x > 10")</li>
 * <li>Comparison expressions (e.g., "status.equals(\"ACTIVE\")")</li>
 * <li>Complex conditions (e.g., "count > 5 && name != null")</li>
 * </ul>
 *
 * <p>
 * Uses the {@link HybridEvaluationProvider} for expression evaluation,
 * providing both performance (Janino) and capability (JShell).
 */
public class ConditionalBreakpointEvaluator {
  private static final Logger logger = LoggerFactory.getLogger(ConditionalBreakpointEvaluator.class);

  private final HybridEvaluationProvider evaluator;

  /**
   * Creates a conditional breakpoint evaluator.
   *
   * @param evaluator the expression evaluator
   */
  public ConditionalBreakpointEvaluator(HybridEvaluationProvider evaluator) {
    this.evaluator = evaluator;
  }

  /**
   * Evaluates a breakpoint condition in the context of a suspended thread.
   *
   * <p>
   * If the condition is null or empty, returns true (unconditional breakpoint).
   * If evaluation fails, logs error and returns true (suspend on error for
   * safety).
   *
   * @param condition the condition expression
   * @param thread    the suspended thread
   * @return true if execution should be suspended, false otherwise
   */
  public boolean shouldSuspend(String condition, ThreadReference thread) {
    // Null or empty condition = unconditional breakpoint
    if (condition == null || condition.trim().isEmpty()) {
      return true;
    }

    try {
      // Validate thread is suspended
      if (!thread.isSuspended()) {
        logger.warn("Thread not suspended, cannot evaluate condition: {}", thread.name());
        return true; // Suspend for safety
      }

      // Get top stack frame
      if (thread.frameCount() == 0) {
        logger.warn("No stack frames available, cannot evaluate condition");
        return true; // Suspend for safety
      }

      StackFrame frame = thread.frame(0);

      // Evaluate condition
      HybridEvaluationProvider.EvaluationResult result = evaluator.evaluate(condition, frame);

      // Parse result as boolean
      boolean shouldSuspend = parseBoolean(result.value());

      logger.debug("Condition '{}' evaluated to {} via {} in {:.2f}ms", condition, shouldSuspend, result.strategy(),
          result.durationMs());

      return shouldSuspend;

    } catch (DebuggerException e) {
      logger.error("Condition evaluation failed for '{}': {}", condition, e.getMessage());
      return true; // Suspend on error for safety
    } catch (Exception e) {
      logger.error("Unexpected error evaluating condition '{}'", condition, e);
      return true; // Suspend on error for safety
    }
  }

  /**
   * Evaluates a condition in a specific stack frame.
   *
   * @param condition the condition expression
   * @param frame     the stack frame
   * @return true if condition evaluates to true
   */
  public boolean evaluateCondition(String condition, StackFrame frame) {
    if (condition == null || condition.trim().isEmpty()) {
      return true;
    }

    try {
      HybridEvaluationProvider.EvaluationResult result = evaluator.evaluate(condition, frame);
      return parseBoolean(result.value());
    } catch (Exception e) {
      logger.error("Condition evaluation failed: {}", e.getMessage());
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Failed to evaluate condition: " + e.getMessage(), e);
    }
  }

  /**
   * Validates that a condition is syntactically correct.
   *
   * @param condition the condition expression
   * @return true if condition is valid
   */
  public boolean validateCondition(String condition) {
    if (condition == null || condition.trim().isEmpty()) {
      return true; // Empty condition is valid (unconditional)
    }

    try {
      // Basic syntax validation
      if (condition.contains(";;") || condition.contains("{") || condition.contains("}")) {
        // Conditions should be single expressions, not statements
        return false;
      }

      // Check for balanced parentheses
      int openParens = 0;
      for (char c : condition.toCharArray()) {
        if (c == '(')
          openParens++;
        else if (c == ')')
          openParens--;
        if (openParens < 0)
          return false;
      }
      if (openParens != 0)
        return false;

      // Additional checks could be added here

      return true;

    } catch (Exception e) {
      logger.debug("Condition validation failed: {}", e.getMessage());
      return false;
    }
  }

  // ========== Internal Methods ==========

  /**
   * Parses a string result as a boolean.
   *
   * <p>
   * Handles various formats:
   * <ul>
   * <li>"true" / "false" (literal boolean)</li>
   * <li>Non-zero numbers (true) / zero (false)</li>
   * <li>Non-null objects (true) / "null" (false)</li>
   * </ul>
   */
  private boolean parseBoolean(String value) {
    if (value == null) {
      return false;
    }

    String trimmed = value.trim();

    // Handle boolean literals
    if (trimmed.equalsIgnoreCase("true")) {
      return true;
    }
    if (trimmed.equalsIgnoreCase("false")) {
      return false;
    }

    // Handle null
    if (trimmed.equalsIgnoreCase("null")) {
      return false;
    }

    // Handle numeric values (non-zero = true)
    try {
      double numValue = Double.parseDouble(trimmed);
      return numValue != 0.0;
    } catch (NumberFormatException e) {
      // Not a number, continue
    }

    // Handle strings (remove quotes if present)
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      String unquoted = trimmed.substring(1, trimmed.length() - 1);
      return !unquoted.isEmpty(); // Empty string = false, non-empty = true
    }

    // Default: non-empty string = true
    return !trimmed.isEmpty();
  }
}
