package com.bitsapplied.descartes.debugger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.events.DebugEvent;
import com.bitsapplied.descartes.debugger.events.ErrorEvent;
import com.bitsapplied.descartes.debugger.events.StreamEvent;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
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
 * <li><b>Owner-tracked subscriptions</b> for automatic cleanup via
 * unsubscribeAll()</li>
 * <li><b>Thread-safe</b> using CopyOnWriteArrayList for concurrent
 * subscribe/dispatch</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is designed for concurrent access:
 * <ul>
 * <li><b>Event loop thread</b>: Reads JDWP EventQueue and dispatches
 * events</li>
 * <li><b>Application threads</b>: Subscribe and unsubscribe from events</li>
 * <li><b>CopyOnWriteArrayList</b>: Safe iteration during concurrent
 * modification</li>
 * <li><b>ConcurrentHashMap</b>: Thread-safe owner tracking</li>
 * </ul>
 *
 * <h2>Subscription Management</h2>
 * <p>
 * Subscriptions are tracked by owner to prevent leaks:
 * <ul>
 * <li>Call {@code subscribe(owner, ...)} to track subscription by owner</li>
 * <li>Call {@code unsubscribeAll(owner)} to cleanup all subscriptions for that
 * owner</li>
 * <li>Automatic cleanup prevents listener accumulation between sessions</li>
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>
 * EventHub hub = new EventHub(vm, debuggerExecutor);
 * hub.start();
 *
 * // Subscribe with owner tracking
 * Disposable sub = hub.subscribe(this, BreakpointEvent.class, event -> handleBreakpoint(event));
 *
 * // Later, cleanup all subscriptions for this owner
 * hub.unsubscribeAll(this);
 * </pre>
 */
public class EventHub {
  private static final Logger logger = LoggerFactory.getLogger(EventHub.class);

  private final VirtualMachine vm;
  private final ExecutorService debuggerExecutor;
  private final Subject<StreamEvent> eventSubject;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread eventThread;

  // Owner-tracked subscriptions for automatic cleanup
  private final CopyOnWriteArrayList<SubscriptionRecord> ownerTrackedSubscriptions = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<Object, List<SubscriptionRecord>> subscriptionsByOwner = new ConcurrentHashMap<>();

  /**
   * Internal record for tracking subscriptions by owner.
   */
  private record SubscriptionRecord(Object owner, Disposable disposable) {
  }

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
   * Subscribes to JDI events with owner tracking for automatic cleanup.
   *
   * <p>
   * <b>Owner Tracking:</b> The subscription is associated with the provided owner
   * object. Later, calling {@code unsubscribeAll(owner)} will automatically
   * dispose all subscriptions for that owner.
   *
   * <p>
   * <b>Thread Safety:</b> Safe to call concurrently with event dispatching. Uses
   * CopyOnWriteArrayList internally.
   *
   * <p>
   * <b>Automatic Cleanup on Dispose:</b> When the returned Disposable is
   * disposed, the subscription is automatically removed from the owner's
   * subscription list.
   *
   * <p>
   * Example usage:
   *
   * <pre>
   * // In DebuggerService.start()
   * Disposable sub = eventHub.subscribe(this, BreakpointEvent.class, this::handleBreakpoint);
   *
   * // Later in DebuggerService.stop()
   * eventHub.unsubscribeAll(this); // Cleans up all subscriptions for this instance
   * </pre>
   *
   * @param owner         the owner object (typically 'this' from the calling
   *                      class)
   * @param jdiEventClass the JDI event class to filter for
   * @param handler       the event handler
   * @param <T>           the JDI event type
   * @return a Disposable to manually unsubscribe if needed
   */
  public <T extends Event> Disposable subscribe(Object owner, Class<T> jdiEventClass, Consumer<T> handler) {

    if (owner == null) {
      throw new IllegalArgumentException("Owner cannot be null");
    }

    // Create RxJava subscription - convert java.util.function.Consumer to
    // io.reactivex.rxjava3.functions.Consumer
    Disposable rxDisposable = jdiEventsOfType(jdiEventClass).subscribe(handler::accept);

    // Wrap with automatic cleanup on dispose
    Disposable wrappedDisposable = new Disposable() {
      private volatile boolean disposed = false;

      @Override
      public void dispose() {
        if (!disposed) {
          disposed = true;
          rxDisposable.dispose();
          removeSubscription(owner, this);
        }
      }

      @Override
      public boolean isDisposed() {
        return disposed;
      }
    };

    // Track subscription by owner
    SubscriptionRecord record = new SubscriptionRecord(owner, wrappedDisposable);
    ownerTrackedSubscriptions.add(record);

    subscriptionsByOwner.compute(owner, (_key, list) -> {
      List<SubscriptionRecord> newList = list != null ? new ArrayList<>(list) : new ArrayList<>();
      newList.add(record);
      return newList;
    });

    // Warn if subscription count is high (potential leak)
    int totalSubscriptions = ownerTrackedSubscriptions.size();
    if (totalSubscriptions > 100) {
      logger.warn("High subscription count detected: {} subscriptions active. Potential memory leak?",
          totalSubscriptions);
    }

    logger.trace("Subscription added for owner {} (total: {})", owner.getClass().getSimpleName(), totalSubscriptions);

    return wrappedDisposable;
  }

