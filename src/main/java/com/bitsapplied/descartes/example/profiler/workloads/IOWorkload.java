package com.bitsapplied.descartes.example.profiler.workloads;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * I/O workload generator for profiler demonstrations.
 *
 * This class demonstrates various I/O patterns visible in comprehensive
 * profiles with I/O events enabled:
 *
 * - Buffered vs unbuffered file operations - shows I/O overhead differences -
 * NIO vs traditional I/O - demonstrates modern I/O API benefits - File system
 * operations - shows directory listing and metadata access - Compression I/O -
 * demonstrates CPU + I/O combined overhead
 *
 * Use cases: - Identifying I/O bottlenecks with profile_type=comprehensive -
 * Comparing different I/O approaches (buffered, NIO, etc.) - Finding excessive
 * file system operations - Analyzing I/O wait time vs CPU time
 *
 * Note: I/O events are only captured in comprehensive profiles. Uses temporary
 * directory for all operations (cleaned up on exit).
 */
public class IOWorkload {

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Random random = new Random(42);
  private final AtomicLong totalOperations = new AtomicLong(0);
  private final AtomicLong totalBytesProcessed = new AtomicLong(0);
  private final Path tempDir;

  public IOWorkload() {
    try {
      // Create temp directory for I/O operations
      tempDir = Files.createTempDirectory("descartes-io-workload-");
      tempDir.toFile().deleteOnExit();
      System.out.println("📁 IOWorkload using temp directory: " + tempDir);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create temp directory", e);
    }
  }

  /**
   * Starts continuous I/O workload in background thread.
   */
  public void startContinuousLoad() {
    if (running.getAndSet(true)) {
      return; // Already running
    }

    Thread ioThread = new Thread(() -> {
      System.out.println("🔄 IOWorkload started");
      while (running.get()) {
        try {
          // Mix different I/O patterns
          bufferedFileOperations(10);
          unbufferedFileOperations(5);
          nioFileOperations(5);
          directoryOperations();
          compressionOperations(3);

          totalOperations.incrementAndGet();

          // Pause between cycles
          Thread.sleep(100);
        } catch (InterruptedException e) {
          break;
        } catch (Exception e) {
          System.err.println("Error in I/O workload: " + e.getMessage());
        }
      }
      System.out.println("⏸️  IOWorkload stopped (operations: " + totalOperations.get() + ")");

      // Cleanup
      cleanup();
    }, "IOWorkload-Thread");

    ioThread.setDaemon(true);
    ioThread.start();
  }

  /**
   * Stops the continuous workload.
   */
  public void stop() {
    running.set(false);
  }

