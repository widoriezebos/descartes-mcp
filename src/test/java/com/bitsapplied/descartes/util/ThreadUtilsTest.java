package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThreadUtils}.
 *
 * <p>
 * These tests verify cross-version compatibility of thread ID retrieval and
 * validate proper behavior across Java 16-23+.
 */
class ThreadUtilsTest {

  @Test
  void testGetThreadId_returnsPositiveId() {
    long threadId = ThreadUtils.getThreadId(Thread.currentThread());
    assertTrue(threadId > 0, "Thread ID should be positive, got: " + threadId);
  }

  @Test
  void testGetThreadId_consistentForSameThread() {
    Thread thread = Thread.currentThread();
    long id1 = ThreadUtils.getThreadId(thread);
    long id2 = ThreadUtils.getThreadId(thread);
    long id3 = ThreadUtils.getThreadId(thread);

    assertEquals(id1, id2, "Thread ID should be consistent across calls");
    assertEquals(id2, id3, "Thread ID should be consistent across calls");
  }

  @Test
  void testGetThreadId_mainThreadHasId() {
    // Main thread typically has ID 1, but this is not guaranteed
    // We just verify it returns a valid positive ID
    long mainThreadId = ThreadUtils.getThreadId(Thread.currentThread());
    assertTrue(mainThreadId > 0, "Main thread should have a positive ID");
  }

  @Test
  void testGetThreadId_differentThreadsHaveDifferentIds() throws InterruptedException {
    long mainThreadId = ThreadUtils.getThreadId(Thread.currentThread());
    AtomicLong workerThreadId = new AtomicLong(-1);
    CountDownLatch latch = new CountDownLatch(1);

    Thread worker = new Thread(() -> {
      workerThreadId.set(ThreadUtils.getThreadId(Thread.currentThread()));
      latch.countDown();
    });

    worker.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker thread should complete");
    worker.join(1000);

    long workerId = workerThreadId.get();
    assertTrue(workerId > 0, "Worker thread should have positive ID");
    assertNotEquals(mainThreadId, workerId,
        "Different threads should have different IDs: main=" + mainThreadId + ", worker=" + workerId);
  }

  @Test
  void testGetThreadId_multipleThreadsHaveUniqueIds() throws InterruptedException {
    final int threadCount = 10;
    Set<Long> threadIds = new HashSet<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    Thread[] threads = new Thread[threadCount];

    // Add main thread ID
    threadIds.add(ThreadUtils.getThreadId(Thread.currentThread()));

    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          long id = ThreadUtils.getThreadId(Thread.currentThread());
          synchronized (threadIds) {
            threadIds.add(id);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      });
      threads[i].start();
    }

    startLatch.countDown(); // Start all threads
    assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

    for (Thread thread : threads) {
      thread.join(1000);
    }

    // We should have threadCount + 1 unique IDs (including main thread)
    assertEquals(threadCount + 1, threadIds.size(), "All threads should have unique IDs. Found " + threadIds.size()
        + " unique IDs for " + (threadCount + 1) + " threads");
  }

  @Test
  void testGetThreadId_throwsNullPointerExceptionForNullThread() {
    assertThrows(NullPointerException.class, () -> {
      ThreadUtils.getThreadId(null);
    }, "getThreadId should throw NullPointerException for null thread");
  }

  @Test
  void testGetThreadId_worksForTerminatedThread() throws InterruptedException {
    AtomicLong capturedId = new AtomicLong(-1);
    Thread[] threadRef = new Thread[1];
    CountDownLatch latch = new CountDownLatch(1);

    Thread worker = new Thread(() -> {
      capturedId.set(ThreadUtils.getThreadId(Thread.currentThread()));
      latch.countDown();
    });

    threadRef[0] = worker;
    worker.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker thread should complete");
    worker.join(1000);

    // Thread is now terminated, but we should still be able to get its ID
    long idAfterTermination = ThreadUtils.getThreadId(threadRef[0]);
    assertEquals(capturedId.get(), idAfterTermination, "Thread ID should be accessible even after thread termination");
  }

  @Test
  void testGetThreadId_compatibleWithNativeThreadId() {
    // This test verifies that our utility returns the same value as the native
    // method
    // On Java 19+, we should get the same value from threadId()
    // On Java 16-18, we should get the same value from getId()
    Thread thread = Thread.currentThread();
    long utilId = ThreadUtils.getThreadId(thread);

    // Try to compare with the native method if available
    try {
      // Try Java 19+ method first
      long nativeId = thread.threadId();
      assertEquals(nativeId, utilId, "ThreadUtils.getThreadId should return same value as Thread.threadId()");
    } catch (NoSuchMethodError e) {
      // Java 16-18: compare with getId()
      @SuppressWarnings("deprecation")
      long nativeId = thread.getId();
      assertEquals(nativeId, utilId, "ThreadUtils.getThreadId should return same value as Thread.getId()");
    }
  }

  @Test
  void testGetThreadId_idIsMonotonicallyIncreasing() throws InterruptedException {
    // Thread IDs should increase monotonically (though not necessarily by 1)
    long id1 = ThreadUtils.getThreadId(Thread.currentThread());
    AtomicLong id2 = new AtomicLong(-1);
    AtomicLong id3 = new AtomicLong(-1);
    CountDownLatch latch = new CountDownLatch(2);

    Thread t1 = new Thread(() -> {
      id2.set(ThreadUtils.getThreadId(Thread.currentThread()));
      latch.countDown();
    });

    Thread t2 = new Thread(() -> {
      id3.set(ThreadUtils.getThreadId(Thread.currentThread()));
      latch.countDown();
    });

    t1.start();
    t2.start();

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should complete");
    t1.join(1000);
    t2.join(1000);

    // All IDs should be positive
    assertTrue(id1 > 0, "First thread ID should be positive");
    assertTrue(id2.get() > 0, "Second thread ID should be positive");
    assertTrue(id3.get() > 0, "Third thread ID should be positive");

    // IDs should be different (uniqueness)
    assertTrue(id2.get() != id1 && id3.get() != id1 && id2.get() != id3.get(), "All thread IDs should be unique");
  }
}
