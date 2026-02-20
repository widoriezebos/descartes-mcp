package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

class DebuggerEvaluateToolFailureDetailsTest {

  private DebuggerExecutor debuggerExecutor;

  @AfterEach
  void tearDown() {
    if (debuggerExecutor != null) {
      debuggerExecutor.shutdownNow();
    }
  }

  @Test
  void includesRemoteOnlyAttemptsInErrorDetails() throws Exception {
    ToolResponse.Error error = executeFailingEvaluationWithStrategies(
        List.of(HybridEvaluationProvider.EvaluationStrategy.JDI));
    Map<String, Object> details = parseDetails(error);

    assertEquals(List.of("JDI"), details.get("attempts"));
    assertEquals("debugger_variables", details.get("recommended_fallback"));
  }

  @Test
  void includesEmbeddedAttemptsInErrorDetails() throws Exception {
    ToolResponse.Error error = executeFailingEvaluationWithStrategies(List
        .of(HybridEvaluationProvider.EvaluationStrategy.JANINO, HybridEvaluationProvider.EvaluationStrategy.JSHELL));
    Map<String, Object> details = parseDetails(error);

    assertEquals(List.of("JANINO", "JSHELL"), details.get("attempts"));
    assertEquals("debugger_variables", details.get("recommended_fallback"));
  }

  @Test
  void nativeTopFrameProducesActionableError() throws Exception {
    DebuggerService debuggerService = mock(DebuggerService.class);
    ThreadReference thread = mock(ThreadReference.class);
    StackFrame frame = mock(StackFrame.class);
    Location location = mock(Location.class);
    Method method = mock(Method.class);
    ReferenceType refType = mock(ReferenceType.class);

    when(debuggerService.getThreadById(123L)).thenReturn(thread);
    when(thread.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
    when(thread.isSuspended()).thenReturn(true);
    when(thread.name()).thenReturn("main");
    when(thread.frames()).thenReturn(List.of(frame));

    when(frame.location()).thenReturn(location);
    when(location.method()).thenReturn(method);
    when(location.declaringType()).thenReturn(refType);
    when(method.isNative()).thenReturn(true);
    when(method.name()).thenReturn("park");
    when(refType.name()).thenReturn("jdk.internal.misc.Unsafe");

    debuggerExecutor = new DebuggerExecutor();
    Map<String, Object> args = Map.of("operation", "evaluate", "thread_id", 123L, "frame_index", 0, "expression",
        "x + 1");

    ToolResponse response;
    try (DebuggerEvaluateTool tool = new DebuggerEvaluateTool(debuggerService, debuggerExecutor)) {
      response = tool.executeAsync(args).get();
    }
    assertTrue(response instanceof ToolResponse.Error);

    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("native method"), "Should mention native method");
    assertTrue(error.message().contains("Unsafe"), "Should mention the native class");
    assertTrue(error.message().contains("park"), "Should mention the native method name");
    assertTrue(error.message().contains("debugger_variables"), "Should suggest debugger_variables as alternative");
  }

  @Test
  void nullMessageExceptionShowsToString() throws Exception {
    DebuggerService debuggerService = mock(DebuggerService.class);
    HybridEvaluationProvider evaluator = mock(HybridEvaluationProvider.class);
    ThreadReference thread = mock(ThreadReference.class);
    StackFrame frame = mock(StackFrame.class);

    when(debuggerService.getThreadById(123L)).thenReturn(thread);
    when(debuggerService.getEvaluationProvider()).thenReturn(evaluator);

    when(thread.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
    when(thread.isSuspended()).thenReturn(true);
    when(thread.frames()).thenReturn(List.of(frame));

    when(evaluator.getSupportedStrategies()).thenReturn(List.of(HybridEvaluationProvider.EvaluationStrategy.JDI));

    // Throw a RuntimeException with null message — this triggers the null-safe path
    RuntimeException nullMsgException = new RuntimeException((String) null);
    when(evaluator.evaluate(any(), any(StackFrame.class)))
        .thenThrow(new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
            "JDI remote evaluation failed: " + nullMsgException.toString(), nullMsgException));

    debuggerExecutor = new DebuggerExecutor();
    Map<String, Object> args = Map.of("operation", "evaluate", "thread_id", 123L, "frame_index", 0, "expression",
        "x + 1");

    ToolResponse response;
    try (DebuggerEvaluateTool tool = new DebuggerEvaluateTool(debuggerService, debuggerExecutor)) {
      response = tool.executeAsync(args).get();
    }
    assertTrue(response instanceof ToolResponse.Error);

    ToolResponse.Error error = (ToolResponse.Error) response;
    // The error message should NOT contain the literal string "null" as a detail
    // (it should contain the toString() representation instead)
    assertFalse(error.message().contains("failed: null"),
        "Error message should not contain literal 'null' as the detail; got: " + error.message());
  }

