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

    LayoutComponentBuilder layout = buildLayout(builder, effectiveLevel, includeStackTraces);

    AppenderComponentBuilder consoleAppender = builder.newAppender("ProxyConsole", "Console")
        .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
        .add(layout);
    builder.add(consoleAppender);

    RootLoggerComponentBuilder rootLogger = builder.newRootLogger(rootLevel)
        .add(builder.newAppenderRef("ProxyConsole"));
    builder.add(rootLogger);

    Configurator.reconfigure(builder.build());
  }

  private static LayoutComponentBuilder buildLayout(ConfigurationBuilder<BuiltConfiguration> builder,
      ProxyLogLevel effectiveLevel, boolean includeStackTraces) {
    // Abbreviate package segments (e.g. c.b.d.d.MCPRemoteDebugProxy) to reduce log noise.
    // Timestamps include the date: proxy logs span days and restarts.
    String prefixedPattern = includeStackTraces
        ? "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %c{1.} - %msg%n%throwable{full}"
        : "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %c{1.} - %msg%notEmpty{ | %throwable{short.message}}%n";

    LayoutComponentBuilder layout = builder.newLayout("PatternLayout")
        .addAttribute("alwaysWriteExceptions", false);

    if (effectiveLevel == ProxyLogLevel.INFO) {
      // INFO mode: emit clean user-facing messages for INFO lines only.
      // WARN/ERROR keep structured prefixes for diagnostics.
      layout.addComponent(builder.newComponent("LevelPatternSelector")
          .addAttribute("defaultPattern", prefixedPattern)
          // LevelPatternSelector has its own exception-rendering default. Without
          // this, INFO mode appends full stack traces even though the surrounding
          // PatternLayout disables them.
          .addAttribute("alwaysWriteExceptions", false)
          .addComponent(builder.newComponent("PatternMatch")
              .addAttribute("key", "INFO")
              .addAttribute("pattern", "%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n")));
    } else {
      // DEBUG/ERROR mode: keep structured prefixes for all emitted messages.
      layout.addAttribute("pattern", prefixedPattern);
    }

    return layout;
  }

  private static Level toRootLevel(ProxyLogLevel logLevel) {
    return switch (logLevel) {
    case ERROR -> Level.ERROR;
    case INFO -> Level.INFO;
    case DEBUG -> Level.DEBUG;
    };
  }
}
