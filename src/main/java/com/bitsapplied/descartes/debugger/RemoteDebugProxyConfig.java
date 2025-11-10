package com.bitsapplied.descartes.debugger;

import java.util.Objects;

/**
 * Configuration for the MCP Remote Debug Proxy.
 *
 * <p>
 * Supports configuration from multiple sources with priority: CLI args > config
 * file > environment variables > defaults
 *
 * <p>
 * Default values:
 * <ul>
 * <li>jdwpHost: "localhost"
 * <li>jdwpPort: 5005
 * <li>mcpPort: 9090 (avoids conflict with embedded mode's 9080)
 * <li>jdwpTimeout: 5000ms
 * <li>reconnectEnabled: true
 * <li>reconnectIntervalMs: 5000ms
 * <li>healthCheckIntervalMs: 30000ms
 * <li>autoDiscover: false
 * <li>processPattern: null
 * </ul>
 */
public class RemoteDebugProxyConfig {

  // JDWP connection settings
  private final String jdwpHost;
  private final int jdwpPort;
  private final int jdwpTimeout;

  // MCP server settings
  private final int mcpPort;

  // Health and reconnection settings
  private final boolean reconnectEnabled;
  private final int reconnectIntervalMs;
  private final int healthCheckIntervalMs;

  // Config file path (optional)
  private final String configFilePath;

  // Auto-discovery settings
  private final boolean autoDiscover;
  private final String processPattern;

  /**
   * Creates a new configuration with all parameters.
   */
  public RemoteDebugProxyConfig(String jdwpHost, int jdwpPort, int jdwpTimeout, int mcpPort, boolean reconnectEnabled,
      int reconnectIntervalMs, int healthCheckIntervalMs, String configFilePath, boolean autoDiscover,
      String processPattern) {

    this.jdwpHost = Objects.requireNonNull(jdwpHost, "jdwpHost cannot be null");
    this.jdwpPort = jdwpPort;
    this.jdwpTimeout = jdwpTimeout;
    this.mcpPort = mcpPort;
    this.reconnectEnabled = reconnectEnabled;
    this.reconnectIntervalMs = reconnectIntervalMs;
    this.healthCheckIntervalMs = healthCheckIntervalMs;
    this.configFilePath = configFilePath;
    this.autoDiscover = autoDiscover;
    this.processPattern = processPattern;

    validate();
  }

  /**
   * Validates configuration parameters.
   *
   * @throws IllegalArgumentException if any parameter is invalid
   */
  private void validate() {
    if (jdwpHost.trim().isEmpty()) {
      throw new IllegalArgumentException("jdwpHost cannot be empty");
    }

    if (jdwpPort < 1 || jdwpPort > 65535) {
      throw new IllegalArgumentException("jdwpPort must be between 1 and 65535, got: " + jdwpPort);
    }

    if (mcpPort < 1 || mcpPort > 65535) {
      throw new IllegalArgumentException("mcpPort must be between 1 and 65535, got: " + mcpPort);
    }

    if (jdwpPort == mcpPort) {
      throw new IllegalArgumentException("jdwpPort and mcpPort cannot be the same: " + jdwpPort);
    }

    if (jdwpTimeout < 100) {
      throw new IllegalArgumentException("jdwpTimeout must be at least 100ms, got: " + jdwpTimeout);
    }

    if (reconnectIntervalMs < 1000) {
      throw new IllegalArgumentException("reconnectIntervalMs must be at least 1000ms, got: " + reconnectIntervalMs);
    }

    if (healthCheckIntervalMs < 5000) {
      throw new IllegalArgumentException(
          "healthCheckIntervalMs must be at least 5000ms, got: " + healthCheckIntervalMs);
    }
  }

  // Getters

  public String getJdwpHost() {
    return jdwpHost;
  }

  public int getJdwpPort() {
    return jdwpPort;
  }

  public int getJdwpTimeout() {
    return jdwpTimeout;
  }

  public int getMcpPort() {
    return mcpPort;
  }

  public boolean isReconnectEnabled() {
    return reconnectEnabled;
  }

  public int getReconnectIntervalMs() {
    return reconnectIntervalMs;
  }

  public int getHealthCheckIntervalMs() {
    return healthCheckIntervalMs;
  }

  public String getConfigFilePath() {
    return configFilePath;
  }

  public boolean autoDiscover() {
    return autoDiscover;
  }

  public String processPattern() {
    return processPattern;
  }

  /**
   * Returns default configuration.
   */
  public static RemoteDebugProxyConfig defaults() {
    return builder().build();
  }

  /**
   * Creates a builder with default values.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for RemoteDebugProxyConfig with fluent API.
   */
  public static class Builder {
    private String jdwpHost = "localhost";
    private int jdwpPort = 5005;
    private int jdwpTimeout = 5000;
    private int mcpPort = 9090; // Different from embedded mode's 9080
    private boolean reconnectEnabled = true;
    private int reconnectIntervalMs = 5000;
    private int healthCheckIntervalMs = 30000;
    private String configFilePath = null;
    private boolean autoDiscover = false;
    private String processPattern = null;

    public Builder jdwpHost(String jdwpHost) {
      this.jdwpHost = jdwpHost;
      return this;
    }

    public Builder jdwpPort(int jdwpPort) {
      this.jdwpPort = jdwpPort;
      return this;
    }

    public Builder jdwpTimeout(int jdwpTimeout) {
      this.jdwpTimeout = jdwpTimeout;
      return this;
    }

    public Builder mcpPort(int mcpPort) {
      this.mcpPort = mcpPort;
      return this;
    }

    public Builder reconnectEnabled(boolean reconnectEnabled) {
      this.reconnectEnabled = reconnectEnabled;
      return this;
    }

    public Builder reconnectIntervalMs(int reconnectIntervalMs) {
      this.reconnectIntervalMs = reconnectIntervalMs;
      return this;
    }

    public Builder healthCheckIntervalMs(int healthCheckIntervalMs) {
      this.healthCheckIntervalMs = healthCheckIntervalMs;
      return this;
    }

    public Builder configFilePath(String configFilePath) {
      this.configFilePath = configFilePath;
      return this;
    }

    public Builder autoDiscover(boolean autoDiscover) {
      this.autoDiscover = autoDiscover;
      return this;
    }

    public Builder processPattern(String processPattern) {
      this.processPattern = processPattern;
      return this;
    }

    public RemoteDebugProxyConfig build() {
      return new RemoteDebugProxyConfig(jdwpHost, jdwpPort, jdwpTimeout, mcpPort, reconnectEnabled, reconnectIntervalMs,
          healthCheckIntervalMs, configFilePath, autoDiscover, processPattern);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "RemoteDebugProxyConfig{jdwpHost='%s', jdwpPort=%d, jdwpTimeout=%d, "
            + "mcpPort=%d, reconnectEnabled=%b, reconnectIntervalMs=%d, "
            + "healthCheckIntervalMs=%d, configFilePath='%s', autoDiscover=%b, processPattern='%s'}",
        jdwpHost, jdwpPort, jdwpTimeout, mcpPort, reconnectEnabled, reconnectIntervalMs, healthCheckIntervalMs,
        configFilePath, autoDiscover, processPattern);
  }
}
