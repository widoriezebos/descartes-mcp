package com.bitsapplied.descartes.tools.threadanalyzer.strategies;

import java.util.Comparator;
import java.util.List;

/**
 * Strategy for large thread counts (50-100 threads) with strong importance prioritization.
 *
 * <p>This strategy:
 * <ul>
 *   <li>Includes only score > 0 threads (positive scoring)</li>
 *   <li>Sorts by importance score (descending)</li>
 *   <li>Reduces stack depth to 20 frames</li>
 *   <li>Auto-excludes JVM system threads and zero-scored threads</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class PrioritizedStrategy implements ThreadDumpStrategy {

    private static final int RECOMMENDED_STACK_DEPTH = 20;
    private static final int MINIMUM_SCORE = 1;  // Only positive scores

    @Override
    public List<ThreadScorePair> filterAndSort(List<ThreadScorePair> threads) {
        // Include only positive-scored threads, sort by score
        return threads.stream()
                .filter(pair -> pair.score() >= MINIMUM_SCORE)
                .sorted(Comparator.comparingInt(ThreadScorePair::score).reversed()
                        .thenComparing(pair -> pair.threadInfo().getThreadName())
                        .thenComparingLong(pair -> pair.threadInfo().getThreadId()))
                .toList();
    }

    @Override
    public int getRecommendedStackDepth(int requestedDepth) {
        // Significantly reduce stack depth
        return Math.min(requestedDepth, RECOMMENDED_STACK_DEPTH);
    }

    @Override
    public int getMinimumScore() {
        return MINIMUM_SCORE;
    }

    @Override
    public String getStrategyName() {
        return "PrioritizedStrategy";
    }

    @Override
    public boolean autoExcludesJvmThreads() {
        return true;
    }
}
