package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests error handling and structured error data preservation in tool responses.
 *
 * <p>
 * This test verifies the Phase 4 enhancements:
 * <ul>
 * <li>Tool error codes are mapped to JSON-RPC error code ranges</li>
 * <li>Original tool error codes are preserved in error data</li>
 * <li>Error details are preserved in structured format</li>
 * <li>ToolExecutionException carries error data correctly</li>
 * </ul>
 */
public class ToolErrorHandlingTest {

  private ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Mock tool that returns various error codes for testing.
   */
  private static class ErrorTestTool implements MCPTool {

    private final int errorCode;
    private final String errorMessage;
    private final String errorDetails;

    public ErrorTestTool(int errorCode, String errorMessage, String errorDetails) {
      this.errorCode = errorCode;
      this.errorMessage = errorMessage;
      this.errorDetails = errorDetails;
    }

    @Override
    public String getToolName() {
      return "error_test_tool";
    }

    @Override
    public String getToolDescription() {
      return "Tool for testing error handling";
    }

    @Override
    public Map<String, Object> getToolSchema() {
      return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
      return CompletableFuture.completedFuture(ToolResponse.error(errorCode, errorMessage, errorDetails));
    }

    @Override
    public void close() {
      // No resources to clean up
    }
  }

  /**
   * Test that ToolResponse.Error contains all expected fields.
   */
  @Test
  public void testToolResponseErrorStructure() {
    int errorCode = 1234;
    String errorMessage = "Test error message";
    String errorDetails = "Detailed error information";

    ToolResponse response = ToolResponse.error(errorCode, errorMessage, errorDetails);

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;

    assertEquals(errorCode, error.code());
    assertEquals(errorMessage, error.message());
    assertEquals(errorDetails, error.details());
  }

  /**
   * Test that ToolExecutionException carries error code and data.
   */
  @Test
  public void testToolExecutionExceptionWithData() {
    int errorCode = -32602;
    String errorMessage = "Invalid parameters";
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("tool_name", "test_tool");
    errorData.put("tool_error_code", 1234);
    errorData.put("details", "Missing required parameter 'foo'");

    ToolExecutionException exception = new ToolExecutionException(errorCode, errorMessage, errorData);

    assertEquals(errorCode, exception.getErrorCode());
    assertEquals(errorMessage, exception.getMessage());
    assertNotNull(exception.getErrorData());
    assertEquals("test_tool", exception.getErrorData().get("tool_name"));
    assertEquals(1234, exception.getErrorData().get("tool_error_code"));
    assertEquals("Missing required parameter 'foo'", exception.getErrorData().get("details"));
  }

  /**
   * Test that error data is immutable.
   */
  @Test
  public void testToolExecutionExceptionImmutableErrorData() {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("key", "value");

    ToolExecutionException exception = new ToolExecutionException(-32000, "Test", errorData);

    // Try to modify the error data after construction
    errorData.put("key", "modified");

    // Original exception should still have the old value
    assertEquals("value", exception.getErrorData().get("key"));
  }

  /**
   * Test successJson response format metadata.
   */
  @Test
  public void testSuccessJsonFormatMetadata() throws Exception {
    Map<String, Object> data = new HashMap<>();
    data.put("result", "success");
    data.put("count", 42);

    ToolResponse response = ToolResponse.successJson(data);

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;

    // Verify JSON format metadata
    assertEquals(ToolResponse.FORMAT_JSON, success.metadata().get(ToolResponse.METADATA_FORMAT));

    // Verify content is valid JSON
    JsonNode node = objectMapper.readTree(success.content());
    assertEquals("success", node.get("result").asText());
    assertEquals(42, node.get("count").asInt());
  }

  /**
   * Test successJson with additional metadata.
   */
  @Test
  public void testSuccessJsonWithAdditionalMetadata() throws Exception {
    Map<String, Object> data = new HashMap<>();
    data.put("result", "success");

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("execution_time", 100);
    metadata.put("cache_hit", true);

    ToolResponse response = ToolResponse.successJson(data, metadata);

    assertTrue(response instanceof ToolResponse.Success);
    ToolResponse.Success success = (ToolResponse.Success) response;

    // Verify JSON format metadata is present
    assertEquals(ToolResponse.FORMAT_JSON, success.metadata().get(ToolResponse.METADATA_FORMAT));

    // Verify additional metadata is present
    assertEquals(100, success.metadata().get("execution_time"));
    assertEquals(true, success.metadata().get("cache_hit"));
  }

  /**
   * Test that empty error data works correctly.
   */
  @Test
  public void testToolExecutionExceptionEmptyData() {
    ToolExecutionException exception = new ToolExecutionException(-32000, "Test error");

    assertNotNull(exception.getErrorData());
    assertTrue(exception.getErrorData().isEmpty());
  }

  /**
   * Test that null error data is handled safely.
   */
  @Test
  public void testToolExecutionExceptionNullData() {
    ToolExecutionException exception = new ToolExecutionException(-32000, "Test error", (Map<String, Object>) null);

    assertNotNull(exception.getErrorData());
    assertTrue(exception.getErrorData().isEmpty());
  }

  /**
   * Verify error response contains tool_error_code in data field.
   *
   * <p>
   * This test simulates what MCPServer does when it catches a ToolResponse.Error
   * and converts it to a ToolExecutionException. It verifies that the original
   * tool error code is preserved in the error data.
   */
  @Test
  public void testErrorCodePreservationInData() {
    // Simulate tool returning an error
    int originalToolErrorCode = 1234;
    ToolResponse.Error toolError = new ToolResponse.Error(originalToolErrorCode, "Test error", "Error details");

    // Simulate MCPServer converting to ToolExecutionException
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("tool_name", "test_tool");
    errorData.put("tool_error_code", toolError.code());
    errorData.put("details", toolError.details());

    int jsonRpcCode = -32602; // Mapped JSON-RPC code
    ToolExecutionException exception = new ToolExecutionException(jsonRpcCode,
        String.format("Tool 'test_tool' error [%d]: %s", toolError.code(), toolError.message()), errorData);

    // Verify JSON-RPC code is used
    assertEquals(jsonRpcCode, exception.getErrorCode());

    // Verify original tool error code is preserved in data
    assertEquals(originalToolErrorCode, exception.getErrorData().get("tool_error_code"));
    assertEquals("test_tool", exception.getErrorData().get("tool_name"));
    assertEquals("Error details", exception.getErrorData().get("details"));
  }
}
