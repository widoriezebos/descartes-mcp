package com.bitsapplied.descartes.tools.logging.support;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Metadata about a log file discovered from Log4j2 appenders.
 *
 * @param appenderName       Name of the Log4j2 appender (e.g., "ERROR_FILE")
 * @param type               Type of appender: "FileAppender" or
 *                           "RollingFileAppender"
 * @param filePath           Current active log file path
 * @param sizeBytes          Size of the file in bytes (may be null if file
 *                           doesn't exist)
 * @param lastModified       Last modification timestamp (may be null if
 *                           unknown)
 * @param readable           Whether the file is readable by the current process
 * @param filePattern        Pattern for rolled files (only for
 *                           RollingFileAppender)
 * @param rolledFiles        List of discovered rolled/archived files
 * @param timestampPattern   Log4j2 timestamp pattern from PatternLayout (e.g.,
 *                           "yyyy-MM-dd HH:mm:ss")
 * @param timestampFormatter Compiled DateTimeFormatter for guaranteed timestamp
 *                           parsing
 * @param error              Error message if file is not accessible (null if
 *                           accessible)
 */
public record LogFileInfo(String appenderName, String type, String filePath, Long sizeBytes, Instant lastModified,
    boolean readable, String filePattern, List<Path> rolledFiles, String timestampPattern,
    DateTimeFormatter timestampFormatter, String error) {
  /**
   * Compact constructor with defensive copying and validation.
   */
  public LogFileInfo {
    if (appenderName == null || appenderName.isBlank()) {
      throw new IllegalArgumentException("Appender name cannot be null or blank");
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("Type cannot be null or blank");
    }
    if (filePath == null || filePath.isBlank()) {
      throw new IllegalArgumentException("File path cannot be null or blank");
    }

    rolledFiles = rolledFiles != null ? List.copyOf(rolledFiles) : List.of();
  }

  /**
   * Create a builder for constructing LogFileInfo instances.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for LogFileInfo with fluent API.
   */
  public static class Builder {
    private String appenderName;
    private String type;
    private String filePath;
    private Long sizeBytes;
    private Instant lastModified;
    private boolean readable;
    private String filePattern;
    private List<Path> rolledFiles;
    private String timestampPattern;
    private DateTimeFormatter timestampFormatter;
    private String error;

    public Builder appenderName(String appenderName) {
      this.appenderName = appenderName;
      return this;
    }

    public Builder type(String type) {
      this.type = type;
      return this;
    }

    public Builder filePath(String filePath) {
      this.filePath = filePath;
      return this;
    }

    public Builder sizeBytes(Long sizeBytes) {
      this.sizeBytes = sizeBytes;
      return this;
    }

    public Builder lastModified(Instant lastModified) {
      this.lastModified = lastModified;
      return this;
    }

    public Builder readable(boolean readable) {
      this.readable = readable;
      return this;
    }

    public Builder filePattern(String filePattern) {
      this.filePattern = filePattern;
      return this;
    }

    public Builder rolledFiles(List<Path> rolledFiles) {
      this.rolledFiles = rolledFiles;
      return this;
    }

    public Builder timestampPattern(String timestampPattern) {
      this.timestampPattern = timestampPattern;
      return this;
    }

    public Builder timestampFormatter(DateTimeFormatter timestampFormatter) {
      this.timestampFormatter = timestampFormatter;
      return this;
    }

    public Builder error(String error) {
      this.error = error;
      return this;
    }

    public LogFileInfo build() {
      return new LogFileInfo(appenderName, type, filePath, sizeBytes, lastModified, readable, filePattern, rolledFiles,
          timestampPattern, timestampFormatter, error);
    }
  }

  /**
   * Create a basic LogFileInfo for a FileAppender.
   */
  public static LogFileInfo forFileAppender(String appenderName, String filePath, boolean readable) {
    return builder().appenderName(appenderName).type("FileAppender").filePath(filePath).readable(readable).build();
  }

  /**
   * Create a basic LogFileInfo for a RollingFileAppender.
   */
  public static LogFileInfo forRollingFileAppender(String appenderName, String filePath, String filePattern,
      boolean readable) {
    return builder().appenderName(appenderName).type("RollingFileAppender").filePath(filePath).filePattern(filePattern)
        .readable(readable).build();
  }

  /**
   * Create an error LogFileInfo when file cannot be accessed.
   */
  public static LogFileInfo withError(String appenderName, String type, String filePath, String error) {
    return builder().appenderName(appenderName).type(type).filePath(filePath).readable(false).error(error).build();
  }

  /**
   * Create a copy with updated file metadata.
   */
  public LogFileInfo withMetadata(Long sizeBytes, Instant lastModified) {
    return builder().appenderName(appenderName).type(type).filePath(filePath).sizeBytes(sizeBytes)
        .lastModified(lastModified).readable(readable).filePattern(filePattern).rolledFiles(rolledFiles)
        .timestampPattern(timestampPattern).timestampFormatter(timestampFormatter).error(error).build();
  }

  /**
   * Create a copy with rolled files added.
   */
  public LogFileInfo withRolledFiles(List<Path> rolledFiles) {
    return builder().appenderName(appenderName).type(type).filePath(filePath).sizeBytes(sizeBytes)
        .lastModified(lastModified).readable(readable).filePattern(filePattern).rolledFiles(rolledFiles)
        .timestampPattern(timestampPattern).timestampFormatter(timestampFormatter).error(error).build();
  }

  /**
   * Create a copy with timestamp pattern added.
   */
  public LogFileInfo withTimestampPattern(String timestampPattern, DateTimeFormatter timestampFormatter) {
    return builder().appenderName(appenderName).type(type).filePath(filePath).sizeBytes(sizeBytes)
        .lastModified(lastModified).readable(readable).filePattern(filePattern).rolledFiles(rolledFiles)
        .timestampPattern(timestampPattern).timestampFormatter(timestampFormatter).error(error).build();
  }

  /**
   * Check if this is a rolling file appender.
   */
  public boolean isRolling() {
    return "RollingFileAppender".equals(type);
  }

  /**
   * Check if the file has an error.
   */
  public boolean hasError() {
    return error != null && !error.isBlank();
  }

  /**
   * Check if timestamp pattern is available for guaranteed parsing.
   */
  public boolean hasTimestampPattern() {
    return timestampPattern != null && timestampFormatter != null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("LogFileInfo{appenderName='").append(appenderName).append("'");
    sb.append(", type='").append(type).append("'");
    sb.append(", filePath='").append(filePath).append("'");

    if (sizeBytes != null) {
      sb.append(", sizeBytes=").append(sizeBytes);
    }
    if (lastModified != null) {
      sb.append(", lastModified=").append(lastModified);
    }

    sb.append(", readable=").append(readable);

    if (filePattern != null) {
      sb.append(", filePattern='").append(filePattern).append("'");
    }
    if (rolledFiles != null && !rolledFiles.isEmpty()) {
      sb.append(", rolledFiles=").append(rolledFiles.size()).append(" files");
    }
    if (timestampPattern != null) {
      sb.append(", timestampPattern='").append(timestampPattern).append("'");
    }
    if (error != null) {
      sb.append(", error='").append(error).append("'");
    }

    sb.append("}");
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    LogFileInfo that = (LogFileInfo) o;
    return readable == that.readable && Objects.equals(appenderName, that.appenderName)
        && Objects.equals(type, that.type) && Objects.equals(filePath, that.filePath)
        && Objects.equals(sizeBytes, that.sizeBytes) && Objects.equals(lastModified, that.lastModified)
        && Objects.equals(filePattern, that.filePattern) && Objects.equals(rolledFiles, that.rolledFiles)
        && Objects.equals(timestampPattern, that.timestampPattern) && Objects.equals(error, that.error);
    // Note: timestampFormatter not included in equals (formatters with same pattern
    // are functionally equal)
  }

  @Override
  public int hashCode() {
    return Objects.hash(appenderName, type, filePath, sizeBytes, lastModified, readable, filePattern, rolledFiles,
        timestampPattern, error);
    // Note: timestampFormatter not included in hashCode (not semantically relevant)
  }
}