  @Test
  void debuggerExceptionNotDoubleWrapped() throws Exception {
    DebuggerService debuggerService = mock(DebuggerService.class);
    HybridEvaluationProvider evaluator = mock(HybridEvaluationProvider.class);
    ThreadReference thread = mock(ThreadReference.class);
    StackFrame frame = mock(StackFrame.class);

    when(debuggerService.getThreadById(123L)).thenReturn(thread);
    when(debuggerService.getEvaluationProvider()).thenReturn(evaluator);

    when(thread.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
    when(thread.isSuspended()).thenReturn(true);
    when(thread.frames()).thenReturn(List.of(frame));

    when(evaluator.getSupportedStrategies()).thenReturn(List.of(HybridEvaluationProvider.EvaluationStrategy.JDI));

    // Throw DebuggerException directly — should not be double-wrapped
    when(evaluator.evaluate(any(), any(StackFrame.class))).thenThrow(
        new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "JDI remote evaluation failed: some detail"));

    debuggerExecutor = new DebuggerExecutor();
    Map<String, Object> args = Map.of("operation", "evaluate", "thread_id", 123L, "frame_index", 0, "expression",
        "x + 1");

    ToolResponse response;
    try (DebuggerEvaluateTool tool = new DebuggerEvaluateTool(debuggerService, debuggerExecutor)) {
      response = tool.executeAsync(args).get();
    }
    assertTrue(response instanceof ToolResponse.Error);

    ToolResponse.Error error = (ToolResponse.Error) response;
    // Count occurrences of "JDI remote evaluation failed" — should appear only once
    String msg = error.message();
    int firstIdx = msg.indexOf("JDI remote evaluation failed");
    int secondIdx = msg.indexOf("JDI remote evaluation failed", firstIdx + 1);
    assertTrue(secondIdx == -1,
        "Error message should contain only ONE occurrence of 'JDI remote evaluation failed'; got: " + msg);
  }

  private ToolResponse.Error executeFailingEvaluationWithStrategies(
      List<HybridEvaluationProvider.EvaluationStrategy> strategies) throws Exception {
    DebuggerService debuggerService = mock(DebuggerService.class);
    HybridEvaluationProvider evaluator = mock(HybridEvaluationProvider.class);
    ThreadReference thread = mock(ThreadReference.class);
    StackFrame frame = mock(StackFrame.class);

    when(debuggerService.getThreadById(123L)).thenReturn(thread);
    when(debuggerService.getEvaluationProvider()).thenReturn(evaluator);

    when(thread.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
    when(thread.isSuspended()).thenReturn(true);
    when(thread.frames()).thenReturn(List.of(frame));

    when(evaluator.getSupportedStrategies()).thenReturn(strategies);
    when(evaluator.evaluate(any(), any(StackFrame.class)))
        .thenThrow(new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "synthetic evaluation failure"));

    debuggerExecutor = new DebuggerExecutor();
    Map<String, Object> args = Map.of("operation", "evaluate", "thread_id", 123L, "frame_index", 0, "expression",
        "x + 1");

    ToolResponse response;
    try (DebuggerEvaluateTool tool = new DebuggerEvaluateTool(debuggerService, debuggerExecutor)) {
      response = tool.executeAsync(args).get();
    }
    assertTrue(response instanceof ToolResponse.Error);

    ToolResponse.Error error = (ToolResponse.Error) response;
    assertEquals(DebuggerErrorCode.EVALUATION_FAILED.getCode(), error.code());
    return error;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseDetails(ToolResponse.Error error) throws Exception {
    return ToolResponse.OBJECT_MAPPER.readValue(error.details(), Map.class);
  }
}
