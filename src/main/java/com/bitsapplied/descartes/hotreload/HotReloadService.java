package com.bitsapplied.descartes.hotreload;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.hotreload.agent.ClassLoadInfo;
import com.bitsapplied.descartes.hotreload.agent.HotReloadAgent;
import com.bitsapplied.descartes.hotreload.analyzer.ClassStructure;
import com.bitsapplied.descartes.hotreload.analyzer.ClassStructureAnalyzer;
import com.bitsapplied.descartes.hotreload.analyzer.ValidationResult;
import com.bitsapplied.descartes.hotreload.util.BytecodeLoader;

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
      Map<ClassLoadInfo, ReloadTarget> changedClasses = detectChanges(candidateClasses, force);

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

      Map<ClassLoadInfo, ReloadTarget> changedClasses = detectChanges(candidateClasses, false);

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
  private Map<ClassLoadInfo, ReloadTarget> detectChanges(List<ClassLoadInfo> candidateClasses, boolean force) {
    Map<ClassLoadInfo, ReloadTarget> changedClasses = new LinkedHashMap<>();

    for (ClassLoadInfo classInfo : candidateClasses) {
      try {
        boolean timestampTrigger = classInfo.isSourceModified();
        boolean requiresContentCheck = !classInfo.hasReliableTimestamp() || !classInfo.hasTrackedBytecode();
        if (!force && !timestampTrigger && !requiresContentCheck) {
          continue;
        }

        byte[] newBytecode = BytecodeLoader.loadClassBytes(classInfo.getClassName(), classInfo.getSourceLocation(),
            classInfo.getClassLoader());

        if (newBytecode == null) {
          LOGGER.fine("Unable to resolve bytecode for class: " + classInfo.getClassName());
          continue;
        }

        boolean bytecodeChanged = force || !classInfo.hasTrackedBytecode() || classInfo.hasBytecodeChanged(newBytecode);
        long sourceTimestamp = classInfo.fetchCurrentSourceTimestamp();

        if (bytecodeChanged) {
          changedClasses.put(classInfo, new ReloadTarget(newBytecode, sourceTimestamp));
          LOGGER.fine("Detected change in class: " + classInfo.getClassName());
        } else if (timestampTrigger) {
          classInfo.markInspected(sourceTimestamp);
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Failed to load bytecode for class: " + classInfo.getClassName(), e);
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to check changes for class: " + classInfo.getClassName(), e);
      }
    }

    return changedClasses;
  }

  /**
   * Validate if classes can be safely redefined.
   * 
   * @param changedClasses Map of changed classes to new bytecode
   * @return Validation result
   */
  private ValidationResult validateRedefinition(Map<ClassLoadInfo, ReloadTarget> changedClasses) {
    List<String> errors = new ArrayList<>();

    for (Map.Entry<ClassLoadInfo, ReloadTarget> entry : changedClasses.entrySet()) {
      ClassLoadInfo classInfo = entry.getKey();
      byte[] newBytecode = entry.getValue().bytecode();

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
  private HotReloadResult performRedefinition(Map<ClassLoadInfo, ReloadTarget> changedClasses, int totalAnalyzed,
      long startTime) {
    Instrumentation inst = HotReloadAgent.getInstrumentation();
    if (inst == null) {
      return HotReloadResult.failed("Instrumentation not available");
    }

    List<ClassDefinition> definitions = new ArrayList<>();
    List<String> reloadedClassNames = new ArrayList<>();
    Map<String, String> skippedClasses = new LinkedHashMap<>();
    List<ReloadRequest> preparedReloads = new ArrayList<>();

    for (Map.Entry<ClassLoadInfo, ReloadTarget> entry : changedClasses.entrySet()) {
      ClassLoadInfo classInfo = entry.getKey();
      ReloadTarget target = entry.getValue();
      byte[] newBytecode = target.bytecode();

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
        preparedReloads.add(new ReloadRequest(classInfo, target));

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

      // Update tracked bytecode only after redefine succeeds
      for (ReloadRequest request : preparedReloads) {
        request.classInfo().updateAfterSuccessfulRedefinition(request.target().bytecode(),
            request.target().sourceTimestamp());
      }

      return HotReloadResult.success(totalAnalyzed, changedClasses.size(), definitions.size(), reloadedClassNames,
          skippedClasses, elapsed);

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Class redefinition failed", e);
      return HotReloadResult.failed("Redefinition failed: " + e.getMessage(), totalAnalyzed, changedClasses.size(),
          skippedClasses);
    }
  }

  private record ReloadTarget(byte[] bytecode, long sourceTimestamp) {
  }

  private record ReloadRequest(ClassLoadInfo classInfo, ReloadTarget target) {
  }
}
