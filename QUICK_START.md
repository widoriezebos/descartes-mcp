# Descartes MCP Quick Start

## For Users (Interactive Testing)

### Option 1: Shell Scripts (Easiest) ⭐

```bash
# Full server with all tools (auto-builds & enables hot reload)
./run-with-hotreload.sh

# Debugger-only remote proxy (JDWP target required)
./run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005

# Debugger demo (all 8 debugger tools + scenarios)
./run-debugger-demo.sh --interactive

# Profiler demo (JFR profiling + workloads)
./run-profiler-demo.sh --interactive

# With hot reload support
./run-with-hotreload.sh
```

### Option 2: Maven Profiles

```bash
# Full server (no agent, limited tooling)
mvn exec:java

# Debugger demo
mvn exec:java -Pdebugger-demo -Dexec.args="--interactive"

# Profiler demo
mvn exec:java -Pprofiler-demo -Dexec.args="--interactive"

# With hot reload
mvn compile exec:exec -Prun-with-agent
```

Embedded servers default to **port 9080**; the remote proxy listens on **port 9090** unless overridden.

## For AI Agents (Automated Debugging Sessions)

### Launch Interactive Mode

```bash
# For debugging scenarios
./run-debugger-demo.sh --interactive

# For profiling scenarios
./run-profiler-demo.sh --interactive

# For full capabilities
mvn exec:java
```

### Connect via MCP

Embedded servers listen on `localhost:9080` (default); the remote proxy listens on `localhost:9090` unless you override `--mcp-port`. Use MCP tools to:
- Set breakpoints, step through code, inspect variables
- Profile CPU/memory performance, generate flame graphs
- Monitor threads, detect deadlocks, analyze exceptions

### Available Scenarios

**Debugger Demo:**
- `basicScenarios` - Stepping, variables, expressions
- `buggyCalculator` - 6 bugs to find and fix
- `dataScenarios` - Nested objects, collections
- `concurrencyScenarios` - Threads, deadlocks
- `exceptionScenarios` - NPE, chaining
- `callStackScenarios` - Recursion, deep stacks

**Profiler Demo:**
- `computationWorkload` - CPU hotspots
- `allocationWorkload` - Memory patterns
- `concurrencyWorkload` - Lock contention
- `ioWorkload` - I/O operations

## First-Time Setup

```bash
# 1. Build the project
mvn clean compile

# 2. Make scripts executable
chmod +x *.sh

# 3. Test a demo
./run-debugger-demo.sh
```

## Connect MCP Client

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "descartes": {
      "command": "node",
      "args": ["/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"]
    }
  }
}
```

### Custom Client

Connect to `tcp://localhost:9080` using MCP JSON-RPC protocol.

## Key Differences

| Example | Tools | Best For |
|---------|-------|----------|
| **SimpleMCPServerExample** | All tools (20+) | Production integration, full capabilities |
| **DebuggerWorkflowExample** | 8 debugger tools | Learning debugging, bug hunting practice |
| **ProfilerWorkflowExample** | 6 profiler tools | Learning profiling, performance analysis |

## Quick Tests

```bash
# Test debugger tools work
./run-debugger-demo.sh --interactive
# In another terminal:
# Use MCP client to call debugger_session, set breakpoints, etc.

# Test profiler tools work
./run-profiler-demo.sh --interactive
# In another terminal:
# Use MCP client to call profiler_start, profiler_hotspots, etc.
```

## More Details

- **Complete guide**: See [RUNNING_EXAMPLES.md](RUNNING_EXAMPLES.md)
- **Debugger docs**: See [src/main/java/.../debugger/README.md](src/main/java/com/bitsapplied/descartes/example/debugger/README.md)
- **Profiler docs**: See [src/main/java/.../profiler/README.md](src/main/java/com/bitsapplied/descartes/example/profiler/README.md)
- **Project overview**: See [CLAUDE.md](CLAUDE.md)
