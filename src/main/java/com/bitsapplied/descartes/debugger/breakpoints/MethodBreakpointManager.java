package com.bitsapplied.descartes.debugger.breakpoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;

/**
 * Manages method breakpoints (entry/exit).
 *
 * <p>
 * Method breakpoints allow pausing execution when:
 * <ul>
 * <li>A method is entered (before first instruction)</li>
 * <li>A method is exited (after return/exception)</li>
 * </ul>
 *
 * <p>
 * Supports:
 * <ul>
 * <li>Specific method breakpoints (class + method name)</li>
 * <li>Pattern-based breakpoints (all methods in a class/package)</li>
 * <li>Entry-only, exit-only, or both</li>
 * </ul>
 *
 * <p>
 * Thread Safety: This class uses concurrent data structures for thread-safe
 * access.
 */
public class MethodBreakpointManager {
  private static final Logger logger = LoggerFactory.getLogger(MethodBreakpointManager.class);

  private final EventRequestManager erm;

  // Method breakpoint storage: ID -> Request
  private final Map<Long, MethodEntryRequest> entryBreakpoints = new ConcurrentHashMap<>();
  private final Map<Long, MethodExitRequest> exitBreakpoints = new ConcurrentHashMap<>();

  // Breakpoint ID generator
  private final AtomicLong nextBreakpointId = new AtomicLong(1);

  /**
   * Creates a method breakpoint manager.
   *
   * @param vm the virtual machine
   */
  public MethodBreakpointManager(VirtualMachine vm) {
    this.erm = vm.eventRequestManager();
  }

  /**
   * Sets a method entry breakpoint.
   *
   * @param classPattern the class pattern (e.g., "com.example.*" or
   *                     "com.example.MyClass")
   * @return the breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setMethodEntry(String classPattern) {
    return setMethodEntry(classPattern, null, EventRequest.SUSPEND_EVENT_THREAD);
  }

  /**
   * Sets a method entry breakpoint with optional method name filter.
   *
   * @param classPattern the class pattern
   * @param methodName   the method name (null for all methods)
   * @return the breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setMethodEntry(String classPattern, String methodName) {
    return setMethodEntry(classPattern, methodName, EventRequest.SUSPEND_EVENT_THREAD);
  }

  /**
   * Sets a method entry breakpoint with configurable suspend policy.
   *
   * @param classPattern  the class pattern
   * @param methodName    the method name (null for all methods)
   * @param suspendPolicy suspend policy (SUSPEND_EVENT_THREAD, SUSPEND_ALL, or
   *                      SUSPEND_NONE)
   * @return the breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setMethodEntry(String classPattern, String methodName, int suspendPolicy) {
    try {
      long id = nextBreakpointId.getAndIncrement();

      MethodEntryRequest request = erm.createMethodEntryRequest();

      // Add class filter
      request.addClassFilter(classPattern);

      // Store class pattern and method name for later retrieval
      request.putProperty("classPattern", classPattern);
      if (methodName != null) {
        request.putProperty("methodName", methodName);
      }

      request.setSuspendPolicy(suspendPolicy);
      request.putProperty("breakpointId", id);
      request.enable();

      entryBreakpoints.put(id, request);

      String policyName = suspendPolicyToString(suspendPolicy);
      logger.info("Method entry breakpoint set: ID={}, class={}, method={} (suspend: {})", id, classPattern,
          methodName != null ? methodName : "*", policyName);

      return id;

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_SET_FAILED,
          "Failed to set method entry breakpoint: " + e.getMessage(), e);
    }
  }

  /**
   * Sets a method exit breakpoint.
   *
   * @param classPattern the class pattern
   * @return the breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setMethodExit(String classPattern) {
    return setMethodExit(classPattern, null, EventRequest.SUSPEND_EVENT_THREAD);
  }

  /**
   * Sets a method exit breakpoint with optional method name filter.
   *
   * @param classPattern the class pattern
   * @param methodName   the method name (null for all methods)
   * @return the breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setMethodExit(String classPattern, String methodName) {
    return setMethodExit(classPattern, methodName, EventRequest.SUSPEND_EVENT_THREAD);
  }

  /**
   * Sets a method exit breakpoint with configurable suspend policy.
   *
   * @param classPattern  the class pattern
   * @param methodName    the method name (null for all methods)
   * @param suspendPolicy suspend policy (SUSPEND_EVENT_THREAD, SUSPEND_ALL, or
   *                      SUSPEND_NONE)
   * @return the breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setMethodExit(String classPattern, String methodName, int suspendPolicy) {
    try {
      long id = nextBreakpointId.getAndIncrement();

      MethodExitRequest request = erm.createMethodExitRequest();

      // Add class filter
      request.addClassFilter(classPattern);

      // Store class pattern and method name for later retrieval
      request.putProperty("classPattern", classPattern);
      if (methodName != null) {
        request.putProperty("methodName", methodName);
      }

      request.setSuspendPolicy(suspendPolicy);
      request.putProperty("breakpointId", id);
      request.enable();

      exitBreakpoints.put(id, request);

      String policyName = suspendPolicyToString(suspendPolicy);
      logger.info("Method exit breakpoint set: ID={}, class={}, method={} (suspend: {})", id, classPattern,
          methodName != null ? methodName : "*", policyName);

      return id;

    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_SET_FAILED,
          "Failed to set method exit breakpoint: " + e.getMessage(), e);
    }
  }

  /**
   * Sets both entry and exit breakpoints for a method.
   *
   * @param classPattern the class pattern
   * @param methodName   the method name (null for all methods)
   * @return array of [entryId, exitId]
   * @throws DebuggerException if breakpoints cannot be set
   */
  public long[] setMethodBreakpoint(String classPattern, String methodName) {
    long entryId = setMethodEntry(classPattern, methodName);
    long exitId = setMethodExit(classPattern, methodName);
    return new long[] { entryId, exitId };
  }