  /**
   * Buffered file operations - efficient I/O pattern.
   *
   * Profile characteristics: - Lower I/O wait time due to buffering - Fewer
   * system calls visible in profile - Best practice for sequential I/O
   *
   * @param count Number of files to write/read
   */
  public void bufferedFileOperations(int count) throws IOException {
    for (int i = 0; i < count; i++) {
      Path file = tempDir.resolve("buffered_" + i + ".txt");

      // Write with buffering (efficient)
      try (BufferedWriter writer = new BufferedWriter(new FileWriter(file.toFile()))) {
        for (int line = 0; line < 100; line++) {
          writer.write("Line " + line + ": " + generateRandomText(50));
          writer.newLine();
        }
      }

      // Read with buffering (efficient)
      try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
        String line;
        int lineCount = 0;
        long totalChars = 0;
        while ((line = reader.readLine()) != null) {
          lineCount++;
          // Simulate processing
          totalChars += line.length();
        }
        // Store to prevent optimization
        totalBytesProcessed.addAndGet(totalChars + lineCount);
      }

      // Clean up
      Files.deleteIfExists(file);
    }
  }

  /**
   * Unbuffered file operations - inefficient pattern for comparison.
   * ANTI-PATTERN: Shows why buffering matters.
   *
   * Profile characteristics: - High I/O wait time (many small system calls) -
   * Much slower than buffered I/O - Visible as I/O bottleneck in comprehensive
   * profiles
   *
   * @param count Number of files to write/read
   */
  public void unbufferedFileOperations(int count) throws IOException {
    for (int i = 0; i < count; i++) {
      Path file = tempDir.resolve("unbuffered_" + i + ".txt");

      // Write without buffering (inefficient)
      try (FileWriter writer = new FileWriter(file.toFile())) {
        for (int line = 0; line < 100; line++) {
          // Each write is a separate system call - slow!
          writer.write("Line " + line + ": " + generateRandomText(50) + "\n");
        }
      }

      // Read without buffering (inefficient)
      try (FileReader reader = new FileReader(file.toFile())) {
        int ch;
        StringBuilder line = new StringBuilder();
        long totalChars = 0;
        while ((ch = reader.read()) != -1) { // Character by character - very slow!
          if (ch == '\n') {
            totalChars += line.length();
            line = new StringBuilder();
          } else {
            line.append((char) ch);
          }
        }
        // Store to prevent optimization
        totalBytesProcessed.addAndGet(totalChars);
      }

      // Clean up
      Files.deleteIfExists(file);
    }
  }

  /**
   * NIO (New I/O) file operations - modern Java I/O API.
   *
   * Profile characteristics: - Efficient for large files and bulk operations -
   * Shows FileChannel and ByteBuffer usage - Direct memory access patterns
   * visible
   *
   * @param count Number of files to write/read
   */
  public void nioFileOperations(int count) throws IOException {
    for (int i = 0; i < count; i++) {
      Path file = tempDir.resolve("nio_" + i + ".dat");

      // Write using NIO
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING)) {

        ByteBuffer buffer = ByteBuffer.allocate(8192);

        for (int block = 0; block < 10; block++) {
          buffer.clear();
          byte[] data = generateRandomBytes(4096);
          buffer.put(data);
          buffer.flip();
          channel.write(buffer);
        }
      }

      // Read using NIO
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {

        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long totalRead = 0;

        while (channel.read(buffer) > 0) {
          buffer.flip();
          totalRead += buffer.remaining();
          buffer.clear();
        }

        // Store to prevent optimization
        totalBytesProcessed.addAndGet(totalRead);
      }

      // Clean up
      Files.deleteIfExists(file);
    }
  }

  /**
   * Directory and file system operations - metadata access patterns.
   *
   * Profile characteristics: - Shows file system metadata access - Directory
   * listing I/O operations - Path resolution and file attribute queries
   */
  public void directoryOperations() throws IOException {
    // Create subdirectory structure
    Path subDir = tempDir.resolve("subdir_" + System.nanoTime());
    Files.createDirectories(subDir);

    // Create multiple files in subdirectory
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Path file = subDir.resolve("file_" + i + ".tmp");
      Files.write(file, generateRandomBytes(1024));
      files.add(file);
    }

    // List directory (file system I/O)
    try (Stream<Path> stream = Files.list(subDir)) {
      stream.forEach(path -> {
        try {
          // Access file attributes (metadata I/O)
          long size = Files.size(path);
          boolean readable = Files.isReadable(path);
          boolean writable = Files.isWritable(path);
          // Use attributes to prevent optimization
          if (readable && writable && size > 0) {
            totalBytesProcessed.addAndGet(size);
          }
        } catch (IOException e) {
          // Ignore
        }
      });
    }

    // Walk directory tree
    try (Stream<Path> stream = Files.walk(subDir)) {
      stream.forEach(path -> {
        try {
          if (Files.isRegularFile(path)) {
            // Read first bytes of each file
            byte[] data = Files.readAllBytes(path);
            // Use data to prevent optimization
            totalBytesProcessed.addAndGet(data.length);
          }
        } catch (IOException e) {
          // Ignore
        }
      });
    }

    // Cleanup subdirectory
    for (Path file : files) {
      Files.deleteIfExists(file);
    }
    Files.deleteIfExists(subDir);
  }

  /**
   * Compression operations - combined CPU and I/O workload.
   *
   * Profile characteristics: - Shows both CPU time (compression) and I/O time
   * (writing) - Demonstrates mixed workload in flame graphs - GZIPOutputStream
   * overhead visible
   *
   * @param count Number of files to compress
   */
  public void compressionOperations(int count) throws IOException {
    for (int i = 0; i < count; i++) {
      Path gzipFile = tempDir.resolve("compressed_" + i + ".gz");

      // Write compressed data (CPU + I/O)
      try (OutputStream fileOut = Files.newOutputStream(gzipFile);
          GZIPOutputStream gzipOut = new GZIPOutputStream(fileOut);
          BufferedOutputStream bufferedOut = new BufferedOutputStream(gzipOut)) {

        // Write data that compresses well
        byte[] data = generateCompressibleData(10240);
        bufferedOut.write(data);
      }

      // Read compressed data
      try (InputStream fileIn = Files.newInputStream(gzipFile);
          java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(fileIn);
          BufferedInputStream bufferedIn = new BufferedInputStream(gzipIn)) {

        byte[] buffer = new byte[1024];
        int bytesRead;
        long totalRead = 0;
        while ((bytesRead = bufferedIn.read(buffer)) != -1) {
          totalRead += bytesRead;
        }

        // Store to prevent optimization
        totalBytesProcessed.addAndGet(totalRead);
      }

      // Clean up
      Files.deleteIfExists(gzipFile);
    }
  }

  /**
   * Generates random text for file content.
   */
  private String generateRandomText(int length) {
    StringBuilder sb = new StringBuilder(length);
    String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 ";
    for (int i = 0; i < length; i++) {
      sb.append(chars.charAt(random.nextInt(chars.length())));
    }
    return sb.toString();
  }

  /**
   * Generates random bytes for binary file content.
   */
  private byte[] generateRandomBytes(int size) {
    byte[] bytes = new byte[size];
    random.nextBytes(bytes);
    return bytes;
  }

  /**
   * Generates compressible data (lots of repetition).
   */
  private byte[] generateCompressibleData(int size) {
    byte[] data = new byte[size];
    // Fill with repeating pattern (compresses well)
    for (int i = 0; i < size; i++) {
      data[i] = (byte) (i % 16);
    }
    return data;
  }

  /**
   * Runs all I/O workloads once for testing.
   */
  public void runAllOnce() {
    System.out.println("Running all I/O workloads once...");

    long start = System.currentTimeMillis();

    try {
      System.out.println("  - Buffered file operations (50 files)...");
      bufferedFileOperations(50);

      System.out.println("  - Unbuffered file operations (20 files)...");
      unbufferedFileOperations(20);

      System.out.println("  - NIO file operations (30 files)...");
      nioFileOperations(30);

      System.out.println("  - Directory operations...");
      directoryOperations();

      System.out.println("  - Compression operations (10 files)...");
      compressionOperations(10);

      long elapsed = System.currentTimeMillis() - start;
      System.out.println("✅ All I/O operations completed in " + elapsed + "ms");

    } catch (IOException e) {
      System.err.println("❌ I/O operations failed: " + e.getMessage());
      e.printStackTrace();
    } finally {
      cleanup();
    }
  }

  /**
   * Cleans up temporary directory and files.
   */
  private void cleanup() {
    try {
      if (Files.exists(tempDir)) {
        // Delete all files in temp directory
        try (Stream<Path> stream = Files.walk(tempDir)) {
          stream.sorted((a, b) -> b.compareTo(a)) // Reverse order (files before dirs)
              .forEach(path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  // Ignore cleanup errors
                }
              });
        }
      }
    } catch (IOException e) {
      // Ignore cleanup errors
    }
  }

  public long getTotalOperations() {
    return totalOperations.get();
  }

  public boolean isRunning() {
    return running.get();
  }

  public Path getTempDir() {
    return tempDir;
  }
}
