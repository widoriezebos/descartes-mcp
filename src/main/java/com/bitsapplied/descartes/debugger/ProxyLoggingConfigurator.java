package com.bitsapplied.descartes.debugger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Runtime logging configuration for the remote debug proxy process.
 */
public final class ProxyLoggingConfigurator {

  private ProxyLoggingConfigurator() {
  }

  public static void configure(ProxyLogLevel logLevel) {
    ProxyLogLevel effectiveLevel = logLevel == null ? ProxyLogLevel.INFO : logLevel;
    boolean includeStackTraces = effectiveLevel == ProxyLogLevel.DEBUG;
    Level rootLevel = toRootLevel(effectiveLevel);

    ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
    builder.setConfigurationName("descartes-proxy-runtime-logging");
    builder.setStatusLevel(Level.ERROR);

    String pattern = includeStackTraces ? "%d{HH:mm:ss.SSS} [%t] %-5level %c - %msg%n%throwable{full}"
        : "%d{HH:mm:ss.SSS} [%t] %-5level %c - %msg%n";
    LayoutComponentBuilder layout = builder.newLayout("PatternLayout")
        .addAttribute("pattern", pattern)
        .addAttribute("alwaysWriteExceptions", false);

    AppenderComponentBuilder consoleAppender = builder.newAppender("ProxyConsole", "Console")
        .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
        .add(layout);
    builder.add(consoleAppender);

    RootLoggerComponentBuilder rootLogger = builder.newRootLogger(rootLevel)
        .add(builder.newAppenderRef("ProxyConsole"));
    builder.add(rootLogger);

    Configurator.reconfigure(builder.build());
  }

  private static Level toRootLevel(ProxyLogLevel logLevel) {
    return switch (logLevel) {
    case ERROR -> Level.ERROR;
    case INFO -> Level.INFO;
    case DEBUG -> Level.DEBUG;
    };
  }
}
