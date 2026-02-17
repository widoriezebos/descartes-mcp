# Descartes Quick Start (Step-by-Step)

This runbook is for a generic remote debugging workflow with any JVM that exposes JDWP.

**Proxy mode is the easiest way to debug a Java process without adding the Descartes dependency to the application you are debugging.**

## 0. Prerequisites

You need:

1. JDK 17+ on the machine running the proxy.
2. Node.js on the machine running command-based MCP clients (Claude Code, Cursor, etc.).
3. A target JVM that you can start with JDWP enabled.
4. Free ports:
   - `5005` for JDWP (or another port you choose)
   - `9090` for the Descartes proxy MCP server (or another port you choose)

## 1. Build Descartes MCP (once)

From the `descartes-mcp` repository root:

```bash
mvn clean package -DskipTests
```

## 2. Start the target Java app in debug mode (Terminal 1)

Use JDWP with suspend so you can break before app startup logic runs:

```bash
java \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  -jar your-app.jar
```

If your app is started another way, make sure the final JVM command still includes JDWP.

## 3. Start the Descartes proxy (Terminal 2)

From `descartes-mcp` root, run one command and keep it running.

### Option A: Explicit host/port (most predictable)

```bash
./scripts/run-remote-proxy.sh \
  --jdwp-host localhost \
  --jdwp-port 5005 \
  --mcp-port 9090 \
  --log-file logs/descartes-proxy.log
```

### Option B: Auto-discover local JDWP process

```bash
./scripts/run-remote-proxy.sh \
  --auto-discover \
  --process-pattern "your-app-name" \
  --mcp-port 9090 \
  --log-file logs/descartes-proxy.log
```

Expected result:

1. Console output appears in Terminal 2.
2. The same output is written to `logs/descartes-proxy.log`.
3. Proxy reports it is listening on MCP port `9090`.

## 4. Connect your MCP client to the proxy

Both clients below must target the proxy MCP port (`9090` in this guide).
Both use the Node adapter (`config/mcp/mcp-tcp-adapter.js`).

### A. Codex

Register the MCP server once from terminal:

```bash
codex mcp add descartes-proxy \
  --env MCP_HOST=localhost \
  --env MCP_PORT=9090 \
  --env MCP_DEBUG=false \
  --env MCP_REQUEST_TIMEOUT=130000 \
  --env MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS=5000 \
  -- node /absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js
```

Verify:

```bash
codex mcp list
codex mcp get descartes-proxy
```

For long waits (for example `debugger_events.wait timeout_ms=120000`), set the Codex client tool-call deadline in `~/.codex/config.toml`:

```toml
[mcp_servers.descartes-proxy]
tool_timeout_sec = 130
```

Optional cleanup:

```bash
codex mcp remove descartes-proxy
```

### B. Claude Code

Use repo-local `.mcp.json` in the `descartes-mcp` root:

```json
{
  "mcpServers": {
    "descartes-proxy": {
      "command": "node",
      "args": [
        "/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"
      ],
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

You must replace `/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js` with the real path on your machine.

For long waits (for example `debugger_events.wait timeout_ms=120000`), raise the Claude Code tool-call timeout in `~/.claude/settings.json`:

```json
{
  "env": {
    "MCP_TOOL_TIMEOUT": "300000"
  }
}
```

> Claude Code's default MCP tool-call timeout is 60 s (from the MCP SDK).
> `MCP_TOOL_TIMEOUT` raises it globally for all MCP servers.
> Unlike Codex CLI, Claude Code does not support per-server tool-call deadlines.

## 5. First debugger checks

Once connected, confirm remote debugging is active:

1. Start a debugger session.
2. Set a breakpoint.
3. Resume execution.
4. Wait for breakpoint hit.
5. Read local variables.

In proxy mode, available tools are debugger-focused:

- `debugger_*`
- `thread_analyzer`
- `object_inspector`

This is expected. Full JShell/profiler/hot-reload tools require embedded mode.

## 5a. Enable the Debug Skill (Recommended)

The repository debug skill lives at `./.claude/skills/debug`.

If your active workspace is this repository:

```bash
.claude/skills/debug/scripts/install-codex-link.sh
```

Claude Code uses repo-local `.claude/skills/` directly.  
For Codex CLI, run the command above and restart Codex CLI.

If your active workspace is a different repository:
1. Copy `.claude/skills/debug` into that repository.
2. Copy `scripts/launch-managed-nontty.sh` into that repository, or set `DESCARTES_LAUNCH_SCRIPT`.
3. Run `.claude/skills/debug/scripts/preflight.sh` there.
4. For Codex CLI, run `.claude/skills/debug/scripts/install-codex-link.sh` there and restart Codex.

For copy/symlink/rename details, see [debug-skill.md](debug-skill.md).

## 6. Stop cleanly

1. Stop your MCP client connection.
2. Stop proxy with `Ctrl+C` in Terminal 2.
3. Stop target JVM in Terminal 1 (or let it continue if needed).

## 7. If something fails

### No JDWP target found

- Verify the target JVM really started with `-agentlib:jdwp=...`.
- If using auto-discovery, verify `--process-pattern` matches the real process name.

### Adapter cannot connect

- Verify proxy is still running.
- Verify `MCP_PORT` in adapter/client is the same as proxy `--mcp-port` (default `9090`).

### `VMDisconnectedException` in proxy logs

- The target JVM disconnected or exited.
- Restart the target JVM, then restart the proxy.

### Port already in use

Use a different port and keep it consistent:

```bash
./scripts/run-remote-proxy.sh --jdwp-port 5006 --mcp-port 9091 --log-file logs/descartes-proxy.log
```

Then set the adapter/client to `MCP_PORT=9091`.

## Embedded mode (full toolset)

Use embedded mode when you want the full Descartes capabilities (JShell, profiler, hot reload, monitoring):

```bash
./scripts/run-with-hotreload.sh
```

This mode requires Descartes in the target JVM runtime/classpath.
