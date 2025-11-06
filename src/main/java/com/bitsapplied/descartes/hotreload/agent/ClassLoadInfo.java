package com.bitsapplied.descartes.hotreload.agent;

import java.io.File;
import java.net.URL;
import java.util.Arrays;

/**
 * Information about a loaded class including its source location and bytecode.
 * 
 * @author Descartes MCP
 */
public class ClassLoadInfo {

  private final String className;
  private final URL sourceLocation;
  private final byte[] originalBytecode;
  private final long loadTime;
  private final ClassLoader classLoader;
  private volatile byte[] currentBytecode;
  private volatile long lastModified;
  private final boolean reliableTimestamp;

  /**
   * Create class load information.
   * 
   * @param className      Binary class name (e.g., com/example/MyClass)
   * @param sourceLocation URL where class was loaded from
   * @param bytecode       Original bytecode (can be null for pre-loaded classes)
   */
  public ClassLoadInfo(String className, URL sourceLocation, byte[] bytecode, ClassLoader classLoader) {
    this.className = className;
    this.sourceLocation = sourceLocation;
    this.originalBytecode = bytecode != null ? bytecode.clone() : null;
    this.currentBytecode = this.originalBytecode;
    this.loadTime = System.currentTimeMillis();
    this.lastModified = getSourceLastModifiedInternal();
    this.classLoader = classLoader;
    this.reliableTimestamp = determineTimestampReliability(sourceLocation, lastModified);
  }

  /**
   * Get the binary class name.
   * 
   * @return Class name with / separators
   */
  public String getClassName() {
    return className;
  }

  /**
   * Get the Java class name.
   * 
   * @return Class name with . separators
   */
  public String getJavaClassName() {
    return className.replace('/', '.');
  }

  /**
   * Get the source location URL.
   * 
   * @return URL where class was loaded from
   */
  public URL getSourceLocation() {
    return sourceLocation;
  }

  /**
   * Get the class loader that loaded this class, or {@code null} for the
   * bootstrap loader.
   */
  public ClassLoader getClassLoader() {
    return classLoader;
  }

  /**
   * Get the original bytecode.
   * 
   * @return Original bytecode or null if not available
   */
  public byte[] getOriginalBytecode() {
    return originalBytecode != null ? originalBytecode.clone() : null;
  }

  /**
   * Get the current bytecode.
   * 
   * @return Current bytecode or null if not available
   */
  public byte[] getCurrentBytecode() {
    return currentBytecode != null ? currentBytecode.clone() : null;
  }

  /**
   * Update the current bytecode.
   * 
   * @param bytecode New bytecode
   */
  public void updateBytecode(byte[] bytecode) {
    updateAfterSuccessfulRedefinition(bytecode, System.currentTimeMillis());
  }

  /**
   * Get the time when the class was loaded.
   * 
   * @return Load time in milliseconds
   */
  public long getLoadTime() {
    return loadTime;
  }

  /**
   * Get the last modification time.
   * 
   * @return Last modification time in milliseconds
   */
  public long getLastModified() {
    return lastModified;
  }

  /**
   * Check if the bytecode has changed.
   * 
   * @param newBytecode New bytecode to compare
   * @return true if bytecode is different
   */
  public boolean hasBytecodeChanged(byte[] newBytecode) {
    if (currentBytecode == null || newBytecode == null) {
      return currentBytecode != newBytecode;
    }
    return !Arrays.equals(currentBytecode, newBytecode);
  }

  /**
   * Get the last modified time of the source location.
   * 
   * @return Last modified time or 0 if cannot be determined
   */
  private long getSourceLastModifiedInternal() {
    if (sourceLocation == null) {
      return 0;
    }

    try {
      if ("file".equals(sourceLocation.getProtocol())) {
        return new File(sourceLocation.toURI()).lastModified();
      } else if ("jar".equals(sourceLocation.getProtocol())) {
        // For JAR files, use the JAR file's modification time
        String path = sourceLocation.getPath();
        int separatorIndex = path.indexOf("!/");
        if (separatorIndex > 0) {
          String jarPath = path.substring(5, separatorIndex); // Remove "file:"
          return new File(jarPath).lastModified();
        }
      }
    } catch (Exception e) {
      // Ignore and return 0
    }

    return 0;
  }

  private boolean determineTimestampReliability(URL location, long sourceTimestamp) {
    if (location == null || sourceTimestamp == 0) {
      return false;
    }

    String protocol = location.getProtocol();
    return "file".equals(protocol) || "jar".equals(protocol);
  }

  /**
   * Return the best-effort timestamp of the class source.
   * 
   * @return Last modified timestamp or 0 if unavailable
   */
  public long fetchCurrentSourceTimestamp() {
    return getSourceLastModifiedInternal();
  }

  /**
   * Check whether the tracked class has a dependable timestamp.
   *
   * @return true if timestamp comparisons can be trusted
   */
  public boolean hasReliableTimestamp() {
    return reliableTimestamp;
  }

  /**
   * Check if the class currently tracks bytecode that can be compared against.
   * 
   * @return true if a baseline exists
   */
  public boolean hasTrackedBytecode() {
    return currentBytecode != null;
  }

  /**
   * Update the stored bytecode and source timestamp after a successful
   * redefinition.
   * 
   * @param bytecode        New bytecode
   * @param sourceTimestamp Timestamp of the source artifact, or 0 if unknown
   */
  public void updateAfterSuccessfulRedefinition(byte[] bytecode, long sourceTimestamp) {
    this.currentBytecode = bytecode != null ? bytecode.clone() : null;
    if (sourceTimestamp > 0) {
      this.lastModified = sourceTimestamp;
    } else {
      this.lastModified = System.currentTimeMillis();
    }
  }

  /**
   * Update the last seen source timestamp without altering the tracked bytecode.
   * Useful when change detection inspects a class but no reload is required.
   * 
   * @param sourceTimestamp Timestamp reported by the source location
   */
  public void markInspected(long sourceTimestamp) {
    if (sourceTimestamp > 0) {
      this.lastModified = sourceTimestamp;
    }
  }

  /**
   * Check if the source has been modified since last check.
   * 
   * @return true if source has been modified
   */
  public boolean isSourceModified() {
    long currentSourceModified = getSourceLastModifiedInternal();
    return currentSourceModified > 0 && (lastModified == 0 || currentSourceModified > lastModified);
  }

  @Override
  public String toString() {
    return String.format("ClassLoadInfo[class=%s, location=%s, hasOriginal=%b, hasCurrent=%b]", className,
        sourceLocation, originalBytecode != null, currentBytecode != null);
  }
}
