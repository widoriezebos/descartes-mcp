package com.bitsapplied.descartes.debugger.watch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

/**
 * Manages watch expressions for debugging.
 *
 * <p>
 * Watch expressions are evaluated automatically when execution suspends,
 * allowing tracking of variable values and expressions over time.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Add/remove watch expressions</li>
 * <li>Evaluate all watches in current context</li>
 * <li>Track value changes between evaluations</li>
 * <li>Enable/disable individual watches</li>
 * </ul>
 *
 * <p>
 * Thread Safety: This class uses concurrent data structures for thread-safe
 * access.
 */
public class WatchExpressionManager {
  private static final Logger logger = LoggerFactory.getLogger(WatchExpressionManager.class);

  private final HybridEvaluationProvider evaluator;

  // Watch storage: ID -> WatchExpression
  private final Map<Long, WatchExpression> watches = new ConcurrentHashMap<>();

  // Watch ID generator
  private final AtomicLong nextWatchId = new AtomicLong(1);

  /**
   * Creates a watch expression manager.
   *
   * @param evaluator the expression evaluator
   */
  public WatchExpressionManager(HybridEvaluationProvider evaluator) {
    this.evaluator = evaluator;
  }

  /**
   * Adds a watch expression.
   *
   * @param expression the expression to watch
   * @return the watch ID
   */
  public long addWatch(String expression) {
    return addWatch(expression, null);
  }

