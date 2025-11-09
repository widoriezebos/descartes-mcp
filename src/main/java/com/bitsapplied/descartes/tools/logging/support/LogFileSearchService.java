package com.bitsapplied.descartes.tools.logging.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Service for searching and filtering log files with comprehensive operations.
 *
 * Provides bash-parity functionality for remote log analysis: - search: Pattern
 * matching with optional context (replaces grep) - count: Count matches without
 * returning content (bandwidth optimization) - tail/head: Get first/last N
 * lines - range: Extract line number range - time_range: Time-based filtering -
 * extract: Extract captured groups from patterns - between: Extract content
 * between markers - timeline: Time-series frequency analysis
 *
 * Features: - Multi-file support with auto-discovery - Multi-pattern search
 * (any/all modes) - Inverse matching (grep -v) - Smart context (showContext
 * flag) - Field extraction - Guaranteed timestamp parsing when available
 */
public class LogFileSearchService {

  private final LogFileDiscoveryService discoveryService;

  public LogFileSearchService() {
    this.discoveryService = new LogFileDiscoveryService();
  }

  /**
   * Execute a search based on SearchParams.
   *
   * @param params Search parameters
   * @return Search results
   */
  public SearchResult executeSearch(SearchParams params) {
    long startTime = System.currentTimeMillis();

    try {
      // Handle multi-file operations
      if (params.filePaths() != null && !params.filePaths().isEmpty()) {
        return executeMultiFileSearch(params, startTime);
      }
      if (params.filePattern() != null && !params.filePattern().isBlank()) {
        return executePatternSearch(params, startTime);
      }

      // Single file operations
      return switch (params.operation()) {
      case "search", "grep" -> search(params, startTime);
      case "count" -> count(params, startTime);
      case "tail" -> tail(params, startTime);
      case "head" -> head(params, startTime);
      case "range" -> range(params, startTime);
      case "time_range" -> timeRange(params, startTime);
      case "extract" -> extract(params, startTime);
      case "between" -> between(params, startTime);
      case "timeline" -> timeline(params, startTime);
      default -> SearchResult.error(params.operation(), params.filePath(), "Unknown operation: " + params.operation());
      };
    } catch (Exception e) {
      return SearchResult.error(params.operation(), params.filePath(), "Search failed: " + e.getMessage());
    }
  }

  /**
   * Execute search across multiple explicitly specified files.
   */
  private SearchResult executeMultiFileSearch(SearchParams params, long startTime) {
    Map<String, Object> fileResults = new LinkedHashMap<>();

    for (String filePath : params.filePaths()) {
      // Create single-file params
      SearchParams singleFileParams = SearchParams.builder().operation(params.operation()).filePath(filePath)
          .pattern(params.pattern()).patterns(params.patterns()).patternMode(params.patternMode())
          .caseInsensitive(params.caseInsensitive()).invertMatch(params.invertMatch()).showContext(params.showContext())
          .contextBefore(params.contextBefore()).contextAfter(params.contextAfter()).maxResults(params.maxResults())
          .countOnly(params.countOnly()).extractOnly(params.extractOnly()).unique(params.unique())
          .captureGroup(params.captureGroup()).levelFilter(params.levelFilter())
          .includeLineNumbers(params.includeLineNumbers()).build();

      SearchResult result = executeSearch(singleFileParams);
      fileResults.put(filePath, result);
    }

    long searchTime = System.currentTimeMillis() - startTime;
    return SearchResult.successMultiFile(params.operation(), fileResults, searchTime);
  }

