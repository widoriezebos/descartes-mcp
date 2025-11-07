package com.bitsapplied.descartes.tools.threadanalyzer.strategies;

import java.lang.management.ThreadInfo;
import java.util.List;

/**
 * Strategy interface for thread dump generation with different filtering approaches.
 *
 * <p>Implementations decide how to filter and prioritize threads when generating
 * dumps, allowing adaptive behavior based on thread count and other factors.
 *
 * @since 0.0.1
 */
public interface ThreadDumpStrategy {

    /**
     * Filters and sorts threads according to this strategy.
     *
     * @param threads List of thread-score pairs to process
     * @return Filtered and sorted list of thread-score pairs
     */
    List<ThreadScorePair> filterAndSort(List<ThreadScorePair> threads);

    /**
     * Gets the recommended maximum stack depth for this strategy.
     *
     * @param requestedDepth The depth requested by user (or default)
     * @return The actual stack depth to use
     */
    int getRecommendedStackDepth(int requestedDepth);

    /**
     * Gets the minimum importance score for thread inclusion.
     *
     * @return Minimum score threshold (-100 to include all, 0 to exclude negative scores, etc.)
     */
    int getMinimumScore();

    /**
     * Gets a human-readable name for this strategy (for metadata).
     *
     * @return Strategy name
     */
    String getStrategyName();

    /**
     * Checks if this strategy auto-excludes JVM system threads.
     *
     * @return true if JVM threads should be automatically excluded
     */
    boolean autoExcludesJvmThreads();

    /**
     * Immutable pair of thread info and its importance score.
     */
    record ThreadScorePair(ThreadInfo threadInfo, int score, long cpuTimeMs) {
        /**
         * Creates a thread-score pair.
         *
         * @param threadInfo Thread information
         * @param score Importance score
         * @param cpuTimeMs CPU time in milliseconds (-1 if unavailable)
         */
        public ThreadScorePair {
            if (threadInfo == null) {
                throw new IllegalArgumentException("threadInfo cannot be null");
            }
        }
    }
}