  /**
   * Adds a watch expression with a display name.
   *
   * @param expression  the expression to watch
   * @param displayName the display name (null to use expression)
   * @return the watch ID
   */
  public long addWatch(String expression, String displayName) {
    if (expression == null || expression.trim().isEmpty()) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, "Watch expression cannot be empty");
    }

    long id = nextWatchId.getAndIncrement();

    WatchExpression watch = new WatchExpression(id, expression, displayName != null ? displayName : expression);

    watches.put(id, watch);

    logger.info("Watch expression added: ID={}, expression='{}'", id, expression);

    return id;
  }

  /**
   * Removes a watch expression.
   *
   * @param watchId the watch ID
   * @throws DebuggerException if watch not found
   */
  public void removeWatch(long watchId) {
    WatchExpression removed = watches.remove(watchId);

    if (removed == null) {
      throw new DebuggerException(DebuggerErrorCode.VARIABLE_NOT_FOUND, "Watch expression not found: " + watchId);
    }

    logger.info("Watch expression removed: ID={}, expression='{}'", watchId, removed.expression);
  }

  /**
   * Removes all watch expressions.
   */
  public void removeAllWatches() {
    int count = watches.size();
    watches.clear();
    logger.info("All {} watch expressions removed", count);
  }

  /**
   * Enables a watch expression.
   *
   * @param watchId the watch ID
   * @throws DebuggerException if watch not found
   */
  public void enableWatch(long watchId) {
    WatchExpression watch = watches.get(watchId);
    if (watch == null) {
      throw new DebuggerException(DebuggerErrorCode.VARIABLE_NOT_FOUND, "Watch expression not found: " + watchId);
    }

    watch.enabled = true;
    logger.debug("Watch expression enabled: ID={}", watchId);
  }

  /**
   * Disables a watch expression.
   *
   * @param watchId the watch ID
   * @throws DebuggerException if watch not found
   */
  public void disableWatch(long watchId) {
    WatchExpression watch = watches.get(watchId);
    if (watch == null) {
      throw new DebuggerException(DebuggerErrorCode.VARIABLE_NOT_FOUND, "Watch expression not found: " + watchId);
    }

    watch.enabled = false;
    logger.debug("Watch expression disabled: ID={}", watchId);
  }

  /**
   * Evaluates all enabled watch expressions in the context of a thread.
   *
   * @param thread the suspended thread
   * @return list of watch results
   */
  public List<WatchResult> evaluateAll(ThreadReference thread) {
    List<WatchResult> results = new ArrayList<>();

    try {
      if (!thread.isSuspended()) {
        logger.warn("Thread not suspended, cannot evaluate watches");
        return results;
      }

      if (thread.frameCount() == 0) {
        logger.warn("No stack frames available, cannot evaluate watches");
        return results;
      }

      StackFrame frame = thread.frame(0);

      for (WatchExpression watch : watches.values()) {
        if (!watch.enabled) {
          continue; // Skip disabled watches
        }

        WatchResult result = evaluateWatch(watch, frame);
        results.add(result);
      }

    } catch (Exception e) {
      logger.error("Error evaluating watches", e);
    }

    return results;
  }

  /**
   * Evaluates all enabled watches in a specific stack frame.
   *
   * @param frame the stack frame
   * @return list of watch results
   */
  public List<WatchResult> evaluateAll(StackFrame frame) {
    List<WatchResult> results = new ArrayList<>();

    for (WatchExpression watch : watches.values()) {
      if (!watch.enabled) {
        continue;
      }

      WatchResult result = evaluateWatch(watch, frame);
      results.add(result);
    }

    return results;
  }

  /**
   * Evaluates a single watch expression.
   *
   * @param watchId the watch ID
   * @param frame   the stack frame
   * @return the watch result
   * @throws DebuggerException if watch not found
   */
  public WatchResult evaluateWatch(long watchId, StackFrame frame) {
    WatchExpression watch = watches.get(watchId);
    if (watch == null) {
      throw new DebuggerException(DebuggerErrorCode.VARIABLE_NOT_FOUND, "Watch expression not found: " + watchId);
    }

    return evaluateWatch(watch, frame);
  }

  /**
   * Lists all watch expressions.
   *
   * @return list of watch info maps
   */
  public List<Map<String, Object>> listWatches() {
    return watches.values().stream()
        .map(w -> Map.of("id", (Object) w.id, "expression", w.expression, "display_name", w.displayName, "enabled",
            w.enabled, "last_value", w.lastValue != null ? w.lastValue : "not evaluated", "evaluation_count",
            w.evaluationCount))
        .toList();
  }

  // ========== Internal Methods ==========

  /**
   * Evaluates a single watch expression.
   */
  private WatchResult evaluateWatch(WatchExpression watch, StackFrame frame) {
    try {
      long startTime = System.nanoTime();

      HybridEvaluationProvider.EvaluationResult result = evaluator.evaluate(watch.expression, frame);

      long durationMs = (System.nanoTime() - startTime) / 1_000_000;

      // Track value changes
      boolean valueChanged = !result.value().equals(watch.lastValue);
      watch.lastValue = result.value();
      watch.evaluationCount++;

      logger.debug("Watch '{}' evaluated to '{}' (changed={}) in {}ms", watch.expression, result.value(), valueChanged,
          durationMs);

      return new WatchResult(watch.id, watch.expression, watch.displayName, result.value(), valueChanged,
          result.strategy().name(), durationMs, null // No error
      );

    } catch (Exception e) {
      logger.debug("Watch '{}' evaluation failed: {}", watch.expression, e.getMessage());

      watch.evaluationCount++;

      return new WatchResult(watch.id, watch.expression, watch.displayName, null, false, null, 0, e.getMessage() // Error
                                                                                                                 // message
      );
    }
  }

  // ========== Inner Classes ==========

  /**
   * Represents a watch expression.
   */
  private static class WatchExpression {
    final long id;
    final String expression;
    final String displayName;
    boolean enabled = true;
    String lastValue = null;
    long evaluationCount = 0;

    WatchExpression(long id, String expression, String displayName) {
      this.id = id;
      this.expression = expression;
      this.displayName = displayName;
    }
  }

  /**
   * Result of evaluating a watch expression.
   *
   * @param watchId      the watch ID
   * @param expression   the watch expression
   * @param displayName  the display name
   * @param value        the evaluated value (null if error)
   * @param valueChanged whether value changed since last evaluation
   * @param strategy     the evaluation strategy used
   * @param durationMs   evaluation duration in milliseconds
   * @param error        error message (null if success)
   */
  public record WatchResult(long watchId, String expression, String displayName, String value, boolean valueChanged,
      String strategy, long durationMs, String error) {
    /**
     * Checks if evaluation was successful.
     *
     * @return true if no error
     */
    public boolean isSuccess() {
      return error == null;
    }

    /**
     * Formats the result as a string.
     *
     * @return formatted result
     */
    public String toFormattedString() {
      if (error != null) {
        return String.format("[%d] %s = ERROR: %s", watchId, displayName, error);
      }
      String changeIndicator = valueChanged ? " (CHANGED)" : "";
      return String.format("[%d] %s = %s%s", watchId, displayName, value, changeIndicator);
    }
  }
}
