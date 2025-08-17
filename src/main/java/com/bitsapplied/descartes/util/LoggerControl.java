package com.bitsapplied.descartes.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

public class LoggerControl {

  private LoggerControl() {
  }

  /** Raise or lower level for root and all explicitly configured loggers. */
  public static void setGlobalLevel(Level level) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();

    // Root
    config.getRootLogger().setLevel(level);

    // All named loggers from configuration (e.g., com.bitsapplied.morpheus, brain,
    // LLMAgent)
    for (LoggerConfig lc : config.getLoggers().values()) {
      lc.setLevel(level);
    }

    ctx.updateLoggers(); // apply
  }

  /** Set level for a package prefix only (e.g., "com.bitsapplied.morpheus"). */
  public static void setPackageLevel(String prefix, Level level) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();

    for (LoggerConfig lc : config.getLoggers().values()) {
      if (lc.getName() != null && (lc.getName().equals(prefix) || lc.getName().startsWith(prefix + "."))) {
        lc.setLevel(level);
      }
    }

    ctx.updateLoggers();
  }
}
