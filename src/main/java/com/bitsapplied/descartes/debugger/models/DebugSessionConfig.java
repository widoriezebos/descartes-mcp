package com.bitsapplied.descartes.debugger.models;

import java.util.List;
import java.util.Map;

/**
 * Debug session configuration record.
 *
 * <p>
 * Encapsulates configuration parameters for a debug session, including JDWP
 * connection settings, stepping behavior, and patterns for filtering system
 * classes.
 *
 * <p>
 * This record is immutable and can be used to create sessions with consistent
 * configuration across multiple operations.
 */
public record DebugSessionConfig(int jdwpTimeout, // Timeout for JDWP connection in milliseconds
    boolean stopOnEntry, // Stop at main method entry
    String[] skipPatterns // Patterns to skip during stepping (e.g., "java.*", "javax.*")
) {
  /**
   * Creates a DebugSessionConfig with default values.
   *
   * <p>
   * Default configuration:
   * <ul>
   * <li>JDWP timeout: 5000ms (5 seconds)</li>
   * <li>Stop on entry: false</li>
   * <li>Skip patterns: java.*, javax.*, jdk.*, sun.*</li>
   * </ul>
   *
   * @return a new DebugSessionConfig with defaults
   */
  public static DebugSessionConfig defaults() {
    return new DebugSessionConfig(5000, // 5 second timeout
        false, new String[] { "java.*", "javax.*", "jdk.*", "sun.*" });
  }

  /**
   * Creates a DebugSessionConfig from a map of values.
   *
   * <p>
   * Supports the following map keys (all optional):
   * <ul>
   * <li>"jdwpTimeout" (Integer or Number): timeout in milliseconds, default
   * 5000</li>
   * <li>"stopOnEntry" (Boolean): whether to stop at main, default false</li>
   * <li>"skipPatterns" (List of String): class patterns to skip, default system
   * packages</li>
   * </ul>
   *
   * @param map the configuration map
   * @return a new DebugSessionConfig
   * @throws ClassCastException if map values are of unexpected types
   */
  public static DebugSessionConfig fromMap(Map<String, Object> map) {
    if (map == null) {
      return defaults();
    }

    int timeout = 5000;
    if (map.containsKey("jdwpTimeout")) {
      Object timeoutValue = map.get("jdwpTimeout");
      if (timeoutValue instanceof Number num) {
        timeout = num.intValue();
      }
    }

    boolean stopOnEntry = false;
    if (map.containsKey("stopOnEntry")) {
      Object stopValue = map.get("stopOnEntry");
      if (stopValue instanceof Boolean b) {
        stopOnEntry = b;
      }
    }

    String[] skipPatterns = new String[] { "java.*", "javax.*", "jdk.*", "sun.*" };
    if (map.containsKey("skipPatterns")) {
      Object patternsValue = map.get("skipPatterns");
      if (patternsValue instanceof List<?> list) {
        skipPatterns = list.stream().filter(p -> p instanceof String).map(p -> (String) p).toArray(String[]::new);
      }
    }

    return new DebugSessionConfig(timeout, stopOnEntry, skipPatterns);
  }

  /**
   * Validates that the configuration values are sensible.
   *
   * @return true if all values are valid
   */
  public boolean isValid() {
    return jdwpTimeout > 0 && jdwpTimeout <= 60000 // Max 60 seconds
        && (skipPatterns == null || skipPatterns.length <= 100); // Reasonable limit
  }

  /**
   * Checks if a class name matches any of the skip patterns.
   *
   * <p>
   * Uses simple wildcard matching where '*' matches any sequence of characters.
   *
   * @param className the fully qualified class name to check
   * @return true if the class should be skipped
   */
  public boolean shouldSkipClass(String className) {
    if (skipPatterns == null || skipPatterns.length == 0) {
      return false;
    }

    for (String pattern : skipPatterns) {
      if (matchesPattern(className, pattern)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if a class name matches a wildcard pattern.
   *
   * <p>
   * Pattern matching rules:
   * <ul>
   * <li>"java.*" matches "java.lang.String" and "java.util.List"</li>
   * <li>"java.lang.*" matches "java.lang.String" but not "java.util.List"</li>
   * <li>"*Exception" matches "RuntimeException" and "IOException"</li>
   * </ul>
   *
   * @param className the class name to test
   * @param pattern   the pattern to test against
   * @return true if the class name matches the pattern
   */
  private boolean matchesPattern(String className, String pattern) {
    // Simple wildcard matching: convert pattern to regex
    String regex = pattern.replace(".", "\\.") // Escape dots
        .replace("*", ".*"); // Convert * to .*

    return className.matches(regex);
  }

  /**
   * Converts the configuration to a map representation.
   *
   * @return a map containing the configuration
   */
  public Map<String, Object> toMap() {
    return Map.of("jdwpTimeout", jdwpTimeout, "stopOnEntry", stopOnEntry, "skipPatterns", skipPatterns);
  }

  /**
   * Gets a detailed description of this configuration.
   *
   * @return formatted configuration details
   */
  @Override
  public String toString() {
    return String.format("DebugSessionConfig{timeout=%dms, stopOnEntry=%b, skipPatterns=%d}", jdwpTimeout, stopOnEntry,
        skipPatterns != null ? skipPatterns.length : 0);
  }
}
