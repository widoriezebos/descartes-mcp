package com.bitsapplied.descartes.debugger.evaluation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.StackFrame;

/**
 * Hybrid expression evaluator combining JDI remote evaluation, Janino, and
 * JShell.
 *
 * <p>
 * Evaluation strategy depends on the mode:
 * <ul>
 * <li><b>Remote-only mode</b> (proxy): Uses only {@link JdiRemoteEvaluator}
 * which evaluates expressions remotely in the debuggee via JDWP. No local
 * JShell or Janino evaluation is attempted.</li>
 * <li><b>Embedded mode</b> (default): Uses Janino first (fast), then falls back
 * to JShell (full REPL) for local evaluation.</li>
 * </ul>
 */
public class HybridEvaluationProvider {
  private static final Logger logger = LoggerFactory.getLogger(HybridEvaluationProvider.class);

  private final boolean remoteOnly;
  private final ExpressionParser parser;
  private final ExpressionCache cache;
  private final JdiRemoteEvaluator jdiEvaluator;
  private final JaninoEvaluator janinoEvaluator;
  private final JShellEvaluator jshellEvaluator;

  /**
   * Creates a hybrid evaluation provider in embedded mode (Janino + JShell).
   */
  public HybridEvaluationProvider() {
    this(false);
  }

  /**
   * Creates a hybrid evaluation provider.
   *
   * @param remoteOnly if true, only use JDI remote evaluation (for proxy mode);
   *                   if false, use JDI with Janino/JShell fallback (embedded
   *                   mode)
   */
  public HybridEvaluationProvider(boolean remoteOnly) {
    this.remoteOnly = remoteOnly;
    this.parser = new ExpressionParser();
    this.jdiEvaluator = new JdiRemoteEvaluator();

    if (remoteOnly) {
      this.cache = null;
      this.janinoEvaluator = null;
      this.jshellEvaluator = null;
      logger.info("HybridEvaluationProvider created in remote-only mode (JDI only)");
    } else {
      this.cache = new ExpressionCache();
      this.janinoEvaluator = new JaninoEvaluator(cache);
      this.jshellEvaluator = new JShellEvaluator();
      logger.info("HybridEvaluationProvider created in embedded mode (Janino + JShell)");
    }
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

    if (remoteOnly) {
      return evaluateRemoteOnly(expression, frame);
    }

    return evaluateEmbedded(expression, frame);
  }

  private EvaluationResult evaluateRemoteOnly(String expression, StackFrame frame) {
    try {
      long startTime = System.nanoTime();
      String result = jdiEvaluator.evaluate(expression, frame);
      long duration = System.nanoTime() - startTime;

      logger.debug("JDI remote evaluation succeeded in {}ms", duration / 1_000_000.0);
      return new EvaluationResult(result, EvaluationStrategy.JDI, duration / 1_000_000.0);
    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      String detail = e.getMessage() != null ? e.getMessage() : e.toString();
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "JDI remote evaluation failed: " + detail, e);
    }
  }

  private EvaluationResult evaluateEmbedded(String expression, StackFrame frame) {
    // Use Janino first for simple expressions
    boolean isSimple = parser.isSimpleExpression(expression);
    Exception janinoFailure = null;

    if (isSimple) {
      try {
        long startTime = System.nanoTime();
        String result = janinoEvaluator.evaluate(expression, frame);
        long duration = System.nanoTime() - startTime;

        logger.debug("Janino evaluation succeeded in {}μs", duration / 1000);
        return new EvaluationResult(result, EvaluationStrategy.JANINO, duration / 1_000_000.0);
      } catch (Exception e) {
        janinoFailure = e;
        logger.debug("Janino failed, falling back to JShell: {}", e.getMessage());
      }
    }

    // Fall back to JShell
    try {
      long startTime = System.nanoTime();
      String result = jshellEvaluator.evaluate(expression, frame);
      long duration = System.nanoTime() - startTime;

      logger.debug("JShell evaluation succeeded in {}ms", duration / 1_000_000.0);
      return new EvaluationResult(result, EvaluationStrategy.JSHELL, duration / 1_000_000.0);
    } catch (Exception e) {
      StringBuilder message = new StringBuilder("All evaluation strategies failed:");
      if (janinoFailure != null) {
        message.append(" [JANINO] ").append(janinoFailure.getMessage()).append(";");
      }
      message.append(" [JSHELL] ").append(e.getMessage());
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, message.toString(), e);
    }
  }

  /**
   * Returns the strategy list that may be attempted in this provider mode.
   */
  public List<EvaluationStrategy> getSupportedStrategies() {
    return remoteOnly ? List.of(EvaluationStrategy.JDI) : List.of(EvaluationStrategy.JANINO, EvaluationStrategy.JSHELL);
  }

  /**
   * Returns true when provider is configured for JDI-only remote evaluation.
   */
  public boolean isRemoteOnly() {
    return remoteOnly;
  }

  /**
   * Gets cache statistics.
   *
   * @return cache stats (empty map in remote-only mode)
   */
  public Map<String, Object> getCacheStats() {
    return cache != null ? cache.getStats() : Collections.emptyMap();
  }

  /**
   * Clears the expression cache.
   */
  public void clearCache() {
    if (cache != null) {
      cache.clear();
    }
  }

  /**
   * Closes the evaluator and releases resources.
   */
  public void close() {
    if (jshellEvaluator != null) {
      jshellEvaluator.close();
    }
  }

  /**
   * Evaluation strategy used.
   */
  public enum EvaluationStrategy {
    JDI, // Remote evaluation via JDI/JDWP in the debuggee JVM
    JANINO, // Fast local compilation with Janino
    JSHELL // Full local REPL with JShell
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
