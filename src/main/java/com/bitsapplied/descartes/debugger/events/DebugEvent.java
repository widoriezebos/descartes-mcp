package com.bitsapplied.descartes.debugger.events;

import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;

/**
 * Event wrapper for debug events.
 *
 * <p>
 * Wraps a JDWP event with its associated event set and timing information.
 * Events represent significant occurrences during debugging (e.g., breakpoint
 * hit, step completion, exception thrown, thread created).
 *
 * <p>
 * The EventSet is required for proper event handling in JDWP; it ensures that
 * when processing one event, all events that occurred at the same suspension
 * point are available together. This is important for correct debugger
 * semantics.
 */
public record DebugEvent(Event event, EventSet eventSet, long timestamp) implements StreamEvent {
  /**
   * Creates a DebugEvent with the current time.
   *
   * <p>
   * This constructor is useful when you're creating a debug event and want to
   * capture the time at which it occurred without explicitly passing the
   * timestamp.
   *
   * @param event    the JDWP event (e.g., BreakpointEvent, StepEvent)
   * @param eventSet the event set containing all events for this suspension point
   * @throws NullPointerException if event or eventSet is null
   */
  public DebugEvent(Event event, EventSet eventSet) {
    this(event, eventSet, System.currentTimeMillis());
  }

  /**
   * Gets the event type as a string.
   *
   * <p>
   * Returns the simple class name of the event for easy identification.
   *
   * @return the event type (e.g., "BreakpointEvent", "StepEvent")
   */
  public String getEventType() {
    return event.getClass().getSimpleName();
  }

  /**
   * Checks if this is a suspension event (stops execution).
   *
   * <p>
   * Suspension events include:
   * <ul>
   * <li>BreakpointEvent</li>
   * <li>StepEvent</li>
   * <li>ExceptionEvent</li>
   * <li>ThreadStartEvent</li>
   * <li>ThreadDeathEvent</li>
   * <li>ClassPrepareEvent</li>
   * </ul>
   *
   * @return true if this event suspends execution
   */
  public boolean isSuspensionEvent() {
    String eventType = getEventType();
    return eventType.equals("BreakpointEvent") || eventType.equals("StepEvent") || eventType.equals("ExceptionEvent")
        || eventType.equals("ThreadStartEvent") || eventType.equals("ThreadDeathEvent")
        || eventType.equals("ClassPrepareEvent");
  }

  /**
   * Checks if this is a breakpoint-related event.
   *
   * @return true if this event is a BreakpointEvent
   */
  public boolean isBreakpointEvent() {
    return "BreakpointEvent".equals(getEventType());
  }

  /**
   * Checks if this is a step-related event.
   *
   * @return true if this event is a StepEvent
   */
  public boolean isStepEvent() {
    return "StepEvent".equals(getEventType());
  }

  /**
   * Checks if this is an exception event.
   *
   * @return true if this event is an ExceptionEvent
   */
  public boolean isExceptionEvent() {
    return "ExceptionEvent".equals(getEventType());
  }

  /**
   * Gets the age of this event in milliseconds.
   *
   * <p>
   * Useful for detecting stale events or measuring event handling latency.
   *
   * @return age of the event in milliseconds
   */
  public long getAgeMs() {
    return System.currentTimeMillis() - timestamp;
  }

  /**
   * Gets a brief description of this event.
   *
   * @return formatted event summary
   */
  public String toShortString() {
    return String.format("%s[age=%dms]", getEventType(), getAgeMs());
  }

  /**
   * Gets a detailed description of this event.
   *
   * @return formatted event details
   */
  @Override
  public String toString() {
    return String.format("DebugEvent{type=%s, timestamp=%d, age=%dms}", getEventType(), timestamp, getAgeMs());
  }
}
