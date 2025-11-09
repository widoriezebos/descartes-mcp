# Hot Reload

`hot_reload_classes` redefines classes inside the running JVM so you can iterate without restarting. It is backed by:

- `HotReloadService` for orchestration and validation.
- `hotreload.agent.HotReloadAgent` (`premain` and `agentmain`) for instrumentation hooks.
- ASM-based analysers that guard against changes the JVM cannot safely redefine.

## Requirements

- Start the JVM with the shaded Descartes JAR as a Java agent:  
  `java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar -jar target/descartes-mcp-*-jar-with-dependencies.jar`
- Alternatively, attach dynamically using the JDK attach API before invoking the tool.
- Only classes loaded by compatible classloaders can be redefined. JDK modules and bootstrap classes are intentionally skipped.

## What Can Change?

| Allowed | Blocked |
|---------|---------|
| Method body edits | Adding/removing methods |
| Control-flow tweaks inside existing methods | Changing method signatures or modifiers |
| Constant value updates | Adding/removing fields |
| | Changing inheritance or generic signatures |
| | Altering static initialisers |

The analyser (`ClassStructureAnalyzer`) enforces these rules and reports why a class was rejected.

## Calling the MCP Tool

```json
{
  "name": "hot_reload_classes",
  "arguments": {
    "packageFilter": "com.example.app.*",
    "validateOnly": true,
    "force": false
  }
}
```

- `packageFilter` (required) — Glob/glob-star pattern limiting which classes are inspected.
- `validateOnly` — Run compatibility checks without redefining classes.
- `force` — Skip timestamp heuristics; useful when the build tool reuses file modification times.

### Response Snapshot

```json
{
  "status": "success",
  "agentLoaded": true,
  "classesAnalyzed": 12,
  "classesChanged": 4,
  "classesReloaded": 4,
  "skippedClasses": [
    { "name": "com.example.LegacyService", "reason": "signature_changed" }
  ],
  "errors": [],
  "reloadTimeMs": 132
}
```

- `skippedClasses` enumerates incompatibilities (e.g., `signature_changed`, `field_added`, `hierarchy_changed`).
- `errors` surfaces unexpected problems (missing class files, instrumentation failures).
- `agentLoaded: false` means the JVM was not launched or attached with the agent; no reload occurs.

## Recommended Workflow

1. Recompile to produce updated `.class` files (`mvn compile`).
2. Invoke `hot_reload_classes` with `validateOnly: true` to confirm compatibility.
3. Invoke again with `validateOnly` omitted (or set to `false`) to apply the change.
4. Exercise the new behaviour via JShell, integration tests, or the application UI.

## Common Issues

| Symptom | Likely Cause | Resolution |
|---------|--------------|------------|
| `agentLoaded` is `false` | JVM missing `-javaagent` or attach step failed | Start with the agent or use the attach API prior to calling the tool |
| `skippedClasses` with `signature_changed` | Method signature or modifiers changed | Revert the signature or restart the JVM |
| Nothing reloads | `packageFilter` misses loaded classes or build output path differs | Verify filter value and ensure new class files replace the originals |
| Behaviour unchanged after “success” | Class files did not rebuild or multiple classloaders hold older versions | Force recompilation and confirm classloaders via `ClassLoadTracker` logs |

## Testing the Feature

- `mvn test -Phot-reload-tests` runs the dedicated suite. It loads the agent automatically and exercises:
  - Successful reloads
  - Validation failures
  - Concurrent invocations
  - Edge cases such as empty filters or missing artifacts
- Tests rely on bytecode fixtures under `src/test/java/com/bitsapplied/descartes/hotreload`.

## Safety Guidelines

- Restrict MCP access; hot reload combined with JShell allows arbitrary code mutation.
- Apply changes in small batches and validate first to catch incompatible edits early.
- Use the logging integration to monitor reload outcomes; the agent logs through Log4j2.
