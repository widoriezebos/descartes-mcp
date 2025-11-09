package com.bitsapplied.descartes.tools.logging.support;

import java.util.List;
import java.util.Map;

/**
 * Results of a log file search operation.
 *
 * @param status        Status of the operation: "success", "error",
 *                      "no_matches"
 * @param operation     Operation type: search, count, tail, head, range,
 *                      time_range, extract, between, timeline
 * @param filePath      Path to the file that was searched (null for multi-file
 *                      operations)
 * @param fileSizeBytes Size of the file in bytes (may be null if unknown)
 * @param linesSearched Number of lines processed during search
 * @param matchesFound  Number of matches found
 * @param searchTimeMs  Time taken for the search in milliseconds
 * @param matches       List of matches with context (for search, time_range
 *                      operations)
 * @param lines         List of simple line strings (for tail, head, range
 *                      operations)
 * @param count         Match count (for count operation)
 * @param extracted     Extracted values (for extract operation)
 * @param uniqueCount   Number of unique extracted values (for extract with
 *                      unique=true)
 * @param sections      Extracted sections (for between operation)
 * @param timeline      Time-series match frequency (for timeline operation)
 * @param rows          Field extraction results (for field operations)
 * @param fileResults   Per-file results (for multi-file operations)
 * @param truncated     True if results were truncated due to max results limit
 * @param errorMessage  Error message if status is "error" (null otherwise)
 */
