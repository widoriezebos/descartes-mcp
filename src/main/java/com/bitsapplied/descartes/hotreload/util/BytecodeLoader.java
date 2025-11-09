package com.bitsapplied.descartes.hotreload.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility methods for loading class bytecode from a variety of sources.
 *
 * <p>
 * The loader understands classes that originate from exploded directories,
 * standard JAR files, nested JARs (e.g. Spring Boot fat JARs), and will fall
 * back to the class loader hierarchy when the protection domain location is not
 * available.
 * </p>
 */
public final class BytecodeLoader {

  private static final Logger LOGGER = Logger.getLogger(BytecodeLoader.class.getName());

  private BytecodeLoader() {
  }

  /**
   * Load bytecode for a class using the most precise information available.
   *
   * @param binaryClassName Class name using '/' separators (e.g.
   *                        com/example/MyClass)
   * @param sourceLocation  Location reported by the protection domain (may be
   *                        null)
   * @param classLoader     The class loader that defined the class (may be null
   *                        for bootstrap)
   * @return Byte array containing the class definition, or {@code null} if it
   *         cannot be located
   * @throws IOException if an IO error occurs while reading the class bytes
   */
  public static byte[] loadClassBytes(String binaryClassName, URL sourceLocation, ClassLoader classLoader)
      throws IOException {
    byte[] fromLocation = loadFromLocation(binaryClassName, sourceLocation);
    if (fromLocation != null) {
      return fromLocation;
    }

    String resourcePath = toClassResource(binaryClassName);
    byte[] fromLoader = loadFromClassLoaderHierarchy(classLoader, resourcePath);
    if (fromLoader != null) {
      return fromLoader;
    }

    // Final fallback: use context and system class loaders
    ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    if (contextLoader != null && contextLoader != classLoader) {
      byte[] fromContext = loadFromClassLoaderHierarchy(contextLoader, resourcePath);
      if (fromContext != null) {
        return fromContext;
      }
    }

    return loadFromSystemClassLoader(resourcePath);
  }

  private static byte[] loadFromLocation(String binaryClassName, URL sourceLocation) throws IOException {
    if (sourceLocation == null) {
      return null;
    }

    String protocol = sourceLocation.getProtocol();
    if ("file".equals(protocol)) {
      return loadFromFileLocation(binaryClassName, sourceLocation);
    } else if ("jar".equals(protocol)) {
      return loadFromJarLocation(binaryClassName, sourceLocation);
    } else {
      return loadFromCustomProtocol(binaryClassName, sourceLocation);
    }
  }

  private static byte[] loadFromFileLocation(String binaryClassName, URL sourceLocation) throws IOException {
    try {
      File sourceFile = new File(sourceLocation.toURI());
      if (!sourceFile.exists()) {
        LOGGER.log(Level.FINE, () -> "Source file does not exist for " + binaryClassName + ": " + sourceFile);
        return null;
      }

      if (sourceFile.isDirectory()) {
        URI baseUri = sourceLocation.toURI();
        URI classUri = baseUri.resolve(toClassResource(binaryClassName));
        URL classUrl = classUri.toURL();
        try (InputStream is = classUrl.openStream()) {
          return readAllBytes(is);
        }
      }

      if (sourceFile.isFile()) {
        return loadFromJarFile(sourceFile, toClassResource(binaryClassName));
      }
    } catch (URISyntaxException e) {
      throw new IOException("Invalid source location for " + binaryClassName + ": " + sourceLocation, e);
    }

    return null;
  }

  private static byte[] loadFromJarLocation(String binaryClassName, URL sourceLocation) throws IOException {
    URLConnection connection = sourceLocation.openConnection();
    if (connection instanceof JarURLConnection jarConnection) {
      String entryPrefix = jarConnection.getEntryName();
      String classEntry = toClassResource(binaryClassName);
      if (entryPrefix != null && !entryPrefix.isEmpty()) {
        String normalizedPrefix = entryPrefix.endsWith("/") ? entryPrefix : entryPrefix + "/";
        classEntry = normalizedPrefix + classEntry;
      }

      try (JarFile jarFile = jarConnection.getJarFile()) {
        JarEntry entry = jarFile.getJarEntry(classEntry);
        if (entry != null) {
          try (InputStream is = jarFile.getInputStream(entry)) {
            return readAllBytes(is);
          }
        }
      }
    }
    return null;
  }

  private static byte[] loadFromCustomProtocol(String binaryClassName, URL sourceLocation) throws IOException {
    String locationStr = sourceLocation.toString();
    String classResource = toClassResource(binaryClassName);

    String classUrl;
    if (locationStr.endsWith("!/")) {
      classUrl = locationStr + classResource;
    } else if (locationStr.contains("!/")) {
      classUrl = locationStr + "/" + classResource;
    } else {
      classUrl = locationStr + "!/" + classResource;
    }

    try {
      URL url = URI.create(classUrl).toURL();
      try (InputStream is = url.openStream()) {
        return readAllBytes(is);
      }
    } catch (IOException e) {
      LOGGER.log(Level.FINE,
          () -> "Unable to load class " + binaryClassName + " via custom protocol: " + e.getMessage());
      return null;
    }
  }

  private static byte[] loadFromJarFile(File jarFile, String classFile) throws IOException {
    try (JarFile jar = new JarFile(jarFile)) {
      JarEntry entry = jar.getJarEntry(classFile);
      if (entry != null) {
        try (InputStream is = jar.getInputStream(entry)) {
          return readAllBytes(is);
        }
      }
    }
    return null;
  }

  private static byte[] loadFromClassLoaderHierarchy(ClassLoader loader, String resourcePath) throws IOException {
    ClassLoader current = loader;
    while (current != null) {
      InputStream is = current.getResourceAsStream(resourcePath);
      if (is != null) {
        try (InputStream autoClose = is) {
          return readAllBytes(autoClose);
        }
      }
      current = current.getParent();
    }
    return null;
  }

  private static byte[] loadFromSystemClassLoader(String resourcePath) throws IOException {
    InputStream is = ClassLoader.getSystemResourceAsStream(resourcePath);
    if (is != null) {
      try (InputStream autoClose = is) {
        return readAllBytes(autoClose);
      }
    }
    return null;
  }

  private static String toClassResource(String binaryClassName) {
    String name = binaryClassName.replace('.', '/');
    return name.endsWith(".class") ? name : name + ".class";
  }

  private static byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = is.read(buffer)) != -1) {
      baos.write(buffer, 0, bytesRead);
    }
    return baos.toByteArray();
  }
}
