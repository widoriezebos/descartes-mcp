package com.bitsapplied.descartes.tools;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.settings.Setting;
import com.bitsapplied.descartes.settings.Settings;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.CpuTimeFilter;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.DaemonFilter;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.NamePatternFilter;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.StateFilter;
import com.bitsapplied.descartes.tools.threadanalyzer.operations.DeadlockDetectionOperation;
import com.bitsapplied.descartes.tools.threadanalyzer.operations.ThreadDumpOperation;
import com.bitsapplied.descartes.tools.threadanalyzer.operations.ThreadInspectOperation;
import com.bitsapplied.descartes.tools.threadanalyzer.operations.ThreadListOperation;
import com.bitsapplied.descartes.tools.threadanalyzer.operations.ThreadOperation;
import com.bitsapplied.descartes.tools.threadanalyzer.operations.ThreadSearchOperation;
import com.bitsapplied.descartes.util.ToolExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for comprehensive thread analysis including deadlock detection, lock
 * analysis, and thread state monitoring.
 */
public class ThreadAnalyzerTool implements MCPTool {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private static final Logger logger = LoggerFactory.getLogger(ThreadAnalyzerTool.class);
  private final ExecutorService executor;
  private final int maxResponseSizeBytes;
  private final int maxThreadsPerInspect;
  private final int defaultMaxResults;

  // Filter chains for different operations
  private final FilterChain threadListFilters;
  private final FilterChain threadSearchFilters;

  // Operation implementations
  private final Map<String, ThreadOperation> operations = new HashMap<>();

  public ThreadAnalyzerTool() {
    this(new ConcurrentHashMap<>());
  }

  public ThreadAnalyzerTool(Map<String, Object> context) {
    Objects.requireNonNull(context, "context");
    this.executor = ToolExecutors.getSharedExecutor(context);

    // Get settings from context or use defaults
    Object settingsObj = context.get("settings");
    if (settingsObj instanceof Settings settings) {
      this.maxResponseSizeBytes = settings.getInt(Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES);
      this.maxThreadsPerInspect = settings.getInt(Setting.THREAD_ANALYZER_MAX_THREADS_PER_INSPECT);
      this.defaultMaxResults = settings.getInt(Setting.THREAD_ANALYZER_DEFAULT_MAX_RESULTS);
    } else {
      this.maxResponseSizeBytes = Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES.defaultValue(Integer.class);
      this.maxThreadsPerInspect = Setting.THREAD_ANALYZER_MAX_THREADS_PER_INSPECT.defaultValue(Integer.class);
      this.defaultMaxResults = Setting.THREAD_ANALYZER_DEFAULT_MAX_RESULTS.defaultValue(Integer.class);
    }

    // Enable thread contention monitoring if available
    if (threadMXBean.isThreadContentionMonitoringSupported()) {
      try {
        threadMXBean.setThreadContentionMonitoringEnabled(true);
      } catch (SecurityException e) {
        logger.warn("Unable to enable thread contention monitoring due to security manager", e);
      }
    }
    // Enable CPU time monitoring if available
    if (threadMXBean.isThreadCpuTimeSupported()) {
      try {
        threadMXBean.setThreadCpuTimeEnabled(true);
      } catch (SecurityException e) {
        logger.warn("Unable to enable thread CPU time monitoring due to security manager", e);
      }
    }

    // Initialize filter chains
    this.threadListFilters = new FilterChain().addFilter(new StateFilter("state_filter"))
        .addFilter(new NamePatternFilter(this::safeCompilePattern)).addFilter(new CpuTimeFilter(threadMXBean));

    this.threadSearchFilters = new FilterChain()
        .addFilter(new NamePatternFilter("name_contains", false, this::safeCompilePattern))
        .addFilter(new StateFilter("state_in")).addFilter(new DaemonFilter())
        .addFilter(new CpuTimeFilter(threadMXBean));

    // Initialize operations
    registerOperation(new ThreadListOperation(threadMXBean, executor, threadListFilters, threadSearchFilters,
        objectMapper, maxResponseSizeBytes, maxThreadsPerInspect, defaultMaxResults));
    registerOperation(new ThreadInspectOperation(threadMXBean, executor, threadListFilters, threadSearchFilters,
        objectMapper, maxResponseSizeBytes, maxThreadsPerInspect, defaultMaxResults));
    registerOperation(new ThreadSearchOperation(threadMXBean, executor, threadListFilters, threadSearchFilters,
        objectMapper, maxResponseSizeBytes, maxThreadsPerInspect, defaultMaxResults));
    registerOperation(new DeadlockDetectionOperation(threadMXBean, executor, threadListFilters, threadSearchFilters,
        objectMapper, maxResponseSizeBytes, maxThreadsPerInspect, defaultMaxResults));
    registerOperation(new ThreadDumpOperation(threadMXBean, executor, threadListFilters, threadSearchFilters,
        objectMapper, maxResponseSizeBytes, maxThreadsPerInspect, defaultMaxResults));
  }

  private void registerOperation(ThreadOperation operation) {
    operations.put(operation.getOperationName(), operation);
  }

  @Override
  public void close() {
    // Shared executor lifecycle is managed centrally by the MCP server.
  }

  @Override
  public String getToolName() {
    return "thread_analyzer";
  }

