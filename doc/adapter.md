# MCP TCP Adapter

`config/mcp/mcp-tcp-adapter.js` bridges Descartes to MCP clients that expect to launch an executable (Claude Desktop, Cursor, etc.). It holds the TCP connection to your running MCP server, retries aggressively, and queues messages while offline.

## Capabilities

- **Connection resilience** — Infinite retry with exponential backoff (1s → 30s) plus jitter to avoid synchronized reconnect storms.
- **Message queueing** — Requests received during downtime are buffered (FIFO, configurable size) and replayed once the server returns.
- **Protocol compliance** — After reconnecting, the adapter sends `notifications/tools/list_changed` and `notifications/resources/list_changed` so clients rediscover capabilities.
- **Health monitoring** — Periodic pings and TCP keep-alives detect stale sockets and trigger reconnects.
- **Graceful errors** — Outstanding `initialize` calls time out after 30s instead of hanging forever.

## Configuration

All settings are environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `MCP_HOST` | `localhost` | Host running the Descartes MCP server |
| `MCP_PORT` | `9080` | TCP port exposed by `MCPServer` |
| `MCP_DEBUG` | `false` | Enable verbose logging |
| `MCP_RECONNECT_MIN_DELAY` | `1000` | Minimum backoff (ms) |
| `MCP_RECONNECT_MAX_DELAY` | `30000` | Maximum backoff (ms) |
| `MCP_MESSAGE_QUEUE_SIZE` | `100` | Max pending requests while offline |
| `MCP_HEALTH_CHECK_INTERVAL` | `30000` | Interval between keep-alive pings (ms) |

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

### Claude Desktop integration

`config/mcp/mcpservers.json` demonstrates how to wire the adapter up:

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

Update the absolute path and copy the JSON file into your Claude configuration directory.

## Testing & Diagnostics

- `config/mcp/test-adapter-robustness.sh` exercises cold starts, restarts, and prolonged outages.
- `config/mcp/test-improved-adapter.sh` verifies reconnection flows.
- `config/mcp/test-mcp-handshake.js` validates MCP handshake compliance.

Run the scripts after making adapter changes, or when tuning reconnection settings.

## Operational Notes

- The adapter never exits because of connection failures; you must stop it manually.
- When `initialize` is pending, the adapter collapses each retry interval to 500 ms for the first 10 attempts to speed up IDE bootstrapping.
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
- Health check activity
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
| Reconnection Delay | Fixed 2s | Exponential 1s-30s |
| Process Exit on Failure | Yes | Never |
| Message Queuing | No | Yes (100 messages) |
| Health Monitoring | No | Yes (30s interval) |
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
8. **Reliable**: Health checks ensure connection integrity
