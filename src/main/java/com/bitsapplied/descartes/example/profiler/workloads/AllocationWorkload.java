package com.bitsapplied.descartes.example.profiler.workloads;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Memory allocation workload generator for profiler demonstrations.
 *
 * This class demonstrates various memory allocation patterns visible in
 * allocation profiles:
 *
 * - Large object allocation - shows in allocation flame graphs as wide sections
 * - String concatenation anti-patterns - demonstrates StringBuilder importance
 * - Collection churning - shows add/remove allocation overhead - Object
 * serialization - demonstrates deep allocation graphs - Stream API overhead -
 * shows intermediate collection allocations
 *
 * Use cases: - Identifying memory allocation hotspots with
 * profile_type=allocation - Finding memory leaks and excessive allocations -
 * Comparing allocation patterns before/after optimization
 */
public class AllocationWorkload {

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Random random = new Random(42);
  private volatile long totalAllocations = 0;

  /**
   * Starts continuous allocation workload in background thread.
   */
  public void startContinuousLoad() {
    if (running.getAndSet(true)) {
      return; // Already running
    }

    Thread allocThread = new Thread(() -> {
      System.out.println("🔄 AllocationWorkload started");
      while (running.get()) {
        try {
          // Mix different allocation patterns
          createLargeObjects(100);
          stringConcatenationAntipattern(500);
          collectionChurning(1000);
          objectSerialization(50);
          streamApiOperations(500);

          totalAllocations++;

          // Small pause to prevent memory saturation
          Thread.sleep(50);
        } catch (InterruptedException e) {
          break;
        } catch (Exception e) {
          System.err.println("Error in allocation workload: " + e.getMessage());
        }
      }
      System.out.println("⏸️  AllocationWorkload stopped (allocation cycles: " + totalAllocations + ")");
    }, "AllocationWorkload-Thread");

    allocThread.setDaemon(true);
    allocThread.start();
  }

  /**
   * Stops the continuous workload.
   */
  public void stop() {
    running.set(false);
  }

  /**
   * Creates many large objects to show allocation hotspots.
   *
   * Profile characteristics: - Shows as wide section in allocation flame graph -
   * High allocation rate in hotspot analysis - Demonstrates array allocation
   * overhead
   *
   * @param count Number of large objects to create
   */
  public void createLargeObjects(int count) {
    List<byte[]> largeObjects = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      // Allocate 100KB arrays
      byte[] largeArray = new byte[100 * 1024];
      random.nextBytes(largeArray); // Touch memory to prevent optimization
      largeObjects.add(largeArray);
    }

