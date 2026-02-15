package com.bitsapplied.descartes.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProxyLogLevelTest {

  @Test
  void parseSupportsCaseInsensitiveValues() {
    assertEquals(ProxyLogLevel.ERROR, ProxyLogLevel.parse("error"));
    assertEquals(ProxyLogLevel.INFO, ProxyLogLevel.parse("INFO"));
    assertEquals(ProxyLogLevel.DEBUG, ProxyLogLevel.parse("DeBuG"));
  }

  @Test
  void parseRejectsInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> ProxyLogLevel.parse(""));
    assertThrows(IllegalArgumentException.class, () -> ProxyLogLevel.parse("trace"));
  }

  @Test
  void remoteDebugProxyConfigDefaultsToInfo() {
    RemoteDebugProxyConfig config = RemoteDebugProxyConfig.builder().build();
    assertEquals(ProxyLogLevel.INFO, config.getLogLevel());
  }

  @Test
  void remoteDebugProxyConfigAcceptsConfiguredLogLevel() {
    RemoteDebugProxyConfig config = RemoteDebugProxyConfig.builder().logLevel(ProxyLogLevel.ERROR).build();
    assertEquals(ProxyLogLevel.ERROR, config.getLogLevel());
  }
}
