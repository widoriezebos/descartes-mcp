package com.bitsapplied.descartes.debugger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.events.DebugEvent;
import com.bitsapplied.descartes.debugger.events.ErrorEvent;
import com.bitsapplied.descartes.debugger.events.StreamEvent;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

/**
 * Reactive event hub for JDWP events using RxJava.
 *
 * <p>
 * Architecture:
 * <ul>
 * <li>Dedicated daemon thread (EventHub-Thread) reads JDWP EventQueue</li>
 * <li>Events published to RxJava Observable for reactive processing</li>
 * <li>Synchronous handoff to debuggerExecutor for thread safety</li>
 * <li>Support for event filtering via {@code .ofType()}</li>
 * <li>Proper EventSet suspend policy handling</li>
 * </ul>
 *
 * <p>
 * Usage:
 * 
 * <pre>
 * EventHub hub = new EventHub(vm, debuggerExecutor);
 * hub.start();
 *
 * // Subscribe to specific event types
 * hub.events().filter(e -> e.isBreakpointEvent()).subscribe(event -> handleBreakpoint(event));
 *
 * // Or use type-safe filtering
 * hub.eventsOfType(BreakpointEvent.class).subscribe(event -> handleBreakpoint(event));
 * </pre>
 */
public class EventHub {
  private static final Logger logger = LoggerFactory.getLogger(EventHub.class);

  private final VirtualMachine vm;
  private final ExecutorService debuggerExecutor;
  private final Subject<StreamEvent> eventSubject;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread eventThread;

  /**
   * Creates an event hub for the given virtual machine.
   *
   * @param vm               the virtual machine to monitor
   * @param debuggerExecutor executor for synchronous event processing
   */
  public EventHub(VirtualMachine vm, ExecutorService debuggerExecutor) {
    this.vm = vm;
    this.debuggerExecutor = debuggerExecutor;
    this.eventSubject = PublishSubject.<StreamEvent>create().toSerialized();
  }

  /**
   * Starts the event processing thread.
   *
   * <p>
   * Thread-safe - synchronized with stop() to prevent race conditions where stop
   * could be called before the event thread is fully initialized.
   */
  public synchronized void start() {
    if (running.getAndSet(true)) {
      logger.warn("EventHub already running");
      return;
    }

    eventThread = new Thread(this::eventLoop, "EventHub-Thread");
    eventThread.setDaemon(true);
    eventThread.start();

    logger.info("EventHub started");
  }

