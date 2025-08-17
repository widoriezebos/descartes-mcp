package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.bitsapplied.descartes.util.InMemoryAppender;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive tests for ExceptionAnalysisTool.
 */
public class ExceptionAnalysisToolTest {

  private ExceptionAnalysisTool tool;
  private ObjectMapper objectMapper;
  private InMemoryAppender mockAppender;

  @BeforeEach
  public void setUp() {
    tool = new ExceptionAnalysisTool();
    objectMapper = new ObjectMapper();
    mockAppender = mock(InMemoryAppender.class);
  }

  @Test
  public void testGetToolName() {
    assertEquals("exception_analysis", tool.getToolName());
  }

  @Test
  public void testGetToolDescription() {
    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("exceptions"));
    assertTrue(description.contains("log buffer"));
  }

  @Test
  public void testGetToolSchema() {
    Map<String, Object> schema = tool.getToolSchema();

    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertNotNull(properties);

    // Check operation property
    @SuppressWarnings("unchecked")
    Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");
    assertEquals("string", operationProp.get("type"));
    @SuppressWarnings("unchecked")
    List<String> operations = (List<String>) operationProp.get("enum");
    assertTrue(operations.contains("get_recent"));
    assertTrue(operations.contains("get_last"));
    assertTrue(operations.contains("clear"));
    assertTrue(operations.contains("stats"));

    // Check count property
    @SuppressWarnings("unchecked")
    Map<String, Object> countProp = (Map<String, Object>) properties.get("count");
    assertEquals("integer", countProp.get("type"));
    assertEquals(1, countProp.get("minimum"));
    assertEquals(50, countProp.get("maximum"));
    assertEquals(10, countProp.get("default"));

    // Check required fields
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("operation"));
  }

  @Test
  public void testGetRecentExceptionsWithMock() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      List<String> mockExceptions = Arrays.asList("java.lang.NullPointerException: Test NPE",
          "java.io.IOException: Test IO error");
      when(mockAppender.getLastExceptions(10)).thenReturn(mockExceptions);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(2, result.get("count"));
      @SuppressWarnings("unchecked")
      List<String> exceptions = (List<String>) result.get("exceptions");
      assertEquals(2, exceptions.size());
      assertTrue(exceptions.get(0).contains("NullPointerException"));
    }
  }

  @Test
  public void testGetRecentExceptionsWithCount() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getLastExceptions(5)).thenReturn(Collections.emptyList());

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");
      args.put("count", 5);

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(0, result.get("count"));
      assertEquals("No exceptions found in log buffer", result.get("message"));

      verify(mockAppender).getLastExceptions(5);
    }
  }

  @Test
  public void testGetRecentExceptionsWithMaxCount() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getLastExceptions(50)).thenReturn(Collections.emptyList());

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");
      args.put("count", 100); // Should be clamped to 50

      tool.executeTool(args);

      verify(mockAppender).getLastExceptions(50); // Max is 50
    }
  }

  @Test
  public void testGetLastException() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getLastException()).thenReturn("java.lang.RuntimeException: Last error");

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_last");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(true, result.get("found"));
      assertEquals("java.lang.RuntimeException: Last error", result.get("fullText"));
      assertNotNull(result.get("exceptionClass"));
      assertNotNull(result.get("message"));
    }
  }

  @Test
  public void testGetLastExceptionWhenNone() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getLastException()).thenReturn(null);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_last");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(false, result.get("found"));
      assertEquals("No exceptions in log buffer", result.get("message"));
    }
  }

  @Test
  public void testClearExceptions() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      // Mock the exception buffer
      List<String> mockBuffer = new ArrayList<>();
      mockBuffer.add("exception1");
      mockBuffer.add("exception2");
      when(mockAppender.getExceptionBuffer()).thenReturn(mockBuffer);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "clear");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(2, result.get("clearedCount"));
      assertEquals("Cleared 2 exception(s) from buffer", result.get("message"));

      verify(mockAppender).clearExceptionBuffer();
    }
  }

  @Test
  public void testGetExceptionStats() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      List<String> mockExceptions = Arrays.asList("java.lang.NullPointerException: Test",
          "java.lang.NullPointerException: Another", "java.io.IOException: IO error",
          "java.lang.RuntimeException: Runtime error");
      when(mockAppender.getExceptionBuffer()).thenReturn(mockExceptions);
      when(mockAppender.getMaxExceptionBufferSize()).thenReturn(1000);
      when(mockAppender.getTruncateExceptionBackTo()).thenReturn(800);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "stats");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(4, result.get("totalCount"));
      assertEquals(1000, result.get("maxBufferSize"));
      assertEquals(800, result.get("truncateBackTo"));

      @SuppressWarnings("unchecked")
      Map<String, Integer> exceptionTypes = (Map<String, Integer>) result.get("exceptionTypes");
      assertEquals(2, exceptionTypes.get("NullPointerException"));
      assertEquals(1, exceptionTypes.get("IOException"));
      assertEquals(1, exceptionTypes.get("RuntimeException"));
    }
  }

  @Test
  public void testNoAppenderAvailable() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(null);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("error", result.get("status"));
      assertEquals("InMemoryAppender not available", result.get("message"));
    }
  }

  @Test
  public void testMissingOperation() {
    Map<String, Object> args = new HashMap<>();

    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });

    assertEquals("Operation is required", exception.getMessage());
  }

  @Test
  public void testUnknownOperation() {
    Map<String, Object> args = new HashMap<>();
    args.put("operation", "unknown");

    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });

    assertTrue(exception.getMessage().contains("Unknown operation"));
  }

  @Test
  public void testNullArguments() {
    Exception exception = assertThrows(NullPointerException.class, () -> {
      tool.executeTool(null);
    });

    assertNotNull(exception);
  }

  @Test
  public void testCountAsString() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getLastExceptions(10)).thenReturn(Collections.emptyList());

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");
      args.put("count", "not a number"); // Invalid type

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));

      // Should use default count of 10
      verify(mockAppender).getLastExceptions(10);
    }
  }

  @Test
  public void testNegativeCount() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getLastExceptions(1)).thenReturn(Collections.emptyList());

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");
      args.put("count", -5); // Should be clamped to 1

      tool.executeTool(args);

      verify(mockAppender).getLastExceptions(1); // Min is 1
    }
  }

  @Test
  public void testExceptionParsing() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      List<String> mockExceptions = Arrays
          .asList("2024-01-01 10:00:00 ERROR - java.lang.NullPointerException: Cannot invoke method\n"
              + "    at com.example.Class.method(Class.java:42)\n" + "    at com.example.Other.call(Other.java:10)");
      when(mockAppender.getLastExceptions(anyInt())).thenReturn(mockExceptions);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "get_recent");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(1, result.get("count"));

      @SuppressWarnings("unchecked")
      List<String> exceptions = (List<String>) result.get("exceptions");
      assertNotNull(exceptions);
      assertEquals(1, exceptions.size());

      // Verify the exception contains expected content
      String exception = exceptions.get(0);
      assertTrue(exception.contains("NullPointerException"));
      assertTrue(exception.contains("Cannot invoke method"));
    }
  }

  @Test
  public void testEmptyExceptionList() throws Exception {
    try (MockedStatic<InMemoryAppender> mockedStatic = mockStatic(InMemoryAppender.class)) {
      mockedStatic.when(InMemoryAppender::getInstance).thenReturn(mockAppender);

      when(mockAppender.getExceptionBuffer()).thenReturn(Collections.emptyList());
      when(mockAppender.getMaxExceptionBufferSize()).thenReturn(1000);
      when(mockAppender.getTruncateExceptionBackTo()).thenReturn(800);

      Map<String, Object> args = new HashMap<>();
      args.put("operation", "stats");

      String resultJson = tool.executeTool(args);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

      assertEquals("success", result.get("status"));
      assertEquals(0, result.get("totalCount"));

      @SuppressWarnings("unchecked")
      Map<String, Integer> exceptionTypes = (Map<String, Integer>) result.get("exceptionTypes");
      assertTrue(exceptionTypes.isEmpty());
    }
  }
}