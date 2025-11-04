package com.bitsapplied.descartes.debugger.breakpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggerTestBase;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

/**
 * Tests for MethodBreakpointManager.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Method entry breakpoints</li>
 * <li>Method exit breakpoints</li>
 * <li>Combined entry/exit breakpoints</li>
 * <li>Pattern-based class filtering</li>
 * <li>Method name filtering</li>
 * <li>Enable/disable operations</li>
 * <li>Listing breakpoints</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.OTHER })
public class MethodBreakpointManagerTest extends DebuggerTestBase {
  private static final Logger logger = LoggerFactory.getLogger(MethodBreakpointManagerTest.class);

  /**
   * Tests setting a method entry breakpoint.
   */
  @Test
  public void testSetMethodEntry() throws Exception {
    logger.info("Testing set method entry...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    long id = mbm.setMethodEntry(classPattern);

    assertTrue(id > 0, "Breakpoint ID should be positive");

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertEquals(1, breakpoints.size());

    Map<String, Object> bp = breakpoints.get(0);
    assertEquals(id, bp.get("id"));
    assertEquals("method_entry", bp.get("type"));
    assertEquals(classPattern, bp.get("class_pattern"));
    assertEquals("*", bp.get("method_name"));
    assertTrue((Boolean) bp.get("enabled"));

    logger.info("Set method entry test passed");
  }

  /**
   * Tests setting a method exit breakpoint.
   */
  @Test
  public void testSetMethodExit() throws Exception {
    logger.info("Testing set method exit...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    long id = mbm.setMethodExit(classPattern);

    assertTrue(id > 0, "Breakpoint ID should be positive");

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertEquals(1, breakpoints.size());

    Map<String, Object> bp = breakpoints.get(0);
    assertEquals(id, bp.get("id"));
    assertEquals("method_exit", bp.get("type"));

    logger.info("Set method exit test passed");
  }

  /**
   * Tests setting method entry with method name filter.
   */
  @Test
  public void testSetMethodEntryWithMethodName() throws Exception {
    logger.info("Testing set method entry with method name...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    String methodName = "calculateSum";

    long _ = mbm.setMethodEntry(classPattern, methodName);

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertEquals(1, breakpoints.size());

    Map<String, Object> bp = breakpoints.get(0);
    assertEquals(methodName, bp.get("method_name"));

    logger.info("Set method entry with method name test passed");
  }

  /**
   * Tests setting method exit with method name filter.
   */
  @Test
  public void testSetMethodExitWithMethodName() throws Exception {
    logger.info("Testing set method exit with method name...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    String methodName = "calculateFactorial";

    long _ = mbm.setMethodExit(classPattern, methodName);

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    Map<String, Object> bp = breakpoints.get(0);
    assertEquals(methodName, bp.get("method_name"));

    logger.info("Set method exit with method name test passed");
  }

  /**
   * Tests setting both entry and exit breakpoints.
   */
  @Test
  public void testSetMethodBreakpoint() throws Exception {
    logger.info("Testing set method breakpoint (entry + exit)...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    String methodName = "calculateSum";

    long[] ids = mbm.setMethodBreakpoint(classPattern, methodName);

    assertNotNull(ids);
    assertEquals(2, ids.length);
    assertTrue(ids[0] > 0, "Entry ID should be positive");
    assertTrue(ids[1] > 0, "Exit ID should be positive");

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertEquals(2, breakpoints.size());

    // Verify we have one entry and one exit
    long entryCount = breakpoints.stream().filter(bp -> "method_entry".equals(bp.get("type"))).count();
    long exitCount = breakpoints.stream().filter(bp -> "method_exit".equals(bp.get("type"))).count();

    assertEquals(1, entryCount);
    assertEquals(1, exitCount);

    logger.info("Set method breakpoint test passed");
  }

  /**
   * Tests removing a method breakpoint.
   */
  @Test
  public void testRemoveBreakpoint() throws Exception {
    logger.info("Testing remove breakpoint...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    long id = mbm.setMethodEntry(classPattern);

    assertEquals(1, mbm.listBreakpoints().size());

    mbm.removeBreakpoint(id);

    assertEquals(0, mbm.listBreakpoints().size());

    logger.info("Remove breakpoint test passed");
  }

  /**
   * Tests removing a non-existent breakpoint.
   */
  @Test
  public void testRemoveNonExistentBreakpoint() throws Exception {
    logger.info("Testing remove non-existent breakpoint...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    assertThrows(DebuggerException.class, () -> mbm.removeBreakpoint(999L), "Should throw for non-existent breakpoint");

    logger.info("Remove non-existent breakpoint test passed");
  }

  /**
   * Tests removing all breakpoints.
   */
  @Test
  public void testRemoveAllBreakpoints() throws Exception {
    logger.info("Testing remove all breakpoints...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();

    mbm.setMethodEntry(classPattern, "calculateSum");
    mbm.setMethodExit(classPattern, "calculateFactorial");
    mbm.setMethodEntry(classPattern, "createList");

    assertEquals(3, mbm.listBreakpoints().size());

    mbm.removeAllBreakpoints();

    assertEquals(0, mbm.listBreakpoints().size());

    logger.info("Remove all breakpoints test passed");
  }

  /**
   * Tests enabling a method breakpoint.
   */
  @Test
  public void testEnableBreakpoint() throws Exception {
    logger.info("Testing enable breakpoint...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    long id = mbm.setMethodEntry(classPattern);

    // Disable first
    mbm.disableBreakpoint(id);
    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertFalse((Boolean) breakpoints.get(0).get("enabled"));

    // Re-enable
    mbm.enableBreakpoint(id);
    breakpoints = mbm.listBreakpoints();
    assertTrue((Boolean) breakpoints.get(0).get("enabled"));

    logger.info("Enable breakpoint test passed");
  }

  /**
   * Tests disabling a method breakpoint.
   */
  @Test
  public void testDisableBreakpoint() throws Exception {
    logger.info("Testing disable breakpoint...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    long id = mbm.setMethodEntry(classPattern);

    // Initially enabled
    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertTrue((Boolean) breakpoints.get(0).get("enabled"));

    // Disable
    mbm.disableBreakpoint(id);
    breakpoints = mbm.listBreakpoints();
    assertFalse((Boolean) breakpoints.get(0).get("enabled"));

    logger.info("Disable breakpoint test passed");
  }

  /**
   * Tests enabling/disabling non-existent breakpoint.
   */
  @Test
  public void testEnableDisableNonExistentBreakpoint() throws Exception {
    logger.info("Testing enable/disable non-existent breakpoint...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    assertThrows(DebuggerException.class, () -> mbm.enableBreakpoint(999L), "Should throw for non-existent breakpoint");

    assertThrows(DebuggerException.class, () -> mbm.disableBreakpoint(999L),
        "Should throw for non-existent breakpoint");

    logger.info("Enable/disable non-existent breakpoint test passed");
  }

  /**
   * Tests listing breakpoints when none exist.
   */
  @Test
  public void testListBreakpointsEmpty() throws Exception {
    logger.info("Testing list breakpoints empty...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertNotNull(breakpoints);
    assertTrue(breakpoints.isEmpty());

    logger.info("List breakpoints empty test passed");
  }

  /**
   * Tests listing multiple breakpoints.
   */
  @Test
  public void testListMultipleBreakpoints() throws Exception {
    logger.info("Testing list multiple breakpoints...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();

    mbm.setMethodEntry(classPattern, "calculateSum");
    mbm.setMethodExit(classPattern, "calculateSum");
    mbm.setMethodEntry(classPattern, "calculateFactorial");

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertEquals(3, breakpoints.size());

    logger.info("List multiple breakpoints test passed");
  }

  /**
   * Tests method filter matching with no filter (matches all).
   */
  @Test
  public void testMatchesMethodFilterNoFilter() throws Exception {
    logger.info("Testing matches method filter no filter...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    mbm.setMethodEntry(classPattern); // No method filter

    mbm.listBreakpoints();
    var request = debuggerService.getVirtualMachine().eventRequestManager().methodEntryRequests().get(0);

    // Should match any method name when no filter is set
    assertTrue(mbm.matchesMethodFilter(request, "anyMethod"));
    assertTrue(mbm.matchesMethodFilter(request, "calculateSum"));
    assertTrue(mbm.matchesMethodFilter(request, "main"));

    logger.info("Matches method filter no filter test passed");
  }

  /**
   * Tests method filter matching with specific method name.
   */
  @Test
  public void testMatchesMethodFilterWithMethodName() throws Exception {
    logger.info("Testing matches method filter with method name...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    String classPattern = getTestApplicationClassName();
    String methodName = "calculateSum";
    long _ = mbm.setMethodEntry(classPattern, methodName);

    var request = debuggerService.getVirtualMachine().eventRequestManager().methodEntryRequests().get(0);

    // Should match exact method name
    assertTrue(mbm.matchesMethodFilter(request, "calculateSum"));

    // Should not match different methods
    assertFalse(mbm.matchesMethodFilter(request, "calculateFactorial"));
    assertFalse(mbm.matchesMethodFilter(request, "main"));

    logger.info("Matches method filter with method name test passed");
  }

  /**
   * Tests setting breakpoints with wildcard class patterns.
   */
  @Test
  public void testWildcardClassPattern() throws Exception {
    logger.info("Testing wildcard class pattern...");

    startDebugSession();
    MethodBreakpointManager mbm = debuggerService.getMethodBreakpointManager();

    // Use wildcard pattern
    long id = mbm.setMethodEntry("com.bitsapplied.descartes.debugger.*");

    assertTrue(id > 0);

    List<Map<String, Object>> breakpoints = mbm.listBreakpoints();
    assertEquals(1, breakpoints.size());

    logger.info("Wildcard class pattern test passed");
  }
}