public record SearchResult(String status, String operation, String filePath, Long fileSizeBytes, Integer linesSearched,
    Integer matchesFound, Long searchTimeMs, List<MatchResult> matches, List<String> lines, Long count,
    List<String> extracted, Long uniqueCount, List<Section> sections, Map<String, Long> timeline,
    List<List<String>> rows, Map<String, Object> fileResults, boolean truncated, String errorMessage) {
  /**
   * Section of content extracted between markers.
   */
  public record Section(int sectionNumber, String startMarker, String endMarker, List<String> content, int startLine,
      int endLine) {
    public Section {
      content = content != null ? List.copyOf(content) : List.of();
    }
  }

  /**
   * Compact constructor with defensive copying and validation.
   */
  public SearchResult {
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("Status cannot be null or blank");
    }
    if (operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("Operation cannot be null or blank");
    }

    matches = matches != null ? List.copyOf(matches) : List.of();
    lines = lines != null ? List.copyOf(lines) : List.of();
    extracted = extracted != null ? List.copyOf(extracted) : List.of();
    sections = sections != null ? List.copyOf(sections) : List.of();
    timeline = timeline != null ? Map.copyOf(timeline) : Map.of();
    rows = rows != null ? List.copyOf(rows.stream().map(List::copyOf).toList()) : List.of();
    fileResults = fileResults != null ? Map.copyOf(fileResults) : Map.of();
  }

  /**
   * Create a builder for constructing SearchResult instances.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for SearchResult with fluent API.
   */
  public static class Builder {
    private String status;
    private String operation;
    private String filePath;
    private Long fileSizeBytes;
    private Integer linesSearched;
    private Integer matchesFound;
    private Long searchTimeMs;
    private List<MatchResult> matches;
    private List<String> lines;
    private Long count;
    private List<String> extracted;
    private Long uniqueCount;
    private List<Section> sections;
    private Map<String, Long> timeline;
    private List<List<String>> rows;
    private Map<String, Object> fileResults;
    private boolean truncated;
    private String errorMessage;

    public Builder status(String status) {
      this.status = status;
      return this;
    }

    public Builder operation(String operation) {
      this.operation = operation;
      return this;
    }

    public Builder filePath(String filePath) {
      this.filePath = filePath;
      return this;
    }

    public Builder fileSizeBytes(Long fileSizeBytes) {
      this.fileSizeBytes = fileSizeBytes;
      return this;
    }

    public Builder linesSearched(Integer linesSearched) {
      this.linesSearched = linesSearched;
      return this;
    }

    public Builder matchesFound(Integer matchesFound) {
      this.matchesFound = matchesFound;
      return this;
    }

    public Builder searchTimeMs(Long searchTimeMs) {
      this.searchTimeMs = searchTimeMs;
      return this;
    }

    public Builder matches(List<MatchResult> matches) {
      this.matches = matches;
      if (matches != null && this.matchesFound == null) {
        this.matchesFound = matches.size();
      }
      return this;
    }

    public Builder lines(List<String> lines) {
      this.lines = lines;
      if (lines != null && this.matchesFound == null) {
        this.matchesFound = lines.size();
      }
      return this;
    }

    public Builder count(Long count) {
      this.count = count;
      if (count != null && this.matchesFound == null) {
        this.matchesFound = count.intValue();
      }
      return this;
    }

    public Builder extracted(List<String> extracted) {
      this.extracted = extracted;
      if (extracted != null && this.matchesFound == null) {
        this.matchesFound = extracted.size();
      }
      return this;
    }

    public Builder uniqueCount(Long uniqueCount) {
      this.uniqueCount = uniqueCount;
      return this;
    }

    public Builder sections(List<Section> sections) {
      this.sections = sections;
      if (sections != null && this.matchesFound == null) {
        this.matchesFound = sections.size();
      }
      return this;
    }

    public Builder timeline(Map<String, Long> timeline) {
      this.timeline = timeline;
      if (timeline != null && this.matchesFound == null) {
        this.matchesFound = timeline.values().stream().mapToInt(Long::intValue).sum();
      }
      return this;
    }

    public Builder rows(List<List<String>> rows) {
      this.rows = rows;
      if (rows != null && this.matchesFound == null) {
        this.matchesFound = rows.size();
      }
      return this;
    }

    public Builder fileResults(Map<String, Object> fileResults) {
      this.fileResults = fileResults;
      if (fileResults != null && this.matchesFound == null) {
        this.matchesFound = fileResults.values().stream().filter(r -> r instanceof SearchResult)
            .mapToInt(r -> ((SearchResult) r).matchesFound != null ? ((SearchResult) r).matchesFound : 0).sum();
      }
      return this;
    }

    public Builder truncated(boolean truncated) {
      this.truncated = truncated;
      return this;
    }

    public Builder errorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    public SearchResult build() {
      return new SearchResult(status, operation, filePath, fileSizeBytes, linesSearched, matchesFound, searchTimeMs,
          matches, lines, count, extracted, uniqueCount, sections, timeline, rows, fileResults, truncated,
          errorMessage);
    }
  }

  /**
   * Create a successful search result with matches.
   */
  public static SearchResult success(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      List<MatchResult> matches, long searchTimeMs, boolean truncated) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).matches(matches).searchTimeMs(searchTimeMs).truncated(truncated).build();
  }

  /**
   * Create a successful tail/head/range result with simple lines.
   */
  public static SearchResult successLines(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      List<String> lines, long searchTimeMs) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).lines(lines).searchTimeMs(searchTimeMs).build();
  }

  /**
   * Create a successful count result.
   */
  public static SearchResult successCount(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      long count, long searchTimeMs) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).count(count).searchTimeMs(searchTimeMs).build();
  }

  /**
   * Create a successful extract result.
   */
  public static SearchResult successExtracted(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      List<String> extracted, Long uniqueCount, long searchTimeMs, boolean truncated) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).extracted(extracted).uniqueCount(uniqueCount).searchTimeMs(searchTimeMs)
        .truncated(truncated).build();
  }

  /**
   * Create a successful between result with sections.
   */
  public static SearchResult successSections(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      List<Section> sections, long searchTimeMs, boolean truncated) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).sections(sections).searchTimeMs(searchTimeMs).truncated(truncated).build();
  }

  /**
   * Create a successful timeline result.
   */
  public static SearchResult successTimeline(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      Map<String, Long> timeline, long searchTimeMs) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).timeline(timeline).searchTimeMs(searchTimeMs).build();
  }

  /**
   * Create a successful field extraction result.
   */
  public static SearchResult successRows(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      List<List<String>> rows, long searchTimeMs, boolean truncated) {
    return builder().status("success").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).rows(rows).searchTimeMs(searchTimeMs).truncated(truncated).build();
  }

  /**
   * Create a successful multi-file result.
   */
  public static SearchResult successMultiFile(String operation, Map<String, Object> fileResults, long searchTimeMs) {
    return builder().status("success").operation(operation).fileResults(fileResults).searchTimeMs(searchTimeMs).build();
  }

  /**
   * Create a no-matches result.
   */
  public static SearchResult noMatches(String operation, String filePath, Long fileSizeBytes, int linesSearched,
      long searchTimeMs) {
    return builder().status("no_matches").operation(operation).filePath(filePath).fileSizeBytes(fileSizeBytes)
        .linesSearched(linesSearched).matchesFound(0).searchTimeMs(searchTimeMs).build();
  }

  /**
   * Create an error result.
   */
  public static SearchResult error(String operation, String filePath, String errorMessage) {
    return builder().status("error").operation(operation).filePath(filePath).linesSearched(0).matchesFound(0)
        .searchTimeMs(0L).errorMessage(errorMessage).build();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("SearchResult{status='").append(status).append("'");
    sb.append(", operation='").append(operation).append("'");
    if (filePath != null) {
      sb.append(", filePath='").append(filePath).append("'");
    }

    if (fileSizeBytes != null) {
      sb.append(", fileSizeBytes=").append(fileSizeBytes);
    }
    if (linesSearched != null) {
      sb.append(", linesSearched=").append(linesSearched);
    }
    if (matchesFound != null) {
      sb.append(", matchesFound=").append(matchesFound);
    }
    if (searchTimeMs != null) {
      sb.append(", searchTimeMs=").append(searchTimeMs);
    }

    if (matches != null && !matches.isEmpty()) {
      sb.append(", matches=").append(matches.size()).append(" items");
    }
    if (lines != null && !lines.isEmpty()) {
      sb.append(", lines=").append(lines.size()).append(" items");
    }
    if (count != null) {
      sb.append(", count=").append(count);
    }
    if (extracted != null && !extracted.isEmpty()) {
      sb.append(", extracted=").append(extracted.size()).append(" items");
    }
    if (uniqueCount != null) {
      sb.append(", uniqueCount=").append(uniqueCount);
    }
    if (sections != null && !sections.isEmpty()) {
      sb.append(", sections=").append(sections.size()).append(" items");
    }
    if (timeline != null && !timeline.isEmpty()) {
      sb.append(", timeline=").append(timeline.size()).append(" buckets");
    }
    if (rows != null && !rows.isEmpty()) {
      sb.append(", rows=").append(rows.size()).append(" items");
    }
    if (fileResults != null && !fileResults.isEmpty()) {
      sb.append(", files=").append(fileResults.size());
    }

    if (truncated) {
      sb.append(", truncated=true");
    }
    if (errorMessage != null) {
      sb.append(", errorMessage='").append(errorMessage).append("'");
    }

    sb.append("}");
    return sb.toString();
  }
}
