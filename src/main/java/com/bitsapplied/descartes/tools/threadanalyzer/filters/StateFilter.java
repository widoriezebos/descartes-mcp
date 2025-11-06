package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Filters threads by their state (RUNNABLE, BLOCKED, WAITING, etc.).
 * Supports both "state_filter" and "state_in" parameter names for backward compatibility.
 */
public class StateFilter implements ThreadFilter {

  private final String parameterName;

  /**
   * Create a StateFilter with the specified parameter name.
   *
   * @param parameterName the parameter name to look for ("state_filter" or "state_in")
   */
  public StateFilter(String parameterName) {
    this.parameterName = parameterName;
  }

  /**
   * Create a StateFilter using "state_filter" as the parameter name.
   */
  public StateFilter() {
    this("state_filter");
  }

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    List<String> stateFilter = ParameterUtils.getStringList(args, parameterName);
    return stateFilter != null && !stateFilter.isEmpty();
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    List<String> stateFilter = ParameterUtils.getStringList(args, parameterName);
    if (stateFilter == null || stateFilter.isEmpty()) {
      return threads;
    }

    Set<Thread.State> states = stateFilter.stream()
        .map(Thread.State::valueOf)
        .collect(Collectors.toSet());

    return threads.stream()
        .filter(t -> states.contains(t.getThreadState()))
        .collect(Collectors.toList());
  }
}