  /**
   * Unsubscribes all event listeners for the given owner.
   *
   * <p>
   * <b>CRITICAL:</b> This method must be called BEFORE any other state reset
   * operations to prevent late-arriving events from modifying state (e.g.,
   * re-suspending threads after they've been resumed).
   *
   * <p>
   * <b>Thread Safety:</b> Safe to call concurrently with event dispatching. Uses
   * CopyOnWriteArrayList and ConcurrentHashMap internally.
   *
   * <p>
   * Example usage in DebuggerService.resetSessionState():
   *
   * <pre>
   * private void resetSessionState() {
   *   // 1. FIRST: Unsubscribe events to prevent late delivery
   *   eventHub.unsubscribeAll(this);
   *
   *   // 2. Small delay to drain in-flight events
   *   Thread.sleep(50);
   *
   *   // 3. THEN: Reset VM state (resume threads, clear requests)
   *   connectionManager.reset();
   *
   *   // 4. FINALLY: Verify clean state
   *   verifyCleanState();
   * }
   * </pre>
   *
   * @param owner the owner whose subscriptions should be removed
   */
  public void unsubscribeAll(Object owner) {
    if (owner == null) {
      return;
    }

    List<SubscriptionRecord> records = subscriptionsByOwner.remove(owner);
    if (records != null && !records.isEmpty()) {
      logger.debug("Unsubscribing {} event listeners for owner {}", records.size(), owner.getClass().getSimpleName());

      for (SubscriptionRecord record : records) {
        try {
          record.disposable().dispose();
          ownerTrackedSubscriptions.remove(record);
        } catch (Exception e) {
          logger.warn("Error disposing subscription for owner {}: {}", owner.getClass().getSimpleName(),
              e.getMessage());
        }
      }

      logger.trace("Unsubscribed all listeners for owner {} ({} total subscriptions remaining)",
          owner.getClass().getSimpleName(), ownerTrackedSubscriptions.size());
    }
  }

  /**
   * Removes a subscription record (called when Disposable.dispose() is called).
   */
  private void removeSubscription(Object owner, Disposable disposable) {
    subscriptionsByOwner.computeIfPresent(owner, (_key, list) -> {
      list.removeIf(record -> record.disposable() == disposable);
      return list.isEmpty() ? null : list;
    });

    ownerTrackedSubscriptions.removeIf(record -> record.disposable() == disposable);
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
      } catch (VMDisconnectedException e) {
        // VM disconnect is a normal terminal condition for short-lived debug targets.
        // Treat it as terminal to avoid tight-loop error spam.
        logger.info("VM disconnected; stopping event loop");
        running.set(false);
        break;
      } catch (Throwable t) {
        // Fatal JVM errors (OutOfMemoryError, StackOverflowError, etc.) should
        // terminate the event loop
        // AssertionError is generally safe to continue from in production
        if (t instanceof Error && !(t instanceof AssertionError)) {
          logger.error("Fatal JVM error in event loop - terminating event processing", t);
          try {
            // Attempt to notify subscribers of fatal error (wrap Error as Exception for
            // ErrorEvent)
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
