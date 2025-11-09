package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chains multiple ThreadFilters together, applying them in sequence. Only
 * applies filters that indicate they should be applied via shouldApply().
 */
public class FilterChain implements ThreadFilter {

  private final List<ThreadFilter> filters = new ArrayList<>();

  /**
   * Add a filter to the chain.
   *
   * @param filter the filter to add
   * @return this FilterChain for fluent chaining
   */
  public FilterChain addFilter(ThreadFilter filter) {
    filters.add(filter);
    return this;
  }

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    return filters.stream().anyMatch(f -> f.shouldApply(args));
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    List<ThreadInfo> result = threads;
    for (ThreadFilter filter : filters) {
      if (filter.shouldApply(args)) {
        result = filter.apply(result, args);
      }
    }
    return result;
  }
}
