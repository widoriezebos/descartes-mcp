package com.bitsapplied.descartes.debugger.stepping;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

/**
 * Controls stepping operations during debugging.
 *
 * <p>
 * Stepping Types:
 * <ul>
 * <li><b>Step Over</b> - Execute the next line of code in the current
 * method</li>
 * <li><b>Step Into</b> - Step into method calls</li>
 * <li><b>Step Out</b> - Step out of the current method to its caller</li>
 * </ul>
 *
 * <p>
 * Skip Patterns: Certain packages are skipped when stepping (e.g., java.*,
 * javax.*) to avoid stepping into JDK internals. These patterns are
 * configurable.
 *
 * <p>
 * Thread Safety: All operations must be called on the debugger executor thread.
 */
public class SteppingController {
  private static final Logger logger = LoggerFactory.getLogger(SteppingController.class);

  private final EventRequestManager erm;
  private final String[] skipPatterns;

  // Currently active step request (only one step at a time)
  private StepRequest activeStepRequest;

  /**
   * Creates a stepping controller.
   *
   * @param vm           the virtual machine
   * @param skipPatterns class patterns to skip when stepping
   */
  public SteppingController(VirtualMachine vm, String[] skipPatterns) {
    this.erm = vm.eventRequestManager();
    this.skipPatterns = skipPatterns != null ? skipPatterns
        : new String[] { "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*" };
  }

  /**
   * Steps over the current line (next line in current method).
   *
   * @param thread the thread to step
   * @throws DebuggerException if step cannot be performed
   */
  public void stepOver(ThreadReference thread) {
    validateSuspended(thread);
    clearActiveStepRequest();

    try {
      StepRequest request = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);

      configureStepRequest(request, thread);
      request.enable();

      this.activeStepRequest = request;

      logger.debug("Step over initiated on thread: {}", thread.name());

      // Resume the thread to execute the step
      thread.resume();

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.UNKNOWN_ERROR, "Failed to step over: " + e.getMessage(), e);
    }
  }

  /**
   * Steps into method calls.
   *
   * @param thread the thread to step
   * @throws DebuggerException if step cannot be performed
   */
  public void stepInto(ThreadReference thread) {
    validateSuspended(thread);
    clearActiveStepRequest();

    try {
      StepRequest request = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);

      configureStepRequest(request, thread);
      request.enable();

      this.activeStepRequest = request;

      logger.debug("Step into initiated on thread: {}", thread.name());

      // Resume the thread to execute the step
      thread.resume();

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.UNKNOWN_ERROR, "Failed to step into: " + e.getMessage(), e);
    }
  }

  /**
   * Steps out of the current method to its caller.
   *
   * @param thread the thread to step
   * @throws DebuggerException if step cannot be performed
   */
  public void stepOut(ThreadReference thread) {
    validateSuspended(thread);
    clearActiveStepRequest();

    try {
      StepRequest request = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT);

      configureStepRequest(request, thread);
      request.enable();

      this.activeStepRequest = request;

      logger.debug("Step out initiated on thread: {}", thread.name());

      // Resume the thread to execute the step
      thread.resume();

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.UNKNOWN_ERROR, "Failed to step out: " + e.getMessage(), e);
    }
  }

  /**
   * Gets the currently active step request.
   *
   * @return active step request, or null if no step is in progress
   */
  public StepRequest getActiveStepRequest() {
    return activeStepRequest;
  }

  /**
   * Checks if a step operation is currently in progress.
   *
   * @return true if stepping
   */
  public boolean isStepping() {
    return activeStepRequest != null && activeStepRequest.isEnabled();
  }

  /**
   * Clears the active step request after a step completes.
   */
  public void clearActiveStepRequest() {
    if (activeStepRequest != null) {
      try {
        erm.deleteEventRequest(activeStepRequest);
        logger.debug("Cleared active step request");
      } catch (Exception e) {
        logger.warn("Error clearing step request: {}", e.getMessage());
      }
      activeStepRequest = null;
    }
  }

  /**
   * Gets the configured skip patterns.
   *
   * @return array of skip patterns
   */
  public String[] getSkipPatterns() {
    return Arrays.copyOf(skipPatterns, skipPatterns.length);
  }

  // ========== Internal Methods ==========

  /**
   * Configures a step request with appropriate settings.
   */
  private void configureStepRequest(StepRequest request, ThreadReference thread) {
    // Suspend all threads when step completes
    request.setSuspendPolicy(EventRequest.SUSPEND_ALL);

    // Add class exclusion filters to skip JDK classes
    for (String pattern : skipPatterns) {
      request.addClassExclusionFilter(pattern);
    }

    // Single-step (automatically disabled after one step)
    request.addCountFilter(1);

    logger.debug("Step request configured with {} skip patterns", skipPatterns.length);
  }

  /**
   * Validates that the thread is suspended before stepping.
   */
  private void validateSuspended(ThreadReference thread) {
    if (thread == null) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_FOUND, "Thread is null");
    }

    if (!thread.isSuspended()) {
      throw new DebuggerException(DebuggerErrorCode.THREAD_NOT_SUSPENDED,
          "Thread must be suspended to perform step: " + thread.name());
    }
  }
}
