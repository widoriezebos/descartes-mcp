package com.bitsapplied.descartes.settings;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enumeration of all configuration settings for Descartes MCP.
 * <p>
 * Each setting has a key (used in properties files and system properties) and a
 * default value. This provides type-safe, centralized configuration management
 * with compile-time checking.
 * <p>
 * Pattern inspired by Morpheus Settings architecture.
 *
 * @see Settings
 */
public enum Setting {
  // ==================== MCP Server Settings ====================

  /** Core pool size for MCP server thread pool executor */
  MCP_EXECUTOR_CORE_POOL_SIZE("mcp.server.executor.corePoolSize", 10),

  /** Maximum pool size for MCP server thread pool executor */
  MCP_EXECUTOR_MAX_POOL_SIZE("mcp.server.executor.maxPoolSize", 100),

  /** Queue capacity for MCP server thread pool executor */
  MCP_EXECUTOR_QUEUE_CAPACITY("mcp.server.executor.queueCapacity", 500),

  /** Keep-alive time in seconds for idle threads in MCP server executor */
  MCP_EXECUTOR_KEEP_ALIVE_SECONDS("mcp.server.executor.keepAliveSeconds", 60),

  /** Maximum message size in bytes for MCP protocol (10 MB default) */
  MCP_MESSAGE_MAX_SIZE_BYTES("mcp.message.maxSizeBytes", 10_485_760),

  /** Tool execution timeout in milliseconds (60 seconds default) */
  MCP_TOOL_TIMEOUT_MS("mcp.tools.timeout.ms", 60000),

  // ==================== JShell Settings ====================

  /** JShell code execution timeout in seconds */
  JSHELL_EXECUTION_TIMEOUT_SECONDS("jshell.execution.timeout.seconds", 30),

  /** Maximum number of concurrent JShell sessions */
  JSHELL_MAX_SESSIONS("jshell.max.sessions", 15),

  /** JShell session idle timeout in minutes */
  JSHELL_SESSION_TIMEOUT_MINUTES("jshell.session.timeout.minutes", 30),

  /** Maximum number of collection elements to display in JShell inspector */
  JSHELL_INSPECTOR_COLLECTION_LIMIT("jshell.inspector.collectionLimit", 10),

  /** Default depth for object inspection in JShell */
  JSHELL_INSPECTOR_DEFAULT_DEPTH("jshell.inspector.defaultDepth", 3),

  /** Maximum string length to display in JShell inspector */
  JSHELL_INSPECTOR_MAX_STRING_LENGTH("jshell.inspector.maxStringLength", 100),

  // ==================== Thread Analyzer Settings ====================

  /** Maximum response size in bytes for thread analyzer operations */
  THREAD_ANALYZER_MAX_RESPONSE_BYTES("thread.analyzer.maxResponseBytes", 200_000),

  /** Maximum number of threads to inspect per operation */
  THREAD_ANALYZER_MAX_THREADS_PER_INSPECT("thread.analyzer.maxThreadsPerInspect", 50),

  /** Default maximum number of results for thread list operations */
  THREAD_ANALYZER_DEFAULT_MAX_RESULTS("thread.analyzer.defaultMaxResults", 100),

  /** Enable smart truncation in thread dumps (importance-based prioritization) */
  THREAD_DUMP_SMART_TRUNCATION_ENABLED("thread.dump.smartTruncation.enabled", true),

  /** Minimum importance score threshold for thread inclusion in dumps */
  THREAD_DUMP_IMPORTANCE_THRESHOLD("thread.dump.importanceThreshold", 0),

  /** Thread count threshold for auto-excluding JVM system threads */
  THREAD_DUMP_AUTO_EXCLUDE_JVM_THRESHOLD("thread.dump.autoExcludeJvmThreshold", 50),

  /** Safety margin (in bytes) reserved for truncation footer and JSON envelope */
  THREAD_DUMP_SIZE_SAFETY_MARGIN("thread.dump.sizeSafetyMargin", 5000),

  /** Soft limit on thread count before warning about using thread_search instead */
  THREAD_DUMP_MAX_THREADS_SOFT_LIMIT("thread.dump.maxThreadsSoftLimit", 100),

  // ==================== Debugger Settings ====================

  /** Debugger executor shutdown timeout in seconds */
  DEBUGGER_SHUTDOWN_TIMEOUT_SECONDS("debugger.shutdown.timeout.seconds", 10),

