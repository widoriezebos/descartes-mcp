package com.bitsapplied.descartes.debugger.models;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

class SessionStateTest {

  @Test
  void closedStateCanReopenConnection() {
    assertTrue(SessionState.CLOSED.canTransitionTo(SessionState.CONNECTING));
    SessionState.CLOSED.validateTransition(SessionState.CONNECTING);
  }

  @Test
  void closedStateStillRejectsInvalidTargets() {
    assertFalse(SessionState.CLOSED.canTransitionTo(SessionState.READY));
    assertThrows(DebuggerException.class, () -> SessionState.CLOSED.validateTransition(SessionState.READY));
  }
}
