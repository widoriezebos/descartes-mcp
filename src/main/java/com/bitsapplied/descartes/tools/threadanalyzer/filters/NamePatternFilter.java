package com.bitsapplied.descartes.tools.threadanalyzer.filters;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Filters threads by name using either regex pattern matching or substring
 * matching. Supports both "name_pattern" (regex) and "name_contains"
 * (substring) parameters.
 */
public class NamePatternFilter implements ThreadFilter {

  private final String parameterName;
  private final boolean isRegex;
  private final PatternCompiler patternCompiler;

  /**
   * Functional interface for compiling regex patterns. Allows injection of
   * safeCompilePattern method.
   */
  @FunctionalInterface
  public interface PatternCompiler {
    Pattern compile(String pattern, String paramName);
  }

  /**
   * Create a NamePatternFilter with the specified parameter name and type.
   *
   * @param parameterName   the parameter name to look for
   * @param isRegex         true if the parameter contains a regex pattern, false
   *                        for substring match
   * @param patternCompiler function to compile regex patterns safely
   */
  public NamePatternFilter(String parameterName, boolean isRegex, PatternCompiler patternCompiler) {
    this.parameterName = parameterName;
    this.isRegex = isRegex;
    this.patternCompiler = patternCompiler;
  }

  /**
   * Create a regex-based NamePatternFilter using "name_pattern" as the parameter
   * name.
   *
   * @param patternCompiler function to compile regex patterns safely
   */
  public NamePatternFilter(PatternCompiler patternCompiler) {
    this("name_pattern", true, patternCompiler);
  }

  @Override
  public boolean shouldApply(Map<String, Object> args) {
    String pattern = ParameterUtils.getString(args, parameterName, null);
    return pattern != null;
  }

  @Override
  public List<ThreadInfo> apply(List<ThreadInfo> threads, Map<String, Object> args) {
    String patternStr = ParameterUtils.getString(args, parameterName, null);
    if (patternStr == null) {
      return threads;
    }

    if (isRegex) {
      // Regex matching
      Pattern pattern = patternCompiler.compile(patternStr, parameterName);
      return threads.stream().filter(t -> pattern.matcher(t.getThreadName()).find()).collect(Collectors.toList());
    } else {
      // Substring matching
      return threads.stream().filter(t -> t.getThreadName().contains(patternStr)).collect(Collectors.toList());
    }
  }
}
