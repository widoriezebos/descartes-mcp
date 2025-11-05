package com.bitsapplied.descartes.debugger.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.models.VariableInfo;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;

/**
 * Tests for VariableExtractor.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Extract variables from stack frames (this, locals, parameters)</li>
 * <li>Extract fields from object references</li>
 * <li>Extract static fields from classes</li>
 * <li>Extract child variables via reference manager</li>
 * <li>Value formatting (primitives, strings, arrays, objects)</li>
 * <li>Collection and map previews</li>
 * <li>Expandable object detection</li>
 * <li>Error handling (absent information, exceptions)</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class VariableExtractorTest {
  private static final Logger logger = LoggerFactory.getLogger(VariableExtractorTest.class);

  private VariableExtractor extractor;
  private VariableReferenceManager referenceManager;

  @BeforeEach
  public void setUp() {
    referenceManager = new VariableReferenceManager();
    extractor = new VariableExtractor(referenceManager);
  }

  /**
   * Tests extracting variables with 'this' reference.
   */
  @Test
  public void testExtractVariablesWithThis() throws Exception {
    logger.info("Testing extract variables with 'this'...");

    // Mock stack frame
    StackFrame frame = mock(StackFrame.class);
    ObjectReference thisObject = createMockObjectReference("MyClass");

    when(frame.thisObject()).thenReturn(thisObject);
    when(frame.visibleVariables()).thenReturn(List.of());
    when(frame.getValues(anyList())).thenReturn(Map.of());

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo thisVar = variables.get(0);
    assertEquals("this", thisVar.name());
    assertEquals("MyClass", thisVar.type());
    assertEquals("this", thisVar.scope());

    logger.info("Extract variables with 'this' test passed");
  }

  /**
   * Tests extracting local variables.
   */
  @Test
  public void testExtractLocalVariables() throws Exception {
    logger.info("Testing extract local variables...");

    // Mock stack frame
    StackFrame frame = mock(StackFrame.class);
    LocalVariable localVar = createMockLocalVariable("count", "int", false);
    IntegerValue value = mock(IntegerValue.class);
    when(value.value()).thenReturn(42);
    when(value.toString()).thenReturn("42");

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(localVar));
    when(frame.getValues(anyList())).thenReturn(Map.of(localVar, value));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo var = variables.get(0);
    assertEquals("count", var.name());
    assertEquals("int", var.type());
    assertEquals("42", var.value());
    assertEquals("local", var.scope());

    logger.info("Extract local variables test passed");
  }

  /**
   * Tests extracting parameter variables.
   */
  @Test
  public void testExtractParameterVariables() throws Exception {
    logger.info("Testing extract parameter variables...");

    // Mock stack frame
    StackFrame frame = mock(StackFrame.class);
    LocalVariable paramVar = createMockLocalVariable("input", "java.lang.String", true);
    StringReference value = createMockStringReference("hello");

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(paramVar));
    when(frame.getValues(anyList())).thenReturn(Map.of(paramVar, value));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo var = variables.get(0);
    assertEquals("input", var.name());
    assertEquals("java.lang.String", var.type());
    assertEquals("\"hello\"", var.value());
    assertEquals("parameter", var.scope());

    logger.info("Extract parameter variables test passed");
  }

  /**
   * Tests extracting variables with null values.
   */
  @Test
  public void testExtractVariablesWithNull() throws Exception {
    logger.info("Testing extract variables with null...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("obj", "java.lang.Object", false);

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, null));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertEquals("null", varInfo.value());
    assertEquals(0, varInfo.variableReference());

    logger.info("Extract variables with null test passed");
  }

  /**
   * Tests extracting variables handles absent information exception.
   */
  @Test
  public void testExtractVariablesAbsentInformation() throws Exception {
    logger.info("Testing extract variables absent information...");

    StackFrame frame = mock(StackFrame.class);
    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenThrow(new AbsentInformationException());

    List<VariableInfo> variables = extractor.extractVariables(frame);

    // Should return empty list, not throw
    assertNotNull(variables);
    assertEquals(0, variables.size());

    logger.info("Extract variables absent information test passed");
  }

  /**
   * Tests extracting fields from object.
   */
  @Test
  public void testExtractFieldsFromObject() throws Exception {
    logger.info("Testing extract fields from object...");

    ObjectReference object = createMockObjectReference("MyClass");
    ReferenceType refType = object.referenceType();

    Field field1 = createMockField("name", "java.lang.String", false);
    Field field2 = createMockField("age", "int", false);

    StringReference nameValue = createMockStringReference("John");
    IntegerValue ageValue = mock(IntegerValue.class);
    when(ageValue.value()).thenReturn(30);
    when(ageValue.toString()).thenReturn("30");

    when(refType.allFields()).thenReturn(List.of(field1, field2));
    when(object.getValues(anyList())).thenReturn(Map.of(field1, nameValue, field2, ageValue));

    List<VariableInfo> fields = extractor.extractFieldsFromObject(object);

    assertEquals(2, fields.size());

    VariableInfo nameField = fields.stream().filter(v -> v.name().equals("name")).findFirst().orElse(null);
    assertNotNull(nameField);
    assertEquals("java.lang.String", nameField.type());
    assertEquals("\"John\"", nameField.value());
    assertEquals("field", nameField.scope());

    VariableInfo ageField = fields.stream().filter(v -> v.name().equals("age")).findFirst().orElse(null);
    assertNotNull(ageField);
    assertEquals("int", ageField.type());
    assertEquals("30", ageField.value());
    assertEquals("field", ageField.scope());

    logger.info("Extract fields from object test passed");
  }

  /**
   * Tests extracting static fields.
   */
  @Test
  public void testExtractStaticFields() throws Exception {
    logger.info("Testing extract static fields...");

    ReferenceType classType = mock(ReferenceType.class);

    Field staticField = createMockField("CONSTANT", "int", true);
    Field instanceField = createMockField("value", "int", false);

    IntegerValue constantValue = mock(IntegerValue.class);
    when(constantValue.value()).thenReturn(100);
    when(constantValue.toString()).thenReturn("100");

    when(classType.allFields()).thenReturn(List.of(staticField, instanceField));
    when(classType.getValue(staticField)).thenReturn(constantValue);

    List<VariableInfo> staticFields = extractor.extractStaticFields(classType);

    assertEquals(1, staticFields.size());
    VariableInfo field = staticFields.get(0);
    assertEquals("CONSTANT", field.name());
    assertEquals("int", field.type());
    assertEquals("100", field.value());
    assertEquals("static", field.scope());

    logger.info("Extract static fields test passed");
  }

  /**
   * Tests extracting child variables via reference manager.
   */
  @Test
  public void testExtractChildVariables() throws Exception {
    logger.info("Testing extract child variables...");

    ObjectReference object = createMockObjectReference("MyClass");
    ReferenceType refType = object.referenceType();

    Field field = createMockField("data", "java.lang.String", false);
    StringReference value = createMockStringReference("test");

    when(refType.allFields()).thenReturn(List.of(field));
    when(object.getValues(anyList())).thenReturn(Map.of(field, value));

    // Register object and get reference
    int refId = referenceManager.registerObjectReference(object);

    // Extract children
    List<VariableInfo> children = extractor.extractChildVariables(refId);

    assertEquals(1, children.size());
    assertEquals("data", children.get(0).name());

    logger.info("Extract child variables test passed");
  }

  /**
   * Tests extract child variables with invalid reference.
   */
  @Test
  public void testExtractChildVariablesInvalidReference() {
    logger.info("Testing extract child variables invalid reference...");

    List<VariableInfo> children = extractor.extractChildVariables(999999);

    assertNotNull(children);
    assertEquals(0, children.size());

    logger.info("Extract child variables invalid reference test passed");
  }

  /**
   * Tests formatting array values.
   */
  @Test
  public void testFormatArrayValue() throws Exception {
    logger.info("Testing format array value...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("numbers", "int[]", false);
    ArrayReference arrayRef = mock(ArrayReference.class);
    ArrayType arrayType = mock(ArrayType.class);

    when(arrayRef.type()).thenReturn(arrayType);
    when(arrayType.name()).thenReturn("int[]");
    when(arrayRef.length()).thenReturn(5);

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, arrayRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertEquals("int[][5]", varInfo.value());

    logger.info("Format array value test passed");
  }

  /**
   * Tests formatting long strings are truncated.
   */
  @Test
  public void testFormatLongString() throws Exception {
    logger.info("Testing format long string...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("longStr", "java.lang.String", false);

    // Create a 250-character string (exceeds MAX_STRING_DISPLAY_LENGTH of 200)
    String longString = "a".repeat(250);
    StringReference stringRef = mock(StringReference.class);
    ReferenceType stringType = mock(ReferenceType.class);
    when(stringRef.type()).thenReturn(stringType);
    when(stringType.name()).thenReturn("java.lang.String");
    when(stringRef.value()).thenReturn(longString);

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, stringRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertTrue(varInfo.value().contains("...\""), "Long string should be truncated");
    assertTrue(varInfo.value().length() < longString.length() + 10, "Truncated value should be shorter");

    logger.info("Format long string test passed");
  }

  /**
   * Tests formatting collection preview.
   */
  @Test
  public void testFormatCollectionPreview() throws Exception {
    logger.info("Testing format collection preview...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("list", "java.util.ArrayList", false);
    ObjectReference listRef = createMockObjectReference("java.util.ArrayList");
    ReferenceType listType = listRef.referenceType();

    Field sizeField = createMockField("size", "int", false);
    IntegerValue sizeValue = mock(IntegerValue.class);
    when(sizeValue.value()).thenReturn(10);

    when(listType.fieldByName("size")).thenReturn(sizeField);
    when(listRef.getValue(sizeField)).thenReturn(sizeValue);

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, listRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertTrue(varInfo.value().contains("size=10"), "Should show collection size");

    logger.info("Format collection preview test passed");
  }

  /**
   * Tests formatting map preview.
   */
  @Test
  public void testFormatMapPreview() throws Exception {
    logger.info("Testing format map preview...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("map", "java.util.HashMap", false);
    ObjectReference mapRef = createMockObjectReference("java.util.HashMap");
    ReferenceType mapType = mapRef.referenceType();

    Field sizeField = createMockField("size", "int", false);
    IntegerValue sizeValue = mock(IntegerValue.class);
    when(sizeValue.value()).thenReturn(5);

    when(mapType.fieldByName("size")).thenReturn(sizeField);
    when(mapRef.getValue(sizeField)).thenReturn(sizeValue);

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, mapRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertTrue(varInfo.value().contains("size=5"), "Should show map size");

    logger.info("Format map preview test passed");
  }

  /**
   * Tests boolean primitive formatting.
   */
  @Test
  public void testFormatBooleanValue() throws Exception {
    logger.info("Testing format boolean value...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("flag", "boolean", false);
    BooleanValue boolValue = mock(BooleanValue.class);
    when(boolValue.value()).thenReturn(true);
    when(boolValue.toString()).thenReturn("true");

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, boolValue));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    assertEquals("true", variables.get(0).value());

    logger.info("Format boolean value test passed");
  }

  /**
   * Tests expandable object gets variable reference.
   */
  @Test
  public void testExpandableObjectGetsReference() throws Exception {
    logger.info("Testing expandable object gets reference...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("obj", "MyClass", false);
    ObjectReference objRef = createMockObjectReference("MyClass");
    ReferenceType refType = objRef.referenceType();

    // Add a non-static field to make it expandable
    Field field = createMockField("data", "int", false);
    when(refType.allFields()).thenReturn(List.of(field));

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, objRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertTrue(varInfo.variableReference() > 0, "Expandable object should have reference");

    logger.info("Expandable object gets reference test passed");
  }

  /**
   * Tests non-expandable object doesn't get variable reference.
   */
  @Test
  public void testNonExpandableObjectNoReference() throws Exception {
    logger.info("Testing non-expandable object no reference...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("obj", "MyClass", false);
    ObjectReference objRef = createMockObjectReference("MyClass");
    ReferenceType refType = objRef.referenceType();

    // No fields - not expandable
    when(refType.allFields()).thenReturn(List.of());

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, objRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertEquals(0, varInfo.variableReference(), "Non-expandable object should have reference 0");

    logger.info("Non-expandable object no reference test passed");
  }

  /**
   * Tests empty array is not expandable.
   */
  @Test
  public void testEmptyArrayNotExpandable() throws Exception {
    logger.info("Testing empty array not expandable...");

    StackFrame frame = mock(StackFrame.class);
    LocalVariable var = createMockLocalVariable("arr", "int[]", false);
    ArrayReference arrayRef = mock(ArrayReference.class);
    ArrayType arrayType = mock(ArrayType.class);

    when(arrayRef.type()).thenReturn(arrayType);
    when(arrayType.name()).thenReturn("int[]");
    when(arrayRef.length()).thenReturn(0);

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(var));
    when(frame.getValues(anyList())).thenReturn(Map.of(var, arrayRef));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(1, variables.size());
    VariableInfo varInfo = variables.get(0);
    assertEquals(0, varInfo.variableReference(), "Empty array should not be expandable");

    logger.info("Empty array not expandable test passed");
  }

  /**
   * Tests extracting mixed locals and parameters.
   */
  @Test
  public void testExtractMixedVariables() throws Exception {
    logger.info("Testing extract mixed variables...");

    StackFrame frame = mock(StackFrame.class);

    LocalVariable param = createMockLocalVariable("x", "int", true);
    LocalVariable local = createMockLocalVariable("y", "int", false);

    IntegerValue xValue = mock(IntegerValue.class);
    when(xValue.value()).thenReturn(10);
    when(xValue.toString()).thenReturn("10");

    IntegerValue yValue = mock(IntegerValue.class);
    when(yValue.value()).thenReturn(20);
    when(yValue.toString()).thenReturn("20");

    when(frame.thisObject()).thenReturn(null);
    when(frame.visibleVariables()).thenReturn(List.of(param, local));
    when(frame.getValues(anyList())).thenReturn(Map.of(param, xValue, local, yValue));

    List<VariableInfo> variables = extractor.extractVariables(frame);

    assertEquals(2, variables.size());

    long paramCount = variables.stream().filter(v -> v.scope().equals("parameter")).count();
    long localCount = variables.stream().filter(v -> v.scope().equals("local")).count();

    assertEquals(1, paramCount);
    assertEquals(1, localCount);

    logger.info("Extract mixed variables test passed");
  }

  // ========== Helper Methods ==========

  private ObjectReference createMockObjectReference(String typeName) {
    ObjectReference objRef = mock(ObjectReference.class);
    ReferenceType refType = mock(ReferenceType.class);
    when(objRef.referenceType()).thenReturn(refType);
    when(refType.name()).thenReturn(typeName);
    when(objRef.type()).thenReturn(refType);
    return objRef;
  }

  private StringReference createMockStringReference(String value) {
    StringReference stringRef = mock(StringReference.class);
    ReferenceType stringType = mock(ReferenceType.class);
    when(stringRef.type()).thenReturn(stringType);
    when(stringType.name()).thenReturn("java.lang.String");
    when(stringRef.value()).thenReturn(value);
    return stringRef;
  }

  private LocalVariable createMockLocalVariable(String name, String typeName, boolean isArgument) {
    LocalVariable var = mock(LocalVariable.class);
    when(var.name()).thenReturn(name);
    when(var.typeName()).thenReturn(typeName);
    when(var.isArgument()).thenReturn(isArgument);
    return var;
  }

  private Field createMockField(String name, String typeName, boolean isStatic) {
    Field field = mock(Field.class);
    when(field.name()).thenReturn(name);
    when(field.typeName()).thenReturn(typeName);
    when(field.isStatic()).thenReturn(isStatic);
    return field;
  }
}
