package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Filters threads by their state (RUNNABLE, BLOCKED, WAITING, etc.) using the
 * "state_filter" parameter (for thread_list) or "state_in" parameter (for
 * thread_search).
 */
public class StateFilter implements ThreadFilter {

  private static final String PARAMETER_NAME_LIST = "state_filter";
  private static final String PARAMETER_NAME_SEARCH = "state_in";

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    List<String> stateFilter = getStateList(args);
    return stateFilter != null && !stateFilter.isEmpty();
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    List<String> stateFilter = getStateList(args);
    if (stateFilter == null || stateFilter.isEmpty()) {
      return threads;
    }

    Set<Thread.State> states = stateFilter.stream().map(Thread.State::valueOf).collect(Collectors.toSet());

    return threads.stream().filter(t -> states.contains(t.getThreadState())).collect(Collectors.toList());
  }

  /**
   * Get the state list from either "state_filter" (thread_list) or "state_in"
   * (thread_search).
   */
  private List<String> getStateList(Map<String, Object> args) {
    List<String> states = ParameterUtils.getStringList(args, PARAMETER_NAME_LIST);
    if (states == null || states.isEmpty()) {
      states = ParameterUtils.getStringList(args, PARAMETER_NAME_SEARCH);
    }
    return states;
  }
}
