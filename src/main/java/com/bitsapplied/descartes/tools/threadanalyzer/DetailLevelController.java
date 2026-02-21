package com.bitsapplied.descartes.tools.threadanalyzer;

/**
 * Controls the level of detail included in thread dumps based on available size
 * budget.
 *
 * <p>
 * As the size budget depletes, this controller progressively reduces the amount
 * of detail included for each thread, ensuring that at least summary
 * information is included for high-priority threads.
 *
 * <p>
 * <b>Detail Levels:</b>
 * 
 * <pre>
 * FULL (budget > 80%):
 *   - Complete stack traces (up to max_stack_depth)
 *   - Lock information
 *   - Monitor information
 *   - Synchronizer information
 *
 * REDUCED (budget 50-80%):
 *   - Stack traces only (no locks)
 *   - Max stack depth reduced by 30%
 *
 * MINIMAL (budget 30-50%):
 *   - Top 5 stack frames only
 *   - Thread header + state + CPU time
 *   - No lock information
 *
 * SUMMARY (budget &lt; 30%):
 *   - Thread name, state, CPU time only
 *   - No stack traces
 *   - Count shown in metadata
 * </pre>
 *
 * @since 0.0.1
 */
public class DetailLevelController {

  private final int totalBudget;
  private int usedBudget;
  private int detailReductionCount;

  /**
   * Creates a controller with the specified size budget.
   *
   * @param totalBudgetBytes Total size budget in bytes
   */
  public DetailLevelController(int totalBudgetBytes) {
    if (totalBudgetBytes <= 0) {
      throw new IllegalArgumentException("Total budget must be positive");
    }
    this.totalBudget = totalBudgetBytes;
    this.usedBudget = 0;
    this.detailReductionCount = 0;
  }

  /**
   * Records that the specified number of bytes have been used.
   *
   * @param bytes Number of bytes to add to used budget
   */
  public void recordBytesUsed(int bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("Bytes must be non-negative");
    }
    this.usedBudget += bytes;
  }

  /**
   * Gets the current detail level based on remaining budget.
   *
   * @return Current detail level
   */
  public DetailLevel getCurrentDetailLevel() {
    double percentageUsed = (double) usedBudget / totalBudget;

    if (percentageUsed < 0.20) { // < 20% used = > 80% remaining
      return DetailLevel.FULL;
    } else if (percentageUsed < 0.50) { // < 50% used = > 50% remaining
      return DetailLevel.REDUCED;
    } else if (percentageUsed < 0.70) { // < 70% used = > 30% remaining
      return DetailLevel.MINIMAL;
    } else {
      return DetailLevel.SUMMARY;
    }
  }

  /**
   * Gets the remaining budget in bytes.
   *
   * @return Remaining bytes
   */
  public int getRemainingBudget() {
    return Math.max(0, totalBudget - usedBudget);
  }

  /**
   * Gets the percentage of budget remaining.
   *
   * @return Percentage remaining (0.0 to 1.0)
   */
  public double getPercentageRemaining() {
    return Math.max(0.0, (double) getRemainingBudget() / totalBudget);
  }

  /**
   * Records that detail level was reduced. Used for metadata tracking.
   */
  public void recordDetailReduction() {
    detailReductionCount++;
  }

  /**
   * Gets the number of times detail was reduced.
   *
   * @return Detail reduction count
   */
  public int getDetailReductionCount() {
    return detailReductionCount;
  }

  /**
   * Checks if there's enough budget for the specified size.
   *
   * @param requiredBytes Number of bytes needed
   * @return true if enough budget remains
   */
  public boolean hasRoom(int requiredBytes) {
    return getRemainingBudget() >= requiredBytes;
  }

  /**
   * Gets the total budget.
   *
   * @return Total budget in bytes
   */
  public int getTotalBudget() {
    return totalBudget;
  }

  /**
   * Gets the used budget.
   *
   * @return Used budget in bytes
   */
  public int getUsedBudget() {
    return usedBudget;
  }

  /**
   * Detail level enum with helper methods for formatting.
   */
  public enum DetailLevel {
    /** Full detail: complete stack traces, locks, monitors, synchronizers */
    FULL,

    /** Reduced detail: stack traces only, reduced depth */
    REDUCED,

    /** Minimal detail: top 5 frames, basic thread info */
    MINIMAL,

    /** Summary only: thread name, state, CPU time */
    SUMMARY;

    /**
     * Gets the maximum stack depth for this detail level.
     *
     * @param baseDepth The base stack depth from strategy
     * @return Adjusted stack depth
     */
    public int getStackDepth(int baseDepth) {
      return switch (this) {
      case FULL -> baseDepth;
      case REDUCED -> (int) (baseDepth * 0.7); // 30% reduction
      case MINIMAL -> Math.min(baseDepth, 5);
      case SUMMARY -> 0; // No stack trace
      };
    }

    /**
     * Checks if lock information should be included.
     *
     * @return true if locks should be included
     */
    public boolean includeLocks() {
      return this == FULL;
    }

    /**
     * Checks if monitor information should be included.
     *
     * @return true if monitors should be included
     */
    public boolean includeMonitors() {
      return this == FULL;
    }

    /**
     * Checks if synchronizer information should be included.
     *
     * @return true if synchronizers should be included
     */
    public boolean includeSynchronizers() {
      return this == FULL;
    }

    /**
     * Checks if stack traces should be included.
     *
     * @return true if stack traces should be included
     */
    public boolean includeStackTrace() {
      return this != SUMMARY;
    }
  }
}
