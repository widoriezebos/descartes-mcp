package com.bitsapplied.descartes.hotreload;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.hotreload.agent.ClassLoadInfo;
import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;
import com.bitsapplied.descartes.hotreload.analyzer.ClassStructure;
import com.bitsapplied.descartes.hotreload.analyzer.ClassStructureAnalyzer;
import com.bitsapplied.descartes.hotreload.analyzer.ValidationResult;

/**
 * Service for hot reloading Java classes at runtime. Manages the entire reload
 * process including change detection, validation, and class redefinition.
 *
 * <p>
 * <b>IMPORTANT LIMITATION - No Transaction Rollback:</b> The JVM's
 * {@code Instrumentation.redefineClasses()} operation is NOT transactional. If
 * redefinition fails partway through processing multiple classes, some classes
 * will have new bytecode while others retain old bytecode, leaving the
 * application in an inconsistent state. There is no rollback mechanism
 * available at the JVM level.
 *
 * <p>
 * <b>Risk Mitigation Strategies:</b>
 * <ul>
 * <li>Always test reloads in a development environment first</li>
 * <li>Use {@link #validateReload(String)} before attempting actual reload</li>
 * <li>Be prepared to restart the application if reload fails</li>
 * <li>Consider reloading classes one at a time for critical applications</li>
 * <li>Monitor application state after reload to detect inconsistencies</li>
 * </ul>
 *
 * @author Descartes MCP
 */
public class HotReloadService {

  private static final Logger LOGGER = Logger.getLogger(HotReloadService.class.getName());

  private final ClassStructureAnalyzer structureAnalyzer;

  public HotReloadService(Map<String, Object> context) {
    // Context parameter kept for future extensibility but not currently used
    this.structureAnalyzer = new ClassStructureAnalyzer();
  }

  /**
   * Reload classes matching the given package filter.
   * 
   * @param packageFilter Package filter pattern (e.g., "com.example.*")
   * @param force         Force reload even if no changes detected
   * @return Result of the reload operation
   */
  public HotReloadResult reloadClasses(String packageFilter, boolean force) {
    long startTime = System.currentTimeMillis();

    try {
      // Validate input
      if (packageFilter == null || packageFilter.trim().isEmpty()) {
        return HotReloadResult.failed("Package filter is required");
      }

      // Step 1: Find classes matching the filter
      List<ClassLoadInfo> candidateClasses = findMatchingClasses(packageFilter);

      if (candidateClasses.isEmpty()) {
        return HotReloadResult.noMatches(packageFilter);
      }

      // Step 2: Detect changes
      Map<ClassLoadInfo, byte[]> changedClasses = detectChanges(candidateClasses, force);

      if (changedClasses.isEmpty()) {
        return HotReloadResult.noChanges(candidateClasses.size());
      }

      // Step 3: Validate redefinition safety
      ValidationResult validation = validateRedefinition(changedClasses);
      if (!validation.isValid()) {
        return HotReloadResult.validationFailed(candidateClasses.size(), changedClasses.size(), validation.getErrors());
      }

      // Step 4: Perform redefinition
      return performRedefinition(changedClasses, candidateClasses.size(), startTime);

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Hot reload failed", e);
      return HotReloadResult.failed("Unexpected error: " + e.getMessage());
    }
  }

  /**
   * Validate if classes can be reloaded without actually reloading them.
   * 
   * @param packageFilter Package filter pattern
   * @return Validation result
   */
  public HotReloadResult validateReload(String packageFilter) {
    try {
      // Validate input
      if (packageFilter == null || packageFilter.trim().isEmpty()) {
        return HotReloadResult.failed("Package filter is required");
      }

      List<ClassLoadInfo> candidateClasses = findMatchingClasses(packageFilter);

      if (candidateClasses.isEmpty()) {
        return HotReloadResult.noMatches(packageFilter);
      }

      Map<ClassLoadInfo, byte[]> changedClasses = detectChanges(candidateClasses, false);

      if (changedClasses.isEmpty()) {
        return HotReloadResult.validationSuccess(candidateClasses.size(), 0);
      }

      ValidationResult validation = validateRedefinition(changedClasses);

      if (validation.isValid()) {
        return HotReloadResult.validationSuccess(candidateClasses.size(), changedClasses.size());
      } else {
        return HotReloadResult.validationFailed(candidateClasses.size(), changedClasses.size(), validation.getErrors());
      }

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Validation failed", e);
      return HotReloadResult.failed("Validation error: " + e.getMessage());
    }
  }

