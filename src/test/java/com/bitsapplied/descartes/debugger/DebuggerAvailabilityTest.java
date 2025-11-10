package com.bitsapplied.descartes.debugger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DebuggerAvailabilityTest {

  @Test
  void hasDebuggerAgentDetectsStandardFlags() {
    List<String> args = List.of("-Xmx512m", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
        "-Dsome.property=value");

    assertThat(DebuggerAvailability.hasDebuggerAgent(args)).isTrue();
  }

  @Test
  void hasDebuggerAgentIgnoresNonDebugArguments() {
    List<String> args = List.of("-Xmx512m", "-Dspring.profiles.active=dev", "-javaagent:metrics-agent.jar");

    assertThat(DebuggerAvailability.hasDebuggerAgent(args)).isFalse();
  }

  @Test
  void isVmDebugPropertySetRecognizesEnabledValues() {
    assertThat(DebuggerAvailability.isVmDebugPropertySet("true")).isTrue();
    assertThat(DebuggerAvailability.isVmDebugPropertySet(" TRUE ")).isTrue();
    assertThat(DebuggerAvailability.isVmDebugPropertySet("false")).isFalse();
    assertThat(DebuggerAvailability.isVmDebugPropertySet("")).isFalse();
    assertThat(DebuggerAvailability.isVmDebugPropertySet(null)).isFalse();
  }

  @Test
  void isDebuggerAvailableHonorsVmDebugPropertyFallback() {
    String previous = System.getProperty("java.vm.debug");
    System.setProperty("java.vm.debug", "true");
    try {
      assertThat(DebuggerAvailability.isDebuggerAvailable()).isTrue();
    } finally {
      if (previous == null) {
        System.clearProperty("java.vm.debug");
      } else {
        System.setProperty("java.vm.debug", previous);
      }
    }
  }
}
