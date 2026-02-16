# AI Assistant File Templates (Copy/Paste)

Use this page when integrating Descartes MCP into any project.

These templates are intentionally repo-agnostic: no codebase-specific paths, class names, or build commands.

## 1. Canonical `AGENTS.md`

Copy this as the main instruction file:

```md
# AI Assistant Runtime Debugging Contract (Descartes MCP)

This project uses Descartes MCP for runtime inspection and debugging.
When Descartes is available, prefer runtime evidence before proposing code changes.

## Operating Rules

1. Base conclusions on observed runtime behavior whenever possible.
2. Start with low-cost inspection, then go deeper:
   - session/status
   - threads/events
   - variables/stack/evaluate
3. Follow a deterministic debugger flow:
   - start session
   - set breakpoints
   - trigger workload
   - wait for breakpoint event
   - inspect
   - resume
   - stop session
4. Treat tool timeout and transport timeout as separate concerns.
   - Tool timeout: request-level `timeout_ms`
   - Transport timeout: adapter/request timeout, must be high enough for expected waits
5. If a wait times out, retry with `since_sequence` from the latest known event sequence.
6. Restrict REPL/hot-reload/debug capabilities to trusted development or test environments.
7. Do not expose debugger ports or MCP endpoints to untrusted networks.

## Process Expectations

1. Separate observed facts from inferences.
2. State uncertainty explicitly.
3. Recommend the next concrete action.
```

## 2. Compatibility `CLAUDE.md`

Copy this as-is when a tool expects a `CLAUDE.md` file:

```md
# CLAUDE.md

Canonical assistant instructions are in `AGENTS.md`.
If this file and `AGENTS.md` differ, follow `AGENTS.md`.
```

## 3. Compatibility `GEMINI.md`

Copy this as-is when a tool expects a `GEMINI.md` file:

```md
# GEMINI.md

Canonical assistant instructions are in `AGENTS.md`.
If this file and `AGENTS.md` differ, follow `AGENTS.md`.
```

## 4. Recommended Rollout

1. Add the canonical `AGENTS.md` first.
2. Add `CLAUDE.md` and `GEMINI.md` wrappers only if your tools require those filenames.
3. Keep all behavior rules in one place (`AGENTS.md`) to prevent drift.
