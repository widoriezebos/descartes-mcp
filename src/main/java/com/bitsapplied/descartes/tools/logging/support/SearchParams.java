package com.bitsapplied.descartes.tools.logging.support;

import java.time.Instant;
import java.util.List;

/**
 * Parameters for log file search operations. Use the Builder or factory methods
 * to construct instances.
 *
 * Supported operations: - search: Pattern matching with context (replaces grep)
 * - count: Count matches without returning content - tail: Last N lines - head:
 * First N lines - range: Line number range - time_range: Time-based filtering -
 * extract: Extract capturing groups - between: Extract content between markers
 * - timeline: Time-series frequency analysis
 *
 * @param operation          Operation type
 * @param filePath           Single file path (null for multi-file operations)
 * @param filePaths          Multiple file paths for multi-file operations
 * @param filePattern        Glob pattern for file discovery
 * @param pattern            Single regex pattern to search for
 * @param patterns           Multiple patterns for multi-pattern search
 * @param patternMode        Pattern matching mode: "any" (OR) or "all" (AND)
 * @param caseInsensitive    Whether pattern matching should be case-insensitive
 * @param invertMatch        Exclude lines matching pattern (like grep -v)
 * @param showContext        Auto-add context lines (3 before/after)
 * @param contextBefore      Number of lines to include before each match
 * @param contextAfter       Number of lines to include after each match
 * @param maxResults         Maximum number of results to return (per file for
 *                           multi-file)
 * @param countOnly          Return only match count, not content
 * @param extractOnly        Extract only captured groups from pattern
 * @param unique             Deduplicate extracted values
 * @param captureGroup       Regex capture group number for extract operation
 * @param lines              Number of lines to retrieve (for tail/head
 *                           operations)
 * @param startLine          Starting line number (for range operations,
 *                           1-indexed)
 * @param endLine            Ending line number (for range operations,
 *                           inclusive)
 * @param startTime          Start of time range
 * @param endTime            End of time range
 * @param since              Relative time string (e.g., "1h", "30m", "2d")
 * @param levelFilter        Filter by log level (ERROR, WARN, INFO, DEBUG,
 *                           TRACE)
 * @param startMarker        Start boundary for between operation
 * @param endMarker          End boundary for between operation
 * @param includeMarkers     Include boundary lines in between results
 * @param maxSections        Maximum sections for between operation
 * @param bucketSize         Time bucket size for timeline (e.g., "5m", "1h")
 * @param delimiter          Field delimiter for field extraction
 * @param fields             Field indices to extract (0-based)
 * @param includeLineNumbers Whether to include line numbers in output
 */
