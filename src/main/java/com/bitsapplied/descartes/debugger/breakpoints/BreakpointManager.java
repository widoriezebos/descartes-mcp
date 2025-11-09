package com.bitsapplied.descartes.debugger.breakpoints;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

/**
 * Manages breakpoint lifecycle and storage.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Creating and removing breakpoints at specific locations</li>
 * <li>Tracking active breakpoints by ID</li>
 * <li>Managing JDI BreakpointRequest objects</li>
 * <li>Querying breakpoints by various criteria</li>
 * <li>Supporting conditional breakpoints (evaluated expressions)</li>
 * </ul>
 *
 * <p>
 * Thread Safety: All operations must be called on the debugger executor thread.
 */
public class BreakpointManager {
  private static final Logger logger = LoggerFactory.getLogger(BreakpointManager.class);

  private final VirtualMachine vm;
  private final EventRequestManager erm;

  // Breakpoint storage: ID -> Breakpoint info
  private final Map<Long, BreakpointInfo> breakpoints = new ConcurrentHashMap<>();

  // ID generator
  private final AtomicLong nextId = new AtomicLong(1);

  /**
   * Creates a breakpoint manager for the given VM.
   *
   * @param vm the virtual machine
   */
  public BreakpointManager(VirtualMachine vm) {
    this.vm = vm;
    this.erm = vm.eventRequestManager();
  }

  /**
   * Sets a breakpoint at the specified location.
   *
   * @param className  fully qualified class name
   * @param lineNumber line number (1-based)
   * @return breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setBreakpoint(String className, int lineNumber) {
    return setBreakpoint(className, lineNumber, null, EventRequest.SUSPEND_EVENT_THREAD);
  }

  /**
   * Sets a conditional breakpoint at the specified location.
   *
   * @param className  fully qualified class name
   * @param lineNumber line number (1-based)
   * @param condition  optional condition expression (null for unconditional)
   * @return breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setBreakpoint(String className, int lineNumber, String condition) {
    return setBreakpoint(className, lineNumber, condition, EventRequest.SUSPEND_EVENT_THREAD);
  }

  /**
   * Sets a breakpoint at the specified location with configurable suspend policy.
   *
   * @param className     fully qualified class name
   * @param lineNumber    line number (1-based)
   * @param condition     optional condition expression (null for unconditional)
   * @param suspendPolicy suspend policy (SUSPEND_EVENT_THREAD, SUSPEND_ALL, or
   *                      SUSPEND_NONE)
   * @return breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setBreakpoint(String className, int lineNumber, String condition, int suspendPolicy) {
    try {
      // Check for existing breakpoint at this location to prevent duplicates
      if (hasBreakpointAt(className, lineNumber)) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_ALREADY_EXISTS,
            String.format("Breakpoint already exists at %s:%d", className, lineNumber));
      }

      // Find all matching classes (handles inner classes)
      List<ReferenceType> classes = vm.classesByName(className);

      if (classes.isEmpty()) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_CLASS_NOT_FOUND, "Class not found: " + className);
      }

      // Try to find a location at the specified line in any of the matching classes
      Location location = null;

      for (ReferenceType refType : classes) {
        try {
          List<Location> locations = refType.locationsOfLine(lineNumber);
          if (!locations.isEmpty()) {
            location = locations.get(0);
            break;
          }
        } catch (AbsentInformationException e) {
          logger.debug("No line info for class {}", refType.name());
        }
      }

      if (location == null) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_LINE_NOT_EXECUTABLE,
            String.format("Line %d is not executable in class %s", lineNumber, className));
      }

      // Create breakpoint request
      BreakpointRequest request = erm.createBreakpointRequest(location);
      request.setSuspendPolicy(suspendPolicy);
      request.enable();

      // Generate ID and store breakpoint info
      long id = nextId.getAndIncrement();
      BreakpointInfo info = new BreakpointInfo(id, className, lineNumber, location, request, condition, true);

      breakpoints.put(id, info);

      String policyName = suspendPolicyToString(suspendPolicy);
      logger.info("Breakpoint {} set at {}:{} (suspend: {})", id, className, lineNumber, policyName);

      return id;

    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_SET_FAILED,
          "Failed to set breakpoint: " + e.getMessage(), e);
    }
  }

  /**
   * Removes a breakpoint by ID.
   *
   * @param id the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void removeBreakpoint(long id) {
    BreakpointInfo info = breakpoints.remove(id);

    if (info == null) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_NOT_FOUND, "Breakpoint not found: " + id);
    }

    try {
      erm.deleteEventRequest(info.request());
      logger.info("Breakpoint {} removed from {}:{}", id, info.className(), info.lineNumber());
    } catch (Exception e) {
      logger.warn("Error deleting breakpoint request for ID {}: {}", id, e.getMessage());
    }
  }

  /**
   * Removes all breakpoints.
   */
  public void removeAllBreakpoints() {
    List<Long> ids = new ArrayList<>(breakpoints.keySet());
    for (Long id : ids) {
      try {
        removeBreakpoint(id);
      } catch (Exception e) {
        logger.warn("Error removing breakpoint {}: {}", id, e.getMessage());
      }
    }
    logger.info("All breakpoints removed");
  }

