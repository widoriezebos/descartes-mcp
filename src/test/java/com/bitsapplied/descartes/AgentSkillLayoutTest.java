package com.bitsapplied.descartes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentSkillLayoutTest {

  private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  private static final Path CANONICAL_SKILL = PROJECT_ROOT.resolve(".agents/skills/descartes-debug");

  @Test
  void canonicalSkillUsesPortableFrontmatterAndCodexMetadata() throws Exception {
    Path skillFile = CANONICAL_SKILL.resolve("SKILL.md");
    assertThat(skillFile).isRegularFile();

    String skill = Files.readString(skillFile);
    int frontmatterEnd = skill.indexOf("\n---", 4);
    assertThat(skill).startsWith("---\n");
    assertThat(frontmatterEnd).isGreaterThan(4);

    List<String> frontmatterKeys = skill.substring(4, frontmatterEnd).lines()
        .filter(line -> !line.isBlank() && !Character.isWhitespace(line.charAt(0)) && line.contains(":"))
        .map(line -> line.substring(0, line.indexOf(':')))
        .toList();

    assertThat(frontmatterKeys).containsExactly("name", "description");
    assertThat(skill).contains("name: descartes-debug");
    assertThat(Files.readAllLines(skillFile)).hasSizeLessThan(500);

    String openAiMetadata = Files.readString(CANONICAL_SKILL.resolve("agents/openai.yaml"));
    assertThat(openAiMetadata).contains(
        "display_name: \"Descartes Java Debugger\"",
        "short_description: \"Debug live Java applications through JDWP\"",
        "default_prompt: \"Use $descartes-debug");
  }

  @Test
  void claudeEntryIsASymlinkToTheCanonicalSkill() throws Exception {
    Path claudeEntry = PROJECT_ROOT.resolve(".claude/skills/descartes-debug");

    assertThat(Files.isSymbolicLink(claudeEntry)).isTrue();
    assertThat(Files.readSymbolicLink(claudeEntry))
        .isEqualTo(Path.of("../../.agents/skills/descartes-debug"));
    assertThat(claudeEntry.toRealPath()).isEqualTo(CANONICAL_SKILL.toRealPath());
    assertThat(PROJECT_ROOT.resolve(".claude/skills/debug")).doesNotExist();
    assertThat(PROJECT_ROOT.resolve(".agents/skills/debug")).doesNotExist();
  }
}
