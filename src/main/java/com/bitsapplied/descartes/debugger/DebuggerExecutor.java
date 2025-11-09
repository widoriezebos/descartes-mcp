package com.bitsapplied.descartes.debugger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.tools.AbstractDebuggerTool;

/**
 * Dedicated single-threaded executor for all JDI (Java Debug Interface)
 * operations.
 *
 * <p>
 * JDI is not thread-safe and requires all debugging operations to be
 * serialized. This executor ensures that all debugger tool operations execute
 * sequentially on a single dedicated thread, preventing race conditions and
 * maintaining debugger state consistency.
 *
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Multiple threads can submit
 * tasks to the executor, but all tasks will execute sequentially on the single
 * debugger thread.
 *
 * <p>
 * <b>Lifecycle:</b> The executor must be properly shut down when no longer
 * needed. Use {@link #shutdown()} for graceful shutdown with a timeout, or
 * {@link #shutdownNow()} for immediate termination.
 *
 * @see AbstractDebuggerTool
 */
public class DebuggerExecutor {

  private static final Logger logger = LoggerFactory.getLogger(DebuggerExecutor.class);
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  private final ExecutorService executor;
  private volatile boolean isShutdown = false;

  /**
   * Creates a new DebuggerExecutor with a single dedicated thread.
   */
  public DebuggerExecutor() {
    this.executor = Executors.newSingleThreadExecutor(new DebuggerThreadFactory());
    logger.info("DebuggerExecutor initialized with single-threaded executor for JDI operations");
  }

  /**
   * Returns the underlying executor service for use with CompletableFuture and
   * other async operations.
   *
   * @return the single-threaded executor service
   * @throws IllegalStateException if the executor has been shut down
   */
  public ExecutorService getExecutor() {
    if (isShutdown) {
      throw new IllegalStateException("DebuggerExecutor has been shut down");
    }
    return executor;
  }

  /**
   * Initiates an orderly shutdown of the executor. Previously submitted tasks are
   * executed, but no new tasks will be accepted. This method waits up to
   * {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds for tasks to complete.
   *
   * @return true if the executor terminated cleanly, false if timeout occurred
   */
  public boolean shutdown() {
    if (isShutdown) {
      logger.warn("DebuggerExecutor.shutdown() called multiple times");
      return true;
    }

    isShutdown = true;
    logger.info("Shutting down DebuggerExecutor...");

    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn("DebuggerExecutor did not terminate within {} seconds, forcing shutdown", SHUTDOWN_TIMEOUT_SECONDS);
        executor.shutdownNow();
        // Wait a bit more for force shutdown
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.error("DebuggerExecutor failed to terminate after forced shutdown");
          return false;
        }
      }
      logger.info("DebuggerExecutor shut down successfully");
      return true;
    } catch (InterruptedException e) {
      logger.warn("Interrupted while waiting for DebuggerExecutor shutdown", e);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Attempts to stop all actively executing tasks and halts the processing of
   * waiting tasks. This method does not wait for actively executing tasks to
   * terminate.
   */
  public void shutdownNow() {
    if (isShutdown) {
      logger.warn("DebuggerExecutor.shutdownNow() called multiple times");
      return;
    }

    isShutdown = true;
    logger.warn("Force shutting down DebuggerExecutor");
    executor.shutdownNow();
  }

  /**
   * Checks if the executor has been shut down.
   *
   * @return true if shutdown has been initiated
   */
  public boolean isShutdown() {
    return isShutdown;
  }

  /**
   * Thread factory for the debugger executor that creates named daemon threads
   * for easy identification in thread dumps.
   */
  private static class DebuggerThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r, "descartes-debugger-" + threadNumber.getAndIncrement());
      thread.setDaemon(true); // Don't prevent JVM shutdown
      thread.setPriority(Thread.NORM_PRIORITY);
      return thread;
    }
  }
}
