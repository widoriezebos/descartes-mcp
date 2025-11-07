package com.bitsapplied.descartes.util;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.bitsapplied.descartes.debugger.integration.MCPEventBridge;

/**
 * Thread-safe bounded queue that buffers debugger notifications so MCP clients
 * can poll or wait for breakpoint/step events without relying on push
 * callbacks. Events are drained when consumed to avoid duplicate delivery.
 */
public final class DebuggerEventQueue {

  private static final int DEFAULT_MAX_EVENTS = 1000;

  private final Deque<EventRecord> events = new ArrayDeque<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition hasEvents = lock.newCondition();
  private final AtomicLong sequenceGenerator = new AtomicLong();
  private final int maxEvents;

  public DebuggerEventQueue() {
    this(DEFAULT_MAX_EVENTS);
  }

  public DebuggerEventQueue(int maxEvents) {
    this.maxEvents = Math.max(100, maxEvents);
  }

  /**
   * Adds a debugger notification to the queue.
   *
   * @param notification debugger notification emitted by MCPEventBridge
   */
  public void addNotification(MCPEventBridge.DebuggerNotification notification) {
    Objects.requireNonNull(notification, "notification");

    lock.lock();
    try {
      if (events.size() >= maxEvents) {
        events.removeFirst();
      }

      long sequence = sequenceGenerator.incrementAndGet();
      EventRecord record = new EventRecord(sequence, notification.type(),
          Map.copyOf(notification.payload()), Instant.now());
      events.addLast(record);
      hasEvents.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Fetches up to maxEvents events that match the supplied filter, removing them
   * from the queue.
   */
  public List<EventRecord> fetch(Filter filter, int maxEventsToFetch) {
    Objects.requireNonNull(filter, "filter");

    lock.lock();
    try {
      List<EventRecord> results = new ArrayList<>(Math.min(maxEventsToFetch, events.size()));
      int remaining = Math.max(0, maxEventsToFetch);

      var iterator = events.iterator();
      while (iterator.hasNext() && remaining > 0) {
        EventRecord record = iterator.next();
        if (filter.matches(record)) {
          results.add(record);
          iterator.remove();
          remaining--;
        }
      }
      return results;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Waits until an event matching the filter becomes available or the timeout
   * expires. When an event is returned it is removed from the queue.
   *
    * @param filter    event filter
   * @param timeoutMs timeout in milliseconds
   * @return matching event, or empty if timed out
   */
  public Optional<EventRecord> waitFor(Filter filter, long timeoutMs) {
    Objects.requireNonNull(filter, "filter");
    if (timeoutMs < 0) {
      throw new IllegalArgumentException("timeoutMs must be non-negative");
    }

    long nanosTimeout = TimeUnit.MILLISECONDS.toNanos(timeoutMs);

    lock.lock();
    try {
      while (true) {
        // Try to find a matching event
        var iterator = events.iterator();
        while (iterator.hasNext()) {
          EventRecord record = iterator.next();
          if (filter.matches(record)) {
            iterator.remove();
            return Optional.of(record);
          }
        }

        if (nanosTimeout == 0L) {
          return Optional.empty();
        }

        try {
          long nanosRemaining = hasEvents.awaitNanos(nanosTimeout);
          if (nanosRemaining <= 0) {
            return Optional.empty();
          }
          nanosTimeout = nanosRemaining;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return Optional.empty();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Gets the current number of buffered events.
   */
  public int size() {
    lock.lock();
    try {
      return events.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Event filter supporting basic criteria.
   */
  public record Filter(Set<String> types, Long threadId) {
    boolean matches(EventRecord record) {
      if (types != null && !types.isEmpty() && !types.contains(record.type())) {
        return false;
      }

      if (threadId != null) {
        Object threadValue = record.payload().get("thread_id");
        if (threadValue instanceof Number number) {
          if (number.longValue() != threadId.longValue()) {
            return false;
          }
        } else if (threadValue instanceof String str) {
          try {
            long parsed = Long.parseLong(str);
            if (parsed != threadId.longValue()) {
              return false;
            }
          } catch (NumberFormatException e) {
            return false;
          }
        } else {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Immutable event entry.
   */
  public record EventRecord(long sequence, String type, Map<String, Object> payload, Instant timestamp) {
    public Map<String, Object> toMap() {
      Map<String, Object> map = new java.util.LinkedHashMap<>();
      map.put("sequence", sequence);
      map.put("type", type);
      map.put("timestamp", timestamp.toString());
      map.put("payload", payload);
      return map;
    }
  }
}
