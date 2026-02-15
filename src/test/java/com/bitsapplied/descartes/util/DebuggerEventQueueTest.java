package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.integration.MCPEventBridge.DebuggerNotification;
import com.bitsapplied.descartes.util.DebuggerEventQueue.EventRecord;
import com.bitsapplied.descartes.util.DebuggerEventQueue.Filter;

class DebuggerEventQueueTest {

  @Test
  void overflowKeepsHighPriorityBreakpointByEvictingLowPriorityEvent() {
    DebuggerEventQueue queue = new DebuggerEventQueue(100);
    for (int i = 0; i < 100; i++) {
      queue.addNotification(new DebuggerNotification("debugger.thread_start", Map.of("thread_id", (long) i)));
    }

    queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 9_001L)));

    List<EventRecord> breakpointEvents = queue.fetch(new Filter(java.util.Set.of("debugger.breakpoint_hit"), null), 10);
    assertEquals(1, breakpointEvents.size());

    List<EventRecord> lifecycleEvents = queue.fetch(new Filter(java.util.Set.of("debugger.thread_start"), null), 200);
    assertEquals(99, lifecycleEvents.size());
  }

  @Test
  void overflowFallsBackToOldestWhenAllEventsSharePriority() {
    DebuggerEventQueue queue = new DebuggerEventQueue(100);
    for (int i = 1; i <= 100; i++) {
      queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("line", i)));
    }

    queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("line", 101)));

    List<EventRecord> events = queue.fetch(new Filter(java.util.Set.of("debugger.breakpoint_hit"), null), 200);
    assertEquals(100, events.size());
    boolean containsFirst = events.stream().anyMatch(event -> ((Number) event.payload().get("line")).intValue() == 1);
    boolean containsNewest =
        events.stream().anyMatch(event -> ((Number) event.payload().get("line")).intValue() == 101);
    assertFalse(containsFirst);
    assertTrue(containsNewest);
  }

  @Test
  void fetchWithSinceSequenceReturnsOnlyNewerEvents() {
    DebuggerEventQueue queue = new DebuggerEventQueue(100);
    queue.addNotification(new DebuggerNotification("debugger.step_complete", Map.of("thread_id", 1L)));
    long baseline = queue.latestSequence();
    queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 2L)));
    queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 3L)));

    List<EventRecord> events =
        queue.fetch(new Filter(java.util.Set.of("debugger.breakpoint_hit"), null, baseline), 10);
    assertEquals(2, events.size());
    assertTrue(events.stream().allMatch(event -> event.sequence() > baseline));
  }

  @Test
  void waitHonorsSinceSequenceCursor() throws Exception {
    DebuggerEventQueue queue = new DebuggerEventQueue(100);
    queue.addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 1L)));
    long baseline = queue.latestSequence();

    Optional<EventRecord> timedOut =
        queue.waitFor(new Filter(java.util.Set.of("debugger.breakpoint_hit"), null, baseline), 10);
    assertTrue(timedOut.isEmpty());

    Thread producer = new Thread(() -> queue
        .addNotification(new DebuggerNotification("debugger.breakpoint_hit", Map.of("thread_id", 2L))));
    producer.start();
    Optional<EventRecord> result =
        queue.waitFor(new Filter(java.util.Set.of("debugger.breakpoint_hit"), null, baseline), 500);
    producer.join(1000);

    assertTrue(result.isPresent());
    assertTrue(result.get().sequence() > baseline);
  }
}
