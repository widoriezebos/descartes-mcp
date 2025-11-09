package com.bitsapplied.descartes.debugger.models;

/**
 * Stack frame information record.
 *
 * <p>
 * Represents a single frame in a thread's call stack, captured at a specific
 * suspension point. Each frame corresponds to a method invocation and provides
 * information about its location in the source code.
 *
 * <p>
 * Frame IDs are stable within a single suspended state but may change when
 * execution resumes. This is important for tools that track frames across
 * multiple debugger operations.
 */
public record StackFrameInfo(int frameId, // Stable frame ID for this suspended state
    String methodName, String className, String fileName, // Can be null for native methods or methods without source
    int lineNumber, // -1 if unknown
    boolean isNative) {
  /**
   * Validates that frame information is coherent.
   *
   * @return true if the frame is valid
   */
  public boolean isValid() {
    return methodName != null && !methodName.isEmpty() && className != null && !className.isEmpty() && frameId >= 0;
  }

  /**
   * Checks if this frame has source code location information.
   *
   * @return true if fileName and lineNumber are available
   */
  public boolean hasSourceLocation() {
    return fileName != null && !fileName.isEmpty() && lineNumber > 0;
  }

  /**
   * Gets the fully qualified method signature.
   *
   * @return class name with method name (e.g., "java.util.ArrayList.add")
   */
  public String getFullMethodName() {
    return className + "." + methodName;
  }

  /**
   * Gets a short description of this frame.
   *
   * @return formatted frame description
   */
  public String toShortString() {
    String desc = getFullMethodName();
    if (isNative) {
      desc += " [native]";
    } else if (hasSourceLocation()) {
      desc += String.format(" (%s:%d)", fileName, lineNumber);
    }
    return desc;
  }

  /**
   * Gets a detailed description of this frame.
   *
   * @return formatted frame details
   */
  @Override
  public String toString() {
    return String.format("Frame[%d]: %s at %s:%d%s", frameId, getFullMethodName(),
        fileName != null ? fileName : "unknown", lineNumber, isNative ? " (native)" : "");
  }
}
