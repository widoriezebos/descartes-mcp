package com.bitsapplied.descartes.debugger.evaluation;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.StackFrame;

/**
 * Hybrid expression evaluator combining Janino and JShell.
 *
 * <p>
 * Evaluation strategy:
 * <ol>
 * <li>Try Janino first (fast, lightweight)</li>
 * <li>Fall back to JShell if Janino fails or expression is complex</li>
 * </ol>
 *
 * <p>
 * This provides the best balance of performance and capability.
 */
public class HybridEvaluationProvider {
  private static final Logger logger = LoggerFactory.getLogger(HybridEvaluationProvider.class);

  private final ExpressionParser parser;
  private final ExpressionCache cache;
  private final JaninoEvaluator janinoEvaluator;
  private final JShellEvaluator jshellEvaluator;

  /**
   * Creates a hybrid evaluation provider.
   */
  public HybridEvaluationProvider() {
    this.parser = new ExpressionParser();
    this.cache = new ExpressionCache();
    this.janinoEvaluator = new JaninoEvaluator(cache);
    this.jshellEvaluator = new JShellEvaluator();
  }

  /**
   * Evaluates an expression in the context of a stack frame.
   *
   * @param expression the expression to evaluate
   * @param frame      the stack frame providing context
   * @return evaluation result
   * @throws DebuggerException if evaluation fails
   */
  public EvaluationResult evaluate(String expression, StackFrame frame) {
    // Validate syntax
    if (!parser.validateSyntax(expression)) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_COMPILATION_FAILED,
          "Invalid expression syntax: " + expression);
    }

    // Determine evaluation strategy
    boolean isSimple = parser.isSimpleExpression(expression);

    if (isSimple) {
      // Try Janino first
      try {
        long startTime = System.nanoTime();
        String result = janinoEvaluator.evaluate(expression, frame);
        long duration = System.nanoTime() - startTime;

        logger.debug("Janino evaluation succeeded in {}μs", duration / 1000);

        return new EvaluationResult(result, EvaluationStrategy.JANINO, duration / 1_000_000.0);

      } catch (Exception e) {
        logger.debug("Janino failed, falling back to JShell: {}", e.getMessage());
        // Fall through to JShell
      }
    }

    // Use JShell for complex expressions or Janino failures
    try {
      long startTime = System.nanoTime();
      String result = jshellEvaluator.evaluate(expression, frame);
      long duration = System.nanoTime() - startTime;

      logger.debug("JShell evaluation succeeded in {}ms", duration / 1_000_000.0);

      return new EvaluationResult(result, EvaluationStrategy.JSHELL, duration / 1_000_000.0);

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "All evaluation strategies failed: " + e.getMessage(), e);
    }
  }

  /**
   * Gets cache statistics.
   *
   * @return cache stats
   */
  public Map<String, Object> getCacheStats() {
    return cache.getStats();
  }

  /**
   * Clears the expression cache.
   */
  public void clearCache() {
    cache.clear();
  }

  /**
   * Closes the evaluator and releases resources.
   */
  public void close() {
    jshellEvaluator.close();
  }

  /**
   * Evaluation strategy used.
   */
  public enum EvaluationStrategy {
    JANINO, // Fast compilation with Janino
    JSHELL // Full REPL with JShell
  }

  /**
   * Evaluation result with metadata.
   *
   * @param value      the result value as a string
   * @param strategy   the evaluation strategy used
   * @param durationMs evaluation duration in milliseconds
   */
  public record EvaluationResult(String value, EvaluationStrategy strategy, double durationMs) {
    /**
     * Gets a formatted result string.
     *
     * @return formatted result
     */
    public String toFormattedString() {
      return String.format("%s (evaluated via %s in %.2fms)", value, strategy, durationMs);
    }
  }
}
