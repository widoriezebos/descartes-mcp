package com.bitsapplied.descartes.tools.logging.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Service for discovering log files from Log4j2 runtime configuration.
 *
 * Interrogates the Log4j2 LoggerContext to find all FileAppender and
 * RollingFileAppender instances, extracts their file paths and patterns, and
 * discovers rolled files.
 */
public class LogFileDiscoveryService {

  /**
   * Discover all log files from Log4j2 configuration.
   *
   * @return List of LogFileInfo for all discovered log files
   */
  public List<LogFileInfo> discoverLogFiles() {
    List<LogFileInfo> logFiles = new ArrayList<>();

    try {
      LoggerContext context = (LoggerContext) LogManager.getContext(false);
      Configuration config = context.getConfiguration();

      config.getAppenders().forEach((name, appender) -> {
        try {
          if (appender instanceof RollingFileAppender rfa) {
            logFiles.add(processRollingFileAppender(name, rfa));
          } else if (appender instanceof FileAppender fa) {
            logFiles.add(processFileAppender(name, fa));
          }
        } catch (Exception e) {
          // Add error entry for failed appender
          logFiles.add(LogFileInfo.withError(name, appender.getClass().getSimpleName(), "unknown",
              "Failed to process appender: " + e.getMessage()));
        }
      });
    } catch (Exception e) {
      // If Log4j2 context is not available, return empty list
      return List.of();
    }

    return logFiles;
  }

  /**
   * Process a RollingFileAppender.
   */
  private LogFileInfo processRollingFileAppender(String name, RollingFileAppender appender) {
    String fileName = appender.getFileName();
    String filePattern = appender.getFilePattern();

    Path filePath = Paths.get(fileName);
    boolean readable = Files.isReadable(filePath);

    LogFileInfo info = LogFileInfo.forRollingFileAppender(name, fileName, filePattern, readable);

    // Extract timestamp pattern from layout
    if (appender.getLayout() instanceof PatternLayout layout) {
      String conversionPattern = layout.getConversionPattern();
      String timestampPattern = Log4j2PatternConverter.extractTimestampPattern(conversionPattern);
      if (timestampPattern != null) {
        try {
          DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter(timestampPattern);
          info = info.withTimestampPattern(timestampPattern, formatter);
        } catch (Exception e) {
          // Pattern conversion failed, continue without timestamp pattern
        }
      }
    }

    // Add file metadata if file exists
    if (Files.exists(filePath)) {
      try {
        long size = Files.size(filePath);
        Instant lastModified = Files.getLastModifiedTime(filePath).toInstant();
        info = info.withMetadata(size, lastModified);
      } catch (IOException e) {
        // Metadata not available, continue without it
      }
    }

    // Discover rolled files
    try {
      List<Path> rolledFiles = RolledFileResolver.discoverRolledFilesExcludingCurrent(filePath, filePattern);
      info = info.withRolledFiles(rolledFiles);
    } catch (IOException e) {
      // Rolled files discovery failed, continue without them
    }

    return info;
  }

  /**
   * Process a FileAppender.
   */
  private LogFileInfo processFileAppender(String name, FileAppender appender) {
    String fileName = appender.getFileName();
    Path filePath = Paths.get(fileName);
    boolean readable = Files.isReadable(filePath);

    LogFileInfo info = LogFileInfo.forFileAppender(name, fileName, readable);

    // Extract timestamp pattern from layout
    if (appender.getLayout() instanceof PatternLayout layout) {
      String conversionPattern = layout.getConversionPattern();
      String timestampPattern = Log4j2PatternConverter.extractTimestampPattern(conversionPattern);
      if (timestampPattern != null) {
        try {
          DateTimeFormatter formatter = Log4j2PatternConverter.toDateTimeFormatter(timestampPattern);
          info = info.withTimestampPattern(timestampPattern, formatter);
        } catch (Exception e) {
          // Pattern conversion failed, continue without timestamp pattern
        }
      }
    }

    // Add file metadata if file exists
    if (Files.exists(filePath)) {
      try {
        long size = Files.size(filePath);
        Instant lastModified = Files.getLastModifiedTime(filePath).toInstant();
        info = info.withMetadata(size, lastModified);
      } catch (IOException e) {
        // Metadata not available, continue without it
      }
    }

    return info;
  }

  /**
   * Get detailed information about a specific log file. Supports both active
   * files and rolled/archived files.
   *
   * @param filePath Path to the log file (active or rolled)
   * @return LogFileInfo with all metadata
   */
  public LogFileInfo getLogFileInfo(String filePath) {
    List<LogFileInfo> allFiles = discoverLogFiles();

    // First try exact match on active file path
    LogFileInfo match = allFiles.stream().filter(info -> info.filePath().equals(filePath)).findFirst().orElse(null);

    if (match != null) {
      return match;
    }

    // If not found, check if it's a rolled file from any appender
    Path searchPath = Paths.get(filePath);
    return allFiles.stream()
        .filter(info -> info.rolledFiles().stream().anyMatch(rolledPath -> rolledPath.equals(searchPath))).findFirst()
        .orElse(null);
  }

  /**
   * Check if Log4j2 context is available.
   *
   * @return true if Log4j2 is configured and active
   */
  public boolean isLog4j2Available() {
    try (LoggerContext context = (LoggerContext) LogManager.getContext(false)) {
      return context != null && context.getConfiguration() != null;
    } catch (Exception e) {
      return false;
    }
  }
}
