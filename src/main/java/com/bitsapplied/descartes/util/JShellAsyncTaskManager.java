package com.bitsapplied.descartes.util;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.bitsapplied.descartes.tools.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages asynchronous JShell evaluations. Tasks run on a dedicated executor,
 * expose status metadata, and optionally time out or close sessions on
 * completion. Results are cached so clients can poll for completion without
 * keeping the MCP call open.
 */
public final class JShellAsyncTaskManager implements AutoCloseable {

  private static final AtomicInteger WORKER_ID = new AtomicInteger();
  private static final AtomicInteger TIMEOUT_ID = new AtomicInteger();

  @SuppressWarnings("unused")
  private final Map<String, Object> context;
  private final JShellSessionManager sessionManager;
  private final ConcurrentMap<String, JShellAsyncTask> tasks = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;

  public JShellAsyncTaskManager(Map<String, Object> context) {
    this.context = Objects.requireNonNull(context, "context");
    this.sessionManager = JShellSessionManagers.getOrCreate(context);
    this.executor = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "JShellAsyncWorker-" + WORKER_ID.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
    this.scheduler = Executors.newScheduledThreadPool(1, r -> {
      Thread t = new Thread(r, "JShellAsyncTimeout-" + TIMEOUT_ID.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Starts a new asynchronous JShell evaluation.
   *
   * @param request request parameters
   * @return task metadata
   */
  public JShellAsyncTask startTask(Request request) {
    Objects.requireNonNull(request, "request");

    JShellSession session = sessionManager.getOrCreateSession(request.sessionId());
    String actualSessionId = session.getSessionId();

    JShellAsyncTask task = new JShellAsyncTask(UUID.randomUUID().toString(), actualSessionId, request.timeoutSeconds());
    tasks.put(task.taskId(), task);

    CompletableFuture<EvalResult> future = task.future();
    executor.submit(() -> {
      task.markRunning();
      try {
        SessionEvalResult sessionResult = sessionManager.evalWithSession(session, request.code());
        EvalResult evalResult = sessionResult.getEvalResult().withSessionId(sessionResult.getSessionId());
        future.complete(evalResult);
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });

    ScheduledFuture<?> timeoutHandle = null;
    if (request.timeoutSeconds() != null && request.timeoutSeconds() > 0) {
      long timeout = request.timeoutSeconds();
      timeoutHandle = scheduler.schedule(() -> {
        if (task.markTimedOut("JShell async task timed out after " + timeout + " seconds")) {
          sessionManager.stopSession(actualSessionId);
          future
              .completeExceptionally(new TimeoutException("JShell async task timed out after " + timeout + " seconds"));
        }
      }, timeout, TimeUnit.SECONDS);
      task.setTimeoutHandle(timeoutHandle);
    }

    ScheduledFuture<?> timeoutRef = timeoutHandle;
    future.whenComplete((result, throwable) -> {
      task.cancelTimeout();

      if (throwable == null) {
        task.complete(result);
        if (request.extendExpiryMinutes() != null) {
          sessionManager.extendSessionExpiry(actualSessionId, request.extendExpiryMinutes());
        }
      } else {
        task.completeExceptionally(throwable);
      }

      if (request.closeSession()) {
        sessionManager.closeSession(actualSessionId);
      }

      if (timeoutRef != null && !timeoutRef.isDone()) {
        timeoutRef.cancel(false);
      }
    });

    return task;
  }

  /**
   * Retrieves a task by ID.
   */
  public Optional<JShellAsyncTask> getTask(String taskId) {
    if (taskId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(tasks.get(taskId));
  }

  /**
   * Cancels a running task.
   *
   * @param taskId task identifier
   * @param reason user-visible reason
   * @return task metadata if task exists
   */
  public Optional<JShellAsyncTask> cancelTask(String taskId, String reason) {
    JShellAsyncTask task = tasks.get(taskId);
    if (task == null) {
      return Optional.empty();
    }

    if (task.cancel(reason)) {
      task.cancelTimeout();
      sessionManager.stopSession(task.sessionId());
      task.future().cancel(true);
    }
    return Optional.of(task);
  }

  /**
   * Returns an immutable view of current tasks (for diagnostics/tests).
   */
  public Map<String, JShellAsyncTask> tasks() {
    return Collections.unmodifiableMap(tasks);
  }

  @Override
  public void close() {
    tasks.clear();
    executor.shutdownNow();
    scheduler.shutdownNow();
  }

  /**
   * Immutable request parameters for asynchronous JShell execution.
   */
  public static record Request(String sessionId, String code, Long timeoutSeconds, boolean closeSession,
      Integer extendExpiryMinutes) {
  }

  /**
   * Asynchronous JShell task metadata and lifecycle helpers.
   */
  public static final class JShellAsyncTask {
    private enum Status {
      QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, TIMEOUT
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String taskId;
    private final String sessionId;
    private final Instant createdAt;
    private final Long timeoutSeconds;
    private final CompletableFuture<EvalResult> future = new CompletableFuture<>();

    private volatile Status status = Status.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile EvalResult result;
    private volatile String errorType;
    private volatile String errorMessage;
    private volatile ScheduledFuture<?> timeoutHandle;

    JShellAsyncTask(String taskId, String sessionId, Long timeoutSeconds) {
      this.taskId = taskId;
      this.sessionId = sessionId;
      this.timeoutSeconds = timeoutSeconds;
      this.createdAt = Instant.now();
    }

    public String taskId() {
      return taskId;
    }

    public String sessionId() {
      return sessionId;
    }

    public CompletableFuture<EvalResult> future() {
      return future;
    }

    public void setTimeoutHandle(ScheduledFuture<?> handle) {
      this.timeoutHandle = handle;
    }

    public void cancelTimeout() {
      ScheduledFuture<?> handle = this.timeoutHandle;
      if (handle != null && !handle.isDone()) {
        handle.cancel(false);
      }
    }

    void markRunning() {
      if (status == Status.QUEUED) {
        status = Status.RUNNING;
        startedAt = Instant.now();
      }
    }

    boolean markTimedOut(String message) {
      if (isTerminal()) {
        return false;
      }
      status = Status.TIMEOUT;
      errorType = "Timeout";
      errorMessage = message;
      if (completedAt == null) {
        completedAt = Instant.now();
      }
      return true;
    }

    boolean cancel(String reason) {
      if (isTerminal()) {
        return false;
      }
      status = Status.CANCELLED;
      errorType = "Cancelled";
      errorMessage = reason != null ? reason : "Task cancelled";
      completedAt = Instant.now();
      return true;
    }

    void complete(EvalResult evalResult) {
      if (status == Status.TIMEOUT || status == Status.CANCELLED) {
        // Preserve terminal status but keep the result for inspection.
        if (this.result == null) {
          this.result = evalResult;
        }
        if (completedAt == null) {
          completedAt = Instant.now();
        }
        return;
      }
      this.result = evalResult;
      status = Status.SUCCESS;
      completedAt = Instant.now();
    }

    void completeExceptionally(Throwable throwable) {
      Throwable actual = unwrap(throwable);

      if (actual instanceof CancellationException) {
        if (status != Status.CANCELLED) {
          status = Status.CANCELLED;
          errorType = actual.getClass().getSimpleName();
          errorMessage = actual.getMessage() != null ? actual.getMessage() : "Task cancelled";
          completedAt = Instant.now();
        }
        return;
      }

      if (status == Status.TIMEOUT) {
        if (errorMessage == null) {
          errorType = actual.getClass().getSimpleName();
          errorMessage = actual.getMessage();
        }
        if (completedAt == null) {
          completedAt = Instant.now();
        }
        return;
      }

      if (status == Status.CANCELLED) {
        if (errorMessage == null) {
          errorType = actual.getClass().getSimpleName();
          errorMessage = actual.getMessage();
        }
        if (completedAt == null) {
          completedAt = Instant.now();
        }
        return;
      }

      status = Status.FAILED;
      errorType = actual.getClass().getSimpleName();
      errorMessage = actual.getMessage();
      completedAt = Instant.now();
    }

    public boolean isTerminal() {
      return status == Status.SUCCESS || status == Status.FAILED || status == Status.CANCELLED
          || status == Status.TIMEOUT;
    }

    public Map<String, Object> toSummary(boolean includeResult) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("task_id", taskId);
      data.put("session_id", sessionId);
      data.put("status", status.name().toLowerCase());
      data.put("created_at", createdAt.toString());
      if (startedAt != null) {
        data.put("started_at", startedAt.toString());
      }
      if (completedAt != null) {
        data.put("completed_at", completedAt.toString());
      }
      if (timeoutSeconds != null) {
        data.put("timeout_seconds", timeoutSeconds);
      }
      if (includeResult && result != null) {
        Map<String, Object> resultMap = ToolResponseMapper.MAPPER.convertValue(result, MAP_TYPE);
        data.put("result", resultMap);
      }
      if (errorMessage != null) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", errorType);
        error.put("message", errorMessage);
        data.put("error", error);
      }
      return data;
    }

    private static Throwable unwrap(Throwable throwable) {
      Throwable current = throwable;
      while (current != null && (current instanceof ExecutionException
          || current instanceof CompletionException)) {
        if (current.getCause() == null) {
          break;
        }
        current = current.getCause();
      }
      return current != null ? current : throwable;
    }
  }

  /**
   * Helper bridge so we can reuse the shared ToolResponse ObjectMapper without a
   * static dependency cycle.
   */
  private static final class ToolResponseMapper {
    private static final ObjectMapper MAPPER = ToolResponse.OBJECT_MAPPER;
  }
}
