# MCP TCP Adapters

Descartes MCP provides two implementations of the TCP adapter for connecting MCP clients (like Claude Code) to the Descartes MCP server:

1. **Node.js Adapter** (`mcp-tcp-adapter.js`) - Original JavaScript implementation
2. **Java Adapter** (`McpTcpAdapter`) - Pure-Java implementation, drop-in replacement

Both adapters bridge stdin/stdout JSON-RPC communication (used by MCP clients) with TCP socket connections to the Descartes server.

---

## Quick Comparison

| Feature | Node.js Adapter | Java Adapter |
|---------|----------------|--------------|
| **Runtime** | Node.js required | JDK 16+ required |
| **Startup Time** | ~50-100ms | ~200-300ms (JVM overhead) |
| **Memory Usage** | ~30-50MB | ~50-100MB |
| **Type Safety** | Dynamic typing | Strong static typing |
| **Setup** | Single .js file | Requires JAR build |
| **Features** | Reconnection, queueing, timeouts | Same features |
| **Integration** | npm ecosystem | Java ecosystem |
| **Configuration** | Environment variables | Same environment variables |

**Choose Node.js** if: You need minimal startup/memory, already have Node.js, or want simplest deployment

**Choose Java** if: You want to eliminate Node.js dependency, prefer type safety, or have Java-based projects

---

## Node.js Adapter

### Requirements

- Node.js runtime (any modern version)

### Usage

```bash
# Run directly
MCP_PORT=9080 node config/mcp/mcp-tcp-adapter.js

# Or configure in Claude Code
```

### Configuration

`mcpservers.json`:
```json
{
    "mcpServers": {
        "descartes": {
            "command": "node",
            "args": [
                "/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"
            ],
            "env": {
                "MCP_HOST": "localhost",
                "MCP_PORT": "9080",
                "MCP_DEBUG": "false"
            }
        }
    }
}
```

### Features

- ✅ Infinite reconnection with exponential backoff
- ✅ Message queueing while disconnected (FIFO, max 100 by default)
- ✅ Request timeout tracking (30s default)
- ✅ JSON-RPC 2.0 validation
- ✅ Capability refresh after reconnection
- ✅ Graceful error handling

---

## Java Adapter

### Requirements

- JDK 16+ runtime
- Maven (for building the JAR)

### Quick Start

**Step 1: Build the JAR** (first time only, or after code changes):
```bash
cd /path/to/descartes-mcp
mvn clean package -DskipTests
```

**Step 2: Run the adapter**:
```bash
# Using the convenient launcher script (auto-builds if needed)
./run-mcp-adapter.sh

# Or run Java directly
java -cp target/descartes-mcp-*-jar-with-dependencies.jar \
    com.bitsapplied.descartes.mcp.adapter.McpTcpAdapter
```

### Configuration

**Option 1: Using run-mcp-adapter.sh (recommended)**

`mcpservers-java-adapter.json`:
```json
{
    "mcpServers": {
        "descartes": {
            "command": "/absolute/path/to/descartes-mcp/run-mcp-adapter.sh",
            "env": {
                "MCP_HOST": "localhost",
                "MCP_PORT": "9080",
                "MCP_DEBUG": "false"
            }
        }
    }
}
```

**Option 2: Direct Java invocation**

```json
{
    "mcpServers": {
        "descartes": {
            "command": "java",
            "args": [
                "-cp",
                "/absolute/path/to/descartes-mcp/target/descartes-mcp-0.0.1-SNAPSHOT-jar-with-dependencies.jar",
                "com.bitsapplied.descartes.mcp.adapter.McpTcpAdapter"
            ],
            "env": {
                "MCP_HOST": "localhost",
                "MCP_PORT": "9080"
            }
        }
    }
}
```

### Features

All features from the Node.js adapter, plus:

- ✅ Type-safe implementation with records and enums
- ✅ Thread-safe concurrency with proper synchronization
- ✅ Rate-limited logging (prevents log spam)
- ✅ Comprehensive JUnit 5 test suite
- ✅ Resource cleanup with shutdown hooks

### Advantages

- **No Node.js dependency** - Runs on pure JVM
- **Type safety** - Compile-time error checking
- **Better Java integration** - Natural fit for Java projects
- **Production-tested** - Full unit test coverage

### Trade-offs

- **Higher memory** - JVM uses ~50-100MB vs ~30-50MB for Node.js
- **Slower startup** - JVM initialization adds ~100-200ms
- **Build step** - Requires `mvn package` (auto-handled by run-mcp-adapter.sh)

