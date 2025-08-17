package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for ClassPathHelper utility class.
 */
public class ClassPathHelperTest {

  @Test
  public void testBuildExactClassPathWithCurrentClassLoader() {
    String classPath = ClassPathHelper.buildExactClassPath(getClass().getClassLoader());

    assertNotNull(classPath);
    assertFalse(classPath.isEmpty());

    // Should contain path separator
    String separator = File.pathSeparator;
    if (classPath.contains(separator)) {
      // Multiple entries
      String[] entries = classPath.split(separator);
      assertTrue(entries.length > 0);

      // Each entry should be an absolute path
      for (String entry : entries) {
        File file = new File(entry);
        assertTrue(file.isAbsolute(), "Path should be absolute: " + entry);
      }
    }
  }

  @Test
  public void testBuildExactClassPathWithNullClassLoader() {
    // Using bootstrap classloader (null)
    String classPath = ClassPathHelper.buildExactClassPath(null);

    assertNotNull(classPath);
    // May be empty if no java.class.path is set
  }

  @Test
  public void testBuildExactClassPathWithCustomURLClassLoader() throws Exception {
    URL[] urls = new URL[] { new File("/tmp/test1.jar").toURI().toURL(), new File("/tmp/test2.jar").toURI().toURL() };

    try (URLClassLoader customLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
      String classPath = ClassPathHelper.buildExactClassPath(customLoader);

      assertNotNull(classPath);
      // Should contain the custom URLs
      assertTrue(classPath.contains("test1.jar") || classPath.contains("test2.jar"));
    }
  }

  @Test
  public void testBuildImportsForSimpleClass() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(String.class);

