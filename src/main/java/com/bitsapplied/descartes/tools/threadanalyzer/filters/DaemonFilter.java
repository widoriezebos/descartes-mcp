package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filters threads by daemon status. Only applies if the "daemon" parameter is
 * specified.
 */
public class DaemonFilter implements ThreadFilter {

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    return args.containsKey("daemon");
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    Boolean daemon = (Boolean) args.get("daemon");
    if (daemon == null) {
      return threads;
    }

    return threads.stream().filter(t -> t.isDaemon() == daemon).collect(Collectors.toList());
  }
}
