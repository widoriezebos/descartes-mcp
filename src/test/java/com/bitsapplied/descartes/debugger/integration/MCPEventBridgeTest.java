package com.bitsapplied.descartes.debugger.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.sun.jdi.VMDisconnectedException;

class MCPEventBridgeTest {

  @Test
  void detectsDirectVmDisconnectedException() {
    assertTrue(MCPEventBridge.isVmDisconnected(new VMDisconnectedException("connection is closed")));
  }

  @Test
  void detectsNestedVmDisconnectedException() {
    RuntimeException wrapped = new RuntimeException("wrapper", new VMDisconnectedException("connection is closed"));
    assertTrue(MCPEventBridge.isVmDisconnected(wrapped));
  }

  @Test
  void rejectsNonVmDisconnectExceptions() {
    assertFalse(MCPEventBridge.isVmDisconnected(new IllegalStateException("other error")));
  }
}
