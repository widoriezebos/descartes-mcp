package com.bitsapplied.descartes.tools.logging.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility for reading log files with transparent compression support.
 *
 * <p>
 * Supports:
 * <ul>
 * <li>Uncompressed files - direct BufferedReader</li>
 * <li>.gz files - GZIPInputStream wrapper</li>
 * <li>.zip files - ZipInputStream wrapper</li>
 * </ul>
 *
 * <p>
 * Uses streaming to avoid loading entire file into memory. Performance: O(1)
 * memory per line, not O(n) for entire file.
 */
public class LogFileReader {

  private static final int BUFFER_SIZE = 32768; // 32KB for optimal I/O
  private static final int GZIP_BUFFER_SIZE = 8192; // 8KB for gunzip decompression

  /**
   * Open a log file with appropriate compression handling.
   *
   * @param filePath Path to log file
   * @return BufferedReader for streaming
   * @throws IOException if file cannot be opened
   */
  public static BufferedReader open(Path filePath) throws IOException {
    if (!Files.exists(filePath)) {
      throw new NoSuchFileException(filePath.toString());
    }
    if (!Files.isReadable(filePath)) {
      throw new AccessDeniedException(filePath.toString());
    }

    InputStream inputStream = Files.newInputStream(filePath, StandardOpenOption.READ);

    // Detect and handle compression
    CompressionType compression = detectCompression(filePath);
    switch (compression) {
    case GZIP:
      inputStream = new GZIPInputStream(inputStream, GZIP_BUFFER_SIZE);
      break;
    case ZIP:
      ZipInputStream zipStream = new ZipInputStream(inputStream);
      ZipEntry entry = zipStream.getNextEntry();
      if (entry == null) {
        throw new IOException("Empty ZIP file: " + filePath);
      }
      inputStream = zipStream;
      break;
    case NONE:
    default:
      // Use raw InputStream
      break;
    }

    return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8), BUFFER_SIZE);
  }

  /**
   * Read last N lines from file efficiently. Uses circular buffer for memory
   * efficiency - O(1) per line.
   *
   * @param filePath  Path to log file
   * @param lineCount Number of lines to read
   * @return List of last N lines
   * @throws IOException if file cannot be read
   */
  public static List<String> tail(Path filePath, int lineCount) throws IOException {
    if (lineCount <= 0) {
      return Collections.emptyList();
    }

    // Use circular buffer to keep last N lines
    Deque<String> buffer = new ArrayDeque<>(lineCount);

    try (BufferedReader reader = open(filePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.addLast(line);
        if (buffer.size() > lineCount) {
          buffer.removeFirst();
        }
      }
    }

    return new ArrayList<>(buffer);
  }

  /**
   * Read first N lines from file. Early termination for performance.
   *
   * @param filePath  Path to log file
   * @param lineCount Number of lines to read
   * @return List of first N lines
   * @throws IOException if file cannot be read
   */
  public static List<String> head(Path filePath, int lineCount) throws IOException {
    if (lineCount <= 0) {
      return Collections.emptyList();
    }

    List<String> lines = new ArrayList<>(lineCount);

    try (BufferedReader reader = open(filePath)) {
      String line;
      int count = 0;
      while ((line = reader.readLine()) != null && count < lineCount) {
        lines.add(line);
        count++;
      }
    }

    return lines;
  }

  /**
   * Check if file is compressed based on extension.
   *
   * @param filePath Path to check
   * @return true if file appears to be compressed
   */
  public static boolean isCompressed(Path filePath) {
    String fileName = filePath.getFileName().toString().toLowerCase();
    return fileName.endsWith(".gz") || fileName.endsWith(".zip");
  }

  /**
   * Detect compression type from file extension.
   *
   * @param filePath Path to check
   * @return Detected compression type
   */
  private static CompressionType detectCompression(Path filePath) {
    String fileName = filePath.getFileName().toString().toLowerCase();
    if (fileName.endsWith(".gz")) {
      return CompressionType.GZIP;
    } else if (fileName.endsWith(".zip")) {
      return CompressionType.ZIP;
    } else {
      return CompressionType.NONE;
    }
  }

  /**
   * Compression types supported by LogFileReader.
   */
  private enum CompressionType {
    /** No compression */
    NONE,
    /** GZIP compression (.gz files) */
    GZIP,
    /** ZIP compression (.zip files) */
    ZIP
  }
}
