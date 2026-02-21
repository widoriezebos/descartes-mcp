package com.bitsapplied.descartes.tools.threadanalyzer.strategies;

/**
 * Selects the appropriate ThreadDumpStrategy based on thread count.
 *
 * <p>
 * Strategy selection thresholds:
 * <ul>
 * <li>&lt; 20 threads: FullDetailStrategy (include all, full detail)</li>
 * <li>20-49 threads: SmartFilteringStrategy (exclude JVM, reduced detail)</li>
 * <li>50-100 threads: PrioritizedStrategy (strong filtering, minimal
 * detail)</li>
 * <li>&gt; 100 threads: AggressiveStrategy (aggressive filtering, very minimal
 * detail)</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class StrategySelector {

  // Strategy selection thresholds
  private static final int SMALL_THREAD_COUNT = 20;
  private static final int MEDIUM_THREAD_COUNT = 50;
  private static final int LARGE_THREAD_COUNT = 100;

  // Singleton strategy instances (stateless, so can be reused)
  private static final ThreadDumpStrategy FULL_DETAIL = new FullDetailStrategy();
  private static final ThreadDumpStrategy SMART_FILTERING = new SmartFilteringStrategy();
  private static final ThreadDumpStrategy PRIORITIZED = new PrioritizedStrategy();
  private static final ThreadDumpStrategy AGGRESSIVE = new AggressiveStrategy();

  /**
   * Selects the appropriate strategy based on thread count after user filters are
   * applied.
   *
   * @param threadCountAfterFilters Number of threads remaining after user filters
   * @return The most appropriate strategy for this thread count
   */
  public static ThreadDumpStrategy selectStrategy(int threadCountAfterFilters) {
    if (threadCountAfterFilters < SMALL_THREAD_COUNT) {
      return FULL_DETAIL;
    } else if (threadCountAfterFilters < MEDIUM_THREAD_COUNT) {
      return SMART_FILTERING;
    } else if (threadCountAfterFilters <= LARGE_THREAD_COUNT) {
      return PRIORITIZED;
    } else {
      return AGGRESSIVE;
    }
  }

  /**
   * Gets a human-readable explanation of why this strategy was selected.
   *
   * @param threadCountAfterFilters Number of threads
   * @return Explanation string
   */
  public static String getSelectionReason(int threadCountAfterFilters) {
    if (threadCountAfterFilters < SMALL_THREAD_COUNT) {
      return "Small thread count (" + threadCountAfterFilters + "), using full detail";
    } else if (threadCountAfterFilters < MEDIUM_THREAD_COUNT) {
      return "Medium thread count (" + threadCountAfterFilters + "), auto-excluding JVM threads";
    } else if (threadCountAfterFilters <= LARGE_THREAD_COUNT) {
      return "Large thread count (" + threadCountAfterFilters + "), using strong prioritization";
    } else {
      return "Very large thread count (" + threadCountAfterFilters + "), using aggressive filtering";
    }
  }

  // Private constructor to prevent instantiation
  private StrategySelector() {
  }
}
