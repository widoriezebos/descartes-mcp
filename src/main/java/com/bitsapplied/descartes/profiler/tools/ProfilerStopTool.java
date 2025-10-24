package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProfilerStopTool implements MCPTool {

  private final ProfilerService profilerService;
  private final ObjectMapper objectMapper;

  public ProfilerStopTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public String getToolName() {
    return "profiler_stop";
  }

  @Override
  public String getToolDescription() {
    return "Force-stop an active profiling session before its scheduled completion. "
        + "Useful if you want results early or need to free resources. "
        + "Returns profile ID that can be used with profiler_hotspots.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("profile_id", Map.of("type", "string", "description", "Profile ID from profiler_start to stop")),
        "required", List.of("profile_id"));
  }

  @Override
  public String executeTool(Map<String, Object> params) throws Exception {
    String profileId = (String) params.get("profile_id");

    try {
      ProfileSnapshot snapshot = profilerService.stopProfiling(profileId);

      return objectMapper.writeValueAsString(Map.of("success", true, "profile_id", profileId, "status", "stopped",
          "total_samples", snapshot.getTotalSamples(), "duration_seconds", snapshot.getDurationSeconds(), "message",
          String.format("Profiling stopped. Captured %d samples in %ds. Use profiler_hotspots to analyze.",
              snapshot.getTotalSamples(), snapshot.getDurationSeconds())));

    } catch (ProfilerException e) {
      return objectMapper.writeValueAsString(Map.of("success", false, "error", e.getMessage()));
    }
  }
}
