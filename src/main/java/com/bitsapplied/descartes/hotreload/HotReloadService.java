package com.bitsapplied.descartes.hotreload;

import java.io.ByteArrayOutputStream;
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
   * 
   * @param classInfo Class information
   * @return Bytecode or null if not found
   */
  private byte[] loadBytecode(ClassLoadInfo classInfo) throws IOException {
    URL location = classInfo.getSourceLocation();
    if (location == null) {
      return null;
    }

    String className = classInfo.getClassName();
    String classFile = className + ".class";

    if ("file".equals(location.getProtocol())) {
      // Load from directory
      try {
        // Use URI to avoid deprecated URL constructor
        URI baseUri = location.toURI();
        URI classUri = baseUri.resolve(classFile);
        URL classUrl = classUri.toURL();
        return readBytecode(classUrl);
      } catch (URISyntaxException e) {
        throw new IOException("Invalid URL for class location: " + location, e);
      }
    } else if ("jar".equals(location.getProtocol())) {
      // Load from JAR
      return loadFromJar(location, classFile);
    }

    return null;
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
        // Load the class
        Class<?> clazz = Class.forName(classInfo.getJavaClassName());

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