package com.bitsapplied.descartes.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Filters log messages based on whitelist/blacklist patterns.
 * <p>
 * Supports wildcard patterns for logger names:
 * <ul>
 * <li>"com.bitsapplied.*" - all loggers in package and subpackages</li>
 * <li>"*.Brain" - all loggers ending with ".Brain"</li>
 * <li>"com.bitsapplied.*.core.*" - package wildcards</li>
 * </ul>
 * <p>
 * <b>Filtering logic:</b>
 * <ol>
 * <li>If blacklist matches, return false (exclude)</li>
 * <li>If whitelist is empty, return true (include all not blacklisted)</li>
 * <li>If whitelist matches, return true (include)</li>
 * <li>Otherwise, return false (exclude, not in whitelist)</li>
 * </ol>
 * <p>
 * <b>Thread Safety:</b> Uses ConcurrentHashMap.newKeySet() for thread-safe
 * pattern storage. Patterns can be added/removed at runtime via
 * {@link #addWhitelist(String)}, {@link #removeWhitelist(String)}, etc.
 */
public class LoggerFilter {

  private final Set<Pattern> whitelist = ConcurrentHashMap.newKeySet();
  private final Set<Pattern> blacklist = ConcurrentHashMap.newKeySet();

  /**
   * Creates a logger filter.
   *
   * @param whitelistPatterns comma-separated whitelist patterns (empty = allow
   *                          all)
   * @param blacklistPatterns comma-separated blacklist patterns (empty = block
   *                          none)
   */
  public LoggerFilter(String whitelistPatterns, String blacklistPatterns) {
    // Initialize sets by adding patterns (sets are already created as fields)
    this.whitelist.addAll(parsePatterns(whitelistPatterns));
    this.blacklist.addAll(parsePatterns(blacklistPatterns));
  }

  /**
   * Determines whether a logger should be recorded.
   *
   * @param loggerName fully qualified logger name
   * @return true if should be recorded, false otherwise
   */
  public boolean shouldRecord(String loggerName) {
    // 1. Check blacklist first - if matches, exclude
    if (matches(loggerName, blacklist)) {
      return false;
    }

    // 2. If whitelist is empty, allow all (not blacklisted)
    if (whitelist.isEmpty()) {
      return true;
    }

    // 3. Check whitelist - if matches, include
    if (matches(loggerName, whitelist)) {
      return true;
    }

    // 4. Not in whitelist, exclude
    return false;
  }

  /**
   * Checks if logger name matches any pattern in the set.
   */
  private boolean matches(String loggerName, Set<Pattern> patterns) {
    for (Pattern pattern : patterns) {
      if (pattern.matcher(loggerName).matches()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses comma-separated wildcard patterns into regex patterns.
   * <p>
   * Converts:
   * <ul>
   * <li>"*" to ".*" (match any characters)</li>
   * <li>"." to "\." (literal dot)</li>
   * </ul>
   */
  private Set<Pattern> parsePatterns(String commaSeparated) {
    Set<Pattern> patterns = new HashSet<>();
    if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
      return patterns;
    }

    String[] parts = commaSeparated.split(",");
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        // Convert wildcard pattern to regex
        // 1. Escape dots (literal package separators)
        String regex = trimmed.replace(".", "\\.");
        // 2. Convert * to .*
        regex = regex.replace("*", ".*");
        patterns.add(Pattern.compile(regex));
      }
    }

    return patterns;
  }

  /**
   * Returns the number of whitelist patterns.
   */
  public int getWhitelistSize() {
    return whitelist.size();
  }

  /**
   * Returns the number of blacklist patterns.
   */
  public int getBlacklistSize() {
    return blacklist.size();
  }

  // ========== Runtime Modification Methods (NEW) ==========

  /**
   * Adds a pattern to the whitelist at runtime.
   * <p>
   * Thread-safe: Uses ConcurrentHashMap.newKeySet().
   * <p>
   * Example: "com.bitsapplied.*"
   *
   * @param globPattern the wildcard pattern to add to whitelist
   */
  public void addWhitelist(String globPattern) {
    if (globPattern != null && !globPattern.trim().isEmpty()) {
      Pattern regex = globToRegex(globPattern.trim());
      whitelist.add(regex);
    }
  }

  /**
   * Removes a pattern from the whitelist.
   * <p>
   * Note: Removal is by pattern string match, not regex match.
   *
   * @param globPattern the wildcard pattern to remove
   */
  public void removeWhitelist(String globPattern) {
    if (globPattern != null) {
      Pattern regex = globToRegex(globPattern.trim());
      whitelist.removeIf(p -> p.pattern().equals(regex.pattern()));
    }
  }

  /**
   * Clears all whitelist patterns.
   */
  public void clearWhitelist() {
    whitelist.clear();
  }

  /**
   * Adds a pattern to the blacklist at runtime.
   * <p>
   * Thread-safe: Uses ConcurrentHashMap.newKeySet().
   * <p>
   * Example: "com.bitsapplied.descartes.core.recording.*"
   *
   * @param globPattern the wildcard pattern to add to blacklist
   */
  public void addBlacklist(String globPattern) {
    if (globPattern != null && !globPattern.trim().isEmpty()) {
      Pattern regex = globToRegex(globPattern.trim());
      blacklist.add(regex);
    }
  }

  /**
   * Removes a pattern from the blacklist.
   *
   * @param globPattern the wildcard pattern to remove
   */
  public void removeBlacklist(String globPattern) {
    if (globPattern != null) {
      Pattern regex = globToRegex(globPattern.trim());
      blacklist.removeIf(p -> p.pattern().equals(regex.pattern()));
    }
  }

  /**
   * Clears all blacklist patterns.
   */
  public void clearBlacklist() {
    blacklist.clear();
  }

  /**
   * Gets all whitelist patterns as a list of regex strings.
   * <p>
   * For UI display purposes. Returns the regex pattern strings, not the original
   * glob patterns.
   *
   * @return list of whitelist pattern strings
   */
  public List<String> getWhitelistPatterns() {
    List<String> patterns = new ArrayList<>();
    for (Pattern p : whitelist) {
      patterns.add(regexToGlob(p.pattern())); // Convert back to glob for display
    }
    return patterns;
  }

  /**
   * Gets all blacklist patterns as a list of regex strings.
   * <p>
   * For UI display purposes. Returns the regex pattern strings, not the original
   * glob patterns.
   *
   * @return list of blacklist pattern strings
   */
  public List<String> getBlacklistPatterns() {
    List<String> patterns = new ArrayList<>();
    for (Pattern p : blacklist) {
      patterns.add(regexToGlob(p.pattern())); // Convert back to glob for display
    }
    return patterns;
  }

  /**
   * Converts a glob pattern to a compiled regex Pattern.
   * <p>
   * Helper method extracted for reuse in runtime modification.
   *
   * @param globPattern wildcard pattern like "com.bitsapplied.*"
   * @return compiled regex pattern
   */
  private Pattern globToRegex(String globPattern) {
    // 1. Escape dots (literal package separators)
    String regex = globPattern.replace(".", "\\.");
    // 2. Convert * to .*
    regex = regex.replace("*", ".*");
    return Pattern.compile(regex);
  }

  /**
   * Converts a regex pattern back to a glob pattern for display.
   * <p>
   * This is a best-effort conversion. Assumes the regex was created from glob.
   *
   * @param regexPattern the regex pattern string
   * @return approximate glob pattern
   */
  private String regexToGlob(String regexPattern) {
    // Reverse the transformations
    String glob = regexPattern.replace(".*", "*");
    glob = glob.replace("\\.", ".");
    return glob;
  }

  @Override
  public String toString() {
    return "LoggerFilter[whitelist=" + whitelist.size() + " patterns, blacklist=" + blacklist.size() + " patterns]";
  }
}