---

## Using with Remote Proxy Auto-Discovery

Both adapters can be used with the Descartes Remote Debug Proxy, which now supports auto-discovery of JDWP processes on the same machine.

### Quick Setup for Auto-Discovery

**Step 1: Start your application with JDWP enabled**
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-app.jar
```

**Step 2: Start the proxy with auto-discovery**
```bash
# Auto-discover by pattern
./run-proxy-adapter.sh --auto-discover --process-pattern "myapp"

# Or auto-select if only one JDWP process exists
./run-proxy-adapter.sh --auto-discover
```

The `run-proxy-adapter.sh` script combines both the remote proxy and the adapter, so you get:
- Auto-discovery of JDWP processes (no need to specify ports!)
- Stdin/stdout transport for Claude Code
- All the adapter features (reconnection, queueing, etc.)

### Configuration Examples

**Embedded mode with specific port (default 9080):**
```json
{
    "mcpServers": {
        "descartes-embedded": {
            "command": "node",
            "args": ["/path/to/mcp-tcp-adapter.js"],
            "env": {
                "MCP_PORT": "9080"
            }
        }
    }
}
```

**Proxy mode with auto-discovery (default 9090):**
```bash
# Start proxy in separate terminal
./run-remote-proxy.sh --auto-discover --process-pattern "myapp"

# Then configure adapter to connect to proxy
```

```json
{
    "mcpServers": {
        "descartes-proxy": {
            "command": "node",
            "args": ["/path/to/mcp-tcp-adapter.js"],
            "env": {
                "MCP_PORT": "9090"
            }
        }
    }
}
```

**Combined proxy + adapter with auto-discovery:**
```bash
# Use run-proxy-adapter.sh for all-in-one solution
./run-proxy-adapter.sh --auto-discover --process-pattern "morpheus"
```

### When to Use Each Approach

**Embedded Mode (Port 9080)**:
- ✅ Full tool access (JShell, hot-reload, profiling, monitoring, logging)
- ✅ Direct access to application objects via context map
- ✅ Best for local development with full control
- ❌ Requires Descartes JAR in application classpath

**Proxy Mode with Explicit Port (Port 9090)**:
- ✅ No classpath modification needed
- ✅ Debugging remote servers (staging, production)
- ✅ Works with containerized apps
- ✅ Lightweight footprint in target JVM
- ⚠️ Limited to debugger tools only (no JShell, profiling, etc.)
- ❌ Need to know and specify JDWP port

**Proxy Mode with Auto-Discovery (Port 9090)** ✨:
- ✅ All benefits of explicit proxy mode
- ✅ **No need to remember JDWP ports**
- ✅ Pattern matching for multiple applications
- ✅ Perfect for local development
- ⚠️ Only works on same machine as proxy
- ⚠️ Limited to debugger tools only

---

## Environment Variables

Both adapters use identical environment variables:

| Variable | Default | Range | Description |
|----------|---------|-------|-------------|
| `MCP_HOST` | `localhost` | - | MCP server hostname |
| `MCP_PORT` | `9080` | 1-65535 | MCP server TCP port |
| `MCP_DEBUG` | `false` | true/false | Enable debug logging |
| `MCP_RECONNECT_MIN_DELAY` | `500` | 50-300000 | Minimum reconnection delay (ms) |
| `MCP_RECONNECT_MAX_DELAY` | `5000` | 50-300000 | Maximum reconnection delay with exponential backoff (ms) |
| `MCP_MESSAGE_QUEUE_SIZE` | `100` | 1-10000 | Maximum queued messages while disconnected |
| `MCP_REQUEST_TIMEOUT` | `30000` | 1000-600000 | Request timeout duration (ms) |
| `MCP_TCP_KEEP_ALIVE` | `10000` | 1000-600000 | TCP keep-alive interval (ms) |
| `MCP_LOG_RATE_LIMIT_WINDOW` | `60000` | 1000-600000 | Rate limiting time window (ms, Java only) |
| `MCP_LOG_RATE_LIMIT_MAX` | `10` | 1-1000 | Max log messages per window (Java only) |
| `MCP_MAX_MESSAGE_SIZE` | `10485760` | 1024-104857600 | Maximum message size (bytes, 10MB default) |

### Example Configuration

```bash
export MCP_HOST=localhost
export MCP_PORT=9080
export MCP_DEBUG=true
export MCP_MESSAGE_QUEUE_SIZE=200
export MCP_REQUEST_TIMEOUT=60000

