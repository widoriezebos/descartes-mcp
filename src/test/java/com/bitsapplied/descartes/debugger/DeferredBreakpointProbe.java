package com.bitsapplied.descartes.debugger;

/**
 * Test-only class used to validate deferred line breakpoint resolution on class
 * prepare.
 */
public final class DeferredBreakpointProbe {
  private DeferredBreakpointProbe() {
  }

  public static int marker() {
    int value = 42;
    return value;
  }
}
