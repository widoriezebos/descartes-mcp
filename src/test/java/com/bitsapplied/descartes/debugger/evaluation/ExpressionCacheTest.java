package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.codehaus.janino.ExpressionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for ExpressionCache.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Basic cache operations (get/put)</li>
 * <li>LRU eviction policy</li>
 * <li>Cache statistics</li>
 * <li>Thread safety</li>
 * <li>Clear operation</li>
 * </ul>
 */
public class ExpressionCacheTest {
  private static final Logger logger = LoggerFactory.getLogger(ExpressionCacheTest.class);

  private ExpressionCache cache;

  @BeforeEach
  public void setUp() {
    cache = new ExpressionCache(10); // Small size for testing eviction
  }

  /**
   * Tests basic cache put and get.
   */
  @Test
  public void testPutAndGet() {
    logger.info("Testing put and get...");

    ExpressionEvaluator evaluator = new ExpressionEvaluator();
    String key = "x + y";

    cache.put(key, evaluator);

    ExpressionEvaluator retrieved = cache.get(key);
    assertNotNull(retrieved);
    assertEquals(evaluator, retrieved);

    logger.info("Put and get test passed");
  }

  /**
   * Tests cache miss returns null.
   */
  @Test
  public void testCacheMiss() {
    logger.info("Testing cache miss...");

    ExpressionEvaluator result = cache.get("non-existent");
    assertNull(result);

    logger.info("Cache miss test passed");
  }

  /**
   * Tests cache size tracking.
   */
  @Test
  public void testCacheSize() {
    logger.info("Testing cache size...");

    assertEquals(0, cache.size());

    cache.put("expr1", new ExpressionEvaluator());
    assertEquals(1, cache.size());

    cache.put("expr2", new ExpressionEvaluator());
    assertEquals(2, cache.size());

    cache.put("expr3", new ExpressionEvaluator());
    assertEquals(3, cache.size());

    logger.info("Cache size test passed");
  }

  /**
   * Tests LRU eviction when cache is full.
   */
  @Test
  public void testLRUEviction() throws Exception {
    logger.info("Testing LRU eviction...");

    // Fill cache to max size (10)
    for (int i = 0; i < 10; i++) {
      cache.put("expr" + i, new ExpressionEvaluator());
    }

    assertEquals(10, cache.size());

    // Add one more - should evict expr0 (oldest)
    cache.put("expr10", new ExpressionEvaluator());

    assertEquals(10, cache.size());
    assertNull(cache.get("expr0"), "Oldest entry should be evicted");
    assertNotNull(cache.get("expr10"), "Newest entry should be present");

    logger.info("LRU eviction test passed");
  }

  /**
   * Tests LRU updates access order.
   */
  @Test
  public void testLRUAccessOrder() throws Exception {
    logger.info("Testing LRU access order...");

    // Fill cache
    for (int i = 0; i < 10; i++) {
      cache.put("expr" + i, new ExpressionEvaluator());
    }

    // Access expr0 - should move it to end of LRU list
    cache.get("expr0");

    // Add one more - should evict expr1 (now oldest after expr0 was accessed)
    cache.put("expr10", new ExpressionEvaluator());

    assertNotNull(cache.get("expr0"), "Recently accessed entry should not be evicted");
    assertNull(cache.get("expr1"), "Least recently used entry should be evicted");

    logger.info("LRU access order test passed");
  }

  /**
   * Tests clear operation.
   */
  @Test
  public void testClear() {
    logger.info("Testing clear...");

    cache.put("expr1", new ExpressionEvaluator());
    cache.put("expr2", new ExpressionEvaluator());
    cache.put("expr3", new ExpressionEvaluator());

    assertEquals(3, cache.size());

    cache.clear();

    assertEquals(0, cache.size());
    assertNull(cache.get("expr1"));
    assertNull(cache.get("expr2"));
    assertNull(cache.get("expr3"));

    logger.info("Clear test passed");
  }

