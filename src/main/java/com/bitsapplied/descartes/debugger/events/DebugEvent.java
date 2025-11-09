package com.bitsapplied.descartes.debugger.events;

import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;

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
    return event instanceof BreakpointEvent || event instanceof StepEvent || event instanceof ExceptionEvent
        || event instanceof ThreadStartEvent || event instanceof ThreadDeathEvent || event instanceof ClassPrepareEvent;
  }

  /**
   * Checks if this is a breakpoint-related event.
   *
   * @return true if this event is a BreakpointEvent
   */
  public boolean isBreakpointEvent() {
    return event instanceof BreakpointEvent;
  }

  /**
   * Checks if this is a step-related event.
   *
   * @return true if this event is a StepEvent
   */
  public boolean isStepEvent() {
    return event instanceof StepEvent;
  }

  /**
   * Checks if this is an exception event.
   *
   * @return true if this event is an ExceptionEvent
   */
  public boolean isExceptionEvent() {
    return event instanceof ExceptionEvent;
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
