package com.bitsapplied.descartes.example.profiler.workloads;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-intensive workload generator for profiler demonstrations.
 *
 * This class contains various computation-heavy methods that create distinct
 * performance signatures visible in CPU profiles and flame graphs:
 *
 * - Deep call stacks (recursive Fibonacci) - shows as tall flame graph sections
 * - Hot loops (prime generation) - shows as wide flame graph sections - Nested
 * loops (matrix multiplication) - shows multiple layers in call tree - Real
 * algorithms (sorting, hashing) - demonstrates realistic bottlenecks
 *
 * Use cases: - Identifying CPU hotspots with profiler_hotspots - Analyzing call
 * hierarchies with profiler_call_tree - Visualizing performance with flame
 * graphs
 */
public class ComputationWorkload {

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Random random = new Random(42); // Fixed seed for reproducibility
  private volatile long totalOperations = 0;

  /**
   * Starts continuous computation workload in background thread. Mix of different
   * computation types to create realistic profile.
   */
  public void startContinuousLoad() {
    if (running.getAndSet(true)) {
      return; // Already running
    }

    Thread computeThread = new Thread(() -> {
      System.out.println("🔄 ComputationWorkload started");
      while (running.get()) {
        try {
          // Mix different computation types for realistic profile
          recursiveFibonacci(25); // Deep call stack
          generatePrimes(10_000); // Hot loop
          matrixMultiplication(50); // Nested loops
          sortLargeDataset(5_000); // Real algorithm
          cryptographicHashing(1_000); // CPU-intensive operation

          totalOperations++;

          // Small pause to prevent CPU saturation
          Thread.sleep(10);
        } catch (InterruptedException e) {
          break;
        } catch (Exception e) {
          System.err.println("Error in computation workload: " + e.getMessage());
        }
      }
      System.out.println("⏸️  ComputationWorkload stopped (operations: " + totalOperations + ")");
    }, "ComputationWorkload-Thread");

    computeThread.setDaemon(true);
    computeThread.start();
  }

  /**
   * Stops the continuous workload.
   */
  public void stop() {
    running.set(false);
  }

  /**
   * Classic recursive Fibonacci - intentionally inefficient to show deep call
   * stacks.
   *
   * Profile characteristics: - Creates tall flame graph sections (deep recursion)
   * - Shows in call tree as deeply nested structure - Demonstrates exponential
   * time complexity O(2^n)
   *
   * @param n Fibonacci number to calculate (25-30 creates good profile signature)
   * @return The nth Fibonacci number
   */
  public long recursiveFibonacci(int n) {
    if (n <= 1) {
      return n;
    }
    // Intentionally inefficient - no memoization
    return recursiveFibonacci(n - 1) + recursiveFibonacci(n - 2);
  }

  /**
   * Prime number generation using trial division - shows as CPU hotspot.
   *
   * Profile characteristics: - Creates wide flame graph section (hot loop) - High
   * self-time percentage in hotspot analysis - Demonstrates O(n * sqrt(n))
   * complexity
   *
   * @param limit Generate primes up to this number
   * @return List of prime numbers
   */
  public List<Integer> generatePrimes(int limit) {
    List<Integer> primes = new ArrayList<>();

    for (int candidate = 2; candidate <= limit; candidate++) {
      if (isPrime(candidate)) {
        primes.add(candidate);
      }
    }

    return primes;
  }

  private boolean isPrime(int n) {
    if (n < 2)
      return false;
    if (n == 2)
      return true;
    if (n % 2 == 0)
      return false;

    // Trial division up to sqrt(n)
    int sqrt = (int) Math.sqrt(n);
    for (int i = 3; i <= sqrt; i += 2) {
      if (n % i == 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Matrix multiplication - shows nested loop structure in call tree.
   *
   * Profile characteristics: - Shows method call hierarchy clearly - Demonstrates
   * O(n^3) complexity - Creates moderate width flame graph section
   *
   * @param size Size of square matrices (50-100 creates good load)
   * @return Result matrix (just for completeness, not used)
   */
  public double[][] matrixMultiplication(int size) {
    double[][] a = createRandomMatrix(size);
    double[][] b = createRandomMatrix(size);
    double[][] result = new double[size][size];

    for (int i = 0; i < size; i++) {
      for (int j = 0; j < size; j++) {
        result[i][j] = multiplyCell(a, b, i, j, size);
      }
    }

    return result;
  }

  private double[][] createRandomMatrix(int size) {
    double[][] matrix = new double[size][size];
    for (int i = 0; i < size; i++) {
      for (int j = 0; j < size; j++) {
        matrix[i][j] = random.nextDouble();
      }
    }
    return matrix;
  }

  private double multiplyCell(double[][] a, double[][] b, int row, int col, int size) {
    double sum = 0;
    for (int k = 0; k < size; k++) {
      sum += a[row][k] * b[k][col];
    }
    return sum;
  }

  /**
   * Sorts a large random dataset - real-world algorithm performance.
   *
   * Profile characteristics: - Shows Java standard library calls in flame graph -
   * Demonstrates realistic application workload - O(n log n) complexity
   *
   * @param size Number of elements to sort
   */
  public void sortLargeDataset(int size) {
    Integer[] data = new Integer[size];
    for (int i = 0; i < size; i++) {
      data[i] = random.nextInt(size * 10);
    }

    // Use Java's sort - will show as library call in profile
    Arrays.sort(data);

    // Additional processing to make it more realistic
    computeStatistics(data);
  }

  private void computeStatistics(Integer[] data) {
    // Mean
    double sum = 0;
    for (int value : data) {
      sum += value;
    }
    double mean = sum / data.length;

    // Standard deviation
    double variance = 0;
    for (int value : data) {
      double diff = value - mean;
      variance += diff * diff;
    }
    double stdDev = Math.sqrt(variance / data.length);

    // Result not used, just for computational load
    @SuppressWarnings("unused")
    double coefficient = stdDev / mean;
  }

  /**
   * CPU-intensive cryptographic hashing - shows native method calls.
   *
   * Profile characteristics: - Shows JVM native methods in flame graph -
   * Demonstrates interaction with system libraries - Real-world CPU-intensive
   * operation
   *
   * @param iterations Number of hashing operations
   */
  public void cryptographicHashing(int iterations) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] data = new byte[1024];
      random.nextBytes(data);

      for (int i = 0; i < iterations; i++) {
        byte[] hash = digest.digest(data);
        // Use the hash as input for next iteration (chain hashing)
        System.arraycopy(hash, 0, data, 0, Math.min(hash.length, data.length));
      }
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  /**
   * Runs all computation methods once for testing. Useful for quick validation or
   * single-shot profiling.
   */
  public void runAllOnce() {
    System.out.println("Running all computation workloads once...");

    long start = System.currentTimeMillis();

    System.out.println("  - Fibonacci(30)...");
    recursiveFibonacci(30);

    System.out.println("  - Generate primes up to 50,000...");
    generatePrimes(50_000);

    System.out.println("  - Matrix multiplication (100x100)...");
    matrixMultiplication(100);

    System.out.println("  - Sort 20,000 elements...");
    sortLargeDataset(20_000);

    System.out.println("  - SHA-256 hashing (5,000 iterations)...");
    cryptographicHashing(5_000);

    long elapsed = System.currentTimeMillis() - start;
    System.out.println("✅ All computations completed in " + elapsed + "ms");
  }

  public long getTotalOperations() {
    return totalOperations;
  }

  public boolean isRunning() {
    return running.get();
  }
}
