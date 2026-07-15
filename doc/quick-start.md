# Descartes Quick Start (Step-by-Step)

This runbook is for a generic remote debugging workflow with any JVM that exposes JDWP.

**Proxy mode is the easiest way to debug a Java process without adding the Descartes dependency to the application you are debugging.**

## 0. Prerequisites

You need:

1. JDK 17+ on the machine running the proxy.
2. Node.js on the machine running command-based MCP clients (Claude Code, Codex, Gemini CLI, etc.).
3. A target JVM that you can start with JDWP enabled.
4. Free ports:
   - `5005` for JDWP (or another port you choose)
   - `9090` for the Descartes proxy MCP server (or another port you choose)

## 1. Choose Proxy Source

Recommended default (released artifact): use `scripts/run-remote-proxy-from-maven.sh`.
No local `mvn package` is required for proxy mode.
By default it uses the version in `pom.xml`; pass `--version <version>` to pin a specific released artifact.

If you are developing Descartes itself and want to run local source changes, use `scripts/run-remote-proxy.sh` (it auto-builds).

## 2. Start the target Java app in debug mode (Terminal 1)

Use JDWP with suspend so you can break before app startup logic runs:

```bash
java \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  -jar your-app.jar
```

If your app is started another way, make sure the final JVM command still includes JDWP.

For JDK 21+ applications where you need virtual threads to appear in `debugger_threads` / thread-list snapshots, append `includevirtualthreads=y`:

```bash
java \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005,includevirtualthreads=y \
  -jar your-app.jar
```

## 3. Start the Descartes proxy (Terminal 2)

From `descartes-mcp` root, run one command and keep it running.

### Option A: Released proxy artifact (recommended)

```bash
./scripts/run-remote-proxy-from-maven.sh \
  --jdwp-host localhost \
  --jdwp-port 5005 \
  --mcp-port 9090
```

### Option B: Released proxy artifact with auto-discovery

```bash
./scripts/run-remote-proxy-from-maven.sh \
  --auto-discover \
  --process-pattern "your-app-name" \
  --mcp-port 9090
```

### Option C: Local source build (development fallback)

```bash
./scripts/run-remote-proxy.sh \
  --jdwp-host localhost \
  --jdwp-port 5005 \
  --mcp-port 9090 \
  --log-file logs/descartes-proxy.log
```

Expected result:

1. Console output appears in Terminal 2.
2. (Optional) mirror output to a log file with `mkdir -p logs && ./scripts/run-remote-proxy-from-maven.sh ... | tee logs/descartes-proxy.log`.
3. Proxy reports it is listening on MCP port `9090`.

## 4. Connect your MCP client to the proxy

All supported clients use the Node adapter at `config/mcp/mcp-tcp-adapter.js` and target proxy MCP port `9090`. Start the client from this repository so its project configuration is loaded:

| Client | Checked-in configuration | Verify |
| --- | --- | --- |
| Claude Code | `.mcp.json` | `/mcp` |
| Codex | `.codex/config.toml` | `codex mcp get descartes-proxy` |
| Gemini CLI | `.gemini/settings.json` | `gemini mcp list` |

The configurations share these long-wait budgets:

- Adapter default tool budget: `MCP_TOOL_TIMEOUT_MS=120000`
- Adapter request budget: `MCP_REQUEST_TIMEOUT=130000`
- Breakpoint-wait grace: `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS=5000`
- Codex client deadline: `tool_timeout_sec=130`
- Claude per-server deadline: `.mcp.json` `timeout=130000` ms
- Gemini MCP timeout: `130000` ms

When copying Descartes integration into another project, copy the relevant client configuration and adjust the adapter path or working directory for that project's layout.

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

## 5a. Use the Descartes Debug Skill (Recommended)

The single physical skill lives at `./.agents/skills/descartes-debug`.

- Codex and Gemini discover it directly.
- Claude Code follows the checked-in `.claude/skills/descartes-debug` symlink.
- No user-level installation or copied client-specific skill tree is required.

Validate it with:

```bash
.agents/skills/descartes-debug/scripts/preflight.sh
```

For the portable layout and copy instructions, see [debug-skill.md](debug-skill.md).

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
- Restart the target JVM. The proxy keeps retrying and reconnects when JDWP becomes available again.
- Restart the proxy only if its health status does not recover after the target is listening.

### Port already in use

Use a different port and keep it consistent:

```bash
./scripts/run-remote-proxy-from-maven.sh --jdwp-port 5006 --mcp-port 9091
```

Then set the adapter/client to `MCP_PORT=9091`.

## Embedded mode (full toolset)

Use embedded mode when you want the full Descartes capabilities (JShell, profiler, hot reload, monitoring):

```bash
./scripts/run-with-hotreload.sh
```

This mode requires Descartes in the target JVM runtime/classpath.
