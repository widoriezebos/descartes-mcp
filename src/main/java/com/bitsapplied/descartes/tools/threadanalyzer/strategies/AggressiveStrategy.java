package com.bitsapplied.descartes.tools.threadanalyzer.strategies;

import java.util.Comparator;
import java.util.List;

/**
 * Strategy for very large thread counts (>100 threads) with aggressive filtering.
 *
 * <p>This strategy:
 * <ul>
 *   <li>Includes only score > 25 threads (high-value only)</li>
 *   <li>Sorts by importance score (descending)</li>
 *   <li>Reduces stack depth to 15 frames</li>
 *   <li>Aggressively filters to show only problematic/interesting threads</li>
 * </ul>
 *
 * <p>With >100 threads, users should typically use thread_search instead,
 * but this strategy provides a reasonable fallback.
 *
 * @since 0.0.1
 */
public class AggressiveStrategy implements ThreadDumpStrategy {

    private static final int RECOMMENDED_STACK_DEPTH = 15;
    private static final int MINIMUM_SCORE = 25;  // High-value threads only

    @Override
    public List<ThreadScorePair> filterAndSort(List<ThreadScorePair> threads) {
        // Include only high-scoring threads, sort by score
        return threads.stream()
                .filter(pair -> pair.score() >= MINIMUM_SCORE)
                .sorted(Comparator.comparingInt(ThreadScorePair::score).reversed()
                        .thenComparing(pair -> pair.threadInfo().getThreadName())
                        .thenComparingLong(pair -> pair.threadInfo().getThreadId()))
                .toList();
    }

    @Override
    public int getRecommendedStackDepth(int requestedDepth) {
        // Minimal stack depth
        return Math.min(requestedDepth, RECOMMENDED_STACK_DEPTH);
    }

    @Override
    public int getMinimumScore() {
        return MINIMUM_SCORE;
    }

    @Override
    public String getStrategyName() {
        return "AggressiveStrategy";
    }

    @Override
    public boolean autoExcludesJvmThreads() {
        return true;
    }
}