  /**
   * Tests getting max size.
   */
  @Test
  public void testGetMaxSize() {
    logger.info("Testing get max size...");

    assertEquals(10, cache.getMaxSize());

    ExpressionCache largerCache = new ExpressionCache(100);
    assertEquals(100, largerCache.getMaxSize());

    logger.info("Get max size test passed");
  }

  /**
   * Tests default constructor creates cache with size 100.
   */
  @Test
  public void testDefaultConstructor() {
    logger.info("Testing default constructor...");

    ExpressionCache defaultCache = new ExpressionCache();
    assertEquals(100, defaultCache.getMaxSize());

    logger.info("Default constructor test passed");
  }

  /**
   * Tests cache statistics.
   */
  @Test
  public void testGetStats() {
    logger.info("Testing get stats...");

    cache.put("expr1", new ExpressionEvaluator());
    cache.put("expr2", new ExpressionEvaluator());
    cache.put("expr3", new ExpressionEvaluator());

    Map<String, Object> stats = cache.getStats();

    assertNotNull(stats);
    assertEquals(3, stats.get("size"));
    assertEquals(10, stats.get("max_size"));

    double usagePercent = (double) stats.get("usage_percent");
    assertEquals(30.0, usagePercent, 0.01); // 3/10 * 100 = 30%

    logger.info("Get stats test passed");
  }

  /**
   * Tests cache is thread-safe for concurrent access.
   */
  @Test
  public void testThreadSafety() throws Exception {
    logger.info("Testing thread safety...");

    int threadCount = 10;
    int operationsPerThread = 100;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    List<Throwable> errors = new ArrayList<>();

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(() -> {
        try {
          for (int i = 0; i < operationsPerThread; i++) {
            String key = "thread" + threadId + "_expr" + i;
            ExpressionEvaluator evaluator = new ExpressionEvaluator();

            // Put
            cache.put(key, evaluator);

            // Get
            cache.get(key);

            // Get size (synchronized operation)
            cache.size();
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    if (!errors.isEmpty()) {
      throw new AssertionError("Thread safety violations detected: " + errors.size() + " errors", errors.get(0));
    }

    logger.info("Thread safety test passed");
  }

  /**
   * Tests putting same key twice updates the entry.
   */
  @Test
  public void testPutSameKeyTwice() {
    logger.info("Testing put same key twice...");

    ExpressionEvaluator evaluator1 = new ExpressionEvaluator();
    ExpressionEvaluator evaluator2 = new ExpressionEvaluator();

    String key = "x + y";

    cache.put(key, evaluator1);
    assertEquals(1, cache.size());

    cache.put(key, evaluator2);
    assertEquals(1, cache.size(), "Size should remain 1 when same key is reused");

    ExpressionEvaluator retrieved = cache.get(key);
    assertEquals(evaluator2, retrieved, "Should retrieve the most recent evaluator");

    logger.info("Put same key twice test passed");
  }

  /**
   * Tests cache works correctly at boundary (max size).
   */
  @Test
  public void testCacheAtMaxSize() {
    logger.info("Testing cache at max size...");

    // Fill to exactly max size
    for (int i = 0; i < 10; i++) {
      cache.put("expr" + i, new ExpressionEvaluator());
    }

    assertEquals(10, cache.size());

    // All entries should be retrievable
    for (int i = 0; i < 10; i++) {
      assertNotNull(cache.get("expr" + i), "Entry expr" + i + " should be present");
    }

    logger.info("Cache at max size test passed");
  }

  /**
   * Tests usage percent calculation.
   */
  @Test
  public void testUsagePercent() {
    logger.info("Testing usage percent...");

    Map<String, Object> stats = cache.getStats();
    assertEquals(0.0, (double) stats.get("usage_percent"), 0.01);

    cache.put("expr1", new ExpressionEvaluator());
    stats = cache.getStats();
    assertEquals(10.0, (double) stats.get("usage_percent"), 0.01);

    for (int i = 2; i <= 10; i++) {
      cache.put("expr" + i, new ExpressionEvaluator());
    }

    stats = cache.getStats();
    assertEquals(100.0, (double) stats.get("usage_percent"), 0.01);

    logger.info("Usage percent test passed");
  }
}
