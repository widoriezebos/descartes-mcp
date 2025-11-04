package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for DebuggerBreakpointsTool.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Tool metadata (name, description, schema)</li>
 * <li>Set operation (basic and conditional breakpoints)</li>
 * <li>Remove operation</li>
 * <li>RemoveAll operation</li>
 * <li>List operation</li>
 * <li>Enable/disable operations</li>
 * <li>Error handling</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.OTHER })
public class DebuggerBreakpointsToolTest {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerBreakpointsToolTest.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
  };

  private static final String TEST_CLASS = "com.bitsapplied.descartes.debugger.SimpleTestApplication";

  private DebuggerBreakpointsTool tool;
  private ObjectMapper objectMapper;
  private DebuggerService debuggerService;

  @BeforeEach
  public void setUp() throws Exception {
    debuggerService = new DebuggerService();
    tool = new DebuggerBreakpointsTool(debuggerService);
    objectMapper = new ObjectMapper();

    // Start debug session for tests
    if (debuggerService.getState() != SessionState.READY) {
      DebugSessionConfig config = new DebugSessionConfig(10000, false,
          new String[] { "java.*", "javax.*", "jdk.*", "sun.*" });
      debuggerService.start(config);
    }
  }

  @AfterEach
  public void tearDown() {
    try {
      // Clean up breakpoints
      debuggerService.getBreakpointManager().removeAllBreakpoints();

      // Stop session
      if (debuggerService.getState() != SessionState.CLOSED) {
        debuggerService.stop();
      }
    } catch (Exception e) {
      logger.warn("Error cleaning up: {}", e.getMessage());
    }
  }

  /**
   * Tests tool metadata.
   */
  @Test
  public void testToolMetadata() {
    logger.info("Testing tool metadata...");

    assertEquals("debugger_breakpoints", tool.getToolName());

    String description = tool.getToolDescription();
    assertNotNull(description);
    assertTrue(description.contains("breakpoint"));
    assertTrue(description.contains("debugging"));

    Map<String, Object> schema = tool.getToolSchema();
    assertNotNull(schema);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("operation"));
    assertTrue(properties.containsKey("class_name"));
    assertTrue(properties.containsKey("line_number"));
    assertTrue(properties.containsKey("condition"));
    assertTrue(properties.containsKey("breakpoint_id"));

    logger.info("Tool metadata test passed");
  }

  /**
   * Tests schema operations enum.
   */
  @Test
  public void testSchemaOperations() {
    logger.info("Testing schema operations...");

    Map<String, Object> schema = tool.getToolSchema();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

    @SuppressWarnings("unchecked")
    Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");

    @SuppressWarnings("unchecked")
    List<String> operations = (List<String>) operationProp.get("enum");

    assertTrue(operations.contains("set"));
    assertTrue(operations.contains("remove"));
    assertTrue(operations.contains("removeAll"));
    assertTrue(operations.contains("list"));
    assertTrue(operations.contains("enable"));
    assertTrue(operations.contains("disable"));

    logger.info("Schema operations test passed");
  }

  /**
   * Tests set operation with basic breakpoint.
   */
  @Test
  public void testSetOperation() throws Exception {
    logger.info("Testing set operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "set");
    args.put("class_name", TEST_CLASS);
    args.put("line_number", 78);

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    Map<String, Object> result = objectMapper.readValue(resultJson, MAP_TYPE_REF);

    assertEquals("success", result.get("status"));
    assertNotNull(result.get("message"));

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) result.get("breakpoint");
    assertNotNull(breakpoint);
    assertEquals(TEST_CLASS, breakpoint.get("class_name"));
    assertEquals(78, breakpoint.get("line_number"));
    assertTrue((Boolean) breakpoint.get("enabled"));

    logger.info("Set operation test passed");
  }

  /**
   * Tests set operation with conditional breakpoint.
   */
  @Test
  public void testSetConditionalBreakpoint() throws Exception {
    logger.info("Testing set conditional breakpoint...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "set");
    args.put("class_name", TEST_CLASS);
    args.put("line_number", 78);
    args.put("condition", "a > 5");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    Map<String, Object> result = objectMapper.readValue(resultJson, MAP_TYPE_REF);

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) result.get("breakpoint");
    assertEquals("a > 5", breakpoint.get("condition"));

    logger.info("Set conditional breakpoint test passed");
  }

  /**
   * Tests list operation when empty.
   */
  @Test
  public void testListOperationEmpty() throws Exception {
    logger.info("Testing list operation empty...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "list");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(args).get()).content();

    Map<String, Object> result = objectMapper.readValue(resultJson, MAP_TYPE_REF);

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("breakpoint_count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breakpoints = (List<Map<String, Object>>) result.get("breakpoints");
    assertTrue(breakpoints.isEmpty());

    logger.info("List operation empty test passed");
  }

  /**
   * Tests list operation with breakpoints.
   */
  @Test
  public void testListOperationWithBreakpoints() throws Exception {
    logger.info("Testing list operation with breakpoints...");

    // Set a breakpoint first
    Map<String, Object> setArgs = new HashMap<>();
    setArgs.put("operation", "set");
    setArgs.put("class_name", TEST_CLASS);
    setArgs.put("line_number", 78);
    tool.executeAsync(setArgs).get();

    // List breakpoints
    Map<String, Object> listArgs = new HashMap<>();
    listArgs.put("operation", "list");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(listArgs).get()).content();

    Map<String, Object> result = objectMapper.readValue(resultJson, MAP_TYPE_REF);

    assertEquals(1, result.get("breakpoint_count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breakpoints = (List<Map<String, Object>>) result.get("breakpoints");
    assertEquals(1, breakpoints.size());

    Map<String, Object> bp = breakpoints.get(0);
    assertEquals(TEST_CLASS, bp.get("class_name"));
    assertEquals(78, bp.get("line_number"));

    logger.info("List operation with breakpoints test passed");
  }

  /**
   * Tests remove operation.
   */
  @Test
  public void testRemoveOperation() throws Exception {
    logger.info("Testing remove operation...");

    // Set a breakpoint
    Map<String, Object> setArgs = new HashMap<>();
    setArgs.put("operation", "set");
    setArgs.put("class_name", TEST_CLASS);
    setArgs.put("line_number", 78);

    String setJson = ((ToolResponse.Success) tool.executeAsync(setArgs).get()).content();

    Map<String, Object> setResult = objectMapper.readValue(setJson, MAP_TYPE_REF);

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) setResult.get("breakpoint");
    long breakpointId = ((Number) breakpoint.get("id")).longValue();

    // Remove it
    Map<String, Object> removeArgs = new HashMap<>();
    removeArgs.put("operation", "remove");
    removeArgs.put("breakpoint_id", breakpointId);

    String removeJson = ((ToolResponse.Success) tool.executeAsync(removeArgs).get()).content();

    Map<String, Object> removeResult = objectMapper.readValue(removeJson, MAP_TYPE_REF);

    assertEquals("success", removeResult.get("status"));
    assertEquals(breakpointId, ((Number) removeResult.get("breakpoint_id")).longValue());

    logger.info("Remove operation test passed");
  }

  /**
   * Tests removeAll operation.
   */
  @Test
  public void testRemoveAllOperation() throws Exception {
    logger.info("Testing removeAll operation...");

    // Set multiple breakpoints
    for (int line : new int[] { 78, 94, 112 }) {
      Map<String, Object> setArgs = new HashMap<>();
      setArgs.put("operation", "set");
      setArgs.put("class_name", TEST_CLASS);
      setArgs.put("line_number", line);
      tool.executeAsync(setArgs).get();
    }

    // Remove all
    Map<String, Object> removeAllArgs = new HashMap<>();
    removeAllArgs.put("operation", "removeAll");

    String resultJson = ((ToolResponse.Success) tool.executeAsync(removeAllArgs).get()).content();

    Map<String, Object> result = objectMapper.readValue(resultJson, MAP_TYPE_REF);

    assertEquals("success", result.get("status"));
    assertEquals(3, result.get("removed_count"));

    // Verify list is empty
    Map<String, Object> listArgs = new HashMap<>();
    listArgs.put("operation", "list");
    String listJson = ((ToolResponse.Success) tool.executeAsync(listArgs).get()).content();

    Map<String, Object> listResult = objectMapper.readValue(listJson, MAP_TYPE_REF);
    assertEquals(0, listResult.get("breakpoint_count"));

    logger.info("RemoveAll operation test passed");
  }

  /**
   * Tests enable operation.
   */
  @Test
  public void testEnableOperation() throws Exception {
    logger.info("Testing enable operation...");

    // Set and disable a breakpoint
    Map<String, Object> setArgs = new HashMap<>();
    setArgs.put("operation", "set");
    setArgs.put("class_name", TEST_CLASS);
    setArgs.put("line_number", 78);

    String setJson = ((ToolResponse.Success) tool.executeAsync(setArgs).get()).content();

    Map<String, Object> setResult = objectMapper.readValue(setJson, MAP_TYPE_REF);

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) setResult.get("breakpoint");
    long breakpointId = ((Number) breakpoint.get("id")).longValue();

    // Disable first
    Map<String, Object> disableArgs = new HashMap<>();
    disableArgs.put("operation", "disable");
    disableArgs.put("breakpoint_id", breakpointId);
    tool.executeAsync(disableArgs).get();

    // Enable
    Map<String, Object> enableArgs = new HashMap<>();
    enableArgs.put("operation", "enable");
    enableArgs.put("breakpoint_id", breakpointId);

    String enableJson = ((ToolResponse.Success) tool.executeAsync(enableArgs).get()).content();

    Map<String, Object> enableResult = objectMapper.readValue(enableJson, MAP_TYPE_REF);

    assertEquals("success", enableResult.get("status"));
    assertEquals(breakpointId, ((Number) enableResult.get("breakpoint_id")).longValue());

    logger.info("Enable operation test passed");
  }

  /**
   * Tests disable operation.
   */
  @Test
  public void testDisableOperation() throws Exception {
    logger.info("Testing disable operation...");

    // Set a breakpoint
    Map<String, Object> setArgs = new HashMap<>();
    setArgs.put("operation", "set");
    setArgs.put("class_name", TEST_CLASS);
    setArgs.put("line_number", 78);

    String setJson = ((ToolResponse.Success) tool.executeAsync(setArgs).get()).content();

    Map<String, Object> setResult = objectMapper.readValue(setJson, MAP_TYPE_REF);

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) setResult.get("breakpoint");
    long breakpointId = ((Number) breakpoint.get("id")).longValue();

    // Disable
    Map<String, Object> disableArgs = new HashMap<>();
    disableArgs.put("operation", "disable");
    disableArgs.put("breakpoint_id", breakpointId);

    String disableJson = ((ToolResponse.Success) tool.executeAsync(disableArgs).get()).content();

    Map<String, Object> disableResult = objectMapper.readValue(disableJson, MAP_TYPE_REF);

    assertEquals("success", disableResult.get("status"));
    assertEquals(breakpointId, ((Number) disableResult.get("breakpoint_id")).longValue());

    logger.info("Disable operation test passed");
  }

  /**
   * Tests set operation missing class_name returns error.
   */
  @Test
  public void testSetMissingClassName() throws Exception {
    logger.info("Testing set missing class_name...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "set");
    args.put("line_number", 78);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("class_name is required"));

    logger.info("Set missing class_name test passed");
  }

  /**
   * Tests set operation missing line_number returns error.
   */
  @Test
  public void testSetMissingLineNumber() throws Exception {
    logger.info("Testing set missing line_number...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "set");
    args.put("class_name", TEST_CLASS);

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("line_number is required"));

    logger.info("Set missing line_number test passed");
  }

  /**
   * Tests remove operation missing breakpoint_id returns error.
   */
  @Test
  public void testRemoveMissingBreakpointId() throws Exception {
    logger.info("Testing remove missing breakpoint_id...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "remove");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("breakpoint_id is required"));

    logger.info("Remove missing breakpoint_id test passed");
  }

  /**
   * Tests unknown operation returns error.
   */
  @Test
  public void testUnknownOperation() throws Exception {
    logger.info("Testing unknown operation...");

    Map<String, Object> args = new HashMap<>();
    args.put("operation", "invalid_op");

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Unknown operation"));

    logger.info("Unknown operation test passed");
  }

  /**
   * Tests missing operation parameter returns error.
   */
  @Test
  public void testMissingOperation() throws Exception {
    logger.info("Testing missing operation...");

    Map<String, Object> args = new HashMap<>();

    ToolResponse response = tool.executeAsync(args).get();

    assertTrue(response instanceof ToolResponse.Error);
    ToolResponse.Error error = (ToolResponse.Error) response;
    assertTrue(error.message().contains("Operation is required"));

    logger.info("Missing operation test passed");
  }

  /**
   * Tests complete breakpoint workflow (set -> list -> disable -> enable ->
   * remove).
   */
  @Test
  public void testCompleteWorkflow() throws Exception {
    logger.info("Testing complete workflow...");

    // 1. Set breakpoint
    Map<String, Object> setArgs = new HashMap<>();
    setArgs.put("operation", "set");
    setArgs.put("class_name", TEST_CLASS);
    setArgs.put("line_number", 78);
    setArgs.put("condition", "a > 10");

    String setJson = ((ToolResponse.Success) tool.executeAsync(setArgs).get()).content();

    Map<String, Object> setResult = objectMapper.readValue(setJson, MAP_TYPE_REF);

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) setResult.get("breakpoint");
    long breakpointId = ((Number) breakpoint.get("id")).longValue();

    // 2. List - should have 1
    Map<String, Object> listArgs = new HashMap<>();
    listArgs.put("operation", "list");
    String listJson = ((ToolResponse.Success) tool.executeAsync(listArgs).get()).content();

    Map<String, Object> listResult = objectMapper.readValue(listJson, MAP_TYPE_REF);
    assertEquals(1, listResult.get("breakpoint_count"));

    // 3. Disable
    Map<String, Object> disableArgs = new HashMap<>();
    disableArgs.put("operation", "disable");
    disableArgs.put("breakpoint_id", breakpointId);
    tool.executeAsync(disableArgs).get();

    // 4. Enable
    Map<String, Object> enableArgs = new HashMap<>();
    enableArgs.put("operation", "enable");
    enableArgs.put("breakpoint_id", breakpointId);
    tool.executeAsync(enableArgs).get();

    // 5. Remove
    Map<String, Object> removeArgs = new HashMap<>();
    removeArgs.put("operation", "remove");
    removeArgs.put("breakpoint_id", breakpointId);
    tool.executeAsync(removeArgs).get();

    // 6. List - should be empty
    listJson = ((ToolResponse.Success) tool.executeAsync(listArgs).get()).content();
    listResult = objectMapper.readValue(listJson, MAP_TYPE_REF);
    assertEquals(0, listResult.get("breakpoint_count"));

    logger.info("Complete workflow test passed");
  }
}
