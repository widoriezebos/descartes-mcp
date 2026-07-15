package com.bitsapplied.descartes.debugger;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;

@Isolated("Uses proxy schedulers and the process-wide JDWP connector circuit breaker")
class MCPRemoteDebugProxyReconnectTest {

  @Test
  void healthyOperationalStatesDoNotTriggerReconnect() {
    assertThat(MCPRemoteDebugProxy.needsReconnect(SessionState.READY, true)).isFalse();
    assertThat(MCPRemoteDebugProxy.needsReconnect(SessionState.SUSPENDED, true)).isFalse();
    assertThat(MCPRemoteDebugProxy.needsReconnect(SessionState.STEPPING, true)).isFalse();
    assertThat(MCPRemoteDebugProxy.needsReconnect(SessionState.EVALUATING, true)).isFalse();

    assertThat(MCPRemoteDebugProxy.needsReconnect(SessionState.READY, false)).isTrue();
    assertThat(MCPRemoteDebugProxy.needsReconnect(SessionState.CLOSED, true)).isTrue();
  }

  @Test
  void failedReconnectRetainsConfigForTheNextScheduledAttempt() throws Exception {
    int[] ports = reserveDistinctPorts();
    RemoteDebugProxyConfig proxyConfig = RemoteDebugProxyConfig.builder().jdwpHost("127.0.0.1")
        .jdwpPort(ports[0]).jdwpTimeout(100).mcpPort(ports[1]).reconnectEnabled(true).reconnectIntervalMs(1000)
        .healthCheckIntervalMs(5000).build();
    MCPRemoteDebugProxy proxy = new MCPRemoteDebugProxy(proxyConfig);
    DebugSessionConfig sessionConfig = new DebugSessionConfig(100, false, new String[] { "java.*" });

    JDWPConnector.resetCircuitBreaker();
    setRunning(proxy, true);
    try {
      scheduleReconnect(proxy, sessionConfig);

      DebuggerService debuggerService = field(proxy, "debuggerService", DebuggerService.class);
      assertThat(awaitReconnectAttempts(proxy, 2, 4, TimeUnit.SECONDS))
          .as("the scheduled retry must retain the captured config and actually run").isGreaterThanOrEqualTo(2);
      assertThat(debuggerService.getConfig()).as("failed starts clear only the active session config").isNull();
    } finally {
      proxy.stop();
      JDWPConnector.resetCircuitBreaker();
      JDWPConnector.clearPortCache();
    }
  }

  @Test
  void canceledReconnectCannotRescheduleAfterBlockedAttachReturns() throws Exception {
    try (ServerSocket jdwpSocket = new ServerSocket(0); ServerSocket mcpSocket = new ServerSocket(0)) {
      CountDownLatch accepted = new CountDownLatch(1);
      CountDownLatch releaseTarget = new CountDownLatch(1);
      ExecutorService targetExecutor = Executors.newSingleThreadExecutor();
      Future<?> target = targetExecutor.submit(() -> {
        try (Socket socket = jdwpSocket.accept()) {
          accepted.countDown();
          releaseTarget.await(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          // The test intentionally tears down this incomplete JDWP handshake.
        }
      });

      RemoteDebugProxyConfig proxyConfig = RemoteDebugProxyConfig.builder().jdwpHost("127.0.0.1")
          .jdwpPort(jdwpSocket.getLocalPort()).jdwpTimeout(2000).mcpPort(mcpSocket.getLocalPort())
          .reconnectEnabled(true).reconnectIntervalMs(1000).healthCheckIntervalMs(5000).build();
      MCPRemoteDebugProxy proxy = new MCPRemoteDebugProxy(proxyConfig);
      DebugSessionConfig sessionConfig = new DebugSessionConfig(2000, false, new String[] { "java.*" });

      JDWPConnector.resetCircuitBreaker();
      setRunning(proxy, true);
      try {
        scheduleReconnect(proxy, sessionConfig);
        assertThat(accepted.await(3, TimeUnit.SECONDS)).as("fake target accepted the blocked JDWP attach").isTrue();

        cancelReconnect(proxy);
        jdwpSocket.close();
        releaseTarget.countDown();
        target.get(3, TimeUnit.SECONDS);

        CountDownLatch canceledAttemptExited = new CountDownLatch(1);
        field(proxy, "reconnectScheduler", ScheduledExecutorService.class).execute(canceledAttemptExited::countDown);
        assertThat(canceledAttemptExited.await(5, TimeUnit.SECONDS)).as("canceled reconnect attempt exited").isTrue();
        Thread.sleep(1300);
        assertThat(field(proxy, "reconnectAttempts", AtomicInteger.class).get()).isZero();
        assertThat(field(proxy, "reconnectFuture", Object.class)).isNull();
      } finally {
        releaseTarget.countDown();
        proxy.stop();
        targetExecutor.shutdownNow();
        JDWPConnector.resetCircuitBreaker();
        JDWPConnector.clearPortCache();
      }
    }
  }

  private static void scheduleReconnect(MCPRemoteDebugProxy proxy, DebugSessionConfig sessionConfig) throws Exception {
    Method method = MCPRemoteDebugProxy.class.getDeclaredMethod("attemptReconnect", DebugSessionConfig.class);
    method.setAccessible(true);
    method.invoke(proxy, sessionConfig);
  }

  private static void cancelReconnect(MCPRemoteDebugProxy proxy) throws Exception {
    Method method = MCPRemoteDebugProxy.class.getDeclaredMethod("cancelReconnect");
    method.setAccessible(true);
    method.invoke(proxy);
  }

  private static int awaitReconnectAttempts(MCPRemoteDebugProxy proxy, int minimum, long timeout, TimeUnit unit)
      throws Exception {
    AtomicInteger attempts = field(proxy, "reconnectAttempts", AtomicInteger.class);
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (attempts.get() < minimum && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    return attempts.get();
  }

  private static void setRunning(MCPRemoteDebugProxy proxy, boolean running) throws Exception {
    Field field = MCPRemoteDebugProxy.class.getDeclaredField("running");
    field.setAccessible(true);
    field.setBoolean(proxy, running);
  }

  private static <T> T field(MCPRemoteDebugProxy proxy, String name, Class<T> type) throws Exception {
    Field field = MCPRemoteDebugProxy.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(proxy));
  }

  private static int[] reserveDistinctPorts() throws Exception {
    try (ServerSocket jdwpSocket = new ServerSocket(0); ServerSocket mcpSocket = new ServerSocket(0)) {
      return new int[] { jdwpSocket.getLocalPort(), mcpSocket.getLocalPort() };
    }
  }
}