  /**
   * Find classes matching the package filter.
   * 
   * @param packageFilter Package filter pattern
   * @return List of matching classes
   */
  private List<ClassLoadInfo> findMatchingClasses(String packageFilter) {
    String prefix = packageFilter.replace(".", "/").replace("*", "");

    return HotReloadAgent.getAllClassInfo().values().stream().filter(info -> info.getClassName().startsWith(prefix))
        .collect(Collectors.toList());
  }

  /**
   * Detect which classes have changed.
   * 
   * @param candidateClasses Classes to check
   * @param force            Force detection even if timestamps haven't changed
   * @return Map of changed classes to their new bytecode
   */
  private Map<ClassLoadInfo, byte[]> detectChanges(List<ClassLoadInfo> candidateClasses, boolean force) {
    Map<ClassLoadInfo, byte[]> changedClasses = new LinkedHashMap<>();

    for (ClassLoadInfo classInfo : candidateClasses) {
      try {
        // Check if source has been modified or force reload
        if (force || classInfo.isSourceModified()) {
          byte[] newBytecode = loadBytecode(classInfo);

          if (newBytecode != null && (force || classInfo.hasBytecodeChanged(newBytecode))) {
            changedClasses.put(classInfo, newBytecode);
            LOGGER.fine("Detected change in class: " + classInfo.getClassName());
          }
        }
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to check changes for class: " + classInfo.getClassName(), e);
      }
    }

    return changedClasses;
  }

  /**
   * Load bytecode from a class's source location.
   * <p>
   * This method handles multiple URL protocols that can appear in
   * CodeSource.getLocation():
   * <ul>
   * <li><b>file:</b> - Can be either an exploded directory or a JAR file. Uses
   * File.isDirectory() to distinguish between them.</li>
   * <li><b>jar:</b> - Standard JAR protocol (jar:file:/path/to/file.jar!/)</li>
   * <li><b>jar:nested:</b> - Spring Boot fat JAR protocol</li>
   * <li><b>Other protocols:</b> - Custom classloaders may use other schemes</li>
   * </ul>
   *
   * @param classInfo Class information
   * @return Bytecode or null if not found
   */
  private byte[] loadBytecode(ClassLoadInfo classInfo) throws IOException {
    URL location = classInfo.getSourceLocation();
    if (location == null) {
      LOGGER.fine("No source location for class: " + classInfo.getClassName());
      return null;
    }

    String className = classInfo.getClassName();
    String classFile = className + ".class";
    String protocol = location.getProtocol();

    LOGGER.fine("Loading bytecode for " + className + " from " + protocol + " URL: " + location);

    if ("file".equals(protocol)) {
      try {
        File sourceFile = new File(location.toURI());

        if (sourceFile.isDirectory()) {
          // Load from exploded directory
          LOGGER.fine("Loading from directory: " + sourceFile);
          URI baseUri = location.toURI();
          URI classUri = baseUri.resolve(classFile);
          URL classUrl = classUri.toURL();
          return readBytecode(classUrl);
        } else if (sourceFile.isFile()) {
          // Load from JAR file (file: protocol pointing to .jar)
          LOGGER.fine("Detected JAR file with file: protocol: " + sourceFile);
          return loadFromJarFile(sourceFile, classFile);
        } else {
          LOGGER.warning("Source file does not exist or is neither file nor directory: " + sourceFile);
          return null;
        }
      } catch (URISyntaxException e) {
        throw new IOException("Invalid URL for class location: " + location, e);
      }
    } else if ("jar".equals(protocol)) {
      // Standard jar: protocol (jar:file:/path/to/file.jar!/)
      LOGGER.fine("Loading from jar: protocol URL");
      return loadFromJar(location, classFile);
    } else {
      // Try custom protocol handler (e.g., jar:nested: for Spring Boot)
      LOGGER.fine("Attempting custom protocol handler for: " + protocol);
      return loadFromCustomProtocol(location, classFile);
    }
  }

