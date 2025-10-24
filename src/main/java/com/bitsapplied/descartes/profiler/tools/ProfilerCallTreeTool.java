package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.CallTreeNode;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProfilerCallTreeTool implements MCPTool {

  private final ProfilerService profilerService;
  private final ObjectMapper objectMapper;

  public ProfilerCallTreeTool(ProfilerService profilerService) {
    this.profilerService = profilerService;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public String getToolName() {
    return "profiler_call_tree";
  }

  @Override
  public String getToolDescription() {
    return "Get call tree for a specific method showing what it calls (callees) or what calls it (callers). "
        + "Reveals the execution hierarchy and where time is spent within a method's execution path. "
        + "Use this after profiler_hotspots to understand why a hotspot method is slow.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties", Map.of("profile_id",
        Map.of("type", "string", "description", "Profile ID from profiler_start"), "method_pattern",
        Map.of("type", "string", "description", "Method pattern to search:\n" + "- Simple name: 'recallSimilar'\n"
            + "- Class.method: 'MemoryService.recallSimilar'\n" + "- Wildcard: '*.recallSimilar' or 'MemoryService.*'"),
        "max_depth", Map.of("type", "integer", "description", "Maximum tree depth to return (1-50)", "default", 10,
            "minimum", 1, "maximum", 50)),
        "required", List.of("profile_id", "method_pattern"));
  }

  @Override
  public String executeTool(Map<String, Object> params) throws Exception {
    String profileId = (String) params.get("profile_id");
    String methodPattern = (String) params.get("method_pattern");
    int maxDepth = params.containsKey("max_depth") ? ((Number) params.get("max_depth")).intValue() : 10;

    ProfileSnapshot snapshot = profilerService.getProfile(profileId);

    if (snapshot == null) {
      return objectMapper.writeValueAsString(Map.of("success", false, "error", "Profile not found: " + profileId));
    }

    // Find matching methods
    List<String> matches = snapshot.findMethods(methodPattern);
    if (matches.isEmpty()) {
      return objectMapper
          .writeValueAsString(Map.of("success", false, "error", "No methods match pattern: " + methodPattern,
              "suggestion", "Try a simpler pattern like just the method name, or check spelling."));
    }

    // Get call tree for first match
    String matchedMethod = matches.get(0);
    CallTreeNode tree = snapshot.getCallTree(matchedMethod);

    if (tree == null) {
      return objectMapper
          .writeValueAsString(Map.of("success", false, "error", "Call tree not available for: " + matchedMethod));
    }

    return objectMapper.writeValueAsString(
        Map.of("success", true, "profile_id", profileId, "method_pattern", methodPattern, "matched_method",
            matchedMethod, "all_matches", matches, "tree", tree.toMap(snapshot.getTotalSamples(), maxDepth)));
  }
}
