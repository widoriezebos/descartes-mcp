package com.bitsapplied.descartes.resources;

import java.util.HashSet;
import java.util.Set;

import com.bitsapplied.descartes.security.SecurityConfig;
import com.bitsapplied.descartes.security.SensitiveDataFilter;

/**
 * Security configuration for SystemPropertiesResource. Controls access to
 * system properties, environment variables, and JVM information.
 *
 * <p>
 * Provides three preset configurations:
 * <ul>
 * <li><b>forDevelopment()</b> - Permissive settings for local development</li>
 * <li><b>forProduction()</b> - Restrictive settings for production
 * deployment</li>
 * <li><b>forTesting()</b> - Minimal restrictions for test environments</li>
 * </ul>
 *
 * <p>
 * Immutable after construction for thread safety.
 */
public class SystemPropertiesSecurityConfig extends SecurityConfig {
  private final boolean allowSensitiveAccess;
  private final Set<String> allowedKeys;
  private final Set<String> deniedKeys;
  private final Set<String> customSensitiveKeywords;
  private final SensitiveDataFilter filter;

  private SystemPropertiesSecurityConfig(Builder builder) {
    super(builder.enabled, builder.auditLogging, builder.strictMode);
    this.allowSensitiveAccess = builder.allowSensitiveAccess;
    this.allowedKeys = Set.copyOf(builder.allowedKeys);
    this.deniedKeys = Set.copyOf(builder.deniedKeys);
    this.customSensitiveKeywords = Set.copyOf(builder.customSensitiveKeywords);

    // Build the sensitive data filter
    Set<String> allSensitiveKeywords = new HashSet<>(SensitiveDataFilter.DEFAULT_SENSITIVE_KEYWORDS);
    allSensitiveKeywords.addAll(customSensitiveKeywords);

    this.filter = SensitiveDataFilter.builder().withSensitiveKeywords(allSensitiveKeywords).withAllowlist(allowedKeys)
        .withDenylist(deniedKeys).withAuditLogging(isAuditLogging()).build();
  }

  /**
   * Checks if access to sensitive properties is allowed. When false, the
   * includeSensitive parameter is ignored.
   *
   * @return true if sensitive access is allowed
   */
  public boolean isAllowSensitiveAccess() {
    return allowSensitiveAccess;
  }

  /**
   * Gets the set of explicitly allowed property/environment key patterns.
   * Supports wildcards: java.*, os.name, etc.
   *
   * @return Immutable set of allowed key patterns
   */
  public Set<String> getAllowedKeys() {
    return allowedKeys;
  }

  /**
   * Gets the set of explicitly denied property/environment key patterns. Supports
   * wildcards: AWS_*, *_PASSWORD, etc. Denylist takes precedence over allowlist.
   *
   * @return Immutable set of denied key patterns
   */
  public Set<String> getDeniedKeys() {
    return deniedKeys;
  }

  /**
   * Gets custom sensitive keywords added to the default set.
   *
   * @return Immutable set of custom sensitive keywords
   */
  public Set<String> getCustomSensitiveKeywords() {
    return customSensitiveKeywords;
  }

  /**
   * Gets the configured sensitive data filter.
   *
   * @return The SensitiveDataFilter instance
   */
  public SensitiveDataFilter getFilter() {
    return filter;
  }

  /**
   * Factory method for development environment configuration. Permissive settings
   * suitable for local development: - Sensitive access allowed - Audit logging
   * disabled - No strict mode - No allowlist/denylist restrictions
   *
   * @return Development configuration
   */
  public static SystemPropertiesSecurityConfig forDevelopment() {
    return builder().enabled(true).allowSensitiveAccess(true).auditLogging(false).strictMode(false).build();
  }

  /**
   * Factory method for production environment configuration. Restrictive settings
   * suitable for production deployment: - Sensitive access DISABLED - Audit
   * logging ENABLED - Strict mode ENABLED - Common sensitive patterns denied
   * (AWS_*, DB_*, *_PASSWORD, *_SECRET, *_TOKEN, *_KEY) - Only safe properties
   * allowed (java.version, java.vendor, os.name, os.version, os.arch)
   *
   * @return Production configuration
   */
  public static SystemPropertiesSecurityConfig forProduction() {
    return builder().enabled(true).allowSensitiveAccess(false) // No sensitive access in production
        .auditLogging(true) // Log all access attempts
        .strictMode(true) // Maximum security
        .denyKey("AWS_*").denyKey("DB_*").denyKey("*_PASSWORD").denyKey("*_SECRET").denyKey("*_TOKEN").denyKey("*_KEY")
        .denyKey("*_CREDENTIAL").denyKey("JDBC_*").denyKey("DATABASE_*")
        // Allow only basic system information
        .allowKey("java.version").allowKey("java.vendor").allowKey("java.vm.name").allowKey("os.name")
        .allowKey("os.version").allowKey("os.arch").allowKey("user.timezone").build();
  }

