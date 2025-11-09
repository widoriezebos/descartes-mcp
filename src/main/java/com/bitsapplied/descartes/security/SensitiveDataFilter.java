package com.bitsapplied.descartes.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Reusable filter for detecting and filtering sensitive data keys. Supports
 * enhanced sensitive patterns, wildcard matching, allowlist/denylist with
 * priority rules, and audit logging.
 *
 * <p>
 * Priority order (highest to lowest):
 * <ol>
 * <li>Denylist match → Always filtered</li>
 * <li>Allowlist match → Allowed (unless on denylist)</li>
 * <li>Sensitive pattern match → Filtered</li>
 * <li>No match → Allowed</li>
 * </ol>
 *
 * <p>
 * Thread-safe for read operations after construction.
 */
public class SensitiveDataFilter {
  private static final Logger logger = Logger.getLogger(SensitiveDataFilter.class.getName());

  /**
   * Default sensitive keywords that trigger filtering. Includes common patterns
   * for credentials, tokens, and secrets.
   */
  public static final Set<String> DEFAULT_SENSITIVE_KEYWORDS = Set.of("password", "secret", "token", "key",
      "credential", "auth", "api", "access", "private", "cert", "certificate", "jwt", "session", "database", "db",
      "dsn", "connection", "url");

  private final Set<String> sensitiveKeywords;
  private final List<Pattern> allowlistPatterns;
  private final List<Pattern> denylistPatterns;
  private final boolean auditLogging;

  /**
   * Creates a filter with default sensitive keywords and no allowlist/denylist.
   */
  public SensitiveDataFilter() {
    this(DEFAULT_SENSITIVE_KEYWORDS, Set.of(), Set.of(), false);
  }

  /**
   * Creates a filter with custom configuration.
   *
   * @param sensitiveKeywords Keywords that indicate sensitive data
   *                          (case-insensitive)
   * @param allowlistPatterns Glob patterns to explicitly allow (e.g., "java.*",
   *                          "os.name")
   * @param denylistPatterns  Glob patterns to explicitly deny (e.g., "AWS_*",
   *                          "*_PASSWORD")
   * @param auditLogging      Whether to log when sensitive keys are accessed
   */
  public SensitiveDataFilter(Set<String> sensitiveKeywords, Set<String> allowlistPatterns, Set<String> denylistPatterns,
      boolean auditLogging) {
    this.sensitiveKeywords = new HashSet<>(sensitiveKeywords);
    this.allowlistPatterns = compilePatterns(allowlistPatterns);
    this.denylistPatterns = compilePatterns(denylistPatterns);
    this.auditLogging = auditLogging;
  }

  /**
   * Checks if a key should be filtered based on sensitivity rules.
   *
   * @param key The key to check
   * @return true if the key should be filtered (is sensitive or denied)
   */
  public boolean isSensitive(String key) {
    if (key == null) {
      return false;
    }

    // Priority 1: Denylist overrides everything
    if (matchesAnyPattern(key, denylistPatterns)) {
      if (auditLogging) {
        logger.info("Key denied by denylist: " + key);
      }
      return true;
    }

    // Priority 2: Allowlist overrides sensitivity check
    if (matchesAnyPattern(key, allowlistPatterns)) {
      if (auditLogging) {
        logger.fine("Key allowed by allowlist: " + key);
      }
      return false;
    }

    // Priority 3: Check against sensitive keywords
    boolean sensitive = containsSensitiveKeyword(key);
    if (sensitive && auditLogging) {
      logger.info("Key flagged as sensitive: " + key);
    }

    return sensitive;
  }

  /**
   * Checks if a key should be allowed (not filtered). Convenience method that
   * inverts isSensitive().
   *
   * @param key The key to check
   * @return true if the key should be allowed
   */
  public boolean isAllowed(String key) {
    return !isSensitive(key);
  }

