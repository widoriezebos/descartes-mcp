package com.bitsapplied.descartes.tools.threadanalyzer.strategies;

import java.util.Comparator;
import java.util.List;

/**
 * Strategy for medium thread counts (20-50 threads) that auto-excludes JVM
 * system threads.
 *
 * <p>
 * This strategy:
 * <ul>
 * <li>Auto-excludes negative-scored threads (JVM system threads)</li>
 * <li>Sorts by importance score (descending)</li>
 * <li>Reduces stack depth to 30 frames</li>
 * <li>Keeps all non-negative scored threads</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class SmartFilteringStrategy implements ThreadDumpStrategy {

  private static final int RECOMMENDED_STACK_DEPTH = 30;
  private static final int MINIMUM_SCORE = 0; // Exclude negative scores

  @Override
  public List<ThreadScorePair> filterAndSort(List<ThreadScorePair> threads) {
    // Exclude negative-scored threads, sort by score
    return threads.stream().filter(pair -> pair.score() >= MINIMUM_SCORE)
        .sorted(Comparator.comparingInt(ThreadScorePair::score).reversed()
            .thenComparing(pair -> pair.threadInfo().getThreadName())
            .thenComparingLong(pair -> pair.threadInfo().getThreadId()))
        .toList();
  }

  @Override
  public int getRecommendedStackDepth(int requestedDepth) {
    // Reduce stack depth slightly
    return Math.min(requestedDepth, RECOMMENDED_STACK_DEPTH);
  }

  @Override
  public int getMinimumScore() {
    return MINIMUM_SCORE;
  }

  @Override
  public String getStrategyName() {
    return "SmartFilteringStrategy";
  }

  @Override
  public boolean autoExcludesJvmThreads() {
    return true;
  }
}
