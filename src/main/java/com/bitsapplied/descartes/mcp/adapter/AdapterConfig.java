package com.bitsapplied.descartes.mcp.adapter;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

final class AdapterConfig {
  private static final String DEFAULT_HOST = "localhost";
  private static final boolean DEFAULT_DEBUG = false;
  private static final int DEFAULT_PORT = 9080;
  private static final int DEFAULT_RECONNECT_MIN_DELAY_MS = 500;
  private static final int DEFAULT_RECONNECT_MAX_DELAY_MS = 5000;
  private static final int DEFAULT_MESSAGE_QUEUE_SIZE = 100;
  private static final int DEFAULT_REQUEST_TIMEOUT_MS = 30000;
  private static final int DEFAULT_TCP_KEEP_ALIVE_DELAY_MS = 10000;
  private static final int DEFAULT_LOG_RATE_LIMIT_WINDOW_MS = 60000;
  private static final int DEFAULT_LOG_RATE_LIMIT_MAX = 10;
  private static final int DEFAULT_MAX_MESSAGE_SIZE_BYTES = 10 * 1024 * 1024;

  private static final int MIN_PORT = 1;
  private static final int MAX_PORT = 65535;
  private static final int MIN_RECONNECT_DELAY_MS = 50;
  private static final int MAX_RECONNECT_DELAY_MS = 300000;
  private static final int MIN_QUEUE_SIZE = 1;
  private static final int MAX_QUEUE_SIZE = 10000;
  private static final int MIN_REQUEST_TIMEOUT_MS = 100;
  private static final int ENV_MIN_REQUEST_TIMEOUT_MS = 1000;
  private static final int MAX_REQUEST_TIMEOUT_MS = 600000;
  private static final int MIN_TCP_KEEP_ALIVE_MS = 1000;
  private static final int MAX_TCP_KEEP_ALIVE_MS = 600000;
  private static final int MIN_LOG_RATE_WINDOW_MS = 1000;
  private static final int MAX_LOG_RATE_WINDOW_MS = 600000;
  private static final int MIN_LOG_RATE_MAX = 1;
  private static final int MAX_LOG_RATE_MAX = 1000;
  private static final int MIN_MESSAGE_SIZE_BYTES = 1024;
  private static final int MAX_MESSAGE_SIZE_BYTES = 100 * 1024 * 1024;

  final String host;
  final int port;
  final boolean debug;
  final int reconnectMinDelayMs;
  final int reconnectMaxDelayMs;
  final int messageQueueSize;
  final int requestTimeoutMs;
  final int tcpKeepAliveDelayMs;
  final int logRateLimitWindowMs;
  final int logRateLimitMax;
  final int maxMessageSizeBytes;

  private AdapterConfig(String host, int port, boolean debug, int reconnectMinDelayMs, int reconnectMaxDelayMs,
      int messageQueueSize, int requestTimeoutMs, int tcpKeepAliveDelayMs, int logRateLimitWindowMs,
      int logRateLimitMax, int maxMessageSizeBytes) {
    this.host = host;
    this.port = port;
    this.debug = debug;
    this.reconnectMinDelayMs = reconnectMinDelayMs;
    this.reconnectMaxDelayMs = reconnectMaxDelayMs;
    this.messageQueueSize = messageQueueSize;
    this.requestTimeoutMs = requestTimeoutMs;
    this.tcpKeepAliveDelayMs = tcpKeepAliveDelayMs;
    this.logRateLimitWindowMs = logRateLimitWindowMs;
    this.logRateLimitMax = logRateLimitMax;
    this.maxMessageSizeBytes = maxMessageSizeBytes;
  }

