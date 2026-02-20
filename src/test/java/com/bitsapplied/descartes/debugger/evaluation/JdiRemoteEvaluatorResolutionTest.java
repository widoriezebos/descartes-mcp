package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VirtualMachine;

class JdiRemoteEvaluatorResolutionTest {

  @Test
  void resolvesJavaLangSimpleNameBeforeFallbackScan() throws Exception {
    JdiRemoteEvaluator evaluator = new JdiRemoteEvaluator();
    Method resolveReferenceType = resolveReferenceTypeMethod();

    StackFrame frame = mock(StackFrame.class);
    VirtualMachine vm = mock(VirtualMachine.class);
    Location location = mock(Location.class);
    ReferenceType declaringType = mock(ReferenceType.class);
    ReferenceType stringType = mock(ReferenceType.class);

    when(frame.virtualMachine()).thenReturn(vm);
    when(frame.location()).thenReturn(location);
    when(location.declaringType()).thenReturn(declaringType);
    when(declaringType.name()).thenReturn("com.example.TestClass");

    when(stringType.name()).thenReturn("java.lang.String");
    when(vm.classesByName(anyString())).thenAnswer(invocation -> {
      String name = invocation.getArgument(0, String.class);
      return "java.lang.String".equals(name) ? List.of(stringType) : List.of();
    });
    when(vm.allClasses()).thenReturn(List.of(stringType));

    Object resolved = resolveReferenceType.invoke(evaluator, "String", frame, true);
    assertSame(stringType, resolved);
  }

  @Test
  void throwsOnAmbiguousSimpleNameDuringFallbackScan() throws Exception {
    JdiRemoteEvaluator evaluator = new JdiRemoteEvaluator();
    Method resolveReferenceType = resolveReferenceTypeMethod();

    StackFrame frame = mock(StackFrame.class);
    VirtualMachine vm = mock(VirtualMachine.class);
    Location location = mock(Location.class);
    ReferenceType declaringType = mock(ReferenceType.class);
    ReferenceType entryTypeA = mock(ReferenceType.class);
    ReferenceType entryTypeB = mock(ReferenceType.class);

    when(frame.virtualMachine()).thenReturn(vm);
    when(frame.location()).thenReturn(location);
    when(location.declaringType()).thenReturn(declaringType);
    when(declaringType.name()).thenReturn("com.example.TestClass");

    when(vm.classesByName(anyString())).thenReturn(List.of());
    when(entryTypeA.name()).thenReturn("com.foo.Entry");
    when(entryTypeB.name()).thenReturn("org.bar.Entry");
    when(vm.allClasses()).thenReturn(List.of(entryTypeA, entryTypeB));

    InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
        () -> resolveReferenceType.invoke(evaluator, "Entry", frame, true));

    assertTrue(thrown.getCause() instanceof DebuggerException);
    DebuggerException error = (DebuggerException) thrown.getCause();
    assertEquals(DebuggerErrorCode.EVALUATION_FAILED, error.getErrorCode());
    assertTrue(error.getMessage().contains("Ambiguous type name 'Entry'"));
  }

  private Method resolveReferenceTypeMethod() throws Exception {
    Method method = JdiRemoteEvaluator.class.getDeclaredMethod("resolveReferenceType", String.class, StackFrame.class,
        boolean.class);
    method.setAccessible(true);
    return method;
  }
}
