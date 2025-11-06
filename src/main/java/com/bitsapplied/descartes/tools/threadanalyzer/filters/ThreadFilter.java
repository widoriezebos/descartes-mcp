package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;

/**
 * Filter interface for filtering threads based on various criteria.
 * Implements the Chain of Responsibility pattern, allowing filters to be chained together.
 */
public interface ThreadFilter {

  /**
   * Apply filter to the list of threads.
   *
   * @param threads the threads to filter
   * @param args the arguments containing filter parameters
   * @return filtered list of threads
   */
  List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args);

  /**
   * Check if this filter should be applied based on the arguments.
   *
   * @param args the arguments containing filter parameters
   * @return true if this filter should be applied, false otherwise
   */
  default boolean shouldApply(Map<String, Object> args) {
    return true;
  }
}