  /**
   * Filters a value if its key is sensitive.
   *
   * @param key   The key associated with the value
   * @param value The value to potentially filter
   * @return The original value if allowed, otherwise a masked placeholder
   */
  public String filterValue(String key, String value) {
    return isSensitive(key) ? "***FILTERED***" : value;
  }

  /**
   * Logs an audit message when sensitive data is accessed. Only logs if audit
   * logging is enabled.
   *
   * @param key       The sensitive key that was accessed
   * @param operation The operation performed (e.g., "read", "expose")
   */
  public void auditAccess(String key, String operation) {
    if (auditLogging) {
      logger.warning(String.format("SECURITY AUDIT: Sensitive key '%s' accessed via %s", key, operation));
    }
  }

  /**
   * Checks if the key contains any sensitive keyword (case-insensitive).
   */
  private boolean containsSensitiveKeyword(String key) {
    String lowerKey = key.toLowerCase();
    return sensitiveKeywords.stream().anyMatch(lowerKey::contains);
  }

  /**
   * Checks if the key matches any of the given patterns.
   */
  private boolean matchesAnyPattern(String key, List<Pattern> patterns) {
    return patterns.stream().anyMatch(p -> p.matcher(key).matches());
  }

  /**
   * Compiles glob patterns into regex patterns. Supports wildcards: * (any
   * chars), ? (single char)
   */
  private List<Pattern> compilePatterns(Set<String> globPatterns) {
    List<Pattern> compiled = new ArrayList<>();
    for (String glob : globPatterns) {
      if (glob == null || glob.isEmpty()) {
        continue;
      }

      // Convert glob to regex
      String regex = globToRegex(glob);
      try {
        compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
      } catch (Exception e) {
        logger.warning("Failed to compile pattern '" + glob + "': " + e.getMessage());
      }
    }
    return compiled;
  }

  /**
   * Converts a glob pattern to a regex pattern. Supports: * (zero or more chars),
   * ? (exactly one char)
   */
  private String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
      case '*':
        regex.append(".*");
        break;
      case '?':
        regex.append(".");
        break;
      case '.':
      case '(':
      case ')':
      case '+':
      case '|':
      case '^':
      case '$':
      case '@':
      case '%':
      case '[':
      case ']':
      case '{':
      case '}':
      case '\\':
        // Escape regex special characters
        regex.append('\\').append(c);
        break;
      default:
        regex.append(c);
      }
    }
    regex.append("$");
    return regex.toString();
  }

  /**
   * Builder for creating SensitiveDataFilter instances.
   */
  public static class Builder {
    private Set<String> sensitiveKeywords = new HashSet<>(DEFAULT_SENSITIVE_KEYWORDS);
    private Set<String> allowlistPatterns = new HashSet<>();
    private Set<String> denylistPatterns = new HashSet<>();
    private boolean auditLogging = false;

    public Builder withSensitiveKeywords(Set<String> keywords) {
      this.sensitiveKeywords = new HashSet<>(keywords);
      return this;
    }

    public Builder addSensitiveKeyword(String keyword) {
      this.sensitiveKeywords.add(keyword);
      return this;
    }

    public Builder withAllowlist(Set<String> patterns) {
      this.allowlistPatterns = new HashSet<>(patterns);
      return this;
    }

    public Builder addAllowlistPattern(String pattern) {
      this.allowlistPatterns.add(pattern);
      return this;
    }

    public Builder withDenylist(Set<String> patterns) {
      this.denylistPatterns = new HashSet<>(patterns);
      return this;
    }

    public Builder addDenylistPattern(String pattern) {
      this.denylistPatterns.add(pattern);
      return this;
    }

    public Builder withAuditLogging(boolean enabled) {
      this.auditLogging = enabled;
      return this;
    }

    public SensitiveDataFilter build() {
      return new SensitiveDataFilter(sensitiveKeywords, allowlistPatterns, denylistPatterns, auditLogging);
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
