package com.bitsapplied.descartes.hotreload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a hot reload operation containing detailed information about what
 * was reloaded, what failed, and why.
 * 
 * @author Descartes MCP
 */
public class HotReloadResult {

  private final boolean success;
  private final String errorMessage;
  private final int classesAnalyzed;
  private final int classesChanged;
  private final int classesReloaded;
  private final List<String> reloadedClassNames;
  private final Map<String, String> skippedClasses;
  private final List<String> detailedErrors;
  private final long reloadTimeMs;

  private HotReloadResult(boolean success, String errorMessage, int classesAnalyzed, int classesChanged,
      int classesReloaded, List<String> reloadedClassNames, Map<String, String> skippedClasses,
      List<String> detailedErrors, long reloadTimeMs) {
    this.success = success;
    this.errorMessage = errorMessage;
    this.classesAnalyzed = classesAnalyzed;
    this.classesChanged = classesChanged;
    this.classesReloaded = classesReloaded;
    this.reloadedClassNames = reloadedClassNames != null ? new ArrayList<>(reloadedClassNames) : new ArrayList<>();
    this.skippedClasses = skippedClasses != null ? new LinkedHashMap<>(skippedClasses) : new LinkedHashMap<>();
    this.detailedErrors = detailedErrors != null ? new ArrayList<>(detailedErrors) : new ArrayList<>();
    this.reloadTimeMs = reloadTimeMs;
  }

  /**
   * Create a successful reload result.
   * 
   * @param classesAnalyzed    Number of classes analyzed
   * @param classesChanged     Number of classes that changed
   * @param classesReloaded    Number of classes successfully reloaded
   * @param reloadedClassNames Names of reloaded classes
   * @param skippedClasses     Map of skipped classes and reasons
   * @param reloadTimeMs       Time taken for reload in milliseconds
   * @return Success result
   */
  public static HotReloadResult success(int classesAnalyzed, int classesChanged, int classesReloaded,
      List<String> reloadedClassNames, Map<String, String> skippedClasses, long reloadTimeMs) {
    return new HotReloadResult(true, null, classesAnalyzed, classesChanged, classesReloaded, reloadedClassNames,
        skippedClasses, null, reloadTimeMs);
  }

  /**
   * Create a result for when no classes match the filter.
   * 
   * @param filter The filter that matched no classes
   * @return No matches result
   */
  public static HotReloadResult noMatches(String filter) {
    return new HotReloadResult(false, "No classes found matching filter: " + filter, 0, 0, 0, null, null, null, 0);
  }

  /**
   * Create a result for when no changes were detected.
   * 
   * @param classesAnalyzed Number of classes analyzed
   * @return No changes result
   */
  public static HotReloadResult noChanges(int classesAnalyzed) {
    return new HotReloadResult(true, "No changes detected in " + classesAnalyzed + " classes", classesAnalyzed, 0, 0,
        null, null, null, 0);
  }

  /**
   * Create a result for validation success.
   * 
   * @param classesAnalyzed Number of classes analyzed
   * @param classesChanged  Number of classes that would be changed
   * @return Validation success result
   */
  public static HotReloadResult validationSuccess(int classesAnalyzed, int classesChanged) {
    return new HotReloadResult(true, null, classesAnalyzed, classesChanged, 0, null, null, null, 0);
  }

  /**
   * Create a result for validation failure.
   * 
   * @param classesAnalyzed Number of classes analyzed
   * @param classesChanged  Number of classes that changed
   * @param errors          Validation errors
   * @return Validation failed result
   */
  public static HotReloadResult validationFailed(int classesAnalyzed, int classesChanged, List<String> errors) {
    return new HotReloadResult(false, "Validation failed: Classes have incompatible changes", classesAnalyzed,
        classesChanged, 0, null, null, errors, 0);
  }

  /**
   * Create a general failure result.
   * 
   * @param errorMessage Error message
   * @return Failed result
   */
  public static HotReloadResult failed(String errorMessage) {
    return new HotReloadResult(false, errorMessage, 0, 0, 0, null, null, null, 0);
  }

  /**
   * Create a failure result with details.
   * 
   * @param errorMessage    Error message
   * @param classesAnalyzed Number of classes analyzed
   * @param classesChanged  Number of classes that changed
   * @param skippedClasses  Map of skipped classes and reasons
   * @return Failed result
   */
  public static HotReloadResult failed(String errorMessage, int classesAnalyzed, int classesChanged,
      Map<String, String> skippedClasses) {
    return new HotReloadResult(false, errorMessage, classesAnalyzed, classesChanged, 0, null, skippedClasses, null, 0);
  }

  // Getters

  public boolean isSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public int getClassesAnalyzed() {
    return classesAnalyzed;
  }

  public int getClassesChanged() {
    return classesChanged;
  }

  public int getClassesReloaded() {
    return classesReloaded;
  }

  public List<String> getReloadedClassNames() {
    return new ArrayList<>(reloadedClassNames);
  }

  public Map<String, String> getSkippedClasses() {
    return new LinkedHashMap<>(skippedClasses);
  }

  public List<String> getDetailedErrors() {
    return new ArrayList<>(detailedErrors);
  }

  public long getReloadTimeMs() {
    return reloadTimeMs;
  }

  @Override
  public String toString() {
    if (success) {
      return String.format("HotReloadResult[SUCCESS: analyzed=%d, changed=%d, reloaded=%d, time=%dms]", classesAnalyzed,
          classesChanged, classesReloaded, reloadTimeMs);
    } else {
      return String.format("HotReloadResult[FAILED: %s, analyzed=%d, changed=%d]", errorMessage, classesAnalyzed,
          classesChanged);
    }
  }
}