  static AdapterConfig fromEnvironment() {
    String host = getEnv("MCP_HOST", DEFAULT_HOST);
    int port = getIntEnv("MCP_PORT", DEFAULT_PORT, MIN_PORT, MAX_PORT);
    boolean debug = "true".equalsIgnoreCase(getEnv("MCP_DEBUG", Boolean.toString(DEFAULT_DEBUG)));
    int reconnectMinDelayMs = getIntEnv("MCP_RECONNECT_MIN_DELAY", DEFAULT_RECONNECT_MIN_DELAY_MS,
        MIN_RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS);
    int reconnectMaxDelayMs = Math.max(reconnectMinDelayMs, getIntEnv("MCP_RECONNECT_MAX_DELAY",
        DEFAULT_RECONNECT_MAX_DELAY_MS, MIN_RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS));
    int messageQueueSize = getIntEnv("MCP_MESSAGE_QUEUE_SIZE", DEFAULT_MESSAGE_QUEUE_SIZE, MIN_QUEUE_SIZE,
        MAX_QUEUE_SIZE);
    int requestTimeoutMs = getIntEnv("MCP_REQUEST_TIMEOUT", DEFAULT_REQUEST_TIMEOUT_MS, ENV_MIN_REQUEST_TIMEOUT_MS,
        MAX_REQUEST_TIMEOUT_MS);
    int tcpKeepAliveDelayMs = getIntEnv("MCP_TCP_KEEP_ALIVE", DEFAULT_TCP_KEEP_ALIVE_DELAY_MS, MIN_TCP_KEEP_ALIVE_MS,
        MAX_TCP_KEEP_ALIVE_MS);
    int logRateLimitWindowMs = getIntEnv("MCP_LOG_RATE_LIMIT_WINDOW", DEFAULT_LOG_RATE_LIMIT_WINDOW_MS,
        MIN_LOG_RATE_WINDOW_MS, MAX_LOG_RATE_WINDOW_MS);
    int logRateLimitMax = getIntEnv("MCP_LOG_RATE_LIMIT_MAX", DEFAULT_LOG_RATE_LIMIT_MAX, MIN_LOG_RATE_MAX,
        MAX_LOG_RATE_MAX);
    int maxMessageSizeBytes = getIntEnv("MCP_MAX_MESSAGE_SIZE", DEFAULT_MAX_MESSAGE_SIZE_BYTES, MIN_MESSAGE_SIZE_BYTES,
        MAX_MESSAGE_SIZE_BYTES);

    return builder().host(host).port(port).debug(debug).reconnectMinDelayMs(reconnectMinDelayMs)
        .reconnectMaxDelayMs(reconnectMaxDelayMs).messageQueueSize(messageQueueSize).requestTimeoutMs(requestTimeoutMs)
        .tcpKeepAliveDelayMs(tcpKeepAliveDelayMs).logRateLimitWindowMs(logRateLimitWindowMs)
        .logRateLimitMax(logRateLimitMax).maxMessageSizeBytes(maxMessageSizeBytes).build();
  }

  static AdapterConfig create(String host, int port, boolean debug, int reconnectMinDelayMs, int reconnectMaxDelayMs,
      int messageQueueSize, int requestTimeoutMs, int tcpKeepAliveDelayMs, int logRateLimitWindowMs,
      int logRateLimitMax, int maxMessageSizeBytes) {
    return new AdapterConfig(host, port, debug, reconnectMinDelayMs, reconnectMaxDelayMs, messageQueueSize,
        requestTimeoutMs, tcpKeepAliveDelayMs, logRateLimitWindowMs, logRateLimitMax, maxMessageSizeBytes);
  }

  static Builder builder() {
    return new Builder();
  }

