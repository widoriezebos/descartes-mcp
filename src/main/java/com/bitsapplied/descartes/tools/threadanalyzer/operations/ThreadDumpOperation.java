package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.settings.Setting;
import com.bitsapplied.descartes.settings.Settings;
import com.bitsapplied.descartes.tools.threadanalyzer.DetailLevelController;
import com.bitsapplied.descartes.tools.threadanalyzer.ThreadDumpBuilder;
import com.bitsapplied.descartes.tools.threadanalyzer.filters.FilterChain;
import com.bitsapplied.descartes.tools.threadanalyzer.scoring.ThreadImportanceScorer;
import com.bitsapplied.descartes.tools.threadanalyzer.strategies.StrategySelector;
import com.bitsapplied.descartes.tools.threadanalyzer.strategies.ThreadDumpStrategy;
import com.bitsapplied.descartes.tools.threadanalyzer.strategies.ThreadDumpStrategy.ThreadScorePair;
import com.bitsapplied.descartes.util.ParameterUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thread dump operation with intelligent truncation and prioritization.
 *
 * <p>This operation produces full text-based thread dumps for offline analysis with:
 * <ul>
 *   <li>Importance-based prioritization (BLOCKED threads, high CPU, contention)</li>
 *   <li>Automatic size limit enforcement (never exceeds maxResponseSizeBytes)</li>
 *   <li>Adaptive strategy selection based on thread count</li>
 *   <li>Progressive detail reduction when approaching size limit</li>
 *   <li>Rich metadata about what was included/excluded</li>
 * </ul>
 *
 * <p><b>Parameters:</b>
 * <ul>
 *   <li>max_stack_depth: Maximum stack frames per thread (default: 50)</li>
 *   <li>filter_stack_pattern: Regex to filter stack frames</li>
 *   <li>name_pattern: Regex to filter thread names</li>
 *   <li>state_filter: Array of thread states to include</li>
 *   <li>smart_truncation: Enable intelligent prioritization (default: true)</li>
 *   <li>importance_threshold: Minimum score for inclusion (default: 0)</li>
 *   <li>exclude_jvm_threads: "auto" | true | false (default: "auto")</li>
 *   <li>max_threads: Hard limit on thread count regardless of size</li>
 *   <li>detail_level: "full" | "minimal" | "adaptive" (default: "adaptive")</li>
 *   <li>sort_by: "importance" | "name" | "state" | "cpu_time" (default: "importance")</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class ThreadDumpOperation extends AbstractThreadOperation {

  private final Settings settings;

  public ThreadDumpOperation(ThreadMXBean threadMXBean, ExecutorService executor, FilterChain threadListFilters,
      FilterChain threadSearchFilters, ObjectMapper objectMapper, int maxResponseSizeBytes, int maxThreadsPerInspect,
      int defaultMaxResults, Settings settings) {
    super(threadMXBean, executor, threadListFilters, threadSearchFilters, objectMapper, maxResponseSizeBytes,
        maxThreadsPerInspect, defaultMaxResults);
    this.settings = settings;
  }

  @Override
  public String getOperationName() {
    return "thread_dump";
  }

  @Override
  public CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args), executor);
  }

  private Map<String, Object> execute(Map<String, Object> args) {
    // Extract parameters
    int maxStackDepth = ParameterUtils.getInt(args, "max_stack_depth", 50);
    String filterStackPattern = ParameterUtils.getString(args, "filter_stack_pattern", null);
    String namePattern = ParameterUtils.getString(args, "name_pattern", null);
    List<String> stateFilter = ParameterUtils.getStringList(args, "state_filter");

    // Smart truncation parameters
    boolean smartTruncation = ParameterUtils.getBoolean(args, "smart_truncation",
        settings.getBoolean(Setting.THREAD_DUMP_SMART_TRUNCATION_ENABLED));
    int importanceThreshold = ParameterUtils.getInt(args, "importance_threshold",
        settings.getInt(Setting.THREAD_DUMP_IMPORTANCE_THRESHOLD));
    String excludeJvmThreads = ParameterUtils.getString(args, "exclude_jvm_threads", "auto");
    Integer maxThreads = args.containsKey("max_threads") ? ParameterUtils.getInt(args, "max_threads", null) : null;
    String detailLevel = ParameterUtils.getString(args, "detail_level", "adaptive");
    String sortBy = ParameterUtils.getString(args, "sort_by", "importance");

    // Get all threads
    ThreadInfo[] allThreadInfos = threadMXBean.dumpAllThreads(true, true);

    // Apply user filters first (name_pattern, state_filter)
    List<ThreadInfo> userFilteredThreads = applyUserFilters(allThreadInfos, namePattern, stateFilter);

    // Score threads
    ThreadImportanceScorer scorer = new ThreadImportanceScorer();
    List<ThreadScorePair> scoredThreads = scoreThreads(userFilteredThreads, scorer);

    // Select strategy based on thread count
    ThreadDumpStrategy strategy = smartTruncation
        ? StrategySelector.selectStrategy(scoredThreads.size())
        : new com.bitsapplied.descartes.tools.threadanalyzer.strategies.FullDetailStrategy();

    // Apply strategy filtering and sorting
    List<ThreadScorePair> strategyFilteredThreads = strategy.filterAndSort(scoredThreads);

    // Apply importance threshold
    List<ThreadScorePair> thresholdFilteredThreads = strategyFilteredThreads.stream()
        .filter(pair -> pair.score() >= importanceThreshold)
        .toList();

    // Apply exclude_jvm_threads if specified
    List<ThreadScorePair> finalThreads = applyJvmThreadExclusion(
        thresholdFilteredThreads, excludeJvmThreads, userFilteredThreads.size());

    // Apply max_threads limit if specified
    if (maxThreads != null && finalThreads.size() > maxThreads) {
      finalThreads = finalThreads.subList(0, maxThreads);
    }

    // Calculate size budget
    int safetyMargin = settings.getInt(Setting.THREAD_DUMP_SIZE_SAFETY_MARGIN);
    int sizeBudget = Math.max(1000, maxResponseSizeBytes - safetyMargin);

    // Adjust stack depth based on strategy
    int adjustedStackDepth = strategy.getRecommendedStackDepth(maxStackDepth);

    // Build dump with size tracking
    DetailLevelController detailController = new DetailLevelController(sizeBudget);
    ThreadDumpBuilder builder = new ThreadDumpBuilder(
        detailController, strategy, adjustedStackDepth, filterStackPattern);

    // Track excluded threads
    int threadsExcludedByStrategy = scoredThreads.size() - strategyFilteredThreads.size();
    int threadsExcludedByThreshold = strategyFilteredThreads.size() - thresholdFilteredThreads.size();
    int threadsExcludedByJvmFilter = thresholdFilteredThreads.size() - finalThreads.size();

    builder.recordThreadsExcludedByScore(
        threadsExcludedByStrategy + threadsExcludedByThreshold + threadsExcludedByJvmFilter);

    // Add threads to dump
    for (ThreadScorePair pair : finalThreads) {
      boolean added = builder.tryAddThread(pair, this::formatThreadInfo);
      if (!added) {
        // Size limit reached, stop adding
        break;
      }
    }

    // Add truncation footer if needed
    if (builder.isTruncated()) {
      builder.addTruncationFooter();
    }

    // Build response with rich metadata
    Map<String, Object> result = new HashMap<>();
    result.put("status", "success"); // Backward compatibility
    result.put("success", true);
    result.put("thread_dump", builder.getDump());

    // Backward compatibility fields
    result.put("total_threads", allThreadInfos.length);
    result.put("filtered_threads", userFilteredThreads.size());

    // Collection metadata
    Map<String, Object> collectionMeta = new HashMap<>();
    collectionMeta.put("total_threads_in_jvm", allThreadInfos.length);
    collectionMeta.put("threads_after_user_filters", userFilteredThreads.size());
    collectionMeta.put("threads_included_in_dump", builder.getThreadsIncluded());
    collectionMeta.put("threads_excluded", builder.getThreadsExcluded());

    // Truncation metadata
    Map<String, Object> truncationMeta = new HashMap<>();
    truncationMeta.put("truncated", builder.isTruncated());
    truncationMeta.put("strategy_used", strategy.getStrategyName());
    truncationMeta.put("size_limit_bytes", sizeBudget);
    truncationMeta.put("actual_size_bytes", detailController.getUsedBudget());
    truncationMeta.put("detail_reductions_applied", detailController.getDetailReductionCount());

    // Exclusion breakdown
    Map<String, Integer> exclusionBreakdown = new HashMap<>(builder.getExclusionBreakdown());
    if (threadsExcludedByStrategy > 0) {
      exclusionBreakdown.put("strategy_filtering", threadsExcludedByStrategy);
    }
    if (threadsExcludedByThreshold > 0) {
      exclusionBreakdown.put("importance_threshold", threadsExcludedByThreshold);
    }
    if (threadsExcludedByJvmFilter > 0) {
      exclusionBreakdown.put("jvm_thread_exclusion", threadsExcludedByJvmFilter);
    }

    // Filters applied
    List<String> filtersApplied = new ArrayList<>();
    if (namePattern != null) {
      filtersApplied.add("User: name_pattern=" + namePattern);
    }
    if (stateFilter != null && !stateFilter.isEmpty()) {
      filtersApplied.add("User: state_filter=" + stateFilter);
    }
    if (filterStackPattern != null) {
      filtersApplied.add("User: filter_stack_pattern=" + filterStackPattern);
    }
    if (smartTruncation) {
      filtersApplied.add("Smart: " + StrategySelector.getSelectionReason(userFilteredThreads.size()));
    }
    if (strategy.autoExcludesJvmThreads()) {
      filtersApplied.add("Auto: excluded JVM system threads (score < 0)");
    }

    // Recommendations
    List<String> recommendations = new ArrayList<>();
    if (builder.isTruncated()) {
      recommendations.add("For specific threads, use thread_inspect with thread IDs");
      recommendations.add("To narrow down, use thread_search with more specific filters");
      recommendations.add("Consider name_pattern to focus on app threads only");
      recommendations.add("Reduce max_stack_depth to fit more threads");
    }
    if (userFilteredThreads.size() > settings.getInt(Setting.THREAD_DUMP_MAX_THREADS_SOFT_LIMIT)) {
      recommendations.add("Consider using thread_search instead for large thread counts");
    }

    // Assemble metadata
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("collection", collectionMeta);
    metadata.put("truncation", truncationMeta);
    metadata.put("exclusion_breakdown", exclusionBreakdown);
    metadata.put("filters_applied", filtersApplied);
    metadata.put("recommendations", recommendations);

    // Included threads summary (top 10 for quick triage)
    List<Map<String, Object>> includedSummary = builder.getIncludedThreads().stream()
        .limit(10)
        .map(this::threadSummaryToMap)
        .toList();
    metadata.put("included_threads_summary", includedSummary);

    result.put("metadata", metadata);
    result.put("timestamp", System.currentTimeMillis());

    return result;
  }

  /**
   * Applies user filters (name_pattern, state_filter).
   */
  private List<ThreadInfo> applyUserFilters(ThreadInfo[] allThreads, String namePattern, List<String> stateFilter) {
    Pattern nameRegex = namePattern != null ? safeCompilePattern(namePattern, "name_pattern") : null;
    Set<Thread.State> stateSet = stateFilter != null && !stateFilter.isEmpty()
        ? stateFilter.stream().map(Thread.State::valueOf).collect(Collectors.toSet())
        : null;

    List<ThreadInfo> filtered = new ArrayList<>();
    for (ThreadInfo info : allThreads) {
      if (info == null) continue;

      // Apply name filter
      if (nameRegex != null && !nameRegex.matcher(info.getThreadName()).find()) {
        continue;
      }

      // Apply state filter
      if (stateSet != null && !stateSet.contains(info.getThreadState())) {
        continue;
      }

      filtered.add(info);
    }
    return filtered;
  }

  /**
   * Scores all threads using the importance scorer.
   */
  private List<ThreadScorePair> scoreThreads(List<ThreadInfo> threads, ThreadImportanceScorer scorer) {
    List<ThreadScorePair> scored = new ArrayList<>();
    for (ThreadInfo info : threads) {
      long cpuTime = -1;
      try {
        cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
        if (cpuTime > 0) {
          cpuTime = cpuTime / 1_000_000; // Convert to milliseconds
        }
      } catch (UnsupportedOperationException e) {
        // CPU time not supported
      }

      int score = scorer.scoreThread(info, cpuTime);
      scored.add(new ThreadScorePair(info, score, cpuTime));
    }
    return scored;
  }

  /**
   * Applies JVM thread exclusion based on user preference.
   */
  private List<ThreadScorePair> applyJvmThreadExclusion(
      List<ThreadScorePair> threads, String excludeJvmThreads, int threadCountAfterUserFilters) {
    boolean shouldExclude = switch (excludeJvmThreads.toLowerCase()) {
      case "true" -> true;
      case "false" -> false;
      case "auto" -> threadCountAfterUserFilters >= settings.getInt(Setting.THREAD_DUMP_AUTO_EXCLUDE_JVM_THRESHOLD);
      default -> false;
    };

    if (!shouldExclude) {
      return threads;
    }

    return threads.stream()
        .filter(pair -> !ThreadImportanceScorer.isJvmSystemThread(pair.threadInfo().getThreadName()))
        .toList();
  }

  /**
   * Converts thread summary to map for JSON serialization.
   */
  private Map<String, Object> threadSummaryToMap(ThreadDumpBuilder.ThreadSummary summary) {
    Map<String, Object> map = new HashMap<>();
    map.put("name", summary.name());
    map.put("id", summary.id());
    map.put("state", summary.state());
    map.put("importance_score", summary.score());
    map.put("cpu_time_ms", summary.cpuTimeMs());
    map.put("blocked_time_ms", summary.blockedTimeMs());
    return map;
  }
}
