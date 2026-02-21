package com.bitsapplied.descartes.tools.threadanalyzer.strategies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Strategy for small thread counts (&lt;20 threads) that includes all threads with
 * full detail.
 *
 * <p>
 * This strategy:
 * <ul>
 * <li>Includes ALL threads regardless of score</li>
 * <li>Sorts by importance score (descending) for prioritized viewing</li>
 * <li>Uses full stack depth (up to 50 frames)</li>
 * <li>Does not auto-exclude any threads</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class FullDetailStrategy implements ThreadDumpStrategy {

  private static final int DEFAULT_MAX_STACK_DEPTH = 50;

  @Override
  public List<ThreadScorePair> filterAndSort(List<ThreadScorePair> threads) {
    // Include all threads, sort by score (highest first)
    List<ThreadScorePair> result = new ArrayList<>(threads);
    result.sort(Comparator.comparingInt(ThreadScorePair::score).reversed()
        .thenComparing(pair -> pair.threadInfo().getThreadName())
        .thenComparingLong(pair -> pair.threadInfo().getThreadId()));
    return result;
  }

  @Override
  public int getRecommendedStackDepth(int requestedDepth) {
    // Use full depth for small thread counts
    return Math.min(requestedDepth, DEFAULT_MAX_STACK_DEPTH);
  }

  @Override
  public int getMinimumScore() {
    // Include all threads, even negative scores
    return Integer.MIN_VALUE;
  }

  @Override
  public String getStrategyName() {
    return "FullDetailStrategy";
  }

  @Override
  public boolean autoExcludesJvmThreads() {
    return false;
  }
}