  /**
   * Removes a method breakpoint.
   *
   * @param breakpointId the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void removeBreakpoint(long breakpointId) {
    boolean removed = false;

    // Try entry breakpoints
    MethodEntryRequest entryRequest = entryBreakpoints.remove(breakpointId);
    if (entryRequest != null) {
      erm.deleteEventRequest(entryRequest);
      removed = true;
      logger.info("Method entry breakpoint removed: ID={}", breakpointId);
    }

    // Try exit breakpoints
    MethodExitRequest exitRequest = exitBreakpoints.remove(breakpointId);
    if (exitRequest != null) {
      erm.deleteEventRequest(exitRequest);
      removed = true;
      logger.info("Method exit breakpoint removed: ID={}", breakpointId);
    }

    if (!removed) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_NOT_FOUND,
          "Method breakpoint not found: " + breakpointId);
    }
  }

  /**
   * Removes all method breakpoints.
   */
  public void removeAllBreakpoints() {
    // Remove all entry breakpoints
    entryBreakpoints.values().forEach(erm::deleteEventRequest);
    entryBreakpoints.clear();

    // Remove all exit breakpoints
    exitBreakpoints.values().forEach(erm::deleteEventRequest);
    exitBreakpoints.clear();

    logger.info("All method breakpoints removed");
  }

  /**
   * Enables a method breakpoint.
   *
   * @param breakpointId the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void enableBreakpoint(long breakpointId) {
    EventRequest request = findBreakpoint(breakpointId);
    if (request == null) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_NOT_FOUND,
          "Method breakpoint not found: " + breakpointId);
    }

    request.enable();
    logger.info("Method breakpoint enabled: ID={}", breakpointId);
  }

  /**
   * Disables a method breakpoint.
   *
   * @param breakpointId the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void disableBreakpoint(long breakpointId) {
    EventRequest request = findBreakpoint(breakpointId);
    if (request == null) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_NOT_FOUND,
          "Method breakpoint not found: " + breakpointId);
    }

    request.disable();
    logger.info("Method breakpoint disabled: ID={}", breakpointId);
  }

  /**
   * Lists all method breakpoints.
   *
   * @return list of breakpoint info maps
   */
  public List<Map<String, Object>> listBreakpoints() {
    List<Map<String, Object>> result = new ArrayList<>();

    // Entry breakpoints
    for (Map.Entry<Long, MethodEntryRequest> entry : entryBreakpoints.entrySet()) {
      result.add(Map.of("id", entry.getKey(), "type", "method_entry", "class_pattern", getClassFilter(entry.getValue()),
          "method_name",
          entry.getValue().getProperty("methodName") != null ? entry.getValue().getProperty("methodName") : "*",
          "enabled", entry.getValue().isEnabled()));
    }

    // Exit breakpoints
    for (Map.Entry<Long, MethodExitRequest> entry : exitBreakpoints.entrySet()) {
      result.add(Map.of("id", entry.getKey(), "type", "method_exit", "class_pattern", getClassFilter(entry.getValue()),
          "method_name",
          entry.getValue().getProperty("methodName") != null ? entry.getValue().getProperty("methodName") : "*",
          "enabled", entry.getValue().isEnabled()));
    }

    return result;
  }

  /**
   * Checks if a method event matches the method name filter.
   *
   * @param request    the event request
   * @param methodName the actual method name from the event
   * @return true if matches or no filter is set
   */
  public boolean matchesMethodFilter(EventRequest request, String methodName) {
    Object filterObj = request.getProperty("methodName");
    if (filterObj == null) {
      return true; // No filter = match all
    }

    String filter = filterObj.toString();
    return filter.equals(methodName);
  }

  // ========== Internal Methods ==========

  /**
   * Finds a breakpoint request by ID.
   */
  private EventRequest findBreakpoint(long breakpointId) {
    EventRequest request = entryBreakpoints.get(breakpointId);
    if (request != null) {
      return request;
    }

    return exitBreakpoints.get(breakpointId);
  }

  /**
   * Gets the class filter from a request.
   */
  private String getClassFilter(EventRequest request) {
    Object classPattern = request.getProperty("classPattern");
    return classPattern != null ? classPattern.toString() : "*";
  }

  /**
   * Converts suspend policy constant to readable string.
   *
   * @param suspendPolicy the suspend policy constant
   * @return human-readable policy name
   */
  private String suspendPolicyToString(int suspendPolicy) {
    return switch (suspendPolicy) {
    case EventRequest.SUSPEND_NONE -> "none";
    case EventRequest.SUSPEND_EVENT_THREAD -> "thread";
    case EventRequest.SUSPEND_ALL -> "all";
    default -> "unknown";
    };
  }
}
