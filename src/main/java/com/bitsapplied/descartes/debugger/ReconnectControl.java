package com.bitsapplied.descartes.debugger;

/**
 * Coordinates manual debugger session operations with proxy auto-reconnect.
 *
 * <p>
 * Manual start/stop operations should pause reconnect scheduling while in
 * progress so background health checks do not race against explicit user
 * actions.
 */
public interface ReconnectControl {

  /**
   * Pauses automatic reconnect attempts for a manual debugger operation.
   *
   * @param reason operation context for diagnostics
   */
  void pauseAutoReconnect(String reason);

  /**
   * Releases a previously acquired manual reconnect pause.
   *
   * @param reason operation context for diagnostics
   */
  void resumeAutoReconnect(String reason);

  /**
   * @return true if at least one manual operation currently holds reconnect
   *         pause
   */
  boolean isManualSessionInProgress();
}
