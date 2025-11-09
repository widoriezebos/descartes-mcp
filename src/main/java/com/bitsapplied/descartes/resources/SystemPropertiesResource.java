package com.bitsapplied.descartes.resources;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.security.SensitiveDataFilter;
import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Resource that provides information about system properties, environment
 * variables, and JVM configuration with comprehensive security controls.
 *
 * <p>
 * <b>Security Model:</b> This resource uses SystemPropertiesSecurityConfig to
 * control access to sensitive system information. By default, sensitive data
 * access is DISABLED (restrictive by default).
 *
 * <p>
 * <b>Configuration:</b> Use factory methods for preset configurations:
 * <ul>
 * <li>{@link SystemPropertiesSecurityConfig#forDevelopment()} - Permissive for
 * local dev</li>
 * <li>{@link SystemPropertiesSecurityConfig#forProduction()} - Restrictive for
 * production</li>
 * <li>{@link SystemPropertiesSecurityConfig#forTesting()} - Balanced for
 * testing</li>
 * </ul>
 *
 * <p>
 * <b>Breaking Change:</b> The default constructor now uses restrictive
 * production settings. For old behavior, explicitly use forDevelopment()
 * configuration.
 */
public class SystemPropertiesResource implements MCPResourceHandler {
  private static final Logger logger = LogManager.getLogger(SystemPropertiesResource.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  private final SystemPropertiesSecurityConfig securityConfig;
  private final SensitiveDataFilter filter;

  /**
   * Creates a SystemPropertiesResource with default production security settings.
   * Uses restrictive defaults: sensitive access disabled, audit logging enabled.
   *
   * <p>
   * <b>Breaking Change:</b> This is now secure by default. For permissive
   * development behavior, use
   * {@code new SystemPropertiesResource(SystemPropertiesSecurityConfig.forDevelopment())}.
   */
  public SystemPropertiesResource() {
    this(SystemPropertiesSecurityConfig.forProduction());
  }

  /**
   * Creates a SystemPropertiesResource with custom security configuration.
   *
   * @param securityConfig The security configuration to use
   */
  public SystemPropertiesResource(SystemPropertiesSecurityConfig securityConfig) {
    if (securityConfig == null) {
      throw new IllegalArgumentException("Security configuration cannot be null");
    }
    this.securityConfig = securityConfig;
    this.filter = securityConfig.getFilter();
    logger.info("SystemPropertiesResource initialized with security config: " + "allowSensitive="
        + securityConfig.isAllowSensitiveAccess() + ", " + "strictMode=" + securityConfig.isStrictMode() + ", "
        + "auditLogging=" + securityConfig.isAuditLogging());
  }

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
        + "Security enforced via SystemPropertiesSecurityConfig with sensitive data filtering, allowlist/denylist support, and audit logging. "
        + "Parameters: 'type' (all/system/environment/runtime/jvm), 'includeSensitive' (requires security config permission), "
        + "'filter' (property name pattern). "
        + "SECURITY NOTE: Sensitive access is disabled by default. Use SystemPropertiesSecurityConfig.forDevelopment() for permissive mode.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String type = queryParams.get("type", "all");
      boolean includeSensitiveRequested = getBooleanParam(queryParams, "includeSensitive", false);
      String filterPattern = queryParams.get("filter", "");

      // SECURITY CHECK: Enforce includeSensitive permission
      boolean includeSensitive = false;
      if (includeSensitiveRequested) {
        if (!securityConfig.isAllowSensitiveAccess()) {
          logger.warn("SECURITY: includeSensitive requested but denied by security config");
          if (securityConfig.isAuditLogging()) {
            filter.auditAccess("includeSensitive parameter", "denied_request");
          }
          throw new MCPResource.ResourceException(
              "Access to sensitive properties is disabled by security configuration. "
                  + "Use SystemPropertiesSecurityConfig.forDevelopment() to enable for development environments.");
        }
        // Audit when sensitive access is granted
        if (securityConfig.isAuditLogging()) {
          logger.warn("SECURITY AUDIT: Sensitive property access granted for type=" + type);
        }
        includeSensitive = true;
      }

      switch (type) {
      case "all":
        return getAllProperties(includeSensitive, filterPattern);
      case "system":
        return getSystemProperties(includeSensitive, filterPattern);
      case "environment":
        return getEnvironmentVariables(includeSensitive, filterPattern);
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

  private ObjectNode getSystemPropertiesNode(boolean includeSensitive, String filterPattern) {
    ObjectNode result = mapper.createObjectNode();
    Properties props = System.getProperties();

    // Group properties by category with security filtering
    Map<String, List<Map.Entry<Object, Object>>> grouped = props.entrySet().stream()
        .filter(e -> matchesFilterPattern(String.valueOf(e.getKey()), filterPattern))
        .filter(e -> includeSensitive || filter.isAllowed(String.valueOf(e.getKey())))
        .collect(Collectors.groupingBy(e -> {
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
        if (!includeSensitive && filter.isSensitive(key)) {
          value = "***FILTERED***";
          if (securityConfig.isAuditLogging()) {
            filter.auditAccess(key, "system_property_filtered");
          }
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

  private ObjectNode getEnvironmentVariablesNode(boolean includeSensitive, String filterPattern) {
    ObjectNode result = mapper.createObjectNode();
    Map<String, String> env = System.getenv();

    ObjectNode envNode = result.putObject("variables");
    int count = 0;

    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      if (matchesFilterPattern(key, filterPattern) && (includeSensitive || filter.isAllowed(key))) {
        String value = entry.getValue();

        // Mask sensitive values if not included
        if (!includeSensitive && filter.isSensitive(key)) {
          value = "***FILTERED***";
          if (securityConfig.isAuditLogging()) {
            filter.auditAccess(key, "environment_variable_filtered");
          }
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

  /**
   * Checks if a key matches the user-provided filter pattern. Simple substring
   * matching (case-insensitive).
   */
  private boolean matchesFilterPattern(String key, String filterPattern) {
    if (filterPattern == null || filterPattern.isEmpty()) {
      return true;
    }
    return key.toLowerCase().contains(filterPattern.toLowerCase());
  }

  private boolean getBooleanParam(QueryParams params, String key, boolean defaultValue) {
    String value = params.get(key);
    if (value != null) {
      return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
    return defaultValue;
  }
}