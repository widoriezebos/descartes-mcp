package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.Hotspot;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProfilerHotspotsTool implements MCPTool {

  private final ProfilerService profilerService;
  private final ObjectMapper objectMapper;

  public ProfilerHotspotsTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
    this.objectMapper = new ObjectMapper();
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
  public String executeTool(Map<String, Object> params) throws Exception {
    String profileId = (String) params.get("profile_id");
    String hotspotType = (String) params.getOrDefault("hotspot_type", "cpu");
    int topN = params.containsKey("top_n") ? ((Number) params.get("top_n")).intValue() : 20;
    double minPercentage = params.containsKey("min_percentage") ? ((Number) params.get("min_percentage")).doubleValue()
        : 1.0;

    ProfileSnapshot snapshot = profilerService.getProfile(profileId);

    if (snapshot == null) {
      return objectMapper.writeValueAsString(Map.of("success", false, "error", "Profile not found: " + profileId,
          "suggestion", "Use profiler_start to create a new profile, or check the profile_id spelling."));
    }

    List<Hotspot> hotspots;
    switch (hotspotType.toLowerCase()) {
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
      return objectMapper.writeValueAsString(Map.of("success", false, "error", "Unknown hotspot type: " + hotspotType));
    }

    // Filter by minimum percentage
    hotspots = hotspots.stream().filter(h -> h.getPercentage() >= minPercentage).collect(Collectors.toList());

    return objectMapper.writeValueAsString(Map.of("success", true, "profile_id", profileId, "hotspot_type", hotspotType,
        "total_samples", snapshot.getTotalSamples(), "duration_seconds", snapshot.getDurationSeconds(), "hotspots",
        hotspots.stream().map(Hotspot::toMap).collect(Collectors.toList()), "insights", snapshot.getInsights(),
        "recommendations", snapshot.getRecommendations()));
  }
}