  /**
   * Load bytecode from a JAR file.
   *
   * @param jarUrl    JAR URL
   * @param classFile Class file path
   * @return Bytecode or null if not found
   */
  private byte[] loadFromJar(URL jarUrl, String classFile) throws IOException {
    URLConnection connection = jarUrl.openConnection();
    if (connection instanceof JarURLConnection) {
      JarURLConnection jarConnection = (JarURLConnection) connection;
      try (JarFile jarFile = jarConnection.getJarFile()) {
        JarEntry entry = jarFile.getJarEntry(classFile);
        if (entry != null) {
          try (InputStream is = jarFile.getInputStream(entry)) {
            return readAllBytes(is);
          }
        }
      }
    }
    return null;
  }

  /**
   * Load bytecode from a JAR file using a file: protocol URL.
   * <p>
   * This method handles the common case where CodeSource.getLocation() returns a
   * file: URL pointing to a JAR file (e.g., file:/path/to/library.jar) rather
   * than a jar: protocol URL.
   *
   * @param jarFile   JAR file
   * @param classFile Class file path within the JAR
   * @return Bytecode or null if not found
   */
  private byte[] loadFromJarFile(File jarFile, String classFile) throws IOException {
    LOGGER.fine("Loading class " + classFile + " from JAR file: " + jarFile);
    try (JarFile jar = new JarFile(jarFile)) {
      JarEntry entry = jar.getJarEntry(classFile);
      if (entry != null) {
        try (InputStream is = jar.getInputStream(entry)) {
          return readAllBytes(is);
        }
      } else {
        LOGGER.fine("Class " + classFile + " not found in JAR: " + jarFile);
      }
    }
    return null;
  }

  /**
   * Load bytecode from nested or custom protocol URLs.
   * <p>
   * This method handles special cases like Spring Boot's jar:nested: protocol and
   * other custom URL schemes by attempting to resolve the class file URL and read
   * from it directly.
   *
   * @param baseLocation Base location URL
   * @param classFile    Class file path
   * @return Bytecode or null if not found
   */
  private byte[] loadFromCustomProtocol(URL baseLocation, String classFile) throws IOException {
    String protocol = baseLocation.getProtocol();
    LOGGER.fine("Attempting to load class " + classFile + " from custom protocol: " + protocol);

    try {
      // Try to construct the full URL to the class file
      // For jar:nested: URLs, this might look like:
      // jar:nested:/path/to/app.jar/!BOOT-INF/lib/library.jar!/com/example/MyClass.class
      String locationStr = baseLocation.toString();

      // If the location already ends with !/, just append the class file
      String classUrl;
      if (locationStr.endsWith("!/")) {
        classUrl = locationStr + classFile;
      } else if (locationStr.contains("!/")) {
        // Already has a JAR entry separator, append after it
        classUrl = locationStr + "/" + classFile;
      } else {
        // No separator, add one
        classUrl = locationStr + "!/" + classFile;
      }

      LOGGER.fine("Trying to load from URL: " + classUrl);
      URL url = URI.create(classUrl).toURL();

      try (InputStream is = url.openStream()) {
        return readAllBytes(is);
      }
    } catch (IOException e) {
      LOGGER.fine("Failed to load class " + classFile + " from " + protocol + " URL: " + e.getMessage());
      return null;
    }
  }

  /**
   * Read bytecode from a URL.
   * 
   * @param url URL to read from
   * @return Bytecode
   */
  private byte[] readBytecode(URL url) throws IOException {
    try (InputStream is = url.openStream()) {
      return readAllBytes(is);
    }
  }