  /**
   * Stops the event processing thread.
   *
   * <p>
   * Thread-safe - synchronized with start() to prevent race conditions where the
   * event thread could be started while stopping or vice versa.
   */
  public synchronized void stop() {
    if (!running.getAndSet(false)) {
      return;
    }

    if (eventThread != null) {
      eventThread.interrupt();
      try {
        eventThread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    eventSubject.onComplete();
    logger.info("EventHub stopped");
  }

  /**
   * Gets the observable stream of all events (debug events and error events).
   *
   * @return observable of stream events
   */
  public Observable<StreamEvent> events() {
    return eventSubject.hide();
  }

  /**
   * Gets a filtered stream of events matching a specific stream event type.
   *
   * <p>
   * Example usage:
   *
   * <pre>{@code
   * // Subscribe to debug events only
   * eventHub.eventsOfType(DebugEvent.class).subscribe(this::handleDebugEvent);
   *
   * // Subscribe to error events only
   * eventHub.eventsOfType(ErrorEvent.class).subscribe(this::handleErrorEvent);
   * }</pre>
   *
   * @param eventClass the stream event class to filter for (DebugEvent or
   *                   ErrorEvent)
   * @param <T>        the event type
   * @return observable of matching events
   */
  public <T extends StreamEvent> Observable<T> eventsOfType(Class<T> eventClass) {
    return eventSubject.filter(eventClass::isInstance).map(eventClass::cast);
  }

  /**
   * Gets a filtered stream of JDI events matching a specific JDI event type.
   *
   * <p>
   * This is a convenience method for filtering by JDI event types within
   * DebugEvents.
   *
   * @param jdiEventClass the JDI event class to filter for (e.g.,
   *                      BreakpointEvent.class)
   * @param <T>           the JDI event type
   * @return observable of matching JDI events
   */
  public <T extends Event> Observable<T> jdiEventsOfType(Class<T> jdiEventClass) {
    return eventsOfType(DebugEvent.class).filter(debugEvent -> jdiEventClass.isInstance(debugEvent.event()))
        .map(debugEvent -> jdiEventClass.cast(debugEvent.event()));
  }

  /**
   * Main event processing loop - runs on dedicated EventHub-Thread.
   */
  private void eventLoop() {
    logger.debug("Event loop started");

    EventQueue eventQueue = vm.eventQueue();

    while (running.get()) {
      try {
        // Blocking wait for next event set
        EventSet eventSet = eventQueue.remove();

        if (eventSet == null) {
          continue;
        }

        // Process event set synchronously on debugger executor
        debuggerExecutor.submit(() -> processEventSet(eventSet));

      } catch (InterruptedException e) {
        logger.debug("Event loop interrupted");
        Thread.currentThread().interrupt();
        break;
      } catch (Throwable t) {
        // Fatal JVM errors (OutOfMemoryError, StackOverflowError, etc.) should terminate the event loop
        // AssertionError is generally safe to continue from in production
        if (t instanceof Error && !(t instanceof AssertionError)) {
          logger.error("Fatal JVM error in event loop - terminating event processing", t);
          try {
            // Attempt to notify subscribers of fatal error (wrap Error as Exception for ErrorEvent)
            Exception fatalException = new RuntimeException("Fatal JVM error: " + t.getClass().getName(), t);
            ErrorEvent fatalEvent = ErrorEvent.critical(fatalException, "Fatal JVM error in event loop");
            eventSubject.onNext(fatalEvent);
          } catch (Throwable notifyError) {
            logger.error("Failed to emit fatal error event", notifyError);
          }
          throw (Error) t; // Rethrow to terminate event loop
        }

        // Recoverable exceptions - log and emit error event
        logger.error("Error in event loop (recoverable): {}", t.getMessage(), t);

        // Emit error event to subscribers instead of terminating the stream
        try {
          Exception exception = (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
          ErrorEvent errorEvent = ErrorEvent.recoverable(exception, "Event loop processing error");
          eventSubject.onNext(errorEvent);
        } catch (Throwable nested) {
          logger.error("Failed to emit error event (critical) - this should never happen", nested);
          // If we can't even emit the error, there's nothing more we can do
          // Continue processing - the event loop is still functional
        }
      }
    }

    logger.debug("Event loop exited");
  }

  /**
   * Processes an event set - publishes events and handles suspend policy.
   *
   * <p>
   * Must be called on debuggerExecutor for thread safety.
   *
   * <p>
   * <b>IMPORTANT - VM Suspend/Resume Responsibility:</b>
   * <ul>
   * <li>When eventSet.suspendPolicy() is {@link EventRequest#SUSPEND_ALL} or
   * {@link EventRequest#SUSPEND_EVENT_THREAD}, the VM is suspended and WILL
   * REMAIN SUSPENDED until explicitly resumed.</li>
   * <li>Event subscribers (breakpoint handlers, step handlers) MUST call
   * {@code vm.resume()} or {@code thread.resume()} after processing the event.
   * </li>
   * <li>Failure to resume will cause application deadlock - the debugged
   * application will hang indefinitely.</li>
   * <li>Best practice: Use try-finally in event handlers to ensure resume is
   * called even if processing fails.</li>
   * </ul>
   *
   * <p>
   * Example proper event handling:
   *
   * <pre>
   * eventHub.eventsOfType(BreakpointEvent.class).subscribe(event -> {
   *   try {
   *     // Process breakpoint
   *     inspectVariables();
   *   } finally {
   *     // ALWAYS resume to prevent deadlock
   *     event.virtualMachine().resume();
   *   }
   * });
   * </pre>
   */
  private void processEventSet(EventSet eventSet) {
    try {
      // Publish all events in the set
      for (Event event : eventSet) {
        try {
          DebugEvent debugEvent = new DebugEvent(event, eventSet);
          eventSubject.onNext(debugEvent);

          logger.debug("Published event: {} (suspend policy: {})", event.getClass().getSimpleName(),
              eventSet.suspendPolicy());
        } catch (Exception e) {
          // Log individual event errors but continue with remaining events
          logger.error("Error processing individual event: {}, continuing with next event", event, e);

          // Emit error event to subscribers
          try {
            String context = String.format("Failed to process event: %s", event.getClass().getSimpleName());
            ErrorEvent errorEvent = ErrorEvent.warning(e, context);
            eventSubject.onNext(errorEvent);
          } catch (Exception nested) {
            logger.error("Failed to emit error event for individual event failure", nested);
          }
        }
      }

      // Handle suspend policy
      // If the policy is SUSPEND_ALL or SUSPEND_EVENT_THREAD, the VM is suspended
      // and we should NOT auto-resume here - let the debugger service decide
      // Only resume if policy is SUSPEND_NONE
      if (eventSet.suspendPolicy() == EventRequest.SUSPEND_NONE) {
        // No suspension - nothing to do
        logger.trace("EventSet has SUSPEND_NONE policy");
      } else {
        // VM is suspended - log but don't auto-resume
        logger.debug("VM suspended by EventSet (policy: {})", eventSet.suspendPolicy());
      }

    } catch (Exception e) {
      logger.error("Error processing event set", e);
    }
  }

  /**
   * Checks if the event hub is running.
   *
   * @return true if running
   */
  public boolean isRunning() {
    return running.get();
  }
}
