# Running Descartes MCP Examples

This guide shows all the ways to run Descartes MCP examples for debugging and testing.

## Quick Start

### Using Shell Scripts (Easiest)

```bash
# 1. Full server with hot reload & all tools
./scripts/run-with-hotreload.sh                 # Interactive (press Enter to stop)
./scripts/run-with-hotreload.sh --continuous    # Background mode

# 2. Debugger workflow demo
mvn exec:java -Pdebugger-demo                              # Automated scenarios
mvn exec:java -Pdebugger-demo -Dexec.args="--interactive"  # Waits for MCP client

# 3. Profiler workflow demo
./run-profiler-demo.sh                  # Automated scenarios
./run-profiler-demo.sh --interactive    # Waits for MCP client

# 4. Remote debugger proxy (JDWP target required, recommended default)
./scripts/run-remote-proxy-from-maven.sh --jdwp-host localhost --jdwp-port 5005

# Local source fallback
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

### Using Maven Profiles

```bash
# SimpleMCPServerExample (no agent, limited tooling)
mvn exec:java

# SimpleMCPServerExample with hot reload (requires shaded JAR)
mvn compile exec:exec -Prun-with-agent

# Debugger Workflow Example
mvn exec:java -Pdebugger-demo
mvn exec:java -Pdebugger-demo -Dexec.args="--interactive"

# Profiler Workflow Example
mvn exec:java -Pprofiler-demo
mvn exec:java -Pprofiler-demo -Dexec.args="--interactive"

# Remote debug proxy (debugger-only)
mvn exec:java -Prun-remote-proxy \
  -Ddescartes.jdwp.host=localhost \
  -Ddescartes.jdwp.port=5005 \
  -Ddescartes.mcp.port=9090
```

## Detailed Examples

### 1. SimpleMCPServerExample (Default)

**What it demonstrates:**
- Complete MCP server setup with all tools
- Complete debugger toolset
- Complete profiler toolset
- All monitoring tools
- All resources (classpath, metrics, thread dumps, etc.)

**Launch methods:**

```bash
# Method 1: Default Maven execution
mvn exec:java

# Method 2: With hot reload agent
mvn compile exec:exec -Prun-with-agent

# Method 3: Direct JAR execution (after mvn package)
./scripts/run-with-hotreload.sh

# Method 4: Specify main class explicitly
mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.SimpleMCPServerExample"
```

**Server details:**
- Port: 9080
- Mode: Auto-detects (interactive in terminal, continuous in IDE)
- Context: Includes sample data objects for JShell testing

### 2. DebuggerWorkflowExample

**What it demonstrates:**
- Complete debugger toolset through realistic scenarios
- Basic debugging (stepping, variables, expressions)
- Bug hunting (6 intentional bugs to find)
- Complex data structures (nested objects, collections)
- Concurrency (threads, deadlocks, race conditions)
- Exceptions (NPE, chaining, custom exceptions)
- Call stacks (recursion, deep chains)

**Launch methods:**

```bash
# Method 1: Maven profile (recommended)
mvn exec:java -Pdebugger-demo                              # Automated
mvn exec:java -Pdebugger-demo -Dexec.args="--interactive" # Interactive

# Method 2: Full main class specification
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample" \
  -Dexec.args="--interactive"
```

**Server details:**
- Port: 9080
- Automated mode: Runs all scenarios then exits
- Interactive mode: Server stays running for manual debugging
- Output: ./debugger-demo-output/
- Documentation: See `src/main/java/com/bitsapplied/descartes/example/debugger/README.md`

### 3. ProfilerWorkflowExample

**What it demonstrates:**
- JFR-based performance profiling
- CPU profiling (find computation bottlenecks)
- Allocation profiling (memory leak investigation)
- Comprehensive profiling (CPU, memory, locks, I/O, GC)
- Interactive flame graph generation
- Realistic workloads (computation, allocation, concurrency, I/O)

**Launch methods:**

```bash
# Method 1: Shell script (recommended)
./run-profiler-demo.sh              # Automated demo mode
./run-profiler-demo.sh --interactive # Interactive mode

# Method 2: Maven profile
mvn exec:java -Pprofiler-demo                              # Automated
mvn exec:java -Pprofiler-demo -Dexec.args="--interactive" # Interactive

# Method 3: Full main class specification
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample" \
  -Dexec.args="--interactive"
