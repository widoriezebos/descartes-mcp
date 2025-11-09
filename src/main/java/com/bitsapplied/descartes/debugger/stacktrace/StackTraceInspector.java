package com.bitsapplied.descartes.debugger.stacktrace;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.debugger.models.StackFrameInfo;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

/**
 * Captures and formats stack traces from suspended threads.
 *
 * <p>
 * Capabilities:
 * <ul>
 * <li>Capture full stack traces from suspended threads</li>
 * <li>Convert JDI StackFrame to StackFrameInfo records</li>
 * <li>Support filtering by depth and package patterns</li>
 * <li>Handle frames with/without line information gracefully</li>
 * <li>Extract source file locations when available</li>
 * </ul>
 *
 * <p>
 * Thread Safety: All operations must be called on the debugger executor thread.
 */
public class StackTraceInspector {
  private static final Logger logger = LoggerFactory.getLogger(StackTraceInspector.class);

  /**
   * Captures the full stack trace from a thread.
   *
   * @param thread the suspended thread
   * @return list of stack frame information
   * @throws DebuggerException if thread is not suspended or stack cannot be read
   */
  public List<StackFrameInfo> captureStackTrace(ThreadReference thread) {
    return captureStackTrace(thread, Integer.MAX_VALUE);
  }

  /**
   * Captures a limited stack trace from a thread.
   *
   * @param thread   the suspended thread
   * @param maxDepth maximum number of frames to capture
   * @return list of stack frame information
   * @throws DebuggerException if thread is not suspended or stack cannot be read
   */
  public List<StackFrameInfo> captureStackTrace(ThreadReference thread, int maxDepth) {
    if (!thread.isSuspended()) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
          "Thread must be suspended to capture stack trace: " + thread.name());
    }

    try {
      List<StackFrame> frames = thread.frames();
      List<StackFrameInfo> result = new ArrayList<>();

      int depth = Math.min(frames.size(), maxDepth);

      for (int i = 0; i < depth; i++) {
        StackFrame frame = frames.get(i);
        result.add(convertStackFrame(frame, i));
      }

      logger.debug("Captured {} stack frames from thread {}", result.size(), thread.name());

      return result;

    } catch (IncompatibleThreadStateException e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED, "Thread is not suspended: " + thread.name(),
          e);
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.UNKNOWN_ERROR, "Failed to capture stack trace: " + e.getMessage(),
          e);
    }
  }

  /**
   * Captures a filtered stack trace excluding certain packages.
   *
   * @param thread          the suspended thread
   * @param excludePatterns package patterns to exclude (e.g., "java.*",
   *                        "javax.*")
   * @return filtered list of stack frame information
   * @throws DebuggerException if thread is not suspended or stack cannot be read
   */
  public List<StackFrameInfo> captureFilteredStackTrace(ThreadReference thread, String[] excludePatterns) {
    List<StackFrameInfo> allFrames = captureStackTrace(thread);

    if (excludePatterns == null || excludePatterns.length == 0) {
      return allFrames;
    }

    List<StackFrameInfo> filtered = new ArrayList<>();

    for (StackFrameInfo frame : allFrames) {
      boolean exclude = false;

      for (String pattern : excludePatterns) {
        if (matchesPattern(frame.className(), pattern)) {
          exclude = true;
          break;
        }
      }

      if (!exclude) {
        filtered.add(frame);
      }
    }

    logger.debug("Filtered stack trace: {} -> {} frames", allFrames.size(), filtered.size());

    return filtered;
  }

  /**
   * Gets a specific stack frame from a thread.
   *
   * @param thread     the suspended thread
   * @param frameIndex the frame index (0 = top of stack)
   * @return stack frame information
   * @throws DebuggerException if thread is not suspended or frame index is
   *                           invalid
   */
  public StackFrameInfo getFrame(ThreadReference thread, int frameIndex) {
    if (!thread.isSuspended()) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED, "Thread must be suspended: " + thread.name());
    }

    try {
      List<StackFrame> frames = thread.frames();

      if (frameIndex < 0 || frameIndex >= frames.size()) {
        throw new DebuggerException(DebuggerErrorCode.UNKNOWN_ERROR,
            String.format("Invalid frame index %d (valid range: 0-%d)", frameIndex, frames.size() - 1));
      }

      return convertStackFrame(frames.get(frameIndex), frameIndex);

    } catch (IncompatibleThreadStateException e) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED, "Thread is not suspended: " + thread.name(),
          e);
    }
  }

  /**
   * Gets the current (top) stack frame from a thread.
   *
   * @param thread the suspended thread
   * @return current stack frame information
   * @throws DebuggerException if thread is not suspended or has no frames
   */
  public StackFrameInfo getCurrentFrame(ThreadReference thread) {
    return getFrame(thread, 0);
  }

  // ========== Internal Methods ==========

  /**
   * Converts a JDI StackFrame to a StackFrameInfo record.
   */
  private StackFrameInfo convertStackFrame(StackFrame frame, int index) {
    try {
      Location location = frame.location();
      Method method = location.method();
      ReferenceType declaringType = location.declaringType();

      String className = declaringType.name();
      String methodName = method.name();
      boolean isNative = method.isNative();

      int lineNumber = -1;
      String fileName = null;

      try {
        lineNumber = location.lineNumber();
      } catch (Exception e) {
        // Line information not available
        logger.trace("No line info for frame {}: {}", index, e.getMessage());
      }

      try {
        fileName = location.sourceName();
      } catch (Exception e) {
        // Source name not available
        logger.trace("No source name for frame {}: {}", index, e.getMessage());
      }

      return new StackFrameInfo(index, methodName, className, fileName, lineNumber, isNative);

    } catch (Exception e) {
      logger.warn("Error converting stack frame {}: {}", index, e.getMessage());

      // Return a minimal frame info
      return new StackFrameInfo(index, "unknownMethod", "UnknownClass", null, -1, false);
    }
  }

  /**
   * Checks if a class name matches a pattern (supports * wildcard).
   */
  private boolean matchesPattern(String className, String pattern) {
    if (pattern.endsWith("*")) {
      String prefix = pattern.substring(0, pattern.length() - 1);
      return className.startsWith(prefix);
    }
    return className.equals(pattern);
  }
}
