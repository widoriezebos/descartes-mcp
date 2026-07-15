package com.bitsapplied.descartes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RepositoryConfigurationConsistencyTest {

  private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void clientConfigurationsShareTheCanonicalProxyEnvironment() throws Exception {
    JsonNode template = readJson("config/mcp/mcpservers.json");
    Map<String, String> expectedEnvironment = stringMap(
        template.path("mcpServers").path("descartes-proxy").path("env"));

    JsonNode claude = readJson(".mcp.json");
    JsonNode claudeProxy = claude.path("mcpServers").path("descartes-proxy");
    assertThat(stringMap(claudeProxy.path("env"))).isEqualTo(expectedEnvironment);
    assertThat(claudeProxy.path("command").asText()).isEqualTo("node");
    assertThat(claudeProxy.path("args").path(0).asText())
        .isEqualTo("${CLAUDE_PROJECT_DIR:-.}/config/mcp/mcp-tcp-adapter.js");
    assertThat(claudeProxy.path("timeout").asLong()).isEqualTo(130_000L);
    assertThat(PROJECT_ROOT.resolve(claudeProxy.path("args").path(0).asText()
        .replace("${CLAUDE_PROJECT_DIR:-.}", ".")).normalize()).isRegularFile();

    JsonNode gemini = readJson(".gemini/settings.json");
    JsonNode geminiProxy = gemini.path("mcpServers").path("descartes-proxy");
    assertThat(stringMap(geminiProxy.path("env"))).isEqualTo(expectedEnvironment);
    assertThat(geminiProxy.path("command").asText()).isEqualTo("node");
    assertThat(geminiProxy.path("cwd").asText()).isEqualTo(".");
    assertThat(geminiProxy.path("args").path(0).asText()).isEqualTo("config/mcp/mcp-tcp-adapter.js");
    assertThat(geminiProxy.path("timeout").asLong()).isEqualTo(130_000L);
    assertThat(PROJECT_ROOT.resolve(geminiProxy.path("cwd").asText())
        .resolve(geminiProxy.path("args").path(0).asText()).normalize()).isRegularFile();

    String codex = Files.readString(PROJECT_ROOT.resolve(".codex/config.toml"));
    assertThat(parseCodexEnvironment(codex)).isEqualTo(expectedEnvironment);
    String codexServer = codex.substring(codex.indexOf("[mcp_servers.descartes-proxy]"),
        codex.indexOf("[mcp_servers.descartes-proxy.env]"));
    assertThat(codexServer).contains(
        "command = \"node\"",
        "args = [\"config/mcp/mcp-tcp-adapter.js\"]",
        "cwd = \".\"",
        "tool_timeout_sec = 130");
    assertThat(PROJECT_ROOT.resolve(".").resolve("config/mcp/mcp-tcp-adapter.js").normalize()).isRegularFile();

    long toolTimeoutMs = Long.parseLong(expectedEnvironment.get("MCP_TOOL_TIMEOUT_MS"));
    long adapterGraceMs = Long.parseLong(expectedEnvironment.get("MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS"));
    long requestTimeoutMs = Long.parseLong(expectedEnvironment.get("MCP_REQUEST_TIMEOUT"));
    long clientTimeoutMs = 130_000L;
    assertThat(toolTimeoutMs + 1000L).isLessThan(toolTimeoutMs + adapterGraceMs);
    assertThat(toolTimeoutMs + adapterGraceMs).isLessThan(clientTimeoutMs);
    assertThat(requestTimeoutMs).isGreaterThanOrEqualTo(clientTimeoutMs);
  }

  @Test
  void runtimeAndReleaseDocumentationMatchThePomVersion() throws Exception {
    String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
    var projectVersion = Pattern.compile("<version>([^<]+)</version>").matcher(pom);
    assertThat(projectVersion.find()).isTrue();
    String expectedVersion = projectVersion.group(1);

    assertThat(read("src/main/java/com/bitsapplied/descartes/MCPServer.java"))
        .contains("private String serverVersion = \"" + expectedVersion + "\"");
    assertThat(read("src/main/java/com/bitsapplied/descartes/example/SimpleMCPServerExample.java"))
        .contains("context.put(\"example.version\", \"" + expectedVersion + "\")");
    assertThat(read("src/main/java/com/bitsapplied/descartes/example/debugger/DebuggerWorkflowExample.java"))
        .contains("server.setServerVersion(\"" + expectedVersion + "\")");
    assertThat(read("doc/how-to-embed.md")).contains(
        "<groupId>com.bitsapplied.descartes</groupId>",
        "<artifactId>descartes-mcp</artifactId>",
        "<version>" + expectedVersion + "</version>");
    assertThat(read("src/test/java/com/bitsapplied/descartes/hotreload/HotReloadServiceTest.java"))
        .contains("descartes-mcp-" + expectedVersion + "-jar-with-dependencies.jar");

    assertVersionMatches("doc/MCPRemoteDebugProxy.md",
        "PROXY_VERSION=([0-9]+\\.[0-9]+\\.[0-9]+)", expectedVersion);
    assertVersionMatches("scripts/run-remote-proxy-from-maven.sh",
        "--version ([0-9]+\\.[0-9]+\\.[0-9]+)", expectedVersion);
    assertVersionMatches("scripts/README.md",
        "--version ([0-9]+\\.[0-9]+\\.[0-9]+)", expectedVersion);
  }

  private JsonNode readJson(String relativePath) throws Exception {
    return OBJECT_MAPPER.readTree(PROJECT_ROOT.resolve(relativePath).toFile());
  }

  private String read(String relativePath) throws Exception {
    return Files.readString(PROJECT_ROOT.resolve(relativePath));
  }

  private Map<String, String> stringMap(JsonNode object) {
    Map<String, String> values = new LinkedHashMap<>();
    object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
    return values;
  }

  private Map<String, String> parseCodexEnvironment(String toml) {
    int sectionStart = toml.indexOf("[mcp_servers.descartes-proxy.env]");
    assertThat(sectionStart).isGreaterThanOrEqualTo(0);

    Map<String, String> values = new LinkedHashMap<>();
    var matcher = Pattern.compile("(?m)^([A-Z][A-Z0-9_]*) = \"([^\"]*)\"$")
        .matcher(toml.substring(sectionStart));
    while (matcher.find()) {
      values.put(matcher.group(1), matcher.group(2));
    }
    return values;
  }

  private void assertVersionMatches(String relativePath, String regex, String expectedVersion) throws Exception {
    var matcher = Pattern.compile(regex).matcher(read(relativePath));
    int matches = 0;
    while (matcher.find()) {
      assertThat(matcher.group(1)).as(relativePath).isEqualTo(expectedVersion);
      matches++;
    }
    assertThat(matches).as("version declarations in %s", relativePath).isGreaterThan(0);
  }
}