  /**
   * Gets information about a specific breakpoint.
   *
   * @param id the breakpoint ID
   * @return breakpoint information
   * @throws DebuggerException if breakpoint not found
   */
  public BreakpointInfo getBreakpoint(long id) {
    BreakpointInfo info = breakpoints.get(id);
    if (info == null) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_NOT_FOUND, "Breakpoint not found: " + id);
    }
    return info;
  }

  /**
   * Gets all active breakpoints.
   *
   * @return list of breakpoint information
   */
  public List<BreakpointInfo> getAllBreakpoints() {
    return new ArrayList<>(breakpoints.values());
  }

  /**
   * Gets breakpoints for a specific class.
   *
   * @param className the class name
   * @return list of breakpoints in that class
   */
  public List<BreakpointInfo> getBreakpointsForClass(String className) {
    return breakpoints.values().stream().filter(bp -> bp.className().equals(className)).toList();
  }

  /**
   * Checks if a breakpoint exists at the specified location.
   *
   * @param className  the class name
   * @param lineNumber the line number
   * @return true if a breakpoint exists at that location
   */
  public boolean hasBreakpointAt(String className, int lineNumber) {
    return breakpoints.values().stream()
        .anyMatch(bp -> bp.className().equals(className) && bp.lineNumber() == lineNumber);
  }

  /**
   * Enables a breakpoint.
   *
   * @param id the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void enableBreakpoint(long id) {
    BreakpointInfo info = getBreakpoint(id);
    info.request().enable();
    logger.debug("Breakpoint {} enabled", id);
  }

  /**
   * Disables a breakpoint.
   *
   * @param id the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void disableBreakpoint(long id) {
    BreakpointInfo info = getBreakpoint(id);
    info.request().disable();
    logger.debug("Breakpoint {} disabled", id);
  }

  /**
   * Gets the total number of active breakpoints.
   *
   * @return breakpoint count
   */
  public int getBreakpointCount() {
    return breakpoints.size();
  }

  /**
   * Clears all breakpoints and resets the manager.
   */
  public void clear() {
    removeAllBreakpoints();
    nextId.set(1);
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

  /**
   * Information about a breakpoint.
   *
   * @param id         unique breakpoint ID
   * @param className  fully qualified class name
   * @param lineNumber line number (1-based)
   * @param location   JDI location
   * @param request    JDI breakpoint request
   * @param condition  optional condition expression (null if unconditional)
   * @param verified   whether the breakpoint is verified (always true for line
   *                   breakpoints)
   */
  public record BreakpointInfo(long id, String className, int lineNumber, Location location, BreakpointRequest request,
      String condition, boolean verified) {

    /**
     * Create a builder for constructing BreakpointInfo instances.
     */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Builder for BreakpointInfo with fluent API.
     */
    public static class Builder {
      private long id;
      private String className;
      private int lineNumber;
      private Location location;
      private BreakpointRequest request;
      private String condition;
      private boolean verified;

      public Builder id(long id) {
        this.id = id;
        return this;
      }

      public Builder className(String className) {
        this.className = className;
        return this;
      }

      public Builder lineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
        return this;
      }

      public Builder location(Location location) {
        this.location = location;
        return this;
      }

      public Builder request(BreakpointRequest request) {
        this.request = request;
        return this;
      }

      public Builder condition(String condition) {
        this.condition = condition;
        return this;
      }

      public Builder verified(boolean verified) {
        this.verified = verified;
        return this;
      }

      public BreakpointInfo build() {
        return new BreakpointInfo(id, className, lineNumber, location, request, condition, verified);
      }
    }

    /**
     * Checks if this is a conditional breakpoint.
     *
     * @return true if conditional
     */
    public boolean isConditional() {
      return condition != null && !condition.isBlank();
    }

    /**
     * Checks if this breakpoint is currently enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
      return request.isEnabled();
    }

    /**
     * Gets the method name where this breakpoint is set.
     *
     * @return method name
     */
    public String getMethodName() {
      return location.method().name();
    }

    /**
     * Converts to a map for JSON serialization.
     *
     * @return map representation
     */
    public Map<String, Object> toMap() {
      Map<String, Object> map = new HashMap<>();
      map.put("id", id);
      map.put("class_name", className);
      map.put("line_number", lineNumber);
      map.put("method", getMethodName());
      map.put("enabled", isEnabled());
      map.put("verified", verified);

      if (condition != null) {
        map.put("condition", condition);
      }

      return map;
    }
  }
}
