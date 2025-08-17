package com.bitsapplied.descartes.util;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wrapper for a JShell session with metadata tracking.
 */
public final class JShellSession implements AutoCloseable {

  private final String sessionId;
  private final JShellService jshellService;
  private final Instant createdAt;
  private volatile Instant lastAccessedAt;
  private volatile Integer customExpiryMinutes; // Custom expiry time in minutes, null means use default

  public JShellSession(Map<String, Object> context) {
    this(UUID.randomUUID().toString(), context);
  }

  public JShellSession(String sessionId, Map<String, Object> context) {
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.jshellService = new JShellService(Objects.requireNonNull(context, "context"));
    this.createdAt = Instant.now();
    this.lastAccessedAt = this.createdAt;
  }

  public String getSessionId() {
    return sessionId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastAccessedAt() {
    return lastAccessedAt;
  }

  /**
   * Gets the custom expiry time in minutes for this session.
   * 
   * @return custom expiry time in minutes, or null if using default timeout
   */
  public Integer getCustomExpiryMinutes() {
    return customExpiryMinutes;
  }

  /**
   * Sets a custom expiry time for this session.
   * 
   * @param expiryMinutes expiry time in minutes from now, or null to use default
   *                      timeout
   */
  public void setCustomExpiryMinutes(Integer expiryMinutes) {
    this.customExpiryMinutes = expiryMinutes;
  }

  public synchronized EvalResult eval(String code) {
    lastAccessedAt = Instant.now();
    return jshellService.eval(code);
  }

  public boolean isExpired(long defaultTimeoutMinutes) {
    // Use custom expiry time if set, otherwise use default
    long timeoutMinutes = customExpiryMinutes != null ? customExpiryMinutes : defaultTimeoutMinutes;
    return Instant.now().isAfter(lastAccessedAt.plusSeconds(timeoutMinutes * 60));
  }

  @Override
  public void close() {
    try {
      jshellService.close();
    } catch (Exception ignored) {
    }
  }
}