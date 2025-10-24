package com.bitsapplied.descartes.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter for log recording to prevent log storms.
 * <p>
 * Uses a 1-second sliding window to enforce a maximum logs-per-second limit.
 * This prevents recording sessions from being overwhelmed by high-volume
 * logging.
 * <p>
 * Thread-safe using atomic operations and volatile fields.
 * <p>
 * <b>Runtime Reconfiguration:</b> The rate limit can be changed dynamically via
 * {@link #setMaxPerSecond(int)} without creating a new instance.
 * <p>
 * Example:
 *
 * <pre>
 * RateLimiter limiter = new RateLimiter(50); // Max 50 logs/second
 * if (limiter.allowLog()) {
 *   session.addInteraction(new LogInteraction(event));
 * }
 *
 * // Later, increase limit dynamically
 * limiter.setMaxPerSecond(200);
 * </pre>
 */
public class RateLimiter {

  private volatile int maxPerSecond; // Volatile for thread-safe runtime updates
  private final AtomicLong counter = new AtomicLong(0);
  private volatile long windowStart;

  /**
   * Creates a rate limiter.
   *
   * @param maxPerSecond maximum logs allowed per second (e.g., 50, 100, 1000)
   */
  public RateLimiter(int maxPerSecond) {
    this.maxPerSecond = maxPerSecond;
    this.windowStart = System.currentTimeMillis();
  }

  /**
   * Checks if a log should be allowed based on the rate limit.
   * <p>
   * Uses a 1-second sliding window. When the window resets, the counter resets to
   * 0.
   *
   * @return true if log should be allowed, false if rate limit exceeded
   */
  public boolean allowLog() {
    long now = System.currentTimeMillis();
    long elapsed = now - windowStart;

    // Reset window if 1 second has passed
    if (elapsed >= 1000) {
      synchronized (this) {
        // Double-check after acquiring lock
        if (now - windowStart >= 1000) {
          windowStart = now;
          counter.set(0);
        }
      }
    }

    // Increment and check
    long count = counter.incrementAndGet();
    return count <= maxPerSecond;
  }

  /**
   * Returns the current count in the active window.
   * <p>
   * Used for diagnostics and testing.
   *
   * @return current log count in active window
   */
  public long getCurrentCount() {
    return counter.get();
  }

  /**
   * Returns the maximum logs per second.
   *
   * @return the current rate limit
   */
  public int getMaxPerSecond() {
    return maxPerSecond; // Volatile read - thread-safe
  }

  /**
   * Atomically updates the rate limit.
   * <p>
   * Thread-safe: Uses volatile write which is visible to all threads immediately.
   * No synchronization needed for reads/writes of volatile fields.
   * <p>
   * Example:
   *
   * <pre>
   * // Dynamically increase limit for high-traffic scenarios
   * rateLimiter.setMaxPerSecond(1000);
   * </pre>
   *
   * @param newMax the new maximum logs per second (must be > 0)
   * @throws IllegalArgumentException if newMax <= 0
   */
  public void setMaxPerSecond(int newMax) {
    if (newMax <= 0) {
      throw new IllegalArgumentException("maxPerSecond must be > 0, got: " + newMax);
    }
    this.maxPerSecond = newMax; // Volatile write - visible to all threads
  }

  /**
   * Resets the rate limiter (for testing).
   */
  public void reset() {
    synchronized (this) {
      windowStart = System.currentTimeMillis();
      counter.set(0);
    }
  }

  @Override
  public String toString() {
    return "RateLimiter[maxPerSecond=" + maxPerSecond + ", currentCount=" + counter.get() + "]";
  }
}
