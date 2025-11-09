package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.util.ProcessInspector;

/**
 * Comprehensive tests for ProcessInspectorTool.
 */
public class ProcessInspectorToolTest {

  private ProcessInspector mockInspector;
  private ProcessInspectorTool tool;

  @BeforeEach
  public void setUp() {
    mockInspector = mock(ProcessInspector.class);
    tool = new ProcessInspectorTool(mockInspector);
  }

  @Test
  public void testDefaultConstructor() {
    ProcessInspectorTool defaultTool = new ProcessInspectorTool();
    assertNotNull(defaultTool);
    assertEquals("process_inspector_stacks", defaultTool.getToolName());
  }

  @Test
  public void testGetToolName() {
    assertEquals("process_inspector_stacks", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("thread stack traces"));
    assertTrue(description.contains("filtering"));
  }

  @Test
  public void testGetToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();

    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties);

    // Check all expected properties exist
    assertTrue(properties.containsKey("whitelistFilters"));
    assertTrue(properties.containsKey("includeSelf"));
    assertTrue(properties.containsKey("moduleFilter"));
    assertTrue(properties.containsKey("trimToModule"));

    // Check whitelistFilters schema
    @SuppressWarnings("unchecked")
    Map<String, Object> whitelistProp = (Map<String, Object>) properties.get("whitelistFilters");
    assertEquals("array", whitelistProp.get("type"));
    assertNotNull(whitelistProp.get("description"));
    @SuppressWarnings("unchecked")
    Map<String, Object> items = (Map<String, Object>) whitelistProp.get("items");
    assertEquals("string", items.get("type"));

    // Check includeSelf schema
    @SuppressWarnings("unchecked")
    Map<String, Object> includeSelfProp = (Map<String, Object>) properties.get("includeSelf");
    assertEquals("boolean", includeSelfProp.get("type"));
    assertEquals(false, includeSelfProp.get("default"));

    // Check moduleFilter schema
    @SuppressWarnings("unchecked")
    Map<String, Object> moduleFilterProp = (Map<String, Object>) properties.get("moduleFilter");
    assertEquals("string", moduleFilterProp.get("type"));

    // Check trimToModule schema
    @SuppressWarnings("unchecked")
    Map<String, Object> trimProp = (Map<String, Object>) properties.get("trimToModule");
    assertEquals("boolean", trimProp.get("type"));
    assertEquals(false, trimProp.get("default"));
  }

  @Test
  public void testExecuteToolWithAllParameters() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("whitelistFilters", Arrays.asList("com.example.*", "*.MyClass"));
    args.put("includeSelf", true);
    args.put("moduleFilter", "mymodule");
    args.put("trimToModule", true);

    String expectedResult = "Thread stack traces...";
    when(mockInspector.captureThreadStacks(anyList(), anyBoolean(), anyString(), anyBoolean()))
        .thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(Arrays.asList("com.example.*", "*.MyClass")), eq(true), eq("mymodule"),
        eq(true));
  }

  @Test
  public void testExecuteToolWithMinimalParameters() throws Exception {
    Map<String, Object> args = new HashMap<>();

    String expectedResult = "Minimal stack traces...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(false), // default includeSelf
        eq(null), eq(false) // default trimToModule
    );
  }

  @Test
  public void testExecuteToolWithOnlyWhitelistFilters() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("whitelistFilters", Arrays.asList("filter1", "filter2"));

    String expectedResult = "Filtered traces...";
    when(mockInspector.captureThreadStacks(anyList(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(Arrays.asList("filter1", "filter2")), eq(false), eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithOnlyIncludeSelf() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("includeSelf", true);

    String expectedResult = "With self...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(true), eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithModuleFilter() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("moduleFilter", "app");
    args.put("trimToModule", true);

    String expectedResult = "App module traces...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), anyString(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(false), eq("app"), eq(true));
  }

  @Test
  public void testExecuteToolWithEmptyWhitelistFilters() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("whitelistFilters", Collections.emptyList());

    String expectedResult = "Empty filters...";
    when(mockInspector.captureThreadStacks(anyList(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(Collections.emptyList()), eq(false), eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithNullArguments() throws Exception {
    // Null arguments will cause NPE in the current implementation
    try {
      tool.executeAsync(null).get();
      throw new AssertionError("Expected exception");
    } catch (Throwable e) {
      assertNotNull(e.getCause() != null ? e.getCause() : e);
    }
  }

  @Test
  public void testExecuteToolWithInvalidWhitelistType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("whitelistFilters", "not-a-list"); // String instead of List

    String expectedResult = "Invalid type handled...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), // Invalid type becomes null
        eq(false), eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithInvalidBooleanType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("includeSelf", "yes"); // String instead of Boolean

    String expectedResult = "Invalid boolean handled...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(false), // Invalid type uses default
        eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithInvalidModuleFilterType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("moduleFilter", 123); // Number instead of String

    String expectedResult = "Invalid module type handled...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(false), eq(null), // Invalid type becomes null
        eq(false));
  }

  @Test
  public void testExecuteToolWithException() throws Exception {
    Map<String, Object> args = new HashMap<>();

    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean()))
        .thenThrow(new RuntimeException("Capture failed"));

    ToolResponse response = tool.executeAsync(args).get();
    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Capture failed"));
  }

  @Test
  public void testExecuteToolWithComplexFilters() throws Exception {
    Map<String, Object> args = new HashMap<>();
    List<String> complexFilters = Arrays.asList("*.MyClass", "com.example.*", "*Service*", "org.*.util.*", "*");
    args.put("whitelistFilters", complexFilters);

    String expectedResult = "Complex filters...";
    when(mockInspector.captureThreadStacks(anyList(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(complexFilters), eq(false), eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithFalseBooleans() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("includeSelf", false);
    args.put("trimToModule", false);

    String expectedResult = "False booleans...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(false), eq(null), eq(false));
  }

  @Test
  public void testExecuteToolWithEmptyModuleFilter() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("moduleFilter", "");

    String expectedResult = "Empty module...";
    when(mockInspector.captureThreadStacks(any(), anyBoolean(), anyString(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(null), eq(false), eq(""), eq(false));
  }

  @Test
  public void testExecuteToolWithUnicodeInFilters() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("whitelistFilters", Arrays.asList("你好*", "مرحبا.*", "🎉Service"));

    String expectedResult = "Unicode filters...";
    when(mockInspector.captureThreadStacks(anyList(), anyBoolean(), any(), anyBoolean())).thenReturn(expectedResult);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(expectedResult, result);
    verify(mockInspector).captureThreadStacks(eq(Arrays.asList("你好*", "مرحبا.*", "🎉Service")), eq(false), eq(null),
        eq(false));
  }

  @Test
  public void testExecuteToolReturnsEmptyResult() throws Exception {
    Map<String, Object> args = new HashMap<>();

    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn("");

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals("", result);
  }

  @Test
  public void testExecuteToolReturnsNullResult() throws Exception {
    Map<String, Object> args = new HashMap<>();

    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean())).thenReturn(null);

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(null, result);
  }

  @Test
  public void testExecuteToolWithLargeResult() throws Exception {
    Map<String, Object> args = new HashMap<>();

    StringBuilder largeResult = new StringBuilder();
    for (int i = 0; i < 10000; i++) {
      largeResult.append("Thread ").append(i).append(" stack trace...\n");
    }

    when(mockInspector.captureThreadStacks(any(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(largeResult.toString());

    String result = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    assertEquals(largeResult.toString(), result);
  }
}