public record SearchParams(String operation, String filePath, List<String> filePaths, String filePattern,
    String pattern, List<String> patterns, String patternMode, boolean caseInsensitive, boolean invertMatch,
    boolean showContext, int contextBefore, int contextAfter, int maxResults, boolean countOnly, boolean extractOnly,
    boolean unique, Integer captureGroup, Integer lines, Integer startLine, Integer endLine, Instant startTime,
    Instant endTime, String since, String levelFilter, String startMarker, String endMarker, boolean includeMarkers,
    Integer maxSections, String bucketSize, String delimiter, List<Integer> fields, boolean includeLineNumbers) {
  /**
   * Compact constructor with validation.
   */
  public SearchParams {
    if (operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("Operation cannot be null or blank");
    }

    // Validate file specification
    if ((filePath == null || filePath.isBlank()) && (filePaths == null || filePaths.isEmpty())
        && (filePattern == null || filePattern.isBlank())) {
      throw new IllegalArgumentException("Must specify filePath, filePaths, or filePattern");
    }

    if (contextBefore < 0) {
      throw new IllegalArgumentException("contextBefore must be >= 0");
    }
    if (contextAfter < 0) {
      throw new IllegalArgumentException("contextAfter must be >= 0");
    }
    if (maxResults <= 0) {
      throw new IllegalArgumentException("maxResults must be > 0");
    }

    // Validate operation-specific parameters
    switch (operation) {
    case "search", "grep":
      if ((pattern == null || pattern.isBlank()) && (patterns == null || patterns.isEmpty())) {
        throw new IllegalArgumentException("Pattern required for search operation");
      }
      break;
    case "count":
      if ((pattern == null || pattern.isBlank()) && (patterns == null || patterns.isEmpty())) {
        throw new IllegalArgumentException("Pattern required for count operation");
      }
      break;
    case "tail":
    case "head":
      if (lines == null || lines <= 0) {
        throw new IllegalArgumentException("lines must be > 0 for " + operation);
      }
      break;
    case "range":
      if (startLine == null || endLine == null) {
        throw new IllegalArgumentException("startLine and endLine required for range");
      }
      if (startLine < 1) {
        throw new IllegalArgumentException("startLine must be >= 1");
      }
      if (endLine < startLine) {
        throw new IllegalArgumentException("endLine must be >= startLine");
      }
      break;
    case "time_range":
      if (startTime == null && since == null) {
        throw new IllegalArgumentException("startTime or since required for time_range");
      }
      if (endTime == null) {
        throw new IllegalArgumentException("endTime required for time_range");
      }
      if (startTime != null && endTime.isBefore(startTime)) {
        throw new IllegalArgumentException("endTime must be after startTime");
      }
      break;
    case "extract":
      if (pattern == null || pattern.isBlank()) {
        throw new IllegalArgumentException("Pattern required for extract operation");
      }
      if (captureGroup != null && captureGroup < 0) {
        throw new IllegalArgumentException("captureGroup must be >= 0");
      }
      break;
    case "between":
      if (startMarker == null || startMarker.isBlank()) {
        throw new IllegalArgumentException("startMarker required for between operation");
      }
      if (endMarker == null || endMarker.isBlank()) {
        throw new IllegalArgumentException("endMarker required for between operation");
      }
      if (maxSections != null && maxSections <= 0) {
        throw new IllegalArgumentException("maxSections must be > 0");
      }
      break;
    case "timeline":
      if (pattern == null || pattern.isBlank()) {
        throw new IllegalArgumentException("Pattern required for timeline operation");
      }
      if (bucketSize == null || bucketSize.isBlank()) {
        throw new IllegalArgumentException("bucketSize required for timeline operation");
      }
      break;
    }

    // Validate pattern mode
    if (patternMode != null && !patternMode.equals("any") && !patternMode.equals("all")) {
      throw new IllegalArgumentException("patternMode must be 'any' or 'all'");
    }
  }

  /**
   * Builder for SearchParams.
   */
  public static class Builder {
    private String operation;
    private String filePath;
    private List<String> filePaths;
    private String filePattern;
    private String pattern;
    private List<String> patterns;
    private String patternMode = "any";
    private boolean caseInsensitive = false;
    private boolean invertMatch = false;
    private boolean showContext = false;
    private int contextBefore = 0;
    private int contextAfter = 0;
    private int maxResults = 1000;
    private boolean countOnly = false;
    private boolean extractOnly = false;
    private boolean unique = false;
    private Integer captureGroup;
    private Integer lines;
    private Integer startLine;
    private Integer endLine;
    private Instant startTime;
    private Instant endTime;
    private String since;
    private String levelFilter;
    private String startMarker;
    private String endMarker;
    private boolean includeMarkers = false;
    private Integer maxSections;
    private String bucketSize;
    private String delimiter;
    private List<Integer> fields;
    private boolean includeLineNumbers = true;

    public Builder operation(String operation) {
      this.operation = operation;
      return this;
    }

    public Builder filePath(String filePath) {
      this.filePath = filePath;
      return this;
    }

    public Builder filePaths(List<String> filePaths) {
      this.filePaths = filePaths;
      return this;
    }

    public Builder filePattern(String filePattern) {
      this.filePattern = filePattern;
      return this;
    }

    public Builder pattern(String pattern) {
      this.pattern = pattern;
      return this;
    }

    public Builder patterns(List<String> patterns) {
      this.patterns = patterns;
      return this;
    }

    public Builder patternMode(String patternMode) {
      this.patternMode = patternMode;
      return this;
    }

    public Builder caseInsensitive(boolean caseInsensitive) {
      this.caseInsensitive = caseInsensitive;
      return this;
    }

    public Builder invertMatch(boolean invertMatch) {
      this.invertMatch = invertMatch;
      return this;
    }

    public Builder showContext(boolean showContext) {
      this.showContext = showContext;
      return this;
    }

    public Builder contextBefore(int contextBefore) {
      this.contextBefore = contextBefore;
      return this;
    }

    public Builder contextAfter(int contextAfter) {
      this.contextAfter = contextAfter;
      return this;
    }

    public Builder maxResults(int maxResults) {
      this.maxResults = maxResults;
      return this;
    }

    public Builder countOnly(boolean countOnly) {
      this.countOnly = countOnly;
      return this;
    }

    public Builder extractOnly(boolean extractOnly) {
      this.extractOnly = extractOnly;
      return this;
    }

    public Builder unique(boolean unique) {
      this.unique = unique;
      return this;
    }

    public Builder captureGroup(Integer captureGroup) {
      this.captureGroup = captureGroup;
      return this;
    }

    public Builder lines(Integer lines) {
      this.lines = lines;
      return this;
    }

    public Builder startLine(Integer startLine) {
      this.startLine = startLine;
      return this;
    }

    public Builder endLine(Integer endLine) {
      this.endLine = endLine;
      return this;
    }

    public Builder startTime(Instant startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder endTime(Instant endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder since(String since) {
      this.since = since;
      return this;
    }

    public Builder levelFilter(String levelFilter) {
      this.levelFilter = levelFilter;
      return this;
    }

    public Builder startMarker(String startMarker) {
      this.startMarker = startMarker;
      return this;
    }

    public Builder endMarker(String endMarker) {
      this.endMarker = endMarker;
      return this;
    }

    public Builder includeMarkers(boolean includeMarkers) {
      this.includeMarkers = includeMarkers;
      return this;
    }

    public Builder maxSections(Integer maxSections) {
      this.maxSections = maxSections;
      return this;
    }

    public Builder bucketSize(String bucketSize) {
      this.bucketSize = bucketSize;
      return this;
    }

    public Builder delimiter(String delimiter) {
      this.delimiter = delimiter;
      return this;
    }

    public Builder fields(List<Integer> fields) {
      this.fields = fields;
      return this;
    }

    public Builder includeLineNumbers(boolean includeLineNumbers) {
      this.includeLineNumbers = includeLineNumbers;
      return this;
    }

    /**
     * Build the SearchParams instance with validation.
     */
    public SearchParams build() {
      return new SearchParams(operation, filePath, filePaths, filePattern, pattern, patterns, patternMode,
          caseInsensitive, invertMatch, showContext, contextBefore, contextAfter, maxResults, countOnly, extractOnly,
          unique, captureGroup, lines, startLine, endLine, startTime, endTime, since, levelFilter, startMarker,
          endMarker, includeMarkers, maxSections, bucketSize, delimiter, fields, includeLineNumbers);
    }
  }

  /**
   * Create a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Factory method for simple search (grep) operation.
   */
  public static SearchParams forSearch(String filePath, String pattern) {
    return builder().operation("search").filePath(filePath).pattern(pattern).build();
  }

  /**
   * Factory method for count-only operation.
   */
  public static SearchParams forCount(String filePath, String pattern) {
    return builder().operation("count").filePath(filePath).pattern(pattern).countOnly(true).build();
  }

  /**
   * Factory method for tail operation.
   */
  public static SearchParams forTail(String filePath, int lines) {
    return builder().operation("tail").filePath(filePath).lines(lines).build();
  }

  /**
   * Factory method for head operation.
   */
  public static SearchParams forHead(String filePath, int lines) {
    return builder().operation("head").filePath(filePath).lines(lines).build();
  }

  /**
   * Factory method for extract operation.
   */
  public static SearchParams forExtract(String filePath, String pattern, Integer captureGroup) {
    return builder().operation("extract").filePath(filePath).pattern(pattern).captureGroup(captureGroup)
        .extractOnly(true).build();
  }

  /**
   * Factory method for between operation.
   */
  public static SearchParams forBetween(String filePath, String startMarker, String endMarker) {
    return builder().operation("between").filePath(filePath).startMarker(startMarker).endMarker(endMarker).build();
  }

  /**
   * Factory method for timeline operation.
   */
  public static SearchParams forTimeline(String filePath, String pattern, String bucketSize) {
    return builder().operation("timeline").filePath(filePath).pattern(pattern).bucketSize(bucketSize).build();
  }

  /**
   * Factory method for multi-file search with auto-discovery.
   */
  public static SearchParams forMultiFileSearch(String pattern) {
    return builder().operation("search").filePattern("**/*.log").pattern(pattern).build();
  }
}
