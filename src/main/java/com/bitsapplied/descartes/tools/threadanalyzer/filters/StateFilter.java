package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Filters threads by their state (RUNNABLE, BLOCKED, WAITING, etc.) using the
 * "state_filter" parameter.
 */
public class StateFilter implements ThreadFilter {

  private static final String PARAMETER_NAME = "state_filter";

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    List<String> stateFilter = ParameterUtils.getStringList(args, PARAMETER_NAME);
    return stateFilter != null && !stateFilter.isEmpty();
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    List<String> stateFilter = ParameterUtils.getStringList(args, PARAMETER_NAME);
    if (stateFilter == null || stateFilter.isEmpty()) {
      return threads;
    }

    Set<Thread.State> states = stateFilter.stream().map(Thread.State::valueOf).collect(Collectors.toSet());

    return threads.stream().filter(t -> states.contains(t.getThreadState())).collect(Collectors.toList());
  }
}
