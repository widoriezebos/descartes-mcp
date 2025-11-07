package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import com.bitsapplied.descartes.hotreload.HotReloadResult;
import com.bitsapplied.descartes.hotreload.HotReloadService;
import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;
import com.bitsapplied.descartes.util.ToolExecutors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Tool for hot reloading Java classes at runtime using the Java
 * Instrumentation API. This tool allows redefining classes in a running JVM
 * without restarting the application.
 * 
 * IMPORTANT: Requires the JVM to be started with the Descartes JAR as an agent:
 * -javaagent:path/to/descartes-mcp-jar-with-dependencies.jar
 * 
 * Limitations: - Cannot change method signatures - Cannot add/remove fields -
 * Cannot change class hierarchy - Cannot change interfaces
 * 
 * @author Descartes MCP
 */
public class HotClassReloadTool implements MCPTool {

  private static final Logger LOGGER = Logger.getLogger(HotClassReloadTool.class.getName());
  private static final ObjectMapper mapper = new ObjectMapper();

  private final HotReloadService hotReloadService;
  private final ExecutorService executor;

  public HotClassReloadTool(Map<String, Object> context) {
    this.hotReloadService = new HotReloadService(context);
    this.executor = ToolExecutors.getSharedExecutor(context);
  }

  @Override
  public String getToolName() {
    return "hot_reload_classes";
  }

  @Override
  public String getToolDescription() {
    return "Hot reload Java classes at runtime. Requires JVM agent to be installed. "
        + "Can reload classes matching a filter pattern without restarting the application.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    Map<String, Object> properties = new HashMap<>();

    // Package filter parameter
    Map<String, Object> filterParam = new HashMap<>();
    filterParam.put("type", "string");
    filterParam.put("description",
        "Package filter pattern (e.g., 'com.example.*' for all classes in package and subpackages)");
    properties.put("packageFilter", filterParam);

    // Force reload parameter
    Map<String, Object> forceParam = new HashMap<>();
    forceParam.put("type", "boolean");
    forceParam.put("description", "Force reload even if no changes detected (default: false)");
    properties.put("force", forceParam);

    // Validate only parameter
    Map<String, Object> validateParam = new HashMap<>();
    validateParam.put("type", "boolean");
    validateParam.put("description", "Only validate if reload is possible without actually reloading (default: false)");
    properties.put("validateOnly", validateParam);

    schema.put("properties", properties);
    schema.put("required", new String[] { "packageFilter" });
    schema.put("description",
        "Hot reload Java classes via instrumentation agent. Requires JVM started with Descartes agent (-javaagent).");

    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (!HotReloadAgent.isAgentLoaded()) {
          return ToolResponse.preconditionFailed(
              "Hot reload agent not loaded. Start JVM with -javaagent:path/to/descartes-mcp-jar-with-dependencies.jar");
        }

        if (arguments == null) {
          return ToolResponse.validationError("Arguments are required for hot reload");
        }

        String packageFilter = optString(arguments.get("packageFilter"));
        if (packageFilter == null || packageFilter.isBlank()) {
          return ToolResponse.missingParameter("packageFilter");
        }

        boolean force = toBoolean(arguments.get("force"), false);
        boolean validateOnly = toBoolean(arguments.get("validateOnly"), false);

        HotReloadResult reloadResult = validateOnly ? hotReloadService.validateReload(packageFilter)
            : hotReloadService.reloadClasses(packageFilter, force);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", reloadResult.isSuccess() ? "success" : "failed");
        result.put("classesAnalyzed", reloadResult.getClassesAnalyzed());
        result.put("classesChanged", reloadResult.getClassesChanged());
        result.put("classesReloaded", reloadResult.getClassesReloaded());
        result.put("reloadTimeMs", reloadResult.getReloadTimeMs());

        if (!reloadResult.getReloadedClassNames().isEmpty()) {
          ArrayNode classesArray = result.putArray("reloadedClasses");
          for (String className : reloadResult.getReloadedClassNames()) {
            classesArray.add(className);
          }
        }

        if (!reloadResult.getSkippedClasses().isEmpty()) {
          ObjectNode skipped = mapper.createObjectNode();
          reloadResult.getSkippedClasses().forEach(skipped::put);
          result.set("skippedClasses", skipped);
        }

        if (reloadResult.isSuccess()) {
          if (validateOnly) {
            result.put("message", "Validation successful. Classes can be safely reloaded.");
          } else {
            result.put("message", String.format("Successfully reloaded %d classes", reloadResult.getClassesReloaded()));
          }

          LOGGER.info(String.format("Hot reload succeeded: %d classes analyzed, %d changed, %d reloaded",
              reloadResult.getClassesAnalyzed(), reloadResult.getClassesChanged(), reloadResult.getClassesReloaded()));

          Map<String, Object> response = mapper.convertValue(result, new TypeReference<Map<String, Object>>() {
          });
          return ToolResponse.successJson(response);
        }

        if (!reloadResult.getDetailedErrors().isEmpty()) {
          ArrayNode errorsArray = result.putArray("errors");
          for (String error : reloadResult.getDetailedErrors()) {
            errorsArray.add(error);
          }
        }
        result.put("error", reloadResult.getErrorMessage());

        LOGGER.warning(String.format("Hot reload failed: %s", reloadResult.getErrorMessage()));

        // In validation mode or force mode, return structured response with
        // status="failed"
        // In normal execution mode, return error response for failures
        if (validateOnly || force) {
          Map<String, Object> response = mapper.convertValue(result, new TypeReference<Map<String, Object>>() {
          });
          return ToolResponse.successJson(response);
        } else {
          return ToolResponse.executionFailed(reloadResult.getErrorMessage());
        }
      } catch (Exception e) {
        LOGGER.severe("Hot reload failed: " + e.getMessage());
        return ToolResponse.internalError("Hot reload failed: " + e.getMessage(), e);
      }
    }, executor);
  }

  @Override
  public void close() {
    // Shared executor lifecycle is managed centrally by the MCP server.
  }

  private static String optString(Object value) {
    return value instanceof String str ? str : null;
  }

  private static boolean toBoolean(Object value, boolean defaultValue) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String str) {
      return Boolean.parseBoolean(str);
    }
    return defaultValue;
  }
}
