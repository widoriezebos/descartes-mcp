package com.bitsapplied.descartes.profiler.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolResponse;

public class ProfilerListTool implements MCPTool {

  private final ProfilerService profilerService;

  public ProfilerListTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
  }

  @Override
  public String getToolName() {
    return "profiler_list";
  }

  @Override
  public String getToolDescription() {
    return "List all stored profiles and active profiling sessions. "
        + "Shows profile IDs, timestamps, durations, and status. Use this to find available profiles.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        List<String> activeRecordings = profilerService.listActiveRecordings();
        List<String> storedIds = profilerService.listStoredProfiles();

        List<Map<String, Object>> profilesList = storedIds.stream().distinct().map(id -> {
          Map<String, Object> map = new HashMap<>();
          map.put("profile_id", id);

          ProfileSnapshot snapshot = profilerService.getProfile(id);
          boolean isActive = activeRecordings.contains(id);

          if (snapshot != null) {
            map.put("start_time", snapshot.getMetadata().getStartTime().toString());
            map.put("duration_seconds", snapshot.getDurationSeconds());
            map.put("total_samples", snapshot.getTotalSamples());
            map.put("status", isActive ? "recording" : "completed");
          } else {
            map.put("status", isActive ? "recording" : "unavailable");
          }
          return map;
        }).collect(Collectors.toList());

        return ToolResponse.successJson(Map.of("success", true, "active_recordings", activeRecordings,
            "stored_profiles", profilesList, "total_stored", profilesList.size()));
      } catch (Exception e) {
        return ToolResponse.error(9999, "Profiler list failed: " + e.getMessage());
      }
    });
  }
}
