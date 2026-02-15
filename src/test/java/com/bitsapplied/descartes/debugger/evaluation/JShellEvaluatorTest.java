package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Value;

class JShellEvaluatorTest {

  @Test
  void evaluateSupportsPrimitiveFrameVariables() throws Exception {
    JShellEvaluator evaluator = new JShellEvaluator();
    StackFrame frame = mock(StackFrame.class);
    LocalVariable countVar = mock(LocalVariable.class);
    IntegerValue countValue = mock(IntegerValue.class);

    when(countVar.name()).thenReturn("count");
    when(countVar.typeName()).thenReturn("int");
    when(countValue.value()).thenReturn(41);

    List<LocalVariable> visibleVariables = List.of(countVar);
    when(frame.visibleVariables()).thenReturn(visibleVariables);
    when(frame.getValues(visibleVariables)).thenReturn(Map.of(countVar, countValue));

    String result = evaluator.evaluate("count + 1", frame);
    assertEquals("42", result);
  }

  @Test
  void evaluateFailsWhenExpressionNeedsUninjectableObjectVariable() throws Exception {
    JShellEvaluator evaluator = new JShellEvaluator();
    StackFrame frame = mock(StackFrame.class);
    LocalVariable objectVar = mock(LocalVariable.class);
    Value objectValue = mock(Value.class);

    when(objectVar.name()).thenReturn("promptDecision");
    when(objectVar.typeName()).thenReturn("com.example.PromptDecision");

    List<LocalVariable> visibleVariables = List.of(objectVar);
    when(frame.visibleVariables()).thenReturn(visibleVariables);
    when(frame.getValues(visibleVariables)).thenReturn(Map.of(objectVar, objectValue));

    DebuggerException exception = assertThrows(DebuggerException.class,
        () -> evaluator.evaluate("promptDecision.toString()", frame));
    assertEquals(DebuggerErrorCode.EVALUATION_EXECUTION_FAILED, exception.getErrorCode());
    assertTrue(exception.getMessage().contains("Frame variables unavailable in JShell context"));
    assertTrue(exception.getMessage().contains("promptDecision"));
  }
}
