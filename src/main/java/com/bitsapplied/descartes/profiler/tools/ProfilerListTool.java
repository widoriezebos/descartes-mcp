package com.bitsapplied.descartes.profiler.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProfilerListTool implements MCPTool {

  private final ProfilerService profilerService;
  private final ObjectMapper objectMapper;

  public ProfilerListTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
    this.objectMapper = new ObjectMapper();
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
  public String executeTool(Map<String, Object> params) throws Exception {
    List<String> activeRecordings = profilerService.listActiveRecordings();
    List<ProfileSnapshot> storedProfiles = profilerService.listProfiles();

    List<Map<String, Object>> profilesList = storedProfiles.stream().map(snapshot -> {
      Map<String, Object> map = new HashMap<>();
      map.put("profile_id", snapshot.getMetadata().getProfileId());
      map.put("start_time", snapshot.getMetadata().getStartTime().toString());
      map.put("duration_seconds", snapshot.getDurationSeconds());
      map.put("total_samples", snapshot.getTotalSamples());
      map.put("status", "completed");
      return map;
    }).collect(Collectors.toList());

    return objectMapper.writeValueAsString(Map.of("success", true, "active_recordings", activeRecordings,
        "stored_profiles", profilesList, "total_stored", storedProfiles.size()));
  }
}