    // Objects will be GC'd when method exits
    // Simulate some processing
    processLargeObjects(largeObjects);
  }

  private volatile long totalBytesProcessed = 0;

  private void processLargeObjects(List<byte[]> objects) {
    // Calculate total size (forces JVM to keep references alive)
    long totalSize = 0;
    for (byte[] obj : objects) {
      totalSize += obj.length;
    }
    // Store result to prevent optimization
    totalBytesProcessed += totalSize;
  }

  /**
   * Gets total bytes processed (used to prevent dead code elimination).
   *
   * @return total bytes processed
   */
  public long getTotalBytesProcessed() {
    return totalBytesProcessed;
  }

  /**
   * String concatenation anti-pattern - demonstrates why StringBuilder matters.
   *
   * Profile characteristics: - Shows many String object allocations -
   * Demonstrates O(n²) allocation behavior - Visible difference between +
   * operator and StringBuilder
   *
   * @param iterations Number of concatenations
   * @return The concatenated string (just for completeness)
   */
  public String stringConcatenationAntipattern(int iterations) {
    // BAD: Using + operator in loop (creates many intermediate String objects)
    String result = "";
    for (int i = 0; i < iterations; i++) {
      result += "iteration_" + i + ";"; // Each += allocates new String
    }

    // This creates: iterations * ~2 String objects
    // Allocation profile will show this as hotspot
    return result;
  }

  /**
   * StringBuilder version - shows correct pattern (for comparison). In profiling,
   * this should show much lower allocation rate.
   *
   * @param iterations Number of concatenations
   * @return The concatenated string
   */
  public String stringConcatenationOptimized(int iterations) {
    // GOOD: Using StringBuilder (much fewer allocations)
    StringBuilder sb = new StringBuilder(iterations * 20); // Pre-size
    for (int i = 0; i < iterations; i++) {
      sb.append("iteration_").append(i).append(";");
    }
    return sb.toString();
  }

  /**
   * Collection churning - add/remove operations causing allocations.
   *
   * Profile characteristics: - Shows internal array resizing allocations -
   * Demonstrates collection growth overhead - Iterator object allocations
   *
   * @param operations Number of add/remove operations
   */
  public void collectionChurning(int operations) {
    // ArrayList without initial capacity - causes resizing
    List<DataObject> list = new ArrayList<>(); // No capacity hint

    // Add elements (causes multiple array reallocations)
    for (int i = 0; i < operations; i++) {
      list.add(new DataObject(i, "data_" + i));
    }

    // Remove every other element (causes array shifting)
    Iterator<DataObject> iterator = list.iterator();
    boolean remove = false;
    while (iterator.hasNext()) {
      iterator.next();
      if (remove) {
        iterator.remove(); // Allocates during removal
      }
      remove = !remove;
    }

    // Add more (causes more resizing)
    for (int i = 0; i < operations / 2; i++) {
      list.add(new DataObject(i + operations, "more_" + i));
    }

    // Map operations also allocate
    Map<Integer, DataObject> map = new HashMap<>();
    for (DataObject obj : list) {
      map.put(obj.id, obj); // Entry objects allocated
    }

    // ConcurrentHashMap has even more allocation overhead
    Map<Integer, DataObject> concurrentMap = new ConcurrentHashMap<>();
    concurrentMap.putAll(map); // More internal allocations
  }

  /**
   * Object serialization - shows deep allocation graphs.
   *
   * Profile characteristics: - Creates complex allocation call tree - Shows
   * serialization framework allocations - Demonstrates overhead of object
   * serialization
   *
   * @param count Number of objects to serialize
   */
  public void objectSerialization(int count) {
    List<ComplexObject> objects = new ArrayList<>();

    // Create complex object graph
    for (int i = 0; i < count; i++) {
      objects.add(new ComplexObject(i, "object_" + i, createNestedData(3), random.nextDouble()));
    }

    // Serialize each object (many allocations in serialization)
    for (ComplexObject obj : objects) {
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
          ObjectOutputStream oos = new ObjectOutputStream(baos)) {

        oos.writeObject(obj);
        byte[] serialized = baos.toByteArray();

        // Verify size (forces allocation to complete)
        if (serialized.length == 0) {
          throw new IOException("Empty serialization");
        }
      } catch (IOException e) {
        // Ignore errors in demo
      }
    }
  }

  private Map<String, Object> createNestedData(int depth) {
    Map<String, Object> map = new HashMap<>();

    if (depth > 0) {
      map.put("nested", createNestedData(depth - 1));
      map.put("values", Arrays.asList(random.nextInt(), random.nextDouble(), "data"));
    } else {
      map.put("leaf", random.nextInt());
    }

    return map;
  }

  /**
   * Stream API operations - shows intermediate collection allocations.
   *
   * Profile characteristics: - Shows Stream internal object allocations -
   * Demonstrates functional programming overhead - Iterator and Spliterator
   * allocations
   *
   * @param size Size of dataset to process
   */
  public void streamApiOperations(int size) {
    // Create list of numbers
    List<Integer> numbers = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      numbers.add(random.nextInt(1000));
    }

    // Multiple stream operations (each may allocate)
    List<String> result = numbers.stream().filter(n -> n % 2 == 0) // Predicate allocation
        .map(n -> n * 2) // Function allocation
        .sorted() // Comparator allocation
        .limit(size / 2) // Limit operation
        .map(n -> "Number: " + n) // String allocation
        .collect(Collectors.toList()); // Collector allocation

    // Parallel stream (even more allocations for ForkJoin)
    long sum = numbers.parallelStream().filter(n -> n > 100).mapToLong(Integer::longValue).sum();

    // Grouping (creates many Map entries)
    Map<Boolean, List<Integer>> grouped = numbers.stream().collect(Collectors.groupingBy(n -> n % 2 == 0));

    // Use results to prevent optimization
    totalBytesProcessed += result.size() + sum + grouped.size();
  }

  /**
   * Runs all allocation workloads once for testing.
   */
  public void runAllOnce() {
    System.out.println("Running all allocation workloads once...");

    long start = System.currentTimeMillis();

    System.out.println("  - Creating large objects (500)...");
    createLargeObjects(500);

    System.out.println("  - String concatenation anti-pattern (1000 iterations)...");
    stringConcatenationAntipattern(1000);

    System.out.println("  - Collection churning (5000 operations)...");
    collectionChurning(5000);

    System.out.println("  - Object serialization (200 objects)...");
    objectSerialization(200);

    System.out.println("  - Stream API operations (2000 elements)...");
    streamApiOperations(2000);

    long elapsed = System.currentTimeMillis() - start;
    System.out.println("All allocations completed in " + elapsed + "ms");

    // Suggest GC to clean up
    System.gc();
  }

  public long getTotalAllocations() {
    return totalAllocations;
  }

  public boolean isRunning() {
    return running.get();
  }

  // --- Inner classes for realistic object allocation ---

  /**
   * Simple data object for collection operations.
   */
  private static class DataObject {
    final int id;
    final String data;

    DataObject(int id, String data) {
      this.id = id;
      this.data = data;
    }

    @Override
    public int hashCode() {
      return id * 31 + (data != null ? data.hashCode() : 0);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (!(obj instanceof DataObject))
        return false;
      DataObject other = (DataObject) obj;
      return id == other.id && (data != null ? data.equals(other.data) : other.data == null);
    }
  }

  /**
   * Complex object for serialization testing.
   */
  private static class ComplexObject implements Serializable {
    private static final long serialVersionUID = 1L;

    final int id;
    final String name;
    final Map<String, Object> nestedData;
    final double value;

    ComplexObject(int id, String name, Map<String, Object> nestedData, double value) {
      this.id = id;
      this.name = name;
      this.nestedData = nestedData;
      this.value = value;
    }

    @Override
    public String toString() {
      return "ComplexObject{id=" + id + ", name='" + name + "', value=" + value + ", nestedDataSize="
          + (nestedData != null ? nestedData.size() : 0) + "}";
    }
  }
}
