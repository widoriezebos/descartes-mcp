package com.bitsapplied.descartes.debugger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads RemoteDebugProxyConfig from multiple sources with priority: CLI args >
 * config file > environment variables > defaults
 *
 * <p>
 * Supports:
 * <ul>
 * <li>Command-line arguments: --jdwp-host, --jdwp-port, --mcp-port,
 * --auto-discover, --process-pattern, etc.
 * <li>JSON config file: proxy-config.json
 * <li>Environment variables: DESCARTES_JDWP_HOST, DESCARTES_JDWP_PORT,
 * DESCARTES_AUTO_DISCOVER, etc.
 * <li>Built-in defaults: localhost:5005, MCP port 9090
 * <li>Auto-discovery: Automatically find and connect to JDWP processes by
 * pattern
 * </ul>
 */
public class ConfigLoader {

  private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Loads configuration from command-line arguments.
   *
   * <p>
   * Supported arguments:
   * <ul>
   * <li>--jdwp-host &lt;host&gt;
   * <li>--jdwp-port &lt;port&gt;
   * <li>--jdwp-timeout &lt;ms&gt;
   * <li>--mcp-port &lt;port&gt;
   * <li>--log-level &lt;ERROR|INFO|DEBUG&gt;
   * <li>--reconnect &lt;true|false&gt;
   * <li>--reconnect-interval &lt;ms&gt;
   * <li>--health-check-interval &lt;ms&gt;
   * <li>--config &lt;file-path&gt;
   * <li>--auto-discover (enable auto-discovery)
   * <li>--process-pattern &lt;pattern&gt; (pattern to match process name)
   * </ul>
   *
   * @param args command-line arguments
   * @return configuration loaded with priority: CLI > file > env > defaults
   */
  public static RemoteDebugProxyConfig load(String[] args) {
    // Start with defaults
    RemoteDebugProxyConfig.Builder builder = RemoteDebugProxyConfig.builder();

    // Parse CLI args first to get config file path
    String configFilePath = parseConfigFilePath(args);

    // Layer 1: Environment variables (lowest priority after defaults)
    applyEnvironmentVariables(builder);

    // Layer 2: Config file (if specified)
    if (configFilePath != null) {
      builder.configFilePath(configFilePath);
      applyConfigFile(builder, configFilePath);
    }

    // Layer 3: CLI arguments (highest priority)
    applyCommandLineArgs(builder, args);

    // Layer 4: Auto-discovery (if enabled and no explicit port)
    applyAutoDiscovery(builder, args);

    RemoteDebugProxyConfig config = builder.build();
    logger.info("Loaded configuration: {}", config);

    return config;
  }

  /**
   * Parses config file path from CLI arguments.
   */
  private static String parseConfigFilePath(String[] args) {
    for (int i = 0; i < args.length - 1; i++) {
      if ("--config".equals(args[i])) {
        return args[i + 1];
      }
    }
    return null;
  }

