package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy;
import com.bitsapplied.descartes.debugger.ReconnectControl;
import com.bitsapplied.descartes.debugger.models.SessionState;

class DebuggerSessionToolReconnectControlTest {

  private DebuggerExecutor debuggerExecutor;
  private DebuggerService debuggerService;
  private ReconnectControl reconnectControl;
  private DebuggerSessionTool tool;

  @BeforeEach
  void setUp() {
    debuggerExecutor = new DebuggerExecutor();
    debuggerService = mock(DebuggerService.class);
    reconnectControl = mock(ReconnectControl.class);

    Map<String, Object> context = new HashMap<>();
    context.put(MCPRemoteDebugProxy.CONTEXT_RECONNECT_CONTROL, reconnectControl);
    tool = new DebuggerSessionTool(debuggerService, debuggerExecutor, context);
  }

  @AfterEach
  void tearDown() {
    debuggerExecutor.shutdown();
  }

  @Test
  void startPausesAndResumesReconnectOnSuccess() throws Exception {
    when(debuggerService.getState()).thenReturn(SessionState.READY);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "start");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Success);

    InOrder inOrder = inOrder(reconnectControl);
    inOrder.verify(reconnectControl).pauseAutoReconnect("debugger_session.start");
    inOrder.verify(reconnectControl).resumeAutoReconnect("debugger_session.start");
    verify(debuggerService).start(any());
  }

  @Test
  void startResumesReconnectOnFailure() throws Exception {
    doThrow(new RuntimeException("boom")).when(debuggerService).start(any());

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "start");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);

    InOrder inOrder = inOrder(reconnectControl);
    inOrder.verify(reconnectControl).pauseAutoReconnect("debugger_session.start");
    inOrder.verify(reconnectControl).resumeAutoReconnect("debugger_session.start");
    verify(debuggerService).start(any());
  }

  @Test
  void stopPausesAndResumesReconnectOnSuccess() throws Exception {
    when(debuggerService.getState()).thenReturn(SessionState.CLOSED);

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stop");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Success);

    InOrder inOrder = inOrder(reconnectControl);
    inOrder.verify(reconnectControl).pauseAutoReconnect("debugger_session.stop");
    inOrder.verify(reconnectControl).resumeAutoReconnect("debugger_session.stop");
    verify(debuggerService).stop();
  }

  @Test
  void stopResumesReconnectOnFailure() throws Exception {
    doThrow(new RuntimeException("boom")).when(debuggerService).stop();

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "stop");

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);

    InOrder inOrder = inOrder(reconnectControl);
    inOrder.verify(reconnectControl).pauseAutoReconnect("debugger_session.stop");
    inOrder.verify(reconnectControl).resumeAutoReconnect("debugger_session.stop");
    verify(debuggerService).stop();
  }
}
