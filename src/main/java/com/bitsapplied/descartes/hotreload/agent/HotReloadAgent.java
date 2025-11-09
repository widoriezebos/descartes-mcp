package com.bitsapplied.descartes.hotreload.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.bitsapplied.descartes.hotreload.util.BytecodeLoader;

/**
 * Java Agent that provides instrumentation capabilities for hot class
 * reloading. This agent must be loaded at JVM startup using
 * -javaagent:path/to/agent.jar
 * 
 * The agent tracks class loading information and provides the ability to
 * redefine classes at runtime.
 * 
 * @author Descartes MCP
 */
public class HotReloadAgent {

  private static final Logger LOGGER = Logger.getLogger(HotReloadAgent.class.getName());

  private static volatile Instrumentation instrumentation;
  private static final Map<String, ClassLoadInfo> loadedClasses = new ConcurrentHashMap<>();
  private static volatile boolean agentLoaded = false;

  /**
   * Entry point for the agent when loaded at JVM startup.
   * 
   * @param agentArgs Agent arguments (can be null)
   * @param inst      Instrumentation instance provided by JVM
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    initialize(agentArgs, inst);
    LOGGER.info("Hot Reload Agent loaded via premain");
  }

  /**
   * Entry point for the agent when attached to a running JVM.
   * 
   * @param agentArgs Agent arguments (can be null)
   * @param inst      Instrumentation instance provided by JVM
   */
  public static void agentmain(String agentArgs, Instrumentation inst) {
    initialize(agentArgs, inst);
    LOGGER.info("Hot Reload Agent loaded via agentmain (dynamic attach)");
  }

  /**
   * Initialize the agent with instrumentation capabilities.
   */
  private static void initialize(String agentArgs, Instrumentation inst) {
    if (instrumentation != null) {
      LOGGER.warning("Agent already initialized, skipping re-initialization");
      return;
    }

    instrumentation = inst;
    agentLoaded = true;

    // Add transformer to track class loading
    ClassLoadTracker tracker = new ClassLoadTracker();
    inst.addTransformer(tracker, true);

    // Process already loaded classes
    for (Class<?> clazz : inst.getAllLoadedClasses()) {
      if (shouldTrackClass(clazz)) {
        recordClassInfo(clazz);
      }
    }

    LOGGER.info("Hot Reload Agent initialized successfully. " + "Tracking " + loadedClasses.size() + " classes");
  }

  /**
   * Check if the agent has been loaded.
   * 
   * @return true if agent is loaded and ready
   */
  public static boolean isAgentLoaded() {
    return agentLoaded && instrumentation != null;
  }

  /**
   * Get the instrumentation instance.
   * 
   * @return Instrumentation instance or null if agent not loaded
   */
  public static Instrumentation getInstrumentation() {
    return instrumentation;
  }

  /**
   * Record information about a loaded class.
   * 
   * @param className Binary class name (e.g., com/example/MyClass)
   * @param location  URL where class was loaded from
   * @param bytecode  Original bytecode of the class
   */
  public static void recordClassLocation(String className, URL location, byte[] bytecode, ClassLoader classLoader) {
    if (className != null && location != null) {
      ClassLoadInfo info = new ClassLoadInfo(className, location, bytecode, classLoader);
      loadedClasses.put(className, info);
    }
  }

  /**
   * Record information about an already loaded class.
   * 
   * @param clazz Class to record
   */
  private static void recordClassInfo(Class<?> clazz) {
    try {
      String className = clazz.getName().replace('.', '/');
      ProtectionDomain pd = clazz.getProtectionDomain();
      if (pd != null) {
        CodeSource cs = pd.getCodeSource();
        if (cs != null) {
          URL location = cs.getLocation();
          if (location != null) {
            byte[] bytecode = null;
            try {
              bytecode = BytecodeLoader.loadClassBytes(className, location, clazz.getClassLoader());
            } catch (IOException e) {
              LOGGER.log(Level.FINE, "Unable to capture baseline bytecode for " + className, e);
            }

            ClassLoadInfo info = new ClassLoadInfo(className, location, bytecode, clazz.getClassLoader());
            loadedClasses.put(className, info);
          }
        }
      }
    } catch (Exception e) {
      // Ignore classes we can't access
    }
  }

  /**
   * Get information about a loaded class.
   * 
   * @param className Binary class name
   * @return ClassLoadInfo or null if not found
   */
  public static ClassLoadInfo getClassInfo(String className) {
    return loadedClasses.get(className);
  }

  /**
   * Get all tracked class information.
   * 
   * @return Map of class names to class info
   */
  public static Map<String, ClassLoadInfo> getAllClassInfo() {
    return new ConcurrentHashMap<>(loadedClasses);
  }

  /**
   * Check if a class should be tracked.
   * 
   * @param clazz Class to check
   * @return true if class should be tracked
   */
  private static boolean shouldTrackClass(Class<?> clazz) {
    String name = clazz.getName();
    // Don't track JDK classes, agent classes, or synthetic classes
    return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("sun.")
        && !name.startsWith("com.sun.") && !name.startsWith("jdk.") && !name.contains("$Proxy") && !clazz.isSynthetic();
  }

  /**
   * Clear the class cache for specific classes.
   * 
   * @param pattern Package pattern to clear (e.g., "com.example.*")
   */
  public static void clearCache(String pattern) {
    String prefix = pattern.replace(".", "/").replace("*", "");
    loadedClasses.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
  }
}