  /**
   * Applies environment variables to builder.
   *
   * <p>
   * Environment variable mapping:
   * <ul>
   * <li>DESCARTES_JDWP_HOST → jdwpHost
   * <li>DESCARTES_JDWP_PORT → jdwpPort
   * <li>DESCARTES_JDWP_TIMEOUT → jdwpTimeout
   * <li>DESCARTES_MCP_PORT → mcpPort
   * <li>DESCARTES_LOG_LEVEL → logLevel
   * <li>DESCARTES_RECONNECT → reconnectEnabled
   * <li>DESCARTES_RECONNECT_INTERVAL → reconnectIntervalMs
   * <li>DESCARTES_HEALTH_CHECK_INTERVAL → healthCheckIntervalMs
   * <li>DESCARTES_CONFIG_FILE → configFilePath
   * <li>DESCARTES_AUTO_DISCOVER → autoDiscover
   * <li>DESCARTES_PROCESS_PATTERN → processPattern
   * </ul>
   */
  private static void applyEnvironmentVariables(RemoteDebugProxyConfig.Builder builder) {
    Map<String, String> env = System.getenv();

    if (env.containsKey("DESCARTES_JDWP_HOST")) {
      String value = env.get("DESCARTES_JDWP_HOST");
      logger.debug("Applying env DESCARTES_JDWP_HOST: {}", value);
      builder.jdwpHost(value);
    }

    if (env.containsKey("DESCARTES_JDWP_PORT")) {
      String value = env.get("DESCARTES_JDWP_PORT");
      logger.debug("Applying env DESCARTES_JDWP_PORT: {}", value);
      builder.jdwpPort(Integer.parseInt(value));
    }

    if (env.containsKey("DESCARTES_JDWP_TIMEOUT")) {
      String value = env.get("DESCARTES_JDWP_TIMEOUT");
      logger.debug("Applying env DESCARTES_JDWP_TIMEOUT: {}", value);
      builder.jdwpTimeout(Integer.parseInt(value));
    }

    if (env.containsKey("DESCARTES_MCP_PORT")) {
      String value = env.get("DESCARTES_MCP_PORT");
      logger.debug("Applying env DESCARTES_MCP_PORT: {}", value);
      builder.mcpPort(Integer.parseInt(value));
    }

    if (env.containsKey("DESCARTES_LOG_LEVEL")) {
      String value = env.get("DESCARTES_LOG_LEVEL");
      logger.debug("Applying env DESCARTES_LOG_LEVEL: {}", value);
      builder.logLevel(ProxyLogLevel.parse(value));
    }

    if (env.containsKey("DESCARTES_RECONNECT")) {
      String value = env.get("DESCARTES_RECONNECT");
      logger.debug("Applying env DESCARTES_RECONNECT: {}", value);
      builder.reconnectEnabled(Boolean.parseBoolean(value));
    }

    if (env.containsKey("DESCARTES_RECONNECT_INTERVAL")) {
      String value = env.get("DESCARTES_RECONNECT_INTERVAL");
      logger.debug("Applying env DESCARTES_RECONNECT_INTERVAL: {}", value);
      builder.reconnectIntervalMs(Integer.parseInt(value));
    }

    if (env.containsKey("DESCARTES_HEALTH_CHECK_INTERVAL")) {
      String value = env.get("DESCARTES_HEALTH_CHECK_INTERVAL");
      logger.debug("Applying env DESCARTES_HEALTH_CHECK_INTERVAL: {}", value);
      builder.healthCheckIntervalMs(Integer.parseInt(value));
    }

    if (env.containsKey("DESCARTES_AUTO_DISCOVER")) {
      String value = env.get("DESCARTES_AUTO_DISCOVER");
      logger.debug("Applying env DESCARTES_AUTO_DISCOVER: {}", value);
      builder.autoDiscover(Boolean.parseBoolean(value));
    }

    if (env.containsKey("DESCARTES_PROCESS_PATTERN")) {
      String value = env.get("DESCARTES_PROCESS_PATTERN");
      logger.debug("Applying env DESCARTES_PROCESS_PATTERN: {}", value);
      builder.processPattern(value);
    }
  }

