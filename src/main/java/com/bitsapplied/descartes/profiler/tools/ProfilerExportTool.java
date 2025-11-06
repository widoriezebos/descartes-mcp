package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.export.FlameGraphExporter;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolResponse;
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
        String format = params.getOrDefault("format", "text") instanceof String str ? str.toLowerCase() : "text";
        if (!List.of("json", "text", "flamegraph").contains(format)) {
          return ToolResponse.error(400, "Unsupported export format: " + format);
        }

        ProfileSnapshot snapshot = profilerService.getProfile(profileId);

        if (snapshot == null) {
          return ToolResponse.error(404, "Profile not found: " + profileId);
        }

        try {
          if ("json".equals(format)) {
            // Export as JSON
            String json = objectMapper.writeValueAsString(snapshot.toMap());
            return ToolResponse
                .successJson(Map.of("success", true, "profile_id", profileId, "format", "json", "content", json,
                    "size_bytes", json.length()));

          } else if ("flamegraph".equals(format)) {
            // Export as interactive HTML flame graph
            FlameGraphExporter exporter = new FlameGraphExporter();
            String html = exporter.exportToHtml(snapshot);
            return ToolResponse.successJson(Map.of("success", true, "profile_id", profileId, "format", "flamegraph",
                "content", html, "size_bytes", html.length(), "message",
                "Open the HTML content in a browser to view the interactive flame graph"));

          } else {
            // Export as text summary
            String summary = snapshot.getSummary();
            return ToolResponse
                .successJson(Map.of("success", true, "profile_id", profileId, "format", "text", "content", summary));
          }

        } catch (Exception e) {
          return ToolResponse.error(500, "Export failed: " + e.getMessage());
        }
      } catch (Exception e) {
        return ToolResponse.error(9999, "Profiler export failed: " + e.getMessage());
      }
    });
  }
}
