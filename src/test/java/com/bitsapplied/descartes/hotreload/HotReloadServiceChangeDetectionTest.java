package com.bitsapplied.descartes.hotreload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.bitsapplied.descartes.hotreload.agent.ClassLoadInfo;

class HotReloadServiceChangeDetectionTest {

  @TempDir
  Path tempDir;

  private HotReloadService service;
  private Method detectChangesMethod;

  @BeforeEach
  void setUp() throws Exception {
    service = new HotReloadService(Map.of());
    detectChangesMethod = HotReloadService.class.getDeclaredMethod("detectChanges", List.class, boolean.class);
    detectChangesMethod.setAccessible(true);
  }

  @Test
  @DisplayName("No reload when timestamp reliable and bytecode unchanged")
  void noChangeWithReliableTimestamp() throws Exception {
    String className = "com/example/Stable";
    byte[] classBytes = createClassBytes(className, "stable");

    Path classesDir = tempDir.resolve("classes");
    writeClassFile(classesDir, className, classBytes);

    try (URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() })) {
      ClassLoadInfo info = new ClassLoadInfo(className, classesDir.toUri().toURL(), classBytes, loader);

      Map<ClassLoadInfo, ?> result = invokeDetectChanges(List.of(info), false);
      assertTrue(result.isEmpty(), "No changes should be detected for identical bytecode");
    }
  }

  @Test
  @DisplayName("Detects change when timestamp reliable and bytecode updated")
  void detectsChangeWithReliableTimestamp() throws Exception {
    String className = "com/example/Changed";
    byte[] originalBytes = createClassBytes(className, "v1");
    byte[] updatedBytes = createClassBytes(className, "v2");

    Path classesDir = tempDir.resolve("classes-change");
    writeClassFile(classesDir, className, originalBytes);

    try (URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() })) {
      ClassLoadInfo info = new ClassLoadInfo(className, classesDir.toUri().toURL(), originalBytes, loader);

      Path classFile = classesDir.resolve(className + ".class");
      Files.write(classFile, updatedBytes);
      long updatedTimestamp = info.getLastModified() + Duration.ofSeconds(5).toMillis();
      Files.setLastModifiedTime(classesDir, FileTime.fromMillis(updatedTimestamp));

      Map<ClassLoadInfo, ?> result = invokeDetectChanges(List.of(info), false);
      assertEquals(1, result.size(), "Updated bytecode should trigger reload detection");
    }
  }

  @Test
  @DisplayName("Marks inspected when timestamp updated but bytecode unchanged")
  void timestampUpdateWithoutContentChange() throws Exception {
    String className = "com/example/TimestampOnly";
    byte[] classBytes = createClassBytes(className, "same");

    Path classesDir = tempDir.resolve("classes-timestamp");
    writeClassFile(classesDir, className, classBytes);

    try (URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() })) {
      ClassLoadInfo info = new ClassLoadInfo(className, classesDir.toUri().toURL(), classBytes, loader);

      long originalTimestamp = info.getLastModified();
      long newTimestamp = originalTimestamp + Duration.ofSeconds(5).toMillis();
      Files.setLastModifiedTime(classesDir, FileTime.fromMillis(newTimestamp));

      Map<ClassLoadInfo, ?> result = invokeDetectChanges(List.of(info), false);
      assertTrue(result.isEmpty(), "Byte-identical updates should not trigger reload");
      assertEquals(newTimestamp, info.getLastModified(),
          "Class load info should record updated timestamp after inspection");
    }
  }

  @Test
  @DisplayName("Content comparison used when timestamp unreliable")
  void contentComparisonWhenTimestampUnreliable() throws Exception {
    String className = "com/example/Unreliable";
    byte[] originalBytes = createClassBytes(className, "v1");
    byte[] updatedBytes = createClassBytes(className, "v2");

    MapBackedClassLoader loader = new MapBackedClassLoader();
    loader.updateResource(className + ".class", originalBytes);

    ClassLoadInfo info = new ClassLoadInfo(className, null, originalBytes, loader);

    Map<ClassLoadInfo, ?> initial = invokeDetectChanges(List.of(info), false);
    assertTrue(initial.isEmpty(), "Identical content should not trigger reload even without timestamp");
    assertEquals(0, info.getLastModified(), "Last modified remains zero without timestamp data");

    loader.updateResource(className + ".class", updatedBytes);
    Map<ClassLoadInfo, ?> result = invokeDetectChanges(List.of(info), false);
    assertEquals(1, result.size(), "Content change should be detected without reliable timestamps");
  }

  @Test
  @DisplayName("Treat classes without baseline bytecode as changed")
  void classesWithoutBaselineAreReloaded() throws Exception {
    String className = "com/example/NoBaseline";
    byte[] classBytes = createClassBytes(className, "baseline");

    MapBackedClassLoader loader = new MapBackedClassLoader();
    loader.updateResource(className + ".class", classBytes);

    ClassLoadInfo info = new ClassLoadInfo(className, null, null, loader);
    assertFalse(info.hasTrackedBytecode(), "Baseline bytecode should be absent");

    Map<ClassLoadInfo, ?> result = invokeDetectChanges(List.of(info), false);
    assertEquals(1, result.size(), "Classes without baseline bytecode should be scheduled for reload");
  }

  private Map<ClassLoadInfo, ?> invokeDetectChanges(List<ClassLoadInfo> classes, boolean force)
      throws InvocationTargetException, IllegalAccessException {
    @SuppressWarnings("unchecked")
    Map<ClassLoadInfo, ?> result = (Map<ClassLoadInfo, ?>) detectChangesMethod.invoke(service, classes, force);
    return result;
  }

  private void writeClassFile(Path rootDir, String className, byte[] classBytes) throws Exception {
    Path classPath = rootDir.resolve(className + ".class");
    Files.createDirectories(classPath.getParent());
    Files.write(classPath, classBytes);
  }

  private byte[] createClassBytes(String binaryName, String message) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName, null, "java/lang/Object", null);

    MethodVisitor constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    constructor.visitCode();
    constructor.visitVarInsn(Opcodes.ALOAD, 0);
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    constructor.visitInsn(Opcodes.RETURN);
    constructor.visitMaxs(0, 0);
    constructor.visitEnd();

    MethodVisitor method = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "message", "()Ljava/lang/String;",
        null, null);
    method.visitCode();
    method.visitLdcInsn(message);
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();

    cw.visitEnd();
    return cw.toByteArray();
  }

  private static final class MapBackedClassLoader extends ClassLoader {

    private byte[] resource;
    private String resourceName;

    private MapBackedClassLoader() {
      super(null);
    }

    void updateResource(String name, byte[] bytes) {
      this.resourceName = name;
      this.resource = bytes;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      if (resource != null && name.equals(resourceName)) {
        return new ByteArrayInputStream(resource);
      }
      return super.getResourceAsStream(name);
    }
  }
}
