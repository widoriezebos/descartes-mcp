package com.bitsapplied.descartes.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProcessInspector {

  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final String UNNAMED_MODULE_IDENTIFIER = "app";

  /**
   * Takes a snapshot of all thread stacks and creates a text report with filtered
   * stack traces.
   * 
   * 
   * @param whitelistFilters List of filter expressions that can use '*' as
   *                         wildcard. Any thread with a stack element matching
   *                         any filter will be included.
   * @param includeSelf      Whether to include the current thread in the report
   * @return A text report containing the filtered thread stack traces
   */
  public String captureThreadStacks(List<String> whitelistFilters, boolean includeSelf) {
    return captureThreadStacks(whitelistFilters, includeSelf, null, false);
  }

  /**
   * Takes a snapshot of all thread stacks and creates a text report with filtered
   * stack traces.
   * 
   * @param whitelistFilters List of filter expressions that can use '*' as
   *                         wildcard. Any thread with a stack element matching
   *                         any filter will be included.
   * @param includeSelf      Whether to include the current thread in the report
   * @param moduleFilter     Module name to filter on (case insensitive), null for
   *                         no module filtering, or "&lt;unnamed&gt;" to filter
   *                         for threads with unnamed module classes
   * @return A text report containing the filtered thread stack traces
   */
  public String captureThreadStacks(List<String> whitelistFilters, boolean includeSelf, String moduleFilter) {
    return captureThreadStacks(whitelistFilters, includeSelf, moduleFilter, false);
  }

  /**
   * Takes a snapshot of all thread stacks and creates a text report with filtered
   * stack traces.
   * 
   * @param whitelistFilters List of filter expressions that can use '*' as
   *                         wildcard. Any thread with a stack element matching
   *                         any filter will be included.
   * @param includeSelf      Whether to include the current thread in the report
   * @param moduleFilter     Module name to filter on (case insensitive), null for
   *                         no module filtering, or "&lt;unnamed&gt;" to filter
   *                         for threads with unnamed module classes
   * @param trimToModule     When true and moduleFilter is set, only show stack
   *                         elements from the specified module
   * @return A text report containing the filtered thread stack traces
   */
  public String captureThreadStacks(List<String> whitelistFilters, boolean includeSelf, String moduleFilter,
      boolean trimToModule) {
    if (whitelistFilters == null || whitelistFilters.isEmpty()) {
      return captureAllThreadStacks(includeSelf, moduleFilter, trimToModule);
    }

    // Convert wildcard patterns to regex patterns
    List<Pattern> patterns = whitelistFilters.stream().map(this::wildcardToRegex).map(Pattern::compile)
        .collect(Collectors.toList());

    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    // Header
    pw.println("Thread Stack Trace Report");
    pw.println("Generated: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    pw.println("Filters: " + String.join(", ", whitelistFilters));
    pw.println("=".repeat(80));
    pw.println();

    Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
    Thread currentThread = Thread.currentThread();

    // Create snapshots and filter
    List<ThreadSnapshot> filteredSnapshots = allStackTraces.entrySet().stream()
        .map(entry -> new ThreadSnapshot(entry.getKey(), entry.getValue()))
        .filter(snapshot -> includeSelf || !snapshot.thread.equals(currentThread))
        .filter(snapshot -> matchesFilters(snapshot.stackTrace, patterns))
        .filter(snapshot -> matchesModuleFilter(snapshot.stackTrace, moduleFilter))
        .sorted(new ThreadComparator(patterns)).collect(Collectors.toList());

    int matchedThreads = filteredSnapshots.size();

    for (ThreadSnapshot snapshot : filteredSnapshots) {
      StackTraceElement[] stackTrace = snapshot.stackTrace;
      if (trimToModule && moduleFilter != null) {
        stackTrace = trimStackTraceToModule(stackTrace, moduleFilter);
      }
      printThreadInfo(pw, snapshot, stackTrace);
    }

    // Footer
    pw.println("=".repeat(80));
    pw.printf("Total threads matched: %d out of %d%n", matchedThreads, allStackTraces.size());

    return sw.toString();
  }

  /**
   * Captures all thread stacks without filtering.
   */
  public String captureAllThreadStacks(boolean includeSelf) {
    return captureAllThreadStacks(includeSelf, null, false);
  }

  /**
   * Captures all thread stacks without whitelist filtering.
   * 
   * @param includeSelf  Whether to include the current thread in the report
   * @param moduleFilter Module name to filter on (case insensitive), null for no
   *                     module filtering, or "&lt;unnamed&gt;" to filter for
   *                     threads with unnamed module classes
   */
  public String captureAllThreadStacks(boolean includeSelf, String moduleFilter) {
    return captureAllThreadStacks(includeSelf, moduleFilter, false);
  }

  /**
   * Captures all thread stacks without whitelist filtering.
   * 
   * @param includeSelf  Whether to include the current thread in the report
   * @param moduleFilter Module name to filter on (case insensitive), null for no
   *                     module filtering, or "&lt;unnamed&gt;" to filter for
   *                     threads with unnamed module classes
   * @param trimToModule When true and moduleFilter is set, only show stack
   *                     elements from the specified module
   */
  public String captureAllThreadStacks(boolean includeSelf, String moduleFilter, boolean trimToModule) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    String filterLabel = moduleFilter != null
        ? (UNNAMED_MODULE_IDENTIFIER.equalsIgnoreCase(moduleFilter) ? "app" : "Module: " + moduleFilter)
        : "No Filters";
    pw.println("Thread Stack Trace Report" + (moduleFilter != null ? " (" + filterLabel + ")" : " (No Filters)"));
    pw.println("Generated: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    if (moduleFilter != null) {
      pw.println("Module Filter: " + moduleFilter + " (case insensitive)");
    }
    pw.println("=".repeat(80));
    pw.println();

    Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
    Thread currentThread = Thread.currentThread();

    // Create snapshots and sort
    List<ThreadSnapshot> sortedSnapshots = allStackTraces.entrySet().stream()
        .map(entry -> new ThreadSnapshot(entry.getKey(), entry.getValue()))
        .filter(snapshot -> includeSelf || !snapshot.thread.equals(currentThread))
        .filter(snapshot -> matchesModuleFilter(snapshot.stackTrace, moduleFilter)).sorted(new ThreadComparator(null))
        .collect(Collectors.toList());

    for (ThreadSnapshot snapshot : sortedSnapshots) {
      StackTraceElement[] stackTrace = snapshot.stackTrace;
      if (trimToModule && moduleFilter != null) {
        stackTrace = trimStackTraceToModule(stackTrace, moduleFilter);
      }
      printThreadInfo(pw, snapshot, stackTrace);
    }

    pw.println("=".repeat(80));
    pw.printf("Total threads: %d%n", sortedSnapshots.size());

    return sw.toString();
  }

  /**
   * Checks if any stack trace element matches any of the filter patterns.
   */
  private boolean matchesFilters(StackTraceElement[] stackTrace, List<Pattern> patterns) {
    for (StackTraceElement element : stackTrace) {
      String elementString = element.toString();
      for (Pattern pattern : patterns) {
        if (pattern.matcher(elementString).find()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Trims a stack trace to only include elements from the specified module.
   * 
   * @param stackTrace   The original stack trace
   * @param moduleFilter The module name to filter on (case insensitive), or "app"
   *                     for unnamed module
   * @return A new array containing only stack elements from the specified module
   */
  private StackTraceElement[] trimStackTraceToModule(StackTraceElement[] stackTrace, String moduleFilter) {
    if (stackTrace == null || stackTrace.length == 0) {
      return stackTrace;
    }

    boolean filteringForUnnamed = UNNAMED_MODULE_IDENTIFIER.equalsIgnoreCase(moduleFilter);
    List<StackTraceElement> trimmedStack = new ArrayList<>();

    for (StackTraceElement element : stackTrace) {
      String moduleName = element.getModuleName();

      if (filteringForUnnamed) {
        // Include elements from unnamed module (null module name)
        if (moduleName == null) {
          trimmedStack.add(element);
        }
      } else {
        // Include elements from named module
        if (moduleName != null && moduleName.equalsIgnoreCase(moduleFilter)) {
          trimmedStack.add(element);
        }
      }
    }

    return trimmedStack.toArray(new StackTraceElement[0]);
  }

  /**
   * Determines if a thread matches the specified module filter by checking if any
   * stack trace element has a matching module name.
   * 
   * @param stackTrace   The thread's stack trace
   * @param moduleFilter The module name to filter on (case insensitive), null for
   *                     no filtering, or "&lt;unnamed&gt;" to filter for threads
   *                     with unnamed module classes
   * @return true if moduleFilter is null or the thread has at least one stack
   *         frame from the specified module
   */
  private boolean matchesModuleFilter(StackTraceElement[] stackTrace, String moduleFilter) {
    if (moduleFilter == null) {
      return true;
    }

    boolean filteringForUnnamed = UNNAMED_MODULE_IDENTIFIER.equalsIgnoreCase(moduleFilter);

    for (StackTraceElement element : stackTrace) {
      String moduleName = element.getModuleName();
      if (filteringForUnnamed) {
        // Filter for unnamed module (null module name)
        if (moduleName == null) {
          return true;
        }
      } else {
        // Filter for named module
        if (moduleName != null && moduleName.equalsIgnoreCase(moduleFilter)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Converts a wildcard pattern to a regex pattern. Escapes special regex
   * characters and replaces '*' with '.*'
   */
  private String wildcardToRegex(String wildcard) {
    // Escape special regex characters except '*'
    String escaped = wildcard.replace(".", "\\.").replace("?", "\\?").replace("+", "\\+").replace("[", "\\[")
        .replace("]", "\\]").replace("(", "\\(").replace(")", "\\)").replace("{", "\\{").replace("}", "\\}")
        .replace("^", "\\^").replace("$", "\\$").replace("|", "\\|");

    // Replace '*' with '.*' for wildcard matching
    return escaped.replace("*", ".*");
  }

  /**
   * Prints thread information and stack trace.
   */
  private void printThreadInfo(PrintWriter pw, ThreadSnapshot snapshot, StackTraceElement[] stackTrace) {
    pw.printf("Thread: \"%s\" #%d %s%n", snapshot.name, snapshot.threadId, snapshot.isDaemon ? "daemon" : "");
    pw.printf("   State: %s%n", snapshot.state);
    pw.printf("   Priority: %d%n", snapshot.priority);

    if (stackTrace.length == 0) {
      pw.println("   (no stack trace available)");
    } else {
      for (StackTraceElement element : stackTrace) {
        pw.printf("   at %s%n", element);
      }
    }

    pw.println();
  }

  /**
   * Immutable snapshot of thread state to ensure consistent comparisons
   */
  private static class ThreadSnapshot {
    final Thread thread;
    final Thread.State state;
    final String name;
    final int priority;
    final long threadId;
    final boolean isDaemon;
    final StackTraceElement[] stackTrace;

    ThreadSnapshot(Thread thread, StackTraceElement[] stackTrace) {
      this.thread = thread;
      this.state = thread.getState();
      this.name = thread.getName();
      this.priority = thread.getPriority();
      this.threadId = thread.threadId();
      this.isDaemon = thread.isDaemon();
      this.stackTrace = stackTrace;
    }
  }

  /**
   * Comparator for sorting threads by state, matching stack depth, thread name,
   * and priority. State order: RUNNABLE, BLOCKED, TIMED_WAITING/WAITING (same
   * priority), NEW, TERMINATED, others
   */
  private static class ThreadComparator implements Comparator<ThreadSnapshot> {
    private static final Map<Thread.State, Integer> STATE_ORDER = Map.of(Thread.State.RUNNABLE, 1, Thread.State.BLOCKED,
        2, Thread.State.TIMED_WAITING, 3, Thread.State.WAITING, 3, // Same priority as TIMED_WAITING
        Thread.State.NEW, 4, Thread.State.TERMINATED, 5);

    private final List<Pattern> patterns;

    public ThreadComparator(List<Pattern> patterns) {
      this.patterns = patterns;
    }

    @Override
    public int compare(ThreadSnapshot s1, ThreadSnapshot s2) {
      // First compare by state
      int state1Order = STATE_ORDER.getOrDefault(s1.state, 6);
      int state2Order = STATE_ORDER.getOrDefault(s2.state, 6);

      int stateComparison = Integer.compare(state1Order, state2Order);
      if (stateComparison != 0) {
        return stateComparison;
      }

      // If states are equal and we have patterns, compare by matching stack depth
      if (patterns != null && !patterns.isEmpty()) {
        int matchCount1 = countMatchingStackElements(s1.stackTrace, patterns);
        int matchCount2 = countMatchingStackElements(s2.stackTrace, patterns);

        // Higher match count first (threads deeper in application code)
        int matchComparison = Integer.compare(matchCount2, matchCount1);
        if (matchComparison != 0) {
          return matchComparison;
        }
      }

      // Then compare by thread name (with null safety)
      String name1 = s1.name != null ? s1.name : "";
      String name2 = s2.name != null ? s2.name : "";
      int nameComparison = name1.compareTo(name2);
      if (nameComparison != 0) {
        return nameComparison;
      }

      // Compare by priority (higher priority first)
      int priorityComparison = Integer.compare(s2.priority, s1.priority);
      if (priorityComparison != 0) {
        return priorityComparison;
      }

      // Finally, use thread ID as stable tie-breaker
      return Long.compare(s1.threadId, s2.threadId);
    }

    private int countMatchingStackElements(StackTraceElement[] stackTrace, List<Pattern> patterns) {
      int count = 0;
      for (StackTraceElement element : stackTrace) {
        String elementString = element.toString();
        for (Pattern pattern : patterns) {
          if (pattern.matcher(elementString).find()) {
            count++;
            break; // Only count each element once even if multiple patterns match
          }
        }
      }
      return count;
    }
  }
}