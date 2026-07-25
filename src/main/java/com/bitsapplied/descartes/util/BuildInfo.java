package com.bitsapplied.descartes.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build metadata loaded from the Maven-filtered {@code version.properties}
 * resource.
 *
 * <p>
 * Values fall back to {@code "unknown"} when the resource is missing or was
 * packaged without Maven filtering (e.g. some IDE-managed classpaths).
 */
public final class BuildInfo {

  private static final String RESOURCE = "/version.properties";
  private static final String UNKNOWN = "unknown";
  private static final Properties PROPS = load();

  private BuildInfo() {
  }

  private static Properties load() {
    Properties props = new Properties();
    try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException e) {
      // Build metadata is diagnostic only; fall back to "unknown" values.
    }
    return props;
  }

  private static String property(String key) {
    String value = PROPS.getProperty(key, UNKNOWN).trim();
    // An unfiltered resource still contains the raw ${...} placeholder.
    return value.isEmpty() || value.startsWith("${") ? UNKNOWN : value;
  }

  /**
   * @return Maven build timestamp (e.g. {@code 20260725-090437}), or
   *         {@code "unknown"}
   */
  public static String buildId() {
    return property("build.id");
  }

  /**
   * @return project version from the POM (e.g. {@code 1.0.3}), or
   *         {@code "unknown"}
   */
  public static String projectVersion() {
    return property("project.version");
  }

  /**
   * @return combined version string, e.g. {@code 1.0.3+20260725-090437}
   */
  public static String describe() {
    return projectVersion() + "+" + buildId();
  }
}
