package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test for LoggingIntegrationTool MCP tool.
 */
public class LoggingIntegrationToolTest {

  private static final Logger logger = LogManager.getLogger(LoggingIntegrationToolTest.class);
  private LoggingIntegrationTool tool;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    tool = new LoggingIntegrationTool();
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testToolMetadata() {
    assertEquals("logging_integration", tool.getToolName());
    assertNotNull(tool.getToolDescription());
    assertNotNull(tool.getToolSchema());

    Map<String, Object> schema = tool.getToolSchema();
    assertEquals("object", schema.get("type"));
    assertTrue(schema.containsKey("properties"));
  }

  @Test
  public void testTailOperation() throws Exception {
    // Generate some test logs
    logger.info("Test log entry 1");
    logger.warn("Test warning message");
    logger.error("Test error message");

    Map<String, Object> args = Map.of("operation", "tail", "lines", 10);

    String result = tool.executeTool(args);
    assertNotNull(result);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
    assertEquals("success", resultMap.get("status"));
    assertTrue(resultMap.containsKey("logs"));
    assertTrue(resultMap.containsKey("total_buffered"));

    // Verify actual log content
    @SuppressWarnings("unchecked")
    List<String> logs = (List<String>) resultMap.get("logs");
    assertNotNull(logs);
    // Should contain at least our test logs
    assertTrue(logs.stream().anyMatch(log -> log.contains("Test log entry 1")));
    assertTrue(logs.stream().anyMatch(log -> log.contains("Test warning message")));
    assertTrue(logs.stream().anyMatch(log -> log.contains("Test error message")));

    // Verify lines parameter is respected
    assertTrue(logs.size() <= 10, "Should return at most 10 lines");

    // Verify total_buffered is a reasonable number
    Integer totalBuffered = (Integer) resultMap.get("total_buffered");
    assertTrue(totalBuffered >= 3, "Should have at least our 3 test messages");
  }

  @Test
  public void testListLoggersOperation() throws Exception {
    Map<String, Object> args = Map.of("operation", "list_loggers");

    String result = tool.executeTool(args);
    assertNotNull(result);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
    assertEquals("success", resultMap.get("status"));
    assertTrue(resultMap.containsKey("loggers"));
    assertTrue(resultMap.containsKey("logger_count"));

    // Verify logger list content
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> loggers = (List<Map<String, Object>>) resultMap.get("loggers");
    assertNotNull(loggers);
    assertFalse(loggers.isEmpty(), "Should have at least one logger");

    // Verify logger structure
    Map<String, Object> firstLogger = loggers.get(0);
    assertTrue(firstLogger.containsKey("name"));
    assertTrue(firstLogger.containsKey("level"));
    assertNotNull(firstLogger.get("name"));
    assertNotNull(firstLogger.get("level"));

    // Verify count matches list size
    Integer loggerCount = (Integer) resultMap.get("logger_count");
    assertEquals(loggers.size(), loggerCount, "Logger count should match list size");
  }

  @Test
  public void testSetLogLevel() throws Exception {
    Map<String, Object> args = Map.of("operation", "level", "logger", "com.bitsapplied.descartes.test", "new_level",
        "DEBUG");

    String result = tool.executeTool(args);
    assertNotNull(result);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
    assertEquals("success", resultMap.get("status"));
    assertEquals("DEBUG", resultMap.get("new_level"));
  }

  @Test
  public void testGrepOperation() throws Exception {
    // Generate some logs with patterns
    logger.info("Starting process ABC123");
    logger.info("Processing item XYZ789");
    logger.error("Error in process ABC123");

    Map<String, Object> args = Map.of("operation", "grep", "pattern", "ABC123");

    String result = tool.executeTool(args);
    assertNotNull(result);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
    assertEquals("success", resultMap.get("status"));
    assertTrue(resultMap.containsKey("matches"));
    assertTrue(resultMap.containsKey("matches_found"));

    // Verify matches content
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matches = (List<Map<String, Object>>) resultMap.get("matches");
    assertNotNull(matches);
    assertEquals(2, matches.size(), "Should find exactly 2 matches for ABC123");

    // Verify all matches contain the pattern
    for (Map<String, Object> match : matches) {
      // The match structure might have the full log line or just the message
      String logLine = match.get("message") != null ? (String) match.get("message")
          : match.get("log") != null ? (String) match.get("log") : match.toString();
      assertTrue(logLine.contains("ABC123"), "Each match should contain the pattern");
    }

    // Verify matches_found count
    Integer matchesFound = (Integer) resultMap.get("matches_found");
    assertEquals(2, matchesFound, "Matches found count should be 2");
  }

  @Test
  public void testStatsOperation() throws Exception {
    // Generate diverse logs
    logger.trace("Trace message");
    logger.debug("Debug message");
    logger.info("Info message");
    logger.warn("Warning message");
    logger.error("Error message");

    Map<String, Object> args = Map.of("operation", "stats");

    String result = tool.executeTool(args);
    assertNotNull(result);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
    assertEquals("success", resultMap.get("status"));
    assertTrue(resultMap.containsKey("level_distribution"));
    assertTrue(resultMap.containsKey("top_loggers"));

    // Verify level distribution
    @SuppressWarnings("unchecked")
    Map<String, Integer> levelDist = (Map<String, Integer>) resultMap.get("level_distribution");
    assertNotNull(levelDist);
    // At minimum we should have our test logs
    assertTrue(levelDist.getOrDefault("INFO", 0) >= 1, "Should have at least 1 INFO log");
    assertTrue(levelDist.getOrDefault("WARN", 0) >= 1, "Should have at least 1 WARN log");
    assertTrue(levelDist.getOrDefault("ERROR", 0) >= 1, "Should have at least 1 ERROR log");

    // Verify top loggers
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topLoggers = (List<Map<String, Object>>) resultMap.get("top_loggers");
    assertNotNull(topLoggers);
    // Top loggers might be empty if no logs have been captured yet
    // Just verify the structure if there are any
    if (!topLoggers.isEmpty()) {
      Map<String, Object> topLogger = topLoggers.get(0);
      assertTrue(topLogger.containsKey("logger"));
      assertTrue(topLogger.containsKey("count"));
      assertNotNull(topLogger.get("logger"));
      assertTrue(((Integer) topLogger.get("count")) > 0);
    }
  }

  @Test
  public void testInvalidOperation() {
    Map<String, Object> args = Map.of("operation", "invalid_op");

    assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });
  }

  @Test
  public void testMissingRequiredParameter() {
    Map<String, Object> args = Map.of("operation", "level", "logger", "test.logger"
    // missing new_level
    );

    assertThrows(IllegalArgumentException.class, () -> {
      tool.executeTool(args);
    });
  }
}