package com.bitsapplied.descartes.debugger.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.janino.ExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LRU cache for compiled expressions.
 *
 * <p>
 * Caches compiled Janino expressions to avoid recompilation overhead. Uses a
 * bounded LRU (Least Recently Used) eviction policy.
 *
 * <p>
 * Thread Safety: This class is thread-safe using synchronization.
 */
public class ExpressionCache {
  private static final Logger logger = LoggerFactory.getLogger(ExpressionCache.class);

  private final int maxSize;
  private final Map<String, ExpressionEvaluator> cache;

  /**
   * Creates an expression cache with default size (100).
   */
  public ExpressionCache() {
    this(100);
  }

  /**
   * Creates an expression cache with specified size.
   *
   * @param maxSize maximum number of cached expressions
   */
  public ExpressionCache(int maxSize) {
    this.maxSize = maxSize;
    this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<String, ExpressionEvaluator> eldest) {
        boolean shouldRemove = size() > ExpressionCache.this.maxSize;
        if (shouldRemove) {
          logger.trace("Evicting cached expression: {}", eldest.getKey());
        }
        return shouldRemove;
      }
    };
  }

  /**
   * Gets a cached expression evaluator.
   *
   * @param key the cache key
   * @return the evaluator, or null if not cached
   */
  public synchronized ExpressionEvaluator get(String key) {
    ExpressionEvaluator evaluator = cache.get(key);
    if (evaluator != null) {
      logger.trace("Cache hit for: {}", key);
    }
    return evaluator;
  }

  /**
   * Puts an expression evaluator in the cache.
   *
   * @param key       the cache key
   * @param evaluator the compiled evaluator
   */
  public synchronized void put(String key, ExpressionEvaluator evaluator) {
    cache.put(key, evaluator);
    logger.trace("Cached expression: {} (cache size: {})", key, cache.size());
  }

  /**
   * Clears all cached expressions.
   */
  public synchronized void clear() {
    int size = cache.size();
    cache.clear();
    logger.debug("Cleared {} cached expressions", size);
  }

  /**
   * Gets the current cache size.
   *
   * @return number of cached expressions
   */
  public synchronized int size() {
    return cache.size();
  }

  /**
   * Gets the maximum cache size.
   *
   * @return max size
   */
  public int getMaxSize() {
    return maxSize;
  }

  /**
   * Gets cache statistics.
   *
   * @return map of statistics
   */
  public synchronized Map<String, Object> getStats() {
    return Map.of("size", cache.size(), "max_size", maxSize, "usage_percent", (cache.size() * 100.0 / maxSize));
  }
}
