package com.bitsapplied.descartes.debugger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.parallel.Isolated;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.debugger.models.SessionState;

@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
class DebuggerServiceEvaluationModeTest extends DebuggerTestBase {

  @Test
  void allowsModeSelectionBeforeSessionStartAndAfterStop() throws Exception {
    assertDoesNotThrow(() -> debuggerService.setRemoteOnly(true));

    startDebugSession();
    assertEquals(SessionState.READY, debuggerService.getState());
    assertTrue(debuggerService.getEvaluationProvider().isRemoteOnly());

    stopDebugSession();
    assertDoesNotThrow(() -> debuggerService.setRemoteOnly(false));
  }

  @Test
  void rejectsModeSelectionWhileSessionIsActive() throws Exception {
    startDebugSession();

    DebuggerException error = assertThrows(DebuggerException.class, () -> debuggerService.setRemoteOnly(true));
    assertEquals(DebuggerErrorCode.SESSION_INVALID_STATE, error.getErrorCode());
  }
}
