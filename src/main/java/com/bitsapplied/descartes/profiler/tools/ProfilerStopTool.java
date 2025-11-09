package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolResponse;

public class ProfilerStopTool implements MCPTool {

  private final ProfilerService profilerService;

  public ProfilerStopTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
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
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (params == null || !params.containsKey("profile_id")) {
          return ToolResponse.error(400, "profile_id is required");
        }
        Object profileIdValue = params.get("profile_id");
        if (!(profileIdValue instanceof String)) {
          return ToolResponse.error(400, "profile_id must be a non-empty string");
        }
        String profileId = ((String) profileIdValue).trim();
        if (profileId.isEmpty()) {
          return ToolResponse.error(400, "profile_id must be a non-empty string");
        }

        try {
          ProfileSnapshot snapshot = profilerService.stopProfiling(profileId);

          return ToolResponse.successJson(Map.of("success", true, "profile_id", profileId, "status", "stopped",
              "total_samples", snapshot.getTotalSamples(), "duration_seconds", snapshot.getDurationSeconds(), "message",
              String.format("Profiling stopped. Captured %d samples in %ds. Use profiler_hotspots to analyze.",
                  snapshot.getTotalSamples(), snapshot.getDurationSeconds())));

        } catch (ProfilerException e) {
          int code = e.getMessage() != null && e.getMessage().contains("No active recording") ? 404 : 500;
          return ToolResponse.error(code, e.getMessage());
        }
      } catch (Exception e) {
        return ToolResponse.error(9999, "Profiler stop failed: " + e.getMessage());
      }
    });
  }
}
