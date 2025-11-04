package com.bitsapplied.descartes.profiler.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.model.CallTreeNode;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolResponse;
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
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (params == null) {
          return ToolResponse.error(400, "profile_id and method_pattern are required");
        }

        Object profileIdValue = params.get("profile_id");
        Object methodPatternValue = params.get("method_pattern");
        if (!(profileIdValue instanceof String profileId) || profileId.isBlank()) {
          return ToolResponse.error(400, "profile_id must be a non-empty string");
        }
        if (!(methodPatternValue instanceof String methodPattern) || methodPattern.isBlank()) {
          return ToolResponse.error(400, "method_pattern must be a non-empty string");
        }

        final int maxDepth;
        try {
          maxDepth = parseMaxDepth(params.get("max_depth"));
        } catch (IllegalArgumentException ex) {
          return ToolResponse.error(400, ex.getMessage());
        }

        ProfileSnapshot snapshot = profilerService.getProfile(profileId);

        if (snapshot == null) {
          return ToolResponse.error(404, "Profile not found: " + profileId);
        }

        // Find matching methods
        List<String> matches = snapshot.findMethods(methodPattern);
        if (matches.isEmpty()) {
          return ToolResponse.error(404, "No methods match pattern: " + methodPattern);
        }

        // Get call tree for first match
        String matchedMethod = matches.get(0);
        CallTreeNode tree = snapshot.getCallTree(matchedMethod);

        if (tree == null) {
          return ToolResponse.error(404, "Call tree not available for: " + matchedMethod);
        }

        return ToolResponse.success(objectMapper.writeValueAsString(
            Map.of("success", true, "profile_id", profileId, "method_pattern", methodPattern, "matched_method",
                matchedMethod, "all_matches", matches, "tree", tree.toMap(snapshot.getTotalSamples(), maxDepth))));
      } catch (Exception e) {
        return ToolResponse.error(9999, "Profiler call tree analysis failed: " + e.getMessage());
      }
    });
  }

  private int parseMaxDepth(Object value) {
    int maxDepth = 10;
    if (value instanceof Number number) {
      maxDepth = number.intValue();
    } else if (value instanceof String str) {
      try {
        maxDepth = Integer.parseInt(str);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("max_depth must be an integer between 1 and 50");
      }
    } else if (value != null) {
      throw new IllegalArgumentException("max_depth must be an integer between 1 and 50");
    }

    if (maxDepth < 1 || maxDepth > 50) {
      throw new IllegalArgumentException("max_depth must be an integer between 1 and 50");
    }
    return maxDepth;
  }
}
