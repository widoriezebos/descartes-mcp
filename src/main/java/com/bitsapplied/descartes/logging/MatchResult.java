package com.bitsapplied.descartes.logging;

import java.time.Instant;
import java.util.List;

/**
 * A single match from a log file search, including context lines.
 *
 * @param lineNumber    Line number in the file (1-indexed)
 * @param content       The matching line content
 * @param timestamp     Parsed timestamp from the log line (may be null if
 *                      parsing failed)
 * @param level         Log level (ERROR, WARN, INFO, etc.) or null if not
 *                      parsed
 * @param logger        Logger name or null if not parsed
 * @param contextBefore Lines appearing before the match
 * @param contextAfter  Lines appearing after the match
 */
public record MatchResult(int lineNumber, String content, Instant timestamp, String level, String logger,
    List<String> contextBefore, List<String> contextAfter) {
  /**
   * Compact constructor with defensive copying of lists.
   */
  public MatchResult {
    contextBefore = contextBefore != null ? List.copyOf(contextBefore) : List.of();
    contextAfter = contextAfter != null ? List.copyOf(contextAfter) : List.of();

    if (lineNumber < 0) {
      throw new IllegalArgumentException("Line number must be >= 0");
    }
    if (content == null) {
      throw new IllegalArgumentException("Content cannot be null");
    }
  }

  /**
   * Create a MatchResult with empty context lists.
   */
  public static MatchResult of(int lineNumber, String content) {
    return new MatchResult(lineNumber, content, null, null, null, List.of(), List.of());
  }

  /**
   * Create a MatchResult with parsed metadata but no context.
   */
  public static MatchResult of(int lineNumber, String content, Instant timestamp, String level, String logger) {
    return new MatchResult(lineNumber, content, timestamp, level, logger, List.of(), List.of());
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("MatchResult{lineNumber=").append(lineNumber);

    // Truncate long content for readability
    if (content.length() > 100) {
      sb.append(", content='").append(content, 0, 97).append("...'");
    } else {
      sb.append(", content='").append(content).append("'");
    }

    if (timestamp != null) {
      sb.append(", timestamp=").append(timestamp);
    }
    if (level != null) {
      sb.append(", level=").append(level);
    }
    if (logger != null) {
      sb.append(", logger=").append(logger);
    }

    sb.append(", contextBefore=").append(contextBefore.size()).append(" lines");
    sb.append(", contextAfter=").append(contextAfter.size()).append(" lines");
    sb.append("}");

    return sb.toString();
  }
}
