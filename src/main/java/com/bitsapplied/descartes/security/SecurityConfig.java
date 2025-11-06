package com.bitsapplied.descartes.security;

/**
 * Base class for security configuration objects. Provides common security
 * settings that can be extended by specific resources/tools.
 *
 * <p>
 * This class is designed to be extended by resource-specific or tool-specific
 * security configurations (e.g., SystemPropertiesSecurityConfig).
 *
 * <p>
 * Immutable after construction for thread safety.
 */
public abstract class SecurityConfig {
  private final boolean enabled;
  private final boolean auditLogging;
  private final boolean strictMode;

  /**
   * Creates a security configuration with specified settings.
   *
   * @param enabled      Whether security enforcement is enabled
   * @param auditLogging Whether to log security-related events
   * @param strictMode   Whether to use strict (production-level) security
   */
  protected SecurityConfig(boolean enabled, boolean auditLogging, boolean strictMode) {
    this.enabled = enabled;
    this.auditLogging = auditLogging;
    this.strictMode = strictMode;
  }

  /**
   * Checks if security enforcement is enabled. When disabled, security checks are
   * bypassed (development/testing only).
   *
   * @return true if security is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Checks if audit logging is enabled. When enabled, security-relevant events
   * are logged for monitoring.
   *
   * @return true if audit logging is enabled
   */
  public boolean isAuditLogging() {
    return auditLogging;
  }

  /**
   * Checks if strict mode is enabled. Strict mode applies production-level
   * security restrictions: - Sensitive data access disabled - Allowlist-only
   * access - Enhanced filtering
   *
   * @return true if strict mode is enabled
   */
  public boolean isStrictMode() {
    return strictMode;
  }

  /**
   * Base builder for security configurations. Subclasses should extend this
   * builder to add their specific settings.
   *
   * @param <T> The type of SecurityConfig being built
   * @param <B> The type of the builder (for fluent API)
   */
  protected abstract static class Builder<T extends SecurityConfig, B extends Builder<T, B>> {
    public boolean enabled = true;
    public boolean auditLogging = false;
    public boolean strictMode = false;

    /**
     * Returns the builder instance (for fluent API).
     */
    protected abstract B self();

    /**
     * Builds the security configuration.
     */
    public abstract T build();

    /**
     * Sets whether security is enabled. Default: true
     */
    public B enabled(boolean enabled) {
      this.enabled = enabled;
      return self();
    }

    /**
     * Sets whether audit logging is enabled. Default: false
     */
    public B auditLogging(boolean auditLogging) {
      this.auditLogging = auditLogging;
      return self();
    }

    /**
     * Sets whether strict mode is enabled. Default: false
     */
    public B strictMode(boolean strictMode) {
      this.strictMode = strictMode;
      return self();
    }

    /**
     * Validates the configuration before building. Subclasses can override to add
     * validation.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    protected void validate() {
      // Base validation (can be overridden)
    }
  }
}
