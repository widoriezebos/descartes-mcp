package com.bitsapplied.descartes.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventQueue;

class EventHubTest {

  @Test
  void stopsEventLoopAfterVmDisconnect() throws Exception {
    VirtualMachine vm = mock(VirtualMachine.class);
    EventQueue queue = mock(EventQueue.class);
    AtomicInteger removeCalls = new AtomicInteger();
    ExecutorService debuggerExecutor = Executors.newSingleThreadExecutor();

    try {
      when(vm.eventQueue()).thenReturn(queue);
      when(queue.remove()).thenAnswer(invocation -> {
        removeCalls.incrementAndGet();
        throw new VMDisconnectedException();
      });

      EventHub hub = new EventHub(vm, debuggerExecutor);
      hub.start();

      long deadline = System.currentTimeMillis() + 1000;
      while (removeCalls.get() == 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }

      assertTrue(removeCalls.get() > 0, "Event loop should attempt to read from queue");
      Thread.sleep(100);
      assertEquals(1, removeCalls.get(), "Event loop should stop after VM disconnect");
    } finally {
      debuggerExecutor.shutdownNow();
    }
  }
}