  @Override
  public String getToolDescription() {
    return "Advanced thread analysis and deadlock detection tool for JVM applications. "
        + "Monitors thread states (RUNNABLE, BLOCKED, WAITING), detects circular dependencies causing deadlocks, "
        + "analyzes lock contention and synchronization issues, tracks CPU time per thread, and identifies performance bottlenecks. "
        + "Essential for debugging concurrency issues, optimizing thread pool sizes, and ensuring application responsiveness. "
        + "Includes thread contention monitoring and CPU time tracking when supported by JVM.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> properties = new HashMap<>();

    // Operation
    properties.put("operation", Map.of("type", "string", "enum",
        List.of("thread_list", "thread_inspect", "thread_search", "deadlocks", "thread_dump"), "description",
        "Operation to execute. Use 'thread_list' or 'thread_search' before requesting detailed stacks to avoid large responses."));

    // thread_list parameters
    properties.put("state_filter", Map.of("type", "array", "items", Map.of("type", "string"), "description",
        "Filter by thread states: RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, NEW, TERMINATED"));
    properties.put("name_pattern", Map.of("type", "string", "description", "Regex filter applied to thread names"));
    properties.put("min_cpu_time_ms",
        Map.of("type", "integer", "minimum", 0, "description", "Minimum CPU time (ms) for inclusion"));
    properties.put("sort_by", Map.of("type", "string", "enum", List.of("cpu_time", "name", "id", "state"),
        "description", "Sort field", "default", "cpu_time"));
    properties.put("descending", Map.of("type", "boolean", "description", "Sort descending when true", "default", true));
    properties.put("max_results",
        Map.of("type", "integer", "minimum", 1, "maximum", 200,
            "description", "Maximum threads returned for summaries (default 50)", "default", 50));

    // thread_inspect parameters
    properties.put("thread_ids",
        Map.of("type", "array", "items", Map.of("type", "integer"), "maxItems", maxThreadsPerInspect,
            "description", "Thread IDs to inspect (required when thread_inspect selected)"));
    properties.put("thread_names", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems",
        maxThreadsPerInspect, "description", "Thread names to inspect (alternative to thread_ids)"));
    properties.put("include_stack", Map.of("type", "boolean", "description", "Include stack traces", "default", true));
    properties.put("max_stack_depth", Map.of("type", "integer", "minimum", 1, "maximum", 100,
        "description", "Maximum stack depth to capture", "default", 20));
    properties.put("include_locks",
        Map.of("type", "boolean", "description", "Include lock ownership information", "default", true));
    properties.put("include_monitors",
        Map.of("type", "boolean", "description", "Include monitor details", "default", true));
    properties.put("include_synchronizers",
        Map.of("type", "boolean", "description", "Include ownable synchronizer details", "default", false));
    properties.put("filter_stack_pattern",
        Map.of("type", "string", "description", "Regex applied to stack frames to keep only matching entries"));

    // thread_search parameters
    properties.put("name_contains", Map.of("type", "string", "description", "Substring match for thread names"));
    properties.put("state_in",
        Map.of("type", "array", "items", Map.of("type", "string"), "description", "Thread states to include"));
    properties.put("daemon",
        Map.of("type", "boolean", "description", "Filter by daemon threads (true/false)"));
    properties.put("include_details",
        Map.of("type", "boolean", "description", "Return detailed data including stacks", "default", false));

    List<Map<String, Object>> constraints = new ArrayList<>();
    constraints.add(Map.of("if",
        Map.of("properties", Map.of("operation", Map.of("const", "thread_inspect")), "required", List.of("operation")),
        "then",
        Map.of("anyOf", List.of(Map.of("required", List.of("thread_ids")), Map.of("required", List.of("thread_names"))))));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("operation"));
    schema.put("allOf", constraints);
    schema.put("description",
        "Advanced JVM thread analysis tool. Start with 'thread_list' or 'thread_search' and narrow results before requesting full stacks to keep responses manageable.");
    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    String operation = (String) arguments.get("operation");

    if (operation == null) {
      return CompletableFuture.completedFuture(ToolResponse.missingParameter("operation"));
    }

    ThreadOperation threadOperation = operations.get(operation);
    if (threadOperation == null) {
      return CompletableFuture.completedFuture(
          ToolResponse.unsupportedOperation(operation,
              "thread_list, thread_inspect, thread_search, deadlocks, thread_dump"));
    }

    return threadOperation.executeAsync(arguments).thenApply(ToolResponse::successJson).exceptionally(e -> {
      Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause()
          : e;
      String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "Unknown error";
      return ToolResponse.executionFailed("Thread analysis failed: " + message);
    });
  }

  /**
   * Safely compile a regex pattern with ReDoS protection (wrapper for filter
   * initialization).
   */
  private Pattern safeCompilePattern(String patternStr, String paramName) {
    // Delegate to the static utility method in AbstractThreadOperation
    return safeCompilePatternStatic(patternStr, paramName);
  }

  /**
   * Static utility for safe pattern compilation.
   */
  private static Pattern safeCompilePatternStatic(String patternStr, String paramName) {
    if (patternStr == null) {
      throw new IllegalArgumentException(paramName + " cannot be null");
    }

    final int MAX_PATTERN_LENGTH = 500;
    if (patternStr.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException(
          String.format("%s pattern too long: %d characters (max %d). Complex patterns may cause performance issues.",
              paramName, patternStr.length(), MAX_PATTERN_LENGTH));
    }

    if (patternStr.matches(".*\\([^)]*[+*]\\)[+*].*")) {
      throw new IllegalArgumentException(String.format("%s contains nested quantifiers (e.g., (a+)+) which can cause "
          + "catastrophic backtracking. Simplify the pattern to avoid performance issues.", paramName));
    }

    if (patternStr.matches(".*[+*]{2,}.*")) {
      throw new IllegalArgumentException(
          String.format("%s contains consecutive quantifiers (e.g., a**) which is invalid.", paramName));
    }

    try {
      return Pattern.compile(patternStr);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          String.format("%s is not a valid regex pattern: %s", paramName, e.getMessage()), e);
    }
  }
}
