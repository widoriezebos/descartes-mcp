package com.bitsapplied.descartes.debugger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Purpose-built debuggee for deferred breakpoint resolution tests.
 *
 * <p>
 * It waits for a trigger file path (arg[0]) to appear, then loads
 * {@link DeferredBreakpointProbe} and keeps the JVM alive long enough for
 * debugger assertions.
 */
public class DeferredBreakpointDebuggee {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("Expected trigger file path as first argument");
    }

    Path triggerFile = Path.of(args[0]);
    long deadline = System.currentTimeMillis() + 30_000L;

    while (System.currentTimeMillis() < deadline && !Files.exists(triggerFile)) {
      Thread.sleep(50L);
    }

    if (Files.exists(triggerFile)) {
      DeferredBreakpointProbe.marker();
    }

    // Keep process alive briefly so the debugger can process class-prepare and
    // notifications.
    Thread.sleep(5_000L);
  }
}
