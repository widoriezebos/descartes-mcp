package com.bitsapplied.descartes.debugger;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Utility for detecting whether the current JVM was started with debugging
 * support enabled.
 *
 * <p>
 * Detection relies on standard JDWP flags ({@code -agentlib:jdwp},
 * {@code -Xdebug}, {@code -Xrunjdwp}) as well as the vendor-specific
 * {@code java.vm.debug} property used by some runtimes. This class makes no
 * attempt to verify whether a debugger client is currently attached—only that
 * the process was started in a mode where debugging is possible.
 * </p>
 */
public final class DebuggerAvailability {
  private static final List<String> DEBUG_ARGUMENT_PREFIXES = List.of("-agentlib:jdwp", "-Xdebug", "-Xrunjdwp");
  private static final String VM_DEBUG_PROPERTY = "java.vm.debug";

  private DebuggerAvailability() {
  }

  /**
   * Returns {@code true} when the JVM input arguments or system properties
   * indicate that debugging support is enabled.
   *
   * @return {@code true} if the debugger infrastructure is configured
   */
  public static boolean isDebuggerAvailable() {
    return hasDebuggerAgent(ManagementFactory.getRuntimeMXBean().getInputArguments())
        || isVmDebugPropertySet(System.getProperty(VM_DEBUG_PROPERTY));
  }

  static boolean hasDebuggerAgent(List<String> inputArguments) {
    if (inputArguments == null || inputArguments.isEmpty()) {
      return false;
    }
    return inputArguments.stream().anyMatch(DebuggerAvailability::isDebugArgument);
  }

  static boolean isDebugArgument(String argument) {
    if (argument == null) {
      return false;
    }
    String trimmed = argument.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    return DEBUG_ARGUMENT_PREFIXES.stream().anyMatch(trimmed::startsWith);
  }

  static boolean isVmDebugPropertySet(String vmDebugProperty) {
    if (vmDebugProperty == null) {
      return false;
    }
    String trimmed = vmDebugProperty.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    return !"false".equalsIgnoreCase(trimmed);
  }
}
