package com.bitsapplied.descartes.tools.logging.support;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves rolled log files from Log4j2 file patterns.
 *
 * Converts patterns like "/var/log/app-%d{yyyy-MM-dd}.log.gz" to glob patterns
 * and discovers matching files.
 */
public class RolledFileResolver {

  // Pattern to match Log4j2 date patterns like %d{yyyy-MM-dd}
  private static final Pattern DATE_PATTERN = Pattern.compile("%d(?:\\{([^}]+)\\})?");

  /**
   * Convert Log4j2 file pattern to glob pattern.
   *
   * Example: "/var/log/app-%d{yyyy-MM-dd}.log.gz" → "/var/log/app-*.log.gz"
   *
   * @param log4jPattern Log4j2 file pattern
   * @return Glob pattern
   */
  public static String toGlobPattern(String log4jPattern) {
    if (log4jPattern == null || log4jPattern.isBlank()) {
      return "";
    }

    // Replace Log4j2 date patterns with glob wildcards
    Matcher matcher = DATE_PATTERN.matcher(log4jPattern);
    String globPattern = matcher.replaceAll("*");

    // Replace other Log4j2 patterns
    globPattern = globPattern.replaceAll("%i", "*"); // Index pattern
    globPattern = globPattern.replaceAll("\\$\\{[^}]+\\}", "*"); // Property substitution

    return globPattern;
  }

  /**
   * Discover rolled files matching a Log4j2 pattern.
   *
   * @param filePattern Log4j2 file pattern
   * @return List of matching rolled files, sorted by modification time (newest
   *         first)
   * @throws IOException if directory cannot be read
   */
  public static List<Path> discoverRolledFiles(String filePattern) throws IOException {
    if (filePattern == null || filePattern.isBlank()) {
      return List.of();
    }

    String globPattern = toGlobPattern(filePattern);
    Path patternPath = Paths.get(globPattern);
    Path directory = patternPath.getParent();

    if (directory == null || !Files.exists(directory)) {
      return List.of();
    }

    if (!Files.isDirectory(directory)) {
      return List.of();
    }

    String fileGlob = patternPath.getFileName().toString();
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);

    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(path -> matcher.matches(path.getFileName())).filter(Files::isRegularFile).sorted((p1, p2) -> {
        try {
          // Sort by last modified time, newest first
          return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
        } catch (IOException e) {
          return 0;
        }
      }).collect(Collectors.toList());
    }
  }

  /**
   * Discover rolled files for a current log file, given the pattern.
   *
   * @param currentFile Current active log file
   * @param filePattern Pattern for rolled files
   * @return List of rolled files (excluding current file)
   * @throws IOException if discovery fails
   */
  public static List<Path> discoverRolledFilesExcludingCurrent(Path currentFile, String filePattern)
      throws IOException {
    List<Path> allFiles = discoverRolledFiles(filePattern);

    // Exclude the current active file
    return allFiles.stream().filter(path -> !path.equals(currentFile)).collect(Collectors.toList());
  }

  /**
   * Check if a file matches a Log4j2 pattern.
   *
   * @param filePath     File to check
   * @param log4jPattern Log4j2 pattern
   * @return true if file matches pattern
   */
  public static boolean matchesPattern(Path filePath, String log4jPattern) {
    String globPattern = toGlobPattern(log4jPattern);
    Path patternPath = Paths.get(globPattern);
    String fileGlob = patternPath.getFileName().toString();

    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
    return matcher.matches(filePath.getFileName());
  }
}
