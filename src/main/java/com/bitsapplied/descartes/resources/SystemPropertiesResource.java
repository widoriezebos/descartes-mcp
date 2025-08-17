package com.bitsapplied.descartes.resources;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Resource that provides information about system properties, environment
 * variables, and JVM configuration.
 */
public class SystemPropertiesResource implements MCPResourceHandler {
  private static final ObjectMapper mapper = new ObjectMapper();

  // Sensitive property patterns to filter out by default
  private static final Set<String> SENSITIVE_PATTERNS = Set.of("password", "secret", "token", "key", "credential",
      "auth");

  @Override
  public String getUriPath() {
    return "system/properties";
  }

  @Override
  public String getName() {
    return "System Properties";
  }

  @Override
  public String getDescription() {
    return "System configuration inspector providing access to JVM system properties, environment variables, and runtime settings. "
        + "Reveals Java version, classpath, OS details, memory settings, and application-specific properties. "
        + "Automatically filters sensitive values (passwords, tokens, keys) for security. "
        + "Parameters: 'type' (all/properties/environment/runtime), 'include_sensitive' (show filtered values), "
        + "'filter' (property name pattern). Essential for debugging configuration issues and verifying deployment settings.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String type = queryParams.get("type", "all");
      boolean includeSensitive = getBooleanParam(queryParams, "includeSensitive", false);
      String filter = queryParams.get("filter", "");

      switch (type) {
      case "all":
        return getAllProperties(includeSensitive, filter);
      case "system":
        return getSystemProperties(includeSensitive, filter);
      case "environment":
        return getEnvironmentVariables(includeSensitive, filter);
      case "runtime":
        return getRuntimeInfo();
      case "jvm":
        return getJvmArguments();
      default:
        throw new MCPResource.ResourceException("Unknown type: " + type);
      }
    } catch (MCPResource.ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new MCPResource.ResourceException("Error handling system properties request", e);
    }
  }

  private String getAllProperties(boolean includeSensitive, String filter) throws Exception {
    ObjectNode result = mapper.createObjectNode();

    // Add system properties
    result.set("systemProperties", getSystemPropertiesNode(includeSensitive, filter));

    // Add environment variables
    result.set("environmentVariables", getEnvironmentVariablesNode(includeSensitive, filter));

    // Add runtime info
    result.set("runtime", getRuntimeInfoNode());

    // Add JVM arguments
    result.set("jvmArguments", getJvmArgumentsNode());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getSystemProperties(boolean includeSensitive, String filter) throws Exception {
    ObjectNode result = getSystemPropertiesNode(includeSensitive, filter);
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getSystemPropertiesNode(boolean includeSensitive, String filter) {
    ObjectNode result = mapper.createObjectNode();
    Properties props = System.getProperties();

    // Group properties by category
    Map<String, List<Map.Entry<Object, Object>>> grouped = props.entrySet().stream()
        .filter(e -> matchesFilter(String.valueOf(e.getKey()), filter))
        .filter(e -> includeSensitive || !isSensitive(String.valueOf(e.getKey()))).collect(Collectors.groupingBy(e -> {
          String key = String.valueOf(e.getKey());
          if (key.startsWith("java."))
            return "java";
          if (key.startsWith("os."))
            return "os";
          if (key.startsWith("user."))
            return "user";
          if (key.startsWith("file."))
            return "file";
          if (key.startsWith("path."))
            return "path";
          if (key.startsWith("sun."))
            return "sun";
          return "other";
        }));

    for (Map.Entry<String, List<Map.Entry<Object, Object>>> group : grouped.entrySet()) {
      ObjectNode categoryNode = result.putObject(group.getKey());
      for (Map.Entry<Object, Object> prop : group.getValue()) {
        String key = String.valueOf(prop.getKey());
        String value = String.valueOf(prop.getValue());

        // Mask sensitive values if not included
        if (!includeSensitive && isSensitive(key)) {
          value = "***FILTERED***";
        }

        categoryNode.put(key, value);
      }
    }

    result.put("totalCount", props.size());
    result.put("filteredCount", grouped.values().stream().mapToInt(List::size).sum());

    return result;
  }

  private String getEnvironmentVariables(boolean includeSensitive, String filter) throws Exception {
    ObjectNode result = getEnvironmentVariablesNode(includeSensitive, filter);
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getEnvironmentVariablesNode(boolean includeSensitive, String filter) {
    ObjectNode result = mapper.createObjectNode();
    Map<String, String> env = System.getenv();

    ObjectNode envNode = result.putObject("variables");
    int count = 0;

    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      if (matchesFilter(key, filter) && (includeSensitive || !isSensitive(key))) {
        String value = entry.getValue();

        // Mask sensitive values if not included
        if (!includeSensitive && isSensitive(key)) {
          value = "***FILTERED***";
        }

        envNode.put(key, value);
        count++;
      }
    }

    result.put("totalCount", env.size());
    result.put("filteredCount", count);

    return result;
  }

  private String getRuntimeInfo() throws Exception {
    ObjectNode result = getRuntimeInfoNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getRuntimeInfoNode() {
    ObjectNode result = mapper.createObjectNode();
    RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    result.put("name", runtimeBean.getName());
    result.put("vmName", runtimeBean.getVmName());
    result.put("vmVendor", runtimeBean.getVmVendor());
    result.put("vmVersion", runtimeBean.getVmVersion());
    result.put("specName", runtimeBean.getSpecName());
    result.put("specVendor", runtimeBean.getSpecVendor());
    result.put("specVersion", runtimeBean.getSpecVersion());
    result.put("managementSpecVersion", runtimeBean.getManagementSpecVersion());
    result.put("classPath", runtimeBean.getClassPath());
    result.put("libraryPath", runtimeBean.getLibraryPath());
    result.put("bootClassPath",
        runtimeBean.isBootClassPathSupported() ? runtimeBean.getBootClassPath() : "Not supported");
    result.put("startTime", runtimeBean.getStartTime());
    result.put("uptime", runtimeBean.getUptime());

    return result;
  }

  private String getJvmArguments() throws Exception {
    ObjectNode result = getJvmArgumentsNode();
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode getJvmArgumentsNode() {
    ObjectNode result = mapper.createObjectNode();
    RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    ArrayNode inputArgs = result.putArray("inputArguments");
    for (String arg : runtimeBean.getInputArguments()) {
      inputArgs.add(arg);
    }

    // Categorize arguments
    ArrayNode memoryArgs = result.putArray("memoryArguments");
    ArrayNode gcArgs = result.putArray("gcArguments");
    ArrayNode debugArgs = result.putArray("debugArguments");
    ArrayNode systemProps = result.putArray("systemPropertyArguments");
    ArrayNode otherArgs = result.putArray("otherArguments");

    for (String arg : runtimeBean.getInputArguments()) {
      if (arg.startsWith("-Xmx") || arg.startsWith("-Xms") || arg.startsWith("-XX:MaxMetaspace")
          || arg.startsWith("-XX:MaxDirectMemory")) {
        memoryArgs.add(arg);
      } else if (arg.contains("GC") || arg.contains("Garbage")) {
        gcArgs.add(arg);
      } else if (arg.contains("debug") || arg.contains("Debug") || arg.startsWith("-agentlib")) {
        debugArgs.add(arg);
      } else if (arg.startsWith("-D")) {
        systemProps.add(arg);
      } else {
        otherArgs.add(arg);
      }
    }

    result.put("totalCount", runtimeBean.getInputArguments().size());

    return result;
  }

  private boolean isSensitive(String key) {
    String lowerKey = key.toLowerCase();
    return SENSITIVE_PATTERNS.stream().anyMatch(lowerKey::contains);
  }

  private boolean matchesFilter(String key, String filter) {
    if (filter == null || filter.isEmpty()) {
      return true;
    }
    return key.toLowerCase().contains(filter.toLowerCase());
  }

  private boolean getBooleanParam(QueryParams params, String key, boolean defaultValue) {
    String value = params.get(key);
    if (value != null) {
      return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
    return defaultValue;
  }
}