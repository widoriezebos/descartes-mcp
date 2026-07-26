# Repository Guidelines

Canonical AI assistant instructions for this repository. Compatibility entry points (`CLAUDE.md`, `GEMINI.md`) should refer to this file rather than duplicate policy.

## Project Structure & Module Organization
Production code lives in `src/main/java/com/bitsapplied/descartes`: `tools/`, `resources/`, `util/`, and the `hotreload/` + `profiler/` subsystems. `example/SimpleMCPServerExample` demonstrates full registration on port 9080. Mirror packages under `src/test/java`, keep fixtures in `src/test/resources`, and store adapter assets in `config/mcp/`.

## Architecture Overview
`MCPServer` routes JSON-RPC requests and shares state through a `Map<String,Object>` context. Tools span JShell sessions, inspection, logging, hot reload, and JFR profiling, while resources expose classpath, metrics, thread dumps, and MBeans. Hot reload uses the agent plus `HotReloadService`; profiling manages recordings, hotspots, call trees, and flame graphs.

## Build, Test & Development Commands
- Build: `mvn clean compile` or `mvn clean package` for the shaded agent JAR (add `-Peclipse-m2e` when exporting to Eclipse).
- Run (no agent): `mvn exec:java` on port 9080; append `-Ddescartes.continuous=true` for background mode.
- Run (with agent): `mvn compile exec:exec -Prun-with-agent`, `./scripts/run-with-hotreload.sh`, or `java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar -jar ...`.
- Test: `mvn test` skips concurrency and hot-reload suites; enable `-Pconcurrency-tests`, `-Phot-reload-tests`, or `-Pall-tests` when needed—the agent profiles assemble the shaded JAR first.
- Adapter: `node config/mcp/mcp-tcp-adapter.js` starts the TCP adapter for Claude Code and other clients.
- Remote debug target launch (required for agents): `scripts/launch-managed-nontty.sh --name <name> -- <command>`. Launch without a PTY (`tty=false`) and never run debug targets in foreground/TTY sessions.

## Coding Style & Naming Conventions
Target Java 21 (min 16) with two-space indentation, `UpperCamelCase` types, `lowerCamelCase` members, and `UPPER_SNAKE_CASE` constants. Use `var` sparingly, group imports, favor parameterized Log4j calls, and extend `MCPTool` when adding features—register through `MCPServer` and use the shared context map.

## Testing Guidelines
- JUnit 5 with Mockito and AssertJ backs the suite (`DescartesTestSuite`). Name tests `*Test.java`, run `mvn test`, and enable the concurrency, hot-reload, or all-tests profiles when relevant; hot-reload runs require the assembled agent JAR.
- Always clear Surefire fork leftovers before any Maven test run. Stale `surefirebooter` JVMs cause port/file-lock conflicts and hanging runs. Clean them with `pkill -9 -f 'descartes-mcp/target/surefire' 2>/dev/null` (safe even when nothing is running); check with `ps aux | grep surefirebooter` or `lsof -i :9080` if a run behaves oddly. Never use a bare `pkill -9 -f surefirebooter` — other sessions on this machine run their own Maven test forks concurrently, and the repo-scoped pattern only matches forks whose booter jar lives under this repo's `target/`.
- Combine cleanup with suite execution, e.g.:
```bash
pkill -9 -f 'descartes-mcp/target/surefire' 2>/dev/null; mvn test
pkill -9 -f 'descartes-mcp/target/surefire' 2>/dev/null; mvn test -Pconcurrency-tests
pkill -9 -f 'descartes-mcp/target/surefire' 2>/dev/null; mvn test -Phot-reload-tests
```
- Full `mvn -q test` takes ~9 minutes and produces little console output; let it run to completion instead of assuming the silence means a hang.

## Commit & Pull Request Guidelines
Use short, imperative commit subjects (`Add profiler`, `Update README`). PRs should link issues, summarize behavior changes, list manual test runs (with profiles), and attach logs or screenshots for adapter or profiling changes. Update `README.md`, `TOOLS.md`, or sibling guides when adding user-facing work.

## Releasing
A version bump is **not** a release. `.github/workflows/maven-publish.yml` triggers only on `release: [created]`, so without a published GitHub Release nothing reaches Maven Central or GitHub Packages — the artifacts exist only in the local `target/`. Versions 1.0.2 and 1.0.3 were both bumped and merged without this step and stayed unpublished; 1.0.2 was skipped entirely when 1.0.3 rolled over it.

Run every step; do not stop after the bump.
1. Bump the version everywhere. `pom.xml` is authoritative, but the string is also hardcoded in `MCPServer.serverVersion`, `SimpleMCPServerExample`, `DebuggerWorkflowExample`, `BuildInfo` Javadoc, `HotReloadServiceTest`, `scripts/run-remote-proxy-from-maven.sh`, `scripts/README.md`, `doc/how-to-embed.md`, and `doc/MCPRemoteDebugProxy.md`. Verify with `grep -rn '<old-version>' --include='*.java' --include='*.md' --include='*.sh' --include='*.xml' . | grep -v /target/` — it must return nothing.
2. Verify the build: `pkill -9 -f 'descartes-mcp/target/surefire' 2>/dev/null; mvn -B -ntp clean package`. Maven Central releases are immutable, so never tag a build you have not run green.
3. Commit and push to `main`.
4. Tag the release commit and push the tag: `git tag v<version> && git push origin v<version>`. Tags are `v`-prefixed (`v1.0.1`), the pom version is not (`1.0.1`).
5. Publish the GitHub Release — this is the step that triggers publishing: `gh release create v<version> --title v<version> --generate-notes`.
6. Confirm the workflow ran and both deploy steps passed: `gh run list --workflow=maven-publish.yml --limit 1`. A green run means Sonatype Central plus GitHub Packages; Central itself can take up to ~30 minutes to index.

Check for drift with `gh release list` against `git log` whenever a version bump lands — an untagged bump means an unpublished release.

## Security & Configuration Tips
Keep JShell and hot reload in dev-only environments; never expose agent-enabled builds or the adapter to untrusted networks. Configure Log4j2 with appropriate appenders (see `src/test/resources/log4j2.properties` for examples). Store adapter secrets off-repo, document host paths in `config/mcp/mcpservers.json`, and skip `-javaagent` for production deploys.

## MCP Client & Adapter
`config/mcp/` holds `mcp-tcp-adapter.js`, `mcpservers.json`, the adapter README, and validation scripts. Update the absolute path in `mcpservers.json`, start the server, then launch the adapter—it auto-reconnects, buffers during outages, and exposes backoff/timeout tuning through environment variables.

## Debug Skill Distribution
The canonical Descartes debugger skill lives at `.agents/skills/descartes-debug`.
- Codex and Gemini CLI discover the canonical `.agents/skills/` tree directly.
- Claude Code discovers the same physical skill through the checked-in `.claude/skills/descartes-debug` symlink.
- Keep shared workflow content in the canonical skill only. Product-specific metadata may live under its `agents/` directory; do not create copied skill trees.
- See `doc/debug-skill.md` for the layout, validation, and copy instructions.