  /**
   * Execute search with file pattern discovery.
   */
  private SearchResult executePatternSearch(SearchParams params, long startTime) {
    // Discover files matching pattern
    List<String> discoveredFiles;
    try {
      List<LogFileInfo> logFiles = discoveryService.discoverLogFiles();
      discoveredFiles = logFiles.stream().map(LogFileInfo::filePath)
          .filter(path -> matchesPattern(path, params.filePattern())).toList();

      if (discoveredFiles.isEmpty()) {
        return SearchResult.error(params.operation(), null, "No files found matching pattern: " + params.filePattern());
      }
    } catch (Exception e) {
      return SearchResult.error(params.operation(), null, "File discovery failed: " + e.getMessage());
    }

    // Execute search on discovered files
    SearchParams multiFileParams = SearchParams.builder().operation(params.operation()).filePaths(discoveredFiles)
        .pattern(params.pattern()).patterns(params.patterns()).patternMode(params.patternMode())
        .caseInsensitive(params.caseInsensitive()).invertMatch(params.invertMatch()).showContext(params.showContext())
        .contextBefore(params.contextBefore()).contextAfter(params.contextAfter()).maxResults(params.maxResults())
        .countOnly(params.countOnly()).extractOnly(params.extractOnly()).unique(params.unique())
        .captureGroup(params.captureGroup()).levelFilter(params.levelFilter())
        .includeLineNumbers(params.includeLineNumbers()).build();

    return executeMultiFileSearch(multiFileParams, startTime);
  }

  /**
   * Check if file path matches glob pattern.
   */
  private boolean matchesPattern(String filePath, String pattern) {
    String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
    return filePath.matches(regex);
  }

  /**
   * Search operation with pattern matching and optional context.
   */
  private SearchResult search(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());

    // Compile pattern(s)
    List<Pattern> patterns = compilePatterns(params);
    if (patterns.isEmpty()) {
      return SearchResult.error(params.operation(), params.filePath(), "No valid patterns provided");
    }

    // Apply smart context if requested
    int contextBefore = params.showContext() ? 3 : params.contextBefore();
    int contextAfter = params.showContext() ? 3 : params.contextAfter();

    // Get LogFileInfo for guaranteed timestamp parsing
    LogFileInfo logFileInfo = discoveryService.getLogFileInfo(params.filePath());
    String timestampPattern = logFileInfo != null ? logFileInfo.timestampPattern() : null;
    DateTimeFormatter formatter = logFileInfo != null ? logFileInfo.timestampFormatter() : null;

    List<MatchResult> matches = new ArrayList<>();
    Deque<String> contextBuffer = new ArrayDeque<>(contextBefore + 1);
    int lineNumber = 0;
    int linesSearched = 0;
    boolean truncated = false;

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      int afterContextRemaining = 0;
      MatchResult lastMatch = null;

      while ((line = reader.readLine()) != null) {
        lineNumber++;
        linesSearched++;

        // Parse line for filtering
        LogLineParser.ParsedLogLine parsed = timestampPattern != null && formatter != null
            ? LogLineParser.parse(line, timestampPattern, formatter)
            : LogLineParser.parse(line);

        // Apply level filter
        if (params.levelFilter() != null && !parsed.matchesLevel(params.levelFilter())) {
          continue;
        }

        // Check for pattern match
        boolean matchFound = matchesPatterns(line, patterns, params.patternMode());

        // Apply invertMatch (grep -v)
        if (params.invertMatch()) {
          matchFound = !matchFound;
        }

        if (matchFound) {
          // Create match with before context
          List<String> before = new ArrayList<>(contextBuffer);
          MatchResult match = new MatchResult(lineNumber, line, parsed.timestamp(), parsed.level(), parsed.logger(),
              before, new ArrayList<>());
          matches.add(match);
          lastMatch = match;
          afterContextRemaining = contextAfter;
          contextBuffer.clear();

          // Check max results
          if (matches.size() >= params.maxResults()) {
            truncated = true;
            break;
          }
        } else if (afterContextRemaining > 0) {
          // Collect after context for last match
          if (lastMatch != null) {
            lastMatch.contextAfter().add(line);
          }
          afterContextRemaining--;
        } else {
          // Update before context buffer
          contextBuffer.addLast(line);
          if (contextBuffer.size() > contextBefore) {
            contextBuffer.removeFirst();
          }
        }
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;

    if (matches.isEmpty()) {
      return SearchResult.noMatches(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
          searchTime);
    }

    return SearchResult.success(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
        matches, searchTime, truncated);
  }

  /**
   * Count operation - returns match count without content.
   */
  private SearchResult count(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());