# Then run either adapter
node config/mcp/mcp-tcp-adapter.js        # Node.js
# or
./run-mcp-adapter.sh                      # Java
```

---

## Testing the Adapter

### Manual Test

**Terminal 1: Start Descartes server**
```bash
cd /path/to/descartes-mcp
mvn exec:java
```

**Terminal 2: Run adapter**
```bash
# Node.js version
MCP_DEBUG=true node config/mcp/mcp-tcp-adapter.js

# Java version
MCP_DEBUG=true ./run-mcp-adapter.sh
```

**Terminal 2: Send initialize request**
```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"0.1.0","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
```

You should see:
1. Connection established
2. Initialize request sent to server
3. Response received and returned

### Integration Test with Claude Code

1. Configure Claude Code with your chosen adapter (see examples above)
2. Restart Claude Code
3. Check logs for successful connection
4. Try using Descartes tools through Claude

---

## Troubleshooting

### Node.js Adapter

**Problem:** `Error: spawn node ENOENT`
- **Solution:** Node.js not installed or not in PATH. Install Node.js or use full path to node binary.

**Problem:** Adapter crashes or restarts frequently
- **Solution:** Check Descartes server is running and port is correct. Enable `MCP_DEBUG=true` to see detailed logs.

### Java Adapter

**Problem:** `Error: Could not find or load main class`
- **Solution:** JAR not built or path incorrect. Run `mvn clean package` and verify JAR exists in target/.

**Problem:** High memory usage
- **Solution:** This is normal for JVM. Adjust JVM heap if needed: `java -Xmx50m -cp ...`

**Problem:** Slow startup
- **Solution:** JVM startup overhead is inherent. Consider using Node.js adapter if startup time is critical.

### Both Adapters

**Problem:** Connection refused
- **Solution:** Descartes server not running. Start with `mvn exec:java` first.

**Problem:** Port already in use
- **Solution:** Change `MCP_PORT` to a different port (must match Descartes server port).

**Problem:** Messages timing out
- **Solution:** Increase `MCP_REQUEST_TIMEOUT`. Default is 30s, try 60000 (60s) for slow operations.

**Problem:** Message queue overflow
- **Solution:** Increase `MCP_MESSAGE_QUEUE_SIZE` from default 100 to 200 or higher.

---

## Architecture

### How the Adapter Works

```
┌──────────────────┐          ┌────────────────┐          ┌──────────────────┐
│  MCP Client      │          │  TCP Adapter   │          │  Descartes       │
│  (Claude Code)   │          │  (Node.js/Java)│          │  MCP Server      │
└──────────────────┘          └────────────────┘          └──────────────────┘
         │                            │                            │
         │  stdin/stdout JSON-RPC     │                            │
         │──────────────────────────► │                            │
         │                            │  TCP Socket JSON-RPC       │
         │                            │──────────────────────────► │
         │                            │                            │
         │                            │ ◄──────────────────────────│
         │ ◄──────────────────────────│                            │
         │                            │                            │
```

**Key Functions:**

1. **Protocol Bridge** - Converts between stdin/stdout and TCP socket transport
2. **Connection Management** - Handles reconnection with exponential backoff
3. **Message Queueing** - Buffers requests during disconnections
4. **Request Tracking** - Correlates responses with requests, handles timeouts
5. **Capability Sync** - Re-notifies client of server capabilities after reconnection

### Connection States

Both adapters use the same state machine:

```
DISCONNECTED ──connect()──► CONNECTING ──success──► CONNECTED
     ▲                           │                       │
     │                           │ failure               │
     │                           └───────────────────────┘
     │                                                   │
     └───────────────────────────◄reconnect()────────────┘
```

**DISCONNECTED**: Initial state, no connection
**CONNECTING**: TCP connection in progress
**CONNECTED**: Actively connected, can send/receive messages
**Reconnecting**: Automatically triggered after connection loss with exponential backoff

---

## Source Code

- **Node.js**: `config/mcp/mcp-tcp-adapter.js` (670 lines)
- **Java**: `src/main/java/com/bitsapplied/descartes/mcp/adapter/McpTcpAdapter.java` (881 lines)
- **Java Tests**: `src/test/java/com/bitsapplied/descartes/mcp/adapter/McpTcpAdapterTest.java`

Both implementations follow the same algorithms and behavior for maximum compatibility.

---

## See Also

- [doc/adapter.md](../../doc/adapter.md) - Comprehensive Node.js adapter documentation
- [README.md](../../README.md) - Main Descartes documentation
- Example configurations: `mcpservers.json` (Node.js), `mcpservers-java-adapter.json` (Java)