  /**
   * Applies configuration from JSON file.
   *
   * <p>
   * Expected JSON structure:
   *
   * <pre>
   * {
   *   "jdwpHost": "localhost",
   *   "jdwpPort": 5005,
   *   "jdwpTimeout": 5000,
   *   "mcpPort": 9090,
   *   "logLevel": "INFO",
   *   "reconnectEnabled": true,
   *   "reconnectIntervalMs": 5000,
   *   "healthCheckIntervalMs": 30000,
   *   "autoDiscover": true,
   *   "processPattern": "morpheus"
   * }
   * </pre>
   */
  private static void applyConfigFile(RemoteDebugProxyConfig.Builder builder, String filePath) {
    File configFile = new File(filePath);

    if (!configFile.exists()) {
      logger.warn("Config file not found: {}", filePath);
      return;
    }

    if (!configFile.canRead()) {
      logger.warn("Config file not readable: {}", filePath);
      return;
    }

    try {
      logger.info("Loading config from file: {}", filePath);
      JsonNode root = objectMapper.readTree(configFile);

      if (root.has("jdwpHost")) {
        String value = root.get("jdwpHost").asText();
        logger.debug("Applying file jdwpHost: {}", value);
        builder.jdwpHost(value);
      }

      if (root.has("jdwpPort")) {
        int value = root.get("jdwpPort").asInt();
        logger.debug("Applying file jdwpPort: {}", value);
        builder.jdwpPort(value);
      }

      if (root.has("jdwpTimeout")) {
        int value = root.get("jdwpTimeout").asInt();
        logger.debug("Applying file jdwpTimeout: {}", value);
        builder.jdwpTimeout(value);
      }

      if (root.has("mcpPort")) {
        int value = root.get("mcpPort").asInt();
        logger.debug("Applying file mcpPort: {}", value);
        builder.mcpPort(value);
      }

      if (root.has("logLevel")) {
        String value = root.get("logLevel").asText();
        logger.debug("Applying file logLevel: {}", value);
        builder.logLevel(ProxyLogLevel.parse(value));
      }

      if (root.has("reconnectEnabled")) {
        boolean value = root.get("reconnectEnabled").asBoolean();
        logger.debug("Applying file reconnectEnabled: {}", value);
        builder.reconnectEnabled(value);
      }

      if (root.has("reconnectIntervalMs")) {
        int value = root.get("reconnectIntervalMs").asInt();
        logger.debug("Applying file reconnectIntervalMs: {}", value);
        builder.reconnectIntervalMs(value);
      }

      if (root.has("healthCheckIntervalMs")) {
        int value = root.get("healthCheckIntervalMs").asInt();
        logger.debug("Applying file healthCheckIntervalMs: {}", value);
        builder.healthCheckIntervalMs(value);
      }

      if (root.has("autoDiscover")) {
        boolean value = root.get("autoDiscover").asBoolean();
        logger.debug("Applying file autoDiscover: {}", value);
        builder.autoDiscover(value);
      }

      if (root.has("processPattern")) {
        String value = root.get("processPattern").asText();
        logger.debug("Applying file processPattern: {}", value);
        builder.processPattern(value);
      }

      logger.info("Successfully loaded config from file: {}", filePath);

    } catch (IOException e) {
      logger.error("Failed to parse config file: {}", filePath, e);
      throw new IllegalArgumentException("Invalid config file: " + filePath, e);
    }
  }

  /**
   * Applies command-line arguments to builder (highest priority).
   *
   * <p>
   * Supported arguments: --jdwp-host, --jdwp-port, --jdwp-timeout, --mcp-port,
   * --log-level, --reconnect, --reconnect-interval, --health-check-interval,
   * --auto-discover, --process-pattern
   */
  private static void applyCommandLineArgs(RemoteDebugProxyConfig.Builder builder, String[] args) {
    Map<String, String> argMap = parseCommandLineArgs(args);

    if (argMap.containsKey("jdwp-host")) {
      String value = argMap.get("jdwp-host");
      logger.debug("Applying CLI --jdwp-host: {}", value);
      builder.jdwpHost(value);
    }

    if (argMap.containsKey("jdwp-port")) {
      int value = Integer.parseInt(argMap.get("jdwp-port"));
      logger.debug("Applying CLI --jdwp-port: {}", value);
      builder.jdwpPort(value);
    }

    if (argMap.containsKey("jdwp-timeout")) {
      int value = Integer.parseInt(argMap.get("jdwp-timeout"));
      logger.debug("Applying CLI --jdwp-timeout: {}", value);
      builder.jdwpTimeout(value);
    }

    if (argMap.containsKey("mcp-port")) {
      int value = Integer.parseInt(argMap.get("mcp-port"));
      logger.debug("Applying CLI --mcp-port: {}", value);
      builder.mcpPort(value);
    }

    if (argMap.containsKey("log-level")) {
      String value = argMap.get("log-level");
      logger.debug("Applying CLI --log-level: {}", value);
      builder.logLevel(ProxyLogLevel.parse(value));
    }

    if (argMap.containsKey("reconnect")) {
      boolean value = Boolean.parseBoolean(argMap.get("reconnect"));
      logger.debug("Applying CLI --reconnect: {}", value);
      builder.reconnectEnabled(value);
    }

    if (argMap.containsKey("reconnect-interval")) {
      int value = Integer.parseInt(argMap.get("reconnect-interval"));
      logger.debug("Applying CLI --reconnect-interval: {}", value);
      builder.reconnectIntervalMs(value);
    }

    if (argMap.containsKey("health-check-interval")) {
      int value = Integer.parseInt(argMap.get("health-check-interval"));
      logger.debug("Applying CLI --health-check-interval: {}", value);
      builder.healthCheckIntervalMs(value);
    }

    if (argMap.containsKey("process-pattern")) {
      String value = argMap.get("process-pattern");
      logger.debug("Applying CLI --process-pattern: {}", value);
      builder.processPattern(value);
    }

    // Check for --auto-discover flag (boolean flag without value)
    for (String arg : args) {
      if ("--auto-discover".equals(arg)) {
        logger.debug("Applying CLI --auto-discover: true");
        builder.autoDiscover(true);
        break;
      }
    }
  }

