package com.bitsapplied.descartes.util;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages multiple JShell sessions with automatic cleanup and resource limits.
 */
public final class JShellSessionManager implements AutoCloseable {

  private static final Logger log = LogManager.getLogger(JShellSessionManager.class);

  private final Map<String, Object> context;
  private final Map<String, JShellSession> sessions = new ConcurrentHashMap<>();
  private final ScheduledExecutorService cleanupExecutor;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicInteger maxSessions = new AtomicInteger(10); // Default max sessions
  private final AtomicInteger sessionTimeoutMinutes = new AtomicInteger(30); // Default timeout

  public JShellSessionManager(Map<String, Object> context) {
    this.context = Objects.requireNonNull(context, "context");

    // Extract settings from context if available
    Integer maxSessionsConfig = extractIntSetting("jshell.max_sessions");
    if (maxSessionsConfig != null) {
      this.maxSessions.set(maxSessionsConfig);
    }
    Integer timeoutConfig = extractIntSetting("jshell.session_timeout_minutes");
    if (timeoutConfig != null) {
      this.sessionTimeoutMinutes.set(timeoutConfig);
    }

    this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "JShellSessionManager-Cleanup");
      t.setDaemon(true);
      return t;
    });

    // Schedule cleanup every 5 minutes
    cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);
  }

  /**
   * Gets or creates a session with the given ID.
   */
  public synchronized JShellSession getOrCreateSession(String sessionId) {
    if (closed.get()) {
      throw new IllegalStateException("SessionManager is closed");
    }

    if (sessionId == null || sessionId.trim().isEmpty()) {
      sessionId = UUID.randomUUID().toString();
    }

    JShellSession session = sessions.get(sessionId);
    if (session != null) {
      return session;
    }

    // Check max sessions limit
    int maxSessionsLimit = maxSessions.get();
    if (sessions.size() >= maxSessionsLimit) {
      // Try to clean up expired sessions first
      cleanupExpiredSessions();

      if (sessions.size() >= maxSessionsLimit) {
        throw new IllegalStateException("Maximum number of JShell sessions exceeded. " + "Current sessions: "
            + sessions.size() + ", Maximum allowed: " + maxSessionsLimit + ". "
            + "Please close unused sessions before creating new ones.");
      }
    }

    session = new JShellSession(sessionId, context);
    sessions.put(sessionId, session);
    log.info("Created new JShell session: {}", sessionId);

    return session;
  }

  /**
   * Gets an existing session without creating a new one.
   */
  public JShellSession getSession(String sessionId) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
      return null;
    }
    return sessions.get(sessionId);
  }

  /**
   * Evaluates code in the specified session, creating it if necessary. Returns a
   * SessionEvalResult containing both the result and the session ID.
   */
  public SessionEvalResult eval(String sessionId, String code) {
    JShellSession session = getOrCreateSession(sessionId);
    EvalResult result = session.eval(code);
    return new SessionEvalResult(result, session.getSessionId());
  }

  /**
   * Resets (recreates) a session with the given ID.
   */
  public synchronized void resetSession(String sessionId) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
      return;
    }

    JShellSession oldSession = sessions.remove(sessionId);
    if (oldSession != null) {
      try {
        oldSession.close();
      } catch (Exception e) {
        log.warn("Error closing session {}: {}", sessionId, e.getMessage());
      }
    }

    // Create a new session with the same ID
    JShellSession newSession = new JShellSession(sessionId, context);
    sessions.put(sessionId, newSession);
    log.info("Reset JShell session: {}", sessionId);
  }

  /**
   * Closes and removes a specific session.
   */
  public synchronized void closeSession(String sessionId) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
      return;
    }

    JShellSession session = sessions.remove(sessionId);
    if (session != null) {
      try {
        session.close();
        log.info("Closed JShell session: {}", sessionId);
      } catch (Exception e) {
        log.warn("Error closing session {}: {}", sessionId, e.getMessage());
      }
    }
  }

  /**
   * Extends the expiry time for a specific session.
   *
   * @param sessionId     the session ID to extend
   * @param expiryMinutes expiry time in minutes from now, or null to use default
   *                      timeout
   * @return true if session was found and updated, false otherwise
   */
  public synchronized boolean extendSessionExpiry(String sessionId, Integer expiryMinutes) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
      return false;
    }

    JShellSession session = sessions.get(sessionId);
    if (session != null) {
      session.setCustomExpiryMinutes(expiryMinutes);
      log.info("Extended expiry for JShell session {} to {} minutes", sessionId,
          expiryMinutes != null ? expiryMinutes : "default");
      return true;
    }
    return false;
  }

  /**
   * Attempts to stop currently running evaluation in the specified session. This
   * is a best-effort operation - see JShellService.stop() for limitations.
   *
   * @param sessionId the session ID to stop
   * @return true if session was found and stop was attempted, false otherwise
   */
  public boolean stopSession(String sessionId) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
      return false;
    }

    JShellSession session = sessions.get(sessionId);
    if (session != null) {
      try {
        session.stop();
        log.info("Stop requested for JShell session: {}", sessionId);
        return true;
      } catch (Exception e) {
        log.warn("Error stopping session {}: {}", sessionId, e.getMessage());
        return false;
      }
    }
    return false;
  }

  /**
   * Cleans up expired sessions based on timeout setting.
   */
  private synchronized void cleanupExpiredSessions() {
    if (closed.get()) {
      return;
    }

    int timeoutMinutes = sessionTimeoutMinutes.get();

    Iterator<Map.Entry<String, JShellSession>> iter = sessions.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<String, JShellSession> entry = iter.next();
      JShellSession session = entry.getValue();

      if (session.isExpired(timeoutMinutes)) {
        try {
          session.close();
          iter.remove();
          log.info("Cleaned up expired JShell session: {}", entry.getKey());
        } catch (Exception e) {
          log.warn("Error cleaning up session {}: {}", entry.getKey(), e.getMessage());
        }
      }
    }
  }

  /**
   * Gets the number of active sessions.
   */
  public int getSessionCount() {
    return sessions.size();
  }

  /**
   * Gets the current maximum number of sessions allowed.
   */
  public int getMaxSessions() {
    return maxSessions.get();
  }

  /**
   * Dynamically sets the maximum number of sessions allowed.
   * 
   * @param maxSessionsValue the new maximum number of sessions
   */
  public synchronized void setMaxSessions(int maxSessionsValue) {
    if (maxSessionsValue <= 0) {
      throw new IllegalArgumentException("Max sessions must be positive, got: " + maxSessionsValue);
    }
    this.maxSessions.set(maxSessionsValue);
    log.info("Updated max JShell sessions to: {}", maxSessionsValue);
  }

  /**
   * Helper to extract integer settings from context.
   */
  private Integer extractIntSetting(String key) {
    Object value = context.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    } else if (value instanceof String) {
      try {
        return Integer.parseInt((String) value);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      cleanupExecutor.shutdownNow();

      for (Map.Entry<String, JShellSession> entry : sessions.entrySet()) {
        try {
          entry.getValue().close();
        } catch (Exception e) {
          log.warn("Error closing session {}: {}", entry.getKey(), e.getMessage());
        }
      }
      sessions.clear();

      log.info("JShellSessionManager closed");
    }
  }
}