package com.bitsapplied.descartes.profiler.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProfilerStartTool implements MCPTool {

  private final ProfilerService profilerService;
  private final ObjectMapper objectMapper;

  public ProfilerStartTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public String getToolName() {
    return "profiler_start";
  }

  @Override
  public String getToolDescription() {
    return "Start JFR profiling to capture CPU, memory allocation, lock contention, and I/O performance data. "
        + "Records runtime behavior for specified duration, then stops automatically. "
        + "Returns profile ID for later analysis with profiler_hotspots and profiler_call_tree tools.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("duration_seconds",
            Map.of("type", "integer", "description",
                "How long to record in seconds (10-300 seconds). "
                    + "Longer durations provide more samples but use more disk space.",
                "default", 30, "minimum", 10, "maximum", 300),
            "profile_type",
            Map.of("type", "string", "enum", List.of("cpu", "allocation", "comprehensive", "lightweight"),
                "description",
                "Preset configuration:\n"
                    + "- cpu: CPU sampling only (recommended for finding computation bottlenecks)\n"
                    + "- allocation: Memory allocation tracking (for finding memory leaks)\n"
                    + "- comprehensive: All events including locks and I/O (~2% overhead)\n"
                    + "- lightweight: Low overhead CPU sampling (~0.5% overhead)",
                "default", "cpu"),
            "package_filter",
            Map.of("type", "string", "description",
                "Only profile methods in this package (e.g. 'com.bitsapplied'). "
                    + "Reduces noise from JDK and library code.",
                "default", "com.bitsapplied")),
        "required", List.of());
  }

  @Override
  public String executeTool(Map<String, Object> params) throws Exception {
    // Check if profiler is enabled
    if (!profilerService.isEnabled()) {
      return objectMapper
          .writeValueAsString(Map.of("success", false, "error", "Profiler is disabled. Enable via profiler settings."));
    }

    // Check if JFR is available
    if (!profilerService.isJFRAvailable()) {
      return objectMapper
          .writeValueAsString(Map.of("success", false, "error", "JFR not available. Requires JDK 11+ runtime."));
    }

    // Parse parameters
    int durationSeconds = params.containsKey("duration_seconds") ? ((Number) params.get("duration_seconds")).intValue()
        : 30;
    String profileType = (String) params.getOrDefault("profile_type", "cpu");
    String packageFilter = (String) params.getOrDefault("package_filter", "com.bitsapplied");

    // Build config
    ProfilerConfig config = buildConfig(durationSeconds, profileType, packageFilter);

    // Start profiling
    try {
      String profileId = profilerService.startProfiling(Duration.ofSeconds(durationSeconds), config);

      return objectMapper.writeValueAsString(
          Map.of("success", true, "profile_id", profileId, "status", "recording", "duration_seconds", durationSeconds,
              "profile_type", profileType, "sampling_interval_ms", config.getSamplingIntervalMs(), "message",
              String.format("Profiling started (ID: %s). Will automatically stop after %d seconds. "
                  + "Use profiler_hotspots to analyze results.", profileId, durationSeconds),
              "estimated_completion_time", Instant.now().plus(Duration.ofSeconds(durationSeconds)).toString()));

    } catch (ProfilerException e) {
      return objectMapper.writeValueAsString(Map.of("success", false, "error", e.getMessage()));
    }
  }

  private ProfilerConfig buildConfig(int durationSeconds, String profileType, String packageFilter) {
    ProfilerConfig.Builder builder = ProfilerConfig.builder().duration(Duration.ofSeconds(durationSeconds))
        .packageFilter(packageFilter);

    switch (profileType.toLowerCase()) {
    case "cpu":
      return builder.cpuOnly().samplingInterval(10).build();
    case "allocation":
      return builder.allocationOnly().build();
    case "comprehensive":
      return builder.allProfilingEnabled().samplingInterval(10).build();
    case "lightweight":
      return builder.cpuOnly().samplingInterval(20).build();
    default:
      return builder.cpuOnly().build();
    }
  }
}
