package com.bitsapplied.descartes.tools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.tools.logging.support.LogFileDiscoveryService;
import com.bitsapplied.descartes.tools.logging.support.LogFileInfo;
import com.bitsapplied.descartes.tools.logging.support.RolledFileResolver;

/**
 * MCP tool for discovering log files from Log4j2 appenders.
 *
 * Operations: - list: List all discovered log files with metadata - appenders:
 * Show Log4j2 appender configurations - discover: Discover rolled files for a
 * specific file pattern
 */
public class LogFileDiscoveryTool implements MCPTool {

  private final LogFileDiscoveryService discoveryService;

  public LogFileDiscoveryTool() {
    this.discoveryService = new LogFileDiscoveryService();
  }

  @Override
  public String getToolName() {
    return "log_file_discovery";
  }

  @Override
  public String getToolDescription() {
    return "Discovers log files from Log4j2 appender configuration. "
        + "Lists active log files, rolling file patterns, and discovers archived/rolled log files. "
        + "Operations: 'list' shows all log files, 'appenders' shows appender configs, "
        + "'discover' finds rolled files for a pattern.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");

    Map<String, Object> properties = new HashMap<>();

    // operation property
    Map<String, Object> operation = new HashMap<>();
    operation.put("type", "string");
    operation.put("description", "Operation to perform");
    operation.put("enum", List.of("list", "appenders", "discover"));
    properties.put("operation", operation);

    // file_pattern property (optional, for discover operation)
    Map<String, Object> filePattern = new HashMap<>();
    filePattern.put("type", "string");
    filePattern.put("description", "Log4j2 file pattern for rolled file discovery");
    properties.put("file_pattern", filePattern);

    schema.put("properties", properties);
    schema.put("required", List.of("operation"));

    return schema;
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        String operation = (String) arguments.get("operation");
        if (operation == null) {
          return ToolResponse.error(ToolErrorCode.INVALID_PARAMETER_VALUE, "operation parameter is required");
        }

        return switch (operation.toLowerCase()) {
        case "list" -> executeList();
        case "appenders" -> executeAppenders();
        case "discover" -> executeDiscover(arguments);
        default -> ToolResponse.error(ToolErrorCode.INVALID_PARAMETER_VALUE, "Unknown operation: " + operation);
        };
      } catch (Exception e) {
        return ToolResponse.error(ToolErrorCode.INVALID_PARAMETER_VALUE, "Tool execution failed: " + e.getMessage());
      }
    });
  }

  private ToolResponse executeList() {
    List<LogFileInfo> logFiles = discoveryService.discoverLogFiles();

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("count", logFiles.size());
    result.put("log_files", logFiles.stream().map(this::logFileInfoToMap).toList());

    return ToolResponse.successJson(result);
  }

  private ToolResponse executeAppenders() {
    if (!discoveryService.isLog4j2Available()) {
      return ToolResponse.error(ToolErrorCode.INVALID_PARAMETER_VALUE, "Log4j2 is not configured or available");
    }

    List<LogFileInfo> logFiles = discoveryService.discoverLogFiles();

    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("appender_count", logFiles.size());
    result.put("appenders",
        logFiles.stream()
            .map(info -> Map.of("name", info.appenderName(), "type", info.type(), "file_path", info.filePath(),
                "file_pattern", info.filePattern() != null ? info.filePattern() : "N/A", "readable", info.readable()))
            .toList());

    return ToolResponse.successJson(result);
  }

  private ToolResponse executeDiscover(Map<String, Object> arguments) {
    String filePattern = (String) arguments.get("file_pattern");
    if (filePattern == null || filePattern.isBlank()) {
      return ToolResponse.error(ToolErrorCode.INVALID_PARAMETER_VALUE,
          "file_pattern parameter is required for discover operation");
    }

    try {
      List<Path> rolledFiles = RolledFileResolver.discoverRolledFiles(filePattern);

      Map<String, Object> result = new HashMap<>();
      result.put("status", "success");
      result.put("pattern", filePattern);
      result.put("count", rolledFiles.size());
      result.put("files", rolledFiles.stream().map(path -> path.toString()).toList());

      return ToolResponse.successJson(result);
    } catch (Exception e) {
      return ToolResponse.error(ToolErrorCode.INVALID_PARAMETER_VALUE,
          "Failed to discover rolled files: " + e.getMessage());
    }
  }

  private Map<String, Object> logFileInfoToMap(LogFileInfo info) {
    Map<String, Object> map = new HashMap<>();
    map.put("appender_name", info.appenderName());
    map.put("type", info.type());
    map.put("file_path", info.filePath());
    map.put("readable", info.readable());

    if (info.sizeBytes() != null) {
      map.put("size_bytes", info.sizeBytes());
    }
    if (info.lastModified() != null) {
      map.put("last_modified", info.lastModified().toString());
    }
    if (info.filePattern() != null) {
      map.put("file_pattern", info.filePattern());
    }
    if (!info.rolledFiles().isEmpty()) {
      map.put("rolled_files_count", info.rolledFiles().size());
      map.put("rolled_files", info.rolledFiles().stream().map(path -> path.toString()).toList());
    }
    if (info.hasError()) {
      map.put("error", info.error());
    }

    return map;
  }
}
