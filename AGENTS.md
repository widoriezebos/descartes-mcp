# Repository Guidelines

## Project Structure & Module Organization
Production code lives in `src/main/java/com/bitsapplied/descartes`: `tools/`, `resources/`, `util/`, and the `hotreload/` + `profiler/` subsystems. `example/SimpleMCPServerExample` demonstrates full registration on port 9080. Mirror packages under `src/test/java`, keep fixtures in `src/test/resources`, and store adapter assets in `config/mcp/`.

## Architecture Overview
`MCPServer` routes JSON-RPC requests and shares state through a `Map<String,Object>` context. Tools span JShell sessions, inspection, logging, hot reload, and JFR profiling, while resources expose classpath, metrics, thread dumps, and MBeans. Hot reload uses the agent plus `HotReloadService`; profiling manages recordings, hotspots, call trees, and flame graphs.

## Build, Test & Development Commands
- Build: `mvn clean compile` or `mvn clean package` for the shaded agent JAR (add `-Peclipse-m2e` when exporting to Eclipse).
- Run (no agent): `mvn exec:java` on port 9080; append `-Ddescartes.continuous=true` for background mode.
- Run (with agent): `mvn compile exec:exec -Prun-with-agent`, `./run-with-hotreload.sh`, or `java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar -jar ...`.
- Test: `mvn test` skips concurrency and hot-reload suites; enable `-Pconcurrency-tests`, `-Phot-reload-tests`, or `-Pall-tests` when needed—the agent profiles assemble the shaded JAR first.
- Adapter: `node config/mcp/mcp-tcp-adapter.js` starts the TCP adapter for Claude Desktop and other clients.

## Coding Style & Naming Conventions
Target Java 23 (min 16) with two-space indentation, `UpperCamelCase` types, `lowerCamelCase` members, and `UPPER_SNAKE_CASE` constants. Use `var` sparingly, group imports, favor parameterized Log4j calls, and extend `MCPTool` when adding features—register through `MCPServer` and use the shared context map.

## Testing Guidelines
- JUnit 5 with Mockito and AssertJ backs the suite (`DescartesTestSuite`). Name tests `*Test.java`, run `mvn test`, and enable the concurrency, hot-reload, or all-tests profiles when relevant; hot-reload runs require the assembled agent JAR.
- Always clear Surefire fork leftovers before any Maven test run. Stale `surefirebooter` JVMs cause port/file-lock conflicts and hanging runs. Clean them with `pkill -9 -f surefirebooter 2>/dev/null` (safe even when nothing is running); check with `ps aux | grep surefirebooter` or `lsof -i :9080` if a run behaves oddly.
- Combine cleanup with suite execution, e.g.:
```bash
pkill -9 -f surefirebooter 2>/dev/null; mvn test
pkill -9 -f surefirebooter 2>/dev/null; mvn test -Pconcurrency-tests
pkill -9 -f surefirebooter 2>/dev/null; mvn test -Phot-reload-tests
```

## Commit & Pull Request Guidelines
Use short, imperative commit subjects (`Add profiler`, `Update README`). PRs should link issues, summarize behavior changes, list manual test runs (with profiles), and attach logs or screenshots for adapter or profiling changes. Update `README.md`, `TOOLS.md`, or sibling guides when adding user-facing work.

## Security & Configuration Tips
Keep JShell and hot reload in dev-only environments; never expose agent-enabled builds or the adapter to untrusted networks. Configure Log4j2 with the `InMemoryAppender` (copy settings from `src/test/resources/log4j2.properties`). Store adapter secrets off-repo, document host paths in `config/mcp/mcpservers.json`, and skip `-javaagent` for production deploys.

## MCP Client & Adapter
`config/mcp/` holds `mcp-tcp-adapter.js`, `mcpservers.json`, the adapter README, and validation scripts. Update the absolute path in `mcpservers.json`, start the server, then launch the adapter—it auto-reconnects, buffers during outages, and exposes backoff/timeout tuning through environment variables.
