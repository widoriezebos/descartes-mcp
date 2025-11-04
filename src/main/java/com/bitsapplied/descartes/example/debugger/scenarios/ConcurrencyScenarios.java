package com.bitsapplied.descartes.example.debugger.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency scenarios for multi-threaded debugging.
 *
 * <p>
 * This class demonstrates debugger capabilities for:
 * <ul>
 * <li>Multi-threaded execution</li>
 * <li>Deadlock detection and analysis</li>
 * <li>Race condition debugging</li>
 * <li>Thread suspension and resumption</li>
 * <li>Synchronized block inspection</li>
 * </ul>
 *
 * <h3>Debugging Focus:</h3>
 * <ul>
 * <li>Thread listing and filtering</li>
 * <li>Thread suspension/resumption</li>
 * <li>Deadlock detection</li>
 * <li>Variable inspection in multi-threaded context</li>
 * <li>Breakpoints in concurrent code</li>
 * </ul>
 */
public class ConcurrencyScenarios {

  private volatile boolean keepRunning = true;

  /**
   * Simple multi-threaded execution.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Use debugger_threads to list all threads</li>
   * <li>Filter by name pattern: "Worker-.*"</li>
   * <li>Set breakpoint in worker loop</li>
   * <li>Suspend specific worker thread</li>
   * <li>Inspect variables in suspended thread</li>
   * <li>Resume thread and observe continuation</li>
   * </ul>
   */
  public void multipleThreads() throws InterruptedException {
    System.out.println("Starting multiple worker threads...");

    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      int workerId = i;
      Thread worker = new Thread(() -> {
        for (int j = 0; j < 5; j++) {
          System.out.println("Worker-" + workerId + " iteration " + j);
          try {
            Thread.sleep(100); // Breakpoint here
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }, "Worker-" + i);

      workers.add(worker);
      worker.start();
    }

    // Wait for all workers to complete
    for (Thread worker : workers) {
      worker.join(5000);
    }

    System.out.println("All workers completed");
  }

  /**
   * Deadlock scenario for detection and analysis.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Let this run for a few seconds</li>
   * <li>Use thread_analyzer operation="deadlocks"</li>
   * <li>Examine deadlock report showing circular wait</li>
   * <li>Inspect stack traces of deadlocked threads</li>
   * <li>Note which locks each thread holds and waits for</li>
   * </ul>
   *
   * <p>
   * <b>WARNING:</b> This method intentionally creates a deadlock! Call
   * {@link #stopDeadlock()} to interrupt the threads.
   */
  public void createDeadlock() throws InterruptedException {
    final Object lock1 = new Object();
    final Object lock2 = new Object();

    System.out.println("Creating intentional deadlock...");
    System.out.println("Use debugger to detect and analyze it!");

    Thread thread1 = new Thread(() -> {
      synchronized (lock1) {
        System.out.println("Thread-1: Holding lock1...");
        try {
          Thread.sleep(100); // Give thread2 time to get lock2
        } catch (InterruptedException e) {
          return;
        }

        System.out.println("Thread-1: Waiting for lock2...");
        synchronized (lock2) { // Will block here!
          System.out.println("Thread-1: Got both locks!");
        }
      }
    }, "DeadlockThread-1");

    Thread thread2 = new Thread(() -> {
      synchronized (lock2) {
        System.out.println("Thread-2: Holding lock2...");
        try {
          Thread.sleep(100); // Give thread1 time to get lock1
        } catch (InterruptedException e) {
          return;
        }

        System.out.println("Thread-2: Waiting for lock1...");
        synchronized (lock1) { // Will block here!
          System.out.println("Thread-2: Got both locks!");
        }
      }
    }, "DeadlockThread-2");

    thread1.start();
    thread2.start();

    // Give threads time to deadlock
    Thread.sleep(500);

    System.out.println("Deadlock should be established now.");
    System.out.println("Threads will remain deadlocked until interrupted.");

    // Store threads for cleanup
    deadlockedThreads.add(thread1);
    deadlockedThreads.add(thread2);
  }

  private final List<Thread> deadlockedThreads = new ArrayList<>();

  /**
   * Stop the deadlocked threads (if any).
   */
  public void stopDeadlock() {
    System.out.println("Interrupting deadlocked threads...");
    for (Thread thread : deadlockedThreads) {
      thread.interrupt();
    }
    deadlockedThreads.clear();
  }

  /**
   * Race condition with shared counter.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in increment operation</li>
   * <li>Suspend one thread while another modifies counter</li>
   * <li>Watch {@code counter} value change unexpectedly</li>
   * <li>Compare with fixed version using AtomicInteger</li>
   * <li>Use conditional breakpoint: {@code counter == 50}</li>
   * </ul>
   */
  public void raceCondition() throws InterruptedException {
    System.out.println("Demonstrating race condition...");

    final Counter unsafeCounter = new Counter();
    final AtomicInteger safeCounter = new AtomicInteger(0);

    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Thread thread = new Thread(() -> {
        for (int j = 0; j < 100; j++) {
          unsafeCounter.increment(); // UNSAFE - race condition
          safeCounter.incrementAndGet(); // SAFE - atomic
        }
      }, "RaceThread-" + i);

      threads.add(thread);
      thread.start();
    }

    // Wait for all threads
    for (Thread thread : threads) {
      thread.join();
    }

    System.out
        .println("Unsafe counter: " + unsafeCounter.getValue() + " (expected 500, likely less due to race condition)");
    System.out.println("Safe counter:   " + safeCounter.get() + " (expected 500, correctly counted)");
  }

