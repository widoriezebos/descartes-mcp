package com.bitsapplied.descartes.debugger.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks debugger performance metrics.
 *
 * <p>
 * Collects metrics about debugger operations including:
 * <ul>
 * <li>Breakpoint operations (set, hit, remove)</li>
 * <li>Step operations (over, into, out)</li>
 * <li>Expression evaluations (count, duration, success rate)</li>
 * <li>Thread operations (suspend, resume)</li>
 * <li>Event processing statistics</li>
 * </ul>
 *
 * <p>
 * Thread Safety: This class uses atomic counters and concurrent data structures
 * for thread-safe metric collection.
 *
 * <p>
 * Usage:
 * 
 * <pre>{@code
 * DebuggerMetrics metrics = new DebuggerMetrics();
 *
 * metrics.recordBreakpointSet();
 * metrics.recordExpressionEvaluation(duration, true);
 *
 * Map<String, Object> stats = metrics.getMetrics();
 * }</pre>
 */
public class DebuggerMetrics {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerMetrics.class);

  // Session tracking
  private final Instant sessionStartTime;
  private volatile Instant sessionEndTime;

  // Breakpoint metrics
  private final LongAdder breakpointsSet = new LongAdder();
  private final LongAdder breakpointsHit = new LongAdder();
  private final LongAdder breakpointsRemoved = new LongAdder();
  private final LongAdder conditionalBreakpointsEvaluated = new LongAdder();
  private final LongAdder conditionalBreakpointsFailed = new LongAdder();

  // Step metrics
  private final LongAdder stepsOver = new LongAdder();
  private final LongAdder stepsInto = new LongAdder();
  private final LongAdder stepsOut = new LongAdder();

  // Expression evaluation metrics
  private final LongAdder expressionEvaluations = new LongAdder();
  private final LongAdder expressionEvaluationSuccesses = new LongAdder();
  private final LongAdder expressionEvaluationFailures = new LongAdder();
  private final LongAdder janinoEvaluations = new LongAdder();
  private final LongAdder jshellEvaluations = new LongAdder();
  private final AtomicLong totalEvaluationTimeMs = new AtomicLong(0);
  private final AtomicLong minEvaluationTimeMs = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong maxEvaluationTimeMs = new AtomicLong(0);

  // Thread metrics
  private final LongAdder threadSuspends = new LongAdder();
  private final LongAdder threadResumes = new LongAdder();
  private final AtomicLong peakThreadCount = new AtomicLong(0);

  // Variable metrics
  private final LongAdder variableInspections = new LongAdder();
  private final LongAdder variableReferences = new LongAdder();

  // Watch expression metrics
  private final LongAdder watchesAdded = new LongAdder();
  private final LongAdder watchesRemoved = new LongAdder();
  private final LongAdder watchEvaluations = new LongAdder();

  // Event metrics
  private final Map<String, LongAdder> eventCounts = new ConcurrentHashMap<>();

  /**
   * Creates debugger metrics.
   */
  public DebuggerMetrics() {
    this.sessionStartTime = Instant.now();
  }

  // ========== Recording Methods ==========

  /**
   * Records a breakpoint being set.
   */
  public void recordBreakpointSet() {
    breakpointsSet.increment();
  }

  /**
   * Records a breakpoint being hit.
   */
  public void recordBreakpointHit() {
    breakpointsHit.increment();
  }

  /**
   * Records a breakpoint being removed.
   */
  public void recordBreakpointRemoved() {
    breakpointsRemoved.increment();
  }

  /**
   * Records a conditional breakpoint evaluation.
   *
   * @param success whether evaluation succeeded
   */
  public void recordConditionalBreakpointEvaluation(boolean success) {
    conditionalBreakpointsEvaluated.increment();
    if (!success) {
      conditionalBreakpointsFailed.increment();
    }
  }

  /**
   * Records a step over operation.
   */
  public void recordStepOver() {
    stepsOver.increment();
  }

  /**
   * Records a step into operation.
   */
  public void recordStepInto() {
    stepsInto.increment();
  }

  /**
   * Records a step out operation.
   */
  public void recordStepOut() {
    stepsOut.increment();
  }

  /**
   * Records an expression evaluation.
   *
   * @param durationMs evaluation duration in milliseconds
   * @param success    whether evaluation succeeded
   * @param isJanino   whether Janino was used (vs JShell)
   */
  public void recordExpressionEvaluation(long durationMs, boolean success, boolean isJanino) {
    expressionEvaluations.increment();

    if (success) {
      expressionEvaluationSuccesses.increment();
    } else {
      expressionEvaluationFailures.increment();
    }

    if (isJanino) {
      janinoEvaluations.increment();
    } else {
      jshellEvaluations.increment();
    }

    // Update duration stats
    totalEvaluationTimeMs.addAndGet(durationMs);
    updateMin(minEvaluationTimeMs, durationMs);
    updateMax(maxEvaluationTimeMs, durationMs);
  }

  /**
   * Records a thread suspend operation.
   */
  public void recordThreadSuspend() {
    threadSuspends.increment();
  }

  /**
   * Records a thread resume operation.
   */
  public void recordThreadResume() {
    threadResumes.increment();
  }

  /**
   * Records current thread count for peak tracking.
   *
   * @param currentCount current thread count
   */
  public void recordThreadCount(long currentCount) {
    updateMax(peakThreadCount, currentCount);
  }

  /**
   * Records a variable inspection.
   */
  public void recordVariableInspection() {
    variableInspections.increment();
  }

  /**
   * Records a variable reference being registered.
   */
  public void recordVariableReference() {
    variableReferences.increment();
  }

  /**
   * Records a watch being added.
   */
  public void recordWatchAdded() {
    watchesAdded.increment();
  }

  /**
   * Records a watch being removed.
   */
  public void recordWatchRemoved() {
    watchesRemoved.increment();
  }

  /**
   * Records a watch evaluation.
   */
  public void recordWatchEvaluation() {
    watchEvaluations.increment();
  }

  /**
   * Records a debugger event.
   *
   * @param eventType the event type
   */
  public void recordEvent(String eventType) {
    eventCounts.computeIfAbsent(eventType, _type -> new LongAdder()).increment();
  }

  /**
   * Marks the session as ended.
   */
  public void endSession() {
    if (sessionEndTime == null) {
      sessionEndTime = Instant.now();
    }
  }

  // ========== Metrics Retrieval ==========

  /**
   * Gets all metrics as a map.
   *
   * @return metrics map
   */
  public Map<String, Object> getMetrics() {
    Map<String, Object> metrics = new LinkedHashMap<>();

    // Session info
    metrics.put("session_start", sessionStartTime.toString());
    if (sessionEndTime != null) {
      metrics.put("session_end", sessionEndTime.toString());
      metrics.put("session_duration_seconds", Duration.between(sessionStartTime, sessionEndTime).getSeconds());
    } else {
      metrics.put("session_duration_seconds", Duration.between(sessionStartTime, Instant.now()).getSeconds());
    }

    // Breakpoint metrics
    Map<String, Object> breakpointMetrics = new LinkedHashMap<>();
    breakpointMetrics.put("set", breakpointsSet.sum());
    breakpointMetrics.put("hit", breakpointsHit.sum());
    breakpointMetrics.put("removed", breakpointsRemoved.sum());
    breakpointMetrics.put("conditional_evaluated", conditionalBreakpointsEvaluated.sum());
    breakpointMetrics.put("conditional_failed", conditionalBreakpointsFailed.sum());
    breakpointMetrics.put("hit_rate_percent", calculatePercentage(breakpointsHit.sum(), breakpointsSet.sum()));
    metrics.put("breakpoints", breakpointMetrics);

    // Step metrics
    Map<String, Object> stepMetrics = new LinkedHashMap<>();
    stepMetrics.put("step_over", stepsOver.sum());
    stepMetrics.put("step_into", stepsInto.sum());
    stepMetrics.put("step_out", stepsOut.sum());
    stepMetrics.put("total_steps", stepsOver.sum() + stepsInto.sum() + stepsOut.sum());
    metrics.put("stepping", stepMetrics);

    // Expression evaluation metrics
    Map<String, Object> expressionMetrics = new LinkedHashMap<>();
    long totalEvals = expressionEvaluations.sum();
    expressionMetrics.put("total", totalEvals);
    expressionMetrics.put("successes", expressionEvaluationSuccesses.sum());
    expressionMetrics.put("failures", expressionEvaluationFailures.sum());
    expressionMetrics.put("success_rate_percent", calculatePercentage(expressionEvaluationSuccesses.sum(), totalEvals));
    expressionMetrics.put("janino_used", janinoEvaluations.sum());
    expressionMetrics.put("jshell_used", jshellEvaluations.sum());
    expressionMetrics.put("janino_percentage", calculatePercentage(janinoEvaluations.sum(), totalEvals));

    if (totalEvals > 0) {
      expressionMetrics.put("avg_duration_ms", totalEvaluationTimeMs.get() / (double) totalEvals);
      expressionMetrics.put("min_duration_ms",
          minEvaluationTimeMs.get() == Long.MAX_VALUE ? 0 : minEvaluationTimeMs.get());
      expressionMetrics.put("max_duration_ms", maxEvaluationTimeMs.get());
    }
    metrics.put("expression_evaluation", expressionMetrics);

    // Thread metrics
    Map<String, Object> threadMetrics = new LinkedHashMap<>();
    threadMetrics.put("suspends", threadSuspends.sum());
    threadMetrics.put("resumes", threadResumes.sum());
    threadMetrics.put("peak_thread_count", peakThreadCount.get());
    metrics.put("threads", threadMetrics);

    // Variable metrics
    Map<String, Object> variableMetrics = new LinkedHashMap<>();
    variableMetrics.put("inspections", variableInspections.sum());
    variableMetrics.put("references_created", variableReferences.sum());
    metrics.put("variables", variableMetrics);

    // Watch metrics
    Map<String, Object> watchMetrics = new LinkedHashMap<>();
    watchMetrics.put("added", watchesAdded.sum());
    watchMetrics.put("removed", watchesRemoved.sum());
    watchMetrics.put("evaluations", watchEvaluations.sum());
    watchMetrics.put("active", watchesAdded.sum() - watchesRemoved.sum());
    metrics.put("watches", watchMetrics);

    // Event metrics
    Map<String, Long> events = new LinkedHashMap<>();
    eventCounts.forEach((type, count) -> events.put(type, count.sum()));
    metrics.put("events", events);

    return metrics;
  }

  /**
   * Gets a summary of key metrics.
   *
   * @return summary map
   */
  public Map<String, Object> getSummary() {
    long totalEvals = expressionEvaluations.sum();
    long totalSteps = stepsOver.sum() + stepsInto.sum() + stepsOut.sum();

    return Map.of("session_duration_seconds",
        sessionEndTime != null ? Duration.between(sessionStartTime, sessionEndTime).getSeconds()
            : Duration.between(sessionStartTime, Instant.now()).getSeconds(),
        "breakpoints_hit", breakpointsHit.sum(), "total_steps", totalSteps, "expressions_evaluated", totalEvals,
        "expression_success_rate_percent", calculatePercentage(expressionEvaluationSuccesses.sum(), totalEvals),
        "peak_threads", peakThreadCount.get());
  }

  /**
   * Resets all metrics.
   */
  public void reset() {
    breakpointsSet.reset();
    breakpointsHit.reset();
    breakpointsRemoved.reset();
    conditionalBreakpointsEvaluated.reset();
    conditionalBreakpointsFailed.reset();

    stepsOver.reset();
    stepsInto.reset();
    stepsOut.reset();

    expressionEvaluations.reset();
    expressionEvaluationSuccesses.reset();
    expressionEvaluationFailures.reset();
    janinoEvaluations.reset();
    jshellEvaluations.reset();
    totalEvaluationTimeMs.set(0);
    minEvaluationTimeMs.set(Long.MAX_VALUE);
    maxEvaluationTimeMs.set(0);

    threadSuspends.reset();
    threadResumes.reset();
    peakThreadCount.set(0);

    variableInspections.reset();
    variableReferences.reset();

    watchesAdded.reset();
    watchesRemoved.reset();
    watchEvaluations.reset();

    eventCounts.clear();

    logger.info("Debugger metrics reset");
  }

  // ========== Helper Methods ==========

  private void updateMin(AtomicLong current, long newValue) {
    current.updateAndGet(existingValue -> Math.min(existingValue, newValue));
  }

  private void updateMax(AtomicLong current, long newValue) {
    current.updateAndGet(existingValue -> Math.max(existingValue, newValue));
  }

  private double calculatePercentage(long part, long total) {
    if (total == 0) {
      return 0.0;
    }
    return (part * 100.0) / total;
  }
}
