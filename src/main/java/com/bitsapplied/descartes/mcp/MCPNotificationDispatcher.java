package com.bitsapplied.descartes.mcp;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dispatcher for sending MCP notifications asynchronously to clients.
 *
 * <p>
 * This class provides thread-safe notification delivery using a dedicated
 * single-threaded executor for writing to the output stream. Notifications are
 * queued and delivered in order, ensuring proper JSON-RPC message formatting.
 *
 * <p>
 * <b>Threading Model:</b>
 * <ul>
 * <li>Notifications are queued from any thread via
 * {@link #sendNotification}</li>
 * <li>A dedicated writer thread serializes and writes messages to the output
 * stream</li>
 * <li>The writer thread ensures atomic writes (one complete message at a
 * time)</li>
 * </ul>
 *
 * <p>
 * <b>Usage:</b>
 * 
 * <pre>{@code
 * MCPNotificationDispatcher dispatcher = new MCPNotificationDispatcher(outputStream);
 *
 * // Send a notification
 * dispatcher.sendNotification("descartes/debugger.stopped", Map.of("reason", "breakpoint", "threadId", 12345));
 *
 * // Send a simple message
 * dispatcher.sendMessage("Breakpoint hit at MyClass.java:42");
 *
 * // Clean shutdown
 * dispatcher.close();
 * }</pre>
 */
public final class MCPNotificationDispatcher implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(MCPNotificationDispatcher.class);

  private final OutputStream outputStream;
  private final ObjectMapper objectMapper;
  private final ExecutorService writerExecutor;
  private final BlockingQueue<Notification> notificationQueue;
  private final AtomicBoolean running;
  private final Object writeLock;

  /**
   * Internal record representing a queued notification.
   */
  private record Notification(String method, Map<String, Object> params) {
  }

  /**
   * Creates a notification dispatcher that writes to the given output stream.
   *
   * @param outputStream the output stream for sending notifications (typically
   *                     System.out)
   */
  public MCPNotificationDispatcher(OutputStream outputStream) {
    this(outputStream, new Object());
  }

  /**
   * Creates a dispatcher with an explicit write lock. Use this when multiple
   * components share the same underlying stream and must synchronize writes.
   *
   * @param outputStream the output stream for notifications
   * @param writeLock    shared lock used to serialize writes
   */
  public MCPNotificationDispatcher(OutputStream outputStream, Object writeLock) {
    this.outputStream = outputStream;
    this.objectMapper = new ObjectMapper();
    this.notificationQueue = new LinkedBlockingQueue<>(1000); // Bounded queue to prevent OOM
    this.running = new AtomicBoolean(true);
    this.writeLock = writeLock;

    // Single-threaded executor for sequential message writing
    this.writerExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "mcp-notification-writer");
      t.setDaemon(true);
      return t;
    });

    // Start the writer thread
    writerExecutor.submit(this::processNotifications);
  }

  /**
   * Sends a notification to the MCP client asynchronously.
   *
   * <p>
   * The notification is queued and will be sent by the writer thread. If the
   * queue is full, the oldest notification is discarded (FIFO).
   *
   * @param method the notification method (e.g., "descartes/debugger.stopped")
   * @param params the notification parameters
   */
  public void sendNotification(String method, Map<String, Object> params) {
    if (!running.get()) {
      logger.warn("Attempted to send notification after shutdown: {}", method);
      return;
    }

    Notification notification = new Notification(method, params);
    if (!notificationQueue.offer(notification)) {
      logger.warn("Notification queue full, discarding oldest notification for method: {}", method);
      notificationQueue.poll(); // Remove oldest
      notificationQueue.offer(notification); // Add new
    }
  }

  /**
   * Convenience method to send a simple text message notification.
   *
   * <p>
   * This sends a notification with method "notifications/message" and a single
   * "text" parameter.
   *
   * @param text the message text
   */
  public void sendMessage(String text) {
    sendNotification("notifications/message", Map.of("text", text));
  }

  /**
   * Worker method that processes notifications from the queue. Runs on the
   * dedicated writer thread.
   */
  private void processNotifications() {
    logger.info("Notification dispatcher started");

    while (running.get() || !notificationQueue.isEmpty()) {
      try {
        Notification notification = notificationQueue.poll(100, TimeUnit.MILLISECONDS);
        if (notification != null) {
          writeNotification(notification);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.info("Notification dispatcher interrupted");
        break;
      } catch (Exception e) {
        logger.error("Error processing notification", e);
      }
    }

    logger.info("Notification dispatcher stopped");
  }

  /**
   * Writes a single notification to the output stream in JSON-RPC format.
   *
   * <p>
   * JSON-RPC 2.0 notification format:
   * 
   * <pre>{@code
   * {
   *   "jsonrpc": "2.0",
   *   "method": "notifications/message",
   *   "params": { ... }
   * }
   * }</pre>
   *
   * @param notification the notification to write
   * @throws IOException if writing fails
   */
  private void writeNotification(Notification notification) throws IOException {
    Map<String, Object> message = new HashMap<>();
    message.put("jsonrpc", "2.0");
    message.put("method", notification.method());
    message.put("params", notification.params());

    // Serialize to JSON
    String json = objectMapper.writeValueAsString(message);

    // Write atomically (single write operation)
    synchronized (writeLock) {
      outputStream.write(json.getBytes(StandardCharsets.UTF_8));
      outputStream.write('\n');
      outputStream.flush();
    }

    logger.debug("Sent notification: method={}, params={}", notification.method(), notification.params());
  }

  /**
   * Gets the number of queued notifications waiting to be sent.
   *
   * @return the queue size
   */
  public int getQueueSize() {
    return notificationQueue.size();
  }

  /**
   * Gets the lock used to guard writes. Callers that share the underlying stream
   * should synchronize on this lock before writing.
   *
   * @return the write lock
   */
  public Object getWriteLock() {
    return writeLock;
  }

  /**
   * Closes the dispatcher and releases resources.
   *
   * <p>
   * This method:
   * <ul>
   * <li>Stops accepting new notifications</li>
   * <li>Waits for queued notifications to be sent (up to 5 seconds)</li>
   * <li>Shuts down the writer executor</li>
   * </ul>
   *
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void close() throws IOException {
    if (!running.compareAndSet(true, false)) {
      return; // Already closed
    }

    logger.info("Closing notification dispatcher, {} notifications pending", notificationQueue.size());

    // Shutdown executor gracefully
    writerExecutor.shutdown();
    try {
      if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warn("Writer executor did not terminate in time, forcing shutdown");
        writerExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writerExecutor.shutdownNow();
    }

    logger.info("Notification dispatcher closed");
  }
}
