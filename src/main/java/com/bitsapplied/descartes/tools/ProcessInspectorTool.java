package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.util.ProcessInspector;

/**
 * MCP tool that provides thread stack trace inspection capabilities.
 * <p>
 * This tool allows capturing and filtering thread stack traces with various
 * options:
 * <ul>
 * <li>Whitelist filtering using wildcard patterns</li>
 * <li>Module-based filtering</li>
 * <li>Stack trace trimming to specific modules</li>
 * <li>Optional inclusion of the current thread</li>
 * </ul>
 */
public class ProcessInspectorTool implements MCPTool {
  private static final String TOOL_NAME = "process_inspector_stacks";

  private final ProcessInspector inspector;

  /**
   * Creates a new ProcessInspectorTool with default ProcessInspector.
   */
  public ProcessInspectorTool() {
    this(new ProcessInspector());
  }

  /**
   * Creates a new ProcessInspectorTool with the specified ProcessInspector.
   * 
   * @param inspector the ProcessInspector to use for capturing thread stacks
   */
  public ProcessInspectorTool(ProcessInspector inspector) {
    this.inspector = inspector;
  }

  @Override
  public String getToolName() {
    return TOOL_NAME;
  }

  @Override
  public String getToolDescription() {
    return "Captures and analyzes JVM thread stack traces for debugging and performance analysis. "
        + "Supports filtering by thread name patterns (with wildcards), module-based filtering for modular applications, "
        + "and stack trace trimming to focus on specific code modules. Useful for identifying thread activity, debugging deadlocks, "
        + "analyzing performance bottlenecks, and understanding application behavior at runtime.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return createToolSchema();
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Parameters params = extractParameters(arguments);
        String result = inspector.captureThreadStacks(params.whitelistFilters(), params.includeSelf(),
            params.moduleFilter(), params.trimToModule());
        return ToolResponse.success(result);
      } catch (IllegalArgumentException e) {
        return ToolResponse.validationError(e.getMessage());
      } catch (Exception e) {
        return ToolResponse.executionFailed("Process inspection failed: " + e.getMessage());
      }
    });
  }

  /**
   * Extracts and validates parameters from the arguments map.
   */
  private Parameters extractParameters(Map<String, Object> arguments) {
    List<String> whitelistFilters = extractListParameter(arguments, "whitelistFilters");
    boolean includeSelf = extractBooleanParameter(arguments, "includeSelf", false);
    String moduleFilter = extractStringParameter(arguments, "moduleFilter");
    boolean trimToModule = extractBooleanParameter(arguments, "trimToModule", false);

    return new Parameters(whitelistFilters, includeSelf, moduleFilter, trimToModule);
  }

  /**
   * Extracts a list parameter from the arguments map.
   */
  @SuppressWarnings("unchecked")
  private List<String> extractListParameter(Map<String, Object> arguments, String key) {
    return Optional.ofNullable(arguments.get(key)).filter(List.class::isInstance).map(List.class::cast).orElse(null);
  }

  /**
   * Extracts a boolean parameter from the arguments map with a default value.
   */
  private boolean extractBooleanParameter(Map<String, Object> arguments, String key, boolean defaultValue) {
    return Optional.ofNullable(arguments.get(key)).filter(Boolean.class::isInstance).map(Boolean.class::cast)
        .orElse(defaultValue);
  }

  /**
   * Extracts a string parameter from the arguments map.
   */
  private String extractStringParameter(Map<String, Object> arguments, String key) {
    return Optional.ofNullable(arguments.get(key)).filter(String.class::isInstance).map(String.class::cast)
        .orElse(null);
  }

  /**
   * Creates the JSON schema for tool parameters.
   */
  private static Map<String, Object> createToolSchema() {
    Map<String, Object> properties = Map.of( //
        "whitelistFilters", createArrayProperty(
            "Glob-style filter patterns for thread stack traces. Use '*' as wildcard (e.g., 'com.bitsapplied.*'). "
                + "Only threads with at least one matching frame are included. Helps focus on application code.",
            "string"), //
        "includeSelf", createBooleanProperty(
            "Include the MCP tool's own thread in the stack trace report. Usually false to avoid noise.", false), //
        "moduleFilter",
        createStringProperty(
            "Java module name for filtering threads in modular applications (case-insensitive). Use 'app' for unnamed module."),
        "trimToModule",
        createBooleanProperty(
            "When true and moduleFilter is set, trim stack traces to only show frames from the specified module.",
            false));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("description",
        "Capture and filter JVM thread stack traces. Apply filters to avoid very large outputs (potentially hundreds of KB).");
    return schema;
  }

  private static Map<String, Object> createArrayProperty(String description, String itemType) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", itemType));
  }

  private static Map<String, Object> createBooleanProperty(String description, boolean defaultValue) {
    return Map.of("type", "boolean", "description", description, "default", defaultValue);
  }

  private static Map<String, Object> createStringProperty(String description) {
    return Map.of("type", "string", "description", description);
  }

  /**
   * Immutable record holding the tool parameters.
   */
  private record Parameters(List<String> whitelistFilters, boolean includeSelf, String moduleFilter,
      boolean trimToModule) {
  }
}