  /**
   * Factory method for testing environment configuration. Balanced settings for
   * automated testing: - Sensitive access allowed (tests may need to verify
   * filtering) - Audit logging disabled (reduces test noise) - No strict mode -
   * Minimal restrictions
   *
   * @return Testing configuration
   */
  public static SystemPropertiesSecurityConfig forTesting() {
    return builder().enabled(true).allowSensitiveAccess(true).auditLogging(false).strictMode(false).build();
  }

  /**
   * Creates a new builder for custom configuration.
   *
   * @return A new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for SystemPropertiesSecurityConfig. Provides fluent API for
   * configuration with sensible secure defaults.
   */
  public static class Builder extends SecurityConfig.Builder<SystemPropertiesSecurityConfig, Builder> {
    private boolean allowSensitiveAccess = false; // Secure default: deny sensitive access
    private final Set<String> allowedKeys = new HashSet<>();
    private final Set<String> deniedKeys = new HashSet<>();
    private final Set<String> customSensitiveKeywords = new HashSet<>();

    private Builder() {
    }

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets whether sensitive data access is allowed. Default: false (secure
     * default)
     *
     * @param allow true to allow sensitive access
     * @return this builder
     */
    public Builder allowSensitiveAccess(boolean allow) {
      this.allowSensitiveAccess = allow;
      return this;
    }

    /**
     * Adds a key pattern to the allowlist. Supports wildcards: java.*, os.name,
     * etc.
     *
     * @param pattern The pattern to allow
     * @return this builder
     */
    public Builder allowKey(String pattern) {
      if (pattern != null && !pattern.isEmpty()) {
        this.allowedKeys.add(pattern);
      }
      return this;
    }

    /**
     * Adds multiple key patterns to the allowlist.
     *
     * @param patterns The patterns to allow
     * @return this builder
     */
    public Builder allowKeys(Set<String> patterns) {
      if (patterns != null) {
        this.allowedKeys.addAll(patterns);
      }
      return this;
    }

    /**
     * Adds a key pattern to the denylist. Supports wildcards: AWS_*, *_PASSWORD,
     * etc. Denylist takes precedence over allowlist.
     *
     * @param pattern The pattern to deny
     * @return this builder
     */
    public Builder denyKey(String pattern) {
      if (pattern != null && !pattern.isEmpty()) {
        this.deniedKeys.add(pattern);
      }
      return this;
    }

    /**
     * Adds multiple key patterns to the denylist.
     *
     * @param patterns The patterns to deny
     * @return this builder
     */
    public Builder denyKeys(Set<String> patterns) {
      if (patterns != null) {
        this.deniedKeys.addAll(patterns);
      }
      return this;
    }

    /**
     * Adds a custom sensitive keyword. This is added to the default sensitive
     * keywords.
     *
     * @param keyword The keyword to add
     * @return this builder
     */
    public Builder addSensitiveKeyword(String keyword) {
      if (keyword != null && !keyword.isEmpty()) {
        this.customSensitiveKeywords.add(keyword);
      }
      return this;
    }

    /**
     * Adds multiple custom sensitive keywords.
     *
     * @param keywords The keywords to add
     * @return this builder
     */
    public Builder addSensitiveKeywords(Set<String> keywords) {
      if (keywords != null) {
        this.customSensitiveKeywords.addAll(keywords);
      }
      return this;
    }

    @Override
    protected void validate() {
      super.validate();

      // In strict mode, sensitive access must be disabled
      if (strictMode && allowSensitiveAccess) {
        throw new IllegalArgumentException("Cannot allow sensitive access in strict mode");
      }

      // Audit logging should be enabled in strict mode
      if (strictMode && !auditLogging) {
        // Auto-enable audit logging in strict mode
        this.auditLogging = true;
      }
    }

    @Override
    public SystemPropertiesSecurityConfig build() {
      validate();
      return new SystemPropertiesSecurityConfig(this);
    }
  }
}
