package com.bitsapplied.descartes.debugger.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class DebuggerNotificationBroadcasterTest {

  @Test
  void broadcastsToAllListenersAndRespectsRemoval() throws Exception {
    DebuggerNotificationBroadcaster broadcaster = DebuggerNotificationBroadcaster.getInstance();
    MCPEventBridge.DebuggerNotification notification = new MCPEventBridge.DebuggerNotification("debugger.test",
        Map.of());

    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();

    AutoCloseable firstRegistration = broadcaster.registerListener(_event -> first.incrementAndGet());
    AutoCloseable secondRegistration = broadcaster.registerListener(_event -> second.incrementAndGet());

    broadcaster.broadcast(notification);
    assertEquals(1, first.get());
    assertEquals(1, second.get());

    firstRegistration.close();

    broadcaster.broadcast(notification);
    assertEquals(1, first.get(), "Unregistered listener should not receive notifications");
    assertEquals(2, second.get());

    secondRegistration.close();
  }
}
