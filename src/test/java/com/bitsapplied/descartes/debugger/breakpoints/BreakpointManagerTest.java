package com.bitsapplied.descartes.debugger.breakpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggerTestBase;
import com.bitsapplied.descartes.debugger.breakpoints.BreakpointManager.BreakpointInfo;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

/**
 * Tests for BreakpointManager.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Setting and removing breakpoints</li>
 * <li>Conditional breakpoints</li>
 * <li>Enable/disable operations</li>
 * <li>Breakpoint queries</li>
 * <li>Multiple breakpoints in same class</li>
 * <li>Error handling</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class BreakpointManagerTest extends DebuggerTestBase {
  private static final Logger logger = LoggerFactory.getLogger(BreakpointManagerTest.class);

  /**
   * Tests setting a basic breakpoint.
   */
  @Test
  public void testSetBasicBreakpoint() throws Exception {
    logger.info("Testing set basic breakpoint...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    int lineNumber = 78; // Line in calculateSum method

    long id = bpm.setBreakpoint(className, lineNumber);

    assertTrue(id > 0, "Breakpoint ID should be positive");
    assertEquals(1, bpm.getBreakpointCount());

    BreakpointInfo info = bpm.getBreakpoint(id);
    assertNotNull(info);
    assertEquals(className, info.className());
    assertEquals(lineNumber, info.lineNumber());
    assertTrue(info.isEnabled());
    assertTrue(info.verified());
    assertFalse(info.isConditional());

    logger.info("Set basic breakpoint test passed");
  }

  /**
   * Tests setting a conditional breakpoint.
   */
  @Test
  public void testSetConditionalBreakpoint() throws Exception {
    logger.info("Testing set conditional breakpoint...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    int lineNumber = 78;
    String condition = "a > 5";

    long id = bpm.setBreakpoint(className, lineNumber, condition);

    BreakpointInfo info = bpm.getBreakpoint(id);
    assertNotNull(info);
    assertTrue(info.isConditional());
    assertEquals(condition, info.condition());

    logger.info("Set conditional breakpoint test passed");
  }

  /**
   * Tests removing a breakpoint.
   */
  @Test
  public void testRemoveBreakpoint() throws Exception {
    logger.info("Testing remove breakpoint...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    long id = bpm.setBreakpoint(className, 78);
    assertEquals(1, bpm.getBreakpointCount());

    bpm.removeBreakpoint(id);
    assertEquals(0, bpm.getBreakpointCount());

    // Verify breakpoint is removed
    assertThrows(DebuggerException.class, () -> bpm.getBreakpoint(id));

    logger.info("Remove breakpoint test passed");
  }

  /**
   * Tests removing a non-existent breakpoint.
   */
  @Test
  public void testRemoveNonExistentBreakpoint() throws Exception {
    logger.info("Testing remove non-existent breakpoint...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    assertThrows(DebuggerException.class, () -> bpm.removeBreakpoint(999L), "Should throw for non-existent breakpoint");

    logger.info("Remove non-existent breakpoint test passed");
  }

  /**
   * Tests setting multiple breakpoints in the same class.
   */
  @Test
  public void testMultipleBreakpointsInSameClass() throws Exception {
    logger.info("Testing multiple breakpoints in same class...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();

    bpm.setBreakpoint(className, 78); // calculateSum
    bpm.setBreakpoint(className, 94); // calculateFactorial loop
    bpm.setBreakpoint(className, 112); // createList return

    assertEquals(3, bpm.getBreakpointCount());

    List<BreakpointInfo> classBreakpoints = bpm.getBreakpointsForClass(className);
    assertEquals(3, classBreakpoints.size());

    assertTrue(bpm.hasBreakpointAt(className, 78));
    assertTrue(bpm.hasBreakpointAt(className, 94));
    assertTrue(bpm.hasBreakpointAt(className, 112));

    logger.info("Multiple breakpoints in same class test passed");
  }

  /**
   * Tests enabling and disabling breakpoints.
   */
  @Test
  public void testEnableDisableBreakpoint() throws Exception {
    logger.info("Testing enable/disable breakpoint...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    long id = bpm.setBreakpoint(className, 78);

    // Initially enabled
    BreakpointInfo info = bpm.getBreakpoint(id);
    assertTrue(info.isEnabled());

    // Disable
    bpm.disableBreakpoint(id);
    info = bpm.getBreakpoint(id);
    assertFalse(info.isEnabled());

    // Re-enable
    bpm.enableBreakpoint(id);
    info = bpm.getBreakpoint(id);
    assertTrue(info.isEnabled());

    logger.info("Enable/disable breakpoint test passed");
  }

  /**
   * Tests getting all breakpoints.
   */
  @Test
  public void testGetAllBreakpoints() throws Exception {
    logger.info("Testing get all breakpoints...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();

    bpm.setBreakpoint(className, 78);
    bpm.setBreakpoint(className, 94);
    bpm.setBreakpoint(className, 112);

    List<BreakpointInfo> allBreakpoints = bpm.getAllBreakpoints();
    assertEquals(3, allBreakpoints.size());

    logger.info("Get all breakpoints test passed");
  }

  /**
   * Tests removing all breakpoints.
   */
  @Test
  public void testRemoveAllBreakpoints() throws Exception {
    logger.info("Testing remove all breakpoints...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();

    bpm.setBreakpoint(className, 78);
    bpm.setBreakpoint(className, 94);
    bpm.setBreakpoint(className, 112);

    assertEquals(3, bpm.getBreakpointCount());

    bpm.removeAllBreakpoints();
    assertEquals(0, bpm.getBreakpointCount());

    logger.info("Remove all breakpoints test passed");
  }

  /**
   * Tests setting breakpoint on non-existent class.
   */
  @Test
  public void testSetBreakpointOnNonExistentClass() throws Exception {
    logger.info("Testing set breakpoint on non-existent class...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    assertThrows(DebuggerException.class, () -> bpm.setBreakpoint("com.example.NonExistent", 10),
        "Should throw for non-existent class");

    logger.info("Set breakpoint on non-existent class test passed");
  }

  /**
   * Tests setting breakpoint on non-executable line.
   */
  @Test
  public void testSetBreakpointOnNonExecutableLine() throws Exception {
    logger.info("Testing set breakpoint on non-executable line...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();

    // Try setting breakpoint on class declaration line (line 1) or a comment/blank
    // line
    assertThrows(DebuggerException.class, () -> bpm.setBreakpoint(className, 1),
        "Should throw for non-executable line");

    logger.info("Set breakpoint on non-executable line test passed");
  }

  /**
   * Tests breakpoint with blank condition (treated as unconditional).
   */
  @Test
  public void testBreakpointWithBlankCondition() throws Exception {
    logger.info("Testing breakpoint with blank condition...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    long id = bpm.setBreakpoint(className, 78, "   "); // Blank condition

    BreakpointInfo info = bpm.getBreakpoint(id);
    assertFalse(info.isConditional(), "Blank condition should be treated as unconditional");

    logger.info("Breakpoint with blank condition test passed");
  }

  /**
   * Tests getting breakpoint info includes method name.
   */
  @Test
  public void testBreakpointInfoIncludesMethodName() throws Exception {
    logger.info("Testing breakpoint info includes method name...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    long id = bpm.setBreakpoint(className, 78); // In calculateSum method

    BreakpointInfo info = bpm.getBreakpoint(id);
    String methodName = info.getMethodName();

    assertNotNull(methodName);
    assertEquals("calculateSum", methodName);

    logger.info("Breakpoint info includes method name test passed");
  }

  /**
   * Tests breakpoint toMap for JSON serialization.
   */
  @Test
  public void testBreakpointToMap() throws Exception {
    logger.info("Testing breakpoint toMap...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();
    long id = bpm.setBreakpoint(className, 78, "a > 5");

    BreakpointInfo info = bpm.getBreakpoint(id);
    var map = info.toMap();

    assertNotNull(map);
    assertEquals(id, map.get("id"));
    assertEquals(className, map.get("class_name"));
    assertEquals(78, map.get("line_number"));
    assertTrue((Boolean) map.get("enabled"));
    assertTrue((Boolean) map.get("verified"));
    assertEquals("a > 5", map.get("condition"));
    assertNotNull(map.get("method"));

    logger.info("Breakpoint toMap test passed");
  }

  /**
   * Tests hasBreakpointAt query.
   */
  @Test
  public void testHasBreakpointAt() throws Exception {
    logger.info("Testing hasBreakpointAt...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();

    assertFalse(bpm.hasBreakpointAt(className, 78), "Should not have breakpoint initially");

    bpm.setBreakpoint(className, 78);

    assertTrue(bpm.hasBreakpointAt(className, 78), "Should have breakpoint after setting");

    assertFalse(bpm.hasBreakpointAt(className, 79), "Should not have breakpoint at different line");

    logger.info("HasBreakpointAt test passed");
  }

  /**
   * Tests clear operation resets ID generator.
   */
  @Test
  public void testClearResetsIdGenerator() throws Exception {
    logger.info("Testing clear resets ID generator...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    String className = getTestApplicationClassName();

    long id1 = bpm.setBreakpoint(className, 78);
    assertEquals(1, id1, "First ID should be 1");

    bpm.clear();
    assertEquals(0, bpm.getBreakpointCount());

    long id2 = bpm.setBreakpoint(className, 78);
    assertEquals(1, id2, "ID should reset to 1 after clear");

    logger.info("Clear resets ID generator test passed");
  }

  /**
   * Tests getting breakpoints for non-existent class returns empty list.
   */
  @Test
  public void testGetBreakpointsForNonExistentClass() throws Exception {
    logger.info("Testing get breakpoints for non-existent class...");

    startDebugSession();
    BreakpointManager bpm = debuggerService.getBreakpointManager();

    List<BreakpointInfo> breakpoints = bpm.getBreakpointsForClass("com.example.NonExistent");
    assertNotNull(breakpoints);
    assertTrue(breakpoints.isEmpty());

    logger.info("Get breakpoints for non-existent class test passed");
  }
}
