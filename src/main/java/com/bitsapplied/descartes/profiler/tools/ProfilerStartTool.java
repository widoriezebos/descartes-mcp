package com.bitsapplied.descartes.profiler.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolResponse;

public class ProfilerStartTool implements MCPTool {

  private final ProfilerService profilerService;

  public ProfilerStartTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
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
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (!profilerService.isEnabled()) {
          return ToolResponse.error(503, "Profiler is disabled. Enable via profiler settings.");
        }

        if (!profilerService.isJFRAvailable()) {
          return ToolResponse.error(501, "JFR not available. Requires JDK 11+ runtime.");
        }

        final Map<String, Object> arguments = params == null ? Map.of() : params;

        final int durationSeconds;
        try {
          durationSeconds = parseDuration(arguments.get("duration_seconds"));
        } catch (IllegalArgumentException e) {
          return ToolResponse.error(400, e.getMessage());
        }
        if (durationSeconds < 10 || durationSeconds > 300) {
          return ToolResponse.error(400, "duration_seconds must be between 10 and 300 seconds");
        }

        String profileType = parseProfileType(arguments.get("profile_type"));
        if (profileType == null) {
          return ToolResponse.error(400, "profile_type must be one of: cpu, allocation, comprehensive, lightweight");
        }

        String packageFilter = arguments.getOrDefault("package_filter", "com.bitsapplied") instanceof String str ? str
            : "com.bitsapplied";

        ProfilerConfig config = buildConfig(durationSeconds, profileType, packageFilter);

        try {
          String profileId = profilerService.startProfiling(Duration.ofSeconds(durationSeconds), config);

          return ToolResponse.successJson(Map.of("success", true, "profile_id", profileId,
              "status", "recording", "duration_seconds", durationSeconds, "profile_type", profileType,
              "sampling_interval_ms", config.getSamplingIntervalMs(), "message",
              String.format("Profiling started (ID: %s). Will automatically stop after %d seconds. "
                  + "Use profiler_hotspots to analyze results.", profileId, durationSeconds),
              "estimated_completion_time", Instant.now().plus(Duration.ofSeconds(durationSeconds)).toString()));

        } catch (ProfilerException e) {
          return ToolResponse.error(500, "Failed to start profiling: " + e.getMessage());
        }
      } catch (Exception e) {
        return ToolResponse.error(9999, "Profiler start failed: " + e.getMessage());
      }
    });
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

  private int parseDuration(Object value) {
    if (value == null) {
      return 30;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String str) {
      try {
        return Integer.parseInt(str);
      } catch (NumberFormatException ignored) {
      }
    }
    throw new IllegalArgumentException("duration_seconds must be a number");
  }

  private String parseProfileType(Object value) {
    String type = value instanceof String str ? str.toLowerCase() : "cpu";
    return switch (type) {
    case "cpu", "allocation", "comprehensive", "lightweight" -> type;
    default -> null;
    };
  }
}
