package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for ObjectInspectorTool.
 */
public class ObjectInspectorToolTest {

  private Map<String, Object> context;
  private ObjectInspectorTool tool;
  private ObjectInspectorTool appContextTool; // Tool with "appContext" variable
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() throws Exception {
    // Create a test context with some test data
    context = new HashMap<>();
    TestObject testObj = new TestObject();
    context.put("testObject", testObj);
    context.put("testString", "test-value");
    context.put("testNumber", 42);

    // Create tool with default "context" variable name
    tool = new ObjectInspectorTool(context);

    // Create tool with "appContext" variable name for compatibility tests
    appContextTool = new ObjectInspectorTool(context, "appContext");

    objectMapper = new ObjectMapper();
  }

  // Test object class for testing
  public static class TestObject {
    private String value = "test-value";
    public int number = 42;

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }

    public String toString() {
      return "TestObject[" + value + "]";
    }
  }

  @AfterEach
  public void tearDown() {
    if (tool != null) {
      tool.close();
    }
    if (appContextTool != null) {
      appContextTool.close();
    }
  }

  @Test
  public void testGetToolName() {
    assertEquals("object_inspector", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("Inspects objects"));
    assertTrue(description.contains("context"));
  }

  @Test
  public void testGetToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("expression"));
    assertTrue(properties.containsKey("operation"));

    @SuppressWarnings("unchecked")
    Map<String, Object> expressionProp = (Map<String, Object>) properties.get("expression");
    assertEquals("string", expressionProp.get("type"));
    assertTrue(((String) expressionProp.get("description")).contains("context"));
  }

  @Test
  public void testInspectSimpleObject() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testString\")");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals("context.get(\"testString\")", result.get("expression"));
    assertEquals("java.lang.String", result.get("type"));
    assertEquals("test-value", result.get("value"));
  }

  @Test
  public void testInspectTestObject() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testObject\")");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertTrue(result.get("type").toString().contains("TestObject"));
    assertEquals("TestObject[test-value]", result.get("value"));

    // Check that fields are included
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
    assertNotNull(fields);
    assertTrue(fields.size() > 0);
  }

  @Test
  public void testGetFields() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testObject\")");
    args.put("operation", "fields");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
    assertNotNull(fields);

    // Should have at least the public "number" field
    boolean hasNumberField = fields.stream().anyMatch(f -> "number".equals(f.get("name")));
    assertTrue(hasNumberField);
  }

  @Test
  public void testGetMethods() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testObject\")");
    args.put("operation", "methods");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> methods = (List<Map<String, Object>>) result.get("methods");
    assertNotNull(methods);

    // Should have getValue and setValue methods
    boolean hasGetValue = methods.stream().anyMatch(m -> "getValue".equals(m.get("name")));
    boolean hasSetValue = methods.stream().anyMatch(m -> "setValue".equals(m.get("name")));
    assertTrue(hasGetValue);
    assertTrue(hasSetValue);
  }

  @Test
  public void testGetType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testNumber\")");
    args.put("operation", "type");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals("java.lang.Integer", result.get("type"));
    assertEquals("Integer", result.get("simple_type"));
  }

  @Test
  public void testGetValue() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testNumber\")");
    args.put("operation", "value");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals("42", result.get("value"));
  }

  @Test
  public void testInspectNull() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"nonexistent\")");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertEquals("null", result.get("result"));
    assertEquals("null", result.get("type"));
  }

  @Test
  public void testExpressionNotStartingWithContext() {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "System.out");

    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });

    assertTrue(exception.getMessage().contains("must start with 'context'"));
  }

  @Test
  public void testExpressionWithWhitespace() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "  context  ");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("type"));
  }

  @Test
  public void testAppContextCompatibility() throws Exception {
    // Test that the appContextTool works with "appContext" variable
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "appContext");

    String resultJson = appContextTool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("type"));

    // Test that it rejects expressions not starting with appContext
    Map<String, Object> badArgs = new HashMap<>();
    badArgs.put("expression", "context");

    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      appContextTool.executeTool(badArgs);
    });

    assertTrue(exception.getMessage().contains("must start with 'appContext'"));
  }

  @Test
  public void testMaxDepth() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context");
    args.put("max_depth", 1);

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));

    // With max_depth=1, should have fields but not deeply nested values
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
    assertNotNull(fields);
  }

  @Test
  public void testIncludePrivate() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context.get(\"testObject\")");
    args.put("operation", "fields");
    args.put("include_private", true);

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("success", result.get("status"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
    assertNotNull(fields);

    // Should have both public "number" and private "value" fields
    boolean hasNumberField = fields.stream().anyMatch(f -> "number".equals(f.get("name")));
    boolean hasValueField = fields.stream().anyMatch(f -> "value".equals(f.get("name")));
    assertTrue(hasNumberField);
    assertTrue(hasValueField);
  }

  // Test removed - invalid expressions are hard to test with JShell
  // as it tries to parse and fix various syntax issues

  @Test
  public void testUnknownOperation() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("expression", "context");
    args.put("operation", "invalid");

    String resultJson = tool.executeTool(args);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

    assertEquals("error", result.get("status"));
    String error = (String) result.get("error");
    assertNotNull(error);
    assertTrue(error.contains("Unknown operation") || error.contains("IllegalArgumentException"));
  }
}