  /**
   * Producer-Consumer pattern with wait/notify.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in {@code produce()} and {@code consume()}</li>
   * <li>Observe threads in WAITING state</li>
   * <li>Inspect queue contents when thread is suspended</li>
   * <li>Watch producer fill queue and wait</li>
   * <li>Watch consumer empty queue and wait</li>
   * </ul>
   */
  public void producerConsumer() throws InterruptedException {
    System.out.println("Starting producer-consumer...");

    final BlockingQueue<Integer> queue = new BlockingQueue<>(5);
    keepRunning = true;

    Thread producer = new Thread(() -> {
      int item = 0;
      while (keepRunning) {
        try {
          queue.produce(item++); // Breakpoint here
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }, "ProducerThread");

    Thread consumer = new Thread(() -> {
      while (keepRunning) {
        try {
          queue.consume(); // Breakpoint here
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }, "ConsumerThread");

    producer.start();
    consumer.start();

    // Let it run for a bit
    Thread.sleep(2000);

    // Stop threads
    keepRunning = false;
    producer.interrupt();
    consumer.interrupt();

    producer.join(1000);
    consumer.join(1000);

    System.out.println("Producer-consumer stopped");
  }

  /**
   * Run all concurrency scenarios (except deadlock).
   */
  public void runAllScenarios() throws InterruptedException {
    System.out.println("\n=== Concurrency Scenarios ===\n");

    System.out.println("1. Multiple Threads:");
    multipleThreads();

    System.out.println("\n2. Race Condition:");
    raceCondition();

    System.out.println("\n3. Producer-Consumer:");
    producerConsumer();

    System.out.println("\n=== Concurrency Scenarios Complete ===\n");
    System.out.println("NOTE: Deadlock scenario not run automatically.");
    System.out.println("To test deadlock detection, call createDeadlock() manually.");
  }

  // ============================================================================
  // Supporting classes
  // ============================================================================

  /**
   * Unsafe counter with race condition.
   */
  private static class Counter {
    private int value = 0;

    public void increment() {
      // Race condition: read-modify-write is not atomic
      int temp = value; // Read
      temp = temp + 1; // Modify - breakpoint here to see race
      value = temp; // Write
    }

    public int getValue() {
      return value;
    }
  }

  /**
   * Simple blocking queue for producer-consumer.
   */
  private static class BlockingQueue<T> {
    private final List<T> queue = new ArrayList<>();
    private final int maxSize;

    public BlockingQueue(int maxSize) {
      this.maxSize = maxSize;
    }

    public synchronized void produce(T item) throws InterruptedException {
      while (queue.size() >= maxSize) {
        System.out.println("Queue full, producer waiting...");
        wait(); // Wait for consumer - thread in WAITING state
      }

      queue.add(item);
      System.out.println("Produced: " + item + " (queue size: " + queue.size() + ")");
      notifyAll(); // Wake up consumers
    }

    public synchronized T consume() throws InterruptedException {
      while (queue.isEmpty()) {
        System.out.println("Queue empty, consumer waiting...");
        wait(); // Wait for producer - thread in WAITING state
      }

      T item = queue.remove(0);
      System.out.println("Consumed: " + item + " (queue size: " + queue.size() + ")");
      notifyAll(); // Wake up producers
      return item;
    }
  }
}
