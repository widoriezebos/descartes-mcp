package com.bitsapplied.descartes.security;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Detects the runtime environment (development, staging, production) from
 * various sources. Uses a fail-safe approach: defaults to "production" when
 * environment is unknown to ensure maximum security.
 *
 * <p>
 * Detection order (first match wins):
 * <ol>
 * <li>Context map: "descartes.environment"</li>
 * <li>System properties: "descartes.environment", "env", "environment"</li>
 * <li>Environment variables: "DESCARTES_ENVIRONMENT", "ENV", "ENVIRONMENT",
 * "DEPLOYMENT_ENV"</li>
 * <li>Default: "production" (fail-safe)</li>
 * </ol>
 *
 * <p>
 * Thread-safe and stateless.
 */
public class EnvironmentDetector {
  private static final Logger logger = Logger.getLogger(EnvironmentDetector.class.getName());

  /**
   * Standard environment names.
   */
  public enum Environment {
    DEVELOPMENT("development", "dev"), STAGING("staging", "stage"), PRODUCTION("production", "prod"),
    TEST("test", "testing"), UNKNOWN("unknown");

    private final String[] aliases;

    Environment(String... aliases) {
      this.aliases = aliases;
    }

    /**
     * Parses a string into an Environment, matching against aliases
     * (case-insensitive).
     */
    public static Environment parse(String value) {
      if (value == null || value.isEmpty()) {
        return UNKNOWN;
      }

      String normalized = value.toLowerCase().trim();
      for (Environment env : values()) {
        for (String alias : env.aliases) {
          if (alias.equals(normalized)) {
            return env;
          }
        }
      }

      return UNKNOWN;
    }

    /**
     * Checks if this environment should be treated as production (high security).
     */
    public boolean isProduction() {
      return this == PRODUCTION || this == UNKNOWN;
    }

    /**
     * Checks if this environment allows relaxed security (development/testing).
     */
    public boolean isDevelopment() {
      return this == DEVELOPMENT || this == TEST;
    }
  }

  /**
   * Detects the current environment from context map, system properties, and
   * environment variables.
   *
   * @param context Application context map (may be null)
   * @return Detected environment (defaults to PRODUCTION if unknown)
   */
  public static Environment detectEnvironment(Map<String, Object> context) {
    Environment env = detectFromContext(context);
    if (env != Environment.UNKNOWN) {
      logger.info("Environment detected from context: " + env);
      return env;
    }

    env = detectFromSystemProperties();
    if (env != Environment.UNKNOWN) {
      logger.info("Environment detected from system properties: " + env);
      return env;
    }

    env = detectFromEnvironmentVariables();
    if (env != Environment.UNKNOWN) {
      logger.info("Environment detected from environment variables: " + env);
      return env;
    }

    logger.warning("Environment not specified, defaulting to PRODUCTION (fail-safe)");
    return Environment.PRODUCTION;
  }

  /**
   * Detects environment from context map.
   */
  private static Environment detectFromContext(Map<String, Object> context) {
    if (context == null) {
      return Environment.UNKNOWN;
    }

    Object value = context.get("descartes.environment");
    if (value instanceof String) {
      return Environment.parse((String) value);
    }

    return Environment.UNKNOWN;
  }

  /**
   * Detects environment from system properties.
   */
  private static Environment detectFromSystemProperties() {
    // Try specific property first
    String value = System.getProperty("descartes.environment");
    if (value != null) {
      return Environment.parse(value);
    }

    // Try common properties
    value = System.getProperty("env");
    if (value != null) {
      return Environment.parse(value);
    }

    value = System.getProperty("environment");
    if (value != null) {
      return Environment.parse(value);
    }

    return Environment.UNKNOWN;
  }

  /**
   * Detects environment from environment variables.
   */
  private static Environment detectFromEnvironmentVariables() {
    // Try specific variable first
    String value = System.getenv("DESCARTES_ENVIRONMENT");
    if (value != null) {
      return Environment.parse(value);
    }

    // Try common variables
    value = System.getenv("ENV");
    if (value != null) {
      return Environment.parse(value);
    }

    value = System.getenv("ENVIRONMENT");
    if (value != null) {
      return Environment.parse(value);
    }

    value = System.getenv("DEPLOYMENT_ENV");
    if (value != null) {
      return Environment.parse(value);
    }

    return Environment.UNKNOWN;
  }

  /**
   * Checks if strict security mode should be enabled. Returns true for
   * production/staging environments or when environment is unknown (fail-safe).
   *
   * @param context Application context map (may be null)
   * @return true if strict security should be enforced
   */
  public static boolean isStrictMode(Map<String, Object> context) {
    Environment env = detectEnvironment(context);
    return env.isProduction() || env == Environment.STAGING;
  }

  /**
   * Checks if development mode is active (relaxed security allowed).
   *
   * @param context Application context map (may be null)
   * @return true if in development/test environment
   */
  public static boolean isDevelopmentMode(Map<String, Object> context) {
    Environment env = detectEnvironment(context);
    return env.isDevelopment();
  }
}
