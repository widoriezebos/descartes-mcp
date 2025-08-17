# MCP TCP Adapter - Robustness Improvements

### Features

1. **Queued Initialization**:
   - `initialize` requests are queued when server is unavailable (no immediate error)
   - Placed at front of message queue for priority processing
   - Sent immediately when connection is established

2. **Aggressive Reconnection for Initialize**:
   - When an `initialize` request is waiting, adapter uses minimal reconnect delay (500ms)
   - Quickly attempts connection for first 10 attempts
   - Returns to normal exponential backoff after initial attempts

3. **Timeout Protection**:
   - `initialize` requests timeout after 30 seconds if server remains unavailable
   - Prevents indefinite hanging while allowing ample time for server startup
   - Returns appropriate error after timeout

4. **Common Scenarios Now Handled**:
   - ✅ Start Claude Code → Start MCP server later → Connection established automatically
   - ✅ MCP server crashes → Restart server → Connection restored without manual intervention
   - ✅ Server slow to start → Adapter waits patiently → Initialize succeeds
   - ✅ Server never starts → Timeout after 30 seconds → Clean error returned

## Overview
The MCP TCP adapter has been designed to handle network issues, server restarts, and connection failures gracefully. The adapter provides enterprise-grade reliability with automatic reconnection, message queuing, health monitoring, and proper MCP protocol compliance for reconnection scenarios.

## Key Features

### 1. Infinite Reconnection with Exponential Backoff
- **Continuous Retry**: The adapter will never give up trying to connect to the server
- **Exponential Backoff**: Reconnection delays start at 1 second and double up to 30 seconds
- **Jitter**: Random jitter (±20%) prevents thundering herd problems
- **Auto-reset**: Delay resets to minimum after successful connection

### 2. Message Queuing
- **Offline Queuing**: Messages received while disconnected are queued for later delivery
- **Queue Management**: FIFO queue with configurable size limit (default: 100 messages)
- **Automatic Replay**: Queued messages are sent immediately upon reconnection
- **Smart Handling**: Critical messages like 'initialize' return errors immediately

### 3. Connection State Management
- **Three States**: DISCONNECTED, CONNECTING, CONNECTED
- **No Process Exit**: Adapter never exits due to connection failures
- **Graceful Degradation**: Continues operating even when server is unavailable
- **Clear Logging**: Connection state changes are logged clearly

### 4. Health Monitoring
- **Periodic Health Checks**: Sends ping messages every 30 seconds (configurable)
- **Dead Connection Detection**: Detects and recovers from stale connections
- **TCP Keep-Alive**: Uses TCP keep-alive packets every 10 seconds
- **Activity Tracking**: Monitors all network activity to optimize health checks

### 5. Enhanced Error Handling
- **Proper JSON-RPC Errors**: Returns appropriate error codes for connection issues
- **Uncaught Exception Handling**: Prevents process crashes from unexpected errors
- **Detailed Logging**: Debug mode provides comprehensive connection diagnostics
- **Error Recovery**: Attempts to recover from all error conditions

### 6. MCP Protocol Compliance for Reconnections
- **Connection State Tracking**: Distinguishes initial connections from reconnections
- **Capability Change Notifications**: Sends `notifications/tools/list_changed` and `notifications/resources/list_changed` after reconnection
- **Automatic Re-discovery**: Prompts Claude to re-query available tools and resources after connection restoration
- **Seamless Recovery**: Maintains MCP tool availability across server restarts

## Configuration

The adapter can be configured using environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `MCP_HOST` | `localhost` | MCP server hostname |
| `MCP_PORT` | `9080` | MCP server port |
| `MCP_DEBUG` | `false` | Enable debug logging |
| `MCP_RECONNECT_MIN_DELAY` | `1000` | Minimum reconnection delay (ms) |
| `MCP_RECONNECT_MAX_DELAY` | `30000` | Maximum reconnection delay (ms) |
| `MCP_MESSAGE_QUEUE_SIZE` | `100` | Maximum queued messages |
| `MCP_HEALTH_CHECK_INTERVAL` | `30000` | Health check interval (ms) |

## Usage Examples

### Basic Usage
```bash
node mcp-tcp-adapter.js
```

### With Debug Logging
```bash
MCP_DEBUG=true node mcp-tcp-adapter.js
```

### Custom Configuration
```bash
MCP_HOST=remote-server \
MCP_PORT=8080 \
MCP_RECONNECT_MAX_DELAY=60000 \
node mcp-tcp-adapter.js
```

### In MCP Configuration (mcpservers.json)
```json
{
    "mcpServers": {
        "morpheus": {
            "command": "node",
            "args": ["/path/to/mcp-tcp-adapter.js"],
            "env": {
                "MCP_HOST": "localhost",
                "MCP_PORT": "9080",
                "MCP_DEBUG": "false",
                "MCP_RECONNECT_MIN_DELAY": "1000",
                "MCP_RECONNECT_MAX_DELAY": "30000",
                "MCP_MESSAGE_QUEUE_SIZE": "100",
                "MCP_HEALTH_CHECK_INTERVAL": "30000"
            }
        }
    }
}
```

## Testing

A comprehensive test script is provided to verify the adapter's robustness:

```bash
./test-adapter-robustness.sh
```

The test script validates:
- Starting without a server
- Connecting when server becomes available
- Surviving server restarts
- Handling rapid server restarts
- Recovering from extended disconnections

## Behavior Scenarios

### Scenario 1: Server Not Running at Startup
- Adapter starts and attempts connection
- Retries with exponential backoff (1s, 2s, 4s, 8s, 16s, 30s, 30s...)
- Queues any incoming messages
- Connects immediately when server starts

### Scenario 2: Server Restart
- Adapter detects connection loss
- Begins reconnection attempts with exponential backoff
- Queues messages received during downtime
- Reconnects and replays queued messages when server returns

### Scenario 3: Network Interruption
- TCP keep-alive detects network issues
- Health check fails after timeout
- Connection is destroyed and reconnection begins
- Recovers automatically when network is restored

### Scenario 4: Stale Connection
- Health check detects no activity for 30 seconds
- Sends ping message to verify connection
- If no response within 5 seconds, destroys connection
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