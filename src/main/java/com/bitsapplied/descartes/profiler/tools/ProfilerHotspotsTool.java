package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.Hotspot;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolResponse;

public class ProfilerHotspotsTool implements MCPTool {

  private final ProfilerService profilerService;

  public ProfilerHotspotsTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
  }

  @Override
  public String getToolName() {
    return "profiler_hotspots";
  }

  @Override
  public String getToolDescription() {
    return "Get top CPU or memory allocation hotspots from a completed profile. "
        + "Returns methods ranked by percentage of time/allocations, with source locations (file:line) "
        + "and actionable insights for optimization. Use this to identify performance bottlenecks.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("profile_id", Map.of("type", "string", "description", "Profile ID from profiler_start tool"),
            "hotspot_type",
            Map.of("type", "string", "enum", List.of("cpu", "allocation", "lock"), "description",
                "Type of hotspots:\n" + "- cpu: Methods consuming most CPU time (execution samples)\n"
                    + "- allocation: Methods allocating most memory (bytes allocated)\n"
                    + "- lock: Methods with most lock contention (wait time in ms)",
                "default", "cpu"),
            "top_n",
            Map.of("type", "integer", "description", "Number of hotspots to return (1-100)", "default", 20, "minimum",
                1, "maximum", 100),
            "min_percentage", Map.of("type", "number", "description",
                "Only show hotspots above this percentage (0-100)", "default", 1.0, "minimum", 0.0, "maximum", 100.0)),
        "required", List.of("profile_id"));
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (params == null || !params.containsKey("profile_id")) {
          return ToolResponse.error(400, "profile_id is required");
        }

        Object profileIdValue = params.get("profile_id");
        if (!(profileIdValue instanceof String profileId) || profileId.isBlank()) {
          return ToolResponse.error(400, "profile_id must be a non-empty string");
        }

        String hotspotType = params.getOrDefault("hotspot_type", "cpu") instanceof String str ? str.toLowerCase()
            : "cpu";
        if (!List.of("cpu", "allocation", "lock").contains(hotspotType)) {
          return ToolResponse.error(400, "Unknown hotspot type: " + hotspotType);
        }

        final int topN;
        final double minPercentage;
        try {
          topN = parseInteger(params.get("top_n"), 20, 1, 100, "top_n must be between 1 and 100");
          minPercentage = parseDouble(params.get("min_percentage"), 1.0, 0.0, 100.0,
              "min_percentage must be between 0 and 100");
        } catch (IllegalArgumentException ex) {
          return ToolResponse.error(400, ex.getMessage());
        }

        ProfileSnapshot snapshot = profilerService.getProfile(profileId);

        if (snapshot == null) {
          return ToolResponse.error(404, "Profile not found: " + profileId);
        }

        List<Hotspot> hotspots;
        switch (hotspotType) {
        case "cpu":
          hotspots = snapshot.getCPUHotspots(topN);
          break;
        case "allocation":
          hotspots = snapshot.getAllocationHotspots(topN);
          break;
        case "lock":
          hotspots = snapshot.getLockHotspots(topN);
          break;
        default:
          throw new IllegalStateException("Unexpected hotspot type: " + hotspotType);
        }

        // Filter by minimum percentage
        hotspots = hotspots.stream().filter(h -> h.getPercentage() >= minPercentage).collect(Collectors.toList());

        return ToolResponse.successJson(Map.of("success", true, "profile_id", profileId, "hotspot_type", hotspotType,
            "total_samples", snapshot.getTotalSamples(), "duration_seconds", snapshot.getDurationSeconds(), "hotspots",
            hotspots.stream().map(Hotspot::toMap).collect(Collectors.toList()), "insights", snapshot.getInsights(),
            "recommendations", snapshot.getRecommendations()));
      } catch (Exception e) {
        return ToolResponse.error(9999, "Profiler hotspots analysis failed: " + e.getMessage());
      }
    });
  }

  private int parseInteger(Object value, int defaultValue, int min, int max, String errorMessage) {
    int result = defaultValue;
    if (value instanceof Number number) {
      result = number.intValue();
    } else if (value instanceof String str) {
      try {
        result = Integer.parseInt(str);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(errorMessage);
      }
    } else if (value != null) {
      throw new IllegalArgumentException(errorMessage);
    }

    if (result < min || result > max) {
      throw new IllegalArgumentException(errorMessage);
    }
    return result;
  }

  private double parseDouble(Object value, double defaultValue, double min, double max, String errorMessage) {
    double result = defaultValue;
    if (value instanceof Number number) {
      result = number.doubleValue();
    } else if (value instanceof String str) {
      try {
        result = Double.parseDouble(str);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(errorMessage);
      }
    } else if (value != null) {
      throw new IllegalArgumentException(errorMessage);
    }

    if (result < min || result > max) {
      throw new IllegalArgumentException(errorMessage);
    }
    return result;
  }
}
