# Scripts

This directory contains operational helper scripts for the Descartes repository.

Design goals for scripts in this folder:
- Make repeated operational workflows deterministic.
- Hide fragile shell details behind one stable command.
- Return clear success/failure output that agents and humans can rely on.
- Be safe by default (explicit PID/log management, fail-fast behavior).

## Script Index

| Script | Why it exists | Typical use |
|---|---|---|
| `launch-detached.sh` | Start a process detached from the current terminal/PTY, with PID and log tracking. | Launch a JVM target for JDWP debugging so it does not die when an agent session/PTY is closed. |
| `run-with-hotreload.sh` | Start the Descartes MCP server with Java agent flags and predictable startup behavior for class hot reload workflows. | Run the embedded MCP server with hot reload support during local development. |
| `run-remote-proxy.sh` | Start the Descartes MCP remote debug proxy with consistent defaults, logging, and auto-build behavior. | Run a local proxy that bridges MCP clients to a JDWP target process. |

## `launch-detached.sh`

### Name

`launch-detached.sh`

### Why it exists

When debugging, processes launched in interactive sessions can terminate unexpectedly when the controlling terminal/PTY ends. This script provides a consistent detached-launch contract:
- Starts the command in the background via `nohup` with stdin detached.
- Persists process identity in a PID file.
- Persists stdout/stderr in a log file.
- Optionally waits for a TCP port to become reachable before returning success.
- Fails early with useful diagnostics if startup fails.

### How it works

1. Parses options and validates inputs.
2. Resolves default paths:
- PID file: `.pids/<name>.pid`
- Log file: `logs/<name>.log`
3. If PID file already points to a running process:
- Fails by default.
- Stops existing process first when `--replace` is provided.
4. Launches command with:
- `nohup <command> > <log> 2>&1 < /dev/null &`
5. Writes PID to PID file.
6. Verifies the process is still alive after startup.
7. If `--wait-port` is set, polls until the port is ready (or timeout/failure).
8. Prints normalized launch metadata and exits `0` on success.

### Usage

```bash
scripts/launch-detached.sh [options] -- <command> [args...]
```

### Options

- `--name <name>`: Logical name used for default PID/log paths. Default: `debug-target`
- `--log-file <path>`: Custom log file path
- `--pid-file <path>`: Custom PID file path
- `--wait-port <port>`: Wait until this TCP port is reachable
- `--wait-host <host>`: Host for `--wait-port`. Default: `127.0.0.1`
- `--wait-timeout-sec <sec>`: Max time to wait for port readiness. Default: `30`
- `--wait-interval-sec <sec>`: Poll interval for readiness checks. Default: `0.2`
- `--cwd <dir>`: Working directory for the launched command
- `--replace`: Stop existing PID from PID file before launch
- `-h`, `--help`: Show usage

### Examples

Launch any long-running process detached:

```bash
scripts/launch-detached.sh --name demo -- bash -c 'sleep 600'
```

Launch a JVM debug target and wait for JDWP port `5005`:

```bash
scripts/launch-detached.sh \
  --name myapp-debug-target \
  --wait-port 5005 \
  --replace \
  -- java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-application.jar
```

Launch with explicit working directory and output files:

```bash
scripts/launch-detached.sh \
  --name myapp \
  --cwd /path/to/app \
  --pid-file /tmp/myapp.pid \
  --log-file /tmp/myapp.log \
  --replace \
  -- ./start.sh
```

### Output

On success:
- Prints script name, PID, PID file, log file, and port readiness details (if requested).
- Exit code `0`.

On failure:
- Prints a concise error message.
- For startup failure, prints tail of the log file to aid diagnosis.
- Exit code is non-zero.

### Notes

- Run scripts from the repository root for predictable relative paths.
- If your command exits quickly by design, this script treats that as startup failure.
- For debugging workflows, use this script for target JVMs to avoid terminal-coupled process lifetimes.

## `run-with-hotreload.sh`

### Name

`run-with-hotreload.sh`

### Why it exists

Running the embedded MCP server in hot-reload mode requires specific JVM flags and a correctly built shaded JAR.
This script provides one stable command for that workflow so local development sessions are easy to start and repeat.

### How it works

1. Looks for the newest shaded JAR in `target/descartes-mcp-*-jar-with-dependencies.jar`.
2. Builds a shaded JAR (`mvn clean package -DskipTests -q`) if no JAR is found.
3. Chooses an available MCP port from `9080` through `9084` (first free port wins).
4. Starts Java with:
- `-javaagent:<jar>`
- `-XX:+EnableDynamicAgentLoading`
- `--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED`
- `-Ddescartes.mcp.port=<detectedPort>`
5. Executes the same shaded JAR as the application (`-jar <jar>`) and forwards any CLI args.

### Usage

```bash
scripts/run-with-hotreload.sh [app-args...]
```

### Examples

Start in the default mode:

```bash
scripts/run-with-hotreload.sh
```

Pass through an application argument:

```bash
scripts/run-with-hotreload.sh --continuous
```

### Output

On success:
- Prints selected JAR path and selected MCP port.
- Runs in the foreground as the active server process.

On failure:
- Prints build/startup error and exits non-zero.

### Notes

- This script is intended for local development where embedded mode and hot-reload tools are needed.
- Run it from the repository root so relative paths like `target/` resolve correctly.

## `run-remote-proxy.sh`

### Name

`run-remote-proxy.sh`

### Why it exists

Starting the MCP remote debug proxy manually is error-prone because it requires:
- Finding the correct shaded JAR.
- Rebuilding when sources changed.
- Using consistent default ports.
- Capturing startup/runtime output in a stable log file.

This script standardizes that workflow so agents and humans can start the proxy with one command and predictable behavior.

### How it works

1. Determines repository root using Git metadata.
2. Resolves log file path (default: `logs/descartes-proxy.log`).
3. Parses wrapper flags (`--log-file`, `--rebuild`).
4. Uses default proxy args when no args are provided:
- `--jdwp-host localhost --jdwp-port 5005 --mcp-port 9090`
5. Checks for latest shaded JAR in `target/descartes-mcp-*-jar-with-dependencies.jar`.
6. Builds a new shaded JAR (`mvn clean package -DskipTests -q`) when:
- No shaded JAR exists.
- `--rebuild` is set.
- `src/main/java`, `src/main/resources`, or `pom.xml` are newer than the JAR.
7. Starts `com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy` with Java `--add-opens` flags and forwards remaining CLI args to the proxy.
8. Mirrors all output to both terminal and log file via `tee`.

### Usage

```bash
scripts/run-remote-proxy.sh [wrapper-options] [proxy-args...]
```

### Wrapper options

- `--log-file <path>`: Write mirrored output to a specific file (relative paths resolve from repo root).
- `--rebuild`: Force shaded JAR rebuild before launch.

All other arguments are passed through unchanged to `MCPRemoteDebugProxy`.

### Examples

Start with defaults (`localhost:5005` -> MCP `:9090`):

```bash
scripts/run-remote-proxy.sh
```

Explicit JDWP target and MCP port:

```bash
scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
```

Auto-discovery mode:

```bash
scripts/run-remote-proxy.sh --auto-discover
```

Force rebuild and custom log path:

```bash
scripts/run-remote-proxy.sh --rebuild --log-file logs/descartes-proxy.log --auto-discover
```

### Output

On success:
- Prints resolved repo path, effective args, and selected log file.
- Continues running in the foreground as the active proxy process.

On failure:
- Prints build or launch error details and exits non-zero.

### Notes

- This script runs the proxy in the foreground by design.
- Use `scripts/launch-detached.sh` when you need detached/background lifecycle management.
