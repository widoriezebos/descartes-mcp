# MCP TCP Adapter (Node.js)

This guide documents the supported adapter workflow:

- `config/mcp/mcp-tcp-adapter.js` for stdin/stdout MCP clients (Claude Code, Cursor, etc.)
- `./scripts/run-remote-proxy.sh` for script-based JDWP proxy sessions
- Embedded Descartes mode for full tool coverage

Unsupported launcher variants are intentionally omitted here to avoid sandbox-related failures in agent-driven environments.

## Supported Workflows

| Workflow | Start Command | Adapter Target Port | Tool Coverage |
|----------|---------------|---------------------|---------------|
| Embedded mode | `./scripts/run-with-hotreload.sh` | `9080` | Full toolset |
| Script-based proxy mode | `./scripts/run-remote-proxy.sh ...` | `9090` (default) | `debugger_*`, `thread_analyzer`, `object_inspector` |

## 1. Start Descartes

### Embedded mode (full tools)

```bash
./scripts/run-with-hotreload.sh
```

### Proxy mode (debugger-only)

```bash
# Defaults: --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
./scripts/run-remote-proxy.sh

# Auto-discover local JDWP target
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp"

# Explicit target
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090

# Optional log capture (console + file)
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp" --log-file logs/descartes-proxy.log
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
The examples below use a long-wait profile suitable for `debugger_events.wait timeout_ms=120000`.

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
        "MCP_DEBUG": "false",
        "MCP_REQUEST_TIMEOUT": "130000",
        "MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS": "5000"
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
        "MCP_DEBUG": "false",
        "MCP_REQUEST_TIMEOUT": "130000",
        "MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS": "5000"
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
| `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS` | `5000` | Extra timeout padding for `debugger_events.wait` (ms) |
| `MCP_TCP_KEEP_ALIVE` | `10000` | TCP keep-alive interval (ms) |
| `MCP_LOG_RATE_LIMIT_WINDOW` | `60000` | Log rate-limit window (ms) |
| `MCP_LOG_RATE_LIMIT_MAX` | `10` | Max logs per rate-limit window |
| `MCP_MAX_MESSAGE_SIZE` | `10485760` | Max JSON message size in bytes |

## Timeout Alignment for Breakpoint Waits

`debugger_events wait` depends on multiple timeout layers:

1. `timeout_ms` in the tool call.
2. Adapter request timeout (`MCP_REQUEST_TIMEOUT`).
3. Adapter grace (`MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS`), auto-added for `debugger_events.wait`.
4. MCP client call deadline (Codex CLI: `tool_timeout_sec` in `~/.codex/config.toml`; Claude Code: `MCP_TOOL_TIMEOUT` in `~/.claude/settings.json`).

Example for long waits (`timeout_ms=120000`):

- Set adapter env to at least:
  - `MCP_REQUEST_TIMEOUT=130000`
  - `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS=5000`
- Set Codex CLI deadline (`~/.codex/config.toml`):

```toml
[mcp_servers.descartes-proxy]
tool_timeout_sec = 130
```

- Set Claude Code deadline (`~/.claude/settings.json`):

```json
{
  "env": {
    "MCP_TOOL_TIMEOUT": "300000"
  }
}
```

> Claude Code's default MCP tool-call timeout is 60 s.
> `MCP_TOOL_TIMEOUT` raises it globally for all MCP servers.

## Testing

```bash
mvn -Dtest=McpTcpAdapterNodeScriptTest test
```

Run this from the repository root after changing adapter reconnection, queueing, or timeout behavior.

## Troubleshooting

### `spawn node ENOENT`
- Node.js is missing from `PATH`. Install Node.js or use an absolute path to `node`.

### Adapter cannot connect
- Confirm Descartes is running and `MCP_PORT` matches the target mode (`9080` embedded, `9090` proxy).

### Proxy auto-discovery finds nothing
- Ensure the target JVM started with JDWP (`-agentlib:jdwp=...`) and that the process pattern matches.

### Sandboxed agent cannot launch local commands
- Start `scripts/run-remote-proxy.sh` manually in a separate terminal and connect the client to `localhost:9090` directly.

## See Also

- `doc/adapter.md`
- `doc/running.md`
- `README.md`
- `config/mcp/mcpservers.json`
