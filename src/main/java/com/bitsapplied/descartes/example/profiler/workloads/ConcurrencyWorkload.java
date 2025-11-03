package com.bitsapplied.descartes.example.profiler.workloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Concurrency and lock contention workload generator for profiler
 * demonstrations.
 *
 * This class demonstrates various concurrency patterns visible in comprehensive
 * profiles with lock contention events:
 *
 * - Synchronized method contention - shows as lock wait time in profiles -
 * ConcurrentHashMap operations - shows internal locking overhead -
 * Producer-consumer patterns - shows blocking queue wait times - ReadWriteLock
 * contention - demonstrates lock type differences - Deadlock scenarios -
 * detectable with thread analyzer
 *
 * Use cases: - Identifying lock contention with profile_type=comprehensive -
 * Finding synchronization bottlenecks - Comparing different concurrency
 * primitives - Thread analyzer deadlock detection
 *
 * Note: Lock contention events are only captured in comprehensive profiles.
 */
public class ConcurrencyWorkload {

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Random random = new Random(42);
  private final AtomicLong totalOperations = new AtomicLong(0);

  // Shared resources for contention
  private final Object sharedLock = new Object();
  private final Map<String, Integer> contentedMap = new ConcurrentHashMap<>();
  private final BlockingQueue<WorkItem> queue = new LinkedBlockingQueue<>(100);
  private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final Lock exclusiveLock = new ReentrantLock();

  private volatile int sharedCounter = 0;
  private volatile List<String> sharedData = new ArrayList<>();

  /**
   * Starts continuous concurrency workload with multiple threads. Creates
   * realistic lock contention scenarios.
   */
  public void startContinuousLoad() {
    if (running.getAndSet(true)) {
      return; // Already running
    }

    System.out.println("🔄 ConcurrencyWorkload started");

    // Start multiple worker threads competing for locks
    startContentedSynchronizedThreads(4);
    startConcurrentMapThreads(3);
    startProducerConsumerThreads(2, 3);
    startReadWriteLockThreads(5);
  }

  /**
   * Stops all concurrent workloads.
   */
  public void stop() {
    running.set(false);
    System.out.println("⏸️  ConcurrencyWorkload stopped (operations: " + totalOperations.get() + ")");
  }

