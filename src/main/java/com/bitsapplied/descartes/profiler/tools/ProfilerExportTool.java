package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.export.FlameGraphExporter;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class ProfilerExportTool implements MCPTool {

  private final ProfilerService profilerService;
  private final ObjectMapper objectMapper;

  public ProfilerExportTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
    this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  }

  @Override
  public String getToolName() {
    return "profiler_export";
  }

  @Override
  public String getToolDescription() {
    return "Export a profile to JSON, text summary, or interactive HTML flame graph. "
        + "JSON export is suitable for external analysis tools or archiving. "
        + "Text summary provides human-readable overview. "
        + "Flame graph generates interactive HTML visualization (zoom, search, tooltips).";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties", Map.of("profile_id",
        Map.of("type", "string", "description", "Profile ID to export"), "format",
        Map.of("type", "string", "enum", List.of("json", "text", "flamegraph"), "description",
            "Export format:\n" + "- json: Full JSON export (for archiving or external tools)\n"
                + "- text: Human-readable summary\n" + "- flamegraph: Interactive HTML flame graph (opens in browser)",
            "default", "text")),
        "required", List.of("profile_id"));
  }

  @Override
  public String executeTool(Map<String, Object> params) throws Exception {
    String profileId = (String) params.get("profile_id");
    String format = (String) params.getOrDefault("format", "text");

    ProfileSnapshot snapshot = profilerService.getProfile(profileId);

    if (snapshot == null) {
      return objectMapper.writeValueAsString(Map.of("success", false, "error", "Profile not found: " + profileId));
    }

    try {
      if ("json".equals(format)) {
        // Export as JSON
        String json = objectMapper.writeValueAsString(snapshot.toMap());
        return objectMapper.writeValueAsString(Map.of("success", true, "profile_id", profileId, "format", "json",
            "content", json, "size_bytes", json.length()));

      } else if ("flamegraph".equals(format)) {
        // Export as interactive HTML flame graph
        FlameGraphExporter exporter = new FlameGraphExporter();
        String html = exporter.exportToHtml(snapshot);
        return objectMapper.writeValueAsString(
            Map.of("success", true, "profile_id", profileId, "format", "flamegraph", "content", html, "size_bytes",
                html.length(), "message", "Open the HTML content in a browser to view the interactive flame graph"));

      } else {
        // Export as text summary
        String summary = snapshot.getSummary();
        return objectMapper
            .writeValueAsString(Map.of("success", true, "profile_id", profileId, "format", "text", "content", summary));
      }

    } catch (Exception e) {
      return objectMapper.writeValueAsString(Map.of("success", false, "error", "Export failed: " + e.getMessage()));
    }
  }
}