```

**Server details:**
- Port: 9080
- Requirements: JDK 11+ for JFR support
- Automated mode: Runs all profiling scenarios then exits
- Interactive mode: Server stays running for manual profiling
- Output: ./profiler-demo-output/
- Documentation: See `src/main/java/com/bitsapplied/descartes/example/profiler/README.md`

## Mode Comparison

### Automated Demo Mode (Default)

**Best for:**
- Learning what scenarios are available
- Seeing example output
- Understanding the workflow
- Quick demonstrations

**Behavior:**
- Runs all scenarios sequentially
- Prints explanatory output
- Exits when complete
- Great for understanding capabilities

### Interactive Mode (--interactive flag)

**Best for:**
- Hands-on debugging practice
- Testing MCP tools manually
- Preparing for real debugging sessions
- Agent-driven debugging workflows

**Behavior:**
- Starts MCP server on port 9080
- Waits for MCP client connections
- Scenarios available via context map
- Press Enter to stop (or Ctrl+C)

## For Claude/AI Agents

When you want to test or debug using Descartes, use the **Descartes debug skill** (recommended) or **launch in interactive mode** manually:

```bash
# Launch debugger demo for agent access
mvn exec:java -Pdebugger-demo -Dexec.args="--interactive"

# OR launch profiler demo for agent access
./run-profiler-demo.sh --interactive

# OR launch full server with all tools
./scripts/run-with-hotreload.sh --continuous
```

With the checked-in skill and client configuration, Claude Code, Codex, and Gemini CLI can build, launch, and connect automatically—no separate skill installation is required. See [debug-skill.md](debug-skill.md).

Embedded sessions listen on `localhost:9080`; the remote proxy listens on `localhost:9090` by default. Connect your MCP client accordingly and use the registered tools.

## Connecting MCP Clients

Embedded examples expose MCP on **port 9080**. The standalone remote proxy exposes MCP on **port 9090** by default. Configure your MCP client to match the mode you started.

### Claude Code, Codex, and Gemini CLI

The checked-in `.mcp.json`, `.codex/config.toml`, and `.gemini/settings.json` configurations target proxy port `9090`. For embedded mode on port `9080`, copy the `descartes-embedded` template from `config/mcp/mcpservers.json` into the client's native project configuration.

### Custom MCP Client

Connect to `tcp://localhost:9080` (embedded) or `tcp://localhost:9090` (proxy) using MCP JSON-RPC.

## Available Tools by Example

### SimpleMCPServerExample
- **Full embedded toolset**: debugger, profiler, monitoring, JShell, hot reload, logging, and object inspection
- **All resources**: classpath, system properties, metrics, thread dumps, MBeans, context

### DebuggerWorkflowExample
- **Debugger-focused toolset**: debugger events and operations plus monitoring, inspection, JShell, logging, and hot reload
- **Scenarios**: Available in context map (basicScenarios, buggyCalculator, dataScenarios, etc.)

### ProfilerWorkflowExample
- **Profiler tools only**: start, stop, hotspots, call_tree, list, export
- **Workloads**: Available in context map (computationWorkload, allocationWorkload, etc.)

## Troubleshooting

### Port already in use

```bash
# Find what's using the ports
lsof -i :9080
lsof -i :9090

# Kill the process
kill <PID>

# Or choose a different port (edit source code)
```

### Shell scripts not executable

```bash
chmod +x run-profiler-demo.sh
chmod +x scripts/run-with-hotreload.sh
```

### Maven commands fail

Ensure you've built the project first:

```bash
mvn clean compile
# Or for full build
mvn clean package
```

### JFR not available (ProfilerWorkflowExample)

Profiler requires JDK 11+ for JFR support. Check your Java version:

```bash
java -version
```

### Hot reload not working

Ensure you're running with the agent:

```bash
# Use the run-with-agent profile
mvn compile exec:exec -Prun-with-agent

# OR use the shell script
./scripts/run-with-hotreload.sh
```

## Summary

| Example | Shell Script | Maven Profile | Best For |
|---------|--------------|---------------|----------|
| SimpleMCPServerExample | `mvn exec:java` | (default) | Full server with all tools |
| SimpleMCPServerExample (hot reload) | `./scripts/run-with-hotreload.sh` | `-Prun-with-agent` | Development with hot reload |
| DebuggerWorkflowExample | `mvn exec:java -Pdebugger-demo` | `-Pdebugger-demo` | Learning debugger tools |
| ProfilerWorkflowExample | `./run-profiler-demo.sh` | `-Pprofiler-demo` | Learning profiler tools |

All examples support `--interactive` mode for hands-on testing!
