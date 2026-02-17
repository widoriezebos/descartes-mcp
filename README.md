# Descartes MCP

Debug live JVMs from MCP clients like CodexCLI and Claude Code.

Descartes MCP connects directly to a running Java process over JDWP and exposes a structured debug surface: threads, stack state, variables, breakpoints, and runtime introspection.

It has two modes:

- Proxy mode for external debugging against JDWP-enabled JVMs.
- Embedded mode for full runtime introspection inside your app (JShell, profiler, hot reload, resources, and more).

---

## Why you should Use Descartes

- Debug JDWP-enabled JVMs without restarting application code.
- Investigate thread contention and lock waits through target thread state and stack snapshots (`debugger_threads`, `debugger_stacktrace`) during suspend points.
- Share a repeatable debugging workflow with AI-assisted agent tooling.
- Identify blocked/waiting thread states (`BLOCKED` / `WAITING`) quickly during incidents.

---

## 30-Second Demo

Run these three commands and ask your agent to debug.

```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
```

```bash
codex mcp add descartes-proxy \
  --env MCP_HOST=localhost \
  --env MCP_PORT=9090 \
  --env MCP_DEBUG=false \
  -- node /absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js
```

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar your-app.jar
```

Then tell your agent:  
“Use the debug skill to inspect the failure in this running app and reproduce the thread state around the exception path.”

---

## Quick Start (Minimal)

### 1) Prerequisites

JDK 17+, Node.js, and a Java process that can run with JDWP.

### 2) Build the proxy artifact once

```bash
mvn clean package -DskipTests
```

`scripts/run-remote-proxy.sh` expects `target/descartes-mcp-*-jar-with-dependencies.jar`.

### 3) Run proxy

```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
```

### 4) Register MCP adapter

```bash
codex mcp add descartes-proxy \
  --env MCP_HOST=localhost \
  --env MCP_PORT=9090 \
  --env MCP_DEBUG=false \
  -- node /absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js
```

### 5) Install debug skill

Codex CLI:

```bash
.claude/skills/debug/scripts/install-codex-link.sh
```

Then restart Codex CLI (or restart your terminal session) so the new skill registration is picked up.

Claude Code:

- Claude Code reads repo-local skills from `.claude/skills/debug`.
- If you copied the repo, this folder is present by default.
- No separate install command is needed in this repository.

### 6) Debug

Then start your app in JDWP mode and ask your agent to use the debug skill.

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar your-app.jar
```

---

## How It Fits

```mermaid
flowchart LR
  A[Claude Code / Codex] --> B[mcp-tcp-adapter.js]
  B --> C[Descartes MCP Proxy]
  C --> D[Target JVM via JDWP]
  D --> C
  C --> A
```

Use proxy mode when you want external, agent-driven inspection.
Use embed mode when you want full Descartes capabilities inside your app.

---

## Modes: Which one to start with

| Path | Setup | Best for |
| --- | --- | --- |
| Proxy mode | Minutes | Debugging existing JDWP-enabled JVMs with no application code changes |
| Embedded mode | Requires dependency changes | JShell, profiling, hot reload, and deeper introspection |

---

## What you can do first in proxy mode

- Pause and step through execution at breakpoints or watchpoints.
- Read and evaluate thread snapshots around contention and blocking hotspots.
- Inspect object fields and locals on suspended stack frames.
- Search thread snapshots quickly without redeploying.

---

## Known Restriction

One proxy instance can target one JVM at a time. For multi-JVM workflows, run one proxy per target JVM or pick different ports.

See [doc/restrictions.md](doc/restrictions.md).

---

## Quick tips

- Stop cleanly: end MCP usage, stop proxy with `Ctrl+C`, then stop target JVM.
- Port setup: keep adapter MCP port (`MCP_PORT`) aligned with proxy `--mcp-port`. Configure JDWP host/port separately for the target JVM.
- Non-TTY launch (agent target workloads):  
  `scripts/launch-managed-nontty.sh --name myapp -- java -jar your-app.jar`

---

## Learn more

- [doc/quick-start.md](doc/quick-start.md)
- [doc/adapter.md](doc/adapter.md)
- [doc/debugger.md](doc/debugger.md)
- [doc/how-to-embed.md](doc/how-to-embed.md)
- [doc/debug-skill.md](doc/debug-skill.md)
- [doc/restrictions.md](doc/restrictions.md)
