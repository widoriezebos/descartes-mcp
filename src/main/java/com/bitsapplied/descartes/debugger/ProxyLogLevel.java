package com.bitsapplied.descartes.debugger;

import java.util.Locale;

/**
 * Log verbosity modes supported by the remote debug proxy.
 */
public enum ProxyLogLevel {
  ERROR, INFO, DEBUG;

  /**
   * Parses a log level token from CLI/config/env sources.
   *
   * @param value raw token
   * @return parsed level
   * @throws IllegalArgumentException when value is null, blank, or unsupported
   */
  public static ProxyLogLevel parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("log level cannot be null/blank. Expected ERROR, INFO, or DEBUG.");
    }
    try {
      return ProxyLogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid log level '" + value + "'. Expected one of: ERROR, INFO, DEBUG.", e);
    }
  }
}
