package com.bitsapplied.descartes.hotreload.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ClassFileTransformer that tracks where classes are loaded from. This
 * transformer is registered with the instrumentation API to monitor all class
 * loading in the JVM.
 * 
 * @author Descartes MCP
 */
public class ClassLoadTracker implements ClassFileTransformer {

  private static final Logger LOGGER = Logger.getLogger(ClassLoadTracker.class.getName());

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

    // Only track during initial load, not during redefinition
    if (classBeingRedefined != null) {
      return null;
    }

    // Skip null class names
    if (className == null) {
      return null;
    }

    // Skip JDK and agent classes
    if (shouldSkipClass(className)) {
      return null;
    }

    try {
      // Get the location where the class was loaded from
      URL location = null;
      if (protectionDomain != null) {
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource != null) {
          location = codeSource.getLocation();
        }
      }

      // Record the class information
      if (location != null) {
        HotReloadAgent.recordClassLocation(className, location, classfileBuffer.clone());
        LOGGER.log(Level.FINE, "Tracked class: " + className + " from " + location);
      }

    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to track class: " + className, e);
    }

    // Return null to indicate we're not modifying the class
    return null;
  }

  /**
   * Check if a class should be skipped from tracking.
   * 
   * @param className Binary class name (e.g., java/lang/String)
   * @return true if class should be skipped
   */
  private boolean shouldSkipClass(String className) {
    return className.startsWith("java/") || className.startsWith("javax/") || className.startsWith("sun/")
        || className.startsWith("com/sun/") || className.startsWith("jdk/")
        || className.startsWith("com/bitsapplied/descartes/hotreload/agent/") || className.contains("$$") || // Skip
                                                                                                             // synthetic
                                                                                                             // classes
        className.contains("$Proxy"); // Skip proxy classes
  }
}