  /** Maximum depth for expanding object variables in debugger */
  DEBUGGER_VARIABLES_MAX_EXPANSION_DEPTH("debugger.variables.maxExpansionDepth", 10),

  /** Maximum string length to display for variable values in debugger */
  DEBUGGER_VARIABLES_MAX_STRING_LENGTH("debugger.variables.maxStringLength", 200),

  /** Maximum consecutive JDWP connection failures before circuit breaker opens */
  DEBUGGER_JDWP_MAX_CONSECUTIVE_FAILURES("debugger.jdwp.maxConsecutiveFailures", 3),

  /** Circuit breaker open duration in minutes for JDWP connections */
  DEBUGGER_JDWP_CIRCUIT_BREAKER_DURATION_MINUTES("debugger.jdwp.circuitBreakerDurationMinutes", 5),

  // ==================== Profiler Settings ====================

  /** Minimum profiling duration in seconds */
  PROFILER_DURATION_MIN_SECONDS("profiler.duration.min.seconds", 10),

  /** Maximum profiling duration in seconds */
  PROFILER_DURATION_MAX_SECONDS("profiler.duration.max.seconds", 300),

  /** CPU sampling interval in milliseconds for standard profiling */
  PROFILER_CPU_SAMPLING_INTERVAL_MS("profiler.cpu.sampling.interval.ms", 10),

  /** CPU sampling interval in milliseconds for lightweight profiling */
  PROFILER_LIGHTWEIGHT_SAMPLING_INTERVAL_MS("profiler.lightweight.sampling.interval.ms", 20),

  /** Number of threads for profiler scheduler thread pool */
  PROFILER_SCHEDULER_THREADS("profiler.scheduler.threads", 2),

  /** Maximum number of stored profiles before eviction */
  PROFILER_MAX_STORED_PROFILES("profiler.max.stored.profiles", 100),

  /** Directory path for profiler storage */
  PROFILER_STORAGE_PATH("profiler.storage.path", "logs/profiles"),

  // ==================== Tool Executor Settings ====================

  /** Shared tool executor shutdown timeout in seconds */
  TOOLS_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS("tools.executor.shutdown.timeout.seconds", 5),

  // ==================== Exception Analysis Settings ====================

  /** Maximum number of exceptions to retrieve in a single operation */
  EXCEPTION_ANALYSIS_MAX_COUNT("exception.analysis.max.count", 50),

  // ==================== Logging Integration Settings ====================

  /** Default number of log lines to retrieve for tail operations */
  LOGGING_DEFAULT_TAIL_LINES("logging.default.tail.lines", 50),

  /** Maximum number of log lines in memory buffer */
  LOGGING_MAX_BUFFER_LINES("logging.max.buffer.lines", 500),

  /** Number of lines to truncate back to when buffer exceeds maximum */
  LOGGING_TRUNCATE_BACK_TO("logging.truncate.back.to", 400);

  // ==================== Enum Implementation ====================

  private final String key;
  private final Object defaultValue;

  private Setting(String key, Object defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }

  /**
   * @return the configuration key used in properties files and system properties
   */
  public String key() {
    return key;
  }

  /**
   * @return the default value for this setting (type may vary)
   */
  public Object defaultValue() {
    return defaultValue;
  }

  /**
   * @return the default value as a String
   */
  public String defaultString() {
    return String.valueOf(defaultValue);
  }

  /**
   * Get the default value cast to a specific type.
   *
   * @param <T>  the target type
   * @param type the Class object for the target type
   * @return the default value cast to the specified type
   * @throws ClassCastException if the default value cannot be cast to the
   *                            specified type
   */
  public <T> T defaultValue(Class<T> type) {
    return type.cast(defaultValue);
  }

  // Static reverse lookup map for finding Setting by key
  private static final Map<String, Setting> BY_KEY = Arrays.stream(values())
      .collect(Collectors.toMap(Setting::key, Function.identity()));

  /**
   * Look up a Setting by its key.
   *
   * @param key the configuration key
   * @return the Setting enum constant, or null if not found
   */
  public static Setting fromKey(String key) {
    return BY_KEY.get(key);
  }

  /**
   * Get the default value for a configuration key, cast to a specific type.
   *
   * @param <T>  the target type
   * @param key  the configuration key
   * @param type the Class object for the target type
   * @return the default value for the key, or null if key not found
   */
  public static <T> T defaultFor(String key, Class<T> type) {
    Setting s = fromKey(key);
    return s == null ? null : s.defaultValue(type);
  }
}
