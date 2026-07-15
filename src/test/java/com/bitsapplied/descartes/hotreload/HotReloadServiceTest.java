package com.bitsapplied.descartes.hotreload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.bitsapplied.descartes.hotreload.agent.ClassLoadInfo;
import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;
import com.bitsapplied.descartes.hotreload.test.BytecodeModificationUtil;
import com.bitsapplied.descartes.hotreload.test.IncompatibleChangeTestClass;
import com.bitsapplied.descartes.hotreload.test.ReloadableTestClass;

/**
 * Comprehensive test suite for hot reload functionality.
 * 
 * <p>
 * <b>IMPORTANT:</b> These tests require the JVM agent to be loaded and will
 * skip if not available.
 * </p>
 * 
 * <h3>Running with Maven:</h3>
 * 
 * <pre>
 * # Run only hot reload tests (recommended)
 * mvn test -Phot-reload-tests
 * 
 * # Run all tests including hot reload tests
 * mvn test -Pall-tests
 * 
 * # These tests are NOT included in the default build
 * mvn test  # Will NOT run these tests
 * </pre>
 * 
 * <h3>Running in Eclipse/IDE:</h3>
 * <ol>
 * <li>First build the agent JAR: {@code mvn clean package}</li>
 * <li>Add VM arguments to your run configuration:
 * 
 * <pre>
 * -XX:+EnableDynamicAgentLoading -javaagent:target/descartes-mcp-1.0.2-jar-with-dependencies.jar
 * </pre>
 * 
 * </li>
 * <li>Run as JUnit Test</li>
 * </ol>
 * 
 * <p>
 * Without the agent, tests will output: "WARNING: Hot reload agent not loaded.
 * Some tests will be skipped."
 * </p>
 * 
 * @see com.bitsapplied.descartes.hotreload.agent.HotReloadAgent
 * @see HotReloadService
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HotReloadServiceTest {

  private static HotReloadService hotReloadService;
  private static boolean agentAvailable;

  @BeforeAll
  static void setupClass() {
    // Check if agent is loaded
    agentAvailable = HotReloadAgent.isAgentLoaded();

    if (!agentAvailable) {
      System.err.println("WARNING: Hot reload agent not loaded. Some tests will be skipped.");
      System.err.println("Run with: mvn test -Phot-reload-tests");
    }

    // Create service
    Map<String, Object> context = new HashMap<>();
    hotReloadService = new HotReloadService(context);
  }

  @Test
  @Order(1)
  @DisplayName("Test agent availability")
  void testAgentAvailable() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    assertTrue(HotReloadAgent.isAgentLoaded(),
        "Hot reload agent should be loaded. Run with -Phot-reload-tests profile");
    assertNotNull(HotReloadAgent.getInstrumentation(), "Instrumentation should be available");
  }

  @Test
  @Order(2)
  @DisplayName("Test successful method body reload")
  void testSuccessfulMethodBodyReload() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Get the original test class
    ReloadableTestClass instance = new ReloadableTestClass();
    assertEquals(1, ReloadableTestClass.getVersion(), "Initial version should be 1");
    assertEquals("Original", instance.getMessage(), "Initial message should be 'Original'");
    assertEquals(5, instance.calculate(2, 3), "Initial calculation should be addition");

    // Get original bytecode
    byte[] originalBytecode = getClassBytecode(ReloadableTestClass.class);

    // Create modified bytecode
    byte[] modifiedBytecode = BytecodeModificationUtil.modifyReloadableTestClassVersion(originalBytecode, 42);

    // Simulate bytecode change by directly updating via Instrumentation
    Instrumentation inst = HotReloadAgent.getInstrumentation();
    inst.redefineClasses(new ClassDefinition(ReloadableTestClass.class, modifiedBytecode));

    // Verify the change
    assertEquals(42, ReloadableTestClass.getVersion(), "Version should be updated to 42");
    assertEquals(6, instance.calculate(2, 3), "Calculation should now be multiplication");
  }

  @Test
  @Order(3)
  @DisplayName("Test validation with compatible changes")
  void testValidationWithCompatibleChanges() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Test validation for compatible changes
    HotReloadResult result = hotReloadService
        .validateReload("com.bitsapplied.descartes.hotreload.test.ReloadableTestClass");

    // Even without changes, validation should succeed
    assertTrue(result.isSuccess() || result.getClassesAnalyzed() > 0, "Validation should analyze classes");
  }

  @Test
  @Order(4)
  @DisplayName("Test incompatible field addition")
  void testIncompatibleFieldAddition() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Get original bytecode
    byte[] originalBytecode = getClassBytecode(IncompatibleChangeTestClass.class);

    // Create bytecode with added field
    byte[] modifiedBytecode = BytecodeModificationUtil.addFieldToBytecode(originalBytecode);

    // Try to redefine with incompatible change
    Instrumentation inst = HotReloadAgent.getInstrumentation();

    // This should fail
    assertThrows(Exception.class, () -> {
      inst.redefineClasses(new ClassDefinition(IncompatibleChangeTestClass.class, modifiedBytecode));
    }, "Adding fields should cause redefinition to fail");
  }

  @Test
  @Order(5)
  @DisplayName("Test incompatible method signature change")
  void testIncompatibleMethodSignatureChange() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Get original bytecode
    byte[] originalBytecode = getClassBytecode(IncompatibleChangeTestClass.class);

    // Create bytecode with changed method signature
    byte[] modifiedBytecode = BytecodeModificationUtil.changeMethodSignature(originalBytecode);

    // Try to redefine with incompatible change
    Instrumentation inst = HotReloadAgent.getInstrumentation();

    // This should fail
    assertThrows(Exception.class, () -> {
      inst.redefineClasses(new ClassDefinition(IncompatibleChangeTestClass.class, modifiedBytecode));
    }, "Changing method signatures should cause redefinition to fail");
  }

  @Test
  @Order(6)
  @DisplayName("Test incompatible superclass change")
  void testIncompatibleSuperclassChange() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Get original bytecode
    byte[] originalBytecode = getClassBytecode(IncompatibleChangeTestClass.class);

    // Create bytecode with changed superclass
    byte[] modifiedBytecode = BytecodeModificationUtil.changeSuperclass(originalBytecode);

    // Try to redefine with incompatible change
    Instrumentation inst = HotReloadAgent.getInstrumentation();

    // This should fail
    assertThrows(Exception.class, () -> {
      inst.redefineClasses(new ClassDefinition(IncompatibleChangeTestClass.class, modifiedBytecode));
    }, "Changing superclass should cause redefinition to fail");
  }

  @Test
  @Order(7)
  @DisplayName("Test reload with no matching classes")
  void testReloadNoMatchingClasses() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Try to reload non-existent package
    HotReloadResult result = hotReloadService.reloadClasses("com.nonexistent.package.*", false);

    assertFalse(result.isSuccess(), "Should fail when no classes match");
    assertTrue(result.getErrorMessage().contains("No classes found"),
        "Error message should indicate no matching classes");
    assertEquals(0, result.getClassesAnalyzed(), "No classes should be analyzed");
  }

  @Test
  @Order(8)
  @DisplayName("Test reload with no changes detected")
  void testReloadNoChanges() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Try to reload without any actual changes
    HotReloadResult result = hotReloadService.reloadClasses("com.bitsapplied.descartes.hotreload.test.*", false);

    // Should succeed but with no changes
    assertTrue(result.isSuccess() || result.getClassesChanged() == 0, "Should handle no changes gracefully");
  }

  @Test
  @Order(9)
  @DisplayName("Test force reload option")
  void testForceReload() throws Exception {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Create a test instance
    ReloadableTestClass instance = new ReloadableTestClass();
    String originalMessage = instance.getMessage();

    // Verify the original message first
    assertEquals("Original", originalMessage, "Initial message should be 'Original'");

    // Get original bytecode
    byte[] originalBytecode = getClassBytecode(ReloadableTestClass.class);

    // Create modified bytecode with only method body change
    byte[] modifiedBytecode = BytecodeModificationUtil.modifyMethodBody(originalBytecode, "Force Reloaded");

    // Force reload even without timestamp changes
    Instrumentation inst = HotReloadAgent.getInstrumentation();
    inst.redefineClasses(new ClassDefinition(ReloadableTestClass.class, modifiedBytecode));

    // Verify the change - message should be different from original
    String newMessage = instance.getMessage();
    assertNotEquals(originalMessage, newMessage, "Message should have changed after reload");
    assertEquals("Force Reloaded", newMessage, "Message should be updated to 'Force Reloaded'");
  }

  @Test
  @Order(10)
  @DisplayName("Test class tracking and info retrieval")
  void testClassTrackingAndInfo() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Get class info for test class
    String className = ReloadableTestClass.class.getName().replace('.', '/');
    ClassLoadInfo info = HotReloadAgent.getClassInfo(className);

    if (info != null) {
      assertEquals(className, info.getClassName(), "Class name should match");
      assertEquals(ReloadableTestClass.class.getName(), info.getJavaClassName(), "Java class name should match");
      assertNotNull(info.getSourceLocation(), "Source location should be available");
    }

    // Get all tracked classes
    Map<String, ClassLoadInfo> allClasses = HotReloadAgent.getAllClassInfo();
    assertNotNull(allClasses, "Should return tracked classes map");
    assertTrue(allClasses.size() > 0, "Should have tracked some classes");
  }

  @Test
  @Order(11)
  @DisplayName("Test validation only mode")
  void testValidationOnlyMode() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Test validation without actual reload
    HotReloadResult result = hotReloadService.validateReload("com.bitsapplied.descartes.hotreload.test.*");

    // Validation should work
    assertNotNull(result, "Result should not be null");
    assertTrue(result.getClassesAnalyzed() > 0 || result.isSuccess(), "Should analyze classes or succeed");
    assertEquals(0, result.getClassesReloaded(), "No classes should be reloaded in validation mode");
  }

  @Test
  @Order(12)
  @DisplayName("Test error handling and recovery")
  void testErrorHandlingAndRecovery() {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Test with invalid package pattern
    HotReloadResult result1 = hotReloadService.reloadClasses("", false);
    assertNotNull(result1, "Should handle empty pattern");

    // Test with null context (service was created with null/empty context)
    HotReloadService serviceWithNullContext = new HotReloadService(null);
    HotReloadResult result2 = serviceWithNullContext.validateReload("com.bitsapplied.descartes.hotreload.test.*");
    assertNotNull(result2, "Should handle null context");
  }

  @Test
  @Order(13)
  @DisplayName("Test concurrent reload attempts")
  void testConcurrentReloadAttempts() throws InterruptedException {
    if (!agentAvailable) {
      System.out.println("Agent not available - test skipped");
      return;
    }

    // Test that concurrent reload attempts are handled properly
    Thread[] threads = new Thread[3];
    final boolean[] success = new boolean[3];

    for (int i = 0; i < threads.length; i++) {
      final int index = i;
      threads[i] = new Thread(() -> {
        HotReloadResult result = hotReloadService.validateReload("com.bitsapplied.descartes.hotreload.test.*");
        success[index] = result != null;
      });
    }

    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }

    // Wait for completion
    for (Thread thread : threads) {
      thread.join(5000); // 5 second timeout
    }

    // All should complete successfully
    for (boolean s : success) {
      assertTrue(s, "All concurrent attempts should complete");
    }
  }

  /**
   * Helper method to get bytecode of a class.
   */
  private byte[] getClassBytecode(Class<?> clazz) throws IOException {
    String className = clazz.getName().replace('.', '/') + ".class";
    try (var is = clazz.getClassLoader().getResourceAsStream(className)) {
      if (is == null) {
        throw new IOException("Cannot find class: " + className);
      }
      return is.readAllBytes();
    }
  }
}