  private static String getEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return value.trim();
  }

  private static int getIntEnv(String name, int defaultValue, int min, int max) {
    String raw = System.getenv(name);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed < min) {
        warnOutOfRange(name, parsed, min, max);
        return min;
      }
      if (parsed > max) {
        warnOutOfRange(name, parsed, min, max);
        return max;
      }
      return parsed;
    } catch (NumberFormatException ex) {
      System.err.printf(Locale.ROOT, "[MCP-TCP-Adapter] %s - Invalid integer for %s: %s (using default %d)%n",
          Instant.now(), name, raw, defaultValue);
      return defaultValue;
    }
  }

  private static void warnOutOfRange(String name, int value, int min, int max) {
    System.err.printf(Locale.ROOT, "[MCP-TCP-Adapter] %s - %s=%d out of range [%d,%d], clamping%n", Instant.now(), name,
        value, min, max);
  }

  private static int validateRange(String name, int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          String.format(Locale.ROOT, "%s must be between %d and %d (was %d)", name, min, max, value));
    }
    return value;
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "host=%s, port=%d, debug=%s, queueSize=%d, requestTimeout=%dms", host, port,
        debug, messageQueueSize, requestTimeoutMs);
  }

  static final class Builder {
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private boolean debug = DEFAULT_DEBUG;
    private int reconnectMinDelayMs = DEFAULT_RECONNECT_MIN_DELAY_MS;
    private int reconnectMaxDelayMs = DEFAULT_RECONNECT_MAX_DELAY_MS;
    private int messageQueueSize = DEFAULT_MESSAGE_QUEUE_SIZE;
    private int requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    private int tcpKeepAliveDelayMs = DEFAULT_TCP_KEEP_ALIVE_DELAY_MS;
    private int logRateLimitWindowMs = DEFAULT_LOG_RATE_LIMIT_WINDOW_MS;
    private int logRateLimitMax = DEFAULT_LOG_RATE_LIMIT_MAX;
    private int maxMessageSizeBytes = DEFAULT_MAX_MESSAGE_SIZE_BYTES;

    Builder host(String host) {
      this.host = normalizeHost(host);
      return this;
    }

    Builder port(int port) {
      this.port = validateRange("port", port, MIN_PORT, MAX_PORT);
      return this;
    }

    Builder debug(boolean debug) {
      this.debug = debug;
      return this;
    }

    Builder reconnectMinDelayMs(int delayMs) {
      this.reconnectMinDelayMs = validateRange("reconnectMinDelayMs", delayMs, MIN_RECONNECT_DELAY_MS,
          MAX_RECONNECT_DELAY_MS);
      if (this.reconnectMaxDelayMs < this.reconnectMinDelayMs) {
        this.reconnectMaxDelayMs = this.reconnectMinDelayMs;
      }
      return this;
    }

    Builder reconnectMaxDelayMs(int delayMs) {
      this.reconnectMaxDelayMs = validateRange("reconnectMaxDelayMs", delayMs, MIN_RECONNECT_DELAY_MS,
          MAX_RECONNECT_DELAY_MS);
      if (this.reconnectMaxDelayMs < this.reconnectMinDelayMs) {
        this.reconnectMinDelayMs = this.reconnectMaxDelayMs;
      }
      return this;
    }

    Builder messageQueueSize(int size) {
      this.messageQueueSize = validateRange("messageQueueSize", size, MIN_QUEUE_SIZE, MAX_QUEUE_SIZE);
      return this;
    }

    Builder requestTimeoutMs(int timeoutMs) {
      this.requestTimeoutMs = validateRange("requestTimeoutMs", timeoutMs, MIN_REQUEST_TIMEOUT_MS,
          MAX_REQUEST_TIMEOUT_MS);
      return this;
    }

    Builder tcpKeepAliveDelayMs(int keepAliveMs) {
      this.tcpKeepAliveDelayMs = validateRange("tcpKeepAliveDelayMs", keepAliveMs, MIN_TCP_KEEP_ALIVE_MS,
          MAX_TCP_KEEP_ALIVE_MS);
      return this;
    }

    Builder logRateLimitWindowMs(int windowMs) {
      this.logRateLimitWindowMs = validateRange("logRateLimitWindowMs", windowMs, MIN_LOG_RATE_WINDOW_MS,
          MAX_LOG_RATE_WINDOW_MS);
      return this;
    }

    Builder logRateLimitMax(int max) {
      this.logRateLimitMax = validateRange("logRateLimitMax", max, MIN_LOG_RATE_MAX, MAX_LOG_RATE_MAX);
      return this;
    }

    Builder maxMessageSizeBytes(int maxBytes) {
      this.maxMessageSizeBytes = validateRange("maxMessageSizeBytes", maxBytes, MIN_MESSAGE_SIZE_BYTES,
          MAX_MESSAGE_SIZE_BYTES);
      return this;
    }

    AdapterConfig build() {
      return new AdapterConfig(host, port, debug, reconnectMinDelayMs, reconnectMaxDelayMs, messageQueueSize,
          requestTimeoutMs, tcpKeepAliveDelayMs, logRateLimitWindowMs, logRateLimitMax, maxMessageSizeBytes);
    }

    private static String normalizeHost(String value) {
      String trimmed = Objects.requireNonNullElse(value, DEFAULT_HOST).trim();
      return trimmed.isEmpty() ? DEFAULT_HOST : trimmed;
    }
  }
}
