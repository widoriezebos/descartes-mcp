package com.bitsapplied.descartes.debugger.events;

/**
 * Marker interface for events that can be published through the EventHub event
 * stream.
 *
 * <p>
 * This sealed interface ensures type safety while allowing both debug events
 * (from JDWP) and error events (from event processing failures) to flow through
 * the same reactive stream.
 *
 * <p>
 * <b>Permitted Types:</b>
 * <ul>
 * <li>{@link DebugEvent} - Wraps JDWP events (breakpoints, steps, etc.)</li>
 * <li>{@link ErrorEvent} - Represents recoverable errors in event
 * processing</li>
 * </ul>
 *
 * <p>
 * <b>Usage:</b>
 *
 * <pre>{@code
 * // Subscribe to all events
 * eventHub.events().subscribe(event -> {
 *   switch (event) {
 *   case DebugEvent de -> handleDebugEvent(de);
 *   case ErrorEvent ee -> handleError(ee);
 *   }
 * });
 *
 * // Or filter by type
 * eventHub.eventsOfType(DebugEvent.class).subscribe(this::handleDebugEvent);
 * eventHub.eventsOfType(ErrorEvent.class).subscribe(this::handleErrorEvent);
 * }</pre>
 */
public sealed interface StreamEvent permits DebugEvent, ErrorEvent {

  /**
   * Gets the timestamp when this event occurred or was created.
   *
   * @return timestamp in milliseconds since epoch
   */
  long timestamp();

  /**
   * Gets a brief description of this event.
   *
   * @return human-readable event summary
   */
  String toShortString();
}
