# Descartes MCP

Descartes MCP lets Claude Code or Codex debug a live JVM through MCP.

This README focuses on the simplest first run:
1. Add the MCP server in Claude/Codex.
2. Enable the repository debug skill.
3. Start the Descartes proxy script in a separate terminal.
4. Start your app in JVM debug mode (JDWP).
5. Let the agent debug.

> Security: only run this in trusted dev environments. JDWP/debug tools can execute powerful operations.

## Easiest Start: Proxy Mode (Debugger First)

Proxy mode is the fastest onboarding path because you do not need to embed Descartes in your app.

Available tools in this mode:
- `debugger_*`
- `thread_analyzer`
- `object_inspector`

## Prerequisites

1. JDK 17+.
2. Node.js.
3. Ports `5005` (JDWP) and `9090` (MCP proxy), or your own alternatives.
4. Your Java app can be started with `-agentlib:jdwp=...`.

## Step 1: Build the Descartes JAR

From repository root:

```bash
mvn clean package -DskipTests
```

## Step 2: Add Descartes MCP to Codex or Claude

Use the Node adapter: `config/mcp/mcp-tcp-adapter.js`.

### Codex

```bash
codex mcp add descartes-proxy \
  --env MCP_HOST=localhost \
  --env MCP_PORT=9090 \
  --env MCP_DEBUG=false \
  -- node /absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js
```

Verify:

```bash
codex mcp list
codex mcp get descartes-proxy
```

### Claude Code

Create/update `.mcp.json` (or your Claude MCP config) with:

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
        "MCP_DEBUG": "false"
      }
    }
  }
}
```

Use an absolute path for `mcp-tcp-adapter.js`.

## Step 2b: Enable the Debug Skill (Recommended)

This repository includes a runtime-accurate debugger skill at:

`./.claude/skills/debug`

- Claude Code: uses repo-local `.claude/skills/` directly.
- Codex CLI: run `.claude/skills/debug/scripts/install-codex-link.sh`, then restart Codex CLI.

For full setup (copy to another project, preflight, symlink, custom skill names), use:

- `doc/debug-skill.md`

## Step 3: Check JAR Path in `scripts/run-remote-proxy.sh`

The proxy script loads:

```bash
target/descartes-mcp-*-jar-with-dependencies.jar
```

When it starts, it prints `Using JAR: ...`.
If your JAR is elsewhere, update the `MAIN_JAR` lookup in `scripts/run-remote-proxy.sh`.

## Step 4: Start the Proxy (Terminal 1)

Keep this terminal running:

```bash
./scripts/run-remote-proxy.sh \
  --jdwp-host localhost \
  --jdwp-port 5005 \
  --mcp-port 9090 \
  --log-file logs/descartes-proxy.log
```

The proxy listens for MCP clients on `9090`.

## Step 5: Start Your App in Debug Mode (Terminal 2)

Launch your app with JDWP enabled:

```bash
java \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
  -jar your-app.jar
```

For agent-driven workflows, launch the target through the non-TTY managed wrapper:

```bash
scripts/launch-managed-nontty.sh \
  --name myapp-debug-target \
  -- java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-app.jar
```

If you start via Maven/Gradle/IDE, make sure the final JVM command includes the same JDWP flag.

## Step 6: Ask the Agent to Debug

From Claude/Codex:
1. Ask your agent to debug a certain situation
2. Ask it to follow the repository debug skill (`.claude/skills/debug`)
3. Watch your agent debug your situation

That is the full first-run workflow.

## Stop Cleanly

1. Stop/pause MCP client usage in Claude/Codex.
2. Stop proxy with `Ctrl+C` in Terminal 1.
3. Stop the target app in Terminal 2.

## Troubleshooting

### Adapter cannot connect
- Confirm proxy is running.
- Confirm client `MCP_PORT` matches proxy `--mcp-port` (default `9090`).

### Proxy cannot find target JVM
- Confirm app started with `-agentlib:jdwp=...`.
- Confirm `--jdwp-host` and `--jdwp-port` match the target.

### `debugger_session` start/stop race or timeout
- Manual `debugger_session start` and `debugger_session stop` now pause proxy auto-reconnect while the manual call runs.
- If a `debugger_session start` request times out at MCP level, the in-flight start is cancelled and session state is forced back to `CLOSED`.
- Retry `debugger_session start` after confirming the target JVM is listening on the expected JDWP host/port.

### Port already in use
- Pick different ports, for example:
```bash
./scripts/run-remote-proxy.sh --jdwp-port 5006 --mcp-port 9091
```
- Then set client `MCP_PORT=9091`.

## Need Full Descartes Tooling?

Proxy mode is debugger-focused. For JShell, profiler, hot reload, and full observability in your own application, embed Descartes directly.

Start here:
- `doc/how-to-embed.md`

Related docs:
- `doc/quick-start.md`
- `doc/debugger.md`
- `doc/adapter.md`
- `doc/debug-skill.md`
