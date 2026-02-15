package com.bitsapplied.descartes.debugger.breakpoints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
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
  private final Map<String, List<Long>> pendingBreakpointIdsByClass = new ConcurrentHashMap<>();
  private final Map<String, ClassPrepareRequest> classPrepareRequests = new ConcurrentHashMap<>();

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
    return setBreakpoint(className, lineNumber, null, EventRequest.SUSPEND_EVENT_THREAD, true);
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
    return setBreakpoint(className, lineNumber, condition, EventRequest.SUSPEND_EVENT_THREAD, true);
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
    return setBreakpoint(className, lineNumber, condition, suspendPolicy, true);
  }

  /**
   * Sets a breakpoint at the specified location with configurable suspend policy
   * and deferred class loading behavior.
   *
   * @param className       fully qualified class name
   * @param lineNumber      line number (1-based)
   * @param condition       optional condition expression (null for unconditional)
   * @param suspendPolicy   suspend policy (SUSPEND_EVENT_THREAD, SUSPEND_ALL, or
   *                        SUSPEND_NONE)
   * @param deferIfUnloaded whether to defer binding until class load when class
   *                        is not yet loaded
   * @return breakpoint ID
   * @throws DebuggerException if breakpoint cannot be set
   */
  public long setBreakpoint(String className, int lineNumber, String condition, int suspendPolicy,
      boolean deferIfUnloaded) {
    return setBreakpoint(className, lineNumber, condition, suspendPolicy, deferIfUnloaded, BreakpointLineMode.EXACT,
        true, 0);
  }

  /**
   * Sets a breakpoint with configurable line resolution behavior.
   *
   * @param className       fully qualified class name
   * @param lineNumber      requested line number (1-based)
   * @param condition       optional condition expression
   * @param suspendPolicy   suspend policy
   * @param deferIfUnloaded whether to defer if class is not loaded
   * @param lineMode        line resolution mode
   * @param strictSameMethod when true, reject closest-line snapping across method
   *                         boundaries
   * @param maxLineDelta    maximum allowed absolute line delta for closest mode
   * @return breakpoint ID
   */
  public long setBreakpoint(String className, int lineNumber, String condition, int suspendPolicy,
      boolean deferIfUnloaded, BreakpointLineMode lineMode, boolean strictSameMethod, int maxLineDelta) {
    return setBreakpointWithResolution(className, lineNumber, condition, suspendPolicy, deferIfUnloaded, lineMode,
        strictSameMethod, maxLineDelta).breakpointId();
  }

  /**
   * Sets a breakpoint and returns line-resolution metadata used for binding.
   */
  public SetBreakpointResult setBreakpointWithResolution(String className, int lineNumber, String condition,
      int suspendPolicy, boolean deferIfUnloaded, BreakpointLineMode lineMode, boolean strictSameMethod,
      int maxLineDelta) {
    try {
      LineResolutionInternal resolvedLine = resolveLineInternal(className, lineNumber, lineMode, strictSameMethod,
          maxLineDelta, deferIfUnloaded);
      BreakpointLineResolution lineResolution = resolvedLine.resolution();
      int targetLine =
          lineResolution.pendingClassLoad() ? lineNumber : Objects.requireNonNullElse(lineResolution.resolvedLine(),
              lineNumber);

      // Check for existing breakpoint at this resolved location to prevent duplicates
      if (hasBreakpointAt(className, targetLine)) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_ALREADY_EXISTS,
            String.format("Breakpoint already exists at %s:%d", className, targetLine));
      }

      if (lineResolution.pendingClassLoad()) {
        long id = createPendingBreakpoint(className, targetLine, condition, suspendPolicy);
        return new SetBreakpointResult(id, lineResolution);
      }

      long id = nextId.getAndIncrement();
      BreakpointInfo info = BreakpointInfo.builder().id(id).className(className).lineNumber(targetLine)
          .location(resolvedLine.location()).condition(condition).verified(true).state(BreakpointState.VERIFIED)
          .enabled(true).suspendPolicy(suspendPolicy).build();

      BreakpointInfo boundInfo = bindBreakpointRequest(info);
      breakpoints.put(id, boundInfo);
      if (lineResolution.resolvedLine() != null && lineResolution.resolvedLine().intValue() != lineNumber) {
        logger.info("Breakpoint {} set at {}:{} (requested line {}, mode {}, suspend: {})", id, className, targetLine,
            lineNumber, lineResolution.mode().toApiValue(), suspendPolicyToString(suspendPolicy));
      } else {
        logger.info("Breakpoint {} set at {}:{} (suspend: {})", id, className, targetLine,
            suspendPolicyToString(suspendPolicy));
      }
      return new SetBreakpointResult(id, lineResolution);

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
      if (info.request() != null) {
        erm.deleteEventRequest(info.request());
      }
      logger.info("Breakpoint {} removed from {}:{}", id, info.className(), info.lineNumber());
    } catch (Exception e) {
      logger.warn("Error deleting breakpoint request for ID {}", id, e);
    } finally {
      removePendingBreakpointId(info.className(), id);
      cleanupClassPrepareRequestIfNoPending(info.className());
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
        logger.warn("Error removing breakpoint {}", id, e);
      }
    }
    removeAllClassPrepareRequests();
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
   * Finds a breakpoint at a specific class/line location.
   *
   * @param className  fully qualified class name
   * @param lineNumber line number (1-based)
   * @return matching breakpoint info, or null when none exists
   */
  public BreakpointInfo findBreakpointAt(String className, int lineNumber) {
    return breakpoints.values().stream()
        .filter(bp -> bp.className().equals(className) && bp.lineNumber() == lineNumber)
        .findFirst().orElse(null);
  }

  /**
   * Creates or updates a breakpoint at the provided class/line location.
   *
   * <p>
   * If no breakpoint exists at the location, a new one is created.
   * If a breakpoint already exists, its mutable configuration is updated in place.
   *
   * @param className       fully qualified class name
   * @param lineNumber      line number (1-based)
   * @param condition       optional condition expression
   * @param suspendPolicy   suspend policy
   * @param deferIfUnloaded whether unloaded classes should create pending
   *                        breakpoints
   * @param enabled         whether the resulting breakpoint should be enabled
   * @return upsert result containing action and latest breakpoint info
   */
  public BreakpointUpsertResult upsertBreakpoint(String className, int lineNumber, String condition, int suspendPolicy,
      boolean deferIfUnloaded, boolean enabled) {
    return upsertBreakpoint(className, lineNumber, condition, suspendPolicy, deferIfUnloaded, enabled,
        BreakpointLineMode.EXACT, true, 0);
  }

  /**
   * Creates or updates a breakpoint with configurable line resolution.
   */
  public BreakpointUpsertResult upsertBreakpoint(String className, int lineNumber, String condition, int suspendPolicy,
      boolean deferIfUnloaded, boolean enabled, BreakpointLineMode lineMode, boolean strictSameMethod,
      int maxLineDelta) {
    LineResolutionInternal lineResolution =
        resolveLineInternal(className, lineNumber, lineMode, strictSameMethod, maxLineDelta, deferIfUnloaded);
    int targetLine =
        lineResolution.resolution().pendingClassLoad() ? lineNumber : Objects.requireNonNullElse(
            lineResolution.resolution().resolvedLine(), lineNumber);
    BreakpointInfo existing = findBreakpointAt(className, targetLine);
    if (existing == null) {
      SetBreakpointResult createdResult = setBreakpointWithResolution(className, lineNumber, condition, suspendPolicy,
          deferIfUnloaded, lineMode, strictSameMethod, maxLineDelta);
      BreakpointInfo created = getBreakpoint(createdResult.breakpointId());
      if (!enabled) {
        created = updateBreakpoint(createdResult.breakpointId(), condition, suspendPolicy, false);
      }
      return new BreakpointUpsertResult(created, BreakpointUpsertAction.CREATED, createdResult.lineResolution());
    }

    BreakpointInfo updated = updateBreakpoint(existing.id(), condition, suspendPolicy, enabled);
    BreakpointUpsertAction action = hasConfigurationChanged(existing, updated) ? BreakpointUpsertAction.UPDATED
        : BreakpointUpsertAction.UNCHANGED;
    return new BreakpointUpsertResult(updated, action, lineResolution.resolution());
  }

  /**
   * Resolves the requested line to an executable line according to the provided
   * mode.
   */
  public BreakpointLineResolution resolveLine(String className, int lineNumber, BreakpointLineMode lineMode,
      boolean strictSameMethod, int maxLineDelta, boolean deferIfUnloaded) {
    return resolveLineInternal(className, lineNumber, lineMode, strictSameMethod, maxLineDelta, deferIfUnloaded)
        .resolution();
  }

  /**
   * Updates mutable breakpoint configuration for an existing breakpoint.
   *
   * @param id            breakpoint identifier
   * @param condition     optional condition expression
   * @param suspendPolicy suspend policy
   * @param enabled       enabled state
   * @return updated breakpoint info
   */
  public BreakpointInfo updateBreakpoint(long id, String condition, int suspendPolicy, boolean enabled) {
    BreakpointInfo info = getBreakpoint(id);
    String normalizedCondition = normalizeCondition(condition);
    boolean conditionChanged = !Objects.equals(normalizeCondition(info.condition()), normalizedCondition);
    boolean suspendPolicyChanged = info.suspendPolicy() != suspendPolicy;
    boolean enabledChanged = info.isEnabled() != enabled;

    if (!conditionChanged && !suspendPolicyChanged && !enabledChanged) {
      return info;
    }

    BreakpointInfo updated =
        info.toBuilder().condition(normalizedCondition).suspendPolicy(suspendPolicy).enabled(enabled).build();

    if (info.request() != null) {
      BreakpointRequest request = info.request();
      if (suspendPolicyChanged) {
        request.disable();
        request.setSuspendPolicy(suspendPolicy);
      }
      if (conditionChanged && normalizedCondition != null) {
        request.putProperty("condition", normalizedCondition);
      }
      if (enabled) {
        request.enable();
      } else if (enabledChanged) {
        request.disable();
      }
      updated = updated.toBuilder().request(request).enabled(request.isEnabled()).build();
    }

    breakpoints.put(id, updated);
    logger.info("Breakpoint {} updated at {}:{} (suspend: {}, enabled: {}, conditional: {})", id, updated.className(),
        updated.lineNumber(), suspendPolicyToString(suspendPolicy), updated.isEnabled(), updated.isConditional());
    return updated;
  }

  /**
   * Enables a breakpoint.
   *
   * @param id the breakpoint ID
   * @throws DebuggerException if breakpoint not found
   */
  public void enableBreakpoint(long id) {
    BreakpointInfo info = getBreakpoint(id);
    if (info.request() != null) {
      info.request().enable();
    }
    breakpoints.put(id, info.toBuilder().enabled(true).build());
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
    if (info.request() != null) {
      info.request().disable();
    }
    breakpoints.put(id, info.toBuilder().enabled(false).build());
    logger.debug("Breakpoint {} disabled", id);
  }

  /**
   * Resolves pending breakpoints when a class is prepared.
   *
   * @param referenceType the newly prepared class
   * @return resolution results for breakpoints that transitioned out of pending
   */
  public List<BreakpointResolution> resolvePendingBreakpoints(ReferenceType referenceType) {
    String className = referenceType.name();
    List<Long> pendingIds = pendingBreakpointIdsByClass.get(className);
    if (pendingIds == null || pendingIds.isEmpty()) {
      return List.of();
    }

    List<Long> idsToResolve = new ArrayList<>(pendingIds);
    List<BreakpointResolution> resolutions = new ArrayList<>();

    for (Long id : idsToResolve) {
      BreakpointInfo info = breakpoints.get(id);
      if (info == null || info.state() != BreakpointState.PENDING) {
        removePendingBreakpointId(className, id);
        continue;
      }

      try {
        Location location = findLocation(List.of(referenceType), info.lineNumber());
        if (location == null) {
          String failureReason =
              String.format("Line %d is not executable in class %s", info.lineNumber(), info.className());
          BreakpointInfo failed = info.toBuilder().state(BreakpointState.FAILED).verified(false).pendingReason(null)
              .failureReason(failureReason).build();
          breakpoints.put(id, failed);
          removePendingBreakpointId(className, id);
          resolutions.add(BreakpointResolution.failed(failed.id(), failed.className(), failed.lineNumber(),
              failureReason));
          continue;
        }

        BreakpointInfo resolved = bindBreakpointRequest(info.toBuilder().location(location).state(BreakpointState.VERIFIED)
            .verified(true).pendingReason(null).failureReason(null).build());
        breakpoints.put(id, resolved);
        removePendingBreakpointId(className, id);
        resolutions.add(BreakpointResolution.verified(resolved.id(), resolved.className(), resolved.lineNumber()));
        logger.info("Pending breakpoint {} resolved at {}:{} (suspend: {})", resolved.id(), resolved.className(),
            resolved.lineNumber(), suspendPolicyToString(resolved.suspendPolicy()));
      } catch (Exception e) {
        BreakpointInfo failed = info.toBuilder().state(BreakpointState.FAILED).verified(false).pendingReason(null)
            .failureReason("Failed to bind breakpoint: " + e.getMessage()).build();
        breakpoints.put(id, failed);
        removePendingBreakpointId(className, id);
        resolutions.add(BreakpointResolution.failed(failed.id(), failed.className(), failed.lineNumber(),
            failed.failureReason()));
        logger.error("Failed to resolve pending breakpoint {} for {}", id, className, e);
      }
    }

    cleanupClassPrepareRequestIfNoPending(className);
    return resolutions;
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
  private static String suspendPolicyToString(int suspendPolicy) {
    return switch (suspendPolicy) {
    case EventRequest.SUSPEND_NONE -> "none";
    case EventRequest.SUSPEND_EVENT_THREAD -> "thread";
    case EventRequest.SUSPEND_ALL -> "all";
    default -> "unknown";
    };
  }

  private long createPendingBreakpoint(String className, int lineNumber, String condition, int suspendPolicy) {
    long id = nextId.getAndIncrement();
    BreakpointInfo info = BreakpointInfo.builder().id(id).className(className).lineNumber(lineNumber).condition(condition)
        .verified(false).state(BreakpointState.PENDING).pendingReason("class_not_loaded").enabled(true)
        .suspendPolicy(suspendPolicy).build();
    breakpoints.put(id, info);
    pendingBreakpointIdsByClass.computeIfAbsent(className, ignored -> new ArrayList<>()).add(id);
    ensureClassPrepareRequest(className);
    logger.info("Breakpoint {} deferred for {}:{} (class not loaded yet)", id, className, lineNumber);
    return id;
  }

  private BreakpointInfo bindBreakpointRequest(BreakpointInfo info) {
    BreakpointRequest request = erm.createBreakpointRequest(info.location());
    request.setSuspendPolicy(info.suspendPolicy());
    request.putProperty("breakpointId", info.id());
    if (info.isConditional()) {
      request.putProperty("condition", info.condition());
    }
    if (info.enabled()) {
      request.enable();
    }
    return info.toBuilder().request(request).verified(true).state(BreakpointState.VERIFIED).build();
  }

  private Location findLocation(List<ReferenceType> classes, int lineNumber) {
    for (ReferenceType refType : classes) {
      try {
        List<Location> locations = refType.locationsOfLine(lineNumber);
        if (!locations.isEmpty()) {
          return locations.get(0);
        }
      } catch (AbsentInformationException e) {
        logger.debug("No line info for class {}", refType.name());
      }
    }
    return null;
  }

  private LineResolutionInternal resolveLineInternal(String className, int requestedLine, BreakpointLineMode lineMode,
      boolean strictSameMethod, int maxLineDelta, boolean deferIfUnloaded) {
    if (requestedLine <= 0) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_INVALID_LOCATION,
          "Requested line must be positive: " + requestedLine);
    }

    if (lineMode == BreakpointLineMode.CLOSEST && maxLineDelta < 0) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_INVALID_LOCATION,
          "maxLineDelta must be zero or positive");
    }

    List<ReferenceType> classes = vm.classesByName(className);
    if (classes.isEmpty()) {
      if (!deferIfUnloaded) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_CLASS_NOT_FOUND, "Class not found: " + className);
      }
      BreakpointLineResolution resolution = new BreakpointLineResolution(requestedLine, null, null, null, null,
          lineMode, strictSameMethod, maxLineDelta, true, "class_not_loaded");
      return new LineResolutionInternal(resolution, null);
    }

    LocationCandidate candidate =
        lineMode == BreakpointLineMode.EXACT ? findExactCandidate(classes, requestedLine)
            : findClosestCandidate(classes, requestedLine);

    if (candidate == null) {
      throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_LINE_NOT_EXECUTABLE,
          String.format("Line %d is not executable in class %s", requestedLine, className));
    }

    if (lineMode == BreakpointLineMode.CLOSEST) {
      if (candidate.lineDelta() > maxLineDelta) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_LINE_NOT_EXECUTABLE, String.format(
            "Nearest executable line for %s:%d is %d (delta=%d), exceeding max_line_delta=%d", className,
            requestedLine, candidate.lineNumber(), candidate.lineDelta(), maxLineDelta));
      }
      if (strictSameMethod && !isLineWithinMethodRange(candidate.location().method(), requestedLine)) {
        throw new DebuggerException(DebuggerErrorCode.BREAKPOINT_LINE_NOT_EXECUTABLE, String.format(
            "Nearest executable line for %s:%d is %d in method %s, outside requested line context",
            className, requestedLine, candidate.lineNumber(), candidate.methodName()));
      }
    }

    BreakpointLineResolution resolution = new BreakpointLineResolution(requestedLine, candidate.lineNumber(),
        candidate.className(), candidate.methodName(), candidate.lineDelta(), lineMode, strictSameMethod, maxLineDelta,
        false, null);
    return new LineResolutionInternal(resolution, candidate.location());
  }

  private LocationCandidate findExactCandidate(List<ReferenceType> classes, int requestedLine) {
    for (ReferenceType refType : classes) {
      try {
        List<Location> locations = refType.locationsOfLine(requestedLine);
        if (!locations.isEmpty()) {
          Location location = locations.get(0);
          return new LocationCandidate(location, refType.name(), location.method().name(), location.lineNumber(), 0);
        }
      } catch (AbsentInformationException e) {
        logger.debug("No line info for class {}", refType.name());
      }
    }
    return null;
  }

  private LocationCandidate findClosestCandidate(List<ReferenceType> classes, int requestedLine) {
    List<LocationCandidate> candidates = new ArrayList<>();
    for (ReferenceType refType : classes) {
      try {
        for (Location location : refType.allLineLocations()) {
          int candidateLine = location.lineNumber();
          if (candidateLine <= 0) {
            continue;
          }
          candidates.add(new LocationCandidate(location, refType.name(), location.method().name(), candidateLine,
              Math.abs(candidateLine - requestedLine)));
        }
      } catch (AbsentInformationException e) {
        logger.debug("No line info for class {}", refType.name());
      }
    }
    return candidates.stream()
        .min(Comparator.comparingInt(LocationCandidate::lineDelta).thenComparingInt(LocationCandidate::lineNumber)
            .thenComparing(LocationCandidate::className).thenComparing(LocationCandidate::methodName))
        .orElse(null);
  }

  private boolean isLineWithinMethodRange(Method method, int requestedLine) {
    try {
      int minLine = Integer.MAX_VALUE;
      int maxLine = Integer.MIN_VALUE;
      for (Location location : method.allLineLocations()) {
        int line = location.lineNumber();
        if (line <= 0) {
          continue;
        }
        minLine = Math.min(minLine, line);
        maxLine = Math.max(maxLine, line);
      }
      if (minLine == Integer.MAX_VALUE) {
        return false;
      }
      return requestedLine >= minLine && requestedLine <= maxLine;
    } catch (AbsentInformationException e) {
      logger.debug("No method line info for {}#{}", method.declaringType().name(), method.name());
      return false;
    }
  }

  private void ensureClassPrepareRequest(String className) {
    if (classPrepareRequests.containsKey(className)) {
      return;
    }
    ClassPrepareRequest request = erm.createClassPrepareRequest();
    request.addClassFilter(className);
    request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
    request.enable();
    classPrepareRequests.put(className, request);
    logger.debug("Registered class-prepare request for {}", className);
  }

  private void removePendingBreakpointId(String className, long id) {
    List<Long> ids = pendingBreakpointIdsByClass.get(className);
    if (ids == null) {
      return;
    }
    ids.remove(id);
    if (ids.isEmpty()) {
      pendingBreakpointIdsByClass.remove(className);
    }
  }

  private void cleanupClassPrepareRequestIfNoPending(String className) {
    List<Long> ids = pendingBreakpointIdsByClass.get(className);
    if (ids != null && !ids.isEmpty()) {
      return;
    }
    ClassPrepareRequest request = classPrepareRequests.remove(className);
    if (request != null) {
      try {
        erm.deleteEventRequest(request);
      } catch (Exception e) {
        logger.warn("Failed to delete class-prepare request for {}", className, e);
      }
    }
  }

  private void removeAllClassPrepareRequests() {
    List<String> classNames = new ArrayList<>(classPrepareRequests.keySet());
    for (String className : classNames) {
      ClassPrepareRequest request = classPrepareRequests.remove(className);
      if (request != null) {
        try {
          erm.deleteEventRequest(request);
        } catch (Exception e) {
          logger.warn("Failed to delete class-prepare request for {}", className, e);
        }
      }
    }
    pendingBreakpointIdsByClass.clear();
  }

  private boolean hasConfigurationChanged(BreakpointInfo before, BreakpointInfo after) {
    return !Objects.equals(normalizeCondition(before.condition()), normalizeCondition(after.condition()))
        || before.suspendPolicy() != after.suspendPolicy() || before.isEnabled() != after.isEnabled();
  }

  private static String normalizeCondition(String condition) {
    return condition == null || condition.isBlank() ? null : condition;
  }

  /**
   * Upsert action details for breakpoint set/update operations.
   */
  public enum BreakpointUpsertAction {
    CREATED, UPDATED, UNCHANGED;

    public String toApiValue() {
      return name().toLowerCase();
    }
  }

  /**
   * Result of a breakpoint upsert call.
   */
  public record BreakpointUpsertResult(BreakpointInfo breakpoint, BreakpointUpsertAction action,
      BreakpointLineResolution lineResolution) {
  }

  /**
   * Result of a breakpoint set call that includes line-resolution metadata.
   */
  public record SetBreakpointResult(long breakpointId, BreakpointLineResolution lineResolution) {
  }

  /**
   * Line resolution modes for breakpoint placement.
   */
  public enum BreakpointLineMode {
    EXACT, CLOSEST;

    public String toApiValue() {
      return name().toLowerCase();
    }
  }

  /**
   * Line-resolution metadata used to place or validate breakpoints.
   */
  public record BreakpointLineResolution(int requestedLine, Integer resolvedLine, String resolvedClass,
      String resolvedMethod, Integer lineDelta, BreakpointLineMode mode, boolean strictSameMethod, int maxLineDelta,
      boolean pendingClassLoad, String rejectionReason) {
    public Map<String, Object> toMap() {
      Map<String, Object> map = new HashMap<>();
      map.put("requested_line", requestedLine);
      map.put("resolution_mode", mode.toApiValue());
      map.put("strict_same_method", strictSameMethod);
      map.put("max_line_delta", maxLineDelta);
      map.put("pending_class_load", pendingClassLoad);
      if (resolvedLine != null) {
        map.put("resolved_line", resolvedLine);
      }
      if (resolvedClass != null) {
        map.put("resolved_class", resolvedClass);
      }
      if (resolvedMethod != null) {
        map.put("resolved_method", resolvedMethod);
      }
      if (lineDelta != null) {
        map.put("line_delta", lineDelta);
      }
      if (rejectionReason != null) {
        map.put("rejection_reason", rejectionReason);
      }
      return map;
    }
  }

  private record LineResolutionInternal(BreakpointLineResolution resolution, Location location) {
  }

  private record LocationCandidate(Location location, String className, String methodName, int lineNumber,
      int lineDelta) {
  }

  /**
   * Breakpoint lifecycle state.
   */
  public enum BreakpointState {
    PENDING, VERIFIED, FAILED;

    public String toApiValue() {
      return name().toLowerCase();
    }
  }

  /**
   * Result object returned when deferred breakpoints transition on class prepare.
   */
  public record BreakpointResolution(long breakpointId, String className, int lineNumber, BreakpointState state,
      String reason) {
    public static BreakpointResolution verified(long breakpointId, String className, int lineNumber) {
      return new BreakpointResolution(breakpointId, className, lineNumber, BreakpointState.VERIFIED, null);
    }

    public static BreakpointResolution failed(long breakpointId, String className, int lineNumber, String reason) {
      return new BreakpointResolution(breakpointId, className, lineNumber, BreakpointState.FAILED, reason);
    }
  }

  /**
   * Information about a breakpoint.
   *
   * @param id         unique breakpoint ID
   * @param className  fully qualified class name
   * @param lineNumber line number (1-based)
   * @param location      JDI location (null for pending/failed breakpoints)
   * @param request       JDI breakpoint request (null for pending/failed
   *                      breakpoints)
   * @param condition     optional condition expression (null if unconditional)
   * @param verified      whether the breakpoint has been resolved to a concrete
   *                      location
   * @param state         lifecycle state
   * @param pendingReason reason for pending state
   * @param failureReason reason for failed state
   * @param enabled       whether breakpoint is enabled (used while pending)
   * @param suspendPolicy configured suspend policy
   */
  public record BreakpointInfo(long id, String className, int lineNumber, Location location, BreakpointRequest request,
      String condition, boolean verified, BreakpointState state, String pendingReason, String failureReason,
      boolean enabled, int suspendPolicy) {

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
      private BreakpointState state = BreakpointState.VERIFIED;
      private String pendingReason;
      private String failureReason;
      private boolean enabled = true;
      private int suspendPolicy = EventRequest.SUSPEND_EVENT_THREAD;

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

      public Builder state(BreakpointState state) {
        this.state = state;
        return this;
      }

      public Builder pendingReason(String pendingReason) {
        this.pendingReason = pendingReason;
        return this;
      }

      public Builder failureReason(String failureReason) {
        this.failureReason = failureReason;
        return this;
      }

      public Builder enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
      }

      public Builder suspendPolicy(int suspendPolicy) {
        this.suspendPolicy = suspendPolicy;
        return this;
      }

      public BreakpointInfo build() {
        return new BreakpointInfo(id, className, lineNumber, location, request, condition, verified, state, pendingReason,
            failureReason, enabled, suspendPolicy);
      }
    }

    /**
     * Creates a mutable builder pre-populated with current values.
     */
    public Builder toBuilder() {
      return builder().id(id).className(className).lineNumber(lineNumber).location(location).request(request)
          .condition(condition).verified(verified).state(state).pendingReason(pendingReason).failureReason(failureReason)
          .enabled(enabled).suspendPolicy(suspendPolicy);
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
      if (request != null) {
        return request.isEnabled();
      }
      return enabled;
    }

    /**
     * Gets the method name where this breakpoint is set.
     *
     * @return method name
     */
    public String getMethodName() {
      if (location == null) {
        return null;
      }
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
      map.put("enabled", isEnabled());
      map.put("verified", verified);
      map.put("state", state.toApiValue());
      map.put("suspend_policy", suspendPolicyToString(suspendPolicy));

      String methodName = getMethodName();
      if (methodName != null) {
        map.put("method", methodName);
      }

      if (condition != null) {
        map.put("condition", condition);
      }
      if (pendingReason != null) {
        map.put("pending_reason", pendingReason);
      }
      if (failureReason != null) {
        map.put("failure_reason", failureReason);
      }

      return map;
    }
  }
}
