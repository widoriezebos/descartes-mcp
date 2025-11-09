package com.bitsapplied.descartes.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
import com.bitsapplied.descartes.profiler.tools.ProfilerStartTool;
import com.bitsapplied.descartes.resources.MCPResource;
import com.bitsapplied.descartes.settings.SettingsProvider;
import com.bitsapplied.descartes.tools.JShellTool;
import com.bitsapplied.descartes.tools.MCPTool;

class McpServerLauncherTest {

  private DescartesRuntime runtime;
  private RecordingMcpServer server;

  @BeforeEach
  void setUp() {
    runtime = DescartesRuntime.bootstrap(TestHosts.simple());
    server = new RecordingMcpServer(new InMemorySettingsProvider(), 0);
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  void registerDebuggerToolsAddsCompleteSuiteOnce() {
    McpServerLauncher launcher = McpServerLauncher.attach(runtime, server);

    launcher.registerDebuggerTools().registerDebuggerTools();

    assertThat(server.toolsSnapshot()).extracting(tool -> tool.getClass().getSimpleName()).containsExactlyInAnyOrder(
        "DebuggerSessionTool", "DebuggerBreakpointsTool", "DebuggerStepTool", "DebuggerThreadsTool",
        "DebuggerStackTraceTool", "DebuggerVariablesTool", "DebuggerEvaluateTool", "DebuggerWatchTool",
        "DebuggerEventsTool");

    assertThat(launcher.registeredTools()).hasSize(9);
  }

  @Test
  void registerProfilerToolsAddsProfilerSuite() {
    McpServerLauncher launcher = McpServerLauncher.attach(runtime, server);

    launcher.registerProfilerTools();

    assertThat(server.toolsSnapshot()).extracting(tool -> tool.getClass().getSimpleName()).containsExactlyInAnyOrder(
        "ProfilerStartTool", "ProfilerStopTool", "ProfilerHotspotsTool", "ProfilerCallTreeTool", "ProfilerListTool",
        "ProfilerExportTool");
  }

  @Test
  void registerJshellAndCustomTool() {
    McpServerLauncher launcher = McpServerLauncher.attach(runtime, server);

    launcher.registerJshellTools().registerTool(new ProfilerStartTool(runtime.profiler().service()));

    assertThat(server.toolsSnapshot()).anyMatch(tool -> tool instanceof JShellTool);
    assertThat(server.toolsSnapshot()).anyMatch(tool -> tool instanceof ProfilerStartTool);
  }

  @Test
  void registerSystemResourcesAndApplicationContext() {
    McpServerLauncher launcher = McpServerLauncher.attach(runtime, server);

    launcher.registerSystemResources().registerApplicationContextResource();

    List<String> resourceUris = server.resourcesSnapshot().stream()
        .flatMap(resource -> resource.listResources().stream()).map(meta -> (String) meta.get("uri"))
        .collect(Collectors.toList());

    assertThat(resourceUris).contains("system://classpath", "system://system/properties", "system://metrics",
        "system://threads/dump", "system://mbeans", "app://context");

    assertThat(launcher.registeredResourceHandlers()).containsKey("system");
    assertThat(launcher.registeredResourceHandlers().get("system")).hasSize(5);
  }

  @Test
  void createLauncherWithDefaultsSetsContextEntries() {
    try (DescartesRuntime newRuntime = DescartesRuntime.bootstrap(TestHosts.simple())) {
      McpServerLauncher launcher = McpServerLauncher.create(newRuntime, 0);

      // ensure runtime context is propagated to server context
      assertThat(launcher.server().getContext()).containsKeys(DescartesRuntime.CONTEXT_KEY_RUNTIME,
          DescartesRuntime.CONTEXT_KEY_PROFILER, DescartesRuntime.CONTEXT_KEY_DEBUGGER);
    }
  }

  private static final class RecordingMcpServer extends MCPServer {
    RecordingMcpServer(SettingsProvider settingsProvider, int port) {
      super(settingsProvider, port, new ConcurrentHashMap<>());
    }

    List<MCPTool> toolsSnapshot() {
      return new ArrayList<>(this.tools);
    }

    List<MCPResource> resourcesSnapshot() {
      return new ArrayList<>(this.resources);
    }
  }

  private static final class InMemorySettingsProvider implements SettingsProvider {
    private final Map<String, String> values = new HashMap<>();

    @Override
    public String getString(String key, String defaultValue) {
      return values.getOrDefault(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
      return Integer.parseInt(values.getOrDefault(key, Integer.toString(defaultValue)));
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
      return Boolean.parseBoolean(values.getOrDefault(key, Boolean.toString(defaultValue)));
    }

    @Override
    public double getDouble(String key, double defaultValue) {
      return Double.parseDouble(values.getOrDefault(key, Double.toString(defaultValue)));
    }

    @Override
    public void setString(String key, String value) {
      values.put(key, value);
    }

    @Override
    public void setInt(String key, int value) {
      values.put(key, Integer.toString(value));
    }

    @Override
    public void setBoolean(String key, boolean value) {
      values.put(key, Boolean.toString(value));
    }
  }

  private static final class TestHosts {
    private TestHosts() {
    }

    static DescartesHost simple() {
      return new DescartesHost() {
        @Override
        public ProfilerIntegration profiler() {
          return new ProfilerIntegration() {
            @Override
            public ProfilerSettings settings() {
              return ProfilerSettings.builder().enabled(false).build();
            }
          };
        }
      };
    }
  }
}
