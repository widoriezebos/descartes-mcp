# Running Descartes Examples

This guide explains the robust, reliable ways to run Descartes MCP examples.

## Quick Reference

Proxy mode has two launch paths:
- `./scripts/run-remote-proxy-from-maven.sh` (recommended): pulls the released proxy artifact and runs it.
- `./scripts/run-remote-proxy.sh` (source-dev fallback): builds/runs from your local workspace.

## Available Startup Scripts

### 1. `./scripts/run-with-hotreload.sh`
**SimpleMCPServerExample with hot reload capability**

```bash
# Interactive mode (waits for Enter to stop)
./scripts/run-with-hotreload.sh

# Continuous mode (runs until killed)
./scripts/run-with-hotreload.sh --continuous
```

**Features:**
- All 28 tools available (debugger, profiler, monitoring, JShell, etc.)
- Hot class reload enabled
- Requires `-javaagent` flag (automatically included)
- Port: 9080

### 2. DebuggerWorkflowExample
**AI-assisted debugging scenarios via Maven profile or debug skill**

```bash
# Automated demo (runs all scenarios, then exits)
mvn exec:java -Pdebugger-demo

# Interactive mode (waits for MCP client)
mvn exec:java -Pdebugger-demo -Dexec.args="--interactive"
```

Alternatively, use the **debug skill** from Claude Code or Codex CLI -- the agent will build, launch with JDWP, and connect automatically. See [debug-skill.md](debug-skill.md).

**Features:**
- 8 debugger tools + monitoring/introspection tools
- Includes all JVM flags for JDK 17+ (JDI/Attach API)
- Built-in debugging scenarios (basic, bugs, data structures, concurrency, exceptions, call stacks)
- Port: 9080

### 3. `./run-profiler-demo.sh`
**ProfilerWorkflowExample with performance profiling**

```bash
# Automated demo (runs all profiling scenarios)
./run-profiler-demo.sh

# Interactive mode (waits for MCP client)
./run-profiler-demo.sh --interactive
```

**Features:**
- JFR-based profiling (CPU, allocation, locks, I/O)
- Realistic workload generators
- Flame graph generation
- Output: `./profiler-demo-output/`
- Port: 9080

### 4. `./scripts/run-remote-proxy-from-maven.sh` (Recommended)
**Standalone JDWP proxy from released Maven artifact**

```bash
# Defaults (JDWP localhost:5005, MCP port 9090)
./scripts/run-remote-proxy-from-maven.sh

# Explicit target
./scripts/run-remote-proxy-from-maven.sh --jdwp-host localhost --jdwp-port 5005
./scripts/run-remote-proxy-from-maven.sh --jdwp-host staging.example.com --jdwp-port 5005 --mcp-port 9090

# Auto-discovery
./scripts/run-remote-proxy-from-maven.sh --auto-discover --process-pattern "myapp"
./scripts/run-remote-proxy-from-maven.sh --auto-discover
```

### 5. `./scripts/run-remote-proxy.sh` (Local Source Fallback)
**Standalone JDWP proxy built from the current workspace**

```bash
# Defaults (JDWP localhost:5005, MCP port 9090)
./scripts/run-remote-proxy.sh

# Optional logging to console + file
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp" --log-file logs/descartes-proxy.log
```

**Features:**
- Registers only JDWP-compatible tools (`debugger_*`, `thread_analyzer`, `object_inspector`)
- No Descartes dependency inside the target JVM
- JDWP target must be started with `-agentlib:jdwp=...`
- Proxy MCP port defaults to 9090

## Alternative: Maven Commands

If you prefer Maven profiles (source workspace only):

```bash
# SimpleMCPServerExample (no agent, limited tooling)
mvn exec:java

# SimpleMCPServerExample (with hot reload)
mvn compile exec:exec -Prun-with-agent

# Remote debug proxy
mvn exec:java -Prun-remote-proxy \
  -Ddescartes.jdwp.host=localhost \
  -Ddescartes.jdwp.port=5005 \
  -Ddescartes.mcp.port=9090
```

**Note**:
1. For released versions, prefer `run-remote-proxy-from-maven.sh`.
2. For local development against uncommitted changes, use `run-remote-proxy.sh`.
3. Maven profiles are mainly for development workflows.
4. `run-remote-proxy-from-maven.sh` uses `pom.xml` version by default; use `--version <version>` to pin.

## Troubleshooting

### JAR not found
If `run-remote-proxy.sh` reports "JAR file not found", it will automatically run `mvn clean package -DskipTests` to build it.

### Port already in use
If port 9080 or 9090 is occupied:

```bash
# Check what's using the port
lsof -i :9080
lsof -i :9090

# Kill the process
kill -9 <PID>
```

### JDK 17+ Issues
The debugger script automatically includes:
- `-XX:+EnableDynamicAgentLoading`
- `-Xshare:off`
- `--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED`
- `--add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED`
- `--add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED`

These are **required** for JDK 17+ to use the debugger tools.

## Stopping the Server

- **Interactive mode**: Press Enter
- **Continuous mode**: Press Ctrl+C
- **Background process**: `kill <PID>` or `pkill -f "SimpleMCPServerExample"`

## Next Steps

1. Start a server using one of the scripts above
2. Connect your MCP client (Claude Code, custom client, etc.) to `localhost:9080` (embedded) or `localhost:9090` (proxy)
3. Use the available tools through the MCP protocol

For detailed tool documentation, see:
- [tools.md](tools.md) - Complete tool reference
- [README.md](../README.md) - Project documentation
- [../AGENTS.md](../AGENTS.md) - Canonical AI assistant guidance
