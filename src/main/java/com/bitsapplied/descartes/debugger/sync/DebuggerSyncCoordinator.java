package com.bitsapplied.descartes.debugger.sync;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.integration.MCPEventBridge;

/**
 * Coordinates debugger notifications with blocking tool operations so that MCP
 * tools can provide fully synchronous results to agents that require
 * request-response semantics.
 *
 * <p>
 * Tools register an awaiter before initiating an asynchronous debugger action
 * (e.g. {@code stepOver}). When the corresponding debugger notification
 * arrives, the awaiter completes with the structured payload. Awaiters
 * automatically time out and are cleaned up if the expected notification never
 * arrives.
 *
 * <p>
 * Thread safety: registration, completion and cleanup are fully thread-safe.
 * Outstanding futures are completed exceptionally when the coordinator is
 * closed (e.g. debugger session shutdown).
 */
public final class DebuggerSyncCoordinator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(DebuggerSyncCoordinator.class);

  private final Map<String, ConcurrentLinkedQueue<Waiter<?>>> waitersByType = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Registers a waiter for a debugger notification.
   */
  private <T> CompletableFuture<T> awaitInternal(String notificationType,
      Predicate<MCPEventBridge.DebuggerNotification> predicate,
      Function<MCPEventBridge.DebuggerNotification, T> converter, Duration timeout, String description) {

    Objects.requireNonNull(notificationType, "notificationType");
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(converter, "converter");

    if (closed.get()) {
      CompletableFuture<T> cancelled = new CompletableFuture<>();
      cancelled
          .completeExceptionally(new CancellationException("DebuggerSyncCoordinator is closed (session not active)"));
      return cancelled;
    }

    CompletableFuture<T> future = new CompletableFuture<>();
    Waiter<T> waiter = new Waiter<>(predicate, converter, future, description);

    ConcurrentLinkedQueue<Waiter<?>> queue = waitersByType.computeIfAbsent(notificationType,
        _ -> new ConcurrentLinkedQueue<>());
    queue.add(waiter);

    // Remove the waiter once the future completes (successfully or exceptionally)
    future.whenComplete((_, _) -> {
      queue.remove(waiter);
      if (queue.isEmpty()) {
        waitersByType.remove(notificationType, queue);
      }
    });

    if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
      long timeoutMillis = Math.max(1, timeout.toMillis());
      future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    return future;
  }

  /**
   * Await a {@code debugger.step_complete} notification for the specified thread.
   *
   * @param threadId the debugger thread id
   * @param timeout  how long to wait before timing out
   * @return a future completing with the parsed {@link StepResult}
   */
  public CompletableFuture<StepResult> awaitStepCompletion(long threadId, Duration timeout) {
    return awaitInternal("debugger.step_complete", notification -> matchesThread(notification, threadId),
        StepResult::fromNotification, timeout, "step_complete(threadId=" + threadId + ")");
  }

  private boolean matchesThread(MCPEventBridge.DebuggerNotification notification, long threadId) {
    try {
      Object candidate = notification.payload().get("thread_id");
      if (candidate == null) {
        return false;
      }
      long eventThreadId = convertToLong(candidate);
      return eventThreadId == threadId;
    } catch (NumberFormatException ex) {
      logger.debug("Ignoring step notification with non-numeric thread_id: {}", notification.payload());
      return false;
    }
  }

  /**
   * Handles incoming debugger notifications. This should be wired directly into
   * the {@link MCPEventBridge}.
   */
  public void handleNotification(MCPEventBridge.DebuggerNotification notification) {
    if (notification == null || closed.get()) {
      return;
    }

    ConcurrentLinkedQueue<Waiter<?>> queue = waitersByType.get(notification.type());
    if (queue == null || queue.isEmpty()) {
      return;
    }

    for (Waiter<?> waiter : queue) {
      if (waiter.future().isDone()) {
        continue;
      }

      boolean matches = false;
      try {
        matches = waiter.predicate().test(notification);
      } catch (Exception ex) {
        logger.warn("Debugger awaiter predicate {} failed: {}", waiter.description(), ex.getMessage());
        waiter.future().completeExceptionally(ex);
      }

      if (matches) {
        completeWaiter(waiter, notification);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void completeWaiter(Waiter<?> waiter, MCPEventBridge.DebuggerNotification notification) {
    Waiter<T> typedWaiter = (Waiter<T>) waiter;
    if (typedWaiter.future().isDone()) {
      return;
    }

    try {
      T value = typedWaiter.converter().apply(notification);
      typedWaiter.future().complete(value);
    } catch (Exception ex) {
      typedWaiter.future().completeExceptionally(ex);
    }
  }

  /**
   * Completes and clears any pending awaiters.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    CancellationException cancellation = new CancellationException("Debugger session closed");
    waitersByType.values().forEach(queue -> {
      for (Waiter<?> waiter : queue) {
        waiter.future().completeExceptionally(cancellation);
      }
      queue.clear();
    });
    waitersByType.clear();
  }

  private static long convertToLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(value.toString());
  }

  /**
   * Step completion payload parsed for synchronous responses.
   *
   * @param threadId   thread identifier
   * @param threadName thread name or {@code null}
   * @param className  declaring type name or {@code null}
   * @param methodName method name or {@code null}
   * @param lineNumber line number (-1 if unavailable)
   * @param sourcePath source path or {@code null}
   * @param payload    original notification payload (unmodifiable)
   * @param receivedAt timestamp (milliseconds) when coordinator observed the
   *                   notification
   */
  public record StepResult(long threadId, String threadName, String className, String methodName, int lineNumber,
      String sourcePath, Map<String, Object> payload, long receivedAt) {

    /**
     * Create a builder for constructing StepResult instances.
     */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Builder for StepResult with fluent API.
     */
    public static class Builder {
      private long threadId;
      private String threadName;
      private String className;
      private String methodName;
      private int lineNumber;
      private String sourcePath;
      private Map<String, Object> payload;
      private long receivedAt;

      public Builder threadId(long threadId) {
        this.threadId = threadId;
        return this;
      }

      public Builder threadName(String threadName) {
        this.threadName = threadName;
        return this;
      }

      public Builder className(String className) {
        this.className = className;
        return this;
      }

      public Builder methodName(String methodName) {
        this.methodName = methodName;
        return this;
      }

      public Builder lineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
        return this;
      }

      public Builder sourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
        return this;
      }

      public Builder payload(Map<String, Object> payload) {
        this.payload = payload;
        return this;
      }

      public Builder receivedAt(long receivedAt) {
        this.receivedAt = receivedAt;
        return this;
      }

      public StepResult build() {
        return new StepResult(threadId, threadName, className, methodName, lineNumber, sourcePath, payload, receivedAt);
      }
    }

    private static StepResult fromNotification(MCPEventBridge.DebuggerNotification notification) {
      Map<String, Object> payload = notification.payload();
      long threadId = convertToLong(payload.getOrDefault("thread_id", -1L));
      String threadName = asString(payload.get("thread_name"));
      String className = asString(payload.get("class"));
      String methodName = asString(payload.get("method"));
      int line = convertToInt(payload.getOrDefault("line", -1));
      String sourcePath = asString(payload.get("source_path"));

      return new StepResult(threadId, threadName, className, methodName, line, sourcePath, payload,
          System.currentTimeMillis());
    }

    public Map<String, Object> locationMap() {
      return Map.of("class", Optional.ofNullable(className).orElse("unknown"), "method",
          Optional.ofNullable(methodName).orElse("unknown"), "line", lineNumber, "source_path",
          Optional.ofNullable(sourcePath).orElse("unknown"));
    }

    private static String asString(Object value) {
      return value != null ? value.toString() : null;
    }

    private static int convertToInt(Object value) {
      if (value instanceof Number number) {
        return number.intValue();
      }
      return Integer.parseInt(value.toString());
    }
  }

  private record Waiter<T>(Predicate<MCPEventBridge.DebuggerNotification> predicate,
      Function<MCPEventBridge.DebuggerNotification, T> converter, CompletableFuture<T> future, String description) {
  }
}