  /**
   * Creates threads that compete for synchronized methods. Shows high lock
   * contention in profiles.
   */
  private void startContentedSynchronizedThreads(int threadCount) {
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread thread = new Thread(() -> {
        while (running.get()) {
          try {
            // All threads compete for same lock
            contentedSynchronizedMethod(threadId);
            totalOperations.incrementAndGet();
            Thread.sleep(5);
          } catch (InterruptedException e) {
            break;
          }
        }
      }, "Synchronized-Worker-" + i);
      thread.setDaemon(true);
      thread.start();
    }
  }

  /**
   * Synchronized method with significant work inside lock. ANTI-PATTERN: Shows
   * why long critical sections are bad.
   *
   * Profile characteristics: - Shows as lock contention in comprehensive profiles
   * - Thread analyzer will show BLOCKED threads - High wait time in lock analysis
   */
  private synchronized void contentedSynchronizedMethod(int threadId) {
    // Simulate work while holding lock (BAD practice)
    sharedCounter++;

    // Intentionally slow operation under lock
    try {
      Thread.sleep(10); // Exaggerates contention for demo
    } catch (InterruptedException e) {
      // Ignore
    }

    sharedData.add("Thread-" + threadId + ": " + sharedCounter);

    // More work under lock
    if (sharedData.size() > 1000) {
      sharedData.clear();
    }
  }

  /**
   * Better pattern: Minimal synchronized block. Shows reduced contention compared
   * to full method synchronization.
   *
   * This is a reference implementation demonstrating best practices.
   * Call demonstrateSynchronizationPatterns() to compare both approaches.
   */
  private void optimizedSynchronizedMethod(int threadId) {
    // Do non-critical work outside lock
    String data = "Thread-" + threadId + ": " + System.nanoTime();

    // Only synchronize critical section
    synchronized (sharedLock) {
      sharedCounter++;
      sharedData.add(data);

      if (sharedData.size() > 1000) {
        sharedData = new ArrayList<>(); // Replace instead of clear
      }
    }
  }

  /**
   * Creates threads performing concurrent map operations. Shows internal locking
   * overhead of ConcurrentHashMap.
   */
  private void startConcurrentMapThreads(int threadCount) {
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread thread = new Thread(() -> {
        while (running.get()) {
          try {
            concurrentMapOperations(threadId);
            totalOperations.incrementAndGet();
            Thread.sleep(10);
          } catch (InterruptedException e) {
            break;
          }
        }
      }, "ConcurrentMap-Worker-" + i);
      thread.setDaemon(true);
      thread.start();
    }
  }

  /**
   * ConcurrentHashMap operations under contention.
   *
   * Profile characteristics: - Shows CAS (Compare-And-Swap) operations - Internal
   * segment locking visible - Better than synchronized Map but still has overhead
   */
  private void concurrentMapOperations(int threadId) {
    String keyPrefix = "key_" + threadId + "_";

    // Put operations (may contend on same buckets)
    for (int i = 0; i < 10; i++) {
      contentedMap.put(keyPrefix + i, random.nextInt(1000));
    }

    // Compute operations (atomic but may contend)
    for (int i = 0; i < 10; i++) {
      contentedMap.compute(keyPrefix + i, (_, v) -> {
        // Simulate work in compute function
        int newValue = (v == null ? 0 : v) + 1;
        return newValue;
      });
    }

    // Get operations (mostly non-contending but visible in profile)
    for (int i = 0; i < 10; i++) {
      contentedMap.get(keyPrefix + random.nextInt(10));
    }

    // Clean up periodically
    if (random.nextInt(100) == 0) {
      contentedMap.clear();
    }
  }

  /**
   * Creates producer-consumer threads using BlockingQueue. Shows blocking wait
   * times in profiles.
   */
  private void startProducerConsumerThreads(int producers, int consumers) {
    // Producer threads
    for (int i = 0; i < producers; i++) {
      final int producerId = i;
      Thread thread = new Thread(() -> {
        while (running.get()) {
          try {
            WorkItem item = new WorkItem(producerId, random.nextInt(1000));
            queue.put(item); // Blocks if queue is full
            totalOperations.incrementAndGet();
            Thread.sleep(20);
          } catch (InterruptedException e) {
            break;
          }
        }
      }, "Producer-" + i);
      thread.setDaemon(true);
      thread.start();
    }

    // Consumer threads
    for (int i = 0; i < consumers; i++) {
      Thread thread = new Thread(() -> {
        while (running.get()) {
          try {
            WorkItem item = queue.poll(100, TimeUnit.MILLISECONDS);
            if (item != null) {
              processWorkItem(item);
              totalOperations.incrementAndGet();
            }
          } catch (InterruptedException e) {
            break;
          }
        }
      }, "Consumer-" + i);
      thread.setDaemon(true);
      thread.start();
    }
  }

  /**
   * Simulates work processing in consumer.
   */
  private void processWorkItem(WorkItem item) {
    // Simulate processing time
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      // Ignore
    }

    // Do some computation and store to prevent optimization
    int result = item.data * 2 + item.producerId;
    totalOperations.addAndGet(result > 0 ? 1 : 0);
  }

  /**
   * Creates threads using ReadWriteLock to show lock type differences. Read locks
   * allow concurrency, write locks are exclusive.
   */
  private void startReadWriteLockThreads(int threadCount) {
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread thread = new Thread(() -> {
        while (running.get()) {
          try {
            // Mostly reads (80%), some writes (20%)
            if (random.nextInt(100) < 80) {
              readOperation(threadId);
            } else {
              writeOperation(threadId);
            }
            totalOperations.incrementAndGet();
            Thread.sleep(10);
          } catch (InterruptedException e) {
            break;
          }
        }
      }, "ReadWrite-Worker-" + i);
      thread.setDaemon(true);
      thread.start();
    }
  }

  /**
   * Read operation using read lock. Multiple threads can hold read lock
   * simultaneously.
   *
   * Profile characteristics: - Low contention for read operations - Shows wait
   * time only when write lock is held
   */
  private void readOperation(int threadId) {
    rwLock.readLock().lock();
    try {
      // Simulate read operation
      int size = sharedData.size();
      if (size > 0) {
        String value = sharedData.get(random.nextInt(size));
        // Use value to prevent optimization
        if (value != null && value.contains("Thread")) {
          totalOperations.incrementAndGet();
        }
      }

      // Simulate processing time under read lock
      Thread.sleep(5);
    } catch (InterruptedException e) {
      // Ignore
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /**
   * Write operation using write lock. Exclusive - no other threads can hold read
   * or write lock.
   *
   * Profile characteristics: - High contention if write-heavy workload - Shows
   * exclusive lock wait time
   */
  private void writeOperation(int threadId) {
    rwLock.writeLock().lock();
    try {
      // Simulate write operation
      sharedData.add("Write-" + threadId + ": " + System.nanoTime());

      if (sharedData.size() > 500) {
        sharedData.clear();
      }

      // Simulate processing time under write lock
      Thread.sleep(10);
    } catch (InterruptedException e) {
      // Ignore
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * Demonstrates ReentrantLock with explicit lock/unlock. Shows as lock
   * contention in profiles, similar to synchronized.
   */
  public void reentrantLockOperation() {
    exclusiveLock.lock();
    try {
      // Critical section
      sharedCounter++;

      // Simulate work
      Thread.sleep(5);
    } catch (InterruptedException e) {
      // Ignore
    } finally {
      exclusiveLock.unlock();
    }
  }

  /**
   * Creates a potential deadlock scenario for thread analyzer detection. DO NOT
   * call during normal profiling - use only for deadlock testing.
   */
  public void createDeadlockScenario() {
    final Object lock1 = new Object();
    final Object lock2 = new Object();

    Thread thread1 = new Thread(() -> {
      synchronized (lock1) {
        System.out.println("Thread 1: Holding lock1...");
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
        }
        System.out.println("Thread 1: Waiting for lock2...");
        synchronized (lock2) {
          System.out.println("Thread 1: Acquired lock2");
        }
      }
    }, "Deadlock-Thread-1");

    Thread thread2 = new Thread(() -> {
      synchronized (lock2) {
        System.out.println("Thread 2: Holding lock2...");
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
        }
        System.out.println("Thread 2: Waiting for lock1...");
        synchronized (lock1) {
          System.out.println("Thread 2: Acquired lock1");
        }
      }
    }, "Deadlock-Thread-2");

    thread1.start();
    thread2.start();

    System.out.println("⚠️  Deadlock scenario created - use thread_analyzer to detect!");
  }

  /**
   * Demonstrates the difference between contended and optimized synchronization.
   * Useful for comparing profiling results between the two approaches.
   */
  public void demonstrateSynchronizationPatterns() {
    System.out.println("Demonstrating synchronization patterns:");
    System.out.println("1. Contended (full method synchronized)");
    for (int i = 0; i < 100; i++) {
      contentedSynchronizedMethod(i);
    }
    System.out.println("2. Optimized (minimal critical section)");
    for (int i = 0; i < 100; i++) {
      optimizedSynchronizedMethod(i);
    }
  }

  public long getTotalOperations() {
    return totalOperations.get();
  }

  public boolean isRunning() {
    return running.get();
  }

  /**
   * Simple work item for producer-consumer pattern.
   */
  private static class WorkItem {
    final int producerId;
    final int data;

    WorkItem(int producerId, int data) {
      this.producerId = producerId;
      this.data = data;
    }
  }
}