  /**
   * Parses command-line arguments into a map.
   *
   * <p>
   * Expected format: --key value --key2 value2
   */
  private static Map<String, String> parseCommandLineArgs(String[] args) {
    Map<String, String> argMap = new HashMap<>();

    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("--") && i + 1 < args.length) {
        String key = args[i].substring(2); // Remove "--"
        String value = args[i + 1];

        // Skip if next arg is also a flag
        if (!value.startsWith("--")) {
          argMap.put(key, value);
          i++; // Skip the value in next iteration
        }
      }
    }

    return argMap;
  }

  /**
   * Applies auto-discovery if enabled and no explicit JDWP port was provided.
   *
   * <p>
   * This method is called after all other configuration sources have been
   * applied. It only performs discovery if:
   * <ul>
   * <li>autoDiscover is true (from any config source)</li>
   * <li>jdwpPort is still the default (5005), indicating no explicit port was
   * set</li>
   * </ul>
   *
   * @param builder the builder to update with discovered port
   * @param args    command-line arguments for checking explicit port
   */
  private static void applyAutoDiscovery(RemoteDebugProxyConfig.Builder builder, String[] args) {
    // Build a temporary config to check current values
    RemoteDebugProxyConfig tempConfig = builder.build();

    if (!tempConfig.autoDiscover()) {
      logger.trace("Auto-discovery not enabled, skipping");
      return;
    }

    // Check if user explicitly set a port (CLI, env, or config file)
    // We check args directly to see if --jdwp-port was explicitly provided
    Map<String, String> argMap = parseCommandLineArgs(args);
    boolean hasExplicitPort = argMap.containsKey("jdwp-port") || System.getenv().containsKey("DESCARTES_JDWP_PORT");

    if (hasExplicitPort) {
      logger.info("Auto-discovery enabled but explicit JDWP port provided, skipping discovery");
      return;
    }

    logger.info("Auto-discovery enabled, searching for JDWP processes...");

    try {
      String pattern = tempConfig.processPattern();

      if (pattern != null && !pattern.isBlank()) {
        // Pattern-based discovery
        logger.info("Searching for JDWP process matching pattern: '{}'", pattern);
        JDWPConnector.JdwpProcess process = JDWPConnector.findByPattern(pattern);
        logger.info("Auto-discovered JDWP process: {} (PID: {}, port: {})", process.displayName, process.pid,
            process.jdwpPort);
        builder.jdwpPort(process.jdwpPort);
        builder.jdwpHost(process.host);
      } else {
        // No pattern - discover all and use if only one found
        logger.info("No process pattern specified, discovering all JDWP processes");
        List<JDWPConnector.JdwpProcess> processes = JDWPConnector.discoverLocalJdwpProcesses();

        if (processes.isEmpty()) {
          throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED,
              "No Java debug processes found. Ensure target JVM is running with -agentlib:jdwp=... enabled.");
        }

        if (processes.size() == 1) {
          JDWPConnector.JdwpProcess process = processes.get(0);
          logger.info("Auto-discovered single JDWP process: {} (PID: {}, port: {})", process.displayName, process.pid,
              process.jdwpPort);
          builder.jdwpPort(process.jdwpPort);
          builder.jdwpHost(process.host);
        } else {
          // Multiple processes - need pattern
          String available = processes.stream()
              .map(p -> String.format("  - %s (PID: %s, port: %d)", p.displayName, p.pid, p.jdwpPort))
              .collect(Collectors.joining("\n"));

          throw new DebuggerException(DebuggerErrorCode.JDWP_CONNECTION_FAILED, String
              .format("Multiple JDWP processes found. Please specify --process-pattern to select one:\n%s", available));
        }
      }
    } catch (DebuggerException e) {
      // Re-throw debugger exceptions (they have helpful messages)
      throw e;
    } catch (Exception e) {
      logger.error("Auto-discovery failed: {}", e.getMessage(), e);
      throw new IllegalStateException("Auto-discovery failed: " + e.getMessage(), e);
    }
  }

  /**
   * Prints usage information to stderr.
   */
  public static void printUsage() {
    System.err.println("MCP Remote Debug Proxy - Usage:");
    System.err.println();
    System.err.println("  java -jar descartes-mcp.jar [options]");
    System.err.println();
    System.err.println("Options (CLI args override all other sources):");
    System.err.println("  --jdwp-host <host>            Target JVM hostname (default: localhost)");
    System.err.println("  --jdwp-port <port>            Target JDWP port (default: 5005)");
    System.err.println("  --jdwp-timeout <ms>           JDWP connection timeout (default: 5000)");
    System.err.println("  --mcp-port <port>             MCP server port (default: 9090)");
    System.err.println("  --log-level <ERROR|INFO|DEBUG> Log verbosity (default: INFO)");
    System.err.println("  --reconnect <true|false>      Enable auto-reconnect (default: true)");
    System.err.println("  --reconnect-interval <ms>     Reconnect interval (default: 5000)");
    System.err.println("  --health-check-interval <ms>  Health check interval (default: 30000)");
    System.err.println("  --config <file>               Load config from JSON file");
    System.err.println("  --auto-discover               Enable auto-discovery of JDWP processes");
    System.err.println("  --process-pattern <pattern>   Pattern to match process name (* and ? wildcards)");
    System.err.println();
    System.err.println("Environment Variables (override defaults):");
    System.err.println("  DESCARTES_JDWP_HOST");
    System.err.println("  DESCARTES_JDWP_PORT");
    System.err.println("  DESCARTES_JDWP_TIMEOUT");
    System.err.println("  DESCARTES_MCP_PORT");
    System.err.println("  DESCARTES_LOG_LEVEL");
    System.err.println("  DESCARTES_RECONNECT");
    System.err.println("  DESCARTES_RECONNECT_INTERVAL");
    System.err.println("  DESCARTES_HEALTH_CHECK_INTERVAL");
    System.err.println("  DESCARTES_AUTO_DISCOVER");
    System.err.println("  DESCARTES_PROCESS_PATTERN");
    System.err.println();
    System.err.println("Config File (proxy-config.json):");
    System.err.println("  {");
    System.err.println("    \"jdwpHost\": \"localhost\",");
    System.err.println("    \"jdwpPort\": 5005,");
    System.err.println("    \"mcpPort\": 9090,");
    System.err.println("    \"logLevel\": \"INFO\",");
    System.err.println("    \"autoDiscover\": true,");
    System.err.println("    \"processPattern\": \"morpheus\"");
    System.err.println("  }");
    System.err.println();
    System.err.println("Examples:");
    System.err.println("  # Explicit port");
    System.err.println("  java -jar descartes-mcp.jar --jdwp-port 5005");
    System.err.println();
    System.err.println("  # Auto-discover with pattern");
    System.err.println("  java -jar descartes-mcp.jar --auto-discover --process-pattern \"morpheus\"");
    System.err.println();
    System.err.println("  # Auto-discover single process (no pattern needed)");
    System.err.println("  java -jar descartes-mcp.jar --auto-discover");
    System.err.println();
    System.err.println("  # Remote debugging");
    System.err.println("  java -jar descartes-mcp.jar --jdwp-host staging.example.com --jdwp-port 5005");
    System.err.println();
    System.err.println("  # With config file");
    System.err.println("  java -jar descartes-mcp.jar --config proxy-config.json");
    System.err.println();
    System.err.println("See doc/MCPRemoteDebugProxy.md for comprehensive documentation.");
  }
}
