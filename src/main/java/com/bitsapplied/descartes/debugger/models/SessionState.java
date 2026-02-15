package com.bitsapplied.descartes.debugger.models;

import static com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode.SESSION_INVALID_STATE;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

/**
 * Represents the state of a debug session with transition validation.
 *
 * <p>
 * State machine transitions:
 * 
 * <pre>
 * CREATED → CONNECTING → READY ←→ SUSPENDED ←→ STEPPING
 *                          ↓              ↓
 *                          ↓              ↓
 *                      EVALUATING     EVALUATING
 *                          ↓              ↓
 *                          ↓              ↓
 *                     DISCONNECTING ← ←  ←
 *                          ↓
 *                        CLOSED
 *                          ↓
 *                     CONNECTING
 * </pre>
 */
public enum SessionState {
  /**
   * Session created but not yet connected to JDWP.
   */
  CREATED,

  /**
   * Attempting to connect to JDWP.
   */
  CONNECTING,

  /**
   * Connected and ready for debugging operations.
   */
  READY,

  /**
   * Debuggee is suspended (e.g., at a breakpoint).
   */
  SUSPENDED,

  /**
   * Performing a step operation.
   */
  STEPPING,

  /**
   * Evaluating an expression.
   */
  EVALUATING,

  /**
   * Disconnecting from the debuggee.
   */
  DISCONNECTING,

  /**
   * Session closed and resources released.
   */
  CLOSED;

  /**
   * Valid state transitions map.
   */
  private static final Map<SessionState, Set<SessionState>> VALID_TRANSITIONS = Map.of(CREATED,
      EnumSet.of(CONNECTING, CLOSED), CONNECTING, EnumSet.of(READY, CLOSED), READY,
      EnumSet.of(SUSPENDED, STEPPING, EVALUATING, DISCONNECTING, CLOSED), SUSPENDED,
      EnumSet.of(READY, STEPPING, EVALUATING, DISCONNECTING, CLOSED), STEPPING,
      EnumSet.of(READY, SUSPENDED, DISCONNECTING, CLOSED), EVALUATING,
      EnumSet.of(READY, SUSPENDED, DISCONNECTING, CLOSED), DISCONNECTING, EnumSet.of(CLOSED), CLOSED,
      EnumSet.of(CONNECTING));

  /**
   * Checks if a transition to the target state is valid.
   *
   * @param target the target state
   * @return true if the transition is valid
   */
  public boolean canTransitionTo(SessionState target) {
    return VALID_TRANSITIONS.get(this).contains(target);
  }

  /**
   * Validates that a transition to the target state is legal.
   *
   * @param target the target state
   * @throws DebuggerException if the transition is invalid
   */
  public void validateTransition(SessionState target) {
    if (!canTransitionTo(target)) {
      throw new DebuggerException(SESSION_INVALID_STATE,
          String.format("Invalid state transition: %s → %s", this, target));
    }
  }

  /**
   * Checks if this state allows debugger operations.
   *
   * @return true if operations are allowed
   */
  public boolean isOperational() {
    return this == READY || this == SUSPENDED || this == STEPPING || this == EVALUATING;
  }

  /**
   * Checks if the debuggee is suspended in this state.
   *
   * @return true if suspended
   */
  public boolean isSuspended() {
    return this == SUSPENDED || this == STEPPING || this == EVALUATING;
  }
}
