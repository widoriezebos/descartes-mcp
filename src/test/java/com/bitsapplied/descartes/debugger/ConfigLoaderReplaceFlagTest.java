package com.bitsapplied.descartes.debugger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigLoaderReplaceFlagTest {

  @Test
  void replaceDefaultsToFalse() {
    RemoteDebugProxyConfig config = ConfigLoader.load(new String[] {});

    assertThat(config.isReplaceExisting()).isFalse();
  }

  @Test
  void replaceFlagEnablesReplaceExisting() {
    RemoteDebugProxyConfig config = ConfigLoader.load(new String[] { "--replace" });

    assertThat(config.isReplaceExisting()).isTrue();
  }

  @Test
  void replaceFlagCombinesWithValueArguments() {
    RemoteDebugProxyConfig config = ConfigLoader.load(new String[] { "--mcp-port", "9191", "--replace" });

    assertThat(config.getMcpPort()).isEqualTo(9191);
    assertThat(config.isReplaceExisting()).isTrue();
  }
}
