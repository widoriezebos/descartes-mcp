package com.bitsapplied.descartes.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

  @Test
  void buildIdIsResolvedFromFilteredResource() {
    assertThat(BuildInfo.buildId()).isNotBlank().doesNotContain("${");
  }

  @Test
  void projectVersionMatchesPomFormat() {
    assertThat(BuildInfo.projectVersion()).matches("[0-9]+\\.[0-9]+\\.[0-9]+");
  }

  @Test
  void describeCombinesVersionAndBuildId() {
    assertThat(BuildInfo.describe()).isEqualTo(BuildInfo.projectVersion() + "+" + BuildInfo.buildId());
  }
}
