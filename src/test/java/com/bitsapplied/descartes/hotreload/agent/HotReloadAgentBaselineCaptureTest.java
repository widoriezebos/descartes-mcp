package com.bitsapplied.descartes.hotreload.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.bitsapplied.descartes.hotreload.util.BytecodeLoader;

class HotReloadAgentBaselineCaptureTest {

  @TempDir
  Path tempDir;

  @AfterEach
  void cleanup() {
    HotReloadAgent.clearCache("com.example.agent.*");
  }

  @Test
  @DisplayName("Record baseline bytecode for already-loaded class from directory")
  void recordBaselineFromDirectory() throws Exception {
    String className = "com.example.agent.DirectoryBaseline";
    byte[] classBytes = generateClassBytes(className, "DIR");

    Path classesDir = tempDir.resolve("classes");
    Path classPath = classesDir.resolve(className.replace('.', '/') + ".class");
    Files.createDirectories(classPath.getParent());
    Files.write(classPath, classBytes);

    try (URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() }, null)) {
      Class<?> clazz = loader.loadClass(className);
      invokeRecordClassInfo(clazz);

      String binaryName = className.replace('.', '/');
      ClassLoadInfo info = HotReloadAgent.getClassInfo(binaryName);
      assertNotNull(info, "Class info should be recorded for preloaded class");
      assertTrue(info.hasTrackedBytecode(), "Captured bytecode should be tracked");

      byte[] expected = BytecodeLoader.loadClassBytes(binaryName, classesDir.toUri().toURL(), loader);
      assertArrayEquals(expected, info.getOriginalBytecode(), "Original bytecode should match class file");
    }
  }

  @Test
  @DisplayName("Record baseline bytecode for already-loaded class from jar")
  void recordBaselineFromJar() throws Exception {
    String className = "com.example.agent.JarBaseline";
    byte[] classBytes = generateClassBytes(className, "JAR");

    Path jarPath = tempDir.resolve("baseline.jar");
    String entryName = className.replace('.', '/') + ".class";
    try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
      JarEntry entry = new JarEntry(entryName);
      jos.putNextEntry(entry);
      jos.write(classBytes);
      jos.closeEntry();
    }

    URL jarUrl = jarPath.toUri().toURL();
    try (URLClassLoader loader = new URLClassLoader(new URL[] { jarUrl }, null)) {
      Class<?> clazz = loader.loadClass(className);
      invokeRecordClassInfo(clazz);

      String binaryName = className.replace('.', '/');
      ClassLoadInfo info = HotReloadAgent.getClassInfo(binaryName);
      assertNotNull(info, "Class info should be recorded for jar-loaded class");
      assertTrue(info.hasTrackedBytecode(), "Captured bytecode should be tracked");

      byte[] expected = BytecodeLoader.loadClassBytes(binaryName, jarUrl, loader);
      assertArrayEquals(expected, info.getOriginalBytecode(), "Original bytecode should match jar entry");
    }
  }

  private void invokeRecordClassInfo(Class<?> clazz) throws Exception {
    Method method = HotReloadAgent.class.getDeclaredMethod("recordClassInfo", Class.class);
    method.setAccessible(true);
    method.invoke(null, clazz);
  }

  private byte[] generateClassBytes(String dottedName, String message) {
    String binaryName = dottedName.replace('.', '/');
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
}
