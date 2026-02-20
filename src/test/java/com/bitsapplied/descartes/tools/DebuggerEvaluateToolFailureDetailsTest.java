package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.evaluation.HybridEvaluationProvider;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
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
    ToolResponse.Error error = executeFailingEvaluationWithStrategies(List.of(HybridEvaluationProvider.EvaluationStrategy.JDI));
    Map<String, Object> details = parseDetails(error);

    assertEquals(List.of("JDI"), details.get("attempts"));
    assertEquals("debugger_variables", details.get("recommended_fallback"));
  }

  @Test
  void includesEmbeddedAttemptsInErrorDetails() throws Exception {
    ToolResponse.Error error = executeFailingEvaluationWithStrategies(
        List.of(HybridEvaluationProvider.EvaluationStrategy.JANINO, HybridEvaluationProvider.EvaluationStrategy.JSHELL));
    Map<String, Object> details = parseDetails(error);

    assertEquals(List.of("JANINO", "JSHELL"), details.get("attempts"));
    assertEquals("debugger_variables", details.get("recommended_fallback"));
  }

  private ToolResponse.Error executeFailingEvaluationWithStrategies(List<HybridEvaluationProvider.EvaluationStrategy> strategies)
      throws Exception {
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
    DebuggerEvaluateTool tool = new DebuggerEvaluateTool(debuggerService, debuggerExecutor);
    Map<String, Object> args = Map.of("operation", "evaluate", "thread_id", 123L, "frame_index", 0, "expression", "x + 1");

    ToolResponse response = tool.executeAsync(args).get();
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
