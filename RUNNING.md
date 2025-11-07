# Running Descartes Examples

This guide explains the robust, reliable ways to run Descartes MCP examples.

## Quick Reference

All startup scripts:
- ✅ **Automatically detect** if JAR exists
- ✅ **Auto-build** if JAR is missing
- ✅ **Include all necessary JVM flags** (no manual configuration needed)
- ✅ **Work reliably** across different environments

## Available Startup Scripts

### 1. `./run-with-hotreload.sh`
**SimpleMCPServerExample with hot reload capability**

```bash
# Interactive mode (waits for Enter to stop)
./run-with-hotreload.sh

# Continuous mode (runs until killed)
./run-with-hotreload.sh --continuous
```

**Features:**
- All 28 tools available (debugger, profiler, monitoring, JShell, etc.)
- Hot class reload enabled
- Requires `-javaagent` flag (automatically included)
- Port: 9080

### 2. `./run-debugger-demo.sh`
**DebuggerWorkflowExample with AI-assisted debugging scenarios**

```bash
# Automated demo (runs all scenarios, then exits)
./run-debugger-demo.sh

# Interactive mode (waits for MCP client)
./run-debugger-demo.sh --interactive
```

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

## Alternative: Maven Commands

If you prefer Maven (less robust, may require manual JAR building):

```bash
# SimpleMCPServerExample (standard mode)
mvn exec:java

# SimpleMCPServerExample (with hot reload)
mvn compile exec:exec -Prun-with-agent
```

**Note**: The shell scripts are **recommended** because they:
1. Check for JAR existence
2. Auto-build if needed
3. Handle JAR filename resolution dynamically
4. Include all necessary JVM flags

## Troubleshooting

### JAR not found
If you see "JAR file not found", the script will automatically run `mvn clean package -DskipTests` to build it.

### Port already in use
If port 9080 is occupied:

```bash
# Check what's using the port
lsof -i :9080

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
2. Connect your MCP client (Claude Desktop, custom client, etc.) to `localhost:9080`
3. Use the available tools through the MCP protocol

For detailed tool documentation, see:
- [TOOLS.md](TOOLS.md) - Complete tool reference
- [README.md](README.md) - Project documentation
- [CLAUDE.md](CLAUDE.md) - AI assistant guidance
