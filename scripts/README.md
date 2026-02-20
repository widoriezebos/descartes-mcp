# Scripts

This directory contains operational helper scripts for the Descartes repository.

Design goals for scripts in this folder:
- Make repeated operational workflows deterministic.
- Hide fragile shell details behind one stable command.
- Return clear success/failure output that agents and humans can rely on.
- Be safe by default (explicit PID/log management, fail-fast behavior).

## Related Skill Scripts

The debugger skill in `.claude/skills/debug` includes companion scripts that depend on the canonical launcher in this folder:
- `.claude/skills/debug/scripts/preflight.sh` validates launcher availability after copy/install.
- `.claude/skills/debug/scripts/install-codex-link.sh` installs a symlink into `$CODEX_HOME/skills` for Codex CLI without copying.
- `.claude/skills/debug/scripts/launch-managed-nontty.sh` is a thin wrapper over `scripts/launch-managed-nontty.sh`.

See `doc/debug-skill.md` for copy/symlink/rename workflows.

## Script Index

| Script | Why it exists | Typical use |
|---|---|---|
| `launch-managed-nontty.sh` | Start a process as a supervised child in non-TTY mode (no detach). | Default launch for JDWP debug targets used by agents, with robust process lifecycle and signal forwarding. Also bundled in `.claude/skills/debug/scripts/` for skill portability. |
| `run-with-hotreload.sh` | Start the Descartes MCP server with Java agent flags and predictable startup behavior for class hot reload workflows. | Run the embedded MCP server with hot reload support during local development. |
| `run-remote-proxy.sh` | Start the Descartes MCP remote debug proxy with consistent defaults, logging, and auto-build behavior. | Run proxy mode from local source changes (development fallback). |
| `run-remote-proxy-from-maven.sh` | Pull the published proxy shaded JAR from Maven repositories and launch it directly. | Default proxy launcher for released versions (no local build required). |

## `launch-managed-nontty.sh`

### Name

`launch-managed-nontty.sh`

### Why it exists

For JDWP debugging, the most reliable launch mode is a supervised target process running on non-TTY pipes:
- Avoids PTY lifecycle issues that can terminate the debug target unexpectedly.
- Keeps the target bound to an explicit parent process rather than daemonized background behavior.
- Allows deterministic signal forwarding and clean exit status propagation.

This is the default launcher for agent-driven debug target startup.

### How it works

1. Validates that stdin/stdout/stderr are all non-TTY.
2. Resolves default PID file path (`.pids/<name>.pid`).
3. Starts the command as a supervised child (uses `setsid` when available).
4. Writes PID metadata and reports startup success.
5. Forwards `TERM`/`INT`/`HUP`/`QUIT` to the child.
6. Waits for the child and exits with the same exit code.

### Usage

```bash
scripts/launch-managed-nontty.sh [options] -- <command> [args...]
```

### Options

- `--name <name>`: Logical name used for default PID path. Default: `debug-target`
- `--pid-file <path>`: Custom PID file path
- `--cwd <dir>`: Working directory for launched command
- `--json`: Emit machine-readable startup metadata
- `-h`, `--help`: Show usage

### Examples

Launch a JDWP target as supervised non-TTY process:

```bash
scripts/launch-managed-nontty.sh \
  --name myapp-debug-target \
  -- java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
     -jar your-application.jar
```

Launch with explicit working directory:

```bash
scripts/launch-managed-nontty.sh \
  --name myapp \
  --cwd /path/to/app \
  -- ./start.sh
```

### Notes

- For agent/tool-based launches, run without PTY (`tty=false`).
- This script is intentionally not detached and not `nohup`-based.

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
Use this script when validating local source changes; for released versions, prefer `run-remote-proxy-from-maven.sh`.

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
- Use `scripts/launch-managed-nontty.sh` for default agent-driven debug target launch.

## `run-remote-proxy-from-maven.sh`

### Name

`run-remote-proxy-from-maven.sh`

### Why it exists

For release validation and operational usage, it is useful to run proxy mode directly from the published Maven artifact instead of the local workspace build.
This is the recommended default launcher for proxy mode.
This script standardizes that flow:
- Pull the shaded proxy artifact classifier from Maven repositories.
- Resolve the exact JAR path in the local Maven repository.
- Start the proxy with stable JVM flags.

### How it works

1. Resolves artifact coordinates (`groupId`, `artifactId`, `version`, `classifier`).
2. Downloads the artifact via `maven-dependency-plugin:get`.
3. Locates the downloaded JAR in the configured Maven local repository.
4. Starts proxy mode with:
- `--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED`
- `-jar <artifact-with-classifier>`
5. Passes remaining arguments directly to `MCPRemoteDebugProxy`.

### Usage

```bash
scripts/run-remote-proxy-from-maven.sh [wrapper-options] [proxy-args...]
```

### Examples

Run released proxy artifact with defaults:

```bash
scripts/run-remote-proxy-from-maven.sh
```

Run with explicit target:

```bash
scripts/run-remote-proxy-from-maven.sh --version 1.0.0 --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
```

Run with mirrored logs:

```bash
scripts/run-remote-proxy-from-maven.sh --log-file logs/descartes-proxy.log --auto-discover
```

### Notes

- `--version` is optional when running from a workspace with `pom.xml` (script auto-detects `project.version`).
- Pass `--version <version>` to pin a specific released artifact.
- `--log-file <path>` mirrors output via `tee`.
- If artifact resolution fails and local source is available, the script falls back to `mvn -DskipTests install` (disable with `--no-local-build-fallback`).
- Defaults: `groupId=com.bitsapplied.descartes`, `artifactId=descartes-mcp`, `classifier=proxy`.