  /**
   * Read all bytes from an input stream.
   * 
   * @param is Input stream
   * @return Byte array
   */
  private byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = is.read(buffer)) != -1) {
      baos.write(buffer, 0, bytesRead);
    }
    return baos.toByteArray();
  }

  /**
   * Validate if classes can be safely redefined.
   * 
   * @param changedClasses Map of changed classes to new bytecode
   * @return Validation result
   */
  private ValidationResult validateRedefinition(Map<ClassLoadInfo, byte[]> changedClasses) {
    List<String> errors = new ArrayList<>();

    for (Map.Entry<ClassLoadInfo, byte[]> entry : changedClasses.entrySet()) {
      ClassLoadInfo classInfo = entry.getKey();
      byte[] newBytecode = entry.getValue();

      // Skip if no original bytecode (pre-loaded classes)
      if (classInfo.getOriginalBytecode() == null) {
        LOGGER.warning("No original bytecode for validation: " + classInfo.getClassName());
        continue;
      }

      try {
        // Analyze class structures
        ClassStructure currentStructure = structureAnalyzer.analyzeStructure(
            classInfo.getCurrentBytecode() != null ? classInfo.getCurrentBytecode() : classInfo.getOriginalBytecode());
        ClassStructure newStructure = structureAnalyzer.analyzeStructure(newBytecode);

        // Check compatibility
        List<String> incompatibilities = currentStructure.getIncompatibilities(newStructure);
        if (!incompatibilities.isEmpty()) {
          errors.add(String.format("Class %s has incompatible changes: %s", classInfo.getJavaClassName(),
              String.join(", ", incompatibilities)));
        }

      } catch (Exception e) {
        errors.add(String.format("Failed to validate %s: %s", classInfo.getJavaClassName(), e.getMessage()));
      }
    }

    return new ValidationResult(errors);
  }

  /**
   * Perform the actual class redefinition.
   * 
   * @param changedClasses Map of changed classes to new bytecode
   * @param totalAnalyzed  Total number of classes analyzed
   * @param startTime      Start time of the operation
   * @return Result of the redefinition
   */
  private HotReloadResult performRedefinition(Map<ClassLoadInfo, byte[]> changedClasses, int totalAnalyzed,
      long startTime) {
    Instrumentation inst = HotReloadAgent.getInstrumentation();
    if (inst == null) {
      return HotReloadResult.failed("Instrumentation not available");
    }

    List<ClassDefinition> definitions = new ArrayList<>();
    List<String> reloadedClassNames = new ArrayList<>();
    Map<String, String> skippedClasses = new LinkedHashMap<>();

    for (Map.Entry<ClassLoadInfo, byte[]> entry : changedClasses.entrySet()) {
      ClassLoadInfo classInfo = entry.getKey();
      byte[] newBytecode = entry.getValue();

      try {
        // Load the class using the original class loader when available
        ClassLoader loader = classInfo.getClassLoader();
        Class<?> clazz = loader != null ? Class.forName(classInfo.getJavaClassName(), false, loader)
            : Class.forName(classInfo.getJavaClassName());

        // Check if class can be redefined
        if (!inst.isModifiableClass(clazz)) {
          skippedClasses.put(classInfo.getJavaClassName(), "Class is not modifiable");
          continue;
        }

        definitions.add(new ClassDefinition(clazz, newBytecode));
        reloadedClassNames.add(classInfo.getJavaClassName());

        // Update the stored bytecode
        classInfo.updateBytecode(newBytecode);

      } catch (ClassNotFoundException e) {
        skippedClasses.put(classInfo.getJavaClassName(), "Class not found in current classloader");
      } catch (Exception e) {
        skippedClasses.put(classInfo.getJavaClassName(), "Error: " + e.getMessage());
      }
    }

    if (definitions.isEmpty()) {
      return HotReloadResult.failed("No classes could be prepared for redefinition", totalAnalyzed,
          changedClasses.size(), skippedClasses);
    }

    try {
      // Perform the redefinition
      inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));

      long elapsed = System.currentTimeMillis() - startTime;

      LOGGER.info(String.format("Successfully reloaded %d classes in %d ms", definitions.size(), elapsed));

      return HotReloadResult.success(totalAnalyzed, changedClasses.size(), definitions.size(), reloadedClassNames,
          skippedClasses, elapsed);

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Class redefinition failed", e);
      return HotReloadResult.failed("Redefinition failed: " + e.getMessage(), totalAnalyzed, changedClasses.size(),
          skippedClasses);
    }
  }
}
