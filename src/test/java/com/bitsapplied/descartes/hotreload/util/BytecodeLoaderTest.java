package com.bitsapplied.descartes.hotreload.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class BytecodeLoaderTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("Load class bytes from exploded directory")
  void loadFromDirectory() throws Exception {
    String className = "com/example/DirLoaded";
    byte[] classBytes = generateSimpleClass(className, "DIR");

    Path classesDir = tempDir.resolve("classes");
    Path classFile = writeClassFile(classesDir, className, classBytes);

    try (URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() })) {
      byte[] loaded = BytecodeLoader.loadClassBytes(className, classesDir.toUri().toURL(), loader);
      assertNotNull(loaded, "Bytecode should be resolved from directory");
      assertArrayEquals(classBytes, loaded);
    }

    Files.deleteIfExists(classFile);
  }

  @Test
  @DisplayName("Load class bytes from JAR using file: protocol")
  void loadFromJarFileProtocol() throws Exception {
    String className = "com/example/JarLoaded";
    byte[] classBytes = generateSimpleClass(className, "JAR");

    File jarFile = createJar("file-protocol.jar", className + ".class", classBytes);
    URL jarUrl = jarFile.toURI().toURL();

    try (URLClassLoader loader = new URLClassLoader(new URL[] { jarUrl })) {
      byte[] loaded = BytecodeLoader.loadClassBytes(className, jarUrl, loader);
      assertNotNull(loaded, "Bytecode should be resolved from jar file via file protocol");
      assertArrayEquals(classBytes, loaded);
    }
  }

  @Test
  @DisplayName("Load class bytes from JAR using jar: protocol")
  void loadFromJarProtocol() throws Exception {
    String className = "com/example/JarProtocolLoaded";
    byte[] classBytes = generateSimpleClass(className, "JAR_PROTO");

    File jarFile = createJar("jar-protocol.jar", className + ".class", classBytes);
    URI jarUri = jarFile.toURI();
    URI jarRootUri = new URI("jar", jarUri.toASCIIString() + "!/", null);
    URL jarRoot = jarRootUri.toURL();

    byte[] loaded = BytecodeLoader.loadClassBytes(className, jarRoot, null);
    assertNotNull(loaded, "Bytecode should be resolved from jar protocol URL");
    assertArrayEquals(classBytes, loaded);
  }

  @Test
  @DisplayName("Load class bytes from nested jar-style location")
  void loadFromNestedJarLikeLocation() throws Exception {
    String className = "com/example/NestedLoaded";
    byte[] classBytes = generateSimpleClass(className, "NESTED");

    String entryName = "BOOT-INF/classes/" + className + ".class";
    File jarFile = createJarWithDirectories("nested.jar", entryName, classBytes);

    URI jarUri = jarFile.toURI();
    URI nestedRootUri = new URI("jar", jarUri.toASCIIString() + "!/BOOT-INF/classes/", null);
    URL nestedRoot = nestedRootUri.toURL();
    byte[] loaded = BytecodeLoader.loadClassBytes(className, nestedRoot, null);
    assertNotNull(loaded, "Bytecode should be resolved from nested jar-style URL");
    assertArrayEquals(classBytes, loaded);
  }

  @Test
  @DisplayName("Fallback to class loader when location is null")
  void loadViaClassLoaderFallback() throws Exception {
    String className = "com/example/LoaderFallback";
    byte[] classBytes = generateSimpleClass(className, "LOADER");

    Map<String, byte[]> resources = new HashMap<>();
    resources.put(className + ".class", classBytes);
    ClassLoader loader = new MapBackedClassLoader(resources);

    byte[] loaded = BytecodeLoader.loadClassBytes(className, null, loader);
    assertNotNull(loaded, "Bytecode should be resolved via class loader fallback");
    assertArrayEquals(classBytes, loaded);
  }

  @Test
  @DisplayName("Fallback to context class loader")
  void loadViaContextClassLoaderFallback() throws Exception {
    String className = "com/example/ContextFallback";
    byte[] classBytes = generateSimpleClass(className, "CTX");

    Map<String, byte[]> resources = new HashMap<>();
    resources.put(className + ".class", classBytes);
    ClassLoader loader = new MapBackedClassLoader(resources);

    ClassLoader original = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    try {
      byte[] loaded = BytecodeLoader.loadClassBytes(className, null, null);
      assertNotNull(loaded, "Bytecode should be resolved via context class loader");
      assertArrayEquals(classBytes, loaded);
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @Test
  @DisplayName("Return null when bytecode cannot be located")
  void returnsNullWhenNotFound() throws Exception {
    String className = "com/example/Missing";

    File jarFile = createJar("missing.jar", "com/example/Other.class",
        generateSimpleClass("com/example/Other", "OTHER"));
    URL jarUrl = jarFile.toURI().toURL();

    byte[] loaded = BytecodeLoader.loadClassBytes(className, jarUrl, null);
    assertNull(loaded, "Bytecode should be null when class cannot be located");
  }

  private Path writeClassFile(Path rootDir, String className, byte[] classBytes) throws IOException {
    Path classPath = rootDir.resolve(className + ".class");
    Files.createDirectories(classPath.getParent());
    Files.write(classPath, classBytes);
    return classPath;
  }

  private File createJar(String fileName, String entryName, byte[] classBytes) throws IOException {
    Path jarPath = tempDir.resolve(fileName);
    try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
      JarEntry entry = new JarEntry(entryName);
      jos.putNextEntry(entry);
      jos.write(classBytes);
      jos.closeEntry();
    }
    return jarPath.toFile();
  }

  private File createJarWithDirectories(String fileName, String entryName, byte[] classBytes) throws IOException {
    Path jarPath = tempDir.resolve(fileName);
    try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
      String dir = entryName.substring(0, entryName.lastIndexOf('/') + 1);
      if (!dir.isEmpty()) {
        String[] segments = dir.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
          if (segment.isEmpty()) {
            continue;
          }
          current.append(segment).append("/");
          JarEntry dirEntry = new JarEntry(current.toString());
          dirEntry.setTime(System.currentTimeMillis());
          jos.putNextEntry(dirEntry);
          jos.closeEntry();
        }
      }

      JarEntry classEntry = new JarEntry(entryName);
      classEntry.setTime(System.currentTimeMillis());
      jos.putNextEntry(classEntry);
      jos.write(classBytes);
      jos.closeEntry();
    }
    return jarPath.toFile();
  }

  private byte[] generateSimpleClass(String binaryName, String message) {
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

    private final Map<String, byte[]> resources;

    private MapBackedClassLoader(Map<String, byte[]> resources) {
      super(null);
      this.resources = resources;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      byte[] data = resources.get(name);
      if (data != null) {
        return new ByteArrayInputStream(data);
      }
      return super.getResourceAsStream(name);
    }
  }
}