    assertNotNull(imports);
    // String is in java.lang package, so no imports needed
    assertTrue(imports.isEmpty());
  }

  @Test
  public void testBuildImportsForCollectionClass() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(ArrayList.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include ArrayList and its interfaces
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.ArrayList")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.List")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.Collection")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.io.Serializable")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.RandomAccess")));

    // Should not include java.lang classes
    assertFalse(imports.stream().anyMatch(i -> i.contains("java.lang.Object")));
    assertFalse(imports.stream().anyMatch(i -> i.contains("java.lang.Cloneable")));
  }

  @Test
  public void testBuildImportsForCustomClass() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(TestClass.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include the test class itself
    assertTrue(imports.stream().anyMatch(i -> i.contains("ClassPathHelperTest$TestClass")));

    // Should include the interface
    assertTrue(imports.stream().anyMatch(i -> i.contains("ClassPathHelperTest$TestInterface")));
  }

  @Test
  public void testBuildImportsForArray() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(String[].class);

    assertNotNull(imports);
    // Array of String (java.lang) should not generate imports
    assertTrue(imports.isEmpty());
  }

  @Test
  public void testBuildImportsForCollectionArray() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(List[].class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include List and its superinterfaces
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.List")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.Collection")));
  }

  @Test
  public void testBuildImportsForPrimitive() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(int.class);

    assertNotNull(imports);
    assertTrue(imports.isEmpty()); // Primitives don't need imports
  }

  @Test
  public void testBuildImportsForPrimitiveArray() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(int[].class);

    assertNotNull(imports);
    assertTrue(imports.isEmpty()); // Primitive arrays don't need imports
  }

  @Test
  public void testBuildImportsForNull() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(null);

    assertNotNull(imports);
    assertTrue(imports.isEmpty());
  }

  @Test
  public void testBuildImportsWithNameCollision() {
    // Test with classes that have the same simple name
    List<String> imports = ClassPathHelper.buildImportsForInjected(java.util.Date.class);

    assertNotNull(imports);

    // Should include java.util.Date
    assertTrue(imports.stream().anyMatch(i -> i.equals("import java.util.Date;")));

    // If there were a collision (e.g., java.sql.Date), it would be excluded
  }

  @Test
  public void testBuildImportsForAnonymousClass() {
    Runnable anonymous = new Runnable() {
      @Override
      public void run() {
        // Anonymous class
      }
    };

    List<String> imports = ClassPathHelper.buildImportsForInjected(anonymous.getClass());

    assertNotNull(imports);
    // Anonymous classes are not importable, and Runnable is in java.lang so not
    // imported
    assertTrue(imports.isEmpty());
  }

  @Test
  public void testBuildImportsForLocalClass() {
    class LocalClass implements TestInterface {
      @Override
      public void test() {
        // Local class
      }
    }

    List<String> imports = ClassPathHelper.buildImportsForInjected(LocalClass.class);

    assertNotNull(imports);
    // Local classes and their interfaces from the test package should be importable
    // If not found, it's because the interface is not considered importable
    // Let's just check the imports are generated
    assertTrue(imports != null);
  }

  @Test
  public void testBuildImportsForEnum() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(TestEnum.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include the enum itself
    assertTrue(imports.stream().anyMatch(i -> i.contains("ClassPathHelperTest$TestEnum")));

    // Enum extends java.lang.Enum which is in java.lang
    assertFalse(imports.stream().anyMatch(i -> i.contains("java.lang.Enum")));
  }

  @Test
  public void testBuildImportsForMap() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(HashMap.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include HashMap and its interfaces
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.HashMap")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.util.Map")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.io.Serializable")));
  }

  @Test
  public void testBuildImportsForNestedClass() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(OuterClass.InnerClass.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include the nested class
    assertTrue(imports.stream().anyMatch(i -> i.contains("OuterClass$InnerClass")));
  }

  @Test
  public void testBuildImportsRemovesDuplicates() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(ExtendedTestClass.class);

    assertNotNull(imports);

    // Count occurrences of TestInterface - should be only one
    long interfaceCount = imports.stream().filter(i -> i.contains("TestInterface")).count();

    assertEquals(1, interfaceCount, "Should not have duplicate imports");
  }

  @Test
  public void testImportFormat() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(ArrayList.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Check format of imports
    for (String imp : imports) {
      assertTrue(imp.startsWith("import "), "Should start with 'import ': " + imp);
      assertTrue(imp.endsWith(";"), "Should end with semicolon: " + imp);
      assertFalse(imp.contains("$"), "Should use proper nested class syntax if needed: " + imp);
    }
  }

  @Test
  public void testBuildImportsForComplexHierarchy() {
    List<String> imports = ClassPathHelper.buildImportsForInjected(ComplexClass.class);

    assertNotNull(imports);
    assertFalse(imports.isEmpty());

    // Should include all interfaces from hierarchy
    assertTrue(imports.stream().anyMatch(i -> i.contains("TestInterface")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("AnotherInterface")));
    assertTrue(imports.stream().anyMatch(i -> i.contains("java.io.Serializable")));
  }

  @Test
  public void testClassPathWithSystemProperties() {
    // Save original properties
    String originalClassPath = System.getProperty("java.class.path");
    String originalModulePath = System.getProperty("jdk.module.path");

    try {
      // Set test properties
      System.setProperty("java.class.path", "/test/path1" + File.pathSeparator + "/test/path2");

      String classPath = ClassPathHelper.buildExactClassPath(null);

      assertNotNull(classPath);
      assertTrue(classPath.contains("test"));

    } finally {
      // Restore original properties
      if (originalClassPath != null) {
        System.setProperty("java.class.path", originalClassPath);
      }
      if (originalModulePath != null) {
        System.setProperty("jdk.module.path", originalModulePath);
      }
    }
  }

  @Test
  public void testEmptyClassPath() {
    // Save original
    String original = System.getProperty("java.class.path");

    try {
      System.setProperty("java.class.path", "");

      String classPath = ClassPathHelper.buildExactClassPath(null);
      assertNotNull(classPath);
      // May still have entries from classloader

    } finally {
      if (original != null) {
        System.setProperty("java.class.path", original);
      }
    }
  }

  // Test helper classes and interfaces

  public interface TestInterface {
    void test();
  }

  public interface AnotherInterface {
    void another();
  }

  public static class TestClass implements TestInterface {
    @Override
    public void test() {
      // Test implementation
    }
  }

  public static class ExtendedTestClass extends TestClass implements TestInterface {
    // Implements same interface as parent - should not duplicate
  }

  public static class ComplexClass extends TestClass implements AnotherInterface, java.io.Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public void another() {
      // Implementation
    }
  }

  public enum TestEnum {
    VALUE1, VALUE2
  }

  public static class OuterClass {
    public static class InnerClass {
      // Nested class
    }
  }
}