# MCP TCP Adapter

`config/mcp/mcp-tcp-adapter.js` bridges Descartes to MCP clients that expect to launch an executable (Claude Code, Codex, Gemini CLI, Cursor, etc.). It holds the TCP connection to your running MCP server, retries aggressively, and queues messages while offline.

## Capabilities

- **Connection resilience** — Infinite retry with exponential backoff (500 ms → 5 s by default) plus jitter to avoid synchronized reconnect storms.
- **Message queueing** — Requests received during downtime are buffered (FIFO, configurable size) and replayed once the server returns.
- **Protocol compliance** — After reconnecting, the adapter sends `notifications/tools/list_changed` and `notifications/resources/list_changed` so clients rediscover capabilities.
- **Socket liveness** — TCP keep-alive plus socket close/error handling detects broken connections and triggers reconnects.
- **Graceful errors** — Outstanding `initialize` calls time out after 30s instead of hanging forever.
- **Debugger wait-aware timeouts** — `tools/call` requests for `debugger_events` `operation=wait` automatically get a padded adapter timeout (`timeout_ms` + grace) so expected "no breakpoint yet" waits return tool results instead of transport errors.

## Configuration

All settings are environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `MCP_HOST` | `localhost` | Host running the Descartes MCP server |
| `MCP_PORT` | `9080` | TCP port exposed by `MCPServer` |
| `MCP_DEBUG` | `false` | Enable verbose logging |
| `MCP_RECONNECT_MIN_DELAY` | `500` | Minimum backoff (ms) |
| `MCP_RECONNECT_MAX_DELAY` | `5000` | Maximum backoff (ms) |
| `MCP_MESSAGE_QUEUE_SIZE` | `100` | Max pending requests while offline |
| `MCP_REQUEST_TIMEOUT` | `30000` | Base adapter request timeout (ms) |
| `MCP_TOOL_TIMEOUT_MS` | `60000` | Default timeout injected into tool calls that do not supply one (ms) |
| `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS` | `5000` | Extra timeout padding (ms) added for `debugger_events.wait` |
| `MCP_TCP_KEEP_ALIVE` | `10000` | TCP keep-alive delay (ms) |
| `MCP_LOG_RATE_LIMIT_WINDOW` | `60000` | Log suppression window (ms) |
| `MCP_LOG_RATE_LIMIT_MAX` | `10` | Max identical log messages per window before suppression |
| `MCP_MAX_MESSAGE_SIZE` | `10485760` | Max message size accepted by adapter (bytes) |

## Timeout Layers for `debugger_events.wait`

Long breakpoint waits depend on four timeout layers:

1. Tool wait timeout (`timeout_ms` in `debugger_events.wait`).
2. Server execution guard (the semantic wait plus a 1000 ms completion grace).
3. Adapter request timer (the tool timeout plus `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS` for `debugger_events.wait`).
4. MCP client tool-call deadline (Codex: `tool_timeout_sec`; Claude Code or Gemini CLI: server `timeout`).

`MCP_REQUEST_TIMEOUT` is the base timer for non-tool requests and the fallback when `MCP_TOOL_TIMEOUT_MS` is unavailable; it is not a minimum for tool calls.

Recommended baseline for `timeout_ms=120000`:

- Adapter env:
  - `MCP_REQUEST_TIMEOUT=130000`
  - `MCP_TOOL_TIMEOUT_MS=120000`
  - `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS=5000`
- Codex (`.codex/config.toml` in this repository):

```toml
[mcp_servers.descartes-proxy]
tool_timeout_sec = 130
```

- Claude Code (`.mcp.json` in this repository):

```json
{
  "mcpServers": {
    "descartes-proxy": {
      "timeout": 130000
    }
  }
}
```

- Gemini CLI (`.gemini/settings.json` in this repository): set the `descartes-proxy` MCP server's `timeout` to `130000` ms.

> Claude Code also supports the global `MCP_TOOL_TIMEOUT`, but the per-server field avoids changing unrelated MCP servers.

## Usage

### Direct invocation

```bash
node config/mcp/mcp-tcp-adapter.js
```

Enable debug logging:

```bash
MCP_DEBUG=true node config/mcp/mcp-tcp-adapter.js
```

Point at a remote host:

```bash
MCP_HOST=example.internal MCP_PORT=10090 node config/mcp/mcp-tcp-adapter.js
```

### Client integration

This repository includes `.mcp.json` for Claude Code, `.codex/config.toml` for Codex, and `.gemini/settings.json` for Gemini CLI. `config/mcp/mcpservers.json` is the two-mode template for other MCP clients:

```json
{
  "mcpServers": {
    "descartes": {
      "command": "node",
      "args": ["/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"],
      "env": {
        "MCP_HOST": "localhost",
        "MCP_PORT": "9080"
      }
    }
  }
}
```

For another client or project, translate the same command, adapter path, and environment into that client's native configuration format.

## Testing & Diagnostics

- Automated adapter regression tests live in
  `src/test/java/com/bitsapplied/descartes/mcp/adapter/McpTcpAdapterNodeScriptTest.java`.

Run after adapter changes:

```bash
mvn -Dtest=McpTcpAdapterNodeScriptTest test
```

## Operational Notes

- The adapter never exits because of connection failures; you must stop it manually.
- When `initialize` is pending, the adapter collapses each retry interval to 500 ms for the first 10 attempts to speed up IDE bootstrapping.
- For `debugger_events.wait`, the adapter extends its own request timeout to at least `timeout_ms + MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS`.
- Keep the adapter process close to the MCP server to minimize latency; the MCP protocol is chatty during debugging sessions.
- Initiates reconnection process

### Scenario 5: Server Overload
- Messages are queued if server is slow to respond
- Queue has size limit to prevent memory issues
- Oldest messages dropped if queue fills (FIFO)
- Critical messages return errors immediately

## Logging

The adapter provides two levels of logging:

### Info Level (Always Visible)
- Connection established/lost
- Reconnection scheduling
- Server start/stop
- Queue processing

### Debug Level (When MCP_DEBUG=true)
- All info level messages
- Detailed connection attempts
- Message send/receive
- Reconnect and socket activity
- Error details

## Migration from Old Adapter

The new adapter is backward compatible. To migrate:

1. Replace the old `mcp-tcp-adapter.js` with the new version
2. Optionally configure new environment variables
3. No changes needed to MCP server or client configuration

## Comparison with Previous Version

| Feature | Old Version | New Version |
|---------|------------|-------------|
| Reconnection Attempts | 5 max | Infinite |
| Reconnection Delay | Fixed 2s | Exponential 500ms-5s by default |
| Process Exit on Failure | Yes | Never |
| Message Queuing | No | Yes (100 messages) |
| Socket Liveness | Basic | TCP keep-alive + socket events |
| Connection States | Basic | Full state machine |
| Error Recovery | Limited | Comprehensive |
| TCP Keep-Alive | No | Yes |
| Debug Logging | Basic | Detailed |

## Benefits

1. **High Availability**: Adapter never gives up, ensuring maximum uptime
2. **Zero Message Loss**: Queuing prevents message loss during disconnections
3. **Automatic Recovery**: No manual intervention needed for connection issues
4. **Production Ready**: Handles all common failure scenarios gracefully
5. **Observable**: Clear logging makes troubleshooting easy
6. **Configurable**: All timeouts and limits can be customized
7. **Efficient**: Exponential backoff prevents server overload
8. **Reliable**: TCP keep-alive and socket events detect broken connections
