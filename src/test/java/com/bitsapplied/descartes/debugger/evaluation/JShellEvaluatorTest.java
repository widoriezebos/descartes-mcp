package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
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

  @Test
  void evaluateIgnoresUnrelatedInvalidDeclarations() throws Exception {
    JShellEvaluator evaluator = new JShellEvaluator();
    StackFrame frame = mock(StackFrame.class);

    LocalVariable countVar = mock(LocalVariable.class);
    IntegerValue countValue = mock(IntegerValue.class);
    when(countVar.name()).thenReturn("count");
    when(countVar.typeName()).thenReturn("int");
    when(countValue.value()).thenReturn(5);

    LocalVariable invalidVar = mock(LocalVariable.class);
    IntegerValue invalidValue = mock(IntegerValue.class);
    when(invalidVar.name()).thenReturn("invalid-name");
    when(invalidVar.typeName()).thenReturn("int");
    when(invalidValue.value()).thenReturn(99);

    List<LocalVariable> visible = List.of(countVar, invalidVar);
    when(frame.visibleVariables()).thenReturn(visible);
    when(frame.getValues(visible)).thenReturn(Map.of(countVar, countValue, invalidVar, invalidValue));

    String result = evaluator.evaluate("count + 1", frame);
    assertEquals("6", result);
  }

  @Test
  void evaluateRetriesWithSanitizedBindingsForMalformedTypeDeclarations() throws Exception {
    JShellEvaluator evaluator = new JShellEvaluator();
    StackFrame frame = mock(StackFrame.class);
    LocalVariable nullableVar = mock(LocalVariable.class);

    when(nullableVar.name()).thenReturn("promptDecision");
    when(nullableVar.typeName()).thenReturn("com.example.Bad<Type");

    List<LocalVariable> visible = List.of(nullableVar);
    when(frame.visibleVariables()).thenReturn(visible);
    Map<LocalVariable, Value> values = new HashMap<>();
    values.put(nullableVar, null);
    when(frame.getValues(visible)).thenReturn(values);

    String result = evaluator.evaluate("promptDecision == null", frame);
    assertEquals("true", result);
  }
}
