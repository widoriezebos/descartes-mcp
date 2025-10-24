package com.bitsapplied.descartes.profiler.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.profiler.model.CallTreeNode;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * Builds call tree structures from JFR stack traces.
 *
 * <p>
 * Processes stack traces to create hierarchical call trees showing
 * caller/callee relationships and time spent in each method.
 */
public class CallTreeBuilder {

  private final Map<String, CallTreeNode> rootNodes = new HashMap<>();
  private final String packageFilter;

  public CallTreeBuilder(String packageFilter) {
    this.packageFilter = packageFilter;
  }

  /**
   * Add a stack trace sample to the call trees.
   *
   * @param stackTrace Stack trace from JFR event
   */
  public void addSample(RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return;
    }

    List<RecordedFrame> frames = stackTrace.getFrames();
    if (frames.isEmpty()) {
      return;
    }

    // Walk from bottom to top (deepest call to shallowest)
    CallTreeNode currentNode = null;
    CallTreeNode rootNode = null;

    for (int i = frames.size() - 1; i >= 0; i--) {
      RecordedFrame frame = frames.get(i);
      RecordedMethod method = frame.getMethod();

      if (method == null) {
        continue;
      }

      String className = method.getType().getName();
      String methodName = method.getName();

      // Apply package filter
      if (packageFilter != null && !packageFilter.isEmpty()) {
        if (!className.startsWith(packageFilter)) {
          continue; // Skip methods outside filter
        }
      }

      String signature = formatMethodSignature(method, frame.getLineNumber());

      if (currentNode == null) {
        // This is the root of this stack trace
        rootNode = rootNodes.computeIfAbsent(signature,
            _ -> new CallTreeNode(signature, className, methodName, getSourceFile(method), frame.getLineNumber()));
        currentNode = rootNode;
      } else {
        // This is a child of the current node
        currentNode = currentNode.getOrCreateChild(signature, className, methodName, getSourceFile(method),
            frame.getLineNumber());
      }

      currentNode.incrementHitCount();
    }
  }

  /**
   * Get all root nodes (methods at the top of call stacks).
   */
  public Map<String, CallTreeNode> getRootNodes() {
    return rootNodes;
  }

  /**
   * Format method signature for display.
   */
  private String formatMethodSignature(RecordedMethod method, int lineNumber) {
    String className = method.getType().getName();
    String methodName = method.getName();
    String sourceFile = getSourceFile(method);

    if (sourceFile != null && lineNumber > 0) {
      return String.format("%s.%s(%s:%d)", className, methodName, sourceFile, lineNumber);
    } else if (sourceFile != null) {
      return String.format("%s.%s(%s)", className, methodName, sourceFile);
    } else {
      return String.format("%s.%s", className, methodName);
    }
  }

  /**
   * Extract source file name from method.
   */
  private String getSourceFile(RecordedMethod method) {
    try {
      return method.getType().getName().substring(method.getType().getName().lastIndexOf('.') + 1) + ".java";
    } catch (Exception e) {
      return null;
    }
  }
}