    // Compile pattern(s)
    List<Pattern> patterns = compilePatterns(params);
    if (patterns.isEmpty()) {
      return SearchResult.error(params.operation(), params.filePath(), "No valid patterns provided");
    }

    long count = 0;
    int linesSearched = 0;

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        linesSearched++;

        boolean matchFound = matchesPatterns(line, patterns, params.patternMode());

        // Apply invertMatch
        if (params.invertMatch()) {
          matchFound = !matchFound;
        }

        if (matchFound) {
          count++;
        }
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;
    return SearchResult.successCount(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
        count, searchTime);
  }

  /**
   * Extract operation - extract captured groups from pattern matches.
   */
  private SearchResult extract(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());

    Pattern pattern;
    try {
      int flags = params.caseInsensitive() ? Pattern.CASE_INSENSITIVE : 0;
      pattern = Pattern.compile(params.pattern(), flags);
    } catch (PatternSyntaxException e) {
      return SearchResult.error(params.operation(), params.filePath(), "Invalid regex pattern: " + e.getMessage());
    }

    int captureGroup = params.captureGroup() != null ? params.captureGroup() : 1;
    List<String> extracted = new ArrayList<>();
    Set<String> uniqueValues = params.unique() ? new LinkedHashSet<>() : null;
    int linesSearched = 0;
    boolean truncated = false;

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        linesSearched++;

        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
          try {
            String value = matcher.group(captureGroup);
            if (value != null) {
              if (uniqueValues != null) {
                uniqueValues.add(value);
              } else {
                extracted.add(value);
              }

              if (extracted.size() >= params.maxResults()
                  || (uniqueValues != null && uniqueValues.size() >= params.maxResults())) {
                truncated = true;
                break;
              }
            }
          } catch (IndexOutOfBoundsException e) {
            // Capture group doesn't exist, continue
          }
        }
        if (truncated)
          break;
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;

    List<String> finalExtracted = uniqueValues != null ? new ArrayList<>(uniqueValues) : extracted;
    Long uniqueCount = uniqueValues != null ? (long) uniqueValues.size() : null;

    return SearchResult.successExtracted(params.operation(), params.filePath(), filePath.toFile().length(),
        linesSearched, finalExtracted, uniqueCount, searchTime, truncated);
  }

  /**
   * Between operation - extract content between start and end markers.
   */
  private SearchResult between(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());

    Pattern startPattern = Pattern.compile(params.startMarker());
    Pattern endPattern = Pattern.compile(params.endMarker());

    List<SearchResult.Section> sections = new ArrayList<>();
    List<String> currentSection = null;
    int sectionNumber = 0;
    int startLine = -1;
    int lineNumber = 0;
    int linesSearched = 0;
    boolean truncated = false;

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        linesSearched++;

        if (currentSection == null) {
          // Looking for start marker
          if (startPattern.matcher(line).find()) {
            currentSection = new ArrayList<>();
            startLine = lineNumber;
            sectionNumber++;
            if (params.includeMarkers()) {
              currentSection.add(line);
            }
          }
        } else {
          // Inside a section, looking for end marker
          if (endPattern.matcher(line).find()) {
            if (params.includeMarkers()) {
              currentSection.add(line);
            }

            // Section complete
            sections.add(new SearchResult.Section(sectionNumber, params.startMarker(), params.endMarker(),
                currentSection, startLine, lineNumber));

            currentSection = null;
            startLine = -1;

            // Check max sections
            if (params.maxSections() != null && sections.size() >= params.maxSections()) {
              truncated = true;
              break;
            }
          } else {
            // Content line
            currentSection.add(line);
          }
        }
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;

    if (sections.isEmpty()) {
      return SearchResult.noMatches(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
          searchTime);
    }

    return SearchResult.successSections(params.operation(), params.filePath(), filePath.toFile().length(),
        linesSearched, sections, searchTime, truncated);
  }

  /**
   * Timeline operation - time-series frequency analysis of matches.
   */
  private SearchResult timeline(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());

    Pattern pattern;
    try {
      int flags = params.caseInsensitive() ? Pattern.CASE_INSENSITIVE : 0;
      pattern = Pattern.compile(params.pattern(), flags);
    } catch (PatternSyntaxException e) {
      return SearchResult.error(params.operation(), params.filePath(), "Invalid regex pattern: " + e.getMessage());
    }

    // Parse bucket size
    long bucketSeconds = parseBucketSize(params.bucketSize());
    if (bucketSeconds <= 0) {
      return SearchResult.error(params.operation(), params.filePath(), "Invalid bucket size: " + params.bucketSize());
    }

    // Get LogFileInfo for timestamp parsing
    LogFileInfo logFileInfo = discoveryService.getLogFileInfo(params.filePath());
    String timestampPattern = logFileInfo != null ? logFileInfo.timestampPattern() : null;
    DateTimeFormatter formatter = logFileInfo != null ? logFileInfo.timestampFormatter() : null;

    Map<String, Long> timeline = new LinkedHashMap<>();
    int linesSearched = 0;
    Instant baseTime = params.startTime();

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        linesSearched++;

        if (pattern.matcher(line).find()) {
          // Parse timestamp
          LogLineParser.ParsedLogLine parsed = timestampPattern != null && formatter != null
              ? LogLineParser.parse(line, timestampPattern, formatter)
              : LogLineParser.parse(line);

          if (parsed.timestamp() != null) {
            if (baseTime == null) {
              baseTime = parsed.timestamp();
            }

            // Calculate bucket
            long secondsSinceBase = Duration.between(baseTime, parsed.timestamp()).getSeconds();
            long bucketNumber = secondsSinceBase / bucketSeconds;
            Instant bucketStart = baseTime.plusSeconds(bucketNumber * bucketSeconds);

            String bucketKey = bucketStart.toString();
            timeline.merge(bucketKey, 1L, Long::sum);
          }
        }
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;

    if (timeline.isEmpty()) {
      return SearchResult.noMatches(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
          searchTime);
    }

    return SearchResult.successTimeline(params.operation(), params.filePath(), filePath.toFile().length(),
        linesSearched, timeline, searchTime);
  }

  private SearchResult tail(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());
    List<String> lines = LogFileReader.tail(filePath, params.lines());

    long searchTime = System.currentTimeMillis() - startTime;
    return SearchResult.successLines(params.operation(), params.filePath(), filePath.toFile().length(), lines.size(),
        lines, searchTime);
  }

  private SearchResult head(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());
    List<String> lines = LogFileReader.head(filePath, params.lines());

    long searchTime = System.currentTimeMillis() - startTime;
    return SearchResult.successLines(params.operation(), params.filePath(), filePath.toFile().length(), lines.size(),
        lines, searchTime);
  }

  private SearchResult range(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());
    List<String> lines = new ArrayList<>();
    int lineNumber = 0;

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (lineNumber >= params.startLine() && lineNumber <= params.endLine()) {
          lines.add(line);
        }
        if (lineNumber > params.endLine()) {
          break;
        }
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;
    return SearchResult.successLines(params.operation(), params.filePath(), filePath.toFile().length(), lineNumber,
        lines, searchTime);
  }

  private SearchResult timeRange(SearchParams params, long startTime) throws IOException {
    Path filePath = Paths.get(params.filePath());
    List<MatchResult> matches = new ArrayList<>();
    int lineNumber = 0;
    int linesSearched = 0;

    // Parse since parameter if provided
    Instant actualStartTime = params.startTime();
    if (params.since() != null) {
      actualStartTime = Instant.now().minus(parseSinceDuration(params.since()));
    }

    // Get LogFileInfo for guaranteed timestamp parsing
    LogFileInfo logFileInfo = discoveryService.getLogFileInfo(params.filePath());
    String timestampPattern = logFileInfo != null ? logFileInfo.timestampPattern() : null;
    DateTimeFormatter formatter = logFileInfo != null ? logFileInfo.timestampFormatter() : null;

    try (BufferedReader reader = LogFileReader.open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        linesSearched++;

        // Use guaranteed parsing if available
        LogLineParser.ParsedLogLine parsed = timestampPattern != null && formatter != null
            ? LogLineParser.parse(line, timestampPattern, formatter)
            : LogLineParser.parse(line);

        if (parsed.inTimeRange(actualStartTime, params.endTime())) {
          MatchResult match = MatchResult.of(lineNumber, line, parsed.timestamp(), parsed.level(), parsed.logger());
          matches.add(match);

          if (matches.size() >= params.maxResults()) {
            break;
          }
        }
      }
    }

    long searchTime = System.currentTimeMillis() - startTime;

    if (matches.isEmpty()) {
      return SearchResult.noMatches(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
          searchTime);
    }

    return SearchResult.success(params.operation(), params.filePath(), filePath.toFile().length(), linesSearched,
        matches, searchTime, false);
  }

  /**
   * Compile patterns from params (handles both single and multi-pattern).
   */
  private List<Pattern> compilePatterns(SearchParams params) {
    List<Pattern> patterns = new ArrayList<>();
    int flags = params.caseInsensitive() ? Pattern.CASE_INSENSITIVE : 0;

    try {
      if (params.pattern() != null && !params.pattern().isBlank()) {
        patterns.add(Pattern.compile(params.pattern(), flags));
      }
      if (params.patterns() != null) {
        for (String pattern : params.patterns()) {
          if (pattern != null && !pattern.isBlank()) {
            patterns.add(Pattern.compile(pattern, flags));
          }
        }
      }
    } catch (PatternSyntaxException e) {
      // Return empty list on error
      return List.of();
    }

    return patterns;
  }

  /**
   * Check if line matches patterns according to pattern mode.
   */
  private boolean matchesPatterns(String line, List<Pattern> patterns, String mode) {
    if (patterns.isEmpty()) {
      return false;
    }

    if ("all".equals(mode)) {
      // All patterns must match
      return patterns.stream().allMatch(p -> p.matcher(line).find());
    } else {
      // Any pattern matches (default)
      return patterns.stream().anyMatch(p -> p.matcher(line).find());
    }
  }

  /**
   * Parse bucket size string (e.g., "5m", "1h", "30s").
   */
  private long parseBucketSize(String bucketSize) {
    if (bucketSize == null || bucketSize.isBlank()) {
      return -1;
    }

    bucketSize = bucketSize.toLowerCase().trim();
    try {
      if (bucketSize.endsWith("s")) {
        return Long.parseLong(bucketSize.substring(0, bucketSize.length() - 1));
      } else if (bucketSize.endsWith("m")) {
        return Long.parseLong(bucketSize.substring(0, bucketSize.length() - 1)) * 60;
      } else if (bucketSize.endsWith("h")) {
        return Long.parseLong(bucketSize.substring(0, bucketSize.length() - 1)) * 3600;
      } else if (bucketSize.endsWith("d")) {
        return Long.parseLong(bucketSize.substring(0, bucketSize.length() - 1)) * 86400;
      } else {
        return Long.parseLong(bucketSize);
      }
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Parse since duration string (e.g., "1h", "30m", "2d").
   */
  private Duration parseSinceDuration(String since) {
    if (since == null || since.isBlank()) {
      return Duration.ZERO;
    }

    since = since.toLowerCase().trim();
    try {
      if (since.endsWith("s")) {
        long seconds = Long.parseLong(since.substring(0, since.length() - 1));
        return Duration.ofSeconds(seconds);
      } else if (since.endsWith("m")) {
        long minutes = Long.parseLong(since.substring(0, since.length() - 1));
        return Duration.ofMinutes(minutes);
      } else if (since.endsWith("h")) {
        long hours = Long.parseLong(since.substring(0, since.length() - 1));
        return Duration.ofHours(hours);
      } else if (since.endsWith("d")) {
        long days = Long.parseLong(since.substring(0, since.length() - 1));
        return Duration.ofDays(days);
      } else {
        return Duration.ofSeconds(Long.parseLong(since));
      }
    } catch (NumberFormatException e) {
      return Duration.ZERO;
    }
  }
}
