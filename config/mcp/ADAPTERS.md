# MCP TCP Adapter (Node.js)

This guide documents the supported adapter workflow:

- `config/mcp/mcp-tcp-adapter.js` for stdin/stdout MCP clients (Claude Code, Cursor, etc.)
- `./run-remote-proxy.sh` for script-based JDWP proxy sessions
- Embedded Descartes mode for full tool coverage

Unsupported launcher variants are intentionally omitted here to avoid sandbox-related failures in agent-driven environments.

## Supported Workflows

| Workflow | Start Command | Adapter Target Port | Tool Coverage |
|----------|---------------|---------------------|---------------|
| Embedded mode | `./run-with-hotreload.sh` | `9080` | Full toolset |
| Script-based proxy mode | `./run-remote-proxy.sh ...` | `9090` (default) | `debugger_*`, `thread_analyzer`, `object_inspector` |

## 1. Start Descartes

### Embedded mode (full tools)

```bash
./run-with-hotreload.sh
```

### Proxy mode (debugger-only)

```bash
# Defaults: --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
./run-remote-proxy.sh

# Auto-discover local JDWP target
./run-remote-proxy.sh --auto-discover --process-pattern "myapp"

# Explicit target
./run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090

# Optional log capture (console + file)
./run-remote-proxy.sh --auto-discover --process-pattern "myapp" --log-file logs/descartes-proxy.log
```

## 2. Run the Adapter

```bash
# Embedded mode target
MCP_PORT=9080 node config/mcp/mcp-tcp-adapter.js

# Proxy mode target
MCP_PORT=9090 node config/mcp/mcp-tcp-adapter.js
```

## 3. Configure Claude Code

Use `config/mcp/mcpservers.json` as a template.

### Embedded mode config

```json
{
  "mcpServers": {
    "descartes": {
      "command": "node",
      "args": ["/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"],
      "env": {
        "MCP_HOST": "localhost",
        "MCP_PORT": "9080",
        "MCP_DEBUG": "false"
      }
    }
  }
}
```

### Proxy mode config

```json
{
  "mcpServers": {
    "descartes-proxy": {
      "command": "node",
      "args": ["/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"],
      "env": {
        "MCP_HOST": "localhost",
        "MCP_PORT": "9090",
        "MCP_DEBUG": "false"
      }
    }
  }
}
```

## Environment Variables

`mcp-tcp-adapter.js` supports:

| Variable | Default | Description |
|----------|---------|-------------|
| `MCP_HOST` | `localhost` | MCP server host |
| `MCP_PORT` | `9080` | MCP server port |
| `MCP_DEBUG` | `false` | Verbose adapter logs |
| `MCP_RECONNECT_MIN_DELAY` | `500` | Minimum reconnect delay (ms) |
| `MCP_RECONNECT_MAX_DELAY` | `5000` | Maximum reconnect delay (ms) |
| `MCP_MESSAGE_QUEUE_SIZE` | `100` | Offline request queue size |
| `MCP_REQUEST_TIMEOUT` | `30000` | Request timeout (ms) |
| `MCP_TCP_KEEP_ALIVE` | `10000` | TCP keep-alive interval (ms) |
| `MCP_LOG_RATE_LIMIT_WINDOW` | `60000` | Log rate-limit window (ms) |
| `MCP_LOG_RATE_LIMIT_MAX` | `10` | Max logs per rate-limit window |
| `MCP_MAX_MESSAGE_SIZE` | `10485760` | Max JSON message size in bytes |

## Testing

```bash
config/mcp/test-adapter-robustness.sh
config/mcp/test-improved-adapter.sh
```

Use these after changing reconnection, queueing, or timeout behavior.

## Troubleshooting

### `spawn node ENOENT`
- Node.js is missing from `PATH`. Install Node.js or use an absolute path to `node`.

### Adapter cannot connect
- Confirm Descartes is running and `MCP_PORT` matches the target mode (`9080` embedded, `9090` proxy).

### Proxy auto-discovery finds nothing
- Ensure the target JVM started with JDWP (`-agentlib:jdwp=...`) and that the process pattern matches.

### Sandboxed agent cannot launch local commands
- Start `run-remote-proxy.sh` manually in a separate terminal and connect the client to `localhost:9090` directly.

## See Also

- `doc/adapter.md`
- `doc/running.md`
- `README.md`
- `config/mcp/mcpservers